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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.cibseven.connect.ConnectorEndpointNotAllowedException;
import org.cibseven.connect.ai.agent.AgentConnector;
import org.cibseven.connect.ai.agent.AgentRequest;
import org.cibseven.connect.ai.agent.KnowledgeIngestorConnector;
import org.cibseven.connect.ai.agent.KnowledgeIngestorRequest;
import org.cibseven.connect.impl.ConnectorEndpointPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;

/** Endpoint policy in the AI connectors. */
public class AgentConnectorEndpointPolicyTest {

  private static final String AGENT_PROPERTY = ConnectorEndpointPolicy.systemPropertyName(AgentConnector.ID);
  private static final String INGESTOR_PROPERTY =
      ConnectorEndpointPolicy.systemPropertyName(KnowledgeIngestorConnector.ID);

  private static final String ALLOWED_LLM = "https://llm.example.com/v1";
  private static final String ALLOWED_MCP = "http://localhost:8081/mcp";
  private static final String ATTACKER_MCP = "http://attacker.test:18082/mcp";

  /** Thrown once an MCP client would be built. */
  static final class McpClientBuilt extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  /** Records factory calls, opens no connection. */
  static final class RecordingConnector extends AgentConnectorImpl {
    final List<String> chatModelBaseUrls = new ArrayList<>();
    final List<String> mcpUrls = new ArrayList<>();

    @Override
    protected ChatModel createChatModel(AgentRequest request, String apiKey, String baseUrl,
        Map<String, String> customHeaders) {
      chatModelBaseUrls.add(baseUrl);
      return new ChatModel() {
        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
          return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
        }
      };
    }

    @Override
    protected McpClient buildMcpClient(String url, Map<String, String> headers) {
      mcpUrls.add(url);
      throw new McpClientBuilt();
    }

