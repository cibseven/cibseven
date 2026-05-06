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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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

import org.cibseven.connect.ai.agent.AgentConnectorConstants;

/**
 * Captures every {@link ChatModelListener} event (request / response / error) into a
 * structured list and — when invoked inside a BPMN execution context — persists it as
 * a JSON-serialised process variable updated after every event. The JSON is consumed
 * by the webclient frontend (Vue.js) to render a timeline-style visualisation of the
 * agent run.
 *
 * <p>The variable name is derived automatically from the current activity:
 * {@code AGENT_CONNECTOR_LOG_PREFIX + activityId}. When a variable with that name
 * already exists at construction time, its content is decoded so subsequent events
 * accumulate on top instead of overwriting earlier history. When the listener is
 * invoked without a {@link BpmnExecutionContext} (e.g. unit tests), it just collects
 * events in memory and never touches the engine.
 */
class AgentChatListener implements ChatModelListener {

  private static final Logger LOG = LoggerFactory.getLogger(AgentChatListener.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final TypeReference<List<Map<String, Object>>> EVENT_LIST_TYPE =
      new TypeReference<List<Map<String, Object>>>() {};

  private final String variableName;
  private final List<Map<String, Object>> events = new ArrayList<>();
  private boolean flagWritten = false;

  AgentChatListener() {
    String activityId = currentActivityId();
    this.variableName = (activityId != null && !activityId.isEmpty())
        ? AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX + activityId
        : null;
    if (this.variableName != null) {
      loadExistingEvents();
    }
  }

  /** Package-private view of the captured events, for tests. */
  List<Map<String, Object>> events() {
    return events;
  }

  /** Derived variable name, or {@code null} when persistence is disabled. */
  String variableName() {
    return variableName;
  }

  private static String currentActivityId() {
    ExecutionEntity execution = currentExecution();
    return execution != null ? execution.getActivityId() : null;
  }

  private void loadExistingEvents() {
    ExecutionEntity execution = currentExecution();
    if (execution == null) {
      return;
    }
    Object existing = execution.getVariable(variableName);
    if (existing == null) {
      return;
    }
    String json = existing.toString();
    if (json.isEmpty()) {
      return;
    }
    try {
      List<Map<String, Object>> previous = MAPPER.readValue(json, EVENT_LIST_TYPE);
      events.addAll(previous);
    } catch (JsonProcessingException e) {
      LOG.warn("Failed to decode previous chat log from variable '{}'; starting with an empty log.",
          variableName, e);
    }
  }

  private void persistEvents() {
    if (variableName == null) {
      return;
    }
    String chatLog;
    try {
      chatLog = MAPPER.writeValueAsString(events);
    } catch (JsonProcessingException e) {
      LOG.error("Failed to serialise chatLog events to JSON; skipping update of '{}'.",
          variableName, e);
      return;
    }
    ExecutionEntity execution = currentExecution();
    if (execution == null) {
      LOG.warn("Cannot update '{}' chat log variable: no BpmnExecutionContext on the current "
          + "thread (connector invoked outside an engine command context).", variableName);
      return;
    }
    // Default Java serialization is always registered (unlike Spin/Jackson) and stores
    // in ACT_GE_BYTEARRAY, so it bypasses the VARCHAR(4000) limit.
    Object value = Variables.objectValue(chatLog).create();
    execution.setVariable(variableName, value);
    if (!flagWritten) {
      // Flag that indicates the execution of the agent connector in the process
      execution.setVariable(AgentConnectorConstants.AGENT_CONNECTOR_FLAG_VARIABLE_NAME, true);
      flagWritten = true;
    }
  }

  private static ExecutionEntity currentExecution() {
    BpmnExecutionContext ctx = Context.getBpmnExecutionContext();
    if (ctx == null) {
      return null;
    }
    return ctx.getExecution();
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
    persistEvents();
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
    persistEvents();
  }

  @Override
  public void onError(ChatModelErrorContext ctx) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("type", "error");
    event.put("timestamp", Instant.now().toString());
    event.put("message", ctx.error().getMessage());
    events.add(event);
    LOG.error("LLM ERROR: {}", ctx.error().getMessage(), ctx.error());
    persistEvents();
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
