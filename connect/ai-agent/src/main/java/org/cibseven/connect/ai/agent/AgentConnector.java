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
package org.cibseven.connect.ai.agent;

import org.cibseven.connect.spi.Connector;

/**
 * Marker interface and constants for the LangChain4j agent connector.
 *
 * <p>Use the connector ID {@value #ID} in a BPMN service task. The example below
 * shows every supported input/output parameter; only {@code agentName} and
 * {@code message} are required, the rest are optional. When {@code instruction}
 * is empty, the bundled default system prompt from
 * {@code /org/cibseven/connect/ai/agent/default-instruction.txt} is used.
 * <pre>{@code
 * <camunda:connector>
 *     <camunda:connectorId>cibseven-ai-agent</camunda:connectorId>
 *     <camunda:inputOutput>
 *       <!-- Core agent configuration -->
 *       <camunda:inputParameter name="agentName">my-agent</camunda:inputParameter>
 *       <camunda:inputParameter name="agentDescription">Customer support agent</camunda:inputParameter>
 *       <camunda:inputParameter name="instruction">You are a helpful assistant.</camunda:inputParameter>
 *       <camunda:inputParameter name="instructionMode">append</camunda:inputParameter>
 *       <camunda:inputParameter name="model">gpt-5.4-nano</camunda:inputParameter>
 *       <camunda:inputParameter name="message">${userMessage}</camunda:inputParameter>
 *
 *       <!-- Process context: allowlist of variable NAMES (not expressions).
 *            Everything listed is required; the second list names the exceptions. -->
 *       <camunda:inputParameter name="contextVariables">orderId,totalAmount,escalationReason</camunda:inputParameter>
 *       <camunda:inputParameter name="optionalContextVariables">escalationReason</camunda:inputParameter>
 *
 *       <!-- LLM endpoint / authentication -->
 *       <camunda:inputParameter name="baseUrl">http://localhost:11434/v1</camunda:inputParameter>
 *       <camunda:inputParameter name="apiKey">${secrets.OPENAI_API_KEY}</camunda:inputParameter>
 *       <camunda:inputParameter name="customHeaders">{"Authorization": "Basic dXNlcjpwYXNz", "X-Tenant": "acme"}</camunda:inputParameter>
 *
 *       <!-- Reasoning controls -->
 *       <camunda:inputParameter name="reasoningEffort">medium</camunda:inputParameter>
 *       <camunda:inputParameter name="reasoningSummary">auto</camunda:inputParameter>
 *
 *       <!-- Tools: Java @Tool classes -->
 *       <camunda:inputParameter name="toolClasses">com.example.WeatherTools,com.example.CalendarTools</camunda:inputParameter>
 *
 *       <!-- Tools: MCP servers (JSON array) -->
 *       <camunda:inputParameter name="mcpServers">
 *         [
 *           {"url": "http://server1/mcp", "headers": {"Authorization": "Bearer abc"}},
 *           {"url": "http://server2/mcp"}
 *         ]
 *       </camunda:inputParameter>
 *
 *       <!-- Chat memory (e.g. human-feedback loop) -->
 *       <camunda:inputParameter name="useChatMemory">true</camunda:inputParameter>
 *       <camunda:inputParameter name="memoryId">${execution.getVariable('memoryId')}</camunda:inputParameter>
 *       <camunda:inputParameter name="chatMemoryMaxMessages">20</camunda:inputParameter>
 *
 *       <!-- EU AI Act audit -->
 *       <camunda:inputParameter name="persistChatLog">true</camunda:inputParameter>
 *
 *       <!-- RAG / pgvector embedding store -->
 *       <camunda:inputParameter name="pgHost">localhost</camunda:inputParameter>
 *       <camunda:inputParameter name="pgPort">5432</camunda:inputParameter>
 *       <camunda:inputParameter name="pgDatabase">vectors</camunda:inputParameter>
 *       <camunda:inputParameter name="pgUser">postgres</camunda:inputParameter>
 *       <camunda:inputParameter name="pgPassword">${secrets.PG_PASSWORD}</camunda:inputParameter>
 *       <camunda:inputParameter name="pgTable">embeddings</camunda:inputParameter>
 *       <camunda:inputParameter name="maxRagResults">5</camunda:inputParameter>
 *       <camunda:inputParameter name="minRagScore">0.7</camunda:inputParameter>
 *       <camunda:inputParameter name="embeddingDimension">384</camunda:inputParameter>
 *       <camunda:inputParameter name="embeddingModelName">text-embedding-3-small</camunda:inputParameter>
 *
 *       <!-- Output -->
 *       <camunda:outputParameter name="agentOutput">${output}</camunda:outputParameter>
 *       <camunda:outputParameter name="agentOutput_aiMeta">${outputAiMeta}</camunda:outputParameter>
 *       <camunda:outputParameter name="memoryId">${memoryId}</camunda:outputParameter>
 *     </camunda:inputOutput>
 * </camunda:connector>
 * }</pre>
 *
 * <p>The chat log is persisted automatically into a process-scoped variable named
 * {@code AGENT_CONNECTOR_LOG_PREFIX + <activityId>} and updated on every
 * {@code request}/{@code response}/{@code error} event of the underlying chat model.
 * If a variable with that name already exists when the connector runs, its content
 * is deserialised and used as the starting point — so multiple invocations of the
 * same service task within one process instance accumulate into a single timeline.
 * The chat log is not exposed as an output parameter because its serialised form
 * regularly exceeds the {@code VARCHAR(4000)} limit of Camunda's TEXT_ column.
 */
