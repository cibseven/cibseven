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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.variable.value.TypedValue;
import org.cibseven.connect.ai.agent.AgentConnectorConstants;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;

/**
 * Tests for the process-variable chat-memory store: variable naming, JSON
 * round-trip, the fallback paths (no engine context / flag disabled), and the
 * memoryId length guard.
 */
public class ProcessVariableChatMemoryStoreTest {

  private static final String MEMORY_ID = "mem-1";
  private static final String VARIABLE_NAME =
      AgentConnectorConstants.AGENT_CONNECTOR_MEMORY_PREFIX + MEMORY_ID;

  private ChatMemoryStore fallback;
  private ProcessVariableChatMemoryStore store;
  private boolean pushedExecution;

  @Before
  public void setUp() {
    fallback = new InMemoryChatMemoryStore();
    store = new ProcessVariableChatMemoryStore(fallback);
  }

  @After
  public void tearDown() {
    if (pushedExecution) {
      try { Context.removeExecutionContext(); } catch (Exception ignored) { }
      pushedExecution = false;
    }
    System.clearProperty(ProcessVariableChatMemoryStore.CHAT_MEMORY_VARIABLE_PROPERTY);
    AgentChatListener.ENV_READER = System::getenv;
  }

  private ExecutionEntity pushExecution() {
    ExecutionEntity execution = mock(ExecutionEntity.class);
    Context.setExecutionContext(execution);
    pushedExecution = true;
    return execution;
  }

  // ── process-variable path ────────────────────────────────────────────────

  @Test
  public void shouldWriteMessagesToProcessVariable() {
    ExecutionEntity execution = pushExecution();

    store.updateMessages(MEMORY_ID, Arrays.asList(UserMessage.from("hi"), AiMessage.from("ok")));

    verify(execution).setVariable(org.mockito.Matchers.eq(VARIABLE_NAME),
        org.mockito.Matchers.any());
  }

  @Test
  public void shouldReadMessagesBackFromProcessVariable() {
    ExecutionEntity execution = pushExecution();
    List<ChatMessage> original = Arrays.asList(UserMessage.from("My name is Alice."),
        AiMessage.from("Nice to meet you, Alice."));
    when(execution.getVariable(VARIABLE_NAME))
        .thenReturn(ChatMessageSerializer.messagesToJson(original));

    List<ChatMessage> restored = store.getMessages(MEMORY_ID);

    assertThat(restored).hasSize(2);
    assertThat(restored.get(0)).isInstanceOf(UserMessage.class);
    assertThat(((UserMessage) restored.get(0)).singleText()).isEqualTo("My name is Alice.");
    assertThat(restored.get(1)).isInstanceOf(AiMessage.class);
    assertThat(((AiMessage) restored.get(1)).text()).isEqualTo("Nice to meet you, Alice.");
  }

  @Test
  public void shouldReturnEmptyListWhenVariableAbsent() {
    ExecutionEntity execution = pushExecution();
    when(execution.getVariable(VARIABLE_NAME)).thenReturn(null);

    assertThat(store.getMessages(MEMORY_ID)).isEmpty();
  }

  @Test
  public void shouldReturnEmptyListWhenVariableEmpty() {
    ExecutionEntity execution = pushExecution();
    when(execution.getVariable(VARIABLE_NAME)).thenReturn("");

    assertThat(store.getMessages(MEMORY_ID)).isEmpty();
  }

  @Test
  public void shouldReturnEmptyListOnUndecodableJson() {
    ExecutionEntity execution = pushExecution();
    when(execution.getVariable(VARIABLE_NAME)).thenReturn("{not valid json");

    // Degrades to an empty conversation instead of failing the job.
    assertThat(store.getMessages(MEMORY_ID)).isEmpty();
  }

  @Test
  public void shouldRemoveVariableOnDelete() {
    ExecutionEntity execution = pushExecution();

    store.deleteMessages(MEMORY_ID);

    verify(execution).removeVariable(VARIABLE_NAME);
  }

  // ── round-trip of the full message-type range ────────────────────────────