    @Override
    protected EmbeddingModel createEmbeddingModel(AgentRequest request) {
      throw new EmbeddingModelBuilt();
    }
  }

  /** Thrown once an embedding model would be built. */
  static final class EmbeddingModelBuilt extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  /** Records factory calls, opens no connection. */
  static final class RecordingIngestor extends KnowledgeIngestorConnectorImpl {
    final List<String> factoriesRun = new ArrayList<>();

    @Override
    protected EmbeddingModel createEmbeddingModel(KnowledgeIngestorRequest request) {
      factoriesRun.add("embeddingModel");
      throw new EmbeddingModelBuilt();
    }

    @Override
    protected EmbeddingStore<TextSegment> createEmbeddingStore(KnowledgeIngestorRequest request) {
      factoriesRun.add("embeddingStore");
      throw new IllegalStateException("must not be reached");
    }
  }

  private RecordingConnector connector;

  @BeforeEach
  public void setUp() {
    // a real allow-list in the environment would interfere
    for (String connectorId : new String[] {AgentConnector.ID, KnowledgeIngestorConnector.ID}) {
      String variable = ConnectorEndpointPolicy.environmentVariableName(connectorId);
      assumeTrue(System.getenv(variable) == null, variable + " is set in the environment");
    }
    assumeTrue(System.getenv(ConnectorEndpointPolicy.GLOBAL_ENVIRONMENT_VARIABLE) == null,
        ConnectorEndpointPolicy.GLOBAL_ENVIRONMENT_VARIABLE + " is set in the environment");
    connector = new RecordingConnector();
  }

  @AfterEach
  public void clearProperties() {
    System.clearProperty(AGENT_PROPERTY);
    System.clearProperty(INGESTOR_PROPERTY);
    System.clearProperty(ConnectorEndpointPolicy.GLOBAL_SYSTEM_PROPERTY);
  }

  private AgentRequest requestWithMcpServer(String mcpUrl) {
    return connector.createRequest()
        .agentName("agent")
        .message("hello")
        .apiKey("test-key")
        .baseUrl(ALLOWED_LLM)
        .mcpServers("[{\"url\":\"" + mcpUrl + "\",\"headers\":{\"Authorization\":\"Bearer STATIC-MCP-SECRET\"}}]");
  }

  @Test
  public void shouldStillConnectAnywhereWhenNotRestricted() {
    assertThatThrownBy(() -> connector.execute(requestWithMcpServer(ATTACKER_MCP)))
        .isInstanceOf(McpClientBuilt.class);
    assertThat(connector.mcpUrls).containsExactly(ATTACKER_MCP);
  }

  @Test
  public void shouldRefuseMcpServerThatIsNotAllowedBeforeBuildingAnyClient() {
    System.setProperty(AGENT_PROPERTY, "https://llm.example.com " + ALLOWED_MCP);

    assertThatThrownBy(() -> connector.execute(requestWithMcpServer(ATTACKER_MCP)))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class)
        .hasMessageContaining("http://attacker.test:18082")
        .hasMessageNotContaining("STATIC-MCP-SECRET");
    assertThat(connector.mcpUrls).isEmpty();
    assertThat(connector.chatModelBaseUrls).isEmpty();
    assertThat(ProcessStarterToolContext.getActiveListener()).isNull();
  }

  @Test
  public void shouldConnectToAllowedMcpServer() {
    System.setProperty(AGENT_PROPERTY, "https://llm.example.com, http://localhost:8081");

    assertThatThrownBy(() -> connector.execute(requestWithMcpServer(ALLOWED_MCP)))
        .isInstanceOf(McpClientBuilt.class);
    assertThat(connector.mcpUrls).containsExactly(ALLOWED_MCP);
  }

  @Test
  public void shouldRefuseLlmEndpointThatIsNotAllowed() {
    System.setProperty(AGENT_PROPERTY, ALLOWED_MCP);

    AgentRequest request = connector.createRequest()
        .agentName("agent").message("hello").apiKey("test-key").baseUrl("https://attacker.test/v1");

    assertThatThrownBy(() -> connector.execute(request))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class)
        .hasMessageContaining("https://attacker.test:443");
    assertThat(connector.chatModelBaseUrls).isEmpty();
  }

  @Test
  public void shouldRunWithAllowedLlmEndpoint() {
    System.setProperty(AGENT_PROPERTY, "https://llm.example.com");

    AgentRequest request = connector.createRequest()
        .agentName("agent").message("hello").apiKey("test-key").baseUrl(ALLOWED_LLM);

    assertThat(connector.execute(request).getOutput()).isEqualTo("ok");
    assertThat(connector.chatModelBaseUrls).containsExactly(ALLOWED_LLM);
  }

  @Test
  public void shouldRefusePgVectorHostThatIsNotAllowed() {
    System.setProperty(AGENT_PROPERTY, "https://llm.example.com postgresql://db.example.com");

    AgentRequest request = connector.createRequest()
        .agentName("agent").message("hello").apiKey("test-key").baseUrl(ALLOWED_LLM)
        .pgHost("attacker.test").pgPort("5432").pgDatabase("d").pgUser("u").pgPassword("secret").pgTable("t");

    assertThatThrownBy(() -> connector.execute(request))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class)
        .hasMessageContaining("postgresql://attacker.test:5432");
    assertThat(connector.chatModelBaseUrls).isEmpty();
  }

  @Test
  public void shouldCheckPgVectorHostExactlyAsGiven() {
    System.setProperty(AGENT_PROPERTY, "postgresql://db.example.com");
    ConnectorEndpointPolicy policy = ConnectorEndpointPolicy.forConnector(AgentConnector.ID);

    assertThatCode(() -> AgentConnectorImpl.checkPgEndpoint(policy, "db.example.com", "5432"))
        .doesNotThrowAnyException();
    assertThatCode(() -> AgentConnectorImpl.checkPgEndpoint(policy, null, null))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> AgentConnectorImpl.checkPgEndpoint(policy, "db.example.com", "6543"))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class);
    assertThatThrownBy(() -> AgentConnectorImpl.checkPgEndpoint(policy, "db.example.com", "x"))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class);
    assertThatThrownBy(() -> AgentConnectorImpl.checkPgEndpoint(policy, "db.example.com,attacker.test", "5432"))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class);
    assertThatThrownBy(() -> AgentConnectorImpl.checkPgEndpoint(policy, "user@db.example.com", "5432"))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class);
  }

  @Test
  public void shouldRefuseWhenOnlyOneOfSeveralMcpServersIsNotAllowed() {
    System.setProperty(AGENT_PROPERTY, "https://llm.example.com http://localhost:8081 http://localhost:8082");

    AgentRequest request = connector.createRequest()
        .agentName("agent").message("hello").apiKey("test-key").baseUrl(ALLOWED_LLM)
        .mcpServers("[{\"url\":\"http://localhost:8081/mcp\"},"
            + "{\"url\":\"" + ATTACKER_MCP + "\"},"
            + "{\"url\":\"http://localhost:8082/mcp\"}]");

    assertThatThrownBy(() -> connector.execute(request))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class)
        .hasMessageContaining("http://attacker.test:18082");
    assertThat(connector.mcpUrls).isEmpty();
  }

  @Test
  public void shouldReportMcpEntryWithoutUrlAsBefore() {
    System.setProperty(AGENT_PROPERTY, "https://llm.example.com");

    AgentRequest request = connector.createRequest()
        .agentName("agent").message("hello").apiKey("test-key").baseUrl(ALLOWED_LLM)
        .mcpServers("[{\"name\":\"engine\"}]");

    assertThatThrownBy(() -> connector.execute(request))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("missing or empty 'url'");
    assertThat(connector.chatModelBaseUrls).isEmpty();
  }

  private AgentRequest ragRequest(String embeddingModelName) {
    return connector.createRequest()
        .agentName("agent").message("hello").apiKey("test-key").baseUrl(ALLOWED_LLM)
        .pgHost("db.example.com").pgPort("5432").pgDatabase("d").pgUser("u").pgPassword("p").pgTable("t")
        .embeddingModelName(embeddingModelName);
  }

  @Test
  public void shouldRefuseOpenAiEmbeddingEndpointThatIsNotAllowed() {
    System.setProperty(AGENT_PROPERTY, "https://llm.example.com postgresql://db.example.com");

    assertThatThrownBy(() -> connector.execute(ragRequest("text-embedding-3-small")))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class)
        .hasMessageContaining("https://api.openai.com:443");
    assertThat(connector.chatModelBaseUrls).isEmpty();
  }

  @Test
  public void shouldAllowOpenAiEmbeddingEndpointWhenListed() {
    System.setProperty(AGENT_PROPERTY, "https://llm.example.com postgresql://db.example.com https://api.openai.com");

    assertThatThrownBy(() -> connector.execute(ragRequest("text-embedding-3-small")))
        .isInstanceOf(EmbeddingModelBuilt.class);
  }

  @Test
  public void shouldNotCheckEmbeddingEndpointForLocalModelOrWithoutRag() {
    System.setProperty(AGENT_PROPERTY, "https://llm.example.com postgresql://db.example.com");

    assertThatThrownBy(() -> connector.execute(ragRequest(null)))
        .isInstanceOf(EmbeddingModelBuilt.class);

    AgentRequest withoutRag = connector.createRequest()
        .agentName("agent").message("hello").apiKey("test-key").baseUrl(ALLOWED_LLM)
        .embeddingModelName("text-embedding-3-small");
    assertThat(connector.execute(withoutRag).getOutput()).isEqualTo("ok");
  }

  @Test
  public void shouldCheckPgPortExactlyAsGiven() {
    System.setProperty(AGENT_PROPERTY, "postgresql://db.example.com, postgresql://[::1]");
    ConnectorEndpointPolicy policy = ConnectorEndpointPolicy.forConnector(AgentConnector.ID);

    for (String port : new String[] {"0", "65536", "-1", " 5432", "", null}) {
      assertThatThrownBy(() -> AgentConnectorImpl.checkPgEndpoint(policy, "db.example.com", port))
          .as("port '" + port + "'")
          .isInstanceOf(ConnectorEndpointNotAllowedException.class);
    }
    assertThatThrownBy(() -> AgentConnectorImpl.checkPgEndpoint(policy, " db.example.com", "5432"))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class);
    assertThatCode(() -> AgentConnectorImpl.checkPgEndpoint(policy, "::1", "5432"))
        .doesNotThrowAnyException();
  }

  @Test
  public void shouldRefusePgVectorHostListsEvenWithWildcardEntry() {
    System.setProperty(AGENT_PROPERTY, "postgresql://*.example.com");
    ConnectorEndpointPolicy policy = ConnectorEndpointPolicy.forConnector(AgentConnector.ID);

    assertThatCode(() -> AgentConnectorImpl.checkPgEndpoint(policy, "db.example.com", "5432"))
        .doesNotThrowAnyException();
    for (String host : new String[] {"attacker.test,db.example.com", "attacker.test/db.example.com",
        "u:secret@attacker.test,db.example.com"}) {
      assertThatThrownBy(() -> AgentConnectorImpl.checkPgEndpoint(policy, host, "5432"))
          .as(host)
          .isInstanceOf(ConnectorEndpointNotAllowedException.class)
          .hasMessageContaining("(invalid endpoint)")
          .hasMessageNotContaining("attacker")
          .hasMessageNotContaining("secret");
    }
  }

  @Test
  public void shouldApplyGlobalListToEveryEndpointOfTheAgent() {
    String all = "https://llm.example.com http://localhost:8081 postgresql://db.example.com https://api.openai.com";
    AgentRequest request = ragRequest("text-embedding-3-small")
        .mcpServers("[{\"url\":\"" + ALLOWED_MCP + "\"}]");

    for (String missing : new String[] {"https://llm.example.com", "http://localhost:8081",
        "postgresql://db.example.com", "https://api.openai.com"}) {
      System.setProperty(ConnectorEndpointPolicy.GLOBAL_SYSTEM_PROPERTY, all.replace(missing, ""));
      assertThatThrownBy(() -> connector.execute(request))
          .as("without " + missing)
          .isInstanceOf(ConnectorEndpointNotAllowedException.class)
          .hasMessageContaining("not allowed by '" + ConnectorEndpointPolicy.GLOBAL_SYSTEM_PROPERTY + "'");
    }
    assertThat(connector.mcpUrls).isEmpty();

    System.setProperty(ConnectorEndpointPolicy.GLOBAL_SYSTEM_PROPERTY, all);
    assertThatThrownBy(() -> connector.execute(request)).isInstanceOf(McpClientBuilt.class);
  }

  private KnowledgeIngestorRequest ingestorRequest(RecordingIngestor ingestor, String pgHost,
      String embeddingModelName) {
    return ingestor.createRequest()
        .content("some text").pgHost(pgHost).pgPort("5432")
        .pgDatabase("d").pgUser("u").pgPassword("secret").pgTable("t")
        .embeddingModelName(embeddingModelName);
  }

  @Test
  public void knowledgeIngestorShouldRefusePgVectorHostThatIsNotAllowed() {
    System.setProperty(INGESTOR_PROPERTY, "postgresql://db.example.com");
    RecordingIngestor ingestor = new RecordingIngestor();

    assertThatThrownBy(() -> ingestor.execute(ingestorRequest(ingestor, "attacker.test", null)))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class)
        .hasMessageContaining("'cibseven-knowledge-ingestor'");
    assertThat(ingestor.factoriesRun).isEmpty();
  }

  @Test
  public void knowledgeIngestorShouldConnectToAllowedPgVectorHost() {
    System.setProperty(INGESTOR_PROPERTY, "postgresql://db.example.com");
    RecordingIngestor ingestor = new RecordingIngestor();

    assertThatThrownBy(() -> ingestor.execute(ingestorRequest(ingestor, "db.example.com", null)))
        .isInstanceOf(EmbeddingModelBuilt.class);
    assertThat(ingestor.factoriesRun).containsExactly("embeddingModel");
  }

  @Test
  public void knowledgeIngestorShouldRefuseOpenAiEmbeddingEndpointThatIsNotAllowed() {
    System.setProperty(INGESTOR_PROPERTY, "postgresql://db.example.com");
    RecordingIngestor ingestor = new RecordingIngestor();

    assertThatThrownBy(() -> ingestor.execute(ingestorRequest(ingestor, "db.example.com", "text-embedding-3-small")))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class)
        .hasMessageContaining("https://api.openai.com:443");
    assertThat(ingestor.factoriesRun).isEmpty();
  }

}
