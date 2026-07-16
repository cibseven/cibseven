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
 * BPMN connector that ingests text content into a pgvector knowledge base.
 *
 * <p>Use the connector ID {@value #ID} in a BPMN service task:
 * <pre>{@code
 * <camunda:connector>
 *   <camunda:connectorId>cibseven-knowledge-ingestor</camunda:connectorId>
 *   <camunda:inputOutput>
 *     <camunda:inputParameter name="content">${documentText}</camunda:inputParameter>
 *     <camunda:inputParameter name="source">${documentSource}</camunda:inputParameter>
 *     <camunda:inputParameter name="pgHost">localhost</camunda:inputParameter>
 *     <camunda:inputParameter name="pgDatabase">postgres</camunda:inputParameter>
 *     <camunda:inputParameter name="pgUser">my_user</camunda:inputParameter>
 *     <camunda:inputParameter name="pgPassword">${pgPassword}</camunda:inputParameter>
 *     <camunda:outputParameter name="ingestedChunks">${chunksIngested}</camunda:outputParameter>
 *   </camunda:inputOutput>
 * </camunda:connector>
 * }</pre>
 *
 * <p>After ingestion, use {@link AgentConnector} with the same pgvector parameters to
 * query the knowledge base via RAG.
 */
public interface KnowledgeIngestorConnector extends Connector<KnowledgeIngestorRequest> {

  /** Connector ID used in {@code <camunda:connectorId>}. */
  String ID = "cibseven-knowledge-ingestor";

  // ── Input parameter names ──────────────────────────────────────────────────

  /** Required. The text content to embed and store. */
  String PARAM_NAME_CONTENT = "content";

  /**
   * Optional. Source identifier (e.g. file name, URL, process instance ID).
   * Stored as metadata on each embedded segment for traceability.
   */
  String PARAM_NAME_SOURCE = "source";

  /**
   * Optional. Comma-separated {@code key=value} pairs stored as metadata on each segment.
   * Example: {@code "category=legal,author=john.doe"}
   */
  String PARAM_NAME_METADATA = "metadata";

  /**
   * Optional. Maximum characters per text chunk before splitting.
   * Defaults to {@value AgentConnectorConstants#DEFAULT_CHUNK_SIZE}.
   */
  String PARAM_NAME_CHUNK_SIZE = "chunkSize";

  /**
   * Optional. Character overlap between consecutive chunks.
   * Defaults to {@value AgentConnectorConstants#DEFAULT_CHUNK_OVERLAP}.
   */
  String PARAM_NAME_CHUNK_OVERLAP = "chunkOverlap";

  /**
   * Optional. Embedding vector dimension. Must match the model in use.
   * Defaults to {@value AgentConnectorConstants#DEFAULT_EMBEDDING_DIMENSION} (AllMiniLmL6V2).
   */
  String PARAM_NAME_EMBEDDING_DIMENSION = AgentConnector.PARAM_NAME_EMBEDDING_DIMENSION;

  // embedding model params — same names as AgentConnector for consistency
  String PARAM_NAME_API_KEY              = AgentConnector.PARAM_NAME_API_KEY;
  String PARAM_NAME_EMBEDDING_MODEL_NAME = AgentConnector.PARAM_NAME_EMBEDDING_MODEL_NAME;

  // pgvector params — same names as AgentConnector for consistency
  String PARAM_NAME_PG_HOST     = AgentConnector.PARAM_NAME_PG_HOST;
  String PARAM_NAME_PG_PORT     = AgentConnector.PARAM_NAME_PG_PORT;
  String PARAM_NAME_PG_DATABASE = AgentConnector.PARAM_NAME_PG_DATABASE;
  String PARAM_NAME_PG_USER     = AgentConnector.PARAM_NAME_PG_USER;
  String PARAM_NAME_PG_PASSWORD = AgentConnector.PARAM_NAME_PG_PASSWORD;
  String PARAM_NAME_PG_TABLE    = AgentConnector.PARAM_NAME_PG_TABLE;

  // ── Output parameter names ─────────────────────────────────────────────────

  /** Number of text chunks that were successfully embedded and stored. */
  String PARAM_NAME_CHUNKS_INGESTED = "chunksIngested";

}
