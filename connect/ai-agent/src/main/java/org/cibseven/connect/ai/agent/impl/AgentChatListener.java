/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.cibseven.connect.ai.agent.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiResponsesChatRequestParameters;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import org.cibseven.bpm.engine.impl.context.BpmnExecutionContext;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.identity.Authentication;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.variable.Variables;

import org.cibseven.connect.ai.agent.AgentConnectorConstants;

/**
 * {@link ChatModelListener} that emits one structured audit event per LLM
 * request, response, or error. The event payload carries the fields required
 * by EU AI Act Art. 12 (record-keeping) and Art. 26 (deployer obligations):
 * run identity ({@code runId} + {@code eventSeq}), model identity
 * ({@code provider}, {@code model}, {@code endpoint}), process correlation
 * ({@code processInstanceId}, {@code processDefinitionKey/Id},
 * {@code businessKey}, {@code tenantId}, {@code executionId},
 * {@code activityId}), caller identity ({@code userId}, {@code groupIds}),
 * outcome ({@code finishReason}, {@code durationMs}, {@code errorClass} +
 * short stack on failures), and per-tool side-effects published by
 * {@link ProcessStarterTool} (resulting {@code processInstanceId} +
 * executing principal).
 *
 * <p>The chat-log variable name is derived automatically from the current
 * activity: {@code AGENT_CONNECTOR_LOG_PREFIX + activityId}. When a variable
 * with that name already exists at construction time, its content is decoded
 * so subsequent events accumulate on top instead of overwriting earlier
 * history. When the listener is invoked without a {@link BpmnExecutionContext}
 * (e.g. unit tests), it just collects events in memory and never touches the
 * engine.
 *
 * <h3>Content redaction</h3>
 * Setting the {@value #REDACT_CONTENT_PROPERTY} system property to
 * {@code true} replaces every {@code messages[].content} string with a
 * JSON-encoded marker carrying a SHA-256 hash and the original character
 * length, to satisfy GDPR-conservative deployments at the cost of
 * frontend readability. The default is unredacted (preserves the existing
 * webclient JSON contract).
 */
class AgentChatListener implements ChatModelListener {

