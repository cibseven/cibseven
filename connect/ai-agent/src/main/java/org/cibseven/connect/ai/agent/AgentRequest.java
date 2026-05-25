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

import org.cibseven.connect.spi.ConnectorRequest;

/**
 * Request interface for the LangChain4j agent connector.
 *
 * <p>Instances are obtained via {@link AgentConnector#createRequest()} and configure
 * the LLM agent that will be invoked when {@link #execute()} is called.
 */
public interface AgentRequest extends ConnectorRequest<AgentResponse> {

  // ── Fluent setters ─────────────────────────────────────────────────────────

  AgentRequest agentName(String agentName);

  AgentRequest agentDescription(String agentDescription);

  AgentRequest instruction(String instruction);

  /**
   * Optional. Controls how {@link #instruction(String)} is combined with the
   * bundled default system prompt. See
   * {@link AgentConnector#PARAM_NAME_INSTRUCTION_MODE} for allowed values.
   */
  AgentRequest instructionMode(String instructionMode);

  AgentRequest model(String model);

  AgentRequest message(String message);

  /**
   * Registers tool classes for this request.
   *
   * @param toolClasses comma-separated list of fully qualified class names
   */
  AgentRequest toolClasses(String toolClasses);

  AgentRequest apiKey(String apiKey);

  /**
   * Optional. OpenAI-compatible base URL.
   * When omitted the value is resolved from the {@code OPENAI_BASE_URL} environment variable,
   * falling back to the standard OpenAI API endpoint.
   * Examples: Ollama ({@code http://localhost:11434/v1}), Azure OpenAI,
   * OpenRouter ({@code https://openrouter.ai/api/v1}).
   */
  AgentRequest baseUrl(String baseUrl);

  /**
   * Optional. Custom HTTP headers attached to every OpenAI-compatible request,
   * encoded as a JSON object of {@code String} → {@code String} pairs.
   */
  AgentRequest customHeaders(String customHeaders);

  /**
   * Optional. JSON array describing one or more MCP servers with per-server
   * custom headers. See {@link AgentConnector#PARAM_NAME_MCP_SERVERS} for the
   * expected shape.
   */
  AgentRequest mcpServers(String mcpServers);

  /**
   * Optional. Reasoning effort hint (e.g. {@code "low"}, {@code "medium"},
   * {@code "high"}). Values are model-dependent.
   */
  AgentRequest reasoningEffort(String reasoningEffort);

  /**
   * Optional. Enables a reasoning summary in {@code AiMessage.thinking()}
   * (e.g. {@code "auto"}). Setting this value switches to the OpenAI Responses API.
   */
  AgentRequest reasoningSummary(String reasoningSummary);

  // ── Chat memory fluent setters ─────────────────────────────────────────────

  /**
   * Enables or disables chat memory for this invocation. When enabled, the
   * agent's prior messages are loaded from {@code AgentChatMemoryStore} keyed
   * by {@link #memoryId(String)} and the new exchange is appended.
   */
  AgentRequest useChatMemory(boolean useChatMemory);

  /**
   * Optional. Memory identifier to reuse across invocations. When unset and
   * {@link #useChatMemory(boolean)} is {@code true}, the connector generates a
   * new UUID on first call.
   */
  AgentRequest memoryId(String memoryId);

  /** Sliding-window size (max messages retained) for the chat memory. */
  AgentRequest chatMemoryMaxMessages(int chatMemoryMaxMessages);

  // ── EU AI Act audit fluent setter ─────────────────────────────────────────

  /**
   * Per-activity override for the chat-log audit variable. See
   * {@link AgentConnector#PARAM_NAME_PERSIST_CHAT_LOG}. Pass {@code null} to
   * defer to the deployment-wide default.
   */
  AgentRequest persistChatLog(Boolean persistChatLog);

  // ── RAG / pgvector fluent setters ──────────────────────────────────────────

  /** PostgreSQL host — when set, RAG is activated using pgvector. */
  AgentRequest pgHost(String pgHost);
  AgentRequest pgPort(String pgPort);
  AgentRequest pgDatabase(String pgDatabase);
  AgentRequest pgUser(String pgUser);
  AgentRequest pgPassword(String pgPassword);
  AgentRequest pgTable(String pgTable);
  AgentRequest maxRagResults(int maxRagResults);
  AgentRequest minRagScore(double minRagScore);
  AgentRequest embeddingDimension(int embeddingDimension);
  AgentRequest embeddingModelName(String embeddingModelName);

  // ── Typed getters ──────────────────────────────────────────────────────────

  String getAgentName();

  String getAgentDescription();

  String getInstruction();

  /**
   * Returns the resolved {@code instructionMode} — falls back to
   * {@link AgentConnectorConstants#DEFAULT_INSTRUCTION_MODE} when the parameter
   * is unset or empty.
   */
  String getInstructionMode();

  String getModel();

  String getMessage();

  String getToolClasses();

  String getApiKey();

  String getBaseUrl();

  String getCustomHeaders();

  String getMcpServers();

  String getReasoningEffort();

  String getReasoningSummary();

  // ── Chat memory typed getters ──────────────────────────────────────────────

  boolean isUseChatMemory();
  String getMemoryId();
  int getChatMemoryMaxMessages();

  // ── EU AI Act audit typed getter ──────────────────────────────────────────

  /**
   * Per-activity override for the chat-log audit variable. Returns
   * {@code null} when unset (caller must fall through to the deployment-wide
   * resolver — typically {@code AgentChatListener.isChatLogVariableEnabled()}).
   */
  Boolean getPersistChatLog();

  // ── RAG / pgvector typed getters ───────────────────────────────────────────

  String getPgHost();
  String getPgPort();
  String getPgDatabase();
  String getPgUser();
  String getPgPassword();
  String getPgTable();
  int getMaxRagResults();
  double getMinRagScore();
  int getEmbeddingDimension();
  String getEmbeddingModelName();

}
