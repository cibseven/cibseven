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
   * Optional. MCP server SSE URL (e.g. {@code "http://localhost:3000/sse"}).
   * When set, tools exposed by the MCP server are registered with the agent.
   */
  AgentRequest mcpServerUrl(String mcpServerUrl);

  /**
   * Optional. Custom HTTP headers attached to every OpenAI request,
   * encoded as {@code key: value} pairs separated by {@code |}.
   */
  AgentRequest openaiCustomHeaders(String openaiCustomHeaders);

  /**
   * Optional. Custom HTTP headers attached to every MCP request to the server
   * configured via {@link #mcpServerUrl(String)}, encoded as {@code key: value}
   * pairs separated by {@code |}.
   */
  AgentRequest mcpCustomHeaders(String mcpCustomHeaders);

  /**
   * Optional. JSON array describing one or more MCP servers with per-server
   * custom headers. See {@link AgentConnector#PARAM_NAME_MCP_SERVERS} for the
   * expected shape. Combined with the legacy {@link #mcpServerUrl(String)}
   * field — both contribute clients to the agent.
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

  String getModel();

  String getMessage();

  String getToolClasses();

  String getApiKey();

  String getBaseUrl();

  String getMcpServerUrl();

  String getOpenaiCustomHeaders();

  String getMcpCustomHeaders();

  String getMcpServers();

  String getReasoningEffort();

  String getReasoningSummary();

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
