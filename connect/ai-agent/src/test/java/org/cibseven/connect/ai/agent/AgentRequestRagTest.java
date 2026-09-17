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

import static org.assertj.core.api.Assertions.assertThat;

import org.cibseven.connect.ai.agent.impl.AgentConnectorImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AgentRequestRagTest {

  private AgentConnectorImpl connector;

  @BeforeEach
  public void setUp() {
    connector = new AgentConnectorImpl();
  }

  // ── pgHost ─────────────────────────────────────────────────────────────────

  @Test
  public void shouldSetAndGetPgHost() {
    AgentRequest request = connector.createRequest().pgHost("localhost");
    assertThat(request.getPgHost()).isEqualTo("localhost");
  }

  @Test
  public void shouldReturnNullPgHostWhenNotSet() {
    AgentRequest request = connector.createRequest();
    assertThat(request.getPgHost()).isNull();
  }

  // ── pgPort ─────────────────────────────────────────────────────────────────

  @Test
  public void shouldReturnDefaultPgPortWhenNotSet() {
    AgentRequest request = connector.createRequest();
    assertThat(request.getPgPort()).isEqualTo(AgentConnectorConstants.DEFAULT_PG_PORT);
  }

  @Test
  public void shouldSetAndGetPgPort() {
    AgentRequest request = connector.createRequest().pgPort("5433");
    assertThat(request.getPgPort()).isEqualTo("5433");
  }

  // ── pgDatabase ─────────────────────────────────────────────────────────────

  @Test
  public void shouldSetAndGetPgDatabase() {
    AgentRequest request = connector.createRequest().pgDatabase("my_db");
    assertThat(request.getPgDatabase()).isEqualTo("my_db");
  }

  @Test
  public void shouldReturnNullPgDatabaseWhenNotSet() {
    AgentRequest request = connector.createRequest();
    assertThat(request.getPgDatabase()).isNull();
  }

  // ── pgUser / pgPassword ────────────────────────────────────────────────────

  @Test
  public void shouldSetAndGetPgUser() {
    AgentRequest request = connector.createRequest().pgUser("my_user");
    assertThat(request.getPgUser()).isEqualTo("my_user");
  }

  @Test
  public void shouldSetAndGetPgPassword() {
    AgentRequest request = connector.createRequest().pgPassword("secret");
    assertThat(request.getPgPassword()).isEqualTo("secret");
  }

  // ── pgTable ────────────────────────────────────────────────────────────────

  @Test
  public void shouldReturnDefaultPgTableWhenNotSet() {
    AgentRequest request = connector.createRequest();
    assertThat(request.getPgTable()).isEqualTo(AgentConnectorConstants.DEFAULT_PG_TABLE);
  }

  @Test
  public void shouldSetAndGetPgTable() {
    AgentRequest request = connector.createRequest().pgTable("my_embeddings");
    assertThat(request.getPgTable()).isEqualTo("my_embeddings");
  }

  // ── maxRagResults ──────────────────────────────────────────────────────────

  @Test
  public void shouldReturnDefaultMaxRagResultsWhenNotSet() {
    AgentRequest request = connector.createRequest();
    assertThat(request.getMaxRagResults()).isEqualTo(AgentConnectorConstants.DEFAULT_MAX_RAG_RESULTS);
  }

  @Test
  public void shouldSetAndGetMaxRagResultsAsInteger() {
    AgentRequest request = connector.createRequest().maxRagResults(3);
    assertThat(request.getMaxRagResults()).isEqualTo(3);
  }

  @Test
  public void shouldParseMaxRagResultsFromString() {
    AgentRequest request = connector.createRequest();
    request.setRequestParameter(AgentConnector.PARAM_NAME_MAX_RAG_RESULTS, "7");
    assertThat(request.getMaxRagResults()).isEqualTo(7);
  }

  // ── minRagScore ────────────────────────────────────────────────────────────

  @Test
  public void shouldReturnDefaultMinRagScoreWhenNotSet() {
    AgentRequest request = connector.createRequest();
    // Default is 0.0 (no filtering) so AllMiniLmL6V2 results are never silently dropped
    assertThat(request.getMinRagScore()).isEqualTo(0.0);
    assertThat(request.getMinRagScore()).isEqualTo(AgentConnectorConstants.DEFAULT_MIN_RAG_SCORE);
  }

  @Test
  public void shouldSetAndGetMinRagScoreAsDouble() {
    AgentRequest request = connector.createRequest().minRagScore(0.75);
    assertThat(request.getMinRagScore()).isEqualTo(0.75);
  }

  @Test
  public void shouldParseMinRagScoreFromString() {
    AgentRequest request = connector.createRequest();
    request.setRequestParameter(AgentConnector.PARAM_NAME_MIN_RAG_SCORE, "0.8");
    assertThat(request.getMinRagScore()).isEqualTo(0.8);
  }

  // ── embeddingDimension ─────────────────────────────────────────────────────

  @Test
  public void shouldReturnDefaultEmbeddingDimensionWhenNotSet() {
    AgentRequest request = connector.createRequest();
    assertThat(request.getEmbeddingDimension()).isEqualTo(AgentConnectorConstants.DEFAULT_EMBEDDING_DIMENSION);
  }

  @Test
  public void shouldSetAndGetEmbeddingDimensionAsInteger() {
    AgentRequest request = connector.createRequest().embeddingDimension(768);
    assertThat(request.getEmbeddingDimension()).isEqualTo(768);
  }

  @Test
  public void shouldParseEmbeddingDimensionFromString() {
    AgentRequest request = connector.createRequest();
    request.setRequestParameter(AgentConnector.PARAM_NAME_EMBEDDING_DIMENSION, "1536");
    assertThat(request.getEmbeddingDimension()).isEqualTo(1536);
  }

  // ── Generic map ───────────────────────────────────────────────────────────

  @Test
  public void shouldStoreRagParametersInGenericMap() {
    AgentRequest request = connector.createRequest()
        .pgHost("localhost")
        .pgDatabase("rag_db")
        .pgUser("u")
        .pgPassword("p")
        .maxRagResults(5)
        .minRagScore(0.6)
        .embeddingDimension(384);

    assertThat(request.getRequestParameters())
        .containsEntry(AgentConnector.PARAM_NAME_PG_HOST, "localhost")
        .containsEntry(AgentConnector.PARAM_NAME_PG_DATABASE, "rag_db")
        .containsEntry(AgentConnector.PARAM_NAME_PG_USER, "u")
        .containsEntry(AgentConnector.PARAM_NAME_PG_PASSWORD, "p")
        .containsEntry(AgentConnector.PARAM_NAME_MAX_RAG_RESULTS, 5)
        .containsEntry(AgentConnector.PARAM_NAME_MIN_RAG_SCORE, 0.6)
        .containsEntry(AgentConnector.PARAM_NAME_EMBEDDING_DIMENSION, 384);
  }

}
