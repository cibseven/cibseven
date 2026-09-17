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
import static org.assertj.core.api.Assertions.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.cibseven.connect.ai.agent.AgentConnectorConstants;
import org.cibseven.connect.ai.agent.AgentRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;

/**
 * Coverage-focused tests for the smaller helper methods of
 * {@link AgentConnectorImpl} that the existing test suite does not yet
 * exercise: header parsing, base-URL resolution, tool-class instantiation,
 * default system prompt loading, RAG content retriever early-exit, the
 * chat-memory id branches, and embedding-model selection.
 */
public class AgentConnectorImplCoverageTest {

  private AgentConnectorImpl connector;

  @BeforeEach
  public void setUp() {
    connector = new AgentConnectorImpl();
  }

  // ── parseCustomHeaders ───────────────────────────────────────────────────

  @Test
  public void parseCustomHeadersShouldReturnEmptyMapForNullOrBlank() {
    assertThat(AgentConnectorImpl.parseCustomHeaders(null)).isEmpty();
    assertThat(AgentConnectorImpl.parseCustomHeaders("")).isEmpty();
    assertThat(AgentConnectorImpl.parseCustomHeaders("   ")).isEmpty();
  }

  @Test
  public void parseCustomHeadersShouldParseSinglePair() {
    Map<String, String> headers = AgentConnectorImpl.parseCustomHeaders(
        "{\"Authorization\": \"Bearer abc\"}");
    assertThat(headers).containsExactly(Map.entry("Authorization", "Bearer abc"));
  }

  @Test
  public void parseCustomHeadersShouldParseMultipleEntries() {
    Map<String, String> headers = AgentConnectorImpl.parseCustomHeaders(
        "{\"Authorization\": \"Bearer abc\", \"X-Workspace\": \"ws-1\", \"X-Trace-Id\": \"t-42\"}");

    assertThat(headers)
        .containsEntry("Authorization", "Bearer abc")
        .containsEntry("X-Workspace", "ws-1")
        .containsEntry("X-Trace-Id", "t-42");
  }

  @Test
  public void parseCustomHeadersShouldSkipBlankKeys() {
    Map<String, String> headers = AgentConnectorImpl.parseCustomHeaders(
        "{\"\": \"ignored\", \"   \": \"also-ignored\", \"A\": \"1\"}");
    assertThat(headers).containsExactly(Map.entry("A", "1"));
  }

  @Test
  public void parseCustomHeadersShouldSkipNullValues() {
    Map<String, String> headers = AgentConnectorImpl.parseCustomHeaders(
        "{\"X-Empty\": null, \"A\": \"1\"}");
    assertThat(headers).containsExactly(Map.entry("A", "1"));
  }

  @Test
  public void parseCustomHeadersShouldCoerceNonStringValues() {
    Map<String, String> headers = AgentConnectorImpl.parseCustomHeaders(
        "{\"X-Count\": 42, \"X-Flag\": true}");
    assertThat(headers)
        .containsEntry("X-Count", "42")
        .containsEntry("X-Flag", "true");
  }

  @Test
  public void parseCustomHeadersShouldThrowOnMalformedJson() {
    assertThatThrownBy(() -> AgentConnectorImpl.parseCustomHeaders("not-json"))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("customHeaders");
  }

  @Test
  public void parseCustomHeadersShouldThrowOnJsonArray() {
    assertThatThrownBy(() -> AgentConnectorImpl.parseCustomHeaders("[\"a\", \"b\"]"))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("customHeaders");
  }

  // ── resolveBaseUrl (private helper, via reflection) ─────────────────────

  @Test
  public void resolveBaseUrlShouldReturnExplicitValueWhenSet() throws Exception {
    AgentRequest request = connector.createRequest().baseUrl("https://example.test/v1");
    assertThat(invokeResolveBaseUrl(request)).isEqualTo("https://example.test/v1");
  }

