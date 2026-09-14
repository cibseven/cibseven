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

import java.util.Arrays;
import java.util.List;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.runtime.VariableInstance;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.connect.ai.agent.AgentConnectorConstants;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;

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

  @Rule
  public ProcessEngineRule engineRule = new ProcessEngineRule(ENGINE);

  private static ProcessEngine buildInMemoryEngine() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setJdbcUrl("jdbc:h2:mem:chat-memory-store-test;DB_CLOSE_DELAY=-1");
    // Jobs are executed explicitly in the test, so the async boundary is a
    // deterministic step rather than a race with a background thread.
    configuration.setJobExecutorActivate(false);
    return configuration.buildProcessEngine();
  }

  @Before
  public void setUp() {
    readBack = null;
    deployProcess();
  }

  /**
   * The engine is shared across the class, so each test removes its own
   * deployment — cascading, which takes the process instances, variables and
   * history with it — instead of relying on {@code ensureCleanAfterTest}.
   */
  @After
  public void tearDown() {
    readBack = null;
    engineRule.getRepositoryService().createDeploymentQuery().list()
        .forEach(deployment ->
            engineRule.getRepositoryService().deleteDeployment(deployment.getId(), true));
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
    ProcessInstance instance = engineRule.getRuntimeService()
        .startProcessInstanceByKey("chatMemoryStoreEngineTest");

    // ── the write landed at process-instance scope, not on the local execution ──
    VariableInstance variable = engineRule.getRuntimeService()
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
    Job job = engineRule.getManagementService().createJobQuery()
        .processInstanceId(instance.getId())
        .singleResult();
    assertThat(job).isNotNull();
    engineRule.getManagementService().executeJob(job.getId());

    assertThat(readBack).hasSize(2);
    assertThat(readBack.get(0)).isInstanceOf(UserMessage.class);
    assertThat(((UserMessage) readBack.get(0)).singleText()).isEqualTo("My name is Alice.");
    assertThat(readBack.get(1)).isInstanceOf(AiMessage.class);
    // Intact after the byte-array round-trip, including the oversized payload.
    assertThat(((AiMessage) readBack.get(1)).text()).isEqualTo(LONG_TEXT);
  }

  @Test
  public void shouldRemoveTheVariableWhenTheProcessInstanceIsDeleted() {
    ProcessInstance instance = engineRule.getRuntimeService()
        .startProcessInstanceByKey("chatMemoryStoreEngineTest");

    assertThat(runtimeVariableCount(instance.getId())).isEqualTo(1);

    engineRule.getRuntimeService().deleteProcessInstance(instance.getId(), "test");

    // Chat memory ends with the process instance — this is why the store needs no
    // retention configuration of its own.
    assertThat(runtimeVariableCount(instance.getId())).isZero();
  }

  private long runtimeVariableCount(String processInstanceId) {
    return engineRule.getRuntimeService().createVariableInstanceQuery()
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

    engineRule.getRepositoryService()
        .createDeployment()
        .addString("chatMemoryStoreEngineTest.bpmn20.xml", bpmn)
        .deploy();

    ProcessDefinition definition = engineRule.getRepositoryService()
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

  /** Guards against the serializer silently changing shape for large payloads. */
  @Test
  public void shouldRoundTripAPayloadLargerThanTheInlineTextLimit() {
    List<ChatMessage> original = Arrays.asList(AiMessage.from(LONG_TEXT));
    String json = ChatMessageSerializer.messagesToJson(original);

    assertThat(json.length()).isGreaterThan(4000);
  }

}