public interface AgentConnector extends Connector<AgentRequest> {

  /** Connector ID used in {@code <camunda:connectorId>}. */
  String ID = "cibseven-ai-agent";

  // ── Input parameter names ──────────────────────────────────────────────────

  /** Required. Logical name of the agent (passed to {@code LlmAgent.builder().name()}). */
  String PARAM_NAME_AGENT_NAME = "agentName";

  /** Optional. Human-readable description of the agent. */
  String PARAM_NAME_AGENT_DESCRIPTION = "agentDescription";

  /**
   * Optional. System instruction for the LLM. When {@code null} or empty,
   * the connector falls back to the bundled default prompt at
   * {@code /org/cibseven/connect/ai/agent/default-instruction.txt}, which
   * describes the generic CIB seven agent role and its possible capabilities
   * (tools, RAG, chat memory, reasoning). Override this parameter to specialise
   * the agent for a concrete task. See {@link #PARAM_NAME_INSTRUCTION_MODE} for
   * how this value is combined with the bundled default.
   */
  String PARAM_NAME_INSTRUCTION = "instruction";

  /**
   * Optional. Controls how the caller-supplied {@link #PARAM_NAME_INSTRUCTION}
   * is combined with the bundled default system prompt. Allowed values:
   * <ul>
   *   <li>{@code "replace"} (default) — the bundled default is discarded and
   *       only {@code instruction} is sent to the LLM. Matches the historical
   *       behaviour.</li>
   *   <li>{@code "append"} — the bundled default is sent first, followed by
   *       {@code instruction}, separated by a blank line.</li>
   *   <li>{@code "prepend"} — {@code instruction} is sent first, followed by
   *       the bundled default, separated by a blank line.</li>
   * </ul>
   * When {@code instruction} is empty the mode is ignored — only the bundled
   * default is used. An unknown value causes the connector to reject the
   * request with {@code AgentConnectorException}.
   */
  String PARAM_NAME_INSTRUCTION_MODE = "instructionMode";

  /**
   * Optional. LLM model identifier (e.g. {@code gpt-5.4-nano}, {@code claude-opus-4.7},
   * {@code gemma-4-26b}; on OpenRouter prefix with provider, e.g. {@code openai/gpt-5.4-nano}).
   * Defaults to {@value AgentConnectorConstants#DEFAULT_MODEL}.
   */
  String PARAM_NAME_MODEL = "model";

  /** Required. The user message to send to the agent. */
  String PARAM_NAME_MESSAGE = "message";

  /**
   * Optional. Comma-separated list of fully qualified class names whose
   * {@code @Tool}-annotated methods are registered with the agent.
   * Each class must have a public no-arg constructor.
   * Example: {@code "com.example.WeatherTools,com.example.CalendarTools"}
   */
  String PARAM_NAME_TOOL_CLASSES = "toolClasses";

  /**
   * Optional. OpenAI-compatible base URL (e.g. for Ollama: {@code "http://localhost:11434/v1"}
   * or Azure OpenAI endpoints). When omitted, the standard OpenAI API endpoint is used.
   */
  String PARAM_NAME_BASE_URL = "baseUrl";

  /**
   * Optional. Override the {@code OPENAI_API_KEY} for this specific request.
   * The environment variable {@code OPENAI_API_KEY} is the recommended approach.
   */
  String PARAM_NAME_API_KEY = "apiKey";

