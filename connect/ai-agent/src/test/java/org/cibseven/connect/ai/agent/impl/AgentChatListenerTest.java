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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.connect.ai.agent.AgentConnectorConstants;
import org.junit.After;
import org.junit.Test;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

public class AgentChatListenerTest {

  /** Tracks BpmnExecutionContext pushed onto the engine's per-thread stack so {@link #tearDown()} drains it. */
  private boolean pushedExecution;

  @After
  public void tearDown() {
    if (pushedExecution) {
      try { Context.removeExecutionContext(); } catch (Exception ignored) {}
      pushedExecution = false;
    }
  }

  @Test
  public void shouldStartWithNoCapturedEvents() {
    AgentChatListener listener = new AgentChatListener();

    assertThat(listener.events()).isEmpty();
  }

  @Test
  public void shouldCaptureRequestEventWithMessagesAndTools() throws Exception {
    AgentChatListener listener = new AgentChatListener();

    ChatRequest request = ChatRequest.builder()
        .messages(Arrays.asList(
            SystemMessage.from("You are an invoice agent."),
            UserMessage.from("Extract data from this invoice")))
        .toolSpecifications(
            ToolSpecification.builder().name("lookupCustomer").build(),
            ToolSpecification.builder().name("createInvoice").build())
        .build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));

    List<Map<String, Object>> events = listener.events();
    assertThat(events).hasSize(1);

    Map<String, Object> event = events.get(0);
    assertThat(event).containsEntry("type", "request");
    assertThat(event).containsKey("timestamp");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> messages = (List<Map<String, Object>>) event.get("messages");
    assertThat(messages).hasSize(2);
    assertThat(messages.get(0)).containsEntry("role", "SYSTEM")
        .containsEntry("content", "You are an invoice agent.");
    assertThat(messages.get(1)).containsEntry("role", "USER")
        .containsEntry("content", "Extract data from this invoice");

    assertThat(event.get("tools")).isEqualTo(Arrays.asList("lookupCustomer", "createInvoice"));
  }

  @Test
  public void shouldCaptureResponseEventWithAnswerAndTokens() throws Exception {
    AgentChatListener listener = new AgentChatListener();

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("Hello"))).build();
    ChatResponse response = ChatResponse.builder()
        .aiMessage(AiMessage.from("The customer is Acme Corp."))
        .tokenUsage(new TokenUsage(312, 18, 330))
        .build();
    listener.onResponse(new ChatModelResponseContext(response, request, null, new HashMap<>()));

    List<Map<String, Object>> events = listener.events();
    assertThat(events).hasSize(1);

    Map<String, Object> event = events.get(0);
    assertThat(event).containsEntry("type", "response")
        .containsEntry("answer", "The customer is Acme Corp.")
        .doesNotContainKey("toolCalls");

    @SuppressWarnings("unchecked")
    Map<String, Object> tokens = (Map<String, Object>) event.get("tokens");
    assertThat(tokens).containsEntry("input", 312)
        .containsEntry("output", 18)
        .containsEntry("total", 330);
  }

  @Test
  public void shouldCaptureResponseEventWithToolCalls() throws Exception {
    AgentChatListener listener = new AgentChatListener();

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("Look up customer 123"))).build();
    ToolExecutionRequest call = ToolExecutionRequest.builder()
        .name("lookupCustomer")
        .arguments("{\"id\":\"123\"}")
        .build();
    ChatResponse response = ChatResponse.builder()
        .aiMessage(AiMessage.from(call))
        .build();
    listener.onResponse(new ChatModelResponseContext(response, request, null, new HashMap<>()));

    Map<String, Object> event = listener.events().get(0);
    assertThat(event).containsEntry("type", "response")
        .doesNotContainKey("answer")
        .doesNotContainKey("tokens");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> calls = (List<Map<String, Object>>) event.get("toolCalls");
    assertThat(calls).hasSize(1);
    assertThat(calls.get(0)).containsEntry("name", "lookupCustomer")
        .containsEntry("arguments", "{\"id\":\"123\"}");
  }

  @Test
  public void shouldCaptureErrorEvent() throws Exception {
    AgentChatListener listener = new AgentChatListener();

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("ping"))).build();
    listener.onError(new ChatModelErrorContext(
        new RuntimeException("Connection refused"), request, null, new HashMap<>()));

    Map<String, Object> event = listener.events().get(0);
    assertThat(event).containsEntry("type", "error")
        .containsEntry("message", "Connection refused")
        .containsKey("timestamp");
  }

  @Test
  public void shouldPreserveEventOrderAcrossMultipleCallbacks() throws Exception {
    AgentChatListener listener = new AgentChatListener();

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("Hi"))).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));

    ChatResponse response = ChatResponse.builder()
        .aiMessage(AiMessage.from("Hello!"))
        .build();
    listener.onResponse(new ChatModelResponseContext(response, request, null, new HashMap<>()));

    listener.onError(new ChatModelErrorContext(
        new IllegalStateException("boom"), request, null, new HashMap<>()));

    List<Map<String, Object>> events = listener.events();
    assertThat(events).hasSize(3);
    assertThat(events.get(0)).containsEntry("type", "request");
    assertThat(events.get(1)).containsEntry("type", "response");
    assertThat(events.get(2)).containsEntry("type", "error");
  }

  @Test
  public void shouldOmitToolsWhenRequestHasNone() throws Exception {
    AgentChatListener listener = new AgentChatListener();

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("Hi"))).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));

    Map<String, Object> event = listener.events().get(0);
    assertThat(event).doesNotContainKey("tools");
  }

  @Test
  public void shouldDisablePersistenceWhenInvokedOutsideEngineContext() {
    // Without a BpmnExecutionContext on the current thread there is no activityId,
    // so the listener cannot derive a variable name and persistence is disabled.
    assertThat(new AgentChatListener().variableName()).isNull();
  }

  @Test
  public void shouldNotFailWhenInvokedOutsideEngineContext() {
    AgentChatListener listener = new AgentChatListener();

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("Hi"))).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));

    // Persistence is a no-op without a BpmnExecutionContext, but the in-memory event
    // list is still populated so callers/tests can inspect what would have been written.
    assertThat(listener.events()).hasSize(1);
    assertThat(listener.events().get(0)).containsEntry("type", "request");
  }

  // ── extractContent: per-message-type branches ────────────────────────────

  @Test
  public void shouldExtractMultiContentUserMessageViaToString() {
    AgentChatListener listener = new AgentChatListener();

    // Two TextContents → hasSingleText() is false → falls back to toString().
    UserMessage multi = new UserMessage(TextContent.from("hello"), TextContent.from("world"));
    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(multi)).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> messages =
        (List<Map<String, Object>>) listener.events().get(0).get("messages");
    String content = (String) messages.get(0).get("content");
    // toString of a UserMessage with multiple TextContents mentions both texts.
    assertThat(content).contains("hello").contains("world");
  }

  @Test
  public void shouldExtractAiMessageAsJsonWithTextAndThinking() throws Exception {
    AgentChatListener listener = new AgentChatListener();

    AiMessage ai = AiMessage.builder()
        .text("final answer")
        .thinking("reasoned step-by-step")
        .build();
    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(ai)).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> messages =
        (List<Map<String, Object>>) listener.events().get(0).get("messages");
    String json = (String) messages.get(0).get("content");
    @SuppressWarnings("unchecked")
    Map<String, Object> decoded = new com.fasterxml.jackson.databind.ObjectMapper()
        .readValue(json, Map.class);
    assertThat(decoded)
        .containsEntry("text", "final answer")
        .containsEntry("thinking", "reasoned step-by-step");
  }

  @Test
  public void shouldExtractToolExecutionResultMessageContent() {
    AgentChatListener listener = new AgentChatListener();

    ToolExecutionResultMessage toolResult =
        ToolExecutionResultMessage.from("call-1", "lookupCustomer", "{\"customer\":\"Acme\"}");
    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(toolResult)).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> messages =
        (List<Map<String, Object>>) listener.events().get(0).get("messages");
    assertThat(messages.get(0))
        .containsEntry("content", "{\"customer\":\"Acme\"}")
        .containsEntry("toolName", "lookupCustomer");
  }

  // ── persistEvents + variableName derivation when run under an execution context ──

  @Test
  public void shouldDeriveVariableNameAndPersistEventsToExecutionVariable() {
    ExecutionEntity execution = mock(ExecutionEntity.class);
    when(execution.getActivityId()).thenReturn("extractInvoice");
    when(execution.getVariable(AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX + "extractInvoice"))
        .thenReturn(null);
    pushExecution(execution);

    AgentChatListener listener = new AgentChatListener();
    String expectedVar = AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX + "extractInvoice";
    assertThat(listener.variableName()).isEqualTo(expectedVar);

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("hi"))).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));

    // The chat-log variable AND the connector-flag variable must both be written on
    // the first persistEvents() call.
    verify(execution).setVariable(org.mockito.Matchers.eq(expectedVar), org.mockito.Matchers.any());
    verify(execution).setVariable(
        AgentConnectorConstants.AGENT_CONNECTOR_FLAG_VARIABLE_NAME, true);
  }

  @Test
  public void shouldWriteConnectorFlagVariableOnlyOnceAcrossMultipleEvents() {
    ExecutionEntity execution = mock(ExecutionEntity.class);
    when(execution.getActivityId()).thenReturn("agentTask");
    when(execution.getVariable(AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX + "agentTask"))
        .thenReturn(null);
    pushExecution(execution);

    AgentChatListener listener = new AgentChatListener();

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("hi"))).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));
    listener.onResponse(new ChatModelResponseContext(
        ChatResponse.builder().aiMessage(AiMessage.from("ok")).build(),
        request, null, new HashMap<>()));
    listener.onError(new ChatModelErrorContext(
        new RuntimeException("late failure"), request, null, new HashMap<>()));

    // 3 events → chat-log variable written 3 times; flag exactly once.
    verify(execution, org.mockito.Mockito.times(3))
        .setVariable(org.mockito.Matchers.eq(
            AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX + "agentTask"),
            org.mockito.Matchers.any());
    verify(execution, org.mockito.Mockito.times(1))
        .setVariable(AgentConnectorConstants.AGENT_CONNECTOR_FLAG_VARIABLE_NAME, true);
  }

  // ── loadExistingEvents: prior log resumed on construction ────────────────

  @Test
  public void shouldResumeFromExistingLogVariableOnConstruction() {
    String varName = AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX + "resumable";
    String priorJson = "[{\"type\":\"request\",\"timestamp\":\"2026-05-15T10:00:00Z\"}]";
    ExecutionEntity execution = mock(ExecutionEntity.class);
    when(execution.getActivityId()).thenReturn("resumable");
    when(execution.getVariable(varName)).thenReturn(priorJson);
    pushExecution(execution);

    AgentChatListener listener = new AgentChatListener();

    assertThat(listener.events()).hasSize(1);
    assertThat(listener.events().get(0)).containsEntry("type", "request");
  }

  @Test
  public void shouldStartWithEmptyEventsWhenExistingVariableIsEmptyString() {
    String varName = AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX + "blank";
    ExecutionEntity execution = mock(ExecutionEntity.class);
    when(execution.getActivityId()).thenReturn("blank");
    when(execution.getVariable(varName)).thenReturn("");
    pushExecution(execution);

    AgentChatListener listener = new AgentChatListener();
    assertThat(listener.events()).isEmpty();
  }

  @Test
  public void shouldStartWithEmptyEventsWhenExistingLogIsMalformed() {
    String varName = AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX + "bad";
    ExecutionEntity execution = mock(ExecutionEntity.class);
    when(execution.getActivityId()).thenReturn("bad");
    when(execution.getVariable(varName)).thenReturn("not-json {{{");
    pushExecution(execution);

    AgentChatListener listener = new AgentChatListener();
    // Malformed JSON is logged and silently swallowed — the listener proceeds with an empty log.
    assertThat(listener.events()).isEmpty();
  }

  @Test
  public void shouldDisablePersistenceWhenActivityIdEmpty() {
    ExecutionEntity execution = mock(ExecutionEntity.class);
    when(execution.getActivityId()).thenReturn(""); // present but blank
    pushExecution(execution);

    AgentChatListener listener = new AgentChatListener();
    assertThat(listener.variableName()).isNull();

    // persistEvents must short-circuit on null variableName: no setVariable calls.
    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("hi"))).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));
    verify(execution, org.mockito.Mockito.never())
        .setVariable(org.mockito.Matchers.anyString(), org.mockito.Matchers.any());
    verify(execution, org.mockito.Mockito.never())
        .setVariable(org.mockito.Matchers.anyString(), org.mockito.Matchers.anyBoolean());
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private void pushExecution(ExecutionEntity execution) {
    Context.setExecutionContext(execution);
    pushedExecution = true;
  }
}
