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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>The chat-log name is derived automatically from the current activity:
 * {@code AGENT_CONNECTOR_LOG_PREFIX + activityId}. The log is stored in chunks,
 * one variable per model call named {@code <name>~<eventSeq of its first
 * event>}, the number padded with zeros to ten digits, each written once
 * (CIB7-2156). A reader puts the activity's log together from its chunks in
 * the order of their names, after the single variable
 * {@code <name>} that versions before chunking rewrote whole. Numbering
 * continues after whatever an earlier invocation of the activity wrote. When
 * the listener is invoked without a {@link BpmnExecutionContext} (e.g. unit
 * tests), it just collects events in memory and never touches the engine.
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
 * The per-activity {@code cibseven-connect-ai-agent_<activityId>~<eventSeq>}
 * process variables can be suppressed at two scopes:
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
   *
   * <p>2: {@code context} and {@code documents} events, the system message of
   * a {@code request} event referencing the {@code context} event instead of
   * carrying the process-context block, and {@code userIdSource = "startUser"}
   * for a user derived from the process instance's start user.
   */
  static final int SCHEMA_VERSION = 2;

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
   * Resolves the deployment-wide chat-log variable flag from system property
   * → env var → default ({@code true}). Logs the resolved value exactly once
   * per JVM lifetime: WARN on the non-default {@code false} (compliance-
   * relevant deviation), DEBUG on {@code true}.
   */
  static boolean isChatLogVariableEnabled() {
    boolean enabled = ConnectorSettings.resolveBoolean(
        CHAT_LOG_VARIABLE_PROPERTY, CHAT_LOG_VARIABLE_ENV_VAR, true);
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
    return ConnectorSettings.resolveBoolean(REDACT_CONTENT_PROPERTY, REDACT_CONTENT_ENV_VAR, false);
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
  /**
   * {@code "context"} for a user authenticated on the thread, {@code "startUser"}
   * when the connector derived the process instance's start user because the
   * thread carried no authentication at all — for a job without a tenant run by
   * the job executor, or a synchronous run without authentication. A job of a
   * tenant carries the job executor's user-less authentication and is recorded
   * as {@code "missing"}.
   */
  private final String userIdSource;
  private final List<String> groupIds;

  // ── persistence ──────────────────────────────────────────────────────────
  private final String variableName;
  private final boolean chatLogVariableEnabled;
  private final List<Map<String, Object>> events = new ArrayList<>();
  /** How many of {@link #events} are written already. */
  private int persisted = 0;
  private boolean flagWritten = false;

  private static final String CHUNK_SEPARATOR =
      AgentConnectorConstants.AGENT_CONNECTOR_LOG_CHUNK_SEPARATOR;

  /**
   * The activity ids whose chunk names were too long already, so that each is warned about
   * once per JVM rather than on every invocation.
   */
  private static final Set<String> TOO_LONG_WARNED = ConcurrentHashMap.newKeySet();

  /**
   * How many names {@link #TOO_LONG_WARNED} keeps, so that it cannot grow for the life of the JVM;
   * past that, every further one is warned about on each chunk it does not write.
   */
  private static final int TOO_LONG_WARNED_MAX = 1000;

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

  /**
   * Descriptor per attached document, keyed by the exact {@code Content}
   * instance handed to the model. Lets {@code renderMultiContent} name the
   * source variable and file instead of dumping Base64. {@code null} when no
   * documents are attached, which is the normal case.
   */
  private volatile Map<dev.langchain4j.data.message.Content, Map<String, Object>>
      documentDescriptors;

  /**
   * The process-context block appended to the system prompt, and the text that
   * replaces it in the {@code request} events. {@code null} when this run
   * declares no context. Set by {@link #recordContextEvent(Map, String)}.
   */
  private volatile String contextBlock;
  private volatile String contextBlockReference;

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
    this.userIdSource = (auth instanceof StartUserAuthentication) ? "startUser" : "context";
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
      continueNumbering();
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

  /** Package-private view of the events this listener captured, for tests. */
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
    Map<String, Object> event = newCorrelatedEvent("retrieval");
    event.putAll(payload);
    // A retrieval runs before the model call it grounds and is written with that call's
    // response. One that failed ends the call there, so it is written at once.
    appendEvent(event, payload.containsKey("errorClass"));
  }

  /**
   * Emits one {@code context} audit event describing the declared process
   * variables that were resolved into the system prompt — name, type, presence,
   * null-ness, value length and SHA-256 per variable, never the value itself.
   *
   * <p>Without it the chat log records the rendered prompt only, so "which
   * process data reached the model" cannot be reconstructed after the fact.
   * That is an EU AI Act Art. 12 (record-keeping) and Art. 14 (human oversight)
   * evidence gap, and it is the reason
   * {@link ProcessContextResolver#describe(java.util.List, String)} exists
   * alongside the rendered block.
   *
   * <p>Emitted before the first model turn. It is <em>not</em> a record of
   * aborted runs: when a missing required variable fails the activity, the
   * transaction rolls back and this event goes with it, like every other write
   * to the chat-log variable.
   *
   * <p>{@code renderedBlock} is the block as appended to the system prompt. The
   * {@code request} events of this run name this event in its place, as they
   * describe documents instead of carrying them: each model call's chunk is kept,
   * at history level FULL, in the history as well, so a block repeated per model
   * call would multiply the values in the history — and
   * keep them there after the variable itself is deleted or anonymised.
   */
  void recordContextEvent(Map<String, Object> payload, String renderedBlock) {
    if (payload == null) {
      return;
    }
    Map<String, Object> event = newCorrelatedEvent("context");
    event.putAll(payload);
    if (renderedBlock != null && !renderedBlock.isEmpty()) {
      this.contextBlockReference = "[process context: see the context event, runId " + runId
          + ", eventSeq " + event.get("eventSeq") + "]";
      this.contextBlock = renderedBlock;
    }
    // Before the first model call, so written with it, in its chunk (CIB7-2156).
    appendEvent(event, false);
  }

  /**
   * Publishes the per-{@code Content} descriptor map for the documents attached
   * to this invocation. Must be set before the first model turn, because it is
   * what {@code renderMultiContent} uses to describe an attachment instead of
   * serialising it. Pass {@code null} to clear.
   */
  void setDocumentDescriptors(
      Map<dev.langchain4j.data.message.Content, Map<String, Object>> descriptors) {
    this.documentDescriptors = (descriptors == null || descriptors.isEmpty()) ? null : descriptors;
  }

  /**
   * Emits one {@code documents} audit event listing the attachments sent to the
   * model — variable, filename, mime type, content kind, byte size and SHA-256
   * per document, plus the count and combined size.
   *
   * <p>Never the payload. The hash covers the raw bytes of the variable, so an
   * auditor holding the original file can prove it is the one that reached the
   * model without the chat-log variable carrying a second copy of it — which for
   * documents would mean megabytes per run in the engine database.
   */
  void recordDocumentsEvent(Map<String, Object> payload) {
    if (payload == null) {
      return;
    }
    Map<String, Object> event = newCorrelatedEvent("documents");
    event.putAll(payload);
    // Before the first model call, so written with it, in its chunk (CIB7-2156).
    appendEvent(event, false);
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

  /**
   * Continues the numbering of the activity's chat log, so {@code eventSeq} is
   * monotonic across invocations and every chunk gets a name of its own. Reads
   * only the newest chunk, or, before the first one, the single variable an
   * earlier version wrote.
   */
  private void continueNumbering() {
    ExecutionEntity execution = currentExecution();
    if (execution == null) {
      return;
    }
    String chunkPrefix = variableName + CHUNK_SEPARATOR;
    int lastChunk = -1;
    String lastChunkName = null;
    for (String name : execution.getVariableNames()) {
      if (name.startsWith(chunkPrefix) && isAsciiNumber(name.substring(chunkPrefix.length()))) {
        try {
          int chunk = Integer.parseInt(name.substring(chunkPrefix.length()));
          if (chunk > lastChunk) {
            lastChunk = chunk;
            // Read by the name it has, padded or not, rather than one rebuilt from the number.
            lastChunkName = name;
          }
        } catch (NumberFormatException e) {
          // Not a chunk of this log; whatever it is, it does not number it.
        }
      }
    }
    if (lastChunk >= 0) {
      // After an undecodable chunk at least one past it, so no later name collides with it.
      eventSeq.set(lastChunk + Math.max(1, decodedSize(execution, lastChunkName)));
    } else {
      eventSeq.set(decodedSize(execution, variableName));
    }
  }

  /**
   * Whether {@code suffix} is a chunk number as the connector writes it: ASCII digits only.
   * {@code Integer.parseInt} also takes a sign and other scripts' digits.
   */
  static boolean isAsciiNumber(String suffix) {
    if (suffix.isEmpty()) {
      return false;
    }
    for (int i = 0; i < suffix.length(); i++) {
      char c = suffix.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }

  /** The number of events the variable {@code name} holds; 0 when absent or undecodable. */
  private static int decodedSize(ExecutionEntity execution, String name) {
    Object existing = execution.getVariable(name);
    if (existing == null || existing.toString().isEmpty()) {
      return 0;
    }
    try {
      return MAPPER.readValue(existing.toString(), EVENT_LIST_TYPE).size();
    } catch (JsonProcessingException e) {
      LOG.warn("Failed to decode the previous chat log in variable '{}'; numbering events as if"
          + " it were empty.", name, e);
      return 0;
    }
  }

  /**
   * Writes the events since the last write as a chunk of their own, named
   * after the first one's {@code eventSeq} (CIB7-2156). Each chunk is written
   * once and never again, so what the chat log stores, and its history at level
   * {@code full}, grows with the events rather than with their square, as it
   * did while one variable holding the whole log was rewritten on every write.
   */
  private void persistEvents() {
    if (variableName == null || !chatLogVariableEnabled) {
      // No persistence target → also no connector-flag write. The flag points
      // at the chat-log timeline and is meaningless without it.
      return;
    }
    if (persisted == events.size()) {
      return;
    }
    ExecutionEntity execution = currentExecution();
    if (execution == null) {
      LOG.warn("Cannot update '{}' chat log variable: no BpmnExecutionContext on the current "
          + "thread (connector invoked outside an engine command context).", variableName);
      return;
    }
    String name = chunkName(events.get(persisted).get("eventSeq"));
    if (name.getBytes(StandardCharsets.UTF_8).length
        > ProcessVariableChatMemoryStore.MAX_VARIABLE_NAME_LENGTH) {
      // Caught here rather than as a database error, which would fail the invocation. Every
      // chunk name of an activity has the same length, so an id that fits once always fits.
      // Counted in UTF-8 bytes, as a byte-length column (DB2) counts it; on a column counting
      // characters that is the conservative bound, the same for an ASCII id.
      if (!TOO_LONG_WARNED.contains(variableName)
          && (TOO_LONG_WARNED.size() >= TOO_LONG_WARNED_MAX || TOO_LONG_WARNED.add(variableName))) {
        LOG.warn("AI Agent connector: the chat-log variable '{}' is longer than the {} bytes"
            + " the engine stores; the chat log of activity '{}' is not persisted from here on."
            + " Shorten the activity id to keep its audit trail.", name,
            ProcessVariableChatMemoryStore.MAX_VARIABLE_NAME_LENGTH, variableName.substring(
                AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX.length()));
      }
      persisted = events.size();
      return;
    }
    if (!flagWritten) {
      // Flag that indicates the execution of the agent connector in the process.
      execution.setVariable(AgentConnectorConstants.AGENT_CONNECTOR_FLAG_VARIABLE_NAME, true);
      flagWritten = true;
    }
    List<Map<String, Object>> chunk = new ArrayList<>(events.subList(persisted, events.size()));
    String chatLog;
    try {
      chatLog = MAPPER.writeValueAsString(chunk);
    } catch (JsonProcessingException e) {
      LOG.error("Failed to serialise chatLog events to JSON; skipping update of '{}'.",
          variableName, e);
      return;
    }
    // Default Java serialization is always registered (unlike Spin/Jackson) and stores
    // in ACT_GE_BYTEARRAY, so it bypasses the VARCHAR(4000) limit.
    Object value = Variables.objectValue(chatLog).create();
    execution.setVariable(name, value);
    persisted = events.size();
  }

  /**
   * Writes what no {@code response} or {@code error} wrote yet, such as a
   * retrieval or a request whose model call never ended, as a chunk of its own.
   * Called once when the connector invocation ends (CIB7-2156); where every model
   * call ended, everything is written already and this writes nothing.
   */
  void flush() {
    persistEvents();
  }

  /**
   * The variable name of the chunk whose first event has {@code firstSeq}, the number padded with
   * zeros to ten digits, the width of {@link Integer#MAX_VALUE}: every chunk name of an activity has
   * the same length, and their order as text is their numeric order (CIB7-2156).
   */
  String chunkName(Object firstSeq) {
    // Locale.ROOT: the default locale may format with other digits (Arabic-Indic, say), which
    // would take more bytes and break the order as text across nodes.
    return variableName + CHUNK_SEPARATOR
        + String.format(java.util.Locale.ROOT, "%010d", ((Number) firstSeq).longValue());
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
      // What the log keeps, not the message: a step's processData as markers, and the content
      // redacted when the deployment redacts it (CIB7-2179).
      LOG.debug("[{}] {}", msg.type(), m.get("content"));
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
    // Written with the response or the error that ends this call (CIB7-2156).
    appendEvent(event, false);
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
    appendEvent(event, true);
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
    appendEvent(event, true);
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
   * Envelope for events that are not a chat turn — currently {@code retrieval},
   * {@code context} and {@code documents}. Same shape as {@link #newEvent} minus
   * the chat-model identity block: a retrieval carries its own
   * {@code embeddingModel} and {@code store} sub-blocks, while context and
   * documents record what was assembled <em>before</em> any model call, so
   * stamping the chat model's identity onto them would claim the model saw
   * something at a point where it had not been invoked yet.
   *
   * <p>Keep new event types out of {@link #newEvent} unless they really are a
   * call to the chat model.
   */
  private Map<String, Object> newCorrelatedEvent(String type) {
    Map<String, Object> event = new LinkedHashMap<>();
    putEnvelopeStart(event, type);
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
      event.put("userIdSource", userIdSource);
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
   * appending it to the audit list, and writes the list when {@code persist}
   * says the event ends a model call.
   *
   * <p>Written once per model call, as a chunk with the response or the error
   * that ends it, rather than once per event (CIB7-2156): the request and any
   * retrieval before it wait in memory until then. That is also the moment the
   * last of them was written before, so the events reach the database at the
   * same point. Only events that no response or error follows stay unwritten: a
   * request whose call ends without either, or a successful retrieval after
   * which no model is asked. Before, each of them was written at once.
   */
  private void appendEvent(Map<String, Object> event, boolean persist) {
    if (!pendingToolSideEffects.isEmpty()) {
      event.put("toolSideEffects", new ArrayList<>(pendingToolSideEffects));
      pendingToolSideEffects.clear();
    }
    events.add(event);
    if (persist) {
      persistEvents();
    }
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

  private String extractContent(ChatMessage msg) {
    String plain = extractPlainContent(msg);
    if (msg instanceof UserMessage) {
      plain = withProcessDataAsMarkers(plain);
    }
    return maybeRedact(plain);
  }

  /**
   * An agentic step's message with each value under {@code processData} in its state block
   * replaced by the redaction marker, its length and hash (CIB7-2179). The values are process
   * variables, which keep them and their history. The log holds every message of the window on
   * every request and is rewritten whole on every event, so a copy here would be one more per
   * step and per rewrite, out of reach of whoever deletes or anonymises the variable. The marker
   * still tells which value the model saw. A message without a state block is left as it is.
   */
  @SuppressWarnings("unchecked")
  static String withProcessDataAsMarkers(String text) {
    if (text == null) {
      return null;
    }
    int open = text.indexOf(AgentConnectorImpl.STATE_BLOCK_OPEN + "\n");
    int start = open + AgentConnectorImpl.STATE_BLOCK_OPEN.length() + 1;
    int end = (open < 0) ? -1 : text.indexOf("\n" + AgentConnectorImpl.STATE_BLOCK_CLOSE, start);
    if (end < 0) {
      return text;
    }
    try {
      Map<String, Object> state = MAPPER.readValue(text.substring(start, end), Map.class);
      Object data = state.get("processData");
      if (!(data instanceof Map)) {
        return text;
      }
      Map<String, Object> marked = new LinkedHashMap<>();
      for (Map.Entry<String, Object> entry : ((Map<String, Object>) data).entrySet()) {
        Object value = entry.getValue();
        marked.put(entry.getKey(), (value == null) ? null : redactionMarker(
            (value instanceof String) ? (String) value : MAPPER.writeValueAsString(value)));
      }
      state.put("processData", marked);
      return text.substring(0, start) + MAPPER.writeValueAsString(state) + text.substring(end);
    } catch (JsonProcessingException e) {
      // Written by the connector itself, so not expected; the block goes as a whole rather
      // than the values in it.
      LOG.warn("Could not read the state block of an agentic step; the chat log keeps it as a"
          + " redaction marker.", e);
      return text.substring(0, start) + redact(text.substring(start, end)) + text.substring(end);
    }
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

  /**
   * Replaces the process-context block in a system prompt with the reference to
   * the {@code context} event. The block is the exact string the connector
   * appended, so an exact match is enough; a prompt without it is returned
   * unchanged.
   */
  private String withoutContextBlock(String text) {
    String block = this.contextBlock;
    if (text == null || block == null || !text.contains(block)) {
      return text;
    }
    return text.replace(block, contextBlockReference);
  }

  private String extractPlainContent(ChatMessage msg) {
    if (msg instanceof UserMessage) {
      UserMessage user = (UserMessage) msg;
      return user.hasSingleText() ? user.singleText() : renderMultiContent(user);
    }
    if (msg instanceof SystemMessage) {
      return withoutContextBlock(((SystemMessage) msg).text());
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
   * Renders a multi-content user message — the shape produced when documents are
   * attached — as text plus one descriptor per attachment.
   *
   * <p>This method is the reason document support is safe to persist at all.
   * The obvious fallback, {@code UserMessage.toString()}, prints the
   * whole Base64 payload of every attachment; with the chat log being a process
   * variable, one invoice PDF would put megabytes of Base64 into the engine
   * database on every run. A descriptor — variable, filename, mime type, size,
   * SHA-256 — carries the evidence without the payload.
   *
   * <p>Descriptors come from {@link #setDocumentDescriptors(Map)}, published by
   * the connector while resolving, and are looked up <b>before</b> the content
   * type is inspected. That order is the whole correctness of this method: a
   * text document is a {@code TextContent}, so branching on the type first sends
   * it down the "this is prompt text, print it" path and puts the entire
   * document into the chat log. Only content the connector did not build has no
   * descriptor.
   *
   * <p>For such content — the agent's own prompt text, or a content object from
   * somewhere else — a {@code TextContent} is printed and anything else emits
   * the content type plus {@code descriptor: "unavailable"}. No size, because
   * reading one would mean reaching into each LangChain4j content type for its
   * payload, which is the very assumption that branch exists to avoid. The
   * guarantee that matters holds either way: never the data.
   */
  private String renderMultiContent(UserMessage user) {
    Map<dev.langchain4j.data.message.Content, Map<String, Object>> descriptors =
        this.documentDescriptors;
    List<Object> parts = new ArrayList<>();
    List<dev.langchain4j.data.message.Content> contents = user.contents();
    for (int index = 0; index < contents.size(); index++) {
      dev.langchain4j.data.message.Content content = contents.get(index);
      // The descriptor lookup has to come first. A text document is a
      // TextContent like any other, so testing the type before consulting the
      // map would write the whole document body into the chat log — up to 5 MB per
      // turn, in a process variable, for exactly the content this map exists to
      // keep out of it. Only content with no descriptor is the agent's own
      // prompt text, and that is what the TEXT branch below is for.
      Map<String, Object> descriptor = (descriptors == null) ? null : descriptors.get(content);
      if (descriptor != null) {
        parts.add(new LinkedHashMap<>(descriptor));
        continue;
      }
      if (content instanceof dev.langchain4j.data.message.TextContent) {
        String text = ((dev.langchain4j.data.message.TextContent) content).text();
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "TEXT");
        // Never for the first part: that is the agent's own prompt text
        // (request.getMessage(), which documents require to be non-empty), and
        // it is not neutered — a message that merely starts with the header
        // would otherwise reach the model while the chat log kept only a hash.
        if (index > 0 && isWrappedTextDocument(text)) {
          // Second line of defence. The descriptor map is identity-keyed, so a
          // LangChain4j step that rebuilds the TextContent — a guardrail, an
          // augmentor, a message copy — makes the lookup above miss, and the
          // document body would land in the chat log. The block header the
          // connector writes is recognisable on its own: log length and hash,
          // never the text.
          part.put("document", true);
          part.put("descriptor", "unavailable");
          part.put("length", text.length());
          part.put("sha256", sha256(text));
        } else {
          part.put("text", text);
        }
        parts.add(part);
        continue;
      }
      Map<String, Object> part = new LinkedHashMap<>();
      part.put("type", content.type() == null ? "UNKNOWN" : content.type().name());
      part.put("descriptor", "unavailable");
      parts.add(part);
    }
    try {
      return MAPPER.writeValueAsString(parts);
    } catch (JsonProcessingException e) {
      LOG.error("Failed to serialise multi-content user message; emitting a placeholder "
          + "rather than the raw message, which would contain base64 payloads.", e);
      return "[" + parts.size() + " content part(s); serialisation failed]";
    }
  }

  /**
   * Whether {@code text} starts with the header
   * {@link DocumentContentResolver#wrapTextDocument} writes. A document body
   * cannot forge it — the delimiters inside the body are neutered — so a match
   * is the connector's own wrapping.
   */
  private static boolean isWrappedTextDocument(String text) {
    return text != null
        && text.startsWith(AgentConnectorConstants.DOCUMENT_BLOCK_OPEN + " variable=\"");
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
    try {
      return MAPPER.writeValueAsString(redactionMarker(plain));
    } catch (JsonProcessingException e) {
      LOG.error("Failed to encode redaction marker; falling back to empty string.", e);
      return "";
    }
  }

  /** The redaction marker of {@code plain}: its character length and SHA-256 hash. */
  private static Map<String, Object> redactionMarker(String plain) {
    Map<String, Object> marker = new LinkedHashMap<>();
    marker.put("redacted", true);
    marker.put("length", plain.length());
    marker.put("hash", sha256(plain));
    return marker;
  }

  /**
   * SHA-256 of {@code s} encoded as UTF-8, prefixed with {@code sha256:}.
   * Package-private so non-listener audit sites can hash a payload they must not
   * emit in the clear — {@link ProcessContextResolver} uses it to record which
   * process-variable values reached the model without writing the values a
   * second time.
   *
   * <p>Use {@link #sha256(byte[])} for anything that originated as bytes: going
   * through a {@code String} re-encodes it as UTF-8, so the digest would no
   * longer match the stored bytes for any other charset.
   */
  static String sha256(String s) {
    return sha256(s.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * SHA-256 of {@code bytes}, prefixed with {@code sha256:}.
   *
   * <p>{@link DocumentContentResolver} hashes the raw variable bytes with this,
   * so a document descriptor in the audit log carries the fingerprint of the
   * file as stored — reproducible with {@code sha256sum} or {@code Get-FileHash}
   * against the original, whatever the mime type or charset.
   */
  static String sha256(byte[] bytes) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(bytes);
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
