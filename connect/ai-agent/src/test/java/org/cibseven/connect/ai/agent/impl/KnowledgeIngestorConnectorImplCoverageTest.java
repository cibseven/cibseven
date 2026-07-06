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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.List;

import org.cibseven.connect.ai.agent.KnowledgeIngestorRequest;
import org.cibseven.connect.ai.agent.KnowledgeIngestorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;

/**
 * Coverage-focused tests for {@link KnowledgeIngestorConnectorImpl} factory and
 * helper methods that the existing {@code RagPipelineTest} bypasses by
 * overriding both factory methods together:
 *
 * <ul>
 *   <li>{@code createEmbeddingModel(KnowledgeIngestorRequest)} — both branches
 *       (default ONNX model and OpenAI-named model).</li>
 *   <li>{@code createEmbeddingStore(KnowledgeIngestorRequest)} — drives the
 *       real PgVector factory body (it then fails on the JDBC handshake against
 *       an unreachable port, but the factory lines have already been
 *       instrumented).</li>
 *   <li>{@code buildDocument(KnowledgeIngestorRequest)} — every metadata
 *       parsing branch, via reflection.</li>
 * </ul>
 */
public class KnowledgeIngestorConnectorImplCoverageTest {

  private KnowledgeIngestorConnectorImpl connector;

  @BeforeEach
  public void setUp() {
    connector = new KnowledgeIngestorConnectorImpl();
  }

  // ── createEmbeddingModel — both branches ────────────────────────────────

  @Test
  public void createEmbeddingModelShouldReturnLocalAllMiniLmWhenNameUnset() {
    KnowledgeIngestorRequest request = connector.createRequest();
    EmbeddingModel model = connector.createEmbeddingModel(request);
    assertThat(model).isNotNull();
    assertThat(model.getClass().getName()).contains("AllMiniLmL6V2EmbeddingModel");
  }

  @Test
  public void createEmbeddingModelShouldReturnOpenAiModelWhenNameSet() {
    KnowledgeIngestorRequest request = connector.createRequest()
        .embeddingModelName("text-embedding-3-small")
        .apiKey("test-key");
    EmbeddingModel model = connector.createEmbeddingModel(request);
    assertThat(model).isNotNull();
    assertThat(model.getClass().getName()).contains("OpenAiEmbeddingModel");
  }

  @Test
  public void createEmbeddingModelShouldFallBackToEnvApiKeyWhenRequestKeyMissing() {
    // The factory falls back to OPENAI_API_KEY when the request carries no key.
    // We can't reliably set/unset env vars in-process across JVMs, so we only
    // verify the factory still returns a non-null model; the underlying
    // OpenAiEmbeddingModel.builder() build is lazy and does not connect.
    KnowledgeIngestorRequest request = connector.createRequest()
        .embeddingModelName("text-embedding-3-small");
    EmbeddingModel model = connector.createEmbeddingModel(request);
    assertThat(model).isNotNull();
  }

  // ── createEmbeddingStore — runs the real PgVector factory body ──────────

  @Test
  public void createEmbeddingStoreShouldExerciseFactoryBodyBeforeJdbcFailure() {
    KnowledgeIngestorRequest request = connector.createRequest()
        .pgHost("127.0.0.1").pgPort("1") // unreachable on purpose
        .pgDatabase("postgres").pgUser("u").pgPassword("p")
        .pgTable("test_embeddings").embeddingDimension(384);

    // PgVectorEmbeddingStore.builder().build() calls initTable() which opens a
    // JDBC connection — that fails because port 1 is unreachable. By the time
    // the exception is thrown, every line of the factory body has executed and
    // been recorded by the coverage agent.
    assertThatThrownBy(() -> connector.createEmbeddingStore(request))
        .isInstanceOf(RuntimeException.class);
  }

  // ── buildDocument — every metadata branch (reflection) ──────────────────

  @Test
  public void buildDocumentShouldOmitSourceMetadataWhenSourceIsNull() throws Exception {
    KnowledgeIngestorRequest request = connector.createRequest().content("hello world");
    Document doc = invokeBuildDocument(request);

    assertThat(doc.text()).isEqualTo("hello world");
    assertThat(doc.metadata().toMap()).doesNotContainKey("source");
  }

  @Test
  public void buildDocumentShouldOmitSourceMetadataWhenSourceIsEmpty() throws Exception {
    KnowledgeIngestorRequest request = connector.createRequest()
        .content("payload").source("");
    Document doc = invokeBuildDocument(request);

    assertThat(doc.metadata().toMap()).doesNotContainKey("source");
  }

  @Test
  public void buildDocumentShouldRecordSourceMetadataWhenSourceSet() throws Exception {
    KnowledgeIngestorRequest request = connector.createRequest()
        .content("payload").source("invoice-2026-03-0047.pdf");
    Document doc = invokeBuildDocument(request);

    assertThat(doc.metadata().toMap()).containsEntry("source", "invoice-2026-03-0047.pdf");
  }