  /**
   * The serialization is done by LangChain4j, so an upgrade can change or drop
   * fields. Two failure modes make that expensive to notice in production: a
   * changed format trips the catch in {@code getMessages} and silently yields an
   * empty conversation, and a silently dropped field yields a structurally
   * invalid message sequence that the provider rejects (a tool result without
   * its preceding tool call is a hard error on the OpenAI API). This test pins
   * the whole range down at build time.
   */
  @Test
  public void shouldRoundTripToolCallSequenceThroughProcessVariable() {
    ExecutionEntity execution = statefulExecutionMock();
    Context.setExecutionContext(execution);
    pushedExecution = true;

    AiMessage toolCall = AiMessage.builder()
        .toolExecutionRequests(Arrays.asList(ToolExecutionRequest.builder()
            .id("call_1")
            .name("queryInvoices")
            .arguments("{\"kunde\":\"4711\"}")
            .build()))
        .build();

    store.updateMessages(MEMORY_ID, Arrays.asList(
        SystemMessage.from("Du bist hilfsbereit."),
        UserMessage.from("Wie viele offene Rechnungen hat Kunde 4711?"),
        toolCall,
        ToolExecutionResultMessage.from("call_1", "queryInvoices", "3"),
        AiMessage.from("Kunde 4711 hat 3 offene Rechnungen.")));

    // Guard the test's own integrity: had the engine context not taken effect,
    // this would run through the JVM-local fallback, which keeps the objects
    // as-is and would exercise no serialization at all — the assertions below
    // would then pass while proving nothing.
    verify(execution).setVariable(org.mockito.Matchers.eq(VARIABLE_NAME),
        org.mockito.Matchers.any());

    List<ChatMessage> restored = store.getMessages(MEMORY_ID);

    assertThat(restored).hasSize(5);
    assertThat(restored.get(0)).isInstanceOf(SystemMessage.class);
    assertThat(((SystemMessage) restored.get(0)).text()).isEqualTo("Du bist hilfsbereit.");
    assertThat(((UserMessage) restored.get(1)).singleText())
        .isEqualTo("Wie viele offene Rechnungen hat Kunde 4711?");

    // The tool call must keep id, name and arguments — without them the next
    // request would carry a tool result that references nothing.
    AiMessage restoredCall = (AiMessage) restored.get(2);
    assertThat(restoredCall.hasToolExecutionRequests()).isTrue();
    ToolExecutionRequest request = restoredCall.toolExecutionRequests().get(0);
    assertThat(request.id()).isEqualTo("call_1");
    assertThat(request.name()).isEqualTo("queryInvoices");
    assertThat(request.arguments()).isEqualTo("{\"kunde\":\"4711\"}");

    // ... and the result must still point at that same id.
    ToolExecutionResultMessage restoredResult = (ToolExecutionResultMessage) restored.get(3);
    assertThat(restoredResult.id()).isEqualTo("call_1");
    assertThat(restoredResult.toolName()).isEqualTo("queryInvoices");
    assertThat(restoredResult.text()).isEqualTo("3");

    assertThat(((AiMessage) restored.get(4)).text())
        .isEqualTo("Kunde 4711 hat 3 offene Rechnungen.");
  }

  @Test
  public void shouldRoundTripReasoningThinkingContent() {
    ExecutionEntity execution = statefulExecutionMock();
    Context.setExecutionContext(execution);
    pushedExecution = true;

    store.updateMessages(MEMORY_ID, Arrays.asList(
        AiMessage.builder().text("Antwort").thinking("Erst nachdenken").build()));

    AiMessage restored = (AiMessage) store.getMessages(MEMORY_ID).get(0);

    assertThat(restored.text()).isEqualTo("Antwort");
    assertThat(restored.thinking()).isEqualTo("Erst nachdenken");
  }

  @Test
  public void shouldRoundTripEmptyMessageList() {
    ExecutionEntity execution = statefulExecutionMock();
    Context.setExecutionContext(execution);
    pushedExecution = true;

    store.updateMessages(MEMORY_ID, java.util.Collections.emptyList());

    assertThat(store.getMessages(MEMORY_ID)).isEmpty();
  }

  /**
   * Mocked execution that actually retains variables, standing in for the engine
   * database. {@code setVariable} receives a {@code TypedValue}; the engine hands
   * the deserialized value back on read, so the mock unwraps it the same way.
   */
  private static ExecutionEntity statefulExecutionMock() {
    Map<String, Object> variables = new HashMap<>();
    ExecutionEntity execution = mock(ExecutionEntity.class);
    doAnswer(invocation -> {
      Object value = invocation.getArguments()[1];
      variables.put((String) invocation.getArguments()[0],
          (value instanceof TypedValue) ? ((TypedValue) value).getValue() : value);
      return null;
    }).when(execution).setVariable(org.mockito.Matchers.anyString(), org.mockito.Matchers.any());
    when(execution.getVariable(org.mockito.Matchers.anyString()))
        .thenAnswer(invocation -> variables.get((String) invocation.getArguments()[0]));
    return execution;
  }

