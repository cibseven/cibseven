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
package org.cibseven.connect.agentconnector.impl;

import org.cibseven.connect.agentconnector.AgentConnector;
import org.cibseven.connect.agentconnector.AgentConnectorConstants;
import org.cibseven.connect.agentconnector.AgentRequest;
import org.cibseven.connect.agentconnector.AgentResponse;
import org.cibseven.connect.impl.AbstractConnectorRequest;
import org.cibseven.connect.spi.Connector;

/**
 * Default implementation of {@link AgentRequest}.
 *
 * <p>Stores all parameters in the generic request-parameter map inherited from
 * {@link AbstractConnectorRequest} so that they are automatically available to any
 * registered {@link org.cibseven.connect.spi.ConnectorRequestInterceptor}.
 */
public class AgentRequestImpl extends AbstractConnectorRequest<AgentResponse> implements AgentRequest {

  public AgentRequestImpl(Connector<AgentRequest> connector) {
    super(connector);
  }

  // ── Fluent setters ─────────────────────────────────────────────────────────

  @Override
  public AgentRequest agentName(String agentName) {
    setRequestParameter(AgentConnector.PARAM_NAME_AGENT_NAME, agentName);
    return this;
  }

  @Override
  public AgentRequest agentDescription(String agentDescription) {
    setRequestParameter(AgentConnector.PARAM_NAME_AGENT_DESCRIPTION, agentDescription);
    return this;
  }

  @Override
  public AgentRequest instruction(String instruction) {
    setRequestParameter(AgentConnector.PARAM_NAME_INSTRUCTION, instruction);
    return this;
  }

  @Override
  public AgentRequest model(String model) {
    setRequestParameter(AgentConnector.PARAM_NAME_MODEL, model);
    return this;
  }

  @Override
  public AgentRequest message(String message) {
    setRequestParameter(AgentConnector.PARAM_NAME_MESSAGE, message);
    return this;
  }

  @Override
  public AgentRequest toolClasses(String toolClasses) {
    setRequestParameter(AgentConnector.PARAM_NAME_TOOL_CLASSES, toolClasses);
    return this;
  }

  @Override
  public AgentRequest apiKey(String apiKey) {
    setRequestParameter(AgentConnector.PARAM_NAME_API_KEY, apiKey);
    return this;
  }

  @Override
  public AgentRequest baseUrl(String baseUrl) {
    setRequestParameter(AgentConnector.PARAM_NAME_BASE_URL, baseUrl);
    return this;
  }

  @Override
  public AgentRequest mcpServerUrl(String mcpServerUrl) {
    setRequestParameter(AgentConnector.PARAM_NAME_MCP_SERVER_URL, mcpServerUrl);
    return this;
  }

  @Override
  public AgentRequest openaiCustomHeaders(String openaiCustomHeaders) {
    setRequestParameter(AgentConnector.PARAM_NAME_OPENAI_CUSTOM_HEADERS, openaiCustomHeaders);
    return this;
  }

  @Override
  public AgentRequest mcpCustomHeaders(String mcpCustomHeaders) {
    setRequestParameter(AgentConnector.PARAM_NAME_MCP_CUSTOM_HEADERS, mcpCustomHeaders);
    return this;
  }

  @Override
  public AgentRequest mcpServers(String mcpServers) {
    setRequestParameter(AgentConnector.PARAM_NAME_MCP_SERVERS, mcpServers);
    return this;
  }

  @Override
  public AgentRequest reasoningEffort(String reasoningEffort) {
    setRequestParameter(AgentConnector.PARAM_NAME_REASONING_EFFORT, reasoningEffort);
    return this;
  }

  @Override
  public AgentRequest reasoningSummary(String reasoningSummary) {
    setRequestParameter(AgentConnector.PARAM_NAME_REASONING_SUMMARY, reasoningSummary);
    return this;
  }

  // ── RAG / pgvector setters ─────────────────────────────────────────────────

  @Override
  public AgentRequest pgHost(String pgHost) {
    setRequestParameter(AgentConnector.PARAM_NAME_PG_HOST, pgHost);
    return this;
  }

  @Override
  public AgentRequest pgPort(String pgPort) {
    setRequestParameter(AgentConnector.PARAM_NAME_PG_PORT, pgPort);
    return this;
  }

  @Override
  public AgentRequest pgDatabase(String pgDatabase) {
    setRequestParameter(AgentConnector.PARAM_NAME_PG_DATABASE, pgDatabase);
    return this;
  }

  @Override
  public AgentRequest pgUser(String pgUser) {
    setRequestParameter(AgentConnector.PARAM_NAME_PG_USER, pgUser);
    return this;
  }

  @Override
  public AgentRequest pgPassword(String pgPassword) {
    setRequestParameter(AgentConnector.PARAM_NAME_PG_PASSWORD, pgPassword);
    return this;
  }

  @Override
  public AgentRequest pgTable(String pgTable) {
    setRequestParameter(AgentConnector.PARAM_NAME_PG_TABLE, pgTable);
    return this;
  }

  @Override
  public AgentRequest maxRagResults(int maxRagResults) {
    setRequestParameter(AgentConnector.PARAM_NAME_MAX_RAG_RESULTS, maxRagResults);
    return this;
  }

  @Override
  public AgentRequest minRagScore(double minRagScore) {
    setRequestParameter(AgentConnector.PARAM_NAME_MIN_RAG_SCORE, minRagScore);
    return this;
  }

