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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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

import org.cibseven.connect.ai.agent.AgentConnectorConstants;
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

  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

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
   *
   * <p>{@code baseUrl} and {@code customHeaders} are honored on the OpenAI
   * branch, so a request that targets a private/Azure/sovereign endpoint
   * sends its ingestion embeddings to the same place — without this, the
   * entire ingested corpus silently defaults to {@code https://api.openai.com/v1}.
   */
  protected EmbeddingModel createEmbeddingModel(KnowledgeIngestorRequest request) {
    String modelName = request.getEmbeddingModelName();
    if (modelName != null && !modelName.isEmpty()) {
      String apiKey = request.getApiKey();
      if (apiKey == null || apiKey.isEmpty()) {
        apiKey = System.getenv("OPENAI_API_KEY");
      }
      String baseUrl = resolveBaseUrl(request);
      Map<String, String> customHeaders = parseCustomHeaders(request.getCustomHeaders());
      return buildOpenAiEmbeddingModel(apiKey, modelName, baseUrl, customHeaders);
    }
    return new AllMiniLmL6V2EmbeddingModel();
  }

  /**
   * Builds the OpenAI embedding model with the resolved coordinates.
   * Extracted from {@link #createEmbeddingModel(KnowledgeIngestorRequest)} so
   * tests can override it to capture the arguments the connector forwards to
   * LangChain4j.
   */
  protected EmbeddingModel buildOpenAiEmbeddingModel(String apiKey, String modelName,
      String baseUrl, Map<String, String> customHeaders) {
    OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder builder = OpenAiEmbeddingModel.builder()
        .apiKey(apiKey)
        .modelName(modelName);
    if (baseUrl != null && !baseUrl.isEmpty()) {
      builder.baseUrl(baseUrl);
    }
    if (customHeaders != null && !customHeaders.isEmpty()) {
      builder.customHeaders(customHeaders);
    }
    return builder.build();
  }

  static String resolveBaseUrl(KnowledgeIngestorRequest request) {
    String url = request.getBaseUrl();
    if (url != null && !url.isEmpty()) {
      return url;
    }
    url = System.getenv(AgentConnectorConstants.ENV_BASE_URL);
    if (url != null && !url.isEmpty()) {
      return url;
    }
    return AgentConnectorConstants.DEFAULT_BASE_URL;
  }

  static Map<String, String> parseCustomHeaders(String raw) {
    if (raw == null || raw.trim().isEmpty()) {
      return new LinkedHashMap<>();
    }
    try {
      Map<String, String> parsed = JSON_MAPPER.readValue(raw,
          new TypeReference<Map<String, String>>() {});
      return parsed != null ? parsed : new LinkedHashMap<>();
    } catch (Exception e) {
      throw new AgentConnectorException(
          "Could not parse 'customHeaders': expected a JSON object of string→string pairs", e);
    }
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
