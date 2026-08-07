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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;

import org.cibseven.bpm.engine.impl.context.BpmnExecutionContext;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.variable.Variables;

import org.cibseven.connect.ai.agent.AgentConnectorConstants;

/**
 * {@link ChatMemoryStore} that keeps the conversation in a process variable
 * ({@code AGENT_CONNECTOR_MEMORY_PREFIX + memoryId}) instead of the JVM heap, so
 * memory survives an engine restart and works across engine replicas. Replaces
 * the previous {@code InMemoryChatMemoryStore} default, which was a JVM-local
 * map and therefore lost the conversation whenever the async continuation was
 * executed by a different node.
 *
 * <p>The variable is written with {@link ExecutionEntity#setVariable}, i.e. at
 * the process-instance scope, so a {@code memoryId} is scoped to one process
 * instance: reusing the same id in another instance starts a fresh conversation.
 *
 * <p>Stateless and safe to share as a singleton across job-executor threads —
 * the execution is resolved per call from the engine's thread-local
 * {@link Context}.
 *
 * <p>Whether this store is used at all is decided by {@link AgentChatMemoryStore},
 * not here. The only case this class handles itself is the absence of an engine
 * context on the calling thread (unit tests, embedded use outside the engine):
 * writing a process variable is then technically impossible, so operations fall
 * back to an internal JVM-local buffer rather than failing the invocation.
 */
final class ProcessVariableChatMemoryStore implements ChatMemoryStore {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessVariableChatMemoryStore.class);

  /**
   * Engine limit for the {@code NAME_} column of {@code ACT_RU_VARIABLE} —
   * {@code ACT_HI_VARINST} and {@code ACT_HI_DETAIL} share it.
   */
  private static final int MAX_VARIABLE_NAME_LENGTH = 255;

  /**
   * Guards the one-time WARN for the missing-engine-context case. That case
   * leaves chat memory JVM-local, i.e. back to the behaviour this store exists
   * to replace, so the deviation must be visible in the log. Logged once rather
   * than per call: the store is hit several times per message, so per-call
   * logging would flood.
   */
  private static final AtomicBoolean NO_ENGINE_CONTEXT_LOGGED = new AtomicBoolean(false);

  /**
   * Last-resort buffer for invocations without an engine context. Deliberately
   * not injectable: it is an implementation detail of the degradation path, not
   * a configurable collaborator — the choice of store is made one level up.
   */
  private final ChatMemoryStore noContextBuffer = new InMemoryChatMemoryStore();

  @Override
  public List<ChatMessage> getMessages(Object memoryId) {
    ExecutionEntity execution = targetExecution();
    if (execution == null) {
      return noContextBuffer.getMessages(memoryId);
    }
    Object raw = execution.getVariable(variableName(memoryId));
    if (raw == null || raw.toString().isEmpty()) {
      return new ArrayList<>();
    }
    try {
      List<ChatMessage> messages = ChatMessageDeserializer.messagesFromJson(raw.toString());
      return (messages == null) ? new ArrayList<>() : new ArrayList<>(messages);
    } catch (RuntimeException e) {
      // A LangChain4j upgrade can change the ChatMessage JSON shape, so an
      // in-flight instance may carry history this version cannot read. A
      // forgetful agent beats a stuck process instance.
      LOG.warn("Could not decode chat memory from variable '{}'; continuing with an empty "
          + "conversation.", variableName(memoryId), e);
      return new ArrayList<>();
    }
  }

  @Override
  public void updateMessages(Object memoryId, List<ChatMessage> messages) {
    ExecutionEntity execution = targetExecution();
    if (execution == null) {
      noContextBuffer.updateMessages(memoryId, messages);
      return;
    }
    String json = ChatMessageSerializer.messagesToJson(messages);
    // Java serialization stores in ACT_GE_BYTEARRAY and so bypasses the
    // VARCHAR(4000) limit — as the chat-log variable does.
    execution.setVariable(variableName(memoryId), Variables.objectValue(json).create());
  }

  @Override
  public void deleteMessages(Object memoryId) {
    ExecutionEntity execution = targetExecution();
    if (execution == null) {
      noContextBuffer.deleteMessages(memoryId);
      return;
    }
    execution.removeVariable(variableName(memoryId));
  }

  /**
   * The execution to store the memory on, or {@code null} when there is no engine
   * context on this thread and the caller must use {@link #noContextBuffer}.
   * Logged once per JVM, because it silently restores the JVM-local behaviour
   * that made conversations unusable across replicas.
   */
  private static ExecutionEntity targetExecution() {
    BpmnExecutionContext ctx = Context.getBpmnExecutionContext();
    if (ctx == null) {
      if (NO_ENGINE_CONTEXT_LOGGED.compareAndSet(false, true)) {
        LOG.warn("AI Agent connector: no BpmnExecutionContext on the current thread, so chat "
            + "memory falls back to a JVM-local store and will neither survive a restart nor be "
            + "shared across engine replicas. Expected outside an engine command context (tests, "
            + "embedded use); unexpected for a BPMN service task. Logged once per JVM.");
      }
      return null;
    }
    return ctx.getExecution();
  }

  static String variableName(Object memoryId) {
    String name = AgentConnectorConstants.AGENT_CONNECTOR_MEMORY_PREFIX + String.valueOf(memoryId);
    if (name.length() > MAX_VARIABLE_NAME_LENGTH) {
      throw new AgentConnectorException(String.format(
          "memoryId is too long: the chat-memory variable name would be %d characters, but the "
          + "engine limits variable names to %d. Use a memoryId of at most %d characters.",
          name.length(), MAX_VARIABLE_NAME_LENGTH,
          MAX_VARIABLE_NAME_LENGTH - AgentConnectorConstants.AGENT_CONNECTOR_MEMORY_PREFIX.length()));
    }
    return name;
  }

}
