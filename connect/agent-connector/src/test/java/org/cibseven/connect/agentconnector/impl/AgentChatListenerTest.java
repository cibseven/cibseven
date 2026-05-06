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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

public class AgentChatListenerTest {

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
  public void shouldDisablePersistenceWhenNoVariableNameProvided() {
    assertThat(new AgentChatListener().variableName()).isNull();
    assertThat(new AgentChatListener(null).variableName()).isNull();
    assertThat(new AgentChatListener("").variableName()).isNull();
  }

  @Test
  public void shouldRetainProvidedVariableName() {
    AgentChatListener listener = new AgentChatListener("agentChatLog");

    assertThat(listener.variableName()).isEqualTo("agentChatLog");
    assertThat(listener.events()).isEmpty();
  }

  @Test
  public void shouldNotFailWhenInvokedOutsideEngineContextWithVariableName() {
    AgentChatListener listener = new AgentChatListener("agentChatLog");

    ChatRequest request = ChatRequest.builder()
        .messages(Collections.singletonList(UserMessage.from("Hi"))).build();
    listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));

    // Persistence is a no-op without a BpmnExecutionContext, but the in-memory event
    // list is still populated so callers/tests can inspect what would have been written.
    assertThat(listener.events()).hasSize(1);
    assertThat(listener.events().get(0)).containsEntry("type", "request");
  }
}
