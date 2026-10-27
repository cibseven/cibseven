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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.runtime.VariableInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.connect.ScriptedAgent;
import org.cibseven.connect.ai.agent.agentic.AdHocConversation;
import org.cibseven.connect.ai.agent.agentic.AgenticTurnRunner;
import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * A feedback loop that re-enters one agentic ad hoc scope, driven turn by turn through
 * {@link ScriptedAgent} (CIB7-2137) -- the shape of the reproduction attached to the ticket:
 * start, the agentic scope, a user-feedback task, and a gateway that sends an unsatisfied
 * answer back into the scope.
 *
 * <p>Where {@link org.cibseven.connect.ai.agent.agentic.AdHocConversationTest} is about the
 * holder execution in isolation, this is about what a real turn does with it: which {@code
 * memoryId} the connector is asked for, whether {@code followUp} reaches it instead of the
 * task, and whether the total-turn cap ends the loop without spending a further model call.
 *
 * <p>{@link ScriptedAgent} replaces the connector outright, so none of this reaches
 * {@code AgentConnectorImpl}, {@link ProcessVariableChatMemoryStore}, or a real model request.
 * {@link AgenticConversationContinuityHttpTest} is what proves those: real messages persisted
 * and read back, {@code finishedSinceLastTurn} actually empty on a re-entry, and that a tool
 * writing the real conversation's variable name does not reach it.
 */
public class AgenticConversationContinuityTest {

  private static final ProcessEngine ENGINE = buildInMemoryEngine();

  private static ProcessEngine buildInMemoryEngine() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setJdbcUrl("jdbc:h2:mem:agentic-conversation-continuity;DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    configuration.setHistoryTimeToLive("P30D");
    configuration.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
    configuration.setProcessEnginePlugins(
        Collections.<ProcessEnginePlugin>singletonList(new ConnectProcessEnginePlugin()));
    return configuration.buildProcessEngine();
  }

  @BeforeEach
  public void setUp() {
    ScriptedAgent.reset();
  }

  @AfterEach
  public void tearDown() {
    ScriptedAgent.uninstall();
  }

  // --- the scripted agent's turns ---------------------------------------------

  /** Answers plainly, starting nothing: the scope completes as soon as this turn ends. */
  private static ScriptedAgent.Turn answers(String text) {
    return parameters -> text;
  }

  /** Starts the scope's one waiting child, so the scope -- and the turn -- stay open. */
  private static ScriptedAgent.Turn startsWorkerAndAnswers(String text) {
    return parameters -> {
      new AdHocSubProcessTool().startActivity("worker", Collections.<String, Object>emptyMap());
      return text;
    };
  }

  // --- the model ---------------------------------------------------------------

  /**
   * One agentic ad hoc scope re-entered through a feedback loop: start, the scope (connector
   * input parameters {@code message} and {@code followUp}, and one startable child "worker"),
   * a user-feedback task carrying {@code satisfied} and {@code feedback}, and a gateway sending
   * an unsatisfied answer back into the scope.
   */
  private static String model(String conversationProperty, Integer maxTurnsTotal) {
    return model(conversationProperty, maxTurnsTotal, null, null);
  }