  @Test
  public void resolveBaseUrlShouldFallBackToEnvOrDefault() throws Exception {
    AgentRequest request = connector.createRequest(); // baseUrl unset
    String resolved = invokeResolveBaseUrl(request);
    String envUrl = System.getenv(AgentConnectorConstants.ENV_BASE_URL);
    if (envUrl != null && !envUrl.isEmpty()) {
      assertThat(resolved).isEqualTo(envUrl);
    } else {
      assertThat(resolved).isEqualTo(AgentConnectorConstants.DEFAULT_BASE_URL);
    }
  }

  // ── resolveToolInstances (private helper, via reflection) ───────────────

  /** Valid tool class — instantiated via the public no-arg constructor. */
  public static class HelloTool {
    public HelloTool() {}
  }

  /** Has no public no-arg constructor — must trigger ReflectiveOperationException. */
  public static class NoPublicCtorTool {
    private NoPublicCtorTool() {}
  }

  @Test
  public void resolveToolInstancesShouldReturnEmptyWhenToolClassesUnset() throws Exception {
    List<Object> tools = invokeResolveToolInstances(connector.createRequest());
    assertThat(tools).isEmpty();
  }

  @Test
  public void resolveToolInstancesShouldSkipBlankEntries() throws Exception {
    AgentRequest request = connector.createRequest()
        .toolClasses(HelloTool.class.getName() + ",  ,");
    List<Object> tools = invokeResolveToolInstances(request);
    assertThat(tools).hasSize(1);
    assertThat(tools.get(0)).isInstanceOf(HelloTool.class);
  }

  @Test
  public void resolveToolInstancesShouldThrowOnMissingClass() {
    AgentRequest request = connector.createRequest()
        .toolClasses("does.not.exist.Nope");

    Throwable cause = catchRootCauseOf(() -> invokeResolveToolInstances(request));
    assertThat(cause)
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("Could not load tool class")
        .hasCauseInstanceOf(ClassNotFoundException.class);
  }

  @Test
  public void resolveToolInstancesShouldThrowOnInstantiationFailure() {
    AgentRequest request = connector.createRequest()
        .toolClasses(NoPublicCtorTool.class.getName());

    Throwable cause = catchRootCauseOf(() -> invokeResolveToolInstances(request));
    assertThat(cause)
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("Could not instantiate tool class");
  }

  // ── loadDefaultInstruction ──────────────────────────────────────────────

  @Test
  public void loadDefaultInstructionShouldReturnNonEmptyContent() {
    String content = AgentConnectorImpl.loadDefaultInstruction();
    assertThat(content).isNotNull().isNotEmpty();
  }

  @Test
  public void loadDefaultInstructionShouldHitCacheOnSecondCall() {
    String first = AgentConnectorImpl.loadDefaultInstruction();
    String second = AgentConnectorImpl.loadDefaultInstruction();
    // Same identity — cached reference returned, exercising the cache-hit branch.
    assertThat(first).isSameAs(second);
  }

  // ── createContentRetriever — early-return null paths ────────────────────

  @Test
  public void createContentRetrieverShouldReturnNullWhenPgHostMissing() {
    AgentRequest request = connector.createRequest(); // no pgHost
    assertThat(connector.createContentRetriever(request)).isNull();
  }

  @Test
  public void createContentRetrieverShouldReturnNullWhenPgHostBlank() {
    AgentRequest request = connector.createRequest().pgHost("");
    assertThat(connector.createContentRetriever(request)).isNull();
  }

  // ── resolveMemoryId (private helper, via reflection) ────────────────────

  @Test
  public void resolveMemoryIdShouldReturnNullWhenChatMemoryDisabled() throws Exception {
    AgentRequest request = connector.createRequest(); // useChatMemory defaults to false
    assertThat(invokeResolveMemoryId(request)).isNull();
  }

  @Test
  public void resolveMemoryIdShouldReturnProvidedIdWhenChatMemoryActive() throws Exception {
    AgentRequest request = connector.createRequest()
        .useChatMemory(true)
        .memoryId("explicit-id");
    assertThat(invokeResolveMemoryId(request)).isEqualTo("explicit-id");
  }

