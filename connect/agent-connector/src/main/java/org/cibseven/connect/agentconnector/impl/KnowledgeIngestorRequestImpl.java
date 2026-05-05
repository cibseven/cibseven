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

import org.cibseven.connect.agentconnector.AgentConnectorConstants;
import org.cibseven.connect.agentconnector.KnowledgeIngestorConnector;
import org.cibseven.connect.agentconnector.KnowledgeIngestorRequest;
import org.cibseven.connect.agentconnector.KnowledgeIngestorResponse;
import org.cibseven.connect.impl.AbstractConnectorRequest;
import org.cibseven.connect.spi.Connector;

/**
 * Default implementation of {@link KnowledgeIngestorRequest}.
 */
public class KnowledgeIngestorRequestImpl
    extends AbstractConnectorRequest<KnowledgeIngestorResponse>
    implements KnowledgeIngestorRequest {

  public KnowledgeIngestorRequestImpl(Connector<KnowledgeIngestorRequest> connector) {
    super(connector);
  }

  // ── Fluent setters ─────────────────────────────────────────────────────────

  @Override
  public KnowledgeIngestorRequest content(String content) {
    setRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_CONTENT, content);
    return this;
  }

  @Override
  public KnowledgeIngestorRequest source(String source) {
    setRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_SOURCE, source);
    return this;
  }

  @Override
  public KnowledgeIngestorRequest metadata(String metadata) {
    setRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_METADATA, metadata);
    return this;
  }

  @Override
  public KnowledgeIngestorRequest chunkSize(int chunkSize) {
    setRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_CHUNK_SIZE, chunkSize);
    return this;
  }

  @Override
  public KnowledgeIngestorRequest chunkOverlap(int chunkOverlap) {
    setRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_CHUNK_OVERLAP, chunkOverlap);
    return this;
  }

  @Override
  public KnowledgeIngestorRequest embeddingDimension(int embeddingDimension) {
    setRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_EMBEDDING_DIMENSION, embeddingDimension);
    return this;
  }

  @Override
  public KnowledgeIngestorRequest apiKey(String apiKey) {
    setRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_API_KEY, apiKey);
    return this;
  }

  @Override
  public KnowledgeIngestorRequest embeddingModelName(String embeddingModelName) {
    setRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_EMBEDDING_MODEL_NAME, embeddingModelName);
    return this;
  }

  @Override
  public KnowledgeIngestorRequest pgHost(String pgHost) {
    setRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_PG_HOST, pgHost);
    return this;
  }

  @Override
  public KnowledgeIngestorRequest pgPort(String pgPort) {
    setRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_PG_PORT, pgPort);
    return this;
  }

  @Override
  public KnowledgeIngestorRequest pgDatabase(String pgDatabase) {
    setRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_PG_DATABASE, pgDatabase);
    return this;
  }

  @Override
  public KnowledgeIngestorRequest pgUser(String pgUser) {
    setRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_PG_USER, pgUser);
    return this;
  }

  @Override
  public KnowledgeIngestorRequest pgPassword(String pgPassword) {
    setRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_PG_PASSWORD, pgPassword);
    return this;
  }

  @Override
  public KnowledgeIngestorRequest pgTable(String pgTable) {
    setRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_PG_TABLE, pgTable);
    return this;
  }

  // ── Typed getters ──────────────────────────────────────────────────────────

  @Override
  public String getContent() {
    return getRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_CONTENT);
  }

  @Override
  public String getSource() {
    return getRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_SOURCE);
  }

  @Override
  public String getMetadata() {
    return getRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_METADATA);
  }

  @Override
  public int getChunkSize() {
    Object val = getRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_CHUNK_SIZE);
    if (val == null) return AgentConnectorConstants.DEFAULT_CHUNK_SIZE;
    if (val instanceof Integer) return (Integer) val;
    return Integer.parseInt(val.toString());
  }

  @Override
  public int getChunkOverlap() {
    Object val = getRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_CHUNK_OVERLAP);
    if (val == null) return AgentConnectorConstants.DEFAULT_CHUNK_OVERLAP;
    if (val instanceof Integer) return (Integer) val;
    return Integer.parseInt(val.toString());
  }

  @Override
  public int getEmbeddingDimension() {
    Object val = getRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_EMBEDDING_DIMENSION);
    if (val == null) return AgentConnectorConstants.DEFAULT_EMBEDDING_DIMENSION;
    if (val instanceof Integer) return (Integer) val;
    return Integer.parseInt(val.toString());
  }

  @Override
  public String getApiKey() {
    return getRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_API_KEY);
  }

  @Override
  public String getEmbeddingModelName() {
    return getRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_EMBEDDING_MODEL_NAME);
  }

  @Override
  public String getPgHost() {
    return getRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_PG_HOST);
  }

  @Override
  public String getPgPort() {
    String port = getRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_PG_PORT);
    return (port != null) ? port : AgentConnectorConstants.DEFAULT_PG_PORT;
  }

  @Override
  public String getPgDatabase() {
    return getRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_PG_DATABASE);
  }

  @Override
  public String getPgUser() {
    return getRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_PG_USER);
  }

  @Override
  public String getPgPassword() {
    return getRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_PG_PASSWORD);
  }

  @Override
  public String getPgTable() {
    String table = getRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_PG_TABLE);
    return (table != null) ? table : AgentConnectorConstants.DEFAULT_PG_TABLE;
  }

  @Override
  protected boolean isRequestValid() {
    return getContent() != null && !getContent().isEmpty()
        && getPgHost() != null && !getPgHost().isEmpty();
  }

}