  /**
   * Optional. Custom HTTP headers attached to every request sent to the OpenAI-compatible
   * endpoint. Format: JSON object of {@code String} → {@code String} pairs.
   * Example: {@code {"Authorization": "Basic dXNlcjpwYXNz", "X-Tenant": "acme"}}
   */
  String PARAM_NAME_CUSTOM_HEADERS = "customHeaders";

  /**
   * Optional. JSON array describing one or more MCP (Model Context Protocol)
   * servers, each with its own URL and (optional) custom HTTP headers. Tools
   * exposed by each server are registered with the agent in addition to any
   * {@code toolClasses}.
   *
   * <p>Each entry must have a {@code url} string; {@code headers} is an optional
   * object of {@code String} → {@code String} pairs. Example:
   * <pre>{@code
   * [
   *   {"url": "http://server1/mcp", "headers": {"Authorization": "Bearer abc"}},
   *   {"url": "http://server2/mcp"}
   * ]
   * }</pre>
   */
  String PARAM_NAME_MCP_SERVERS = "mcpServers";

  // ── Process context input parameter names ─────────────────────────────────

  /**
   * Optional. Comma-separated allowlist of process-variable <em>names</em> whose
   * values are rendered into a structured {@code <process-context>} block and
   * appended to the system prompt. Empty (the default) means no context is
   * passed — the agent sees exactly what {@link #PARAM_NAME_MESSAGE} carries,
   * which is the behaviour of every release before this parameter existed.
   *
   * <p>Names, not {@code ${expressions}}: the Connect boundary unwraps every
   * {@code TypedValue} before the connector runs, so an expression would arrive
   * stripped of its type (and, for files, of filename and mime type). The
   * connector reads {@code execution.getVariableTyped(name, false)} itself, so
   * types survive and {@code null} stays distinguishable from an empty string.
   *
   * <p>Example: {@code "orderId,customerName,totalAmount"}
   *
   * <p>Every name listed here is <b>required</b> unless it also appears in
   * {@link #PARAM_NAME_OPTIONAL_CONTEXT_VARIABLES}: if it cannot reach the model,
   * the activity fails and the agent is not invoked. That default is deliberate —
   * declaring a variable means the task needs it, and the alternative (proceeding
   * over missing data) fails silently.
   *
   * <p>Variables holding files or raw bytes are rendered as a descriptor, never
   * as content. Values are escaped and capped
   * ({@value AgentConnectorConstants#DEFAULT_MAX_CONTEXT_VALUE_CHARS} characters
   * per value, {@value AgentConnectorConstants#DEFAULT_MAX_CONTEXT_BLOCK_CHARS}
   * per block); truncation and omission are visible in the block and in the
   * {@code context} audit event.
   *
   * <p><b>Security:</b> process variables routinely hold user- or
   * document-sourced text. Everything listed here becomes part of the prompt of
   * a possibly tool-enabled agent — declare the minimum the task needs.
   */
  String PARAM_NAME_CONTEXT_VARIABLES = "contextVariables";

  /**
   * Optional. Comma-separated subset of {@link #PARAM_NAME_CONTEXT_VARIABLES}
   * that is allowed to be missing. Such a variable renders as {@code (absent)}
   * and the agent runs on; everything else declared fails the activity when it
   * cannot reach the model.
   *
   * <p>Use it for context that legitimately exists on only some paths — an
   * escalation reason written by a branch that is often skipped, a decision from
   * a previous loop iteration, an answer to an optional enquiry. Requiring those
   * would force the modeler to pre-initialise them, which collapses the
   * {@code (absent)} / empty distinction this block exists to preserve.
   *
   * <p>This list is a pure <em>modifier</em>: it never adds a name to the
   * allowlist. A name listed here but absent from
   * {@link #PARAM_NAME_CONTEXT_VARIABLES} is ignored with a warning, so there is
   * exactly one place that decides what the agent sees.
   *
   * <p>Note that an <em>empty string</em> counts as a value. Only variables that
   * were never written are affected by this list.
   */
  String PARAM_NAME_OPTIONAL_CONTEXT_VARIABLES = "optionalContextVariables";

  // ── Document input parameter names ────────────────────────────────────────