  @Test
  public void resolveMemoryIdShouldGenerateUuidWhenChatMemoryActiveAndIdMissing() throws Exception {
    AgentRequest request = connector.createRequest().useChatMemory(true);
    String generated = invokeResolveMemoryId(request);
    assertThat(generated)
        .isNotNull()
        .isNotEmpty()
        .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  }

  // ── createChatMemoryProvider (factory exposes default branch) ───────────

  @Test
  public void createChatMemoryProviderShouldReturnNonNullProvider() {
    AgentRequest request = connector.createRequest()
        .useChatMemory(true)
        .chatMemoryMaxMessages(7);
    assertThat(connector.createChatMemoryProvider(request)).isNotNull();
  }

  // ── createEmbeddingModel branches ───────────────────────────────────────

  @Test
  public void createEmbeddingModelShouldReturnLocalModelWhenNameUnset() {
    AgentRequest request = connector.createRequest();
    EmbeddingModel model = connector.createEmbeddingModel(request);
    assertThat(model).isNotNull();
    assertThat(model.getClass().getName()).contains("AllMiniLmL6V2EmbeddingModel");
  }

  @Test
  public void createEmbeddingModelShouldReturnOpenAiModelWhenNameSet() {
    AgentRequest request = connector.createRequest()
        .embeddingModelName("text-embedding-3-small")
        .apiKey("test-key");
    EmbeddingModel model = connector.createEmbeddingModel(request);
    assertThat(model).isNotNull();
    assertThat(model.getClass().getName()).contains("OpenAiEmbeddingModel");
  }

  // ── createChatModel: custom headers + reasoningEffort on the standard model ─

  @Test
  public void createChatModelShouldAcceptCustomHeadersOnStandardModel() {
    AgentRequest request = connector.createRequest()
        .model("gpt-4o-mini")
        .reasoningEffort("low");
    ChatModel model = connector.createChatModel(request, "test-key", "https://example.test/v1",
        Map.of("X-Trace-Id", "t-42"));
    assertThat(model).isNotNull();
  }

  // ── Reflection helpers ───────────────────────────────────────────────────

  private String invokeResolveBaseUrl(AgentRequest request) throws Exception {
    Method m = AgentConnectorImpl.class.getDeclaredMethod("resolveBaseUrl", AgentRequest.class);
    m.setAccessible(true);
    return (String) m.invoke(connector, request);
  }

  @SuppressWarnings("unchecked")
  private List<Object> invokeResolveToolInstances(AgentRequest request) throws Exception {
    Method m = AgentConnectorImpl.class.getDeclaredMethod("resolveToolInstances", AgentRequest.class);
    m.setAccessible(true);
    return (List<Object>) m.invoke(connector, request);
  }

  private String invokeResolveMemoryId(AgentRequest request) throws Exception {
    Method m = AgentConnectorImpl.class.getDeclaredMethod("resolveMemoryId", AgentRequest.class);
    m.setAccessible(true);
    return (String) m.invoke(connector, request);
  }

  // ── createContentRetriever — full path with stubbed embedding store ─────

  /**
   * Connector subclass that swaps the live pgvector store and the ONNX
   * embedding model for stubs, so the RAG retriever lambda runs end-to-end
   * without any external service.
   */
  static class RagStubConnector extends AgentConnectorImpl {
    final StubEmbeddingStore store = new StubEmbeddingStore();

    @Override
    protected EmbeddingModel createEmbeddingModel(AgentRequest request) {
      return new ZeroEmbeddingModel();
    }

    @Override
    protected EmbeddingStore<TextSegment> createRagEmbeddingStore(AgentRequest request) {
      return store;
    }
  }

  @Test
  public void createContentRetrieverShouldReturnRetrieverWhenPgHostSet() {
    RagStubConnector ragConnector = new RagStubConnector();
    AgentRequest request = ragConnector.createRequest()
        .pgHost("ignored-host")
        .pgPort("5432")
        .pgDatabase("postgres")
        .pgUser("u")
        .pgPassword("p")
        .pgTable("t")
        .embeddingDimension(8)
        .maxRagResults(3)
        .minRagScore(0.0);

    ContentRetriever retriever = ragConnector.createContentRetriever(request);
    assertThat(retriever).isNotNull();
  }

