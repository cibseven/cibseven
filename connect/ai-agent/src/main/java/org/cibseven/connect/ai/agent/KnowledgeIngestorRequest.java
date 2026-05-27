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
 * Request interface for the knowledge ingestor connector.
 */
public interface KnowledgeIngestorRequest extends ConnectorRequest<KnowledgeIngestorResponse> {

  // ── Fluent setters ─────────────────────────────────────────────────────────

  KnowledgeIngestorRequest content(String content);
  KnowledgeIngestorRequest source(String source);
  KnowledgeIngestorRequest metadata(String metadata);
  KnowledgeIngestorRequest chunkSize(int chunkSize);
  KnowledgeIngestorRequest chunkOverlap(int chunkOverlap);
  KnowledgeIngestorRequest embeddingDimension(int embeddingDimension);
  KnowledgeIngestorRequest apiKey(String apiKey);
  KnowledgeIngestorRequest embeddingModelName(String embeddingModelName);
  KnowledgeIngestorRequest pgHost(String pgHost);
  KnowledgeIngestorRequest pgPort(String pgPort);
  KnowledgeIngestorRequest pgDatabase(String pgDatabase);
  KnowledgeIngestorRequest pgUser(String pgUser);
  KnowledgeIngestorRequest pgPassword(String pgPassword);
  KnowledgeIngestorRequest pgTable(String pgTable);

  // ── Typed getters ──────────────────────────────────────────────────────────

  String getContent();
  String getSource();
  String getMetadata();
  int getChunkSize();
  int getChunkOverlap();
  int getEmbeddingDimension();
  String getApiKey();
  String getEmbeddingModelName();
  String getPgHost();
  String getPgPort();
  String getPgDatabase();
  String getPgUser();
  String getPgPassword();
  String getPgTable();

}