  /**
   * Optional. Comma-separated list of process-variable <em>names</em> holding
   * files that are sent to the model as native attachments — a PDF as a PDF, an
   * image as an image, rather than as text spliced into the prompt.
   *
   * <p>Accepts {@code FileValue} variables (which carry filename and mime type)
   * and {@code BytesValue} variables (which carry neither, so their mime type
   * has to come from {@link #PARAM_NAME_DOCUMENT_MIME_TYPES}).
   *
   * <p>Names, not {@code ${expressions}} — and here the reason is not just type
   * loss: Connect hands over {@code FileValue.getValue()}, which is a bare
   * {@code InputStream}, so filename and mime type are already gone, and the
   * request-parameter accessor is an unchecked cast that would fail with a
   * {@code ClassCastException} rather than a usable message.
   *
   * <p>Every declared document must exist. Unlike
   * {@link #PARAM_NAME_CONTEXT_VARIABLES} there is no optional variant: an
   * agent asked to read an invoice that was not attached is not working on
   * degraded input, it is working on a different task.
   *
   * <p>Example: {@code "invoicePdf,signatureScan"}
   */
  String PARAM_NAME_DOCUMENTS = "documents";

  /**
   * Optional. JSON object of variable name → mime type, used when a document's
   * own metadata does not say what it is, or to override it.
   *
   * <p>Required for every {@code BytesValue} variable listed in
   * {@link #PARAM_NAME_DOCUMENTS}: raw bytes carry no mime type, and guessing
   * one from the leading bytes would fail silently when it guesses wrong.
   *
   * <p>Example: {@code {"scan": "image/png", "report": "application/pdf"}}
   */
  String PARAM_NAME_DOCUMENT_MIME_TYPES = "documentMimeTypes";

  /**
   * Optional. Detail level requested for image attachments — {@code AUTO}
   * (default), {@code LOW}, {@code MEDIUM}, {@code HIGH} or {@code ULTRA_HIGH}.
   * Higher levels cost more input tokens. Ignored for non-image documents.
   */
  String PARAM_NAME_DOCUMENT_DETAIL_LEVEL = "documentDetailLevel";

  /**
   * Optional. Enables {@code audio/*} and {@code video/*} attachments, which are
   * rejected by default.
   *
   * <p>Not a capability switch but an honesty switch: LangChain4j sends audio as
   * Base64 only and requires a well-formed mime type, and video maps to a field
   * that is not an official OpenAI one, so whether either works depends on the
   * gateway behind {@link #PARAM_NAME_BASE_URL}. Enabling this says "I have
   * verified my endpoint accepts these".
   */
  String PARAM_NAME_ALLOW_AUDIO_VIDEO = "allowAudioVideo";

  /**
   * Optional. Reasoning effort hint for reasoning-capable models
   * (e.g. {@code "low"}, {@code "medium"}, {@code "high"}).
   * Allowed values are model-dependent and forwarded as-is to the OpenAI builder.
   */
  String PARAM_NAME_REASONING_EFFORT = "reasoningEffort";

  /**
   * Optional. Enables reasoning-summary text in {@code AiMessage.thinking()}
   * (e.g. {@code "auto"}, {@code "concise"}, {@code "detailed"}).
   * Setting this value switches the connector to the OpenAI <em>Responses API</em>
   * ({@code OpenAiResponsesChatModel}), which is required for reasoning summaries.
   * Allowed values are model-dependent and forwarded as-is to the OpenAI builder.
   */
  String PARAM_NAME_REASONING_SUMMARY = "reasoningSummary";

  // ── Chat memory input parameter names ─────────────────────────────────────

  /**
   * Optional. Activates the per-conversation chat memory backed by
   * {@link org.cibseven.connect.ai.agent.impl.AgentChatMemoryStore}.
   * When {@code true}, the agent's prior messages — keyed by
   * {@link #PARAM_NAME_MEMORY_ID} — are replayed on every invocation so a
   * BPMN process can resume the same conversation, typically after a
   * human-feedback user task. Defaults to {@code false} (stateless).
   */
  String PARAM_NAME_USE_CHAT_MEMORY = "useChatMemory";

  /**
   * Optional. Identifier of the chat memory entry to reuse across invocations.
   * Used together with {@link #PARAM_NAME_USE_CHAT_MEMORY}. When the flag is
   * active and this parameter is {@code null} or empty, the connector generates
   * a new UUID and exposes it as the {@link #PARAM_NAME_MEMORY_ID} output so
   * the caller can persist it (e.g. into a process variable) and pass it back
   * on the next invocation.
   */
  String PARAM_NAME_MEMORY_ID = "memoryId";

  /**
   * Optional. Sliding window size (max number of messages retained) for the
   * chat memory of a given {@link #PARAM_NAME_MEMORY_ID}. Defaults to
   * {@value AgentConnectorConstants#DEFAULT_CHAT_MEMORY_MAX_MESSAGES}.
   */
  String PARAM_NAME_CHAT_MEMORY_MAX_MESSAGES = "chatMemoryMaxMessages";

