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
package org.cibseven.connect.agentconnector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import org.cibseven.connect.agentconnector.impl.KnowledgeIngestorConnectorImpl;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the RAG ingest → retrieval pipeline using an in-memory embedding store
 * so no pgvector instance is required.
 */
public class RagPipelineTest {

  private static final String KNOWLEDGE =
      "CIB seven is an open-source BPMN workflow engine. "
      + "It supports BPMN 2.0 processes, connectors, and service tasks. "
      + "The engine can be embedded in Java applications or run as a standalone server. "
      + "CIB seven is based on the Camunda 7 codebase and fully compatible with it.";

  private InMemoryEmbeddingStore<TextSegment> embeddingStore;
  private EmbeddingModel embeddingModel;

  @Before
  public void setUp() {
    embeddingStore = new InMemoryEmbeddingStore<>();
    embeddingModel = new AllMiniLmL6V2EmbeddingModel();
  }

  // ── Ingest ─────────────────────────────────────────────────────────────────

  @Test
  public void shouldIngestContentAndReturnChunkCount() {
    KnowledgeIngestorConnectorImpl ingestor = ingestorWithInMemoryStore();

    KnowledgeIngestorResponse response = ingestor.createRequest()
        .content(KNOWLEDGE)
        .source("test-doc")
        .pgHost("localhost")   // required for isRequestValid(); not used (store is overridden)
        .execute();

    assertThat(response.getChunksIngested()).isGreaterThan(0);
  }

  @Test
  public void shouldStoreEmbeddingsInStore() {
    KnowledgeIngestorConnectorImpl ingestor = ingestorWithInMemoryStore();

    ingestor.createRequest()
        .content(KNOWLEDGE)
        .pgHost("localhost")
        .execute();

    // Verify store is non-empty by performing a search
    ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
        .embeddingStore(embeddingStore)
        .embeddingModel(embeddingModel)
        .maxResults(5)
        .minScore(0.0)
        .build();
    List<Content> results = retriever.retrieve(Query.from("CIB seven"));
    assertThat(results).isNotEmpty();
  }

  // ── Retrieval ──────────────────────────────────────────────────────────────

  @Test
  public void shouldRetrieveRelevantContextAfterIngestion() {
    KnowledgeIngestorConnectorImpl ingestor = ingestorWithInMemoryStore();
    ingestor.createRequest().content(KNOWLEDGE).pgHost("localhost").execute();

    ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
        .embeddingStore(embeddingStore)
        .embeddingModel(embeddingModel)
        .maxResults(5)
        .minScore(0.0)
        .build();

    List<Content> results = retriever.retrieve(Query.from("What is CIB seven?"));

    assertThat(results).isNotEmpty();
    String allText = results.stream()
        .map(c -> c.textSegment().text())
        .reduce("", (a, b) -> a + " " + b);
    assertThat(allText).containsIgnoringCase("CIB seven");
  }

  @Test
  public void shouldReturnMoreRelevantResultsForSpecificQuery() {
    KnowledgeIngestorConnectorImpl ingestor = ingestorWithInMemoryStore();
    ingestor.createRequest().content(KNOWLEDGE).pgHost("localhost").execute();

    ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
        .embeddingStore(embeddingStore)
        .embeddingModel(embeddingModel)
        .maxResults(3)
        .minScore(0.0)
        .build();

    List<Content> bpmnResults = retriever.retrieve(Query.from("BPMN 2.0 process support"));
    assertThat(bpmnResults).isNotEmpty();

    String allText = bpmnResults.stream()
        .map(c -> c.textSegment().text())
        .reduce("", (a, b) -> a + " " + b);
    assertThat(allText).containsIgnoringCase("BPMN");
  }

  @Test
  public void shouldReturnNoResultsWhenMinScoreIsTooHigh() {
    KnowledgeIngestorConnectorImpl ingestor = ingestorWithInMemoryStore();
    ingestor.createRequest().content(KNOWLEDGE).pgHost("localhost").execute();

    ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
        .embeddingStore(embeddingStore)
        .embeddingModel(embeddingModel)
        .maxResults(5)
        .minScore(0.9999)   // effectively impossible threshold
        .build();

    List<Content> results = retriever.retrieve(Query.from("CIB seven"));
    assertThat(results).isEmpty();
  }

  // ── Full pipeline via AgentConnectorImpl ───────────────────────────────────

  @Test
  public void shouldProvideRagContextToAgent() {
    // Ingest via connector
    KnowledgeIngestorConnectorImpl ingestor = ingestorWithInMemoryStore();
    KnowledgeIngestorResponse ingestResponse = ingestor.createRequest()
        .content(KNOWLEDGE)
        .pgHost("localhost")
        .execute();
    assertThat(ingestResponse.getChunksIngested()).isGreaterThan(0);

    // Retrieve via the same store — mirrors what AgentConnectorImpl.createContentRetriever does
    ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
        .embeddingStore(embeddingStore)
        .embeddingModel(embeddingModel)
        .maxResults(AgentConnectorConstants.DEFAULT_MAX_RAG_RESULTS)
        .minScore(AgentConnectorConstants.DEFAULT_MIN_RAG_SCORE)
        .build();

    List<Content> contents = retriever.retrieve(Query.from("What is CIB seven?"));
    assertThat(contents).isNotEmpty();
    assertThat(contents.get(0).textSegment().text()).containsIgnoringCase("CIB seven");
  }

  // ── KnowledgeIngestorRequest embeddingDimension parameter ────────────────

  @Test
  public void shouldReturnDefaultEmbeddingDimensionForIngestorRequest() {
    KnowledgeIngestorConnectorImpl ingestor = ingestorWithInMemoryStore();
    KnowledgeIngestorRequest req = ingestor.createRequest();
    assertThat(req.getEmbeddingDimension()).isEqualTo(AgentConnectorConstants.DEFAULT_EMBEDDING_DIMENSION);
  }

  @Test
  public void shouldSetEmbeddingDimensionOnIngestorRequest() {
    KnowledgeIngestorConnectorImpl ingestor = ingestorWithInMemoryStore();
    KnowledgeIngestorRequest req = ingestor.createRequest().embeddingDimension(768);
    assertThat(req.getEmbeddingDimension()).isEqualTo(768);
  }

  @Test
  public void shouldParseEmbeddingDimensionFromStringOnIngestorRequest() {
    KnowledgeIngestorConnectorImpl ingestor = ingestorWithInMemoryStore();
    KnowledgeIngestorRequest req = ingestor.createRequest();
    req.setRequestParameter(KnowledgeIngestorConnector.PARAM_NAME_EMBEDDING_DIMENSION, "1536");
    assertThat(req.getEmbeddingDimension()).isEqualTo(1536);
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private KnowledgeIngestorConnectorImpl ingestorWithInMemoryStore() {
    return new KnowledgeIngestorConnectorImpl() {
      @Override
      protected EmbeddingModel createEmbeddingModel(KnowledgeIngestorRequest request) {
        return embeddingModel;
      }
      @Override
      protected EmbeddingStore<TextSegment> createEmbeddingStore(KnowledgeIngestorRequest request) {
        return embeddingStore;
      }
    };
  }

}