  private static final Logger LOG = LoggerFactory.getLogger(AgentChatListener.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final TypeReference<List<Map<String, Object>>> EVENT_LIST_TYPE =
      new TypeReference<List<Map<String, Object>>>() {};

  /** Number of stack frames retained per error event. */
  private static final int ERROR_STACK_DEPTH = 5;

  /**
   * Event schema version stamped on every emitted event. Increment when adding
   * or removing top-level fields so downstream consumers (Vue webclient, future
   * Kafka sink, SAP integration) can detect and adapt to changes.
   */
  static final int SCHEMA_VERSION = 1;

  /**
   * Per-request attribute key used to stash {@code System.nanoTime()} on
   * {@link #onRequest} so {@link #onResponse} / {@link #onError} can compute a
   * {@code durationMs}. Anchored on a sentinel object so the key collides with
   * nothing else stuffed into {@code ctx.attributes()} by other listeners.
   */
  private static final Object KEY_START_NANOS = new Object() {
    @Override public String toString() {
      return "cibseven-ai-agent.startNanos";
    }
  };

  /**
   * System property that, when set to {@code true}, redacts message content
   * (replaces each {@code messages[].content} with a JSON-encoded
   * {@code {hash,length,redacted}} marker). Default {@code false}.
   */
  static final String REDACT_CONTENT_PROPERTY = "cibseven.connect.ai-agent.redactContent";

  // ── run identity ─────────────────────────────────────────────────────────
  private final String runId;
  private final AtomicInteger eventSeq = new AtomicInteger();

  // ── model identity (nullable when constructed without context) ───────────
  private final String configuredModel;
  private final String endpoint;

  // ── BPMN correlation (captured at construction time on the engine thread) ─
  private final String processInstanceId;
  private final String processDefinitionId;
  private final String processDefinitionKey;
  private final String businessKey;
  private final String tenantId;
  private final String executionId;
  private final String activityId;

  // ── caller (captured at construction time) ───────────────────────────────
  private final String userId;
  private final List<String> groupIds;

  // ── persistence ──────────────────────────────────────────────────────────
  private final String variableName;
  private final List<Map<String, Object>> events = new ArrayList<>();
  private boolean flagWritten = false;

  // ── last-response state (for Art. 50(2) AI-output marker) ────────────────
  private volatile String lastProvider;
  private volatile String lastModel;
  private volatile String lastResponseId;
  private volatile String lastResponseTimestamp;

  /**
   * Tool-side-effect records pushed by tools (e.g. {@link ProcessStarterTool})
   * while a model call is in flight. Drained onto the next emitted event so
   * the audit log carries the resulting {@code processInstanceId} and
   * executing principal alongside the tool call that triggered it.
   */
  private final List<Map<String, Object>> pendingToolSideEffects = new ArrayList<>();

  AgentChatListener() {
    this(null, null);
  }

  AgentChatListener(String configuredModel, String endpoint) {
    this.runId = UUID.randomUUID().toString();
    this.configuredModel = configuredModel;
    this.endpoint = endpoint;

    ExecutionEntity execution = currentExecution();
    if (execution != null) {
      this.activityId = execution.getActivityId();
      this.processInstanceId = execution.getProcessInstanceId();
      this.processDefinitionId = execution.getProcessDefinitionId();
      this.processDefinitionKey = safeRead(execution::getProcessDefinitionKey, "processDefinitionKey");
      this.businessKey = safeRead(execution::getBusinessKey, "businessKey");
      this.tenantId = safeRead(execution::getTenantId, "tenantId");
      this.executionId = execution.getId();
    } else {
      this.activityId = null;
      this.processInstanceId = null;
      this.processDefinitionId = null;
      this.processDefinitionKey = null;
      this.businessKey = null;
      this.tenantId = null;
      this.executionId = null;
    }

    Authentication auth = ProcessStarterToolContext.getAuthentication();
    if (auth != null) {
      this.userId = auth.getUserId();
      List<String> g = auth.getGroupIds();
      this.groupIds = (g == null) ? null : Collections.unmodifiableList(new ArrayList<>(g));
    } else {
      this.userId = null;
      this.groupIds = null;
    }

    this.variableName = (activityId != null && !activityId.isEmpty())
        ? AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX + activityId
        : null;
    if (this.variableName != null) {
      loadExistingEvents();
    }
    // EU AI Act Art. 12(3) audit trail requires caller identity. Silent omission
    // is a compliance smell, so when the listener constructs inside a BPMN
    // execution context but no authentication was resolved upstream
    // (AgentConnectorImpl tries the engine IdentityService and the
    // process-instance starter; both can fail e.g. for system-initiated starts),
    // emit a one-time WARN per runId.
    if (execution != null && (userId == null || userId.isEmpty())) {
      LOG.warn("EU AI Act Art. 12(3): no caller identity resolved for runId={} "
          + "(processInstanceId={}, activityId={}). Events will carry "
          + "userIdSource=\"missing\" so the absence is explicit.",
          runId, processInstanceId, activityId);
    }
  }

  /** Package-private view of the captured events, for tests. */
  List<Map<String, Object>> events() {
    return events;
  }

  /** Derived variable name, or {@code null} when persistence is disabled. */
  String variableName() {
    return variableName;
  }

  /** UUID identifying this listener instance. Exposed for tests / tool linkage. */
  String runId() {
    return runId;
  }

  /**
   * Records a side effect produced by a tool call (e.g. the
   * {@code processInstanceId} started by {@link ProcessStarterTool} and the
   * principal under which it ran). The record is held until the next event is
   * emitted, then merged onto that event under {@code toolSideEffects}.
   *
   * <p>Provides the Art. 14 evidence trail for autonomous agent actions.
   */
  void recordToolSideEffect(Map<String, Object> sideEffect) {
    if (sideEffect == null || sideEffect.isEmpty()) {
      return;
    }
    pendingToolSideEffects.add(new LinkedHashMap<>(sideEffect));
  }

  private static ExecutionEntity currentExecution() {
    BpmnExecutionContext ctx = Context.getBpmnExecutionContext();
    if (ctx == null) {
      return null;
    }
    return ctx.getExecution();
  }

  /**
   * Reads a property from the current execution entity, swallowing any
   * runtime failure (e.g. lazy-init blowing up because the entity is detached
   * during a test) so listener construction never aborts the connector run.
   */
  private static <T> T safeRead(java.util.function.Supplier<T> reader, String field) {
    try {
      return reader.get();
    } catch (RuntimeException e) {
      LOG.debug("Could not read '{}' from current execution: {}", field, e.toString());
      return null;
    }
  }

  private void loadExistingEvents() {
    ExecutionEntity execution = currentExecution();
    if (execution == null) {
      return;
    }
    Object existing = execution.getVariable(variableName);
    if (existing == null) {
      return;
    }
    String json = existing.toString();
    if (json.isEmpty()) {
      return;
    }
    try {
      List<Map<String, Object>> previous = MAPPER.readValue(json, EVENT_LIST_TYPE);
      events.addAll(previous);
      // Continue numbering after the previous tail so eventSeq is monotonic
      // across resumed runs.
      eventSeq.set(previous.size());
    } catch (JsonProcessingException e) {
      LOG.warn("Failed to decode previous chat log from variable '{}'; starting with an empty log.",
          variableName, e);
    }
  }

  private void persistEvents() {
    if (variableName == null) {
      return;
    }
    String chatLog;
    try {
      chatLog = MAPPER.writeValueAsString(events);
    } catch (JsonProcessingException e) {
      LOG.error("Failed to serialise chatLog events to JSON; skipping update of '{}'.",
          variableName, e);
      return;
    }
    ExecutionEntity execution = currentExecution();
    if (execution == null) {
      LOG.warn("Cannot update '{}' chat log variable: no BpmnExecutionContext on the current "
          + "thread (connector invoked outside an engine command context).", variableName);
      return;
    }
    // Default Java serialization is always registered (unlike Spin/Jackson) and stores
    // in ACT_GE_BYTEARRAY, so it bypasses the VARCHAR(4000) limit.
    Object value = Variables.objectValue(chatLog).create();
    execution.setVariable(variableName, value);
    if (!flagWritten) {
      // Flag that indicates the execution of the agent connector in the process
      execution.setVariable(AgentConnectorConstants.AGENT_CONNECTOR_FLAG_VARIABLE_NAME, true);
      flagWritten = true;
    }
  }

  // ── ChatModelListener callbacks ──────────────────────────────────────────

  @Override
  public void onRequest(ChatModelRequestContext ctx) {
    ChatRequest req = ctx.chatRequest();
    Map<String, Object> event = newEvent("request", ctx.modelProvider(), req);
    putModelParams(event, req);

    // Track per-request start time so onResponse / onError can compute duration.
    ctx.attributes().put(KEY_START_NANOS, System.nanoTime());

    List<Map<String, Object>> messages = new ArrayList<>();
    for (ChatMessage msg : req.messages()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("role", msg.type().toString());
      m.put("content", extractContent(msg));
      if (msg instanceof ToolExecutionResultMessage) {
        m.put("toolName", ((ToolExecutionResultMessage) msg).toolName());
        m.put("toolCallId", ((ToolExecutionResultMessage) msg).id());
      }
      messages.add(m);
      LOG.debug("[{}] {}", msg.type(), msg);
    }
    event.put("messages", messages);

    List<ToolSpecification> toolSpecs = req.parameters().toolSpecifications();
    if (toolSpecs != null && !toolSpecs.isEmpty()) {
      List<String> tools = toolSpecs.stream()
          .map(ToolSpecification::name)
          .collect(Collectors.toList());
      event.put("tools", tools);
      LOG.debug("Available tools: {}", tools);
    }
    appendEvent(event);
  }

  @Override
  public void onResponse(ChatModelResponseContext ctx) {
    AiMessage aiMsg = ctx.chatResponse().aiMessage();
    Map<String, Object> event = newEvent("response", ctx.modelProvider(), ctx.chatRequest());
    this.lastResponseTimestamp = (String) event.get("timestamp");
    putDuration(event, ctx.attributes());

    if (aiMsg.hasToolExecutionRequests()) {
      List<Map<String, Object>> toolCalls = new ArrayList<>();
      for (ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", req.id());
        call.put("name", req.name());
        call.put("arguments", req.arguments());
        toolCalls.add(call);
        LOG.debug("TOOL CALL: {}({})", req.name(), req.arguments());
      }
      event.put("toolCalls", toolCalls);
    } else {
      event.put("answer", aiMsg.text());
      LOG.debug("FINAL ANSWER: {}", aiMsg.text());
    }

    ChatResponseMetadata metadata = ctx.chatResponse().metadata();
    if (metadata != null) {
      FinishReason finish = metadata.finishReason();
      if (finish != null) {
        event.put("finishReason", finish.name());
      }
      String responseModel = metadata.modelName();
      if (responseModel != null && !responseModel.isEmpty()) {
        // Provider-reported snapshot/version supersedes the configured model
        // (e.g. "gpt-4o-2024-08-06" vs request's "gpt-4o").
        event.put("model", responseModel);
        this.lastModel = responseModel;
      } else {
        this.lastModel = (String) event.get("model");
      }
      String responseId = metadata.id();
      if (responseId != null && !responseId.isEmpty()) {
        event.put("responseId", responseId);
        this.lastResponseId = responseId;
      }
    } else {
      this.lastModel = (String) event.get("model");
    }
    if (ctx.modelProvider() != null) {
      this.lastProvider = ctx.modelProvider().name();
    }

    TokenUsage usage = ctx.chatResponse().tokenUsage();
    if (usage != null) {
      Map<String, Object> tokens = new LinkedHashMap<>();
      tokens.put("input", usage.inputTokenCount());
      tokens.put("output", usage.outputTokenCount());
      tokens.put("total", usage.totalTokenCount());
      event.put("tokens", tokens);
      LOG.debug("Tokens: {}", usage);
    }
    appendEvent(event);
  }

  @Override
  public void onError(ChatModelErrorContext ctx) {
    Map<String, Object> event = newEvent("error", ctx.modelProvider(), ctx.chatRequest());
    putDuration(event, ctx.attributes());

    Throwable error = ctx.error();
    if (error != null) {
      event.put("errorClass", error.getClass().getName());
      event.put("message", error.getMessage());
      event.put("stack", shortStack(error));
    }
    LOG.error("LLM ERROR: {}", error != null ? error.getMessage() : null, error);
    appendEvent(event);
  }

  // ── event construction helpers ───────────────────────────────────────────

  /**
   * Builds the common envelope for every event: run identity, model identity,
   * BPMN correlation, caller, timestamp. Model identity falls back to the
   * configured values when the response context does not override them.
   */
  private Map<String, Object> newEvent(String type, ModelProvider provider, ChatRequest req) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("schemaVersion", SCHEMA_VERSION);
    event.put("type", type);
    event.put("runId", runId);
    event.put("eventSeq", eventSeq.getAndIncrement());
    event.put("timestamp", Instant.now().toString());

    if (provider != null) {
      event.put("provider", provider.name());
    }
    String model = requestModel(req);
    if (model == null) {
      model = configuredModel;
    }
    if (model != null && !model.isEmpty()) {
      event.put("model", model);
    }
    if (endpoint != null && !endpoint.isEmpty()) {
      event.put("endpoint", endpoint);
    }

    if (processInstanceId != null) event.put("processInstanceId", processInstanceId);
    if (processDefinitionId != null) event.put("processDefinitionId", processDefinitionId);
    if (processDefinitionKey != null) event.put("processDefinitionKey", processDefinitionKey);
    if (businessKey != null) event.put("businessKey", businessKey);
    if (tenantId != null) event.put("tenantId", tenantId);
    if (executionId != null) event.put("executionId", executionId);
    if (activityId != null) event.put("activityId", activityId);

    if (userId != null && !userId.isEmpty()) {
      event.put("userId", userId);
      event.put("userIdSource", "context");
    } else if (executionId != null) {
      // Inside a BPMN execution context but identity could not be resolved.
      // Make the absence explicit rather than implicit by field omission so
      // Art. 12(3) audits flag it instead of treating it as untracked.
      event.put("userId", null);
      event.put("userIdSource", "missing");
    }
    if (groupIds != null && !groupIds.isEmpty()) event.put("groupIds", groupIds);
    return event;
  }

