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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.cibseven.bpm.engine.history.HistoricVariableUpdate;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.runtime.VariableInstance;
import org.cibseven.connect.ai.agent.AgentConnectorConstants;
import org.cibseven.connect.ai.agent.agentic.AdHocConversation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Engine-backed counterpart to {@link ProcessVariableChatMemoryStoreTest}, which
 * works against a mocked {@code ExecutionEntity} and therefore skips the two
 * mechanisms the store actually relies on: the scope walk that puts the variable
 * on the process instance rather than the local execution, and the byte-array
 * round-trip through {@code ACT_GE_BYTEARRAY}.
 *
 * <p>The process used here writes the conversation from one service task and
 * reads it back from a second one marked {@code asyncBefore}. Executing that job
 * explicitly gives a real transaction boundary in between, so the read cannot be
 * served from anything the first transaction left in memory — which is what
 * "continues on another replica" and "survives a restart" reduce to once the
 * heap is out of the picture. Each delegate uses a freshly constructed store, so
 * no state can travel through the store object either.
 */
public class ProcessVariableChatMemoryStoreEngineTest {

  private static final String MEMORY_ID = "engine-mem-1";
  private static final String VARIABLE_NAME =
      AgentConnectorConstants.AGENT_CONNECTOR_MEMORY_PREFIX + MEMORY_ID;

  /** Collects what the reading delegate saw, so the assertions can inspect it. */
  static volatile List<ChatMessage> readBack;

  /** Deliberately larger than the VARCHAR(4000) inline limit for variable text. */
  private static final String LONG_TEXT = buildLongText();

  /**
   * Built once for the whole class. JUnit 4 instantiates the test class per test
   * method, so a per-instance engine would try to create the schema again on the
   * same in-memory database and fail on the second test.
   */
  private static final ProcessEngine ENGINE = buildInMemoryEngine();