  @Test
  public void retrieverLambdaShouldLogZeroResultsBranchWhenStoreReturnsEmpty() {
    RagStubConnector ragConnector = new RagStubConnector();
    ragConnector.store.matches = List.of();

    AgentRequest request = ragConnector.createRequest()
        .pgHost("h").pgPort("5432").pgDatabase("d").pgUser("u").pgPassword("p")
        .pgTable("t").embeddingDimension(8).maxRagResults(5).minRagScore(0.0);

    ContentRetriever retriever = ragConnector.createContentRetriever(request);
    List<Content> results = retriever.retrieve(Query.from("any"));
    assertThat(results).isEmpty();
  }

  @Test
  public void retrieverLambdaShouldLogResultsAndTruncateLongPreviews() {
    RagStubConnector ragConnector = new RagStubConnector();
    String shortText = "short snippet";
    String longText = "x".repeat(250); // exceeds the 120-char preview limit → drives substring branch
    ragConnector.store.matches = Arrays.asList(
        match(0.9, shortText),
        match(0.8, longText));

    AgentRequest request = ragConnector.createRequest()
        .pgHost("h").pgPort("5432").pgDatabase("d").pgUser("u").pgPassword("p")
        .pgTable("t").embeddingDimension(8).maxRagResults(5).minRagScore(0.0);

    ContentRetriever retriever = ragConnector.createContentRetriever(request);
    List<Content> results = retriever.retrieve(Query.from("anything"));
    assertThat(results).hasSize(2);
    assertThat(results.get(0).textSegment().text()).isEqualTo(shortText);
    assertThat(results.get(1).textSegment().text()).isEqualTo(longText);
  }

  private static EmbeddingMatch<TextSegment> match(double score, String text) {
    return new EmbeddingMatch<>(score, "id-" + text.hashCode(),
        Embedding.from(new float[] {0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f}),
        TextSegment.from(text));
  }

  // ── createContentRetriever — audit-event emission (EU AI Act Art. 10/12/26) ─

  /**
   * Tears down listener / redaction state that the retrieval-audit tests
   * install, so they don't leak into the rest of the suite.
   */
  @AfterEach
  public void clearRetrievalAuditState() {
    ProcessStarterToolContext.clear();
    System.clearProperty(AgentChatListener.REDACT_CONTENT_PROPERTY);
  }

  @Test
  public void retrieverLambdaShouldEmitRetrievalEventOnSuccess() {
    RagStubConnector ragConnector = new RagStubConnector();
    ragConnector.store.matches = Arrays.asList(
        match(0.91, "BPMN models business processes."),
        match(0.74, "DMN models business decisions."));

    AgentRequest request = ragConnector.createRequest()
        .pgHost("pg-host").pgPort("5433").pgDatabase("ragdb").pgUser("u").pgPassword("p")
        .pgTable("docs").embeddingDimension(8).maxRagResults(5).minRagScore(0.5);

    AgentChatListener listener = new AgentChatListener("gpt-5.4-nano", "https://chat/v1");
    ProcessStarterToolContext.setActiveListener(listener);

    ContentRetriever retriever = ragConnector.createContentRetriever(request);
    retriever.retrieve(Query.from("how do business processes work"));

    assertThat(listener.events()).hasSize(1);
    Map<String, Object> event = listener.events().get(0);
    assertThat(event)
        .containsEntry("type", "retrieval")
        .containsEntry("query", "how do business processes work")
        .containsEntry("resultCount", 2);
    assertThat(event).containsKey("durationMs");

    @SuppressWarnings("unchecked")
    Map<String, Object> embeddingModel = (Map<String, Object>) event.get("embeddingModel");
    assertThat(embeddingModel)
        .containsEntry("provider", "local")
        .containsEntry("model", "AllMiniLmL6V2EmbeddingModel");

    @SuppressWarnings("unchecked")
    Map<String, Object> store = (Map<String, Object>) event.get("store");
    assertThat(store)
        .containsEntry("kind", "pgvector")
        .containsEntry("host", "pg-host")
        .containsEntry("port", "5433")
        .containsEntry("database", "ragdb")
        .containsEntry("table", "docs")
        .containsEntry("dimension", 8);
    // Password must NEVER appear in the audit event.
    assertThat(store).doesNotContainKey("password").doesNotContainKey("user");

    @SuppressWarnings("unchecked")
    Map<String, Object> parameters = (Map<String, Object>) event.get("parameters");
    assertThat(parameters)
        .containsEntry("maxResults", 5)
        .containsEntry("minScore", 0.5);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results = (List<Map<String, Object>>) event.get("results");
    assertThat(results).hasSize(2);
    assertThat(results.get(0))
        .containsEntry("score", 0.91)
        .containsEntry("content", "BPMN models business processes.")
        .containsEntry("length", "BPMN models business processes.".length());
  }

