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
import org.cibseven.bpm.engine.impl.identity.Authentication;
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
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.output.FinishReason;
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
    // Clear thread-locals that may have been set by tests exercising auth /
    // tool-side-effect linkage so they don't leak into subsequent tests.
    ProcessStarterToolContext.clear();
    System.clearProperty(AgentChatListener.REDACT_CONTENT_PROPERTY);
    System.clearProperty(AgentChatListener.CHAT_LOG_VARIABLE_PROPERTY);
    // Restore the env-var lookup seam in case a test replaced it.
    AgentChatListener.ENV_READER = System::getenv;
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

  // ── chatLogVariable.enabled=false opt-out (DB-bloat control, CIB7-1395) ──

  @Test
  public void shouldSkipChatLogVariableWriteWhenChatLogVariableDisabled() {
    System.setProperty(AgentChatListener.CHAT_LOG_VARIABLE_PROPERTY, "false");
    String varName = AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX + "agentTask";
    ExecutionEntity execution = mock(ExecutionEntity.class);
    when(execution.getActivityId()).thenReturn("agentTask");
    pushExecution(execution);

    AgentChatListener listener = new AgentChatListener();

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("hi"))).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));
    listener.onResponse(new ChatModelResponseContext(
        ChatResponse.builder().aiMessage(AiMessage.from("ok")).build(),
        request, null, new HashMap<>()));

    // Chat-log variable must NOT be written for any of the events.
    verify(execution, org.mockito.Mockito.never())
        .setVariable(org.mockito.Matchers.eq(varName), org.mockito.Matchers.any());
    // The connector flag variable is suppressed too — it points at the chat-log
    // timeline and would be misleading without it.
    verify(execution, org.mockito.Mockito.never())
        .setVariable(AgentConnectorConstants.AGENT_CONNECTOR_FLAG_VARIABLE_NAME, true);
    // The in-memory event timeline must still be built so an external sink
    // (Kafka/JMS/SAP) can consume it.
    assertThat(listener.events()).hasSize(2);
  }

  @Test
  public void shouldSkipLoadingExistingChatLogWhenDisabled() {
    System.setProperty(AgentChatListener.CHAT_LOG_VARIABLE_PROPERTY, "false");
    String varName = AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX + "resumable";
    String priorJson = "[{\"type\":\"request\",\"timestamp\":\"2026-05-15T10:00:00Z\"}]";
    ExecutionEntity execution = mock(ExecutionEntity.class);
    when(execution.getActivityId()).thenReturn("resumable");
    when(execution.getVariable(varName)).thenReturn(priorJson);
    pushExecution(execution);

    AgentChatListener listener = new AgentChatListener();

    // The opt-out short-circuits loadExistingEvents() — prior events are not
    // restored from the engine variable.
    assertThat(listener.events()).isEmpty();
    verify(execution, org.mockito.Mockito.never()).getVariable(varName);
  }

  @Test
  public void shouldPersistChatLogVariableWhenPropertyExplicitlyTrue() {
    // Explicit "true" must behave like the default — verifies the parser.
    System.setProperty(AgentChatListener.CHAT_LOG_VARIABLE_PROPERTY, "true");
    String varName = AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX + "agentTask";
    ExecutionEntity execution = mock(ExecutionEntity.class);
    when(execution.getActivityId()).thenReturn("agentTask");
    when(execution.getVariable(varName)).thenReturn(null);
    pushExecution(execution);

    AgentChatListener listener = new AgentChatListener();

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("hi"))).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));

    verify(execution).setVariable(org.mockito.Matchers.eq(varName), org.mockito.Matchers.any());
    verify(execution).setVariable(
        AgentConnectorConstants.AGENT_CONNECTOR_FLAG_VARIABLE_NAME, true);
  }

  @Test
  public void isChatLogVariableEnabledShouldDefaultToTrueWhenPropertyUnset() {
    System.clearProperty(AgentChatListener.CHAT_LOG_VARIABLE_PROPERTY);
    assertThat(AgentChatListener.isChatLogVariableEnabled()).isTrue();
  }

  @Test
  public void isChatLogVariableEnabledShouldFallBackToEnvVarWhenPropertyUnset() {
    System.clearProperty(AgentChatListener.CHAT_LOG_VARIABLE_PROPERTY);
    AgentChatListener.ENV_READER = name ->
        AgentChatListener.CHAT_LOG_VARIABLE_ENV_VAR.equals(name) ? "false" : null;
    assertThat(AgentChatListener.isChatLogVariableEnabled()).isFalse();
  }

  @Test
  public void isChatLogVariableEnabledShouldPreferSystemPropertyOverEnvVar() {
    // System property says true, env var says false → system property wins.
    System.setProperty(AgentChatListener.CHAT_LOG_VARIABLE_PROPERTY, "true");
    AgentChatListener.ENV_READER = name ->
        AgentChatListener.CHAT_LOG_VARIABLE_ENV_VAR.equals(name) ? "false" : null;
    assertThat(AgentChatListener.isChatLogVariableEnabled()).isTrue();
  }

  @Test
  public void perActivityFalseShouldOverrideGlobalTrue() {
    // No system property, no env var → global resolves to true. Per-activity
    // false must still suppress the chat-log variable write.
    String varName = AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX + "agentTask";
    ExecutionEntity execution = mock(ExecutionEntity.class);
    when(execution.getActivityId()).thenReturn("agentTask");
    pushExecution(execution);

    AgentChatListener listener = new AgentChatListener(null, null, Boolean.FALSE);

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("hi"))).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));

    verify(execution, org.mockito.Mockito.never())
        .setVariable(org.mockito.Matchers.eq(varName), org.mockito.Matchers.any());
    verify(execution, org.mockito.Mockito.never())
        .setVariable(AgentConnectorConstants.AGENT_CONNECTOR_FLAG_VARIABLE_NAME, true);
    assertThat(listener.events()).hasSize(1);
  }

  @Test
  public void perActivityTrueShouldOverrideGlobalFalse() {
    // Global flag = false; per-activity true must restore the chat-log write.
    System.setProperty(AgentChatListener.CHAT_LOG_VARIABLE_PROPERTY, "false");
    String varName = AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX + "agentTask";
    ExecutionEntity execution = mock(ExecutionEntity.class);
    when(execution.getActivityId()).thenReturn("agentTask");
    when(execution.getVariable(varName)).thenReturn(null);
    pushExecution(execution);

    AgentChatListener listener = new AgentChatListener(null, null, Boolean.TRUE);

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("hi"))).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));

    verify(execution).setVariable(org.mockito.Matchers.eq(varName), org.mockito.Matchers.any());
  }

  @Test
  public void perActivityNullShouldFallThroughToGlobal() {
    // Per-activity null + global=false → variable skipped, identical to the
    // global-only opt-out test above.
    System.setProperty(AgentChatListener.CHAT_LOG_VARIABLE_PROPERTY, "false");
    String varName = AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX + "agentTask";
    ExecutionEntity execution = mock(ExecutionEntity.class);
    when(execution.getActivityId()).thenReturn("agentTask");
    pushExecution(execution);

    AgentChatListener listener = new AgentChatListener(null, null, null);

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("hi"))).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));

    verify(execution, org.mockito.Mockito.never())
        .setVariable(org.mockito.Matchers.eq(varName), org.mockito.Matchers.any());
  }

  // ── redactContent env-var fallback (normalised in the same pass) ────────

  @Test
  public void isRedactContentEnabledShouldDefaultToFalse() {
    System.clearProperty(AgentChatListener.REDACT_CONTENT_PROPERTY);
    assertThat(AgentChatListener.isRedactContentEnabled()).isFalse();
  }

  @Test
  public void isRedactContentEnabledShouldFallBackToEnvVar() {
    System.clearProperty(AgentChatListener.REDACT_CONTENT_PROPERTY);
    AgentChatListener.ENV_READER = name ->
        AgentChatListener.REDACT_CONTENT_ENV_VAR.equals(name) ? "true" : null;
    assertThat(AgentChatListener.isRedactContentEnabled()).isTrue();
  }

  @Test
  public void isRedactContentEnabledShouldPreferSystemPropertyOverEnvVar() {
    System.setProperty(AgentChatListener.REDACT_CONTENT_PROPERTY, "false");
    AgentChatListener.ENV_READER = name ->
        AgentChatListener.REDACT_CONTENT_ENV_VAR.equals(name) ? "true" : null;
    assertThat(AgentChatListener.isRedactContentEnabled()).isFalse();
  }

  @Test
  public void isChatLogVariableEnabledShouldReturnFalseWhenPropertyFalse() {
    System.setProperty(AgentChatListener.CHAT_LOG_VARIABLE_PROPERTY, "false");
    assertThat(AgentChatListener.isChatLogVariableEnabled()).isFalse();
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

  // ── Audit envelope (EU AI Act Art. 12 record-keeping) ────────────────────

  @Test
  public void shouldStampRunIdAndMonotonicEventSeqOnEveryEvent() {
    AgentChatListener listener = new AgentChatListener();

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("hi"))).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));
    listener.onResponse(new ChatModelResponseContext(
        ChatResponse.builder().aiMessage(AiMessage.from("ok")).build(),
        request, null, new HashMap<>()));
    listener.onError(new ChatModelErrorContext(
        new RuntimeException("boom"), request, null, new HashMap<>()));

    List<Map<String, Object>> events = listener.events();
    String runId = (String) events.get(0).get("runId");
    assertThat(runId).isNotNull().isNotEmpty();
    assertThat(events.get(1)).containsEntry("runId", runId);
    assertThat(events.get(2)).containsEntry("runId", runId);
    assertThat(events.get(0)).containsEntry("eventSeq", 0);
    assertThat(events.get(1)).containsEntry("eventSeq", 1);
    assertThat(events.get(2)).containsEntry("eventSeq", 2);
  }

  @Test
  public void shouldRecordProviderAndConfiguredModelAndEndpoint() {
    AgentChatListener listener = new AgentChatListener("gpt-4o-mini", "https://api.openai.com/v1");

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("hi"))).build();
    listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OPEN_AI, new HashMap<>()));

    Map<String, Object> event = listener.events().get(0);
    assertThat(event).containsEntry("provider", "OPEN_AI")
        .containsEntry("model", "gpt-4o-mini")
        .containsEntry("endpoint", "https://api.openai.com/v1");
  }

  @Test
  public void shouldOverrideConfiguredModelWithProviderReportedSnapshot() {
    AgentChatListener listener = new AgentChatListener("gpt-4o", null);

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("hi"))).build();
    ChatResponseMetadata meta = ChatResponseMetadata.builder()
        .modelName("gpt-4o-2024-08-06")
        .build();
    ChatResponse response = ChatResponse.builder()
        .aiMessage(AiMessage.from("ok"))
        .metadata(meta)
        .build();
    listener.onResponse(new ChatModelResponseContext(response, request, ModelProvider.OPEN_AI, new HashMap<>()));

    Map<String, Object> event = listener.events().get(0);
    assertThat(event).containsEntry("model", "gpt-4o-2024-08-06");
  }

  @Test
  public void shouldRecordFinishReasonOnResponse() {
    AgentChatListener listener = new AgentChatListener();

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("hi"))).build();
    ChatResponseMetadata meta = ChatResponseMetadata.builder()
        .finishReason(FinishReason.CONTENT_FILTER)
        .build();
    ChatResponse response = ChatResponse.builder()
        .aiMessage(AiMessage.from("…"))
        .metadata(meta)
        .build();
    listener.onResponse(new ChatModelResponseContext(response, request, null, new HashMap<>()));

    assertThat(listener.events().get(0)).containsEntry("finishReason", "CONTENT_FILTER");
  }

  @Test
  public void shouldComputeDurationMsBetweenRequestAndResponse() throws Exception {
    AgentChatListener listener = new AgentChatListener();
    Map<Object, Object> attributes = new HashMap<>();

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("hi"))).build();
    listener.onRequest(new ChatModelRequestContext(request, null, attributes));
    Thread.sleep(5);
    listener.onResponse(new ChatModelResponseContext(
        ChatResponse.builder().aiMessage(AiMessage.from("ok")).build(),
        request, null, attributes));

    Object duration = listener.events().get(1).get("durationMs");
    assertThat(duration).isInstanceOf(Long.class);
    assertThat((Long) duration).isGreaterThanOrEqualTo(0L);
  }

  @Test
  public void shouldRecordErrorClassAndShortStackOnFailure() {
    AgentChatListener listener = new AgentChatListener();

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("hi"))).build();
    listener.onError(new ChatModelErrorContext(
        new IllegalStateException("nope"), request, null, new HashMap<>()));

    Map<String, Object> event = listener.events().get(0);
    assertThat(event).containsEntry("errorClass", "java.lang.IllegalStateException")
        .containsEntry("message", "nope")
        .containsKey("stack");
    String stack = (String) event.get("stack");
    assertThat(stack).contains("AgentChatListenerTest");
  }

  // ── BPMN correlation + caller identity (Art. 12(3), Art. 26(3)) ─────────

  @Test
  public void shouldEmbedFullProcessCorrelationInEveryEvent() {
    ExecutionEntity execution = mock(ExecutionEntity.class);
    when(execution.getActivityId()).thenReturn("agentTask");
    when(execution.getId()).thenReturn("exec-1");
    when(execution.getProcessInstanceId()).thenReturn("pi-1");
    when(execution.getProcessDefinitionId()).thenReturn("pd-1:v1:42");
    when(execution.getProcessDefinitionKey()).thenReturn("invoiceProcess");
    when(execution.getBusinessKey()).thenReturn("BK-9000");
    when(execution.getTenantId()).thenReturn("tenant-a");
    when(execution.getVariable(AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX + "agentTask"))
        .thenReturn(null);
    pushExecution(execution);

    AgentChatListener listener = new AgentChatListener();

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("hi"))).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));

    Map<String, Object> event = listener.events().get(0);
    assertThat(event)
        .containsEntry("processInstanceId", "pi-1")
        .containsEntry("processDefinitionId", "pd-1:v1:42")
        .containsEntry("processDefinitionKey", "invoiceProcess")
        .containsEntry("businessKey", "BK-9000")
        .containsEntry("tenantId", "tenant-a")
        .containsEntry("executionId", "exec-1")
        .containsEntry("activityId", "agentTask");
  }

  @Test
  public void shouldEmbedCallerUserIdAndGroupIdsFromForwardedAuthentication() {
    ProcessStarterToolContext.setAuthentication(new Authentication(
        "alice", Arrays.asList("finance", "approvers")));

    AgentChatListener listener = new AgentChatListener();

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("hi"))).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));

    Map<String, Object> event = listener.events().get(0);
    assertThat(event).containsEntry("userId", "alice");
    assertThat(event.get("groupIds")).isEqualTo(Arrays.asList("finance", "approvers"));
  }

  // ── Tool-side-effect linkage (Art. 14) ───────────────────────────────────

  @Test
  public void shouldMergePendingToolSideEffectsOntoNextEvent() {
    AgentChatListener listener = new AgentChatListener();

    Map<String, Object> sideEffect = new HashMap<>();
    sideEffect.put("tool", "runProcessByKey");
    sideEffect.put("processInstanceId", "pi-99");
    sideEffect.put("executedAs", "alice");
    listener.recordToolSideEffect(sideEffect);

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(ToolExecutionResultMessage.from(
            "call-1", "runProcessByKey", "{\"processInstanceId\":\"pi-99\"}")))
        .build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> sideEffects =
        (List<Map<String, Object>>) listener.events().get(0).get("toolSideEffects");
    assertThat(sideEffects).hasSize(1);
    assertThat(sideEffects.get(0))
        .containsEntry("processInstanceId", "pi-99")
        .containsEntry("executedAs", "alice");
  }

  @Test
  public void shouldDrainToolSideEffectsAfterAttachingThem() {
    AgentChatListener listener = new AgentChatListener();

    Map<String, Object> sideEffect = new HashMap<>();
    sideEffect.put("processInstanceId", "pi-1");
    listener.recordToolSideEffect(sideEffect);

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("first"))).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));

    // Side-effect was consumed by the first event only.
    assertThat(listener.events().get(0)).containsKey("toolSideEffects");
    assertThat(listener.events().get(1)).doesNotContainKey("toolSideEffects");
  }

  // ── Content redaction (Art. 10 / GDPR) ───────────────────────────────────

  @Test
  public void shouldReplaceContentWithRedactionMarkerWhenRedactionEnabled() throws Exception {
    System.setProperty(AgentChatListener.REDACT_CONTENT_PROPERTY, "true");
    AgentChatListener listener = new AgentChatListener();

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("sensitive PII"))).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> messages =
        (List<Map<String, Object>>) listener.events().get(0).get("messages");
    String content = (String) messages.get(0).get("content");
    @SuppressWarnings("unchecked")
    Map<String, Object> decoded = new com.fasterxml.jackson.databind.ObjectMapper()
        .readValue(content, Map.class);
    assertThat(decoded).containsEntry("redacted", true)
        .containsEntry("length", "sensitive PII".length());
    String hash = (String) decoded.get("hash");
    assertThat(hash).startsWith("sha256:");
    // SHA-256 = 32 bytes → 64 hex chars; with the "sha256:" prefix, 71 chars total.
    assertThat(hash.length()).isEqualTo(71);
  }

  @Test
  public void shouldNotRedactByDefault() {
    AgentChatListener listener = new AgentChatListener();

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("plaintext"))).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> messages =
        (List<Map<String, Object>>) listener.events().get(0).get("messages");
    assertThat(messages.get(0)).containsEntry("content", "plaintext");
  }

  // ── Schema versioning (Gap 7) ────────────────────────────────────────────

  @Test
  public void shouldStampSchemaVersionAsFirstFieldOnEveryEvent() {
    AgentChatListener listener = new AgentChatListener();

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("hi"))).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));
    listener.onResponse(new ChatModelResponseContext(
        ChatResponse.builder().aiMessage(AiMessage.from("ok")).build(),
        request, null, new HashMap<>()));

    for (Map<String, Object> event : listener.events()) {
      assertThat(event).containsEntry("schemaVersion", AgentChatListener.SCHEMA_VERSION);
      // First key must be schemaVersion so downstream consumers can sniff it without parsing the whole payload.
      assertThat(event.keySet().iterator().next()).isEqualTo("schemaVersion");
    }
  }

  // ── Model invocation parameters (Gap 4 — Art. 15 reproducibility) ────────

  @Test
  public void shouldRecordOpenAiModelParamsOnRequestEvent() {
    AgentChatListener listener = new AgentChatListener();

    OpenAiChatRequestParameters params = OpenAiChatRequestParameters.builder()
        .temperature(0.3)
        .topP(0.9)
        .maxOutputTokens(512)
        .seed(42)
        .reasoningEffort("medium")
        .build();
    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("hi")))
        .parameters(params)
        .build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));

    @SuppressWarnings("unchecked")
    Map<String, Object> modelParams = (Map<String, Object>) listener.events().get(0).get("modelParams");
    assertThat(modelParams)
        .containsEntry("temperature", 0.3)
        .containsEntry("topP", 0.9)
        .containsEntry("maxTokens", 512)
        .containsEntry("seed", 42)
        .containsEntry("reasoningEffort", "medium");
  }

  @Test
  public void shouldOmitModelParamsBlockWhenAllValuesAreNull() {
    AgentChatListener listener = new AgentChatListener();

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("hi"))).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));

    // No params set on the builder → modelParams block is omitted entirely.
    assertThat(listener.events().get(0)).doesNotContainKey("modelParams");
  }

  @Test
  public void shouldOmitModelParamsBlockOnResponseEvent() {
    AgentChatListener listener = new AgentChatListener();

    OpenAiChatRequestParameters params = OpenAiChatRequestParameters.builder()
        .temperature(0.5)
        .build();
    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("hi")))
        .parameters(params)
        .build();
    listener.onResponse(new ChatModelResponseContext(
        ChatResponse.builder().aiMessage(AiMessage.from("ok")).build(),
        request, null, new HashMap<>()));

    // modelParams belongs on the request side only — response events don't repeat it.
    assertThat(listener.events().get(0)).doesNotContainKey("modelParams");
  }

  // ── Auth-capture hardening (Art. 12(3)) ──────────────────────────────────

  @Test
  public void shouldStampUserIdSourceContextWhenAuthenticationResolved() {
    ProcessStarterToolContext.setAuthentication(new Authentication("alice", Collections.emptyList()));
    AgentChatListener listener = new AgentChatListener();

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("hi"))).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));

    Map<String, Object> event = listener.events().get(0);
    assertThat(event)
        .containsEntry("userId", "alice")
        .containsEntry("userIdSource", "context");
  }

  @Test
  public void shouldStampUserIdSourceMissingWhenExecutionContextHasNoAuthentication() {
    ExecutionEntity execution = mock(ExecutionEntity.class);
    when(execution.getActivityId()).thenReturn("agentTask");
    when(execution.getId()).thenReturn("exec-1");
    when(execution.getVariable(AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX + "agentTask"))
        .thenReturn(null);
    pushExecution(execution);

    AgentChatListener listener = new AgentChatListener();
    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("hi"))).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));

    Map<String, Object> event = listener.events().get(0);
    assertThat(event)
        .containsKey("userId")
        .containsEntry("userIdSource", "missing");
    assertThat(event.get("userId")).isNull();
  }

  @Test
  public void shouldOmitUserIdFieldsEntirelyWhenNoExecutionContextAndNoAuthentication() {
    AgentChatListener listener = new AgentChatListener();

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("hi"))).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));

    Map<String, Object> event = listener.events().get(0);
    // No execution context → not part of an audited BPMN run, so the userId
    // field stays out entirely (no implicit "missing" claim).
    assertThat(event)
        .doesNotContainKey("userId")
        .doesNotContainKey("userIdSource");
  }

  // ── Last-response identity (Art. 50(2) AI-output marker source) ──────────

  @Test
  public void shouldExposeLastResponseIdentityForAiMetaMarker() {
    AgentChatListener listener = new AgentChatListener("gpt-4o", null);

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("hi"))).build();
    ChatResponseMetadata meta = ChatResponseMetadata.builder()
        .modelName("gpt-4o-2024-08-06")
        .id("resp-abc")
        .build();
    listener.onResponse(new ChatModelResponseContext(
        ChatResponse.builder().aiMessage(AiMessage.from("answer")).metadata(meta).build(),
        request, ModelProvider.OPEN_AI, new HashMap<>()));

    Map<String, String> identity = listener.lastResponseIdentity();
    assertThat(identity)
        .containsEntry("provider", "OPEN_AI")
        .containsEntry("model", "gpt-4o-2024-08-06")
        .containsEntry("responseId", "resp-abc");
  }

  @Test
  public void shouldReturnEmptyIdentityWhenNoResponseObserved() {
    AgentChatListener listener = new AgentChatListener();
    assertThat(listener.lastResponseIdentity()).isEmpty();
  }

  // ── RAG retrieval audit (Art. 10 / 12 / 26) ──────────────────────────────

  @Test
  public void shouldEmitRetrievalEventWithEnvelopeAndPayload() {
    AgentChatListener listener = new AgentChatListener();

    Map<String, Object> payload = new HashMap<>();
    payload.put("query", "what is BPMN");
    payload.put("resultCount", 2);

    listener.recordRetrievalEvent(payload);

    assertThat(listener.events()).hasSize(1);
    Map<String, Object> event = listener.events().get(0);
    assertThat(event)
        .containsEntry("type", "retrieval")
        .containsEntry("schemaVersion", AgentChatListener.SCHEMA_VERSION)
        .containsEntry("eventSeq", 0)
        .containsEntry("query", "what is BPMN")
        .containsEntry("resultCount", 2);
    assertThat(event).containsKey("runId").containsKey("timestamp");
  }

  @Test
  public void retrievalEventShouldOmitChatModelIdentityFields() {
    AgentChatListener listener = new AgentChatListener("gpt-5.4-nano", "https://example/v1");

    Map<String, Object> payload = new HashMap<>();
    payload.put("query", "hi");
    listener.recordRetrievalEvent(payload);

    Map<String, Object> event = listener.events().get(0);
    // Retrieval has its own embeddingModel / store sub-blocks — the chat-model
    // identity fields must not leak onto it.
    assertThat(event).doesNotContainKey("provider")
        .doesNotContainKey("model")
        .doesNotContainKey("endpoint");
  }

  @Test
  public void shouldIgnoreNullRetrievalPayload() {
    AgentChatListener listener = new AgentChatListener();
    listener.recordRetrievalEvent(null);
    assertThat(listener.events()).isEmpty();
  }

  @Test
  public void shouldDrainPendingToolSideEffectsOntoRetrievalEvent() {
    AgentChatListener listener = new AgentChatListener();

    Map<String, Object> sideEffect = new HashMap<>();
    sideEffect.put("tool", "startProcess");
    sideEffect.put("processInstanceId", "pi-7");
    listener.recordToolSideEffect(sideEffect);

    Map<String, Object> payload = new HashMap<>();
    payload.put("query", "anything");
    listener.recordRetrievalEvent(payload);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> sideEffects =
        (List<Map<String, Object>>) listener.events().get(0).get("toolSideEffects");
    assertThat(sideEffects).hasSize(1);
    assertThat(sideEffects.get(0)).containsEntry("processInstanceId", "pi-7");
  }

  @Test
  public void retrievalEventSeqShouldShareCounterWithChatEvents() {
    AgentChatListener listener = new AgentChatListener();

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("first"))).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));

    Map<String, Object> payload = new HashMap<>();
    payload.put("query", "rag query");
    listener.recordRetrievalEvent(payload);

    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));

    assertThat(listener.events().get(0)).containsEntry("eventSeq", 0);
    assertThat(listener.events().get(1)).containsEntry("eventSeq", 1)
        .containsEntry("type", "retrieval");
    assertThat(listener.events().get(2)).containsEntry("eventSeq", 2);
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private void pushExecution(ExecutionEntity execution) {
    Context.setExecutionContext(execution);
    pushedExecution = true;
  }
}
