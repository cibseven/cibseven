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

import java.nio.charset.StandardCharsets;
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
 *
 * <p>Two properties of the process model can make this store fail where the
 * previous heap map would not, so both are checked on write: a conversation
 * larger than the database will accept (see {@link #DEFAULT_MAX_PAYLOAD_BYTES}),
 * and concurrent branches sharing one {@code memoryId} and therefore one variable
 * row (see {@code warnOnConcurrentBranch}).
 */
final class ProcessVariableChatMemoryStore implements ChatMemoryStore {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessVariableChatMemoryStore.class);

  /**
   * Engine limit for the {@code NAME_} column of {@code ACT_RU_VARIABLE} —
   * {@code ACT_HI_VARINST} and {@code ACT_HI_DETAIL} share it.
   */
  private static final int MAX_VARIABLE_NAME_LENGTH = 255;

  /**
   * Ceiling for the serialized conversation, in bytes. Unlike the variable name,
   * the payload has no engine-side limit — but the database has one, and it is
   * reached silently: {@code ACT_GE_BYTEARRAY.BYTES_} is an unsized {@code BLOB}
   * on DB2 (1 MB inline by default) and bounded by {@code max_allowed_packet} on
   * MySQL / MariaDB. Without this guard an oversized window surfaces as a raw
   * {@code SQLException} at transaction flush: failed job, then an incident, with
   * nothing in the stack trace naming the conversation that produced it.
   *
   * <p>Default 1 MiB — the tightest of the supported databases, and roughly an
   * order of magnitude above a typical 20-message window. Raise it via
   * {@value #MAX_PAYLOAD_BYTES_PROPERTY} on a deployment whose database allows
   * more; set it to {@code 0} or below to disable the check.
   */
  private static final int DEFAULT_MAX_PAYLOAD_BYTES = 1024 * 1024;

  /** Overrides {@link #DEFAULT_MAX_PAYLOAD_BYTES}. */
  static final String MAX_PAYLOAD_BYTES_PROPERTY =
      "cibseven.connect.ai-agent.chatMemoryMaxPayloadBytes";

  /** Environment-variable fallback for {@value #MAX_PAYLOAD_BYTES_PROPERTY}. */
  static final String MAX_PAYLOAD_BYTES_ENV_VAR =
      "CIBSEVEN_CONNECT_AI_AGENT_CHAT_MEMORY_MAX_PAYLOAD_BYTES";

  /**
   * Fraction of the ceiling at which the size WARN fires, so a deployment growing
   * towards the limit is visible before a job actually fails.
   */
  private static final double PAYLOAD_WARN_RATIO = 0.8;

  /**
   * Headroom subtracted from the configured ceiling before comparing it against
   * the measured JSON size.
   *
   * <p>The two are not the same quantity: what reaches the database is the JSON
   * put through the engine's Java object serializer, which adds stream framing on
   * top of the characters, and the database in turn counts the whole statement
   * against limits like {@code max_allowed_packet}. Measuring the JSON is cheap
   * and happens anyway; measuring the persisted form would mean serializing the
   * payload once for the check and again for the engine.
   *
   * <p>This guard exists to turn an opaque {@code SQLException} at flush into a
   * legible error, not to predict the database limit exactly, so a fixed margin
   * is the proportionate trade-off. 4 KiB covers the serializer framing with room
   * to spare.
   */
  private static final int PAYLOAD_OVERHEAD_MARGIN_BYTES = 4096;

  /**
   * Guards the one-time WARNs. Each of these conditions is a property of the
   * deployment or the process model, not of the individual call, so one entry per
   * JVM is enough — and per-call logging would flood, because the store is hit
   * several times per message.
   */
  private static final AtomicBoolean NO_ENGINE_CONTEXT_LOGGED = new AtomicBoolean(false);
  private static final AtomicBoolean PAYLOAD_SIZE_LOGGED = new AtomicBoolean(false);
  private static final AtomicBoolean CONCURRENT_BRANCH_LOGGED = new AtomicBoolean(false);
  private static final AtomicBoolean UNPARSEABLE_LIMIT_LOGGED = new AtomicBoolean(false);

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
    String name = variableName(memoryId);
    Object raw = execution.getVariable(name);
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
          + "conversation.", name, e);
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
    String name = variableName(memoryId);
    checkPayloadSize(name, json, messages.size());
    warnOnConcurrentBranch(execution, name);
    // Java serialization stores in ACT_GE_BYTEARRAY and so bypasses the
    // VARCHAR(4000) limit — as the chat-log variable does.
    execution.setVariable(name, Variables.objectValue(json).create());
  }

  @Override
  public void deleteMessages(Object memoryId) {
    ExecutionEntity execution = targetExecution();
    if (execution == null) {
      // Without an engine context the process variable is unreachable, so this
      // can only clear the internal buffer. Returning quietly would tell the
      // caller the conversation is gone while it is still in the database —
      // see deletesPersistently() and AgentChatMemoryStore.clear(Object).
      noContextBuffer.deleteMessages(memoryId);
      return;
    }
    execution.removeVariable(variableName(memoryId));
  }

  /**
   * Whether a {@link #deleteMessages(Object)} on the current thread would remove
   * the persisted conversation, as opposed to only the in-memory fallback buffer.
   *
   * <p>Exists so {@link AgentChatMemoryStore#clear(Object)} can tell its callers
   * whether the delete actually took effect. A delete from an administration or
   * application thread — anywhere without a {@code BpmnExecutionContext} — cannot
   * reach the process variable, and silently reporting success there would be
   * worse than reporting failure.
   */
  boolean deletesPersistently() {
    return targetExecution() != null;
  }

  /**
   * Rejects a conversation that would exceed {@link #DEFAULT_MAX_PAYLOAD_BYTES}
   * (or its override) before it reaches the database, and warns once while the
   * payload is merely approaching the ceiling.
   *
   * <p>Failing here rather than at transaction flush is the whole point: the
   * message names the conversation, its size and the levers an operator actually
   * has, none of which is recoverable from the {@code SQLException} the database
   * would otherwise raise. It deliberately does not suggest LangChain4j's
   * {@code storeRetrievedContentInChatMemory}, which is a builder option in Java
   * and not something a deployment can turn; the RAG cause is stated as context
   * instead.
   */
  private static void checkPayloadSize(String variableName, String json, int messageCount) {
    int configuredLimit = maxPayloadBytes();
    if (configuredLimit <= 0) {
      return;
    }
    // The measured JSON is smaller than what the database ultimately stores, so
    // compare against the ceiling minus a margin. Floored at 1 so an absurdly
    // small configured limit still rejects rather than silently passing.
    int effectiveLimit = Math.max(1, configuredLimit - PAYLOAD_OVERHEAD_MARGIN_BYTES);
    int size = json.getBytes(StandardCharsets.UTF_8).length;
    if (size > effectiveLimit) {
      throw new AgentConnectorException(String.format(
          "Chat memory for variable '%s' is too large to store: %d messages serialize to %d bytes "
          + "of JSON, and the usable ceiling is %d bytes (the configured limit of %d minus a %d-byte "
          + "margin for serialization and database overhead). Writing it would fail at transaction "
          + "flush as a database error. Reduce 'chatMemoryMaxMessages', or raise the limit via the "
          + "system property '%s' if the database allows larger values. Note that with RAG enabled "
          + "each turn also persists the retrieved chunks, which is the usual reason a window grows "
          + "this large.",
          variableName, messageCount, size, effectiveLimit, configuredLimit,
          PAYLOAD_OVERHEAD_MARGIN_BYTES, MAX_PAYLOAD_BYTES_PROPERTY));
    }
    if (size > effectiveLimit * PAYLOAD_WARN_RATIO
        && PAYLOAD_SIZE_LOGGED.compareAndSet(false, true)) {
      LOG.warn("AI Agent connector: chat memory for variable '{}' holds {} messages serializing to "
          + "{} bytes, within {}% of the {}-byte usable ceiling. Once exceeded the agent task will "
          + "fail. Reduce 'chatMemoryMaxMessages' or raise the limit; with RAG enabled the retrieved "
          + "chunks are persisted with every turn and are the usual cause. Logged once per JVM.",
          variableName, messageCount, size, (int) (PAYLOAD_WARN_RATIO * 100), effectiveLimit);
    }
  }

  /**
   * Resolves the payload ceiling from system property → environment variable →
   * {@link #DEFAULT_MAX_PAYLOAD_BYTES}. An unparseable value is reported once and
   * falls back to the default rather than disabling the guard silently.
   */
  private static int maxPayloadBytes() {
    String raw = ConnectorSettings.resolve(MAX_PAYLOAD_BYTES_PROPERTY, MAX_PAYLOAD_BYTES_ENV_VAR);
    if (raw == null) {
      return DEFAULT_MAX_PAYLOAD_BYTES;
    }
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException e) {
      if (UNPARSEABLE_LIMIT_LOGGED.compareAndSet(false, true)) {
        LOG.warn("Ignoring unparseable chat-memory payload limit '{}' from {} / {}; using the "
            + "default of {} bytes. Logged once per JVM.",
            raw, MAX_PAYLOAD_BYTES_PROPERTY, MAX_PAYLOAD_BYTES_ENV_VAR,
            DEFAULT_MAX_PAYLOAD_BYTES);
      }
      return DEFAULT_MAX_PAYLOAD_BYTES;
    }
  }

  /**
   * Warns once when the writing execution is a concurrent branch, because the
   * memory variable lives at the process-instance scope and every branch sharing
   * a {@code memoryId} therefore contends on one row.
   *
   * <p>The contention itself is handled by the engine — the loser gets an
   * {@code OptimisticLockingException} and the job is retried. What makes it worth
   * a warning is that the retry re-runs the whole service task, and
   * {@link ProcessStarterTool} starts process instances in its own transaction, so
   * anything it already started is not rolled back and will be started again. This
   * cannot be fixed from the store; the remedy is a per-branch {@code memoryId},
   * which only the process model can supply.
   */
  private static void warnOnConcurrentBranch(ExecutionEntity execution, String variableName) {
    if (!execution.isConcurrent() || !CONCURRENT_BRANCH_LOGGED.compareAndSet(false, true)) {
      return;
    }
    LOG.warn("AI Agent connector: chat memory '{}' is being written from a concurrent execution "
        + "(parallel gateway or multi-instance). The variable lives at the process-instance scope, "
        + "so branches sharing this memoryId contend on one row and the losing branch fails with "
        + "an OptimisticLockingException, retrying the entire service task. Tools that commit "
        + "outside the job transaction — ProcessStarterTool starts process instances in its own "
        + "transaction — are then executed a second time. Give each branch its own memoryId "
        + "(for example by appending the loop or branch index) unless the branches are meant to "
        + "share one conversation. Logged once per JVM.", variableName);
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

  /**
   * The process-variable name carrying the conversation for {@code memoryId}.
   *
   * @throws AgentConnectorException when the id is missing or blank, or when it
   *     is too long for the engine's variable-name column. A missing id would
   *     otherwise produce one shared variable name for every such conversation
   *     in the instance — every agent task without an id reading and overwriting
   *     the same history — so it is rejected rather than mapped to a placeholder.
   */
  static String variableName(Object memoryId) {
    String id = (memoryId == null) ? null : memoryId.toString();
    if (id == null || id.trim().isEmpty()) {
      throw new AgentConnectorException(
          "memoryId must not be null or blank: it names the process variable holding the "
          + "conversation, so a missing id would make separate conversations share one variable. "
          + "The connector generates a UUID when chat memory is active and no memoryId is given; "
          + "a BPMN input mapping that resolves to an empty value overrides that with nothing.");
    }
    String name = AgentConnectorConstants.AGENT_CONNECTOR_MEMORY_PREFIX + id;
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
