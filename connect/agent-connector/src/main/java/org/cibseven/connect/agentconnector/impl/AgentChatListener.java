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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.output.TokenUsage;

import org.cibseven.bpm.engine.impl.context.BpmnExecutionContext;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.connect.agentconnector.AgentConnector;

/**
 * Captures every {@link ChatModelListener} event (request / response / error) into a
 * structured list and persists it as a JSON marker variable on the current process
 * execution via {@link #writeChatLogVariable()}. The JSON is consumed by the webclient
 * frontend (Vue.js) to render a timeline-style visualisation of the agent run.
 */
class AgentChatListener implements ChatModelListener {

  private static final Logger LOG = LoggerFactory.getLogger(AgentChatListener.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final List<Map<String, Object>> events = new ArrayList<>();

  /** Package-private view of the captured events, for tests. */
  List<Map<String, Object>> events() {
    return events;
  }

  /**
   * Writes the chat log as a marker variable local to the current activity's execution, so
   * that every instance interacting with the AI agent connector can be discovered (e.g. in
   * Cockpit) without requiring the BPMN to declare a matching {@code <camunda:outputParameter>}.
   * Scoping the variable locally keeps it attached to the connector's activity rather than
   * propagating it to the process instance.
   *
   * <p>Uses the engine's internal thread-local {@link Context}; when invoked outside an engine
   * command context (e.g. from a unit test), the marker variable is skipped but the serialised
   * chat log is still returned so callers can expose it as a connector output parameter.
   *
   * @return the JSON-serialised chat log, or an empty string when serialisation fails
   */
  public String writeChatLogVariable() {
    String chatLog;
    try {
      chatLog = MAPPER.writeValueAsString(events);
    } catch (JsonProcessingException e) {
      LOG.error("Failed to serialise chatLog events to JSON; skipping '{}' marker variable.",
          AgentConnector.VAR_CHAT_LOG, e);
      return "";
    }

    BpmnExecutionContext ctx = Context.getBpmnExecutionContext();
    if (ctx == null) {
      LOG.warn("Cannot write '{}' marker variable: no BpmnExecutionContext on the current thread "
          + "(connector invoked outside an engine command context).", AgentConnector.VAR_CHAT_LOG);
      return chatLog;
    }
    ExecutionEntity execution = ctx.getExecution();
    if (execution == null) {
      LOG.warn("Cannot write '{}' marker variable: BpmnExecutionContext has no ExecutionEntity.",
          AgentConnector.VAR_CHAT_LOG);
      return chatLog;
    }

    // Default Java serialization is always registered (unlike Spin/Jackson) and stores
    // in ACT_GE_BYTEARRAY, so it bypasses the VARCHAR(4000) limit.
    Object value = Variables.objectValue(chatLog).create();
    execution.setVariableLocal(AgentConnector.VAR_CHAT_LOG, value);
    return chatLog;
  }

  @Override
  public void onRequest(ChatModelRequestContext ctx) {
    ChatRequest req = ctx.chatRequest();
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("type", "request");
    event.put("timestamp", Instant.now().toString());

    List<Map<String, Object>> messages = new ArrayList<>();
    for (ChatMessage msg : req.messages()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("role", msg.type().toString());
      m.put("content", extractContent(msg));
      if (msg instanceof ToolExecutionResultMessage) {
        m.put("toolName", ((ToolExecutionResultMessage) msg).toolName());
      }
      messages.add(m);
      LOG.debug("[{}] {}", msg.type(), msg);
    }
    event.put("messages", messages);

    List<ToolSpecification> toolSpecs = req.parameters().toolSpecifications();
    if (toolSpecs != null && !toolSpecs.isEmpty()) {
      List<String> tools = toolSpecs.stream()
          .map(ToolSpecification::name)
          .collect(Collectors.toList());
      event.put("tools", tools);
      LOG.debug("Available tools: {}", tools);
    }
    events.add(event);
  }

  @Override
  public void onResponse(ChatModelResponseContext ctx) {
    AiMessage aiMsg = ctx.chatResponse().aiMessage();
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("type", "response");
    event.put("timestamp", Instant.now().toString());

    if (aiMsg.hasToolExecutionRequests()) {
      List<Map<String, Object>> toolCalls = new ArrayList<>();
      for (ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("name", req.name());
        call.put("arguments", req.arguments());
        toolCalls.add(call);
        LOG.debug("TOOL CALL: {}({})", req.name(), req.arguments());
      }
      event.put("toolCalls", toolCalls);
    } else {
      event.put("answer", aiMsg.text());
      LOG.debug("FINAL ANSWER: {}", aiMsg.text());
    }

    TokenUsage usage = ctx.chatResponse().tokenUsage();
    if (usage != null) {
      Map<String, Object> tokens = new LinkedHashMap<>();
      tokens.put("input", usage.inputTokenCount());
      tokens.put("output", usage.outputTokenCount());
      tokens.put("total", usage.totalTokenCount());
      event.put("tokens", tokens);
      LOG.debug("Tokens: {}", usage);
    }
    events.add(event);
  }

  @Override
  public void onError(ChatModelErrorContext ctx) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("type", "error");
    event.put("timestamp", Instant.now().toString());
    event.put("message", ctx.error().getMessage());
    events.add(event);
    LOG.error("LLM ERROR: {}", ctx.error().getMessage(), ctx.error());
  }

  private static String extractContent(ChatMessage msg) {
    if (msg instanceof UserMessage) {
      UserMessage user = (UserMessage) msg;
      return user.hasSingleText() ? user.singleText() : user.toString();
    }
    if (msg instanceof SystemMessage) {
      return ((SystemMessage) msg).text();
    }
    if (msg instanceof AiMessage) {
      String text = ((AiMessage) msg).text();
      String thinking = ((AiMessage) msg).thinking();

      Map<String, String> content = new LinkedHashMap<>();
      content.put("text", text);
      content.put("thinking", thinking);

      try {
        return MAPPER.writeValueAsString(content);
      } catch (JsonProcessingException e) {
        LOG.error("Failed to serialise AiMessage content (text + thinking) to JSON; "
            + "falling back to plain answer text.", e);
        return text;
      }

    }
    if (msg instanceof ToolExecutionResultMessage) {
      return ((ToolExecutionResultMessage) msg).text();
    }
    return msg.toString();
  }
}