  private static String model(String conversationProperty, Integer maxTurnsTotal,
      Integer maxModelCalls, String chatMemoryMaxMessages) {
    StringBuilder properties = new StringBuilder("<camunda:properties>");
    if (conversationProperty != null) {
      properties.append("<camunda:property name='cibseven.agentic.conversation' value='")
          .append(conversationProperty).append("' />");
    }
    if (maxTurnsTotal != null) {
      properties.append("<camunda:property name='cibseven.agentic.maxTurnsTotal' value='")
          .append(maxTurnsTotal).append("' />");
    }
    if (maxModelCalls != null) {
      properties.append("<camunda:property name='cibseven.agentic.maxModelCalls' value='")
          .append(maxModelCalls).append("' />");
    }
    properties.append("</camunda:properties>");

    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + " xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + " xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'"
        + " targetNamespace='http://cibseven.org/agentic-conversation-continuity'>"
        + "<process id='conversationContinuity' isExecutable='true'>"
        + "  <startEvent id='start' />"
        + "  <sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + "  <adHocSubProcess id='adHoc'>"
        + "    <extensionElements>" + properties + "<camunda:connector>"
        + "      <camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "      <camunda:inputOutput>"
        + "        <camunda:inputParameter name='message'>Do the work.</camunda:inputParameter>"
        + "        <camunda:inputParameter name='followUp'>${feedback}</camunda:inputParameter>"
        + (chatMemoryMaxMessages == null ? ""
            : "        <camunda:inputParameter name='chatMemoryMaxMessages'>"
                + chatMemoryMaxMessages + "</camunda:inputParameter>")
        + "      </camunda:inputOutput>"
        + "    </camunda:connector></extensionElements>"
        + "    <userTask id='worker' name='Worker' />"
        + "  </adHocSubProcess>"
        + "  <sequenceFlow id='f2' sourceRef='adHoc' targetRef='feedback' />"
        + "  <userTask id='feedback' name='Feedback' />"
        + "  <sequenceFlow id='f3' sourceRef='feedback' targetRef='gw' />"
        + "  <exclusiveGateway id='gw' />"
        + "  <sequenceFlow id='toAdHoc' sourceRef='gw' targetRef='adHoc'>"
        + "    <conditionExpression xsi:type='tFormalExpression'>${!satisfied}</conditionExpression>"
        + "  </sequenceFlow>"
        + "  <sequenceFlow id='toEnd' sourceRef='gw' targetRef='end'>"
        + "    <conditionExpression xsi:type='tFormalExpression'>${satisfied}</conditionExpression>"
        + "  </sequenceFlow>"
        + "  <endEvent id='end' />"
        + "</process></definitions>";
  }

  // --- helpers -------------------------------------------------------------------

  private ProcessInstance start(String conversationProperty, Integer maxTurnsTotal,
      ScriptedAgent.Turn... script) {
    return start(conversationProperty, maxTurnsTotal, null, null,
        Collections.<String, Object>emptyMap(), script);
  }

  private ProcessInstance start(String conversationProperty, Integer maxTurnsTotal,
      Integer maxModelCalls, String chatMemoryMaxMessages, Map<String, Object> additionalVariables,
      ScriptedAgent.Turn... script) {
    ScriptedAgent.install(script);
    ENGINE.getRepositoryService().createDeployment()
        .addString("conversationContinuity.bpmn20.xml",
            model(conversationProperty, maxTurnsTotal, maxModelCalls, chatMemoryMaxMessages))
        .deploy();
    // followUp is evaluated exactly once, on a re-entry (AgenticTurnRunner.snapshotFollowUp),
    // so the first entry never evaluates it and this value is never read then. Set anyway,
    // matching the ticket's own reproduction, which starts its instance with feedback = "(keine)".
    Map<String, Object> initial = new HashMap<String, Object>();
    initial.put("feedback", "(none yet)");
    initial.putAll(additionalVariables);
    return ENGINE.getRuntimeService()
        .startProcessInstanceByKey("conversationContinuity", initial);
  }

  /** Runs the one pending turn job, which is one job of one turn (CIB7-2148). */
  private void runJob(ProcessInstance instance) {
    List<Job> jobs = ENGINE.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(jobs).as("a turn step waiting as a job").hasSize(1);
    ENGINE.getManagementService().executeJob(jobs.get(0).getId());
  }

  private void completeFeedback(ProcessInstance instance, boolean satisfied, String feedback) {
    Task task = ENGINE.getTaskService().createTaskQuery()
        .processInstanceId(instance.getId()).taskDefinitionKey("feedback").singleResult();
    assertThat(task).as("the feedback task is waiting").isNotNull();
    Map<String, Object> variables = new HashMap<String, Object>();
    variables.put("satisfied", Boolean.valueOf(satisfied));
    variables.put("feedback", feedback);
    ENGINE.getTaskService().complete(task.getId(), variables);
  }

  private void completeWorker(ProcessInstance instance) {
    Task task = ENGINE.getTaskService().createTaskQuery()
        .processInstanceId(instance.getId()).taskDefinitionKey("worker").singleResult();
    assertThat(task).as("the worker task is waiting").isNotNull();
    ENGINE.getTaskService().complete(task.getId());
  }

  private String outputOf(ProcessInstance instance) {
    List<VariableInstance> variables = ENGINE.getRuntimeService().createVariableInstanceQuery()
        .processInstanceIdIn(instance.getId()).variableName("agentOutput").list();
    return variables.isEmpty() ? null : String.valueOf(variables.get(0).getValue());
  }

  // --- perActivity keeps one conversation across the loop -------------------------