  private static String requestModel(ChatRequest req) {
    if (req == null || req.parameters() == null) {
      return null;
    }
    return req.parameters().modelName();
  }

  /**
   * Records the model invocation parameters needed for Art. 15 (robustness,
   * reproducibility): {@code temperature}, {@code topP}, {@code maxOutputTokens},
   * plus the OpenAI-specific {@code seed} and {@code reasoningEffort} when the
   * provider exposes them. Fields are omitted when the LangChain4j builder did
   * not set them, keeping events compact.
   */
  private static void putModelParams(Map<String, Object> event, ChatRequest req) {
    if (req == null) {
      return;
    }
    ChatRequestParameters params = req.parameters();
    if (params == null) {
      return;
    }
    Map<String, Object> modelParams = new LinkedHashMap<>();
    if (params.temperature() != null) modelParams.put("temperature", params.temperature());
    if (params.topP() != null) modelParams.put("topP", params.topP());
    if (params.maxOutputTokens() != null) modelParams.put("maxTokens", params.maxOutputTokens());
    if (params instanceof OpenAiChatRequestParameters) {
      OpenAiChatRequestParameters openAi = (OpenAiChatRequestParameters) params;
      if (openAi.seed() != null) modelParams.put("seed", openAi.seed());
      if (openAi.reasoningEffort() != null) modelParams.put("reasoningEffort", openAi.reasoningEffort());
    } else if (params instanceof OpenAiResponsesChatRequestParameters) {
      OpenAiResponsesChatRequestParameters responses = (OpenAiResponsesChatRequestParameters) params;
      if (responses.reasoningEffort() != null) modelParams.put("reasoningEffort", responses.reasoningEffort());
    }
    if (!modelParams.isEmpty()) {
      event.put("modelParams", modelParams);
    }
  }

