/*
 * Copyright CIB seven GmbH and/or licensed to CIB seven GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB seven licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cibseven.connect.agentconnector;

import org.cibseven.connect.spi.Connector;

/**
 * Marker interface and constants for the LangChain4j agent connector.
 *
 * <p>Use the connector ID {@value #ID} in a BPMN service task. The example below
 * shows every supported input/output parameter; only {@code agentName},
 * {@code instruction} and {@code message} are required, the rest are optional.
 * <pre>{@code
 * <camunda:connector>
 *     <camunda:connectorId>langchain4j-agent-connector</camunda:connectorId>
 *     <camunda:inputOutput>
 *       <!-- Core agent configuration -->
 *       <camunda:inputParameter name="agentName">my-agent</camunda:inputParameter>
 *       <camunda:inputParameter name="agentDescription">Customer support agent</camunda:inputParameter>
 *       <camunda:inputParameter name="instruction">You are a helpful assistant.</camunda:inputParameter>
 *       <camunda:inputParameter name="model">gpt-5.4-nano</camunda:inputParameter>
 *       <camunda:inputParameter name="message">${userMessage}</camunda:inputParameter>
 *
 *       <!-- LLM endpoint / authentication -->
 *       <camunda:inputParameter name="baseUrl">http://localhost:11434/v1</camunda:inputParameter>
 *       <camunda:inputParameter name="apiKey">${secrets.OPENAI_API_KEY}</camunda:inputParameter>
 *       <camunda:inputParameter name="openaiCustomHeaders">Authorization: Basic dXNlcjpwYXNz|X-Tenant: acme</camunda:inputParameter>
 *
 *       <!-- Reasoning controls -->
 *       <camunda:inputParameter name="reasoningEffort">medium</camunda:inputParameter>
 *       <camunda:inputParameter name="reasoningSummary">auto</camunda:inputParameter>
 *
 *       <!-- Tools: Java @Tool classes -->
 *       <camunda:inputParameter name="toolClasses">com.example.WeatherTools,com.example.CalendarTools</camunda:inputParameter>
 *
 *       <!-- Tools: legacy single MCP server -->
 *       <camunda:inputParameter name="mcpServerUrl">http://localhost:3000/sse</camunda:inputParameter>
 *       <camunda:inputParameter name="mcpCustomHeaders">Authorization: Bearer abc123|X-Workspace: default</camunda:inputParameter>
 *
 *       <!-- Tools: multiple MCP servers (JSON) -->
 *       <camunda:inputParameter name="mcpServers">
 *         [
 *           {"url": "http://server1/mcp", "headers": {"Authorization": "Bearer abc"}},
 *           {"url": "http://server2/mcp"}
 *         ]
 *       </camunda:inputParameter>
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
 *       <!-- Chat log persistence (optional) -->
 *       <camunda:inputParameter name="chatLogVariable">agentChatLog</camunda:inputParameter>
 *
 *       <!-- Output -->
 *       <camunda:outputParameter name="agentOutput">${output}</camunda:outputParameter>
 *     </camunda:inputOutput>
 * </camunda:connector>
 * }</pre>
 *
 * <p>When {@link #PARAM_NAME_CHAT_LOG_VARIABLE chatLogVariable} is set, the connector
 * persists the chat log as a process-scoped variable with that name and updates it on
 * every {@code request}/{@code response}/{@code error} event of the underlying chat
 * model. If a variable with the configured name already exists when the connector
 * runs, its content is deserialised and used as the starting point — so multiple
 * invocations within the same process instance accumulate into a single timeline.
 * The chat log is no longer exposed as an output parameter because its serialised
 * form regularly exceeds the {@code VARCHAR(4000)} limit of Camunda's TEXT_ column.
 */
public interface AgentConnector extends Connector<AgentRequest> {

  /** Connector ID used in {@code <camunda:connectorId>}. */
  String ID = "langchain4j-agent-connector";

  // ── Input parameter names ──────────────────────────────────────────────────

  /** Required. Logical name of the agent (passed to {@code LlmAgent.builder().name()}). */
  String PARAM_NAME_AGENT_NAME = "agentName";

  /** Optional. Human-readable description of the agent. */
  String PARAM_NAME_AGENT_DESCRIPTION = "agentDescription";

  /** Required. System instruction for the LLM. */
  String PARAM_NAME_INSTRUCTION = "instruction";

  /**
   * Optional. Gemini model identifier.
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
   * Optional. MCP (Model Context Protocol) server URL (HTTP+SSE transport).
   * When provided, tools exposed by the MCP server are made available to the agent
   * in addition to any {@code toolClasses}.
   * Example: {@code "http://localhost:3000/sse"}
   */
  String PARAM_NAME_MCP_SERVER_URL = "mcpServerUrl";

  /**
   * Optional. Override the {@code OPENAI_API_KEY} for this specific request.
   * The environment variable {@code OPENAI_API_KEY} is the recommended approach.
   */
  String PARAM_NAME_API_KEY = "apiKey";

  /**
   * Optional. Custom HTTP headers attached to every request sent to the OpenAI-compatible
   * endpoint. Format: {@code key: value} pairs separated by {@code |}.
   * Example: {@code "Authorization: Basic dXNlcjpwYXNz|X-Tenant: acme"}
   */
  String PARAM_NAME_OPENAI_CUSTOM_HEADERS = "openaiCustomHeaders";

  /**
   * Optional. Custom HTTP headers attached to every request sent to the MCP server
   * configured via {@link #PARAM_NAME_MCP_SERVER_URL}. Has no effect on entries declared
   * in {@link #PARAM_NAME_MCP_SERVERS} — those carry their own headers.
   * Format: {@code key: value} pairs separated by {@code |}.
   * Example: {@code "Authorization: Bearer abc123|X-Workspace: default"}
   */
  String PARAM_NAME_MCP_CUSTOM_HEADERS = "mcpCustomHeaders";

  /**
   * Optional. JSON array describing one or more MCP servers, each with its own
   * URL and (optional) custom HTTP headers. Tools exposed by each server are
   * registered with the agent in addition to the legacy single-server fields
   * {@link #PARAM_NAME_MCP_SERVER_URL} / {@link #PARAM_NAME_MCP_CUSTOM_HEADERS}
   * and any {@code toolClasses}.
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

  /**
   * Optional. Name of the process-scoped variable where the JSON-serialised chat
   * log is persisted. When the variable already exists at connector invocation
   * time, its content is decoded and the new events are appended; otherwise the
   * variable is created. The variable is updated after every request/response/error
   * callback of the underlying chat model. When this parameter is empty or absent,
   * no chat log is persisted.
   */
  String PARAM_NAME_CHAT_LOG_VARIABLE = "chatLogVariable";

  // ── Output parameter names ─────────────────────────────────────────────────

  /** The final text response produced by the agent. */
  String PARAM_NAME_OUTPUT = "output";

}