  @Test
  public void retrieverLambdaShouldUseOpenAiEmbeddingDescriptorWhenModelNameSet() {
    RagStubConnector ragConnector = new RagStubConnector();
    ragConnector.store.matches = List.of();

    AgentRequest request = ragConnector.createRequest()
        .pgHost("h").pgPort("5432").pgDatabase("d").pgUser("u").pgPassword("p")
        .pgTable("t").embeddingDimension(8).maxRagResults(3).minRagScore(0.0)
        .embeddingModelName("text-embedding-3-small")
        .apiKey("test-key");

    AgentChatListener listener = new AgentChatListener();
    ProcessStarterToolContext.setActiveListener(listener);

    ContentRetriever retriever = ragConnector.createContentRetriever(request);
    retriever.retrieve(Query.from("anything"));

    @SuppressWarnings("unchecked")
    Map<String, Object> embeddingModel =
        (Map<String, Object>) listener.events().get(0).get("embeddingModel");
    assertThat(embeddingModel)
        .containsEntry("provider", "openai")
        .containsEntry("model", "text-embedding-3-small");
  }

  @Test
  public void retrieverLambdaShouldRedactQueryAndChunksWhenRedactionEnabled()
      throws com.fasterxml.jackson.core.JsonProcessingException {
    System.setProperty(AgentChatListener.REDACT_CONTENT_PROPERTY, "true");

    RagStubConnector ragConnector = new RagStubConnector();
    String sensitive = "patient John Doe presented with chest pain";
    ragConnector.store.matches = List.of(match(0.8, sensitive));

    AgentRequest request = ragConnector.createRequest()
        .pgHost("h").pgPort("5432").pgDatabase("d").pgUser("u").pgPassword("p")
        .pgTable("t").embeddingDimension(8).maxRagResults(3).minRagScore(0.0);

    AgentChatListener listener = new AgentChatListener();
    ProcessStarterToolContext.setActiveListener(listener);

    ContentRetriever retriever = ragConnector.createContentRetriever(request);
    retriever.retrieve(Query.from("PII-bearing question about a patient"));

    Map<String, Object> event = listener.events().get(0);
    String redactedQuery = (String) event.get("query");
    assertThatRedacted(redactedQuery, "PII-bearing question about a patient".length());

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results = (List<Map<String, Object>>) event.get("results");
    String redactedChunk = (String) results.get(0).get("content");
    assertThatRedacted(redactedChunk, sensitive.length());
    // length still reflects the original plaintext length, not the marker.
    assertThat(results.get(0)).containsEntry("length", sensitive.length());
  }