  /**
   * Snapshot of model identity from the most recent response, exposed for the
   * Art. 50(2) AI-generated-output marker emitted by the connector after the
   * agent invocation finishes. Empty when no response was observed (e.g. error
   * before first turn). The map shape matches the corresponding audit-event
   * fields ({@code provider}, {@code model}, {@code responseId}).
   */
  Map<String, String> lastResponseIdentity() {
    Map<String, String> identity = new LinkedHashMap<>();
    if (lastProvider != null) identity.put("provider", lastProvider);
    if (lastModel != null) identity.put("model", lastModel);
    if (lastResponseId != null) identity.put("responseId", lastResponseId);
    return identity;
  }

  /**
   * ISO-8601 wall-clock timestamp recorded on the last {@code response} event,
   * or {@code null} when no response was observed. Reused by the connector to
   * stamp the Art. 50(2) AI-output marker's {@code generatedAt} so it aligns
   * exactly with the corresponding audit event instead of drifting to a later
   * "now" captured after tool unwinding.
   */
  String lastResponseTimestamp() {
    return lastResponseTimestamp;
  }

  private static void putDuration(Map<String, Object> event, Map<Object, Object> attributes) {
    if (attributes == null) {
      return;
    }
    Object start = attributes.get(KEY_START_NANOS);
    if (!(start instanceof Long)) {
      return;
    }
    long ms = (System.nanoTime() - (Long) start) / 1_000_000L;
    event.put("durationMs", ms);
  }

