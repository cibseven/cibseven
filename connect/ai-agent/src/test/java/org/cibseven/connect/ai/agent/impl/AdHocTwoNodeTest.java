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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

/**
 * Two engine nodes, one database — the claim the state relocation is built on.
 *
 * <p>Every other test of this ticket runs one engine, and the restart tests run one
 * engine twice. Both show persistence. Neither shows that a <em>second, concurrently
 * configured</em> node can pick the conversation up, and the review asked for exactly
 * that: "persistiert" and "funktioniert zwischen zwei Nodes" are not the same claim.
 *
 * <p>Node A takes the first turn and leaves a user task waiting. Node B — its own
 * engine, its own deployment cache, its own caches of everything — completes the task,
 * runs the re-activated driver, and has to find the agent's pending list, its turn
 * count and its conversation. Nothing travels between them except the database.
 *
 * <p>Both nodes run with the job executor off, so the test decides which node executes
 * which job rather than racing them. That is the point: the question is whether the
 * state is readable from elsewhere, not whether two executors can be made to collide.
 */
public class AdHocTwoNodeTest {

  private static final String JDBC_URL = "jdbc:h2:mem:adhoc-two-node;DB_CLOSE_DELAY=-1";
  private static final String MEMORY_ID = "cluster-mem";

  private ProcessEngine nodeA;
  private ProcessEngine nodeB;

  /** What a turn does. Static, because both nodes resolve the delegate by class name. */
  interface Turn {
    void run(DelegateExecution execution);
  }

  public static final List<Turn> SCRIPT = new ArrayList<>();
  public static final List<String> RAN_ON = new ArrayList<>();
  static volatile List<ChatMessage> readBack;

  /** The driver. Records which node ran it, so the test can prove the hand-over. */
  public static class AgentTask implements JavaDelegate {

    static int invocations;

    @Override
    public void execute(DelegateExecution execution) {
      int index = invocations++;
      // The engine that is executing this turn, taken from the command context rather
      // than from DelegateExecution, which does not expose the engine itself.
      RAN_ON.add(org.cibseven.bpm.engine.impl.context.Context
          .getProcessEngineConfiguration().getProcessEngineName());
      if (index < SCRIPT.size()) {
        SCRIPT.get(index).run(execution);
      }
    }
  }

  private static ProcessEngine node(String name) {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName(name);
    configuration.setJdbcUrl(JDBC_URL);
    configuration.setDatabaseSchemaUpdate("true");
    configuration.setJobExecutorActivate(false);
    configuration.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
    configuration.setHistoryTimeToLive("P30D");
    return configuration.buildProcessEngine();
  }

  @BeforeEach
  public void startNodes() {
    SCRIPT.clear();
    RAN_ON.clear();
    AgentTask.invocations = 0;
    readBack = null;
    nodeA = node("node-a");
    nodeB = node("node-b");
  }

  @AfterEach
  public void stopNodes() {
    ProcessStarterToolContext.clear();
    if (nodeA != null) {
      nodeA.close();
    }
    if (nodeB != null) {
      nodeB.close();
    }
    SCRIPT.clear();
    RAN_ON.clear();
    readBack = null;
  }

  private static String model() {
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + " xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + " targetNamespace='http://cibseven.org/adhoc-cluster'>"
        + "<process id='adHocCluster' isExecutable='true'>"
        + "  <startEvent id='start' />"
        + "  <sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + "  <adHocSubProcess id='adHoc'>"
        + "    <extensionElements><camunda:properties>"
        + "      <camunda:property name='explicitCompletionOnly' value='true' />"
        + "      <camunda:property name='adHocDriverActivity' value='agent' />"
        + "      <camunda:property name='activeElementsCollection' value='agent' />"
        + "    </camunda:properties></extensionElements>"
        + "    <serviceTask id='agent' name='Agent' camunda:asyncBefore='true'"
        + "        camunda:class='" + AgentTask.class.getName() + "' />"
        + "    <userTask id='approve' name='Approve'>"
        + "      <extensionElements><camunda:formData>"
        + "        <camunda:formField id='approved' label='Approved?' type='boolean' />"
        + "      </camunda:formData></extensionElements>"
        + "    </userTask>"
        + "  </adHocSubProcess>"
        + "  <sequenceFlow id='f2' sourceRef='adHoc' targetRef='end' />"
        + "  <endEvent id='end' />"
        + "</process></definitions>";
  }

  /** Runs the one pending job on {@code node}, with that node as the tool's engine. */
  private void runTurnOn(ProcessEngine node, ProcessInstance instance) {
    ProcessStarterToolContext.setEngine(node);
    List<Job> jobs = node.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(jobs).as("a turn waiting as a job on " + node.getName()).hasSize(1);
    node.getManagementService().executeJob(jobs.get(0).getId());
  }

  /**
   * The conversation and the loop state survive the move from one node to another.
   *
   * <p>Node B writes nothing before it reads: everything it finds was put there by
   * node A, through the database.
   */
  @Test
  public void aTurnStartedOnOneNodeIsContinuedOnAnother() {
    SCRIPT.add(execution -> {
      new ProcessVariableChatMemoryStore().updateMessages(MEMORY_ID,
          Arrays.<ChatMessage>asList(UserMessage.from("Please get this approved."),
              AiMessage.from("Starting the approval.")));
      new AdHocSubProcessTool().startActivity("approve", Collections.<String, Object>emptyMap());
    });
    SCRIPT.add(execution -> {
      ProcessVariableChatMemoryStore store = new ProcessVariableChatMemoryStore();
      readBack = store.getMessages(MEMORY_ID);
      Map<String, Object> listing = new AdHocSubProcessTool().listAvailableActivities();
      execution.setVariable("turnSeenOnB", listing.get("turn"));
      execution.setVariable("finishedSeenOnB",
          String.valueOf(listing.get("finishedSinceLastTurn")));
      new AdHocSubProcessTool().completeScope();
    });

    ProcessStarterToolContext.setEngine(nodeA);
    nodeA.getRepositoryService().createDeployment()
        .addString("adHocCluster.bpmn20.xml", model()).deploy();
    ProcessInstance instance =
        nodeA.getRuntimeService().startProcessInstanceByKey("adHocCluster");

    runTurnOn(nodeA, instance);

    Task waiting = nodeB.getTaskService().createTaskQuery()
        .processInstanceId(instance.getId()).singleResult();
    assertThat(waiting).as("node B sees the task node A created").isNotNull();

    // From here on, only node B.
    ProcessStarterToolContext.setEngine(nodeB);
    nodeB.getTaskService().complete(waiting.getId(),
        Collections.<String, Object>singletonMap("approved", true));
    runTurnOn(nodeB, instance);

    assertThat(RAN_ON).as("the two turns really ran on different nodes")
        .containsExactly("node-a", "node-b");

    assertThat(readBack).as("node B read the conversation node A wrote").hasSize(2);
    assertThat(((UserMessage) readBack.get(0)).singleText())
        .isEqualTo("Please get this approved.");

    assertThat(nodeB.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(instance.getId()).variableName("turnSeenOnB").singleResult().getValue())
        .as("the turn counter continued rather than restarting").isEqualTo(2);
    assertThat(String.valueOf(nodeB.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(instance.getId()).variableName("finishedSeenOnB")
        .singleResult().getValue()))
        .as("node B learned what finished, including the value the person entered")
        .contains("approve").contains("approved");

    assertThat(nodeB.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(instance.getId()).count())
        .as("and the scope ended from node B").isZero();
  }
}