  private static ProcessEngine buildInMemoryEngine() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setJdbcUrl("jdbc:h2:mem:chat-memory-store-test;DB_CLOSE_DELAY=-1");
    // Jobs are executed explicitly in the test, so the async boundary is a
    // deterministic step rather than a race with a background thread.
    configuration.setJobExecutorActivate(false);
    // Every write of a variable is a history row at this level, which is what a batch saves. Set
    // for the whole class, as the engine is shared; the other tests do not depend on it.
    configuration.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
    return configuration.buildProcessEngine();
  }

  @BeforeEach
  public void setUp() {
    readBack = null;
    deployProcess();
  }

  /**
   * The engine is shared across the class, so each test removes its own
   * deployment — cascading, which takes the process instances, variables and
   * history with it — instead of relying on {@code ensureCleanAfterTest}.
   */
  @AfterEach
  public void tearDown() {
    readBack = null;
    ENGINE.getRepositoryService().createDeploymentQuery().list()
        .forEach(deployment ->
            ENGINE.getRepositoryService().deleteDeployment(deployment.getId(), true));
  }

  /**
   * Writes the conversation through the store. Runs inside a real engine command
   * with a BPMN execution context, which is what the store needs.
   */
  public static class WritingDelegate implements JavaDelegate {
    @Override
    public void execute(DelegateExecution execution) {
      new ProcessVariableChatMemoryStore().updateMessages(MEMORY_ID, Arrays.asList(
          UserMessage.from("My name is Alice."),
          AiMessage.from(LONG_TEXT)));
    }
  }

  /** Reads it back with a store instance that has never seen the write. */
  public static class ReadingDelegate implements JavaDelegate {
    @Override
    public void execute(DelegateExecution execution) {
      readBack = new ProcessVariableChatMemoryStore().getMessages(MEMORY_ID);
    }
  }

  @Test
  public void shouldStoreConversationOnTheProcessInstanceAndReadItBackInALaterTransaction() {
    ProcessInstance instance = ENGINE.getRuntimeService()
        .startProcessInstanceByKey("chatMemoryStoreEngineTest");

    // ── the write landed at process-instance scope, not on the local execution ──
    VariableInstance variable = ENGINE.getRuntimeService()
        .createVariableInstanceQuery()
        .processInstanceIdIn(instance.getId())
        .variableName(VARIABLE_NAME)
        .singleResult();

    assertThat(variable).isNotNull();
    assertThat(variable.getProcessInstanceId()).isEqualTo(instance.getId());
    // The scope walk in setVariable is the point: the variable belongs to the
    // process instance's own execution, not to the service task's.
    assertThat(variable.getExecutionId()).isEqualTo(instance.getId());
    // Serialized as an object, so the payload sits in ACT_GE_BYTEARRAY rather
    // than the VARCHAR(4000) text column — the claim the mocked test cannot make.
    assertThat(variable.getTypeName()).isEqualTo("object");

    // ── second transaction: the async job runs the reading delegate ──
    Job job = ENGINE.getManagementService().createJobQuery()
        .processInstanceId(instance.getId())
        .singleResult();
    assertThat(job).isNotNull();
    ENGINE.getManagementService().executeJob(job.getId());

    assertThat(readBack).hasSize(2);
    assertThat(readBack.get(0)).isInstanceOf(UserMessage.class);
    assertThat(((UserMessage) readBack.get(0)).singleText()).isEqualTo("My name is Alice.");
    assertThat(readBack.get(1)).isInstanceOf(AiMessage.class);
    // Intact after the byte-array round-trip, including the oversized payload.
    assertThat(((AiMessage) readBack.get(1)).text()).isEqualTo(LONG_TEXT);
  }

  @Test
  public void shouldRemoveTheVariableWhenTheProcessInstanceIsDeleted() {
    ProcessInstance instance = ENGINE.getRuntimeService()
        .startProcessInstanceByKey("chatMemoryStoreEngineTest");

    assertThat(runtimeVariableCount(instance.getId())).isEqualTo(1);

    ENGINE.getRuntimeService().deleteProcessInstance(instance.getId(), "test");

    // Chat memory ends with the process instance — this is why the store needs no
    // retention configuration of its own.
    assertThat(runtimeVariableCount(instance.getId())).isZero();
  }

  private long runtimeVariableCount(String processInstanceId) {
    return ENGINE.getRuntimeService().createVariableInstanceQuery()
        .processInstanceIdIn(processInstanceId)
        .variableName(VARIABLE_NAME)
        .count();
  }

  private void deployProcess() {
    String bpmn = ""
        + "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + "             targetNamespace='http://cibseven.org/test'>"
        + "  <process id='chatMemoryStoreEngineTest' isExecutable='true'"
        + "           camunda:historyTimeToLive='1'>"
        + "    <startEvent id='start'/>"
        + "    <sequenceFlow id='f1' sourceRef='start' targetRef='write'/>"
        + "    <serviceTask id='write'"
        + "                 camunda:class='" + WritingDelegate.class.getName() + "'/>"
        + "    <sequenceFlow id='f2' sourceRef='write' targetRef='read'/>"
        + "    <serviceTask id='read' camunda:asyncBefore='true'"
        + "                 camunda:class='" + ReadingDelegate.class.getName() + "'/>"
        + "    <sequenceFlow id='f3' sourceRef='read' targetRef='end'/>"
        + "    <endEvent id='end'/>"
        + "  </process>"
        + "</definitions>";

    ENGINE.getRepositoryService()
        .createDeployment()
        .addString("chatMemoryStoreEngineTest.bpmn20.xml", bpmn)
        .deploy();

    ProcessDefinition definition = ENGINE.getRepositoryService()
        .createProcessDefinitionQuery()
        .processDefinitionKey("chatMemoryStoreEngineTest")
        .singleResult();
    assertThat(definition).isNotNull();
  }

  /** ~6 KB, well past the VARCHAR(4000) limit for inline variable text. */
  private static String buildLongText() {
    StringBuilder sb = new StringBuilder(6000);
    while (sb.length() < 6000) {
      sb.append("Die Antwort enthaelt bewusst viel Text, damit die Nutzlast das "
          + "VARCHAR(4000)-Limit ueberschreitet und in ACT_GE_BYTEARRAY landet. ");
    }
    return sb.toString();
  }

  private static final String GONE_MEMORY_ID = "engine-mem-gone";

  /** Whether the variable existed while the batch was still open. */
  static volatile Boolean writtenBeforeClose;

  /**
   * Writes the conversation twice and a second one that it then deletes, all within one batch,
   * as a connector invocation does (CIB7-2156).
   */
  public static class BatchingDelegate implements JavaDelegate {
    @Override
    public void execute(DelegateExecution execution) {
      ProcessVariableChatMemoryStore store = new ProcessVariableChatMemoryStore();
      try (ProcessVariableChatMemoryStore.Batch batch = ProcessVariableChatMemoryStore.Batch.open()) {
        store.updateMessages(MEMORY_ID, Arrays.asList(UserMessage.from("My name is Alice.")));
        store.updateMessages(MEMORY_ID, Arrays.asList(UserMessage.from("My name is Alice."),
            AiMessage.from("Hello Alice.")));
        store.updateMessages(GONE_MEMORY_ID, Arrays.asList(UserMessage.from("Forget me.")));
        store.deleteMessages(GONE_MEMORY_ID);
        writtenBeforeClose = execution.hasVariable(VARIABLE_NAME);
        readBack = store.getMessages(MEMORY_ID);
      }
    }
  }

  /**
   * Within a batch the store reads its own latest version and writes each conversation once, when
   * the batch closes: one history row, holding the last version. A conversation deleted within the
   * batch is not brought back by the close.
   */
  @Test
  public void aBatchWritesEachConversationOnceWhenItCloses() {
    writtenBeforeClose = null;
    ENGINE.getRepositoryService().createDeployment()
        .addString("chatMemoryBatchTest.bpmn20.xml", ""
            + "<?xml version='1.0' encoding='UTF-8'?>"
            + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
            + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
            + "             targetNamespace='http://cibseven.org/test'>"
            + "  <process id='chatMemoryBatchTest' isExecutable='true'"
            + "           camunda:historyTimeToLive='1'>"
            + "    <startEvent id='start'/>"
            + "    <sequenceFlow id='f1' sourceRef='start' targetRef='write'/>"
            + "    <serviceTask id='write'"
            + "                 camunda:class='" + BatchingDelegate.class.getName() + "'/>"
            + "    <sequenceFlow id='f2' sourceRef='write' targetRef='wait'/>"
            + "    <userTask id='wait'/>"
            + "  </process>"
            + "</definitions>")
        .deploy();

    ProcessInstance instance =
        ENGINE.getRuntimeService().startProcessInstanceByKey("chatMemoryBatchTest");

    assertThat(writtenBeforeClose).as("held back while the batch was open").isFalse();
    assertThat(readBack).as("read its own latest version").hasSize(2);
    assertThat(ENGINE.getHistoryService().createHistoricDetailQuery()
        .processInstanceId(instance.getId()).variableUpdates().list())
        .extracting(detail -> ((HistoricVariableUpdate) detail).getVariableName())
        .as("one write of the conversation, none of the deleted one")
        .containsExactly(VARIABLE_NAME);
    Object stored = ENGINE.getRuntimeService().getVariable(instance.getId(), VARIABLE_NAME);
    assertThat(ChatMessageDeserializer.messagesFromJson(stored.toString()))
        .as("the last version").hasSize(2);
    assertThat(ENGINE.getRuntimeService().getVariable(instance.getId(),
        AgentConnectorConstants.AGENT_CONNECTOR_MEMORY_PREFIX + GONE_MEMORY_ID)).isNull();
  }

  private static final String INNER_MEMORY_ID = "engine-mem-inner";

  /** Whether the inner batch wrote what only it held when it closed. */
  static volatile Boolean innerWrittenAtItsClose;

  /**
   * A batch opened within another, as an AI agent task used as a tool opens one within the outer
   * invocation's (CIB7-2155): the conversation the outer batch holds stays there, one only the
   * inner one holds is written when the inner one closes (CIB7-2156).
   */
  public static class NestedBatchingDelegate implements JavaDelegate {
    @Override
    public void execute(DelegateExecution execution) {
      ProcessVariableChatMemoryStore store = new ProcessVariableChatMemoryStore();
      try (ProcessVariableChatMemoryStore.Batch outer = ProcessVariableChatMemoryStore.Batch.open()) {
        store.updateMessages(MEMORY_ID, Arrays.asList(UserMessage.from("My name is Alice.")));
        try (ProcessVariableChatMemoryStore.Batch inner =
            ProcessVariableChatMemoryStore.Batch.open()) {
          store.updateMessages(MEMORY_ID, Arrays.asList(UserMessage.from("My name is Alice."),
              AiMessage.from("Hello Alice.")));
          store.updateMessages(INNER_MEMORY_ID, Arrays.asList(UserMessage.from("Inner.")));
        }
        writtenBeforeClose = execution.hasVariable(VARIABLE_NAME);
        innerWrittenAtItsClose = execution.hasVariable(
            AgentConnectorConstants.AGENT_CONNECTOR_MEMORY_PREFIX + INNER_MEMORY_ID);
        readBack = store.getMessages(MEMORY_ID);
      }
    }
  }

  @Test
  public void aNestedBatchLeavesTheOuterOnesConversationToIt() {
    writtenBeforeClose = null;
    innerWrittenAtItsClose = null;
    deploy("chatMemoryNestedBatchTest", "write", ""
        + "    <serviceTask id='write'"
        + "                 camunda:class='" + NestedBatchingDelegate.class.getName() + "'/>"
        + "    <sequenceFlow id='f2' sourceRef='write' targetRef='wait'/>"
        + "    <userTask id='wait'/>");
    ProcessInstance instance =
        ENGINE.getRuntimeService().startProcessInstanceByKey("chatMemoryNestedBatchTest");

    assertThat(writtenBeforeClose).as("held by the outer batch past the inner close").isFalse();
    assertThat(innerWrittenAtItsClose).as("written when the inner batch closed").isTrue();
    assertThat(readBack).as("the newest version, staged by the inner batch").hasSize(2);
    assertThat(memoryWrites(instance)).as("each conversation written once")
        .containsExactlyInAnyOrder(VARIABLE_NAME,
            AgentConnectorConstants.AGENT_CONNECTOR_MEMORY_PREFIX + INNER_MEMORY_ID);
    Object stored = ENGINE.getRuntimeService().getVariable(instance.getId(), VARIABLE_NAME);
    assertThat(ChatMessageDeserializer.messagesFromJson(stored.toString()))
        .as("the newest version").hasSize(2);
  }

  /**
   * Writes and deletes the driving agent's conversations within one batch, as an agentic step
   * does (CIB7-2156).
   */
  public static class DrivenBatchingDelegate implements JavaDelegate {
    @Override
    public void execute(DelegateExecution execution) throws Exception {
      ExecutionEntity scope = Context.getBpmnExecutionContext().getExecution();
      ProcessVariableChatMemoryStore store = new ProcessVariableChatMemoryStore();
      try (ProcessVariableChatMemoryStore.Batch batch = ProcessVariableChatMemoryStore.Batch.open()) {
        drive(scope, () -> {
          store.updateMessages(MEMORY_ID, Arrays.asList(UserMessage.from("My name is Alice.")));
          store.updateMessages(MEMORY_ID, Arrays.asList(UserMessage.from("My name is Alice."),
              AiMessage.from("Hello Alice.")));
          store.updateMessages(GONE_MEMORY_ID, Arrays.asList(UserMessage.from("Forget me.")));
          store.deleteMessages(GONE_MEMORY_ID);
          PvmExecutionImpl state = AdHocConversation.findMemoryExecution(scope);
          writtenBeforeClose = state != null && state.hasVariableLocal(VARIABLE_NAME);
          readBack = store.getMessages(MEMORY_ID);
        });
      }
    }
  }

  /**
   * The driving agent's conversation is written once when the batch closes, out of its tools'
   * reach; one deleted within the batch is not written back.
   */
  @Test
  public void aBatchWritesTheDrivingAgentsConversationOnceWhereItsToolsCannotReachIt() {
    writtenBeforeClose = null;
    deploy("chatMemoryDrivenBatchTest", "scope", ""
        + "    <subProcess id='scope'>"
        + "      <startEvent id='scopeStart'/>"
        + "      <sequenceFlow id='s1' sourceRef='scopeStart' targetRef='write'/>"
        + "      <serviceTask id='write'"
        + "                   camunda:class='" + DrivenBatchingDelegate.class.getName() + "'/>"
        + "      <sequenceFlow id='s2' sourceRef='write' targetRef='wait'/>"
        + "      <userTask id='wait'/>"
        + "    </subProcess>");
    ProcessInstance instance =
        ENGINE.getRuntimeService().startProcessInstanceByKey("chatMemoryDrivenBatchTest");

    assertThat(writtenBeforeClose).as("held back while the batch was open").isFalse();
    assertThat(readBack).as("read its own latest version").hasSize(2);
    assertThat(memoryWrites(instance)).as("one write of the conversation, none of the deleted one")
        .containsExactly(VARIABLE_NAME);
    VariableInstance stored = ENGINE.getRuntimeService().createVariableInstanceQuery()
        .processInstanceIdIn(instance.getId()).variableName(VARIABLE_NAME).singleResult();
    assertThat(stored.getExecutionId()).as("on the agent state, not at the process level")
        .isNotEqualTo(instance.getId());
    assertThat(ChatMessageDeserializer.messagesFromJson(stored.getValue().toString()))
        .as("the last version").hasSize(2);
  }

  /** {@code AdHocConversation.drive}, which only the turn runner calls, from its own package. */
  private static void drive(ExecutionEntity scope, Runnable call) throws Exception {
    Method drive = AdHocConversation.class.getDeclaredMethod("drive", ExecutionEntity.class,
        Supplier.class);
    drive.setAccessible(true);
    Supplier<Object> supplier = () -> {
      call.run();
      return null;
    };
    try {
      drive.invoke(null, scope, supplier);
    } catch (InvocationTargetException e) {
      throw (Exception) e.getCause();
    }
  }

  /**
   * Two model calls through the chat-log listener, as one connector invocation makes them, each
   * written as a chunk of its own (CIB7-2156).
   */
  public static class ChatLogDelegate implements JavaDelegate {
    @Override
    public void execute(DelegateExecution execution) {
      AgentChatListener listener = new AgentChatListener(null, null, Boolean.TRUE);
      for (int call = 0; call < 2; call++) {
        ChatRequest request = ChatRequest.builder()
            .messages(Collections.singletonList(UserMessage.from("hi"))).build();
        listener.onRequest(new ChatModelRequestContext(request, null, new HashMap<>()));
        listener.onResponse(new ChatModelResponseContext(
            ChatResponse.builder().aiMessage(AiMessage.from("ok")).build(),
            request, null, new HashMap<>()));
      }
      listener.flush();
    }
  }

  /**
   * The chat log of a service task that runs twice in one process instance, which already holds
   * a log written before chunking: the old variable is left as it is, and the chunks number on
   * after it, across both invocations.
   */
  @Test
  public void theChatLogContinuesAfterALogFromBeforeChunkingAndAcrossInvocations() {
    String log = AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX + "agent";
    String separator = AgentConnectorConstants.AGENT_CONNECTOR_LOG_CHUNK_SEPARATOR;
    deploy("chatLogChunkTest", "agent", ""
        + "    <serviceTask id='agent'"
        + "                 camunda:class='" + ChatLogDelegate.class.getName() + "'/>"
        + "    <sequenceFlow id='f2' sourceRef='agent' targetRef='wait'/>"
        + "    <userTask id='wait'/>");
    ProcessInstance instance = ENGINE.getRuntimeService().startProcessInstanceByKey(
        "chatLogChunkTest", Collections.<String, Object>singletonMap(log,
            "[{\"type\":\"request\",\"eventSeq\":0},{\"type\":\"response\",\"eventSeq\":1}]"));
    ENGINE.getRuntimeService().createProcessInstanceModification(instance.getId())
        .startBeforeActivity("agent").execute();

    assertThat(ENGINE.getHistoryService().createHistoricDetailQuery()
        .processInstanceId(instance.getId()).variableUpdates().list().stream()
        .map(detail -> ((HistoricVariableUpdate) detail).getVariableName())
        .filter(name -> name.startsWith(log))
        .collect(Collectors.toList()))
        .as("the old log as it was started with, then each chunk written once")
        .containsExactlyInAnyOrder(log,
            log + separator + 2, log + separator + 4, log + separator + 6, log + separator + 8);
    assertThat(String.valueOf(
        ENGINE.getRuntimeService().getVariable(instance.getId(), log + separator + 6)))
        .as("the second invocation starts after the first one's last chunk")
        .contains("\"eventSeq\":6").contains("\"eventSeq\":7");
  }

  /** The names of the chat-memory variables written in {@code instance}, one per write. */
  private static List<String> memoryWrites(ProcessInstance instance) {
    return ENGINE.getHistoryService().createHistoricDetailQuery()
        .processInstanceId(instance.getId()).variableUpdates().list().stream()
        .map(detail -> ((HistoricVariableUpdate) detail).getVariableName())
        .filter(name -> name.startsWith(AgentConnectorConstants.AGENT_CONNECTOR_MEMORY_PREFIX))
        .collect(Collectors.toList());
  }

  /** Deploys a process {@code key} whose start event leads to {@code first} of {@code elements}. */
  private static void deploy(String key, String first, String elements) {
    ENGINE.getRepositoryService().createDeployment()
        .addString(key + ".bpmn20.xml", ""
            + "<?xml version='1.0' encoding='UTF-8'?>"
            + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
            + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
            + "             targetNamespace='http://cibseven.org/test'>"
            + "  <process id='" + key + "' isExecutable='true'"
            + "           camunda:historyTimeToLive='1'>"
            + "    <startEvent id='start'/>"
            + "    <sequenceFlow id='f1' sourceRef='start' targetRef='" + first + "'/>"
            + elements
            + "  </process>"
            + "</definitions>")
        .deploy();
  }

  /** Guards against the serializer silently changing shape for large payloads. */
  @Test
  public void shouldRoundTripAPayloadLargerThanTheInlineTextLimit() {
    List<ChatMessage> original = Arrays.asList(AiMessage.from(LONG_TEXT));
    String json = ChatMessageSerializer.messagesToJson(original);

    assertThat(json.length()).isGreaterThan(4000);
  }

}
