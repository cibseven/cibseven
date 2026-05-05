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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;

import org.cibseven.connect.agentconnector.AgentConnector;
import org.cibseven.connect.agentconnector.AgentConnectorConstants;
import org.cibseven.connect.agentconnector.AgentRequest;
import org.cibseven.connect.agentconnector.AgentResponse;
import org.cibseven.connect.impl.AbstractConnector;

/**
 * Core implementation of the LangChain4j agent connector.
 *
 * <h3>Execution flow per invocation:</h3>
 * <ol>
 *   <li>Instantiate any tool objects listed in {@code toolClasses} (fail fast before network I/O).</li>
 *   <li>Resolve the OpenAI-compatible API key from the request parameter or
 *       the {@code OPENAI_API_KEY} environment variable.</li>
 *   <li>Resolve the base URL from the request parameter, the {@code OPENAI_BASE_URL}
 *       environment variable, or {@link AgentConnectorConstants#DEFAULT_BASE_URL}.</li>
 *   <li>Build an {@link OpenAiChatModel} from the request parameters.</li>
 *   <li>Create a stateless {@link AiServices} proxy and invoke it with the user message.</li>
 *   <li>Return an {@link AgentResponse} containing the output text and a correlation UUID.</li>
 * </ol>
 */
public class AgentConnectorImpl extends AbstractConnector<AgentRequest, AgentResponse>
    implements AgentConnector {

  private static final Logger LOG = LoggerFactory.getLogger(AgentConnectorImpl.class);

  /** Internal AI service interface — one anonymous proxy is built per request. */
  interface LangChainAgent {
    Result<String> chat(@UserMessage String message);
  }

  public AgentConnectorImpl() {
    super(AgentConnector.ID);
  }

  @Override
  public AgentRequest createRequest() {
    return new AgentRequestImpl(this);
  }

  @Override
  public AgentResponse execute(AgentRequest request) {
    List<Object> tools = resolveToolInstances(request);
    String apiKey = resolveApiKey(request);
    String baseUrl = resolveBaseUrl(request);
    Map<String, String> openaiHeaders = parseCustomHeaders(request.getOpenaiCustomHeaders());
    ChatModel chatModel = createChatModel(request, apiKey, baseUrl, openaiHeaders);

    List<McpClient> mcpClients = createMcpClients(request);

    AiServices<LangChainAgent> builder = AiServices.builder(LangChainAgent.class)
        .chatModel(chatModel)
        .systemMessageProvider(chatMemoryId -> buildSystemPrompt(request));
    if (!tools.isEmpty()) {
      builder.tools(tools.toArray());
    }
    if (!mcpClients.isEmpty()) {
      ToolProvider mcpToolProvider = McpToolProvider.builder()
          .mcpClients(mcpClients)
          .build();
      builder.toolProvider(mcpToolProvider);
    }
    ContentRetriever contentRetriever = createContentRetriever(request);
    LOG.debug("contentRetriever: " + contentRetriever);
    if (contentRetriever != null) {
      builder.contentRetriever(contentRetriever);
    }
    LangChainAgent agent = builder.build();

    Result<String> result = agent.chat(request.getMessage());
    String output = result.content();

    for (ToolExecution exec : result.toolExecutions()) {
      LOG.debug("Tool: {}, Args: {}, Result: {}",
          exec.request().name(), exec.request().arguments(), exec.result());
    }
    LOG.debug("Total tokens: {}", result.tokenUsage());

    String chatLog = "";
    for (ChatModelListener listener : chatModel.listeners()) {
      if (listener instanceof AgentChatListener) {
        chatLog = ((AgentChatListener) listener).writeChatLogVariable();
      }
    }

    return new AgentResponseImpl(output, chatLog);
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private String resolveBaseUrl(AgentRequest request) {
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

  private String resolveApiKey(AgentRequest request) {
    String apiKey = request.getApiKey();
    if (apiKey != null && !apiKey.isEmpty()) {
      return apiKey;
    }
    apiKey = System.getenv("OPENAI_API_KEY");
    if (apiKey != null && !apiKey.isEmpty()) {
      return apiKey;
    }
    throw new AgentConnectorException(
        "No API key provided: set the OPENAI_API_KEY environment variable "
        + "or supply the 'apiKey' request parameter");
  }

  private List<Object> resolveToolInstances(AgentRequest request) {
    List<Object> toolInstances = new ArrayList<>();
    String toolClasses = request.getToolClasses();
    if (toolClasses == null || toolClasses.isEmpty()) {
      return toolInstances;
    }
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    for (String className : toolClasses.split(",")) {
      className = className.trim();
      if (className.isEmpty()) {
        continue;
      }
      try {
        Class<?> toolClass = Class.forName(className, true, classLoader);
        toolInstances.add(toolClass.getDeclaredConstructor().newInstance());
      } catch (ClassNotFoundException e) {
        throw new AgentConnectorException(
            "Could not load tool class '" + className + "'", e);
      } catch (ReflectiveOperationException e) {
        throw new AgentConnectorException(
            "Could not instantiate tool class '" + className
            + "': ensure it has a public no-arg constructor", e);
      }
    }
    return toolInstances;
  }

  private String buildSystemPrompt(AgentRequest request) {
    String description = request.getAgentDescription();
    String instruction = request.getInstruction();
    if (description != null && !description.isEmpty()) {
      return description + "\n\n" + instruction;
    }
    return instruction;
  }

  /**
   * Factory method — override in tests to inject stubbed MCP clients.
   *
   * <p>Builds the list from two sources, in this order:
   * <ol>
   *   <li>The legacy single-server fields ({@code mcpServerUrl} +
   *       {@code mcpCustomHeaders}), if {@code mcpServerUrl} is set.</li>
   *   <li>Each entry of the {@code mcpServers} JSON array, if set.</li>
   * </ol>
   * Returns an empty list when neither is configured.
   */
  protected List<McpClient> createMcpClients(AgentRequest request) {
    List<McpClient> clients = new ArrayList<>();

    String legacyUrl = request.getMcpServerUrl();
    if (legacyUrl != null && !legacyUrl.isEmpty()) {
      clients.add(buildMcpClient(legacyUrl, parseCustomHeaders(request.getMcpCustomHeaders())));
    }

    for (McpServerSpec spec : parseMcpServers(request.getMcpServers())) {
      clients.add(buildMcpClient(spec.url, spec.headers));
    }
    return clients;
  }

  /**
   * Factory method for a single MCP client — override in tests to capture
   * arguments and avoid opening a real transport connection.
   */
  protected McpClient buildMcpClient(String url, Map<String, String> headers) {
    StreamableHttpMcpTransport.Builder transportBuilder = new StreamableHttpMcpTransport.Builder()
        .url(url)
        .logRequests(true)
        .logResponses(true);
    if (headers != null && !headers.isEmpty()) {
      transportBuilder.customHeaders(headers);
    }
    return new DefaultMcpClient.Builder()
        .transport(transportBuilder.build())
        .build();
  }

  /**
   * Factory method — builds and returns a {@link ContentRetriever} backed by pgvector
   * when {@code pgHost} is present in the request, or {@code null} to skip RAG entirely.
   * Override in tests to inject a stubbed retriever.
   */
  protected ContentRetriever createContentRetriever(AgentRequest request) {
    String pgHost = request.getPgHost();
    if (pgHost == null || pgHost.isEmpty()) {
      LOG.debug("RAG not activated: pgHost not set");
      return null;
    }
    String table = request.getPgTable();
    int dimension = request.getEmbeddingDimension();
    int maxResults = request.getMaxRagResults();
    double minScore = request.getMinRagScore();
    LOG.info("RAG activated: host={}:{}, db={}, table={}, dimension={}, maxResults={}, minScore={}",
        pgHost, request.getPgPort(), request.getPgDatabase(), table, dimension, maxResults, minScore);
    EmbeddingModel embeddingModel = createEmbeddingModel(request);
    EmbeddingStore<TextSegment> embeddingStore = PgVectorEmbeddingStore.builder()
        .host(pgHost)
        .port(Integer.parseInt(request.getPgPort()))
        .database(request.getPgDatabase())
        .user(request.getPgUser())
        .password(request.getPgPassword())
        .table(table)
        .dimension(dimension)
        .createTable(false)
        .useIndex(true)
        .indexListSize(100)
        .build();
    ContentRetriever delegate = EmbeddingStoreContentRetriever.builder()
        .embeddingStore(embeddingStore)
        .embeddingModel(embeddingModel)
        .maxResults(maxResults)
        .minScore(minScore)
        .build();
    return query -> {
      List<Content> results = delegate.retrieve(query);
      if (results.isEmpty()) {
        LOG.warn("RAG retrieved 0 results for query: \"{}\" (minScore={}, table={}). "
            + "Consider lowering minRagScore or check that content was ingested.",
            query.text(), minScore, table);
      } else {
        LOG.info("RAG retrieved {} result(s) for query: \"{}\"", results.size(), query.text());
        for (int i = 0; i < results.size(); i++) {
          String preview = results.get(i).textSegment().text().replace('\n', ' ');
          if (preview.length() > 120) preview = preview.substring(0, 120) + "…";
          LOG.debug("  RAG context[{}]: {}", i, preview);
        }
      }
      return results;
    };
  }

  /**
   * Factory method — override in tests to inject a stubbed embedding model.
   * Uses {@code OpenAiEmbeddingModel} when {@code embeddingModelName} is set on the request,
   * otherwise falls back to the local {@code AllMiniLmL6V2EmbeddingModel}.
   */
  protected EmbeddingModel createEmbeddingModel(AgentRequest request) {
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

  /**
   * Factory method — override in tests to inject a stubbed chat model.
   *
   * <p>Selects the OpenAI Responses API ({@link OpenAiResponsesChatModel}) when
   * {@code reasoningSummary} is set on the request — that field is only available
   * on the Responses API. Otherwise builds the standard {@link OpenAiChatModel}.
   * {@code reasoningEffort}, when present, is forwarded to whichever builder is used.
   */
  protected ChatModel createChatModel(AgentRequest request, String apiKey, String baseUrl,
      Map<String, String> customHeaders) {
    String modelName = request.getModel();
    String reasoningEffort = request.getReasoningEffort();
    String reasoningSummary = request.getReasoningSummary();

    if (reasoningSummary != null && !reasoningSummary.isEmpty()) {
      if (customHeaders != null && !customHeaders.isEmpty()) {
        LOG.warn("'openaiCustomHeaders' is ignored when 'reasoningSummary' is set: "
            + "the OpenAI Responses API builder does not yet expose customHeaders.");
      }
      OpenAiResponsesChatModel.Builder rb = OpenAiResponsesChatModel.builder()
          .apiKey(apiKey)
          .baseUrl(baseUrl)
          .modelName(modelName)
          .listeners(List.of(new AgentChatListener()))
          .reasoningSummary(reasoningSummary);
      if (reasoningEffort != null && !reasoningEffort.isEmpty()) {
        rb.reasoningEffort(reasoningEffort);
      }
      return rb.build();
    }

    OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
        .apiKey(apiKey)
        .modelName(modelName)
        .baseUrl(baseUrl)
        .listeners(List.of(new AgentChatListener()));
        // 2026.03.18, Oleg: logXXX is not working here, somehow
        /*
        .logRequests(true)   // logs every request sent to the LLM
        .logResponses(true)  // logs every response from the LLM
        */
    if (customHeaders != null && !customHeaders.isEmpty()) {
      builder.customHeaders(customHeaders);
    }
    if (reasoningEffort != null && !reasoningEffort.isEmpty()) {
      builder.reasoningEffort(reasoningEffort);
    }
    return builder.build();
  }

  /** Internal value object describing one MCP server entry. */
  static final class McpServerSpec {
    final String url;
    final Map<String, String> headers;

    McpServerSpec(String url, Map<String, String> headers) {
      this.url = url;
      this.headers = headers;
    }
  }

  private static final ObjectMapper MCP_SERVERS_MAPPER = new ObjectMapper();

  /**
   * Parses the {@code mcpServers} JSON array into a list of {@link McpServerSpec}.
   * Returns an empty list when the input is null, blank, or an empty JSON array.
   * Throws {@link AgentConnectorException} on malformed JSON or missing {@code url}.
   */
  static List<McpServerSpec> parseMcpServers(String raw) {
    List<McpServerSpec> specs = new ArrayList<>();
    if (raw == null || raw.isEmpty() || raw.trim().isEmpty()) {
      return specs;
    }
    List<Map<String, Object>> entries;
    try {
      entries = MCP_SERVERS_MAPPER.readValue(raw, new TypeReference<List<Map<String, Object>>>() {});
    } catch (Exception e) {
      throw new AgentConnectorException(
          "Could not parse 'mcpServers': expected a JSON array of {url, headers?} objects", e);
    }
    for (int i = 0; i < entries.size(); i++) {
      Map<String, Object> entry = entries.get(i);
      Object urlValue = entry.get("url");
      if (!(urlValue instanceof String) || ((String) urlValue).isEmpty()) {
        throw new AgentConnectorException(
            "mcpServers[" + i + "]: missing or empty 'url' field");
      }
      Map<String, String> headers = new LinkedHashMap<>();
      Object headersValue = entry.get("headers");
      if (headersValue instanceof Map) {
        for (Map.Entry<?, ?> h : ((Map<?, ?>) headersValue).entrySet()) {
          if (h.getKey() != null && h.getValue() != null) {
            headers.put(h.getKey().toString(), h.getValue().toString());
          }
        }
      } else if (headersValue != null) {
        throw new AgentConnectorException(
            "mcpServers[" + i + "].headers: expected an object of string→string pairs");
      }
      specs.add(new McpServerSpec((String) urlValue, headers));
    }
    return specs;
  }

  /**
   * Parses a {@code key: value|key: value} string into an ordered map.
   * Returns an empty (mutable) map when the input is null, blank, or contains no
   * well-formed pairs. Pairs without a {@code :} separator and pairs with a blank
   * key are silently skipped; the first {@code :} in a pair separates key from value.
   * Keys and values are trimmed, so {@code key:value} (no space) is also accepted.
   */
  static Map<String, String> parseCustomHeaders(String raw) {
    Map<String, String> headers = new LinkedHashMap<>();
    if (raw == null || raw.isEmpty()) {
      return headers;
    }
    for (String pair : raw.split("\\|")) {
      int colon = pair.indexOf(':');
      if (colon < 0) {
        continue;
      }
      String key = pair.substring(0, colon).trim();
      String value = pair.substring(colon + 1).trim();
      if (key.isEmpty()) {
        continue;
      }
      headers.put(key, value);
    }
    return headers;
  }

}
