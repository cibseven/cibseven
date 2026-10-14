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
import org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.connect.ScriptedAgent;
import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
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
 * runs the turn job that the task's end scheduled, and has to find the agent's pending list,
 * its turn count and its conversation. Nothing travels between them except the database.
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

  /** What a turn does. */
  interface Turn {
    void run(ExecutionEntity scope);
  }

  public static final List<String> RAN_ON = new ArrayList<>();
  static volatile List<ChatMessage> readBack;

  /** Records which node ran the turn, so the test can prove the hand-over. */
  private static ScriptedAgent.Turn agent(Turn turn) {
    return parameters -> {
      RAN_ON.add(Context.getProcessEngineConfiguration().getProcessEngineName());
      turn.run(Context.getExecutionContext().getExecution());
      return "done";
    };
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
    configuration.setProcessEnginePlugins(
        Collections.<ProcessEnginePlugin>singletonList(new ConnectProcessEnginePlugin()));
    return configuration.buildProcessEngine();
  }

  @BeforeEach
  public void startNodes() {
    RAN_ON.clear();
    readBack = null;
    nodeA = node("node-a");
    nodeB = node("node-b");
  }

  @AfterEach
  public void stopNodes() {
    ScriptedAgent.uninstall();
    ProcessStarterToolContext.clear();
    if (nodeA != null) {
      nodeA.close();
    }
    if (nodeB != null) {
      nodeB.close();
    }
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
        + "      <camunda:property name='cibseven.agentic.enabled' value='true' />"
        + "      <camunda:property name='cibseven.agentic.message' value='Get this approved.' />"
        + "    </camunda:properties></extensionElements>"
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
    ScriptedAgent.install(agent(scope -> {
      new ProcessVariableChatMemoryStore().updateMessages(MEMORY_ID,
          Arrays.<ChatMessage>asList(UserMessage.from("Please get this approved."),
              AiMessage.from("Starting the approval.")));
      new AdHocSubProcessTool().startActivity("approve", Collections.<String, Object>emptyMap());
    }), agent(scope -> {
      ProcessVariableChatMemoryStore store = new ProcessVariableChatMemoryStore();
      readBack = store.getMessages(MEMORY_ID);
      Map<String, Object> listing = new AdHocSubProcessTool().turnReport();
      scope.setVariable("turnSeenOnB", listing.get("turn"));
      scope.setVariable("finishedSeenOnB",
          String.valueOf(listing.get("finishedSinceLastTurn")));
      new AdHocSubProcessTool().completeScope();
    }));

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
