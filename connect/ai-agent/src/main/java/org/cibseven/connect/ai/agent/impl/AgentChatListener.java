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
 * Setting the {@value #REDACT_CONTENT_PROPERTY} system property (or the
 * matching {@value #REDACT_CONTENT_ENV_VAR} environment variable) to
 * {@code true} replaces every {@code messages[].content} string with a
 * JSON-encoded marker carrying a SHA-256 hash and the original character
 * length, to satisfy GDPR-conservative deployments at the cost of
 * frontend readability. The default is unredacted (preserves the existing
 * webclient JSON contract).
 *
 * <h3>Chat-log variable opt-out (DB-bloat control)</h3>
 * The per-activity {@code cibseven-connect-ai-agent_<activityId>} process
 * variable can be suppressed at two scopes:
 * <ul>
 *   <li><b>Per activity</b> — the {@code persistChatLog} connector input
 *       parameter (boolean). When set, overrides the deployment-wide
 *       default for that one service task only. Empty / unset → fall
 *       through to the deployment default.</li>
 *   <li><b>Deployment-wide</b> — system property
 *       {@value #CHAT_LOG_VARIABLE_PROPERTY} or environment variable
 *       {@value #CHAT_LOG_VARIABLE_ENV_VAR}, resolved in that order. The
 *       system property wins when both are set. Default {@code true}.</li>
 * </ul>
 * When disabled (at either scope) the listener still builds the in-memory
 * event timeline and emits to SLF4J, so external audit sinks (Kafka / JMS /
 * SAP via a custom {@code HistoryEventHandler}) keep working. The connector
 * flag variable ({@link AgentConnectorConstants#AGENT_CONNECTOR_FLAG_VARIABLE_NAME})
 * is also suppressed: it points at the chat-log timeline, so writing it
 * without the timeline would be misleading.
 *
 * <p><b>EU AI Act warning:</b> disabling the variable removes the in-engine
 * traceability used to satisfy Art. 12 (record-keeping) and Art. 26(6)
 * (≥ 6-month retention). Deployments that disable it must route the audit
 * events through an external sink for the duration required by their risk
 * tier and document the sink as the official record.
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
      return "cibseven-connect-ai-agent.startNanos";
    }
  };

  /**
   * System property that, when set to {@code true}, redacts message content
   * (replaces each {@code messages[].content} with a JSON-encoded
   * {@code {hash,length,redacted}} marker). Default {@code false}.
   */
  static final String REDACT_CONTENT_PROPERTY = "cibseven.connect.ai-agent.redactContent";

  /** Environment-variable fallback for {@link #REDACT_CONTENT_PROPERTY}. */
  static final String REDACT_CONTENT_ENV_VAR = "CIBSEVEN_CONNECT_AI_AGENT_REDACT_CONTENT";

  /**
   * System property that, when set to {@code false}, disables the per-activity
   * chat-log process variable at the deployment level. The in-memory event
   * list is still emitted to SLF4J so external audit sinks keep working; only
   * the engine DB write is skipped. Default {@code true} (variable is written
   * — Art. 12 / 26(6) compliant by default).
   */
  static final String CHAT_LOG_VARIABLE_PROPERTY =
      "cibseven.connect.ai-agent.chatLogVariable.enabled";

  /** Environment-variable fallback for {@link #CHAT_LOG_VARIABLE_PROPERTY}. */
  static final String CHAT_LOG_VARIABLE_ENV_VAR =
      "CIBSEVEN_CONNECT_AI_AGENT_CHAT_LOG_VARIABLE_ENABLED";

  /** Guards the one-time boot-log entry announcing the resolved global flag. */
  private static final java.util.concurrent.atomic.AtomicBoolean CHAT_LOG_FLAG_LOGGED =
      new java.util.concurrent.atomic.AtomicBoolean(false);

  /**
   * Environment-variable lookup seam. Defaults to {@link System#getenv(String)};
   * tests replace it to simulate env vars without spawning a new JVM. Always
   * restore to {@link System#getenv(String)::apply} after the test.
   */
  static java.util.function.Function<String, String> ENV_READER = System::getenv;

  /**
   * Resolves a boolean flag from a system property, falling back to an
   * environment variable (via {@link #ENV_READER}), then to
   * {@code defaultValue}. The system property wins when both are set so
   * {@code -D} overrides can be applied without editing the container env.
   * Empty / blank values are treated as unset.
   */
  static boolean resolveBooleanFlag(String systemProperty, String envVar, boolean defaultValue) {
    String fromSys = System.getProperty(systemProperty);
    if (fromSys != null && !fromSys.trim().isEmpty()) {
      return Boolean.parseBoolean(fromSys.trim());
    }
    String fromEnv = ENV_READER.apply(envVar);
    if (fromEnv != null && !fromEnv.trim().isEmpty()) {
      return Boolean.parseBoolean(fromEnv.trim());
    }
    return defaultValue;
  }

  /**
   * Resolves the deployment-wide chat-log variable flag from system property
   * → env var → default ({@code true}). Logs the resolved value exactly once
   * per JVM lifetime: WARN on the non-default {@code false} (compliance-
   * relevant deviation), DEBUG on {@code true}.
   */
  static boolean isChatLogVariableEnabled() {
    boolean enabled = resolveBooleanFlag(CHAT_LOG_VARIABLE_PROPERTY, CHAT_LOG_VARIABLE_ENV_VAR, true);
    if (CHAT_LOG_FLAG_LOGGED.compareAndSet(false, true)) {
      if (!enabled) {
        LOG.warn("AI Agent connector: per-activity chat-log variable is DISABLED "
            + "deployment-wide (system property {} or env var {} = false). The "
            + "in-engine EU AI Act Art. 12 / 26(6) audit trail will not be "
            + "persisted as a process variable — pair this setting with an "
            + "external audit sink (Kafka/JMS/SAP via a custom HistoryEventHandler) "
            + "to remain compliant.",
            CHAT_LOG_VARIABLE_PROPERTY, CHAT_LOG_VARIABLE_ENV_VAR);
      } else {
        LOG.debug("AI Agent connector: per-activity chat-log variable is enabled "
            + "(default, or {}/{} explicitly true).",
            CHAT_LOG_VARIABLE_PROPERTY, CHAT_LOG_VARIABLE_ENV_VAR);
      }
    }
    return enabled;
  }

  /**
   * Resolves the deployment-wide content-redaction flag from system property
   * → env var → default ({@code false}). Silent — no per-call or boot-log
   * announcement; matches the existing {@code redactContent} behaviour.
   */
  static boolean isRedactContentEnabled() {
    return resolveBooleanFlag(REDACT_CONTENT_PROPERTY, REDACT_CONTENT_ENV_VAR, false);
  }

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
  private final boolean chatLogVariableEnabled;
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

  /**
   * Per-tool-name provenance descriptor, published by the connector once the
   * MCP clients have been queried for their tool lists. Used to stamp
   * {@code toolProvenance} onto every event that lists tools so MCP-sourced
   * tools (carrying the server URL they came from) stay distinguishable from
   * local {@code @Tool} Java implementations — the Art. 26 data-flow
   * disclosure that motivates CIB7-1399. {@code null} (the default) means
   * no provenance metadata was published and events render exactly as they
   * did before this feature landed.
   */
  private volatile Map<String, Map<String, Object>> toolProvenance;

  AgentChatListener() {
    this(null, null, null);
  }

  AgentChatListener(String configuredModel, String endpoint) {
    this(configuredModel, endpoint, null);
  }

  /**
   * @param perActivityChatLogEnabled when non-{@code null}, overrides the
   *   deployment-wide chat-log variable flag for this listener only. When
   *   {@code null}, the flag is resolved from system property / env var /
   *   built-in default via {@link #isChatLogVariableEnabled()}.
   */
  AgentChatListener(String configuredModel, String endpoint, Boolean perActivityChatLogEnabled) {
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

    // Per-activity override wins; otherwise fall through to the deployment-wide
    // resolver (which is the path that logs the one-time WARN/DEBUG).
    this.chatLogVariableEnabled = (perActivityChatLogEnabled != null)
        ? perActivityChatLogEnabled
        : isChatLogVariableEnabled();
    this.variableName = (activityId != null && !activityId.isEmpty())
        ? AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX + activityId
        : null;
    if (this.variableName != null && this.chatLogVariableEnabled) {
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

  /**
   * Publishes the tool-name → provenance descriptor map for this listener
   * instance. Set once by the connector immediately after MCP clients have
   * enumerated their tools — must happen before the first model turn. Each
   * descriptor is a small {@code {kind, url}} map (or {@code {kind: "local"}}
   * for local {@code @Tool} Java implementations). Subsequent events that
   * list tools or tool calls will carry a {@code toolProvenance} sub-block
   * containing only the entries for the tools actually referenced on that
   * event. Pass {@code null} or an empty map to clear / disable the feature.
   */
  void setToolProvenance(Map<String, Map<String, Object>> provenance) {
    if (provenance == null || provenance.isEmpty()) {
      this.toolProvenance = null;
      return;
    }
    // Defensive copy so the listener's view is stable even if the caller
    // mutates the source map after publish.
    Map<String, Map<String, Object>> snapshot = new LinkedHashMap<>(provenance.size());
    for (Map.Entry<String, Map<String, Object>> e : provenance.entrySet()) {
      if (e.getKey() == null || e.getValue() == null) continue;
      snapshot.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
    }
    this.toolProvenance = snapshot;
  }

  /**
   * Emits one {@code retrieval} audit event into the per-{@code runId} stream
   * with the same envelope (schemaVersion, runId, eventSeq, timestamp, BPMN
   * correlation, caller identity) as request/response/error events, plus the
   * caller-supplied payload describing the RAG retrieval — typically
   * {@code query}, {@code embeddingModel}, {@code store}, {@code parameters},
   * {@code results}, {@code resultCount}, {@code durationMs}, and on failure
   * {@code errorClass} / {@code message} / {@code stack}.
   *
   * <p>The model-identity block ({@code provider} / {@code model} /
   * {@code endpoint}) of the chat envelope is intentionally omitted: retrieval
   * has its own provider/model under the {@code embeddingModel} field, and its
   * own endpoint under the {@code store} block. Adding the chat-model endpoint
   * here would be misleading.
   *
   * <p>Provides the Art. 10 / 12 / 26 evidence trail for which retrieved
   * documents grounded the model's answer.
   */
  void recordRetrievalEvent(Map<String, Object> payload) {
    if (payload == null) {
      return;
    }
    Map<String, Object> event = newRetrievalEvent();
    event.putAll(payload);
    appendEvent(event);
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
    if (variableName == null || !chatLogVariableEnabled) {
      // No persistence target → also no connector-flag write. The flag points
      // at the chat-log timeline and is meaningless without it.
      return;
    }
    ExecutionEntity execution = currentExecution();
    if (execution == null) {
      LOG.warn("Cannot update '{}' chat log variable: no BpmnExecutionContext on the current "
          + "thread (connector invoked outside an engine command context).", variableName);
      return;
    }
    if (!flagWritten) {
      // Flag that indicates the execution of the agent connector in the process.
      execution.setVariable(AgentConnectorConstants.AGENT_CONNECTOR_FLAG_VARIABLE_NAME, true);
      flagWritten = true;
    }
    String chatLog;
    try {
      chatLog = MAPPER.writeValueAsString(events);
    } catch (JsonProcessingException e) {
      LOG.error("Failed to serialise chatLog events to JSON; skipping update of '{}'.",
          variableName, e);
      return;
    }
    // Default Java serialization is always registered (unlike Spin/Jackson) and stores
    // in ACT_GE_BYTEARRAY, so it bypasses the VARCHAR(4000) limit.
    Object value = Variables.objectValue(chatLog).create();
    execution.setVariable(variableName, value);
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
      addToolProvenance(event, tools);
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
      List<String> calledToolNames = new ArrayList<>();
      for (ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", req.id());
        call.put("name", req.name());
        call.put("arguments", req.arguments());
        toolCalls.add(call);
        if (req.name() != null) {
          calledToolNames.add(req.name());
        }
        LOG.debug("TOOL CALL: {}({})", req.name(), req.arguments());
      }
      event.put("toolCalls", toolCalls);
      addToolProvenance(event, calledToolNames);
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
    // SLF4J line stays single-line: error message + the tool-name list the
    // model was offered (most common failure mode in production is "too many
    // tools", e.g. OpenAI's 128-tool cap — and seeing which 400 tools were
    // exposed is the first diagnostic step). The throwable is intentionally
    // NOT passed as the trailing argument: it would print the full provider
    // stack on every failure, which floods the application log. The audit
    // event keeps {@code errorClass}, {@code message}, and a 5-frame
    // {@code stack} for compliance.
    List<String> toolNames = Collections.emptyList();
    ChatRequest req = ctx.chatRequest();
    if (req != null && req.parameters() != null) {
      List<ToolSpecification> specs = req.parameters().toolSpecifications();
      if (specs != null) {
        toolNames = specs.stream().map(ToolSpecification::name).collect(Collectors.toList());
      }
    }
    LOG.error("LLM ERROR: {} (tools[{}]={})",
        error != null ? error.getMessage() : null,
        toolNames.size(),
        toolNames);
    appendEvent(event);
  }

  // ── event construction helpers ───────────────────────────────────────────

  /**
   * Builds the common envelope for every chat event: run identity, model
   * identity, BPMN correlation, caller, timestamp. Model identity falls back
   * to the configured values when the response context does not override them.
   */
  private Map<String, Object> newEvent(String type, ModelProvider provider, ChatRequest req) {
    Map<String, Object> event = new LinkedHashMap<>();
    putEnvelopeStart(event, type);

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

    putCorrelationAndCaller(event);
    return event;
  }

  /**
   * Envelope for {@code retrieval} events. Same shape as {@link #newEvent}
   * minus the chat-model identity block (retrievals carry their own
   * {@code embeddingModel} + {@code store} sub-blocks).
   */
  private Map<String, Object> newRetrievalEvent() {
    Map<String, Object> event = new LinkedHashMap<>();
    putEnvelopeStart(event, "retrieval");
    putCorrelationAndCaller(event);
    return event;
  }

  private void putEnvelopeStart(Map<String, Object> event, String type) {
    event.put("schemaVersion", SCHEMA_VERSION);
    event.put("type", type);
    event.put("runId", runId);
    event.put("eventSeq", eventSeq.getAndIncrement());
    event.put("timestamp", Instant.now().toString());
  }

  private void putCorrelationAndCaller(Map<String, Object> event) {
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

  /**
   * Stamps a {@code toolProvenance} sub-block onto {@code event} containing
   * only the entries for the tools listed in {@code toolNames}. Skips
   * silently when no provenance map has been published (default state) or
   * when no listed tool name matches a known entry — keeps the audit JSON
   * byte-for-byte identical to the pre-CIB7-1399 shape for those events.
   */
  private void addToolProvenance(Map<String, Object> event, List<String> toolNames) {
    Map<String, Map<String, Object>> source = this.toolProvenance;
    if (source == null || toolNames == null || toolNames.isEmpty()) {
      return;
    }
    Map<String, Object> subset = new LinkedHashMap<>();
    for (String name : toolNames) {
      Map<String, Object> entry = source.get(name);
      if (entry != null && !subset.containsKey(name)) {
        subset.put(name, entry);
      }
    }
    if (!subset.isEmpty()) {
      event.put("toolProvenance", subset);
    }
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
   * audit payload with full traces. Package-private so the retrieval audit
   * path in {@code AgentConnectorImpl} can produce identically-shaped
   * {@code stack} fields on retrieval errors.
   */
  static String shortStack(Throwable t) {
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
    return maybeRedact(extractPlainContent(msg));
  }

  /**
   * Applies the deployment-wide content-redaction policy to a single string:
   * returns the SHA-256 + length marker when {@link #isRedactContentEnabled()}
   * is on, otherwise returns the input unchanged. Exposed so non-listener
   * audit sites (e.g. the RAG retriever wrapper in {@code AgentConnectorImpl})
   * can apply the same GDPR-conservative gate to non-{@code messages[].content}
   * payloads such as retrieval queries and chunk text.
   */
  static String maybeRedact(String plain) {
    if (isRedactContentEnabled()) {
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
