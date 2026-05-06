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
package org.cibseven.connect.agentconnector.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.cibseven.connect.agentconnector.AgentConnector;
import org.cibseven.connect.agentconnector.AgentConnectorConstants;
import org.cibseven.connect.agentconnector.AgentRequest;
import org.cibseven.connect.agentconnector.AgentResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;

/**
 * Tests for the chat-memory integration: parameter wiring, automatic id
 * generation, response exposure, and end-to-end conversation continuity across
 * connector invocations using a stubbed {@link ChatModel}.
 */
public class AgentChatMemoryTest {

  /**
   * Connector subclass that swaps the real OpenAI chat model for an
   * {@link EchoChatModel} — so tests can call {@code execute(...)} without any
   * network access while still triggering the {@link ChatMemoryProvider}
   * code path.
   */
  static final class EchoConnector extends AgentConnectorImpl {
    final EchoChatModel echoModel = new EchoChatModel();

    @Override
    protected ChatModel createChatModel(AgentRequest request, String apiKey, String baseUrl,
        Map<String, String> customHeaders) {
      return echoModel;
    }
  }

  private EchoConnector connector;
  private ChatMemoryStore originalStore;

  @Before
  public void setUp() {
    connector = new EchoConnector();
    // Isolate each test from any shared state between runs.
    originalStore = AgentChatMemoryStore.getStore();
    AgentChatMemoryStore.setStore(new InMemoryChatMemoryStore());
  }

  @After
  public void tearDown() {
    AgentChatMemoryStore.setStore(originalStore);
  }

  // ── Setter / getter wiring ───────────────────────────────────────────────

  @Test
  public void shouldSetAndGetUseChatMemory() {
    AgentRequest request = connector.createRequest().useChatMemory(true);
    assertThat(request.isUseChatMemory()).isTrue();
  }

  @Test
  public void shouldDefaultUseChatMemoryToFalse() {
    AgentRequest request = connector.createRequest();
    assertThat(request.isUseChatMemory()).isFalse();
  }

  @Test
  public void shouldSetAndGetMemoryId() {
    AgentRequest request = connector.createRequest().memoryId("mem-123");
    assertThat(request.getMemoryId()).isEqualTo("mem-123");
  }

  @Test
  public void shouldDefaultMemoryIdToNull() {
    AgentRequest request = connector.createRequest();
    assertThat(request.getMemoryId()).isNull();
  }

  @Test
  public void shouldReturnDefaultChatMemoryMaxMessagesWhenNotSet() {
    AgentRequest request = connector.createRequest();
    assertThat(request.getChatMemoryMaxMessages())
        .isEqualTo(AgentConnectorConstants.DEFAULT_CHAT_MEMORY_MAX_MESSAGES);
  }

  @Test
  public void shouldSetAndGetChatMemoryMaxMessages() {
    AgentRequest request = connector.createRequest().chatMemoryMaxMessages(5);
    assertThat(request.getChatMemoryMaxMessages()).isEqualTo(5);
  }

  @Test
  public void shouldStoreMemoryParametersInGenericMap() {
    AgentRequest request = connector.createRequest()
        .useChatMemory(true)
        .memoryId("mem-xyz")
        .chatMemoryMaxMessages(10);

    assertThat(request.getRequestParameters())
        .containsEntry(AgentConnector.PARAM_NAME_USE_CHAT_MEMORY, true)
        .containsEntry(AgentConnector.PARAM_NAME_MEMORY_ID, "mem-xyz")
        .containsEntry(AgentConnector.PARAM_NAME_CHAT_MEMORY_MAX_MESSAGES, 10);
  }

  // ── execute(): id resolution + response exposure ─────────────────────────

  @Test
  public void shouldExposeNullMemoryIdResponseParameterWhenChatMemoryDisabled() {
    AgentRequest request = connector.createRequest()
        .agentName("agent")
        .instruction("inst")
        .message("Hello")
        .apiKey("test-key");

    AgentResponse response = connector.execute(request);

    assertThat(response.getMemoryId()).isNull();
    // The key must be present in the response parameter map (with a null value), so BPMN
    // output mappings like ${memoryId} resolve cleanly instead of throwing PropertyNotFoundException.
    assertThat(response.getResponseParameters()).containsKey(AgentConnector.PARAM_NAME_MEMORY_ID);
    Object memoryIdParam = response.getResponseParameter(AgentConnector.PARAM_NAME_MEMORY_ID);
    assertThat(memoryIdParam).isNull();
  }