  // ── fallback paths ───────────────────────────────────────────────────────

  @Test
  public void shouldDelegateToFallbackWithoutExecutionContext() {
    // No BpmnExecutionContext pushed onto this thread.
    List<ChatMessage> messages = Arrays.asList(UserMessage.from("hi"));
    store.updateMessages(MEMORY_ID, messages);

    assertThat(fallback.getMessages(MEMORY_ID)).hasSize(1);
    assertThat(store.getMessages(MEMORY_ID)).hasSize(1);
  }

  @Test
  public void shouldDelegateToFallbackWhenFlagDisabledViaSystemProperty() {
    ExecutionEntity execution = pushExecution();
    System.setProperty(ProcessVariableChatMemoryStore.CHAT_MEMORY_VARIABLE_PROPERTY, "false");

    store.updateMessages(MEMORY_ID, Arrays.asList(UserMessage.from("hi")));

    verify(execution, never()).setVariable(org.mockito.Matchers.anyString(),
        org.mockito.Matchers.any());
    assertThat(fallback.getMessages(MEMORY_ID)).hasSize(1);
  }

  @Test
  public void shouldDelegateToFallbackWhenFlagDisabledViaEnvVar() {
    ExecutionEntity execution = pushExecution();
    AgentChatListener.ENV_READER = name ->
        ProcessVariableChatMemoryStore.CHAT_MEMORY_VARIABLE_ENV_VAR.equals(name) ? "false" : null;

    store.updateMessages(MEMORY_ID, Arrays.asList(UserMessage.from("hi")));

    verify(execution, never()).setVariable(org.mockito.Matchers.anyString(),
        org.mockito.Matchers.any());
    assertThat(fallback.getMessages(MEMORY_ID)).hasSize(1);
  }

  @Test
  public void shouldPreferSystemPropertyOverEnvVar() {
    ExecutionEntity execution = pushExecution();
    System.setProperty(ProcessVariableChatMemoryStore.CHAT_MEMORY_VARIABLE_PROPERTY, "true");
    AgentChatListener.ENV_READER = name -> "false";

    store.updateMessages(MEMORY_ID, Arrays.asList(UserMessage.from("hi")));

    verify(execution).setVariable(org.mockito.Matchers.eq(VARIABLE_NAME),
        org.mockito.Matchers.any());
  }

  // ── guards ───────────────────────────────────────────────────────────────

  @Test
  public void shouldRejectOverlongMemoryId() {
    int maxIdLength = 255 - AgentConnectorConstants.AGENT_CONNECTOR_MEMORY_PREFIX.length();
    String tooLong = repeat('x', maxIdLength + 1);

    assertThatThrownBy(() -> ProcessVariableChatMemoryStore.variableName(tooLong))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("memoryId is too long");
  }

  @Test
  public void shouldAcceptMemoryIdAtMaximumLength() {
    int maxIdLength = 255 - AgentConnectorConstants.AGENT_CONNECTOR_MEMORY_PREFIX.length();
    String atLimit = repeat('x', maxIdLength);

    assertThat(ProcessVariableChatMemoryStore.variableName(atLimit)).hasSize(255);
  }

  @Test
  public void shouldRejectNullFallback() {
    assertThatThrownBy(() -> new ProcessVariableChatMemoryStore(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * The webclient enumerates chat-log variables with
   * {@code name.startsWith(AGENT_CONNECTOR_LOG_PREFIX)}. A memory variable must
   * not match, or memory blobs would surface in the AI chat UI and be parsed as
   * audit events.
   */
  @Test
  public void shouldNotCollideWithChatLogVariablePrefix() {
    assertThat(ProcessVariableChatMemoryStore.variableName(MEMORY_ID))
        .doesNotStartWith(AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX);
    assertThat(AgentConnectorConstants.AGENT_CONNECTOR_MEMORY_PREFIX)
        .doesNotStartWith(AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX);
  }

  private static String repeat(char c, int times) {
    StringBuilder sb = new StringBuilder(times);
    for (int i = 0; i < times; i++) {
      sb.append(c);
    }
    return sb.toString();
  }

}