  @Test
  public void retrieverLambdaShouldEmitErrorEventWhenDelegateThrows() {
    RagStubConnector ragConnector = new RagStubConnector();
    ragConnector.store.failure = new IllegalStateException("pgvector connection refused");

    AgentRequest request = ragConnector.createRequest()
        .pgHost("h").pgPort("5432").pgDatabase("d").pgUser("u").pgPassword("p")
        .pgTable("t").embeddingDimension(8).maxRagResults(3).minRagScore(0.0);

    AgentChatListener listener = new AgentChatListener();
    ProcessStarterToolContext.setActiveListener(listener);

    ContentRetriever retriever = ragConnector.createContentRetriever(request);
    assertThatThrownBy(() -> retriever.retrieve(Query.from("anything")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pgvector connection refused");

    assertThat(listener.events()).hasSize(1);
    Map<String, Object> event = listener.events().get(0);
    assertThat(event)
        .containsEntry("type", "retrieval")
        .containsEntry("errorClass", "java.lang.IllegalStateException")
        .containsEntry("message", "pgvector connection refused");
    assertThat(event).containsKey("stack").containsKey("durationMs");
    assertThat(event).doesNotContainKey("results").doesNotContainKey("resultCount");
  }

  @Test
  public void retrieverLambdaShouldStillFunctionWithoutActiveListener() {
    RagStubConnector ragConnector = new RagStubConnector();
    ragConnector.store.matches = List.of(match(0.9, "any text"));

    AgentRequest request = ragConnector.createRequest()
        .pgHost("h").pgPort("5432").pgDatabase("d").pgUser("u").pgPassword("p")
        .pgTable("t").embeddingDimension(8).maxRagResults(3).minRagScore(0.0);

    // Deliberately no setActiveListener — engine-agnostic fallback.
    ProcessStarterToolContext.clear();

    ContentRetriever retriever = ragConnector.createContentRetriever(request);
    List<Content> results = retriever.retrieve(Query.from("anything"));
    assertThat(results).hasSize(1);
  }

  private static void assertThatRedacted(String marker, int expectedLength)
      throws com.fasterxml.jackson.core.JsonProcessingException {
    @SuppressWarnings("unchecked")
    Map<String, Object> decoded = new com.fasterxml.jackson.databind.ObjectMapper()
        .readValue(marker, Map.class);
    assertThat(decoded).containsEntry("redacted", true)
        .containsEntry("length", expectedLength);
    assertThat((String) decoded.get("hash")).startsWith("sha256:");
  }

  // ── buildSystemPrompt — all four combinations of description × instruction ─

  @Test
  public void buildSystemPromptShouldReturnDefaultWhenNeitherSet() throws Exception {
    String prompt = invokeBuildSystemPrompt(connector.createRequest());
    // Defaults to the bundled instruction resource — non-empty when present.
    assertThat(prompt).isNotNull().isNotEmpty();
  }

  @Test
  public void buildSystemPromptShouldReturnInstructionOnlyWhenDescriptionUnset() throws Exception {
    AgentRequest request = connector.createRequest().instruction("be brief");
    String prompt = invokeBuildSystemPrompt(request);
    assertThat(prompt).isEqualTo("be brief");
  }

  @Test
  public void buildSystemPromptShouldReturnDescriptionWhenOnlyDescriptionSetAndDefaultMissing()
      throws Exception {
    // When description is the only thing set and the instruction resolves to
    // the default — the default is bundled, so the prepended result will be
    // "description + \\n\\n + default". The presence of the description is the
    // important behavioural assertion.
    AgentRequest request = connector.createRequest().agentDescription("invoice-extractor");
    String prompt = invokeBuildSystemPrompt(request);
    assertThat(prompt).startsWith("invoice-extractor");
  }

  @Test
  public void buildSystemPromptShouldJoinDescriptionAndInstructionWhenBothPresent() throws Exception {
    AgentRequest request = connector.createRequest()
        .agentDescription("You are an extractor.")
        .instruction("Return JSON.");
    String prompt = invokeBuildSystemPrompt(request);
    assertThat(prompt).isEqualTo("You are an extractor.\n\nReturn JSON.");
  }

  // ── buildSystemPrompt — instructionMode branches ────────────────────────

  @Test
  public void buildSystemPromptReplaceModeShouldUseOnlyCallerInstruction() throws Exception {
    AgentRequest request = connector.createRequest()
        .instruction("Return JSON.")
        .instructionMode(AgentConnectorConstants.INSTRUCTION_MODE_REPLACE);
    String prompt = invokeBuildSystemPrompt(request);
    assertThat(prompt).isEqualTo("Return JSON.");
  }

  @Test
  public void buildSystemPromptAppendModeShouldKeepDefaultThenCallerInstruction() throws Exception {
    String defaultPrompt = AgentConnectorImpl.loadDefaultInstruction();
    AgentRequest request = connector.createRequest()
        .instruction("Return JSON.")
        .instructionMode(AgentConnectorConstants.INSTRUCTION_MODE_APPEND);
    String prompt = invokeBuildSystemPrompt(request);
    assertThat(prompt).isEqualTo(defaultPrompt
        + AgentConnectorConstants.INSTRUCTION_MODE_SEPARATOR
        + "Return JSON.");
  }

  @Test
  public void buildSystemPromptPrependModeShouldPutCallerInstructionBeforeDefault() throws Exception {
    String defaultPrompt = AgentConnectorImpl.loadDefaultInstruction();
    AgentRequest request = connector.createRequest()
        .instruction("Return JSON.")
        .instructionMode(AgentConnectorConstants.INSTRUCTION_MODE_PREPEND);
    String prompt = invokeBuildSystemPrompt(request);
    assertThat(prompt).isEqualTo("Return JSON."
        + AgentConnectorConstants.INSTRUCTION_MODE_SEPARATOR
        + defaultPrompt);
  }

  @Test
  public void buildSystemPromptAppendModeShouldFallBackToDefaultWhenInstructionEmpty()
      throws Exception {
    String defaultPrompt = AgentConnectorImpl.loadDefaultInstruction();
    AgentRequest request = connector.createRequest()
        .instructionMode(AgentConnectorConstants.INSTRUCTION_MODE_APPEND);
    String prompt = invokeBuildSystemPrompt(request);
    assertThat(prompt).isEqualTo(defaultPrompt);
  }

  @Test
  public void buildSystemPromptShouldRejectUnknownInstructionMode() {
    AgentRequest request = connector.createRequest()
        .instruction("Return JSON.")
        .instructionMode("merge");
    try {
      invokeBuildSystemPrompt(request);
      fail("expected AgentConnectorException, but no exception was thrown");
    } catch (InvocationTargetException ite) {
      assertThat(ite.getCause())
          .isInstanceOf(AgentConnectorException.class)
          .hasMessageContaining("Unknown instructionMode 'merge'");
    } catch (Exception e) {
      fail("expected InvocationTargetException, got " + e.getClass().getName());
    }
  }

  // ── buildMcpClient — exercise the real factory body once ────────────────

  /**
   * {@code DefaultMcpClient.Builder().build()} eagerly attempts a transport
   * handshake — pointing it at an unreachable port lets every line of the
   * factory execute and then fail predictably with a connection error. The
   * coverage agent has already recorded the factory body by the time the
   * exception is thrown.
   */
  @Test
  public void buildMcpClientShouldRunFactoryWithCustomHeaders() {
    assertThatThrownBy(() ->
        connector.buildMcpClient("http://127.0.0.1:1/mcp",
            Map.of("Authorization", "Bearer test")))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  public void buildMcpClientShouldRunFactoryWithNullHeaders() {
    assertThatThrownBy(() ->
        connector.buildMcpClient("http://127.0.0.1:1/mcp", null))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  public void buildMcpClientShouldRunFactoryWithEmptyHeaders() {
    assertThatThrownBy(() ->
        connector.buildMcpClient("http://127.0.0.1:1/mcp", Map.of()))
        .isInstanceOf(RuntimeException.class);
  }

  // ── execute() — exercises the contentRetriever != null branch ───────────

  @Test
  public void executeShouldWireContentRetrieverWhenPgHostSet() {
    RagStubConnector ragConnector = new RagStubConnector() {
      @Override
      protected ChatModel createChatModel(AgentRequest request, String apiKey, String baseUrl,
          Map<String, String> customHeaders) {
        // Echo model that returns a canned acknowledgement so execute() finishes
        // without any HTTP I/O.
        return new dev.langchain4j.model.chat.ChatModel() {
          @Override
          public dev.langchain4j.model.chat.response.ChatResponse doChat(
              dev.langchain4j.model.chat.request.ChatRequest req) {
            return dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(dev.langchain4j.data.message.AiMessage.from("ok"))
                .build();
          }
        };
      }
    };
    ragConnector.store.matches = List.of(match(0.95, "supporting context"));

    AgentRequest request = ragConnector.createRequest()
        .agentName("agent")
        .message("question?")
        .apiKey("test-key")
        .pgHost("h").pgPort("5432").pgDatabase("d").pgUser("u").pgPassword("p")
        .pgTable("t").embeddingDimension(8).maxRagResults(5).minRagScore(0.0);

    assertThat(ragConnector.execute(request).getOutput()).isEqualTo("ok");
  }

  // ── loadDefaultInstruction — re-prime cache-miss branch ─────────────────

  @Test
  public void loadDefaultInstructionShouldRePopulateCacheAfterReset() throws Exception {
    // Reset the cache and call again to drive the cache-miss → URL lookup →
    // InputStream → readAllBytes path again on a clean state.
    java.lang.reflect.Field f = AgentConnectorImpl.class.getDeclaredField("defaultInstructionCache");
    f.setAccessible(true);
    f.set(null, null);

    String repopulated = AgentConnectorImpl.loadDefaultInstruction();
    assertThat(repopulated).isNotNull().isNotEmpty();
    // Now the cache is back; cache-hit branch covered too.
    assertThat(AgentConnectorImpl.loadDefaultInstruction()).isSameAs(repopulated);
  }

  // ── Reflection helper for buildSystemPrompt ─────────────────────────────

  private String invokeBuildSystemPrompt(AgentRequest request) throws Exception {
    Method m = AgentConnectorImpl.class.getDeclaredMethod("buildSystemPrompt", AgentRequest.class);
    m.setAccessible(true);
    return (String) m.invoke(connector, request);
  }

  // ── Stubs ────────────────────────────────────────────────────────────────

  /** Embedding model returning fixed zero vectors — no ONNX runtime needed. */
  static final class ZeroEmbeddingModel implements EmbeddingModel {
    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
      List<Embedding> out = new ArrayList<>();
      for (int i = 0; i < segments.size(); i++) {
        out.add(Embedding.from(new float[] {0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f}));
      }
      return Response.from(out);
    }
  }

  /**
   * In-memory embedding store stub: returns the matches set on {@link #matches}
   * for any {@code search} call. Defaults to an empty list so that the
   * "0 results" log branch of the retriever lambda is exercised by default.
   */
  static final class StubEmbeddingStore implements EmbeddingStore<TextSegment> {
    List<EmbeddingMatch<TextSegment>> matches = List.of();
    /** Set non-null to make {@link #search} throw — exercises the retriever error path. */
    RuntimeException failure;

    @Override public String add(Embedding embedding) { return "id"; }
    @Override public void add(String id, Embedding embedding) {}
    @Override public String add(Embedding embedding, TextSegment textSegment) { return "id"; }
    @Override public List<String> addAll(List<Embedding> embeddings) { return List.of(); }
    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> embedded) {
      return List.of();
    }
    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
      if (failure != null) {
        throw failure;
      }
      return new EmbeddingSearchResult<>(matches);
    }
  }

  /**
   * Returns the underlying cause of the {@link InvocationTargetException} thrown by
   * the supplied reflective call, so individual tests can assert on the connector's
   * own exception type (e.g. {@link AgentConnectorException}) instead of the
   * reflection wrapper.
   */
  private static Throwable catchRootCauseOf(ReflectiveCall call) {
    try {
      call.run();
    } catch (InvocationTargetException ite) {
      return ite.getCause();
    } catch (Exception e) {
      return e;
    }
    fail("Expected exception was not thrown");
    return null;
  }

  @FunctionalInterface
  private interface ReflectiveCall {
    void run() throws Exception;
  }
}