  /**
   * Mirrors the BPMN scenario where the process leaves {@code useChatMemory} at
   * {@code false} but still maps {@code <camunda:outputParameter
   * name="memoryId">${memoryId}</camunda:outputParameter>}. The Camunda EL engine
   * resolves {@code ${memoryId}} against the connector's response parameter map;
   * the {@code memoryId} key must therefore always be present so the lookup
   * yields {@code null} instead of failing with PropertyNotFoundException.
   */
  @Test
  public void shouldNotFailWhenMemoryIdOutputParameterIsRequestedAndChatMemoryDisabled() {
    AgentRequest request = connector.createRequest()
        .agentName("agent")
        .instruction("inst")
        .message("Hello")
        .apiKey("test-key");
    // useChatMemory left at default (false); memoryId not provided.

    AgentResponse response = connector.execute(request);

    assertThat(response.getResponseParameters()).containsKey(AgentConnector.PARAM_NAME_MEMORY_ID);
    Object memoryIdParam = response.getResponseParameter(AgentConnector.PARAM_NAME_MEMORY_ID);
    assertThat(memoryIdParam).isNull();
  }

  @Test
  public void shouldGenerateMemoryIdWhenChatMemoryActiveAndIdMissing() {
    AgentRequest request = connector.createRequest()
        .agentName("agent")
        .instruction("inst")
        .message("Hello")
        .apiKey("test-key")
        .useChatMemory(true);

    AgentResponse response = connector.execute(request);

    assertThat(response.getMemoryId()).isNotNull().isNotEmpty();
    // Generated value is also exposed via the response parameter map for BPMN
    // <camunda:outputParameter name="memoryId">${memoryId}</camunda:outputParameter>.
    assertThat((String) response.getResponseParameter(AgentConnector.PARAM_NAME_MEMORY_ID))
        .isEqualTo(response.getMemoryId());
  }

  @Test
  public void shouldHonorProvidedMemoryIdWhenChatMemoryActive() {
    AgentRequest request = connector.createRequest()
        .agentName("agent")
        .instruction("inst")
        .message("Hello")
        .apiKey("test-key")
        .useChatMemory(true)
        .memoryId("custom-mem-1");

    AgentResponse response = connector.execute(request);

    assertThat(response.getMemoryId()).isEqualTo("custom-mem-1");
  }

  // ── End-to-end conversation continuity ───────────────────────────────────

  @Test
  public void shouldCarryConversationAcrossInvocationsWhenSameMemoryIdReused() {
    String memoryId = "loop-1";

    AgentRequest first = connector.createRequest()
        .agentName("agent")
        .instruction("You are helpful.")
        .message("My name is Alice.")
        .apiKey("test-key")
        .useChatMemory(true)
        .memoryId(memoryId);

    connector.execute(first);

    AgentRequest second = connector.createRequest()
        .agentName("agent")
        .instruction("You are helpful.")
        .message("What's my name?")
        .apiKey("test-key")
        .useChatMemory(true)
        .memoryId(memoryId);

    connector.execute(second);

    // Last invocation must have replayed the prior turn from the shared store:
    // that means the new request includes the previous user/assistant exchange
    // ahead of the current "What's my name?" message.
    List<ChatMessage> lastMessages = connector.echoModel.lastMessages;
    assertThat(lastMessages.size()).isGreaterThanOrEqualTo(3);
    List<String> userTexts = userTextsOf(lastMessages);
    assertThat(userTexts).containsSubsequence("My name is Alice.", "What's my name?");
  }

  @Test
  public void shouldIsolateConversationsByMemoryId() {
    AgentRequest a1 = connector.createRequest()
        .agentName("agent")
        .instruction("You are helpful.")
        .message("Secret-A")
        .apiKey("test-key")
        .useChatMemory(true)
        .memoryId("convo-A");
    connector.execute(a1);

    AgentRequest b1 = connector.createRequest()
        .agentName("agent")
        .instruction("You are helpful.")
        .message("Hello from B")
        .apiKey("test-key")
        .useChatMemory(true)
        .memoryId("convo-B");
    connector.execute(b1);

    // The B-conversation must NOT contain the A-conversation user turn.
    List<ChatMessage> bMessages = connector.echoModel.lastMessages;
    assertThat(userTextsOf(bMessages)).doesNotContain("Secret-A");
  }

  private static List<String> userTextsOf(List<ChatMessage> messages) {
    List<String> texts = new java.util.ArrayList<>();
    for (ChatMessage m : messages) {
      if (m instanceof UserMessage) {
        texts.add(((UserMessage) m).singleText());
      }
    }
    return texts;
  }

  // ── Stubs ────────────────────────────────────────────────────────────────

  /**
   * Minimal {@link ChatModel} that records the messages sent on the last
   * request and replies with a canned acknowledgement, avoiding any HTTP I/O.
   */
  static final class EchoChatModel implements ChatModel {
    volatile List<ChatMessage> lastMessages;

    @Override
    public ChatResponse doChat(ChatRequest request) {
      this.lastMessages = request.messages();
      return ChatResponse.builder()
          .aiMessage(AiMessage.from("ok"))
          .build();
    }
  }

}