  /**
   * The second entry's first request carries the same {@code memoryId} as the first entry's,
   * and its {@code message} is the feedback rather than the task -- the two halves of the
   * acceptance criterion read directly off what reached the connector.
   */
  @Test
  public void perActivityKeepsTheSameConversationIdAndSendsTheFeedbackOnReEntry() {
    ProcessInstance instance =
        start("perActivity", null, answers("Working on it."), answers("Shorter, as asked."));

    runJob(instance);
    completeFeedback(instance, false, "Please be shorter.");
    runJob(instance);
    completeFeedback(instance, true, "Good, thanks.");

    List<Map<String, Object>> requests = ScriptedAgent.requests();
    assertThat(requests).hasSize(2);
    assertThat(requests.get(0).get("message")).as("the first entry sees the task").isEqualTo(
        "Do the work.");
    assertThat(requests.get(1).get("message")).as("the re-entry sees the feedback instead")
        .isEqualTo("Please be shorter.");
    assertThat(requests.get(1).get("memoryId"))
        .as("one conversation across both entries")
        .isEqualTo(requests.get(0).get("memoryId"));
  }

  @Test
  public void unspecifiedPerActivityWindowUsesTheCallLimitAwareDefault() {
    ProcessInstance defaultLimit = start("perActivity", null, answers("Default window."));
    runJob(defaultLimit);
    assertThat(ScriptedAgent.requests().get(0).get("chatMemoryMaxMessages")).isEqualTo(53);
    completeFeedback(defaultLimit, true, "Done.");

    ProcessInstance higherLimit = start("perActivity", null, 30, null,
        Collections.<String, Object>emptyMap(), answers("Higher call limit."));
    runJob(higherLimit);
    assertThat(ScriptedAgent.requests().get(0).get("chatMemoryMaxMessages")).isEqualTo(63);
    completeFeedback(higherLimit, true, "Done.");
  }

  /**
   * CIB7-2234: a perEntry scope without a window passes none on, so the connector's own default
   * of 20 applies -- with the mode left out, with it spelled out, and with a window expression
   * that resolves to null. Only perActivity has a minimum that needs a default of its own.
   */
  @Test
  public void unspecifiedPerEntryWindowReachesTheConnectorUnset() {
    ProcessInstance defaultMode = start(null, null, answers("Default mode."));
    runJob(defaultMode);
    assertThat(ScriptedAgent.requests().get(0).get("chatMemoryMaxMessages")).isNull();
    completeFeedback(defaultMode, true, "Done.");

    ProcessInstance perEntry = start("perEntry", null, answers("Explicit mode."));
    runJob(perEntry);
    assertThat(ScriptedAgent.requests().get(0).get("chatMemoryMaxMessages")).isNull();
    completeFeedback(perEntry, true, "Done.");

    ProcessInstance dynamic = start("perEntry", null, 2, "${window}",
        Collections.<String, Object>singletonMap("window", null), answers("Unset window."));
    runJob(dynamic);
    assertThat(ScriptedAgent.requests().get(0).get("chatMemoryMaxMessages")).isNull();
    completeFeedback(dynamic, true, "Done.");
  }

  @Test
  public void acceptsTheExplicitPerActivityBoundaryAndLeavesPerEntryWindowsAlone() {
    ProcessInstance perActivity = start("perActivity", null, 2, "7",
        Collections.<String, Object>emptyMap(), answers("Boundary."));
    runJob(perActivity);
    assertThat(ScriptedAgent.requests().get(0).get("chatMemoryMaxMessages")).isEqualTo(7);
    completeFeedback(perActivity, true, "Done.");

    ProcessInstance perEntry = start("perEntry", null, 2, "6",
        Collections.<String, Object>emptyMap(), answers("Existing per-entry window."));
    runJob(perEntry);
    assertThat(ScriptedAgent.requests().get(0).get("chatMemoryMaxMessages")).isEqualTo("6");
    completeFeedback(perEntry, true, "Done.");
  }

  @Test
  public void rejectsAnUndersizedResolvedPerActivityWindowBeforeCallingTheConnector() {
    ProcessInstance instance = start("perActivity", null, 2, "${window}",
        Collections.<String, Object>singletonMap("window", "6"), answers("Must not run."));

    assertThatThrownBy(() -> runJob(instance))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Ad hoc sub process 'adHoc'")
        .hasMessageContaining("chatMemoryMaxMessages must be at least 7 messages");
    assertThat(ScriptedAgent.turnsTaken()).isZero();
    assertThat(ScriptedAgent.requests()).isEmpty();
  }

