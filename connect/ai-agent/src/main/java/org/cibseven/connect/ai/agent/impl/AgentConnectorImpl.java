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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.cibseven.bpm.BpmPlatform;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.history.HistoricProcessInstance;
import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.context.BpmnExecutionContext;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.identity.Authentication;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;

import org.cibseven.connect.ai.agent.AgentConnector;
import org.cibseven.connect.ai.agent.AgentConnectorConstants;
import org.cibseven.connect.ai.agent.AgentRequest;
import org.cibseven.connect.ai.agent.AgentResponse;
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

  /**
   * Internal AI service interface used for stateless invocations.
   *
   * <p>{@link AiServices} rejects {@link MemoryId} parameters unless a
   * {@link ChatMemoryProvider} is registered, so two flavors of the interface
   * are needed: this one without memory and {@link LangChainMemoryAgent} with.
   */
  interface LangChainAgent {
    Result<String> chat(@UserMessage String message);
  }

  /**
   * Internal AI service interface used when chat memory is active. The
   * {@link MemoryId} value is what {@link ChatMemoryProvider} uses to look up
   * (or create) the {@code MessageWindowChatMemory} bound to this conversation.
   */
  interface LangChainMemoryAgent {
    Result<String> chat(@MemoryId Object memoryId, @UserMessage String message);
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

    String memoryId = resolveMemoryId(request);

    Class<?> agentClass = (memoryId != null) ? LangChainMemoryAgent.class : LangChainAgent.class;
    AiServices<?> builder = AiServices.builder(agentClass)
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
    if (memoryId != null) {
      builder.chatMemoryProvider(createChatMemoryProvider(request));
    }
    ContentRetriever contentRetriever = createContentRetriever(request);
    LOG.debug("contentRetriever: " + contentRetriever);
    if (contentRetriever != null) {
      builder.contentRetriever(contentRetriever);
    }

    // Forward the engine reference and the caller's engine authentication to
    // any @Tool that needs them (e.g. ProcessStarterTool). The engine is
    // captured here, on the connector's calling thread, because the same
    // BpmPlatform lookup may return null on a LangChain4j worker thread.
    // Authentication is forwarded so engine-level authorization checks run
    // against the user that triggered the AI agent — not the worker thread.
    ProcessEngine callerEngine = captureProcessEngine();
    Authentication callerAuth = captureCallerAuthentication(callerEngine);
    ProcessStarterToolContext.setEngine(callerEngine);
    ProcessStarterToolContext.setAuthentication(callerAuth);
    try {
      Result<String> result;
      if (memoryId != null) {
        LangChainMemoryAgent agent = (LangChainMemoryAgent) builder.build();
        result = agent.chat(memoryId, request.getMessage());
      } else {
        LangChainAgent agent = (LangChainAgent) builder.build();
        result = agent.chat(request.getMessage());
      }
      String output = result.content();

      for (ToolExecution exec : result.toolExecutions()) {
        LOG.debug("Tool: {}, Args: {}, Result: {}",
            exec.request().name(), exec.request().arguments(), exec.result());
      }
      LOG.debug("Total tokens: {}", result.tokenUsage());

      return new AgentResponseImpl(output, memoryId);
    } finally {
      ProcessStarterToolContext.clear();
    }
  }

  /**
   * Resolves the {@link ProcessEngine} that invoked this connector.
   *
   * <p>Prefers the engine driving the current command — read from the
   * engine's per-thread {@link Context} stack — so a connector wired to a
   * non-default engine still gets the correct one. Falls back to
   * {@link BpmPlatform#getDefaultProcessEngine()} when no command context is
   * active (e.g. the connector is exercised from a non-engine thread or unit
   * test). Returns {@code null} when neither is reachable. Failures are
   * swallowed and logged at debug level.
   */
  private ProcessEngine captureProcessEngine() {
    try {
      ProcessEngineConfigurationImpl config = Context.getProcessEngineConfiguration();
      if (config != null) {
        ProcessEngine engine = config.getProcessEngine();
        if (engine != null) {
          LOG.debug("Resolved invoking engine '{}' from command context", engine.getName());
          return engine;
        }
      }
    } catch (Exception e) {
      LOG.debug("Could not resolve engine from command context: {}", e.toString());
    }
    try {
      ProcessEngine engine = BpmPlatform.getDefaultProcessEngine();
      if (engine == null) {
        LOG.debug("BpmPlatform.getDefaultProcessEngine() returned null on connector thread");
      }
      return engine;
    } catch (Exception e) {
      LOG.debug("No process engine reachable from connector thread: {}", e.toString());
      return null;
    }
  }

  /**
   * Resolves the {@link Authentication} to forward to tool calls.
   *
   * <p>Tries two sources, in order:
   * <ol>
   *   <li>The current per-thread engine authentication, populated by an
   *       interactive caller (e.g. REST request handler) — present when the
   *       service task runs synchronously inside the starter's command.</li>
   *   <li>The starter of the executing process instance — used when the
   *       service task is async and the connector runs on a JobExecutor
   *       thread, which carries no inherited authentication. Resolved via
   *       {@code historicProcessInstance.getStartUserId()} for the BPMN
   *       execution currently on the thread, plus the starter's group
   *       memberships so authorization checks against group permissions
   *       still pass.</li>
   * </ol>
   * Returns {@code null} when {@code engine} is null or neither source
   * yields a user.
   */
  private Authentication captureCallerAuthentication(ProcessEngine engine) {
    if (engine == null) {
      return null;
    }
    try {
      Authentication current = engine.getIdentityService().getCurrentAuthentication();
      if (current != null) {
        return current;
      }
    } catch (Exception e) {
      LOG.debug("Could not read current authentication from IdentityService: {}", e.toString());
    }
    return deriveAuthenticationFromProcessInstance(engine);
  }

  /**
   * Builds an {@link Authentication} from the start user of the BPMN process
   * instance currently on the thread. Used as a fallback when no interactive
   * authentication is available (typical for async service tasks executed by
   * the JobExecutor). The returned {@code Authentication} carries the
   * starter's userId, their group ids, and the process instance's tenantId
   * (if any). Returns {@code null} when the executing BPMN context, the
   * historic process instance record, or the start user cannot be resolved.
   */
  private Authentication deriveAuthenticationFromProcessInstance(ProcessEngine engine) {
    String processInstanceId = null;
    try {
      BpmnExecutionContext executionContext = Context.getBpmnExecutionContext();
      if (executionContext != null) {
        ExecutionEntity execution = executionContext.getExecution();
        if (execution != null) {
          processInstanceId = execution.getProcessInstanceId();
        }
      }
    } catch (Exception e) {
      LOG.debug("Could not read BPMN execution context: {}", e.toString());
    }
    if (processInstanceId == null) {
      LOG.debug("No BPMN execution context on thread; cannot derive authentication from process instance");
      return null;
    }
    try {
      HistoricProcessInstance hpi = engine.getHistoryService()
          .createHistoricProcessInstanceQuery()
          .processInstanceId(processInstanceId)
          .singleResult();
      if (hpi == null) {
        LOG.debug("No historic process instance found for id '{}'", processInstanceId);
        return null;
      }
      String startUserId = hpi.getStartUserId();
      if (startUserId == null || startUserId.isEmpty()) {
        LOG.debug("Process instance '{}' has no startUserId; leaving authentication null", processInstanceId);
        return null;
      }
      List<String> groupIds = new ArrayList<>();
      try {
        for (Group g : engine.getIdentityService().createGroupQuery().groupMember(startUserId).list()) {
          groupIds.add(g.getId());
        }
      } catch (Exception e) {
        LOG.debug("Could not look up groups for user '{}': {}", startUserId, e.toString());
      }
      List<String> tenantIds = (hpi.getTenantId() != null) ? List.of(hpi.getTenantId()) : null;
      LOG.debug("Derived authentication from process instance '{}': user='{}', groups={}, tenants={}",
          processInstanceId, startUserId, groupIds, tenantIds);
      return new Authentication(startUserId, groupIds, tenantIds);
    } catch (Exception e) {
      LOG.debug("Could not derive authentication from process instance '{}': {}",
          processInstanceId, e.toString());
      return null;
    }
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

  /**
   * Resolves the chat-memory id for this invocation, or {@code null} when
   * memory is disabled. When {@code useChatMemory} is active and no
   * {@code memoryId} is supplied, a fresh UUID is generated so the caller can
   * persist it (via the {@code memoryId} output parameter) and reuse it on
   * subsequent invocations.
   */
  private String resolveMemoryId(AgentRequest request) {
    if (!request.isUseChatMemory()) {
      return null;
    }
    String id = request.getMemoryId();
    if (id != null && !id.isEmpty()) {
      return id;
    }
    String generated = UUID.randomUUID().toString();
    LOG.debug("Generated new chat memory id: {}", generated);
    return generated;
  }

  /**
   * Factory method — builds the {@link ChatMemoryProvider} used when chat
   * memory is active. Each provider call returns a {@link MessageWindowChatMemory}
   * bound to the shared {@link AgentChatMemoryStore}, so memory survives between
   * connector invocations within the same JVM.
   */
  protected ChatMemoryProvider createChatMemoryProvider(AgentRequest request) {
    int maxMessages = request.getChatMemoryMaxMessages();
    return memoryId -> MessageWindowChatMemory.builder()
        .id(memoryId)
        .maxMessages(maxMessages)
        .chatMemoryStore(AgentChatMemoryStore.getStore())
        .build();
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
    LOG.warn("No API key provided: set the OPENAI_API_KEY environment variable "
        + "or supply the 'apiKey' request parameter (ignore if the target endpoint does not require one)");
    return null;
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

  /**
   * Classpath resource used as the system prompt when the caller does not
   * supply {@code instruction}. See {@link AgentConnector#PARAM_NAME_INSTRUCTION}.
   */
  static final String DEFAULT_INSTRUCTION_RESOURCE =
      "/org/cibseven/connect/ai/agent/default-instruction.txt";

  /** Lazily loaded cache for {@link #DEFAULT_INSTRUCTION_RESOURCE}. */
  private static volatile String defaultInstructionCache;

  private String buildSystemPrompt(AgentRequest request) {
    String description = request.getAgentDescription();
    String instruction = request.getInstruction();
    boolean callerInstruction = instruction != null && !instruction.isEmpty();
    LOG.info("buildSystemPrompt: callerInstructionPresent={}, descriptionPresent={}",
        callerInstruction, description != null && !description.isEmpty());
    if (!callerInstruction) {
      instruction = loadDefaultInstruction();
    }
    boolean hasDescription = description != null && !description.isEmpty();
    boolean hasInstruction = instruction != null && !instruction.isEmpty();
    LOG.info("buildSystemPrompt: resolved instruction length={}, description length={}",
        hasInstruction ? instruction.length() : 0,
        hasDescription ? description.length() : 0);
    if (hasDescription && hasInstruction) {
      return description + "\n\n" + instruction;
    }
    return hasInstruction ? instruction : description;
  }

  /**
   * Loads the bundled default system prompt from the classpath and caches it
   * for subsequent invocations.
   *
   * <p>Returns {@code null} when the resource is missing or unreadable —
   * the connector still runs without a system prompt in that case; the
   * failure is logged at ERROR level.
   *
   * <p>TODO(debug): the {@code INFO}-level logging in this method is
   * temporary and should be downgraded to {@code DEBUG} once the
   * distribution packaging is confirmed to ship the resource correctly.
   */
  static String loadDefaultInstruction() {
    String cached = defaultInstructionCache;
    if (cached != null) {
      LOG.info("loadDefaultInstruction: cache hit ({} chars)", cached.length());
      return cached;
    }

    ClassLoader ownCl = AgentConnectorImpl.class.getClassLoader();
    ClassLoader ctxCl = Thread.currentThread().getContextClassLoader();
    LOG.info("loadDefaultInstruction: cache miss, resource='{}', "
        + "AgentConnectorImpl classLoader={}, contextClassLoader={}",
        DEFAULT_INSTRUCTION_RESOURCE, ownCl, ctxCl);

    java.net.URL url = AgentConnectorImpl.class.getResource(DEFAULT_INSTRUCTION_RESOURCE);
    LOG.info("loadDefaultInstruction: AgentConnectorImpl.class.getResource -> {}", url);

    InputStream in = AgentConnectorImpl.class.getResourceAsStream(DEFAULT_INSTRUCTION_RESOURCE);
    if (in == null && ctxCl != null) {
      LOG.info("loadDefaultInstruction: own classloader miss, retrying via contextClassLoader");
      // contextClassLoader.getResource expects no leading slash
      String name = DEFAULT_INSTRUCTION_RESOURCE.startsWith("/")
          ? DEFAULT_INSTRUCTION_RESOURCE.substring(1)
          : DEFAULT_INSTRUCTION_RESOURCE;
      java.net.URL ctxUrl = ctxCl.getResource(name);
      LOG.info("loadDefaultInstruction: contextClassLoader.getResource('{}') -> {}", name, ctxUrl);
      in = ctxCl.getResourceAsStream(name);
    }

    if (in == null) {
      LOG.error("Default agent instruction resource not found on classpath: {} "
          + "(own classloader={}, contextClassLoader={})",
          DEFAULT_INSTRUCTION_RESOURCE, ownCl, ctxCl);
      return null;
    }

    try (InputStream stream = in) {
      cached = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      defaultInstructionCache = cached;
      LOG.info("loadDefaultInstruction: loaded {} chars from {}",
          cached.length(), DEFAULT_INSTRUCTION_RESOURCE);
      return cached;
    } catch (IOException e) {
      LOG.error("Failed to read {} from classpath", DEFAULT_INSTRUCTION_RESOURCE, e);
      return null;
    }
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
    AgentChatListener listener = new AgentChatListener();

    if (reasoningSummary != null && !reasoningSummary.isEmpty()) {
      if (customHeaders != null && !customHeaders.isEmpty()) {
        LOG.warn("'openaiCustomHeaders' is ignored when 'reasoningSummary' is set: "
            + "the OpenAI Responses API builder does not yet expose customHeaders.");
      }
      OpenAiResponsesChatModel.Builder rb = OpenAiResponsesChatModel.builder()
          .apiKey(apiKey)
          .baseUrl(baseUrl)
          .modelName(modelName)
          .listeners(List.of(listener))
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
        .listeners(List.of(listener));
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