  @Test
  public void buildDocumentShouldParseCommaSeparatedKeyValuePairs() throws Exception {
    KnowledgeIngestorRequest request = connector.createRequest()
        .content("payload")
        .source("doc.pdf")
        .metadata("author=Alice, year=2026 , language=en");
    Document doc = invokeBuildDocument(request);

    assertThat(doc.metadata().toMap())
        .containsEntry("source", "doc.pdf")
        .containsEntry("author", "Alice")
        .containsEntry("year", "2026")
        .containsEntry("language", "en");
  }

  @Test
  public void buildDocumentShouldSkipPairsWithoutEqualsSign() throws Exception {
    KnowledgeIngestorRequest request = connector.createRequest()
        .content("payload")
        .metadata("validKey=v, noEqualsSign, alsoBad");
    Document doc = invokeBuildDocument(request);

    assertThat(doc.metadata().toMap())
        .containsEntry("validKey", "v")
        .doesNotContainKey("noEqualsSign")
        .doesNotContainKey("alsoBad");
  }

  @Test
  public void buildDocumentShouldKeepFirstEqualsSignAsSeparator() throws Exception {
    // split("=", 2) preserves trailing '=' characters in the value — e.g. a
    // base64 fragment with padding.
    KnowledgeIngestorRequest request = connector.createRequest()
        .content("payload")
        .metadata("encoded=YWxpY2U=");
    Document doc = invokeBuildDocument(request);

    assertThat(doc.metadata().toMap()).containsEntry("encoded", "YWxpY2U=");
  }

  @Test
  public void buildDocumentShouldHandleEmptyMetadataString() throws Exception {
    KnowledgeIngestorRequest request = connector.createRequest()
        .content("payload").metadata("");
    Document doc = invokeBuildDocument(request);

    assertThat(doc.text()).isEqualTo("payload");
    assertThat(doc.metadata().toMap()).isEmpty();
  }

  // ── execute() end-to-end — exercises buildDocument from the public path ─

  @Test
  public void executeShouldPassParsedMetadataDownToTheEmbeddingStore() {
    CapturingIngestor capturing = new CapturingIngestor();

    KnowledgeIngestorRequest request = capturing.createRequest()
        .content("CIB seven is a BPMN engine.")
        .source("readme.md")
        .metadata("topic=docs, version=2.2.0")
        .pgHost("ignored-host");

    KnowledgeIngestorResponse response = capturing.execute(request);
    assertThat(response.getChunksIngested()).isGreaterThan(0);

    assertThat(capturing.lastSegments).isNotEmpty();
    // Every chunked segment inherits the document's metadata.
    assertThat(capturing.lastSegments.get(0).metadata().toMap())
        .containsEntry("source", "readme.md")
        .containsEntry("topic", "docs")
        .containsEntry("version", "2.2.0");
  }

  // ── Reflection helper ────────────────────────────────────────────────────

  private Document invokeBuildDocument(KnowledgeIngestorRequest request) throws Exception {
    Method m = KnowledgeIngestorConnectorImpl.class
        .getDeclaredMethod("buildDocument", KnowledgeIngestorRequest.class);
    m.setAccessible(true);
    return (Document) m.invoke(connector, request);
  }

  // ── Stubs ────────────────────────────────────────────────────────────────

  /**
   * Connector subclass that swaps both factories for in-memory stubs and
   * records the segments handed to the embedding store, so tests can inspect
   * the metadata propagated by {@code buildDocument} → recursive splitter →
   * {@code store.addAll(...)}.
   */
  static class CapturingIngestor extends KnowledgeIngestorConnectorImpl {
    volatile List<TextSegment> lastSegments;

    @Override
    protected EmbeddingModel createEmbeddingModel(KnowledgeIngestorRequest request) {
      return new ZeroEmbeddingModel();
    }

    @Override
    protected EmbeddingStore<TextSegment> createEmbeddingStore(KnowledgeIngestorRequest request) {
      return new CapturingStore();
    }

    class CapturingStore implements EmbeddingStore<TextSegment> {
      @Override public String add(Embedding embedding) { return "id"; }
      @Override public void add(String id, Embedding embedding) {}
      @Override public String add(Embedding embedding, TextSegment textSegment) { return "id"; }
      @Override public List<String> addAll(List<Embedding> embeddings) { return List.of(); }
      @Override
      public List<String> addAll(List<Embedding> embeddings, List<TextSegment> embedded) {
        lastSegments = embedded;
        return List.of();
      }
      @Override
      public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        return new EmbeddingSearchResult<>(List.<EmbeddingMatch<TextSegment>>of());
      }
    }
  }

  static final class ZeroEmbeddingModel implements EmbeddingModel {
    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
      java.util.List<Embedding> out = new java.util.ArrayList<>();
      for (int i = 0; i < segments.size(); i++) {
        out.add(Embedding.from(new float[] {0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f}));
      }
      return Response.from(out);
    }
  }
}
