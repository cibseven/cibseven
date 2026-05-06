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

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;

import org.cibseven.connect.ai.agent.KnowledgeIngestorConnector;
import org.cibseven.connect.ai.agent.KnowledgeIngestorRequest;
import org.cibseven.connect.ai.agent.KnowledgeIngestorResponse;
import org.cibseven.connect.impl.AbstractConnector;

/**
 * Core implementation of the knowledge ingestor connector.
 *
 * <h3>Execution flow per invocation:</h3>
 * <ol>
 *   <li>Split the input {@code content} into chunks using a recursive splitter.</li>
 *   <li>Attach {@code source} and any additional {@code metadata} key=value pairs.</li>
 *   <li>Build a local {@code AllMiniLmL6V2EmbeddingModel} (no external service required).</li>
 *   <li>Connect to pgvector and create the table if it does not exist.</li>
 *   <li>Embed and store all chunks via {@code EmbeddingStoreIngestor}.</li>
 *   <li>Return a {@link KnowledgeIngestorResponse} with the number of ingested chunks.</li>
 * </ol>
 */
public class KnowledgeIngestorConnectorImpl
    extends AbstractConnector<KnowledgeIngestorRequest, KnowledgeIngestorResponse>
    implements KnowledgeIngestorConnector {

  private static final Logger LOG = LoggerFactory.getLogger(KnowledgeIngestorConnectorImpl.class);

  public KnowledgeIngestorConnectorImpl() {
    super(KnowledgeIngestorConnector.ID);
  }

  @Override
  public KnowledgeIngestorRequest createRequest() {
    return new KnowledgeIngestorRequestImpl(this);
  }

  @Override
  public KnowledgeIngestorResponse execute(KnowledgeIngestorRequest request) {
    Document document = buildDocument(request);
    LOG.info("Ingesting document: contentLength={} chars, source={}, chunkSize={}, chunkOverlap={}, dimension={}",
        document.text().length(), request.getSource(),
        request.getChunkSize(), request.getChunkOverlap(), request.getEmbeddingDimension());

    DocumentSplitter splitter = DocumentSplitters.recursive(
        request.getChunkSize(),
        request.getChunkOverlap());

    List<TextSegment> segments = splitter.split(document);
    LOG.info("Document split into {} chunk(s). Embedding and storing into table={} on host={}:{}",
        segments.size(), request.getPgTable(), request.getPgHost(), request.getPgPort());

    EmbeddingModel embeddingModel = createEmbeddingModel(request);
    EmbeddingStore<TextSegment> store = createEmbeddingStore(request);

    List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
    store.addAll(embeddings, segments);

    LOG.info("Successfully ingested {} chunk(s) into pgvector table={}",
        segments.size(), request.getPgTable());
    return new KnowledgeIngestorResponseImpl(segments.size());
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private Document buildDocument(KnowledgeIngestorRequest request) {
    Metadata metadata = new Metadata();
    String source = request.getSource();
    if (source != null && !source.isEmpty()) {
      metadata.put("source", source);
    }
    String rawMetadata = request.getMetadata();
    if (rawMetadata != null && !rawMetadata.isEmpty()) {
      for (String pair : rawMetadata.split(",")) {
        String[] kv = pair.trim().split("=", 2);
        if (kv.length == 2) {
          metadata.put(kv[0].trim(), kv[1].trim());
        }
      }
    }
    return Document.from(request.getContent(), metadata);
  }

  /**
   * Factory method — override in tests to inject a stubbed embedding model.
   * Uses {@code OpenAiEmbeddingModel} when {@code embeddingModelName} is set,
   * otherwise falls back to the local {@code AllMiniLmL6V2EmbeddingModel}.
   */
  protected EmbeddingModel createEmbeddingModel(KnowledgeIngestorRequest request) {
    String modelName = request.getEmbeddingModelName();
    if (modelName != null && !modelName.isEmpty()) {
      String apiKey = request.getApiKey();
      if (apiKey == null || apiKey.isEmpty()) {
        apiKey = System.getenv("OPENAI_API_KEY");
      }
      return OpenAiEmbeddingModel.builder()
          .apiKey(apiKey)
          .modelName(modelName)
          .build();
    }
    return new AllMiniLmL6V2EmbeddingModel();
  }

  /** Factory method — override in tests to inject a stubbed embedding store. */
  protected EmbeddingStore<TextSegment> createEmbeddingStore(KnowledgeIngestorRequest request) {
    LOG.debug("Connecting to pgvector: host={}:{}, db={}, table={}, dimension={}",
        request.getPgHost(), request.getPgPort(),
        request.getPgDatabase(), request.getPgTable(), request.getEmbeddingDimension());
    return PgVectorEmbeddingStore.builder()
        .host(request.getPgHost())
        .port(Integer.parseInt(request.getPgPort()))
        .database(request.getPgDatabase())
        .user(request.getPgUser())
        .password(request.getPgPassword())
        .table(request.getPgTable())
        .dimension(request.getEmbeddingDimension())
        .createTable(true)
        .useIndex(false) // was true
        //.indexListSize(100)
        .build();

        /*
        CREATE INDEX ON public.embeddings
        USING hnsw (embedding vector_l2_ops)
        WITH (m = 4, ef_construction = 10);
        */
  }

}