  /**
   * Optional. Per-activity override for the EU AI Act chat-log audit variable
   * ({@code cibseven-connect-ai-agent_<activityId>}). Tri-state:
   * <ul>
   *   <li>{@code null} / empty / unset — fall through to the deployment-wide
   *       default (system property {@code cibseven.connect.ai-agent.chatLogVariable.enabled}
   *       or env var {@code CIBSEVEN_CONNECT_AI_AGENT_CHAT_LOG_VARIABLE_ENABLED};
   *       built-in default {@code true} for compliance).</li>
   *   <li>{@code true} — write the chat-log variable for this activity,
   *       overriding a global {@code false}.</li>
   *   <li>{@code false} — skip the chat-log variable for this activity,
   *       overriding a global {@code true}.</li>
   * </ul>
   * In all cases the in-memory event timeline is still built and still
   * emitted to SLF4J / the {@code HistoryEventHandler} chain, so external
   * audit sinks keep working. Disabling here without an external sink breaks
   * EU AI Act Art. 12 / 26(6) record-keeping — the modeler is responsible
   * for that trade-off.
   */
  String PARAM_NAME_PERSIST_CHAT_LOG = "persistChatLog";

  // ── RAG / pgvector input parameter names ──────────────────────────────────

  /**
   * Optional. PostgreSQL host for the pgvector embedding store.
   * When present, RAG is activated for this request.
   * Example: {@code "localhost"} or {@code "pg.internal.example.com"}
   */
  String PARAM_NAME_PG_HOST = "pgHost";

  /** Optional. PostgreSQL port. Defaults to {@value AgentConnectorConstants#DEFAULT_PG_PORT}. */
  String PARAM_NAME_PG_PORT = "pgPort";

  /** Optional. PostgreSQL database name. */
  String PARAM_NAME_PG_DATABASE = "pgDatabase";

  /** Optional. PostgreSQL user. */
  String PARAM_NAME_PG_USER = "pgUser";

  /** Optional. PostgreSQL password. */
  String PARAM_NAME_PG_PASSWORD = "pgPassword";

  /**
   * Optional. Name of the pgvector table holding embeddings.
   * Defaults to {@value AgentConnectorConstants#DEFAULT_PG_TABLE}.
   */
  String PARAM_NAME_PG_TABLE = "pgTable";

  /**
   * Optional. Maximum number of documents returned by the retriever per query.
   * Defaults to {@value AgentConnectorConstants#DEFAULT_MAX_RAG_RESULTS}.
   */
  String PARAM_NAME_MAX_RAG_RESULTS = "maxRagResults";

  /**
   * Optional. Minimum cosine-similarity score (0.0–1.0) for retrieved documents.
   * Defaults to {@value AgentConnectorConstants#DEFAULT_MIN_RAG_SCORE}.
   */
  String PARAM_NAME_MIN_RAG_SCORE = "minRagScore";

  /**
   * Optional. Dimension of the embedding vectors stored in pgvector.
   * Must match the model used during ingestion.
   * Defaults to {@value AgentConnectorConstants#DEFAULT_EMBEDDING_DIMENSION}
   * (AllMiniLmL6V2EmbeddingModel).
   */
  String PARAM_NAME_EMBEDDING_DIMENSION = "embeddingDimension";

  /**
   * Optional. Embedding model name (e.g. {@code "text-embedding-3-small"}).
   * When set, {@code OpenAiEmbeddingModel} is used with the supplied {@code apiKey}.
   * When empty or absent, the local {@code AllMiniLmL6V2EmbeddingModel} is used as fallback.
   */
  String PARAM_NAME_EMBEDDING_MODEL_NAME = "embeddingModelName";

  // ── Output parameter names ─────────────────────────────────────────────────

  /** The final text response produced by the agent. */
  String PARAM_NAME_OUTPUT = "output";

  /**
   * EU AI Act Art. 50(2) machine-readable marker for AI-generated output.
   *
   * <p>A {@code Map<String,Object>} carrying {@code aiGenerated=true},
   * the {@code runId} that produced the answer, the model identity
   * ({@code provider}, {@code model}, {@code responseId}), and a
   * {@code generatedAt} ISO-8601 timestamp. BPMN designers map it as a
   * sibling of the main output variable, e.g.
   * {@code <camunda:outputParameter name="agentOutput_aiMeta">${outputAiMeta}</camunda:outputParameter>},
   * so downstream Human Tasks, gateways, and history consumers can distinguish
   * AI-generated values from human-authored ones.
   */
  String PARAM_NAME_OUTPUT_AI_META = "outputAiMeta";

}