  @Test
  public void missingAndBlankDynamicWindowsUseThePerActivityDefault() {
    for (String value : new String[] { null, "   " }) {
      Map<String, Object> variables = new HashMap<String, Object>();
      variables.put("window", value);
      ProcessInstance instance = start("perActivity", null, 2, "${window}", variables,
          answers("Defaulted window."));
      runJob(instance);
      assertThat(ScriptedAgent.requests().get(0).get("chatMemoryMaxMessages")).isEqualTo(53);
      completeFeedback(instance, true, "Done.");
    }
  }

  @Test
  public void propagatesMemoryWindowExpressionEvaluationFailuresBeforeCallingTheConnector() {
    ProcessInstance instance = start("perActivity", null, 2, "${nichtDa.methode()}",
        Collections.<String, Object>emptyMap(), answers("Must not run."));

    assertThatThrownBy(() -> runJob(instance))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Unknown property used in expression: ${nichtDa.methode()}");
    assertThat(ScriptedAgent.turnsTaken()).isZero();
    assertThat(ScriptedAgent.requests()).isEmpty();
  }

  @Test
  public void missingFeedbackIsNotEvaluatedOnFirstEntryAndFallsBackToTaskWithWarningOnReEntry() {
    Logger logger = (Logger) LoggerFactory.getLogger(AgenticTurnRunner.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      ScriptedAgent.install(answers("First entry works."), answers("Fallback works."),
          answers("Feedback works again."));
      ENGINE.getRepositoryService().createDeployment()
          .addString("missingFeedback.bpmn20.xml", model("perActivity", null)).deploy();
      ProcessInstance instance = ENGINE.getRuntimeService()
          .startProcessInstanceByKey("conversationContinuity");
      runJob(instance);
      assertThat(appender.list).as("the first entry never evaluates followUp").isEmpty();

      Task feedbackTask = ENGINE.getTaskService().createTaskQuery()
          .processInstanceId(instance.getId()).taskDefinitionKey("feedback").singleResult();
      ENGINE.getTaskService().complete(feedbackTask.getId(),
          Collections.<String, Object>singletonMap("satisfied", false));
      assertThat(appender.list).hasSize(1);
      ILoggingEvent warning = appender.list.get(0);
      assertThat(warning.getLevel()).isEqualTo(Level.WARN);
      assertThat(warning.getFormattedMessage())
          .contains("Ad hoc sub process 'adHoc'", instance.getId(),
              "'followUp' on re-entry failed", "using the task ('message') instead");
      assertThat(warning.getThrowableProxy()).isNotNull();
      assertThat(ENGINE.getTaskService().createTaskQuery().taskId(feedbackTask.getId()).count())
          .as("fallback allows feedback completion to commit").isZero();
      ENGINE.getRuntimeService().setVariable(instance.getId(), "feedback", "Set after entry.");
      runJob(instance);
      List<Map<String, Object>> requests = ScriptedAgent.requests();
      assertThat(requests).hasSize(2);
      assertThat(requests.get(1).get("message")).isEqualTo("Do the work.");
      assertThat(requests.get(1).get("memoryId")).isEqualTo(requests.get(0).get("memoryId"));
      assertThat(appender.list).as("evaluation is not retried by the turn job").hasSize(1);
      completeFeedback(instance, false, "Valid feedback.");
      runJob(instance);
      assertThat(ScriptedAgent.requests().get(2).get("message")).isEqualTo("Valid feedback.");
      completeFeedback(instance, true, "Done.");
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void followUpIsFrozenBeforeTheFirstReEntryJobRuns() {
    ProcessInstance instance = start("perActivity", null, answers("First."), answers("Second."));
    runJob(instance);
    completeFeedback(instance, false, "Original feedback.");
    ENGINE.getRuntimeService().setVariable(instance.getId(), "feedback", "Changed too late.");
    runJob(instance);
    assertThat(ScriptedAgent.requests().get(1).get("message")).isEqualTo("Original feedback.");
    completeFeedback(instance, true, "Done.");
  }

  @Test
  public void sequentialIterationsShareConversationAndTotalAndUseFollowUp() {
    ScriptedAgent.install(answers("First iteration."), parameters -> {
      ExecutionEntity scope = Context.getBpmnExecutionContext().getExecution();
      assertThat(AdHocConversation.entries(scope)).isEqualTo(2);
      assertThat(AdHocConversation.turnsTotal(scope)).isEqualTo(2);
      return "Second iteration.";
    });
    String sequential = model("perActivity", 2)
        .replace("<userTask id='worker' name='Worker' />",
            "<multiInstanceLoopCharacteristics isSequential='true'>"
            + "<loopCardinality>2</loopCardinality></multiInstanceLoopCharacteristics>"
            + "<userTask id='worker' name='Worker' />");
    ENGINE.getRepositoryService().createDeployment()
        .addString("sequentialConversation.bpmn20.xml", sequential).deploy();
    ProcessInstance instance = ENGINE.getRuntimeService().startProcessInstanceByKey(
        "conversationContinuity", Collections.<String, Object>singletonMap("feedback", "Next item."));
    runJob(instance);
    runJob(instance);
    List<Map<String, Object>> requests = ScriptedAgent.requests();
    assertThat(requests).hasSize(2);
    assertThat(requests.get(0).get("message")).isEqualTo("Do the work.");
    assertThat(requests.get(1).get("message")).isEqualTo("Next item.");
    assertThat(requests.get(1).get("memoryId")).isEqualTo(requests.get(0).get("memoryId"));
    completeFeedback(instance, true, "Done.");
  }

  /**
   * Without {@code perActivity} -- the default -- a re-entry is a new conversation, as it
   * always was, and {@code followUp} never takes over: there is no earlier turn for it to
   * continue.
   */
  @Test
  public void perEntryIgnoresFollowUpAndStartsANewConversationEachTime() {
    ProcessInstance instance =
        start(null, null, answers("Working on it."), answers("Still working on it."));

    runJob(instance);
    completeFeedback(instance, false, "Please be shorter.");
    runJob(instance);
    completeFeedback(instance, true, "Good, thanks.");

    List<Map<String, Object>> requests = ScriptedAgent.requests();
    assertThat(requests).hasSize(2);
    assertThat(requests.get(1).get("message"))
        .as("perEntry: the task leads again, not the feedback").isEqualTo("Do the work.");
    assertThat(requests.get(1).get("memoryId"))
        .as("a new conversation for the new entry")
        .isNotEqualTo(requests.get(0).get("memoryId"));
  }

  /**
   * {@code followUp} only replaces the task on the first turn of a re-entry. A further turn of
   * that same entry -- reached here because the first turn starts the scope's waiting child
   * instead of answering -- sees the task again, exactly as it would without a re-entry.
   */
  @Test
  public void followUpOnlyReplacesTheTaskOnTheFirstTurnOfAReEntry() {
    ProcessInstance instance = start("perActivity", null,
        answers("Working on it."),
        startsWorkerAndAnswers("On it, one more step."),
        answers("Done."));

    runJob(instance);
    completeFeedback(instance, false, "Please be shorter.");
    runJob(instance); // entry 2, turn 1: starts "worker", stays open
    completeWorker(instance);
    runJob(instance); // entry 2, turn 2
    completeFeedback(instance, true, "Good, thanks.");

    List<Map<String, Object>> requests = ScriptedAgent.requests();
    assertThat(requests).hasSize(3);
    assertThat(requests.get(1).get("message"))
        .as("entry 2, turn 1: the feedback").isEqualTo("Please be shorter.");
    assertThat(requests.get(2).get("message"))
        .as("entry 2, turn 2: the task again, as without a re-entry").isEqualTo("Do the work.");
  }

  // --- the total-turn cap -----------------------------------------------------------

  /**
   * The cap across every entry together ends the scope on the entry that would exceed it,
   * without spending a further model call -- the model is asked exactly {@code maxTurnsTotal}
   * times, never a {@code maxTurnsTotal + 1}th.
   */
  @Test
  public void maxTurnsTotalEndsTheLoopWithoutAskingTheModelAgain() {
    ProcessInstance instance =
        start("perActivity", Integer.valueOf(2), answers("Working on it."), answers("Again."));

    runJob(instance); // entry 1, turn 1 -- turnsTotal = 1
    completeFeedback(instance, false, "fb1");
    runJob(instance); // entry 2, turn 1 -- turnsTotal = 2, at the cap but not over it
    completeFeedback(instance, false, "fb2");
    runJob(instance); // entry 3, turn 1 -- turnsTotal = 3, over the cap: ends without a call

    assertThat(ScriptedAgent.turnsTaken())
        .as("the model was asked twice, not a third time").isEqualTo(2);
    assertThat(outputOf(instance)).contains("total turn limit of 2");

    // The scope ended on the cap, same as the model ending it: flow continues to "feedback".
    completeFeedback(instance, true, "done");
    assertThat(ENGINE.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(instance.getId()).singleResult()).isNull();
  }
}