  @Override
  public AgentRequest embeddingDimension(int embeddingDimension) {
    setRequestParameter(AgentConnector.PARAM_NAME_EMBEDDING_DIMENSION, embeddingDimension);
    return this;
  }

  @Override
  public AgentRequest embeddingModelName(String embeddingModelName) {
    setRequestParameter(AgentConnector.PARAM_NAME_EMBEDDING_MODEL_NAME, embeddingModelName);
    return this;
  }

  // ── Typed getters ──────────────────────────────────────────────────────────

  @Override
  public String getAgentName() {
    return getRequestParameter(AgentConnector.PARAM_NAME_AGENT_NAME);
  }

  @Override
  public String getAgentDescription() {
    return getRequestParameter(AgentConnector.PARAM_NAME_AGENT_DESCRIPTION);
  }

  @Override
  public String getInstruction() {
    return getRequestParameter(AgentConnector.PARAM_NAME_INSTRUCTION);
  }

  @Override
  public String getModel() {
    String model = getRequestParameter(AgentConnector.PARAM_NAME_MODEL);
    return (model != null) ? model : AgentConnectorConstants.DEFAULT_MODEL;
  }

  @Override
  public String getMessage() {
    return getRequestParameter(AgentConnector.PARAM_NAME_MESSAGE);
  }

  @Override
  public String getToolClasses() {
    return getRequestParameter(AgentConnector.PARAM_NAME_TOOL_CLASSES);
  }

  @Override
  public String getApiKey() {
    return getRequestParameter(AgentConnector.PARAM_NAME_API_KEY);
  }

  @Override
  public String getBaseUrl() {
    return getRequestParameter(AgentConnector.PARAM_NAME_BASE_URL);
  }

  @Override
  public String getMcpServerUrl() {
    return getRequestParameter(AgentConnector.PARAM_NAME_MCP_SERVER_URL);
  }

  @Override
  public String getOpenaiCustomHeaders() {
    return getRequestParameter(AgentConnector.PARAM_NAME_OPENAI_CUSTOM_HEADERS);
  }

  @Override
  public String getMcpCustomHeaders() {
    return getRequestParameter(AgentConnector.PARAM_NAME_MCP_CUSTOM_HEADERS);
  }

  @Override
  public String getMcpServers() {
    return getRequestParameter(AgentConnector.PARAM_NAME_MCP_SERVERS);
  }

  @Override
  public String getReasoningEffort() {
    return getRequestParameter(AgentConnector.PARAM_NAME_REASONING_EFFORT);
  }

  @Override
  public String getReasoningSummary() {
    return getRequestParameter(AgentConnector.PARAM_NAME_REASONING_SUMMARY);
  }

  // ── RAG / pgvector getters ─────────────────────────────────────────────────

  @Override
  public String getPgHost() {
    return getRequestParameter(AgentConnector.PARAM_NAME_PG_HOST);
  }

  @Override
  public String getPgPort() {
    String port = getRequestParameter(AgentConnector.PARAM_NAME_PG_PORT);
    return (port != null) ? port : AgentConnectorConstants.DEFAULT_PG_PORT;
  }

  @Override
  public String getPgDatabase() {
    return getRequestParameter(AgentConnector.PARAM_NAME_PG_DATABASE);
  }

  @Override
  public String getPgUser() {
    return getRequestParameter(AgentConnector.PARAM_NAME_PG_USER);
  }

  @Override
  public String getPgPassword() {
    return getRequestParameter(AgentConnector.PARAM_NAME_PG_PASSWORD);
  }

  @Override
  public String getPgTable() {
    String table = getRequestParameter(AgentConnector.PARAM_NAME_PG_TABLE);
    return (table != null) ? table : AgentConnectorConstants.DEFAULT_PG_TABLE;
  }

  @Override
  public int getMaxRagResults() {
    Object val = getRequestParameter(AgentConnector.PARAM_NAME_MAX_RAG_RESULTS);
    if (val == null) return AgentConnectorConstants.DEFAULT_MAX_RAG_RESULTS;
    if (val instanceof Integer) return (Integer) val;
    return Integer.parseInt(val.toString());
  }

  @Override
  public double getMinRagScore() {
    Object val = getRequestParameter(AgentConnector.PARAM_NAME_MIN_RAG_SCORE);
    if (val == null) return AgentConnectorConstants.DEFAULT_MIN_RAG_SCORE;
    if (val instanceof Double) return (Double) val;
    return Double.parseDouble(val.toString());
  }

  @Override
  public int getEmbeddingDimension() {
    Object val = getRequestParameter(AgentConnector.PARAM_NAME_EMBEDDING_DIMENSION);
    if (val == null) return AgentConnectorConstants.DEFAULT_EMBEDDING_DIMENSION;
    if (val instanceof Integer) return (Integer) val;
    return Integer.parseInt(val.toString());
  }

  @Override
  public String getEmbeddingModelName() {
    return getRequestParameter(AgentConnector.PARAM_NAME_EMBEDDING_MODEL_NAME);
  }

  @Override
  protected boolean isRequestValid() {
    return getAgentName() != null && !getAgentName().isEmpty()
        && getInstruction() != null && !getInstruction().isEmpty()
        && getMessage() != null && !getMessage().isEmpty();
  }

}