  /**
   * Drains any pending tool-side-effect records onto the event before
   * appending it to the audit list.
   */
  private void appendEvent(Map<String, Object> event) {
    if (!pendingToolSideEffects.isEmpty()) {
      event.put("toolSideEffects", new ArrayList<>(pendingToolSideEffects));
      pendingToolSideEffects.clear();
    }
    events.add(event);
    persistEvents();
  }

  /**
   * Returns the first {@value #ERROR_STACK_DEPTH} frames of {@code t}'s stack
   * trace as a single newline-joined string. Sufficient to classify the
   * failure type for Art. 79 (serious-incident detection) without bloating the
   * audit payload with full traces.
   */
  private static String shortStack(Throwable t) {
    StackTraceElement[] frames = t.getStackTrace();
    if (frames.length == 0) {
      return "";
    }
    int n = Math.min(ERROR_STACK_DEPTH, frames.length);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      if (i > 0) sb.append('\n');
      sb.append("\tat ").append(frames[i].toString());
    }
    if (frames.length > n) {
      sb.append("\n\t... ").append(frames.length - n).append(" more");
    }
    return sb.toString();
  }

  // ── message content extraction ───────────────────────────────────────────

  private static String extractContent(ChatMessage msg) {
    String plain = extractPlainContent(msg);
    if (Boolean.getBoolean(REDACT_CONTENT_PROPERTY)) {
      return redact(plain);
    }
    return plain;
  }

  private static String extractPlainContent(ChatMessage msg) {
    if (msg instanceof UserMessage) {
      UserMessage user = (UserMessage) msg;
      return user.hasSingleText() ? user.singleText() : user.toString();
    }
    if (msg instanceof SystemMessage) {
      return ((SystemMessage) msg).text();
    }
    if (msg instanceof AiMessage) {
      String text = ((AiMessage) msg).text();
      String thinking = ((AiMessage) msg).thinking();

      Map<String, String> content = new LinkedHashMap<>();
      content.put("text", text);
      content.put("thinking", thinking);

      try {
        return MAPPER.writeValueAsString(content);
      } catch (JsonProcessingException e) {
        LOG.error("Failed to serialise AiMessage content (text + thinking) to JSON; "
            + "falling back to plain answer text.", e);
        return text;
      }
    }
    if (msg instanceof ToolExecutionResultMessage) {
      return ((ToolExecutionResultMessage) msg).text();
    }
    return msg.toString();
  }

  /**
   * Replaces {@code plain} with a JSON-encoded redaction marker carrying a
   * SHA-256 hash and the original character length. The marker is stable for
   * the same input so deployers can correlate redacted entries against
   * external retention stores without rehydrating plaintext.
   */
  static String redact(String plain) {
    if (plain == null) {
      return null;
    }
    Map<String, Object> marker = new LinkedHashMap<>();
    marker.put("redacted", true);
    marker.put("length", plain.length());
    marker.put("hash", sha256(plain));
    try {
      return MAPPER.writeValueAsString(marker);
    } catch (JsonProcessingException e) {
      LOG.error("Failed to encode redaction marker; falling back to empty string.", e);
      return "";
    }
  }

  private static String sha256(String s) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2 + 7);
      hex.append("sha256:");
      for (byte b : digest) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16));
        hex.append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandated by the JDK spec — surfacing this as runtime is fine.
      throw new IllegalStateException("SHA-256 not available on this JVM", e);
    }
  }
}
