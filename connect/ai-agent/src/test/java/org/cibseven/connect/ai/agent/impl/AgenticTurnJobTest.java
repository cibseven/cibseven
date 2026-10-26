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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.el.ELResolver;

import org.cibseven.bpm.application.ProcessApplicationExecutionException;
import org.cibseven.bpm.application.ProcessApplicationInterface;
import org.cibseven.bpm.application.impl.EmbeddedProcessApplication;
import org.cibseven.bpm.engine.OptimisticLockingException;
import org.cibseven.bpm.engine.delegate.BpmnError;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.Expression;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.history.HistoricVariableInstance;
import org.cibseven.bpm.engine.history.HistoricVariableUpdate;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.el.ReadOnlyMapELResolver;
import org.cibseven.bpm.engine.impl.jobexecutor.JobExecutor;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.repository.DeploymentBuilder;
import org.cibseven.bpm.engine.impl.util.ClockUtil;
import org.cibseven.bpm.engine.impl.variable.serializer.AbstractObjectValueSerializer;
import org.cibseven.bpm.engine.impl.variable.serializer.TypedValueSerializer;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.runtime.VariableInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.ObjectValue;
import org.cibseven.connect.ai.agent.AgentConnectorConstants;
import org.cibseven.connect.ai.agent.agentic.AdHocAgentState;
import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The turn as a job: the agent is asked, what it starts runs, and what it does at the end of a
 * turn decides whether the scope lives on.
 *
 * <p>The model is a stub that answers from a script, so each test is a sequence of steps rather
 * than a race, and no language model is called.
 */
public class AgenticTurnJobTest {

  private static final int STUB_PORT = 8099;

  private HttpServer stub;
  /** The engine-wide retry cycle the next {@code start} configures; none by default. */
  private String engineRetryCycle;
  /** The default serialization format the next {@code start} configures; the engine's own if null. */
  private String defaultSerializationFormat;
  private final ConcurrentLinkedQueue<String> script = new ConcurrentLinkedQueue<>();
  private ProcessEngine engine;

  @BeforeEach
  public void startStub() throws IOException {
    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", STUB_PORT), 0);
    stub.createContext("/v1/chat/completions", this::respond);
    stub.setExecutor(null);
    stub.start();
  }

  @AfterEach
  public void stopEverything() {
    ClockUtil.reset();
    if (engine != null) {
      engine.close();
      engine = null;
    }
    if (stub != null) {
      stub.stop(0);
      stub = null;
    }
  }

  @Test
  public void aTurnStartsWhatTheAgentAsksFor() {
    // The activity is its own tool: the model names it directly, no id parameter.
    script.add(toolCall("worker", "{}"));
    script.add(answer("Started."));
    ProcessInstance pi = start();

    runWaitingTurn();

    assertThat(engine.getTaskService().createTaskQuery().list()).hasSize(1);
    assertThat(alive(pi)).isTrue();
  }

  /**
   * A tenant's turn job belongs to the tenant and names its process definition (CIB7-2149), the one
   * created on entry and the one a step schedules for the next: a job query by tenant or by process
   * definition finds it. A bare job would carry neither. Its job definition is CIB7-2210.
   */
  @Test
  public void aTenantsTurnJobCarriesItsTenantAndProcessDefinition() {
    script.add(toolCall("worker", "{}"));
    ProcessInstance pi = start(model(), Collections.<String, Object>emptyMap(), null, "t1");

    Job onEntry = engine.getManagementService().createJobQuery().singleResult();
    assertTurnJobOf(onEntry, pi, "t1");

    engine.getManagementService().executeJob(onEntry.getId());
    Job nextStep = engine.getManagementService().createJobQuery().singleResult();
    assertThat(nextStep.getId()).as("the step called a tool, so a further step waits")
        .isNotEqualTo(onEntry.getId());
    assertTurnJobOf(nextStep, pi, "t1");

    assertThat(engine.getManagementService().createJobQuery().tenantIdIn("t1").count()).isOne();
    assertThat(engine.getManagementService().createJobQuery()
        .processDefinitionKey("agenticTurn").count()).isOne();
  }

  private void assertTurnJobOf(Job job, ProcessInstance pi, String tenantId) {
    assertThat(job.getTenantId()).as("the turn job's tenant").isEqualTo(tenantId);
    assertThat(job.getProcessDefinitionId()).as("its process definition")
        .isEqualTo(pi.getProcessDefinitionId());
    assertThat(job.getProcessDefinitionKey()).as("its process definition's key")
        .isEqualTo("agenticTurn");
  }

  @Test
  public void aFurtherTurnComesAtTheEndOfTheChain() {
    script.add(toolCall("worker", "{}"));
    script.add(answer("Started."));
    ProcessInstance pi = start();
    runWaitingTurn();

    // 'worker' flows on to 'second', so finishing it is not the end of the work the agent
    // started -- waking here would show it a half-finished chain.
    complete("Worker");
    assertThat(engine.getManagementService().createJobQuery().list()).isEmpty();
    assertThat(engine.getTaskService().createTaskQuery().taskName("Second").singleResult())
        .isNotNull();

    complete("Second");
    assertThat(engine.getManagementService().createJobQuery().list()).hasSize(1);
    assertThat(alive(pi)).isTrue();
  }

  protected void complete(String taskName) {
    engine.getTaskService().complete(
        engine.getTaskService().createTaskQuery().taskName(taskName).singleResult().getId());
  }

  @Test
  public void aTurnThatStartsNothingEndsTheScope() {
    script.add(answer("There is nothing to do."));
    ProcessInstance pi = start();

    runWaitingTurn();

    assertThat(alive(pi)).isFalse();
    assertThat(engine.getManagementService().createJobQuery().list()).isEmpty();
  }

  @Test
  public void theAnswerIsWrittenToTheConfiguredVariable() {
    script.add(answer("All done."));
    ProcessInstance pi = start();

    runWaitingTurn();

    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("summary").singleResult().getValue())
        .isEqualTo("All done.");
  }

  @Test
  public void theAgentIsOfferedOnlyWhatTheEngineWillStart() {
    script.add(answer("Seen."));
    start();

    runWaitingTurn();

    // 'second' sits behind an inner sequence flow, so the engine refuses to start it
    // directly and the tool list must not offer it. The tools travel in the request
    // body, which is where the stub sees them; whitespace-normalised, because the
    // client pretty-prints.
    String body = lastToolResult().replaceAll("\\s", "");
    assertThat(body)
        .contains("\"name\":\"worker\"")
        .contains("\"name\":\"completeScope\"")
        .doesNotContain("\"name\":\"second\"")
        .doesNotContain("startActivity")
        .doesNotContain("listAvailableActivities");
  }

  // --- the agent's connector on the scope

  /**
   * The connector's input parameters are evaluated on every turn, as a service task's are when it
   * runs: process data changed between two turns reaches the second one.
   */
  @Test
  public void theConnectorsMessageIsEvaluatedOnEveryTurn() {
    script.add(toolCall("worker", "{}"));
    script.add(answer("Started."));
    script.add(answer("Done."));
    ProcessInstance pi = start(connectorModel(
        "<camunda:inputParameter name='message'>Bearbeite Bestellung ${bestellNr}.</camunda:inputParameter>",
        ""), Collections.<String, Object>singletonMap("bestellNr", "4711"));

    runWaitingTurn();
    assertThat(lastToolResult()).contains("Bearbeite Bestellung 4711.");
    assertThat(engine.getRuntimeService().getVariableLocal(
        engine.getRuntimeService().createExecutionQuery().activityId("adHoc").singleResult().getId(),
        "message"))
        .as("nothing of the connector's input is stored on the scope").isNull();

    engine.getRuntimeService().setVariable(pi.getId(), "bestellNr", "4712");
    complete("Worker");
    runWaitingTurn();

    assertThat(lastToolResult())
        .as("the second turn evaluated the message again")
        .contains("Bearbeite Bestellung 4712.");
  }

  @Test
  public void theConnectorsInstructionIsTheSystemPrompt() {
    script.add(answer("Nothing to do."));
    start(connectorModel(
        "<camunda:inputParameter name='message'>Pruefe die Rechnung.</camunda:inputParameter>"
        + "<camunda:inputParameter name='instruction'>Du bist ein genauer Pruefer.</camunda:inputParameter>",
        ""), Collections.<String, Object>emptyMap());

    runWaitingTurn();

    assertThat(lastToolResult().replaceAll("\\s", ""))
        .contains("\"role\":\"system\"")
        .contains("Dubisteingenauer");
  }

  @Test
  public void theConnectorsOutputParametersTakeTheAnswer() {
    script.add(answer("Freigegeben."));
    ProcessInstance pi = start(connectorModel(
        "<camunda:inputParameter name='message'>Entscheide.</camunda:inputParameter>",
        "<camunda:outputParameter name='entscheidung'>${output}</camunda:outputParameter>"),
        Collections.<String, Object>emptyMap());

    runWaitingTurn();

    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("entscheidung").singleResult().getValue())
        .isEqualTo("Freigegeben.");
    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("agentOutput").count())
        .as("the output parameters decide where the answer goes").isZero();
  }

  @Test
  public void theApiKeyMayBeAnExpression() {
    script.add(answer("Nothing to do."));
    start(connectorModel(
        "<camunda:inputParameter name='message'>Pruefe.</camunda:inputParameter>",
        "", "${schluessel}"), Collections.<String, Object>singletonMap("schluessel", "stub-key-4711"));

    runWaitingTurn();

    assertThat(lastAuthorization).isEqualTo("Bearer stub-key-4711");
  }

  /**
   * Only the two limits are read from the scope's properties. A property of the earlier form next
   * to the connector changes nothing: not the model asked, and not whether the scope is an agent.
   */
  @Test
  public void aPropertyOtherThanTheLimitsIsNotRead() {
    script.add(answer("Nothing to do."));
    start(connectorModel("<camunda:properties>"
        + "<camunda:property name='cibseven.agentic.model' value='andere' />"
        + "<camunda:property name='cibseven.agentic.enabled' value='false' />"
        + "</camunda:properties>",
        "<camunda:inputParameter name='message'>Pruefe.</camunda:inputParameter>", "", "stub-key"),
        Collections.<String, Object>emptyMap());

    runWaitingTurn();

    assertThat(lastToolResult().replaceAll("\\s", ""))
        .as("the connector's model is asked").contains("\"model\":\"stub\"")
        .doesNotContain("andere");
  }

  /**
   * The output parameters are applied after every turn, as a service task applies them after its
   * one run: the last turn's answer is what the process finds.
   */
  @Test
  public void theLastTurnsAnswerWins() {
    script.add(toolCall("worker", "{}"));
    script.add(answer("Gestartet."));
    script.add(answer("Erledigt."));
    ProcessInstance pi = start(connectorModel(
        "<camunda:inputParameter name='message'>Entscheide.</camunda:inputParameter>",
        "<camunda:outputParameter name='antwort'>${output}</camunda:outputParameter>"
        + "<camunda:outputParameter name='kiErzeugt'>${outputAiMeta.aiGenerated}"
        + "</camunda:outputParameter>"), Collections.<String, Object>emptyMap());

    runWaitingTurn();
    assertThat(engine.getRuntimeService().getVariable(pi.getId(), "antwort"))
        .as("after the first turn").isEqualTo("Gestartet.");
    assertThat(engine.getRuntimeService().getVariable(pi.getId(), "kiErzeugt"))
        .as("the answer's AI marker, through EL").isEqualTo(Boolean.TRUE);

    complete("Worker");
    runWaitingTurn();

    assertThat(alive(pi)).isFalse();
    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("antwort").singleResult().getValue())
        .as("after the last turn").isEqualTo("Erledigt.");
  }

  /** The answer the loop writes itself when the turn cap is reached goes the same way. */
  @Test
  public void theTurnCapsAnswerTakesTheOutputParameters() {
    script.add(toolCall("worker", "{}"));
    script.add(answer("Gestartet."));
    ProcessInstance pi = start(connectorModel("<camunda:properties>"
        + "<camunda:property name='cibseven.agentic.maxTurns' value='1' />"
        + "</camunda:properties>",
        "<camunda:inputParameter name='message'>Entscheide.</camunda:inputParameter>",
        "<camunda:outputParameter name='antwort'>${output}</camunda:outputParameter>",
        "stub-key"), Collections.<String, Object>emptyMap());

    runWaitingTurn();
    complete("Worker");
    runWaitingTurn();

    assertThat(alive(pi)).isFalse();
    assertThat(String.valueOf(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("antwort").singleResult().getValue()))
        .contains("turn limit of 1");
    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("agentOutput").count())
        .as("the output parameters decide, for the loop's own answer too").isZero();
  }

  /** The loop's own answer says that no model wrote it, to a marker the output parameters set. */
  @Test
  public void theTurnCapsAnswerIsNotMarkedAsAiGenerated() {
    script.add(toolCall("worker", "{}"));
    script.add(answer("Gestartet."));
    ProcessInstance pi = start(connectorModel("<camunda:properties>"
        + "<camunda:property name='cibseven.agentic.maxTurns' value='1' />"
        + "</camunda:properties>",
        "<camunda:inputParameter name='message'>Entscheide.</camunda:inputParameter>",
        "<camunda:outputParameter name='kiErzeugt'>${outputAiMeta.aiGenerated}</camunda:outputParameter>",
        "stub-key"), Collections.<String, Object>emptyMap());

    runWaitingTurn();
    assertThat(engine.getRuntimeService().getVariable(pi.getId(), "kiErzeugt"))
        .as("the model's answer").isEqualTo(Boolean.TRUE);
    complete("Worker");
    runWaitingTurn();

    assertThat(alive(pi)).isFalse();
    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("kiErzeugt").singleResult().getValue())
        .as("the loop's answer at the turn cap").isEqualTo(Boolean.FALSE);
  }

  /**
   * The loop keeps one conversation per scope across its turns, whatever the connector says: the
   * second turn's request carries the first turn's answer, and the conversation sits on the agent
   * state, under the scope's memory id, rather than where the tools could rewrite it.
   */
  @Test
  public void theLoopKeepsOneConversationPerScopeAcrossTurns() {
    script.add(toolCall("worker", "{}"));
    script.add(answer("Gestartet."));
    script.add(answer("Fertig."));
    ProcessInstance pi = start(connectorModel(
        "<camunda:inputParameter name='message'>Erledige die Arbeit.</camunda:inputParameter>",
        ""), Collections.<String, Object>emptyMap());
    String scope = engine.getRuntimeService().createExecutionQuery().activityId("adHoc")
        .singleResult().getId();

    runWaitingTurn();
    String memory = AgentConnectorConstants.AGENT_CONNECTOR_MEMORY_PREFIX + "adhoc-" + scope;
    VariableInstance conversation = engine.getRuntimeService()
        .createVariableInstanceQuery().variableName(memory).singleResult();
    assertThat(conversation).as("the conversation under the scope's memory id").isNotNull();
    assertThat(conversation.getExecutionId())
        .as("on the agent state, neither on the scope nor on the process instance")
        .isNotIn(scope, pi.getId());

    complete("Worker");
    runWaitingTurn();

    assertThat(lastToolResult()).as("the second turn's request carries the first turn's answer")
        .contains("Gestartet.");
  }

  /**
   * The connector refuses a request without a name, and an expression for the name can evaluate
   * to nothing. The scope's id stands in then, as it does when no name is configured at all.
   */
  @Test
  public void anAgentNameThatEvaluatesToNothingFallsBackToTheScopesId() {
    script.add(answer("Nothing to do."));
    ProcessInstance pi = start(connectorModel(
        "<camunda:inputParameter name='message'>Pruefe die Rechnung.</camunda:inputParameter>"
        + "<camunda:inputParameter name='agentName'>${label}</camunda:inputParameter>",
        ""), Collections.<String, Object>singletonMap("label", ""));

    runWaitingTurn();

    assertThat(lastToolResult()).contains("Pruefe die Rechnung.");
    assertThat(alive(pi)).as("the turn ran and, starting nothing, ended the scope").isFalse();
  }

  // --- how a turn ended, for the process (CIB7-2151)

  protected static final String MESSAGE =
      "<camunda:inputParameter name='message'>Entscheide.</camunda:inputParameter>";

  /** Two tools that wait for a person, so a turn can end while one of them still runs. */
  protected static final String TWO_WAITING =
      "      <userTask id='a' name='A' />      <userTask id='b' name='B' />";

  /** A boundary event on the scope that catches every error, its code kept, and a path on. */
  protected static final String LIMIT_BOUNDARY =
      "    <boundaryEvent id='limitReached' attachedToRef='adHoc'>"
      + "<errorEventDefinition camunda:errorCodeVariable='limitCode' /></boundaryEvent>"
      + "    <sequenceFlow id='f3' sourceRef='limitReached' targetRef='afterLimit' />"
      + "    <userTask id='afterLimit' name='After limit' />"
      + "    <sequenceFlow id='f4' sourceRef='afterLimit' targetRef='end' />";

  protected static String limits(String... properties) {
    StringBuilder xml = new StringBuilder("<camunda:properties>");
    for (int i = 0; i + 1 < properties.length; i += 2) {
      xml.append("<camunda:property name='cibseven.agentic.").append(properties[i])
          .append("' value='").append(properties[i + 1]).append("' />");
    }
    return xml.append("</camunda:properties>").toString();
  }

  protected Object finalValue(ProcessInstance pi, String variable) {
    return engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName(variable).singleResult().getValue();
  }

  @Test
  public void anAnswerThatLeavesNothingRunningEndsTheScopeWithOutcomeEnded() {
    script.add(answer("Nichts zu tun."));
    ProcessInstance pi = start(connectorModel(MESSAGE, ""), Collections.<String, Object>emptyMap());

    runWaitingTurn();

    assertThat(alive(pi)).isFalse();
    assertThat(finalValue(pi, "agentOutput")).isEqualTo("Nichts zu tun.");
    assertThat(finalValue(pi, "agentOutcome"))
        .as("without output parameters the outcome goes beside the answer").isEqualTo("ended");
  }

  @Test
  public void completeScopeIsOutcomeCompleted() {
    script.add(toolCall("completeScope", "{}"));
    script.add(answer("Abgeschlossen."));
    ProcessInstance pi = startSteps();

    runWaitingTurn();

    assertThat(alive(pi)).isFalse();
    assertThat(finalValue(pi, "agentOutcome")).isEqualTo("completed");
  }

  /** An answer while work runs is "waiting"; the turn that work's end brings writes its own. */
  @Test
  public void anAnswerWhileWorkRunsIsOutcomeWaitingUntilTheNextTurn() {
    script.add(toolCall("worker", "{}"));
    script.add(answer("Gestartet."));
    script.add(answer("Erledigt."));
    ProcessInstance pi = start(connectorModel(MESSAGE, ""), Collections.<String, Object>emptyMap());

    runWaitingTurn();
    assertThat(engine.getRuntimeService().getVariable(pi.getId(), "agentOutcome"))
        .isEqualTo("waiting");

    complete("Worker");
    runWaitingTurn();

    assertThat(alive(pi)).isFalse();
    assertThat(finalValue(pi, "agentOutcome")).isEqualTo("ended");
  }

  @Test
  public void theTurnCapIsOutcomeTurnLimit() {
    script.add(toolCall("worker", "{}"));
    script.add(answer("Gestartet."));
    ProcessInstance pi = start(connectorModel(limits("maxTurns", "1"), MESSAGE, "", "stub-key"),
        Collections.<String, Object>emptyMap());

    runWaitingTurn();
    complete("Worker");
    runWaitingTurn();

    assertThat(alive(pi)).isFalse();
    assertThat(finalValue(pi, "agentOutcome")).isEqualTo("turnLimit");
    assertThat(String.valueOf(finalValue(pi, "agentOutput"))).contains("turn limit of 1");
  }

  @Test
  public void theModelCallCapIsOutcomeCallLimit() {
    script.add(toolCall("rechnen", "{}"));
    ProcessInstance pi = start(connectorModel(limits("maxModelCalls", "1"), MESSAGE, "", "stub-key",
        SYNC_AND_WAITING), Collections.<String, Object>emptyMap());

    runWaitingTurn();

    assertThat(requests.get()).as("the second step stops before the model").isEqualTo(1);
    assertThat(alive(pi)).isFalse();
    assertThat(finalValue(pi, "agentOutcome")).isEqualTo("callLimit");
  }

  @Test
  public void theTotalTurnCapIsOutcomeTotalTurnLimit() {
    script.add(toolCall("worker", "{}"));
    script.add(answer("Gestartet."));
    ProcessInstance pi = start(connectorModel(
        limits("conversation", "perActivity", "maxTurnsTotal", "1"), MESSAGE, "", "stub-key"),
        Collections.<String, Object>emptyMap());

    runWaitingTurn();
    complete("Worker");
    runWaitingTurn();

    assertThat(alive(pi)).isFalse();
    assertThat(finalValue(pi, "agentOutcome")).isEqualTo("totalTurnLimit");
  }

  @Test
  public void theOutcomeTakesTheOutputParameters() {
    script.add(answer("Fertig."));
    ProcessInstance pi = start(connectorModel(MESSAGE,
        "<camunda:outputParameter name='ausgang'>${outcome}</camunda:outputParameter>"),
        Collections.<String, Object>emptyMap());

    runWaitingTurn();

    assertThat(finalValue(pi, "ausgang")).isEqualTo("ended");
    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("agentOutcome").count())
        .as("the output parameters decide, for the outcome too").isZero();
  }

  /** With onLimit = error the limit ends the scope through its boundary event, code and all. */
  @Test
  public void aLimitWithOnLimitErrorTakesTheBoundaryEvent() {
    script.add(toolCall("rechnen", "{}"));
    ProcessInstance pi = start(connectorModel(limits("maxModelCalls", "1", "onLimit", "error"),
        MESSAGE, "", "stub-key", SYNC_AND_WAITING, LIMIT_BOUNDARY),
        Collections.<String, Object>emptyMap());

    runWaitingTurn();

    assertThat(alive(pi)).isTrue();
    assertThat(engine.getTaskService().createTaskQuery().singleResult().getName())
        .isEqualTo("After limit");
    assertThat(engine.getRuntimeService().getVariable(pi.getId(), "limitCode"))
        .isEqualTo("callLimit");
    assertThat(engine.getRuntimeService().getVariable(pi.getId(), "agentOutcome"))
        .as("the answer and the outcome are written before the error").isEqualTo("callLimit");
    assertThat(engine.getManagementService().createJobQuery().list()).isEmpty();
  }

  /**
   * Over the turn cap with work still running, the error waits for that work, as the scope's end
   * would (CIB7-2144): no task a person may be halfway through is cancelled by a limit.
   */
  @Test
  public void aTurnCapWithOnLimitErrorWaitsForRunningWork() {
    script.add(toolCall("a", "{}"));
    script.add(toolCall("b", "{}"));
    script.add(answer("Beide laufen."));
    ProcessInstance pi = start(connectorModel(limits("maxTurns", "1", "onLimit", "error"),
        MESSAGE, "", "stub-key", TWO_WAITING, LIMIT_BOUNDARY),
        Collections.<String, Object>emptyMap());

    runWaitingTurn();
    complete("A");
    runWaitingTurn();

    assertThat(engine.getTaskService().createTaskQuery().singleResult().getName())
        .as("over the cap, B still runs and no error was thrown").isEqualTo("B");
    assertThat(engine.getRuntimeService().getVariable(pi.getId(), "agentOutcome"))
        .isEqualTo("turnLimit");

    complete("B");
    runWaitingTurn();

    assertThat(engine.getTaskService().createTaskQuery().singleResult().getName())
        .isEqualTo("After limit");
    assertThat(engine.getRuntimeService().getVariable(pi.getId(), "limitCode"))
        .isEqualTo("turnLimit");
  }

  /** Without onLimit = error a limit ends the scope as before, on its normal path. */
  @Test
  public void aLimitWithoutOnLimitErrorLeavesTheBoundaryEventAlone() {
    script.add(toolCall("rechnen", "{}"));
    ProcessInstance pi = start(connectorModel(limits("maxModelCalls", "1"),
        MESSAGE, "", "stub-key", SYNC_AND_WAITING, LIMIT_BOUNDARY),
        Collections.<String, Object>emptyMap());

    runWaitingTurn();

    assertThat(alive(pi)).isFalse();
    assertThat(finalValue(pi, "agentOutcome")).isEqualTo("callLimit");
    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("limitCode").count()).isZero();
  }

  @Test
  public void theTotalTurnCapWithOnLimitErrorTakesTheBoundaryEvent() {
    script.add(toolCall("worker", "{}"));
    script.add(answer("Gestartet."));
    ProcessInstance pi = start(connectorModel(limits("conversation", "perActivity",
        "maxTurnsTotal", "1", "onLimit", "error"), MESSAGE, "", "stub-key",
        "      <userTask id='worker' name='Worker' />", LIMIT_BOUNDARY),
        Collections.<String, Object>emptyMap());

    runWaitingTurn();
    complete("Worker");
    runWaitingTurn();

    assertThat(engine.getTaskService().createTaskQuery().singleResult().getName())
        .isEqualTo("After limit");
    assertThat(engine.getRuntimeService().getVariable(pi.getId(), "limitCode"))
        .isEqualTo("totalTurnLimit");
  }

  /**
   * The model-call cap while work still runs ends the turn, not the scope, so no error yet: the
   * end of that work brings a turn that asks the model again, and a turn that ends without a
   * limit ends the scope on its normal path.
   */
  @Test
  public void theModelCallCapWhileWorkRunsThrowsNothingAndTheNextTurnAsksAgain() {
    script.add(toolCall("a", "{}"));
    script.add(toolCall("b", "{}"));
    script.add(answer("Weiter."));
    script.add(answer("Fertig."));
    ProcessInstance pi = start(connectorModel(limits("maxModelCalls", "2", "onLimit", "error"),
        MESSAGE, "", "stub-key", TWO_WAITING, LIMIT_BOUNDARY),
        Collections.<String, Object>emptyMap());

    runWaitingTurn();
    assertThat(requests.get()).as("the third step stops before the model").isEqualTo(2);
    assertThat(engine.getRuntimeService().getVariable(pi.getId(), "agentOutcome"))
        .isEqualTo("callLimit");
    assertThat(engine.getTaskService().createTaskQuery().count())
        .as("A and B still run, and no error was thrown").isEqualTo(2);

    complete("A");
    runWaitingTurn();
    assertThat(requests.get()).as("the next turn asks the model again").isEqualTo(3);
    assertThat(engine.getRuntimeService().getVariable(pi.getId(), "agentOutcome"))
        .isEqualTo("waiting");

    complete("B");
    runWaitingTurn();
    assertThat(alive(pi)).isFalse();
    assertThat(finalValue(pi, "agentOutcome")).isEqualTo("ended");
    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("limitCode").count()).isZero();
  }

  /**
   * completeScope while something someone else started still runs: the outcome is "completed"
   * at once, and the turn that work's end brings ends the scope without asking the model or
   * writing another outcome.
   */
  @Test
  public void completedWhileOtherWorkRunsStaysCompletedUntilTheScopeEnds() {
    script.add(toolCall("completeScope", "{}"));
    script.add(answer("Abgeschlossen."));
    ProcessInstance pi = start(connectorModel(MESSAGE, ""), Collections.<String, Object>emptyMap());
    String scope = engine.getRuntimeService().createExecutionQuery().activityId("adHoc")
        .singleResult().getId();
    engine.getRuntimeService().createAdHocSubProcessActivation(scope)
        .startActivity("worker").execute();

    runWaitingTurn();
    assertThat(alive(pi)).as("the worker someone else started keeps the scope").isTrue();
    assertThat(engine.getRuntimeService().getVariable(pi.getId(), "agentOutcome"))
        .isEqualTo("completed");

    complete("Worker");
    runWaitingTurn();

    assertThat(alive(pi)).isFalse();
    assertThat(requests.get()).as("no model call after the request to end").isEqualTo(2);
    assertThat(finalValue(pi, "agentOutcome")).isEqualTo("completed");
  }

  /** A boundary event per limit: each limit takes its own path. */
  @Test
  public void aBoundaryEventPerLimitTakesTheMatchingPath() {
    script.add(toolCall("rechnen", "{}"));
    String perLimit =
        "    <boundaryEvent id='onTurnLimit' attachedToRef='adHoc'>"
        + "<errorEventDefinition errorRef='turnLimit' /></boundaryEvent>"
        + "    <sequenceFlow id='t1' sourceRef='onTurnLimit' targetRef='turnLimitTask' />"
        + "    <userTask id='turnLimitTask' name='Turn limit' />"
        + "    <boundaryEvent id='onCallLimit' attachedToRef='adHoc'>"
        + "<errorEventDefinition errorRef='callLimit' /></boundaryEvent>"
        + "    <sequenceFlow id='c1' sourceRef='onCallLimit' targetRef='callLimitTask' />"
        + "    <userTask id='callLimitTask' name='Call limit' />";
    start(connectorModel(limits("maxModelCalls", "1", "onLimit", "error"), MESSAGE, "",
        "stub-key", SYNC_AND_WAITING, perLimit), Collections.<String, Object>emptyMap());

    runWaitingTurn();

    assertThat(engine.getTaskService().createTaskQuery().singleResult().getName())
        .isEqualTo("Call limit");
  }

  /** What the ticket asks for: a gateway after the scope branches on the outcome. */
  @Test
  public void aGatewayAfterTheScopeBranchesOnTheOutcome() {
    script.add(answer("Erledigt."));
    start(withRoute(connectorModel(MESSAGE, "")), Collections.<String, Object>emptyMap());

    runWaitingTurn();

    assertThat(engine.getTaskService().createTaskQuery().singleResult().getName())
        .isEqualTo("Done");
  }

  @Test
  public void aGatewayAfterTheScopeTakesTheOtherPathAtALimit() {
    script.add(toolCall("rechnen", "{}"));
    start(withRoute(connectorModel(limits("maxModelCalls", "1"), MESSAGE, "", "stub-key",
        SYNC_AND_WAITING)), Collections.<String, Object>emptyMap());

    runWaitingTurn();

    assertThat(engine.getTaskService().createTaskQuery().singleResult().getName())
        .isEqualTo("Review");
  }

  /** Out of the scope through a gateway on agentOutcome: ended to Done, anything else to Review. */
  protected static String withRoute(String model) {
    return model.replace("    <sequenceFlow id='f2' sourceRef='adHoc' targetRef='end' />",
        "    <sequenceFlow id='f2' sourceRef='adHoc' targetRef='route' />"
        + "    <exclusiveGateway id='route' default='toReview' />"
        + "    <sequenceFlow id='toDone' sourceRef='route' targetRef='done'>"
        + "<conditionExpression>${agentOutcome == 'ended'}</conditionExpression></sequenceFlow>"
        + "    <sequenceFlow id='toReview' sourceRef='route' targetRef='review' />"
        + "    <userTask id='done' name='Done' />"
        + "    <userTask id='review' name='Review' />"
        + "    <sequenceFlow id='d1' sourceRef='done' targetRef='end' />"
        + "    <sequenceFlow id='r1' sourceRef='review' targetRef='end' />");
  }

  // --- a long final answer (CIB7-2165)

  /** Over the 4000 characters a String variable holds, as the connector's own limit says. */
  protected static final String LONG_ANSWER = repeat("Eine sehr lange Antwort. ", 500);

  protected static String repeat(String text, int times) {
    StringBuilder all = new StringBuilder();
    for (int i = 0; i < times; i++) {
      all.append(text);
    }
    return all.toString().trim();
  }

  @Test
  public void aLongAnswerIsKeptWholeInAgentOutput() {
    script.add(answer(LONG_ANSWER));
    ProcessInstance pi = start(connectorModel(MESSAGE, ""), Collections.<String, Object>emptyMap());

    runWaitingTurn();

    assertThat(alive(pi)).as("the turn ended, without an incident").isFalse();
    assertThat(finalValue(pi, "agentOutput")).isEqualTo(LONG_ANSWER);
  }

  /** The template maps the answer through an output parameter, so this is the usual way. */
  @Test
  public void aLongAnswerIsKeptWholeThroughAnOutputParameter() {
    script.add(answer(LONG_ANSWER));
    ProcessInstance pi = start(connectorModel(MESSAGE,
        "<camunda:outputParameter name='antwort'>${output}</camunda:outputParameter>"),
        Collections.<String, Object>emptyMap());

    runWaitingTurn();

    assertThat(alive(pi)).as("the turn ended, without an incident").isFalse();
    assertThat(finalValue(pi, "antwort")).isEqualTo(LONG_ANSWER);
  }

  /** A variable's history row, its object deserialized or not. */
  protected HistoricVariableInstance historic(ProcessInstance pi, String variable,
      boolean deserialized) {
    org.cibseven.bpm.engine.history.HistoricVariableInstanceQuery query = engine.getHistoryService()
        .createHistoricVariableInstanceQuery().processInstanceId(pi.getId()).variableName(variable);
    return (deserialized ? query : query.disableCustomObjectDeserialization()).singleResult();
  }

  /** Java-serialized, like the chat log, so no engine needs Spin to keep it. */
  @Test
  public void aLongAnswerIsAJavaSerializedObject() {
    script.add(answer(LONG_ANSWER));
    ProcessInstance pi = start(connectorModel(MESSAGE, ""), Collections.<String, Object>emptyMap());

    runWaitingTurn();

    HistoricVariableInstance stored = historic(pi, "agentOutput", false);
    assertThat(stored.getTypeName()).isEqualTo("object");
    assertThat(((ObjectValue) stored.getTypedValue()).getSerializationDataFormat())
        .isEqualTo(Variables.SerializationDataFormats.JAVA.getName());
  }

  /** The column holds 4000 characters here: 4000 stay a String, 4001 do not. */
  @Test
  public void anAnswerOfTheColumnsLengthStaysAString() {
    String fits = repeat("a", 4000);
    script.add(answer(fits));
    ProcessInstance pi = start(connectorModel(MESSAGE, ""), Collections.<String, Object>emptyMap());

    runWaitingTurn();

    HistoricVariableInstance stored = historic(pi, "agentOutput", true);
    assertThat(stored.getTypeName()).isEqualTo("string");
    assertThat(stored.getValue()).isEqualTo(fits);
  }

  @Test
  public void anAnswerOneCharacterOverTheColumnIsAnObject() {
    String over = repeat("a", 4001);
    script.add(answer(over));
    ProcessInstance pi = start(connectorModel(MESSAGE, ""), Collections.<String, Object>emptyMap());

    runWaitingTurn();

    HistoricVariableInstance stored = historic(pi, "agentOutput", true);
    assertThat(stored.getTypeName()).isEqualTo("object");
    assertThat(stored.getValue()).isEqualTo(over);
  }

  @Test
  public void aShortAnswerStaysAString() {
    script.add(answer("Kurz."));
    ProcessInstance pi = start(connectorModel(MESSAGE,
        "<camunda:outputParameter name='antwort'>${output}</camunda:outputParameter>"),
        Collections.<String, Object>emptyMap());

    runWaitingTurn();

    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("antwort").singleResult().getTypeName())
        .isEqualTo("string");
  }

  // --- one model call per step (CIB7-2148)

  /** A tool that runs without waiting, and one that waits for a person. */
  protected static final String SYNC_AND_WAITING =
      "      <serviceTask id='rechnen' name='Rechnen' camunda:expression='${1200}'"
      + " camunda:resultVariable='preis' />"
      + "      <userTask id='worker' name='Worker' />";

  /**
   * As {@link #SYNC_AND_WAITING}, with a tool whose expression reads a variable that does not
   * exist. Calling it fails the step with the engine's own exception (CIB7-2141), whatever a
   * model is told about tools it does not have.
   */
  protected static final String SYNC_WAITING_AND_BROKEN = SYNC_AND_WAITING
      + "      <serviceTask id='kaputt' name='Kaputt' camunda:expression='${nichtDa.methode()}' />";

  /**
   * The CIB7-2135 reproduction, as the ticket and the suite's M05 write it: both parameters
   * declared without a marker, so both are required. Calling it without {@code limit} must be
   * refused before the activity starts, not fail inside it.
   */
  protected static final String SYNC_WITH_REQUIRED_PARAMETER = suche("string");

  /**
   * As {@link #SYNC_WITH_REQUIRED_PARAMETER}, but {@code query} is marked optional: a distinct
   * fixture for a parameter the model may leave out.
   */
  protected static final String SYNC_WITH_OPTIONAL_AND_REQUIRED_PARAMETER = suche("string?");

  /** A tool 'suche' with the parameters query, of the given type, and limit, without a marker. */
  protected static String suche(String queryType) {
    return "      <serviceTask id='suche' name='Suche' camunda:expression='${query}-${limit}'"
        + " camunda:resultVariable='treffer'>"
        + "        <extensionElements><camunda:inputOutput>"
        + "          <camunda:inputParameter name='query'>"
        + "${toolParameter(toolCall.query, 'Suchbegriff', '" + queryType + "')}"
        + "</camunda:inputParameter>"
        + "          <camunda:inputParameter name='limit'>"
        + "${toolParameter(toolCall.limit, 'Maximale Trefferzahl', 'integer')}"
        + "</camunda:inputParameter>"
        + "        </camunda:inputOutput></extensionElements>"
        + "      </serviceTask>";
  }

  protected ProcessInstance startSteps() {
    return start(connectorModel("",
        "<camunda:inputParameter name='message'>Rechne.</camunda:inputParameter>", "",
        "stub-key", SYNC_AND_WAITING), Collections.<String, Object>emptyMap());
  }

  /** As {@link #startSteps()}, with a tool that fails when it is called. */
  protected ProcessInstance startStepsWithABrokenTool() {
    return start(connectorModel("",
        "<camunda:inputParameter name='message'>Rechne.</camunda:inputParameter>", "",
        "stub-key", SYNC_WAITING_AND_BROKEN), Collections.<String, Object>emptyMap());
  }

  /** As {@link #startSteps()}, with {@link #SYNC_WITH_REQUIRED_PARAMETER}'s tool. */
  protected ProcessInstance startStepsWithARequiredParameterTool() {
    return start(connectorModel("",
        "<camunda:inputParameter name='message'>Suche.</camunda:inputParameter>", "",
        "stub-key", SYNC_WITH_REQUIRED_PARAMETER), Collections.<String, Object>emptyMap());
  }

  /** As {@link #startSteps()}, with {@link #SYNC_WITH_OPTIONAL_AND_REQUIRED_PARAMETER}'s tool. */
  protected ProcessInstance startStepsWithAnOptionalParameterTool() {
    return start(connectorModel("",
        "<camunda:inputParameter name='message'>Suche.</camunda:inputParameter>", "",
        "stub-key", SYNC_WITH_OPTIONAL_AND_REQUIRED_PARAMETER),
        Collections.<String, Object>emptyMap());
  }

  protected void runOneJob() {
    Job job = engine.getManagementService().createJobQuery().singleResult();
    engine.getManagementService().executeJob(job.getId());
  }

  /**
   * The agent's own counters and flags, and the pending list, are one map on the marker variable,
   * not a variable each (CIB7-2200): a step writes one variable, and Cockpit shows one readable
   * map, its counts as numbers and the pending list as pairs.
   */
  @Test
  public void theAgentsCountersAndFlagsAreOneMap() {
    script.add(toolCall("worker", "{}"));
    script.add(answer("Gestartet."));
    startSteps();
    runOneJob();

    Object state = engine.getRuntimeService().createVariableInstanceQuery()
        .variableName(AdHocAgentState.STATE_MARKER).singleResult().getValue();
    assertThat(state).isInstanceOf(TreeMap.class);
    Map<?, ?> map = (Map<?, ?>) state;
    assertThat(map.get(AdHocAgentState.LAYOUT_KEY)).isEqualTo(AdHocAgentState.LAYOUT);
    assertThat(map.get("turns")).isEqualTo(1);
    assertThat(map.get("modelCalls")).isEqualTo(1);
    assertThat(map.get("turnInProgress")).isEqualTo(Boolean.TRUE);
    assertThat(map.get("calledThisTurn")).isEqualTo(Collections.singletonList("worker"));
    assertThat(map.get("callsThisAnswer")).isEqualTo(1);
    assertThat((List<?>) map.get("pending")).hasSize(1);
    assertThat(((List<?>) ((List<?>) map.get("pending")).get(0)).get(1)).isEqualTo("worker");
    for (String gone : new String[] {"adHocAgentTurns", "adHocAgentTurnMarker",
        "adHocAgentTurnInProgress", "adHocAgentModelCalls", "adHocAgentModelCallMarker",
        "adHocAgentCalledThisTurn", "adHocAgentCompletionRequested", "adHocAgentPending",
        "adHocAgentCallsThisTurn", "adHocAgentCallsTurnMarker", "adHocAgentResultsRead",
        "adHocAgentResultsReported"}) {
      assertThat(variableType(gone)).as(gone).isNull();
    }
  }

  /**
   * A step writes the state map once, however often it changes it: the turn, the model call, the
   * tool call and the pending list collect, and the map is written when the step's command closes.
   * At history level {@code full} every write is a history row.
   */
  @Test
  public void aStepWritesTheStateMapOnce() {
    script.add(toolCall("worker", "{}"));
    script.add(answer("Gestartet."));
    ProcessInstance pi = startSteps();
    runOneJob();

    long writes = engine.getHistoryService().createHistoricDetailQuery()
        .processInstanceId(pi.getId()).variableUpdates().list().stream()
        .filter(detail -> AdHocAgentState.STATE_MARKER
            .equals(((HistoricVariableUpdate) detail).getVariableName()))
        .count();
    assertThat(writes).as("one when the scope was entered, one by the step").isEqualTo(2);
  }

  private String variableType(String name) {
    VariableInstance variable =
        engine.getRuntimeService().createVariableInstanceQuery().variableName(name).singleResult();
    return (variable == null) ? null : variable.getTypeName();
  }

  /**
   * A model call is a step of its own: the tools it asks for run, the step commits, and the model
   * answers in the next job. A tool that ended within the step schedules nothing besides it.
   */
  @Test
  public void eachModelCallIsAJobOfItsOwn() {
    script.add(toolCall("rechnen", "{}"));
    script.add(answer("Fertig."));
    ProcessInstance pi = startSteps();

    runOneJob();
    assertThat(requests.get()).as("the first step asked the model once").isEqualTo(1);
    assertThat(engine.getManagementService().createJobQuery().list())
        .as("the next step waits, once, though the tool ended within this one").hasSize(1);

    runOneJob();
    assertThat(requests.get()).isEqualTo(2);
    assertThat(alive(pi)).as("the model answered, nothing runs: the scope ended").isFalse();
    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("agentOutput").singleResult().getValue())
        .isEqualTo("Fertig.");
  }

  /**
   * A step that fails replays its own model call and nothing before it: the tool the previous
   * step ran stays run, once.
   */
  @Test
  public void aFailingStepReplaysOnlyItsOwnModelCall() {
    script.add(toolCall("rechnen", "{}"));
    // A tool that fails: the engine's exception ends the step, and the step fails.
    script.add(toolCall("kaputt", "{}"));
    script.add(answer("Fertig."));
    ProcessInstance pi = startStepsWithABrokenTool();
    runOneJob();

    Job second = engine.getManagementService().createJobQuery().singleResult();
    try {
      engine.getManagementService().executeJob(second.getId());
    } catch (RuntimeException expected) {
      // the failed attempt, as the job executor would see it
    }
    runOneJob();

    assertThat(requests.get()).as("one call for the first step, two for the failing one")
        .isEqualTo(3);
    assertThat(engine.getHistoryService().createHistoricActivityInstanceQuery()
        .processInstanceId(pi.getId()).activityId("rechnen").count())
        .as("the tool of the first step ran once").isEqualTo(1);
    assertThat(alive(pi)).isFalse();
  }

  /**
   * A failed step is retried after a pause, not at once (CIB7-2149). Without a cycle on the scope
   * or in the engine the pause is thirty seconds, three attempts in all; the engine counts them
   * and raises the incident after the last. Before, all three came within a fraction of a second.
   */
  @Test
  public void aFailedStepIsRetriedThirtySecondsLaterByDefault() {
    Date now = fixTheClock();
    // A tool that fails: the engine's exception ends the step, and the step fails.
    script.add(toolCall("kaputt", "{}"));
    script.add(toolCall("kaputt", "{}"));
    script.add(toolCall("kaputt", "{}"));
    ProcessInstance pi = startStepsWithABrokenTool();

    Job afterFirst = failTheWaitingStep();
    assertThat(afterFirst.getRetries()).as("R3: two attempts left after the first").isEqualTo(2);
    assertThat(afterFirst.getDuedate()).as("the next attempt waits thirty seconds")
        .isEqualTo(new Date(now.getTime() + 30_000));

    Job afterSecond = failTheWaitingStep();
    assertThat(afterSecond.getRetries()).isEqualTo(1);
    assertThat(afterSecond.getDuedate()).isEqualTo(new Date(now.getTime() + 30_000));

    Job afterThird = failTheWaitingStep();
    assertThat(afterThird.getRetries()).as("the last attempt failed").isZero();
    assertThat(engine.getRuntimeService().createIncidentQuery().processInstanceId(pi.getId()).count())
        .as("and the incident is the engine's").isOne();
  }

  /** The scope's own {@code camunda:failedJobRetryTimeCycle} is the turn's cycle. */
  @Test
  public void theScopesRetryCycleIsTheTurnsCycle() {
    Date now = fixTheClock();
    script.add(toolCall("kaputt", "{}"));
    start(connectorModel("<camunda:failedJobRetryTimeCycle>R5/PT2M</camunda:failedJobRetryTimeCycle>",
        "<camunda:inputParameter name='message'>Rechne.</camunda:inputParameter>", "",
        "stub-key", SYNC_WAITING_AND_BROKEN), Collections.<String, Object>emptyMap());

    Job afterFirst = failTheWaitingStep();
    assertThat(afterFirst.getRetries()).isEqualTo(4);
    assertThat(afterFirst.getDuedate()).isEqualTo(new Date(now.getTime() + 120_000));
  }

  /** Without a cycle on the scope, the engine's {@code failedJobRetryTimeCycle} is the turn's. */
  @Test
  public void theEnginesRetryCycleIsTheDefault() {
    Date now = fixTheClock();
    engineRetryCycle = "R2/PT1M";
    script.add(toolCall("kaputt", "{}"));
    startStepsWithABrokenTool();

    Job afterFirst = failTheWaitingStep();
    assertThat(afterFirst.getRetries()).isEqualTo(1);
    assertThat(afterFirst.getDuedate()).isEqualTo(new Date(now.getTime() + 60_000));
  }

  /** A cycle that cannot be read refuses the deployment, rather than retrying at once later. */
  @Test
  public void aRetryCycleThatCannotBeReadRefusesTheDeployment() {
    assertThatThrownBy(() -> start(connectorModel(
        "<camunda:failedJobRetryTimeCycle>nonsense</camunda:failedJobRetryTimeCycle>",
        "<camunda:inputParameter name='message'>Rechne.</camunda:inputParameter>", "",
        "stub-key", SYNC_AND_WAITING), Collections.<String, Object>emptyMap()))
        .hasMessageContaining("'nonsense' is not a retry cycle for its turns");
  }

  /**
   * Every other job keeps the engine's retry: an asynchronous continuation that fails, outside any
   * agent, is retried at once as before. The plugin decorates the engine's factory and hands such a
   * job back to it.
   */
  @Test
  public void anotherJobKeepsTheEnginesRetry() {
    startSteps();
    engine.getRepositoryService().createDeployment().addString("plain.bpmn20.xml",
        "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + "             targetNamespace='http://cibseven.org/agentic-turn'>"
        + "  <process id='plain' isExecutable='true'>"
        + "    <startEvent id='start' />"
        + "    <sequenceFlow id='f1' sourceRef='start' targetRef='broken' />"
        + "    <serviceTask id='broken' camunda:asyncBefore='true' camunda:expression='${nichtDa}' />"
        + "    <sequenceFlow id='f2' sourceRef='broken' targetRef='end' />"
        + "    <endEvent id='end' />"
        + "  </process>"
        + "</definitions>").deploy();
    ProcessInstance plain = engine.getRuntimeService().startProcessInstanceByKey("plain");

    Job job = engine.getManagementService().createJobQuery().processInstanceId(plain.getId()).singleResult();
    try {
      engine.getManagementService().executeJob(job.getId());
    } catch (RuntimeException expected) {
      // the failed attempt
    }
    Job after = engine.getManagementService().createJobQuery().jobId(job.getId()).singleResult();
    assertThat(after.getRetries()).isEqualTo(2);
    assertThat(after.getDuedate()).as("no retry cycle: the engine retries at once").isNull();
  }

  private Date fixTheClock() {
    Date now = new Date((System.currentTimeMillis() / 1000) * 1000);
    ClockUtil.setCurrentTime(now);
    return now;
  }

  /** Runs the waiting turn job, which fails, and returns it as the engine left it. */
  private Job failTheWaitingStep() {
    // The scope's turn job is the only job of these models.
    Job job = engine.getManagementService().createJobQuery().singleResult();
    try {
      engine.getManagementService().executeJob(job.getId());
    } catch (RuntimeException expected) {
      // the failed attempt, as the job executor would see it
    }
    return engine.getManagementService().createJobQuery().jobId(job.getId()).singleResult();
  }

  /**
   * A tool parameter declared in an input mapping (CIB7-2136): the model is offered it with its
   * type and description, its value reaches the activity through that mapping, and a process
   * variable of the same name is not shadowed. The other argument of the call reaches the activity
   * the same way, through a mapping of its own.
   */
  @Test
  public void aToolParameterInAnInputMappingReachesTheActivityWithoutShadowingTheProcess()
      throws IOException {
    script.add(toolCall("freigeben", "{\\\"betrag\\\":1200,\\\"grund\\\":\\\"Rabatt\\\"}"));
    script.add(answer("Gefragt."));
    ProcessInstance pi = start(connectorModel("",
        "<camunda:inputParameter name='message'>Lass freigeben.</camunda:inputParameter>", "",
        "stub-key",
        "      <userTask id='freigeben' name='Freigeben'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='vorschlag'>"
        + "${toolParameter(toolCall.betrag, 'Betrag in Euro', 'integer')}</camunda:inputParameter>"
        + "<camunda:inputParameter name='bisher'>${betrag}</camunda:inputParameter>"
        + "<camunda:inputParameter name='warum'>${toolParameter(toolCall.grund, 'Begruendung')}"
        + "</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>"),
        Collections.<String, Object>singletonMap("betrag", 5));

    runWaitingTurn();

    assertThat(requestBodies.get(0).replaceAll("\\s", ""))
        .as("the model is offered the parameter with its type and description")
        .contains("\"betrag\":{\"type\":\"integer\",\"description\":\"BetraginEuro\"}");
    Task task = engine.getTaskService().createTaskQuery().taskName("Freigeben").singleResult();
    assertThat(engine.getTaskService().getVariable(task.getId(), "vorschlag"))
        .as("the argument, through the input mapping").isEqualTo(1200L);
    assertThat(engine.getTaskService().getVariable(task.getId(), "bisher"))
        .as("the process variable of the same name, not shadowed").isEqualTo(5);
    assertThat(engine.getTaskService().getVariable(task.getId(), "warum"))
        .as("the other argument, through its own mapping").isEqualTo("Rabatt");
    assertThat(engine.getRuntimeService().getVariable(pi.getId(), "betrag")).isEqualTo(5);
  }

  /**
   * toolCall, the call's and the scope's empty one, is Java-serialized whatever the engine's
   * default format (CIB7-2136): under JSON a plain read in a later command would get an Integer
   * where the call set a Long, and an engine without Spin could not write the map at all.
   */
  @Test
  public void toolCallIsJavaSerializedWhateverTheDefaultFormat() throws IOException {
    defaultSerializationFormat = Variables.SerializationDataFormats.JSON.getName();
    script.add(toolCall("freigeben", "{\\\"betrag\\\":1200}"));
    script.add(answer("Gefragt."));
    start(connectorModel("",
        "<camunda:inputParameter name='message'>Lass freigeben.</camunda:inputParameter>", "",
        "stub-key",
        "      <userTask id='freigeben' name='Freigeben'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='vorschlag'>"
        + "${toolParameter(toolCall.betrag, 'Betrag in Euro', 'integer')}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>"),
        Collections.<String, Object>emptyMap());

    runWaitingTurn();

    List<Object> read = new ArrayList<Object>();
    for (VariableInstance toolCall : engine.getRuntimeService().createVariableInstanceQuery()
        .variableName("toolCall").list()) {
      read.addAll(((java.util.Map<?, ?>) toolCall.getValue()).values());
    }
    assertThat(read).as("the argument read back in a later command, as a plain read gets it")
        .containsExactly(1200L);
    List<VariableInstance> toolCalls = engine.getRuntimeService().createVariableInstanceQuery()
        .variableName("toolCall").disableCustomObjectDeserialization().list();
    assertThat(toolCalls).as("the scope's empty map and the call's").hasSize(2);
    for (VariableInstance toolCall : toolCalls) {
      assertThat(((ObjectValue) toolCall.getTypedValue()).getSerializationDataFormat())
          .isEqualTo(Variables.SerializationDataFormats.JAVA.getName());
    }
  }

  /**
   * The path of an interrupting boundary event on a tool runs where the call's toolCall is
   * visible, so it reads the call's arguments, as the check of misspelt reads assumes there
   * (CIB7-2136). A non-interrupting one is refused inside an ad hoc sub process on this stack.
   */
  @Test
  public void anInterruptingBoundaryPathOfAToolReadsTheCallsArguments() throws IOException {
    script.add(toolCall("anfragen", "{\\\"betrag\\\":1200}"));
    script.add(answer("Gefragt."));
    start(connectorModel("",
        "<camunda:inputParameter name='message'>Frag an.</camunda:inputParameter>", "", "stub-key",
        "      <userTask id='anfragen' name='Anfragen'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='a'>"
        + "${toolParameter(toolCall.betrag, 'Betrag', 'integer')}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>"
        + "<boundaryEvent id='erinnern' attachedToRef='anfragen'><timerEventDefinition>"
        + "<timeDuration>PT1H</timeDuration></timerEventDefinition></boundaryEvent>"
        + "<sequenceFlow id='e1' sourceRef='erinnern' targetRef='mahnen' />"
        + "<userTask id='mahnen' name='Mahnen'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='b'>${toolCall.betrag}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>"),
        Collections.<String, Object>emptyMap());

    // The turn's steps run every job there is, the boundary timer among them.
    runWaitingTurn();

    Task mahnen = engine.getTaskService().createTaskQuery().taskName("Mahnen").singleResult();
    assertThat(engine.getTaskService().getVariable(mahnen.getId(), "b")).isEqualTo(1200L);
  }

  /**
   * A tool's flow across a pair of link events runs where the call's toolCall is visible, so the
   * activity after the catching one reads the call's arguments, as the check of misspelt reads
   * assumes there (CIB7-2136).
   */
  @Test
  public void aToolsFlowAcrossLinkEventsReadsTheCallsArguments() throws IOException {
    script.add(toolCall("anfragen", "{\\\"betrag\\\":1200}"));
    script.add(answer("Gefragt."));
    start(connectorModel("",
        "<camunda:inputParameter name='message'>Frag an.</camunda:inputParameter>", "", "stub-key",
        "      <serviceTask id='anfragen' name='Anfragen' camunda:expression='${1}'>"
        + "<extensionElements><camunda:inputOutput><camunda:inputParameter name='a'>"
        + "${toolParameter(toolCall.betrag, 'Betrag', 'integer')}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></serviceTask>"
        + "<sequenceFlow id='e1' sourceRef='anfragen' targetRef='weg' />"
        + "<intermediateThrowEvent id='weg'><linkEventDefinition name='weiter' />"
        + "</intermediateThrowEvent>"
        + "<intermediateCatchEvent id='hier'><linkEventDefinition name='weiter' />"
        + "</intermediateCatchEvent>"
        + "<sequenceFlow id='e2' sourceRef='hier' targetRef='pruefen' />"
        + "<userTask id='pruefen' name='Pruefen'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='b'>${toolCall.betrag}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>"),
        Collections.<String, Object>emptyMap());

    runWaitingTurn();

    Task pruefen = engine.getTaskService().createTaskQuery().taskName("Pruefen").singleResult();
    assertThat(engine.getTaskService().getVariable(pruefen.getId(), "b")).isEqualTo(1200L);
  }

  /** A tool without parameters gets no toolCall and no variable of its own from the call. */
  @Test
  public void aToolWithoutParametersGetsNoToolCall() throws IOException {
    script.add(toolCall("notieren", "{}"));
    script.add(answer("Notiert."));
    startWith("      <userTask id='notieren' name='Notieren' />");

    runWaitingTurn();

    Task task = engine.getTaskService().createTaskQuery().taskName("Notieren").singleResult();
    assertThat(engine.getTaskService().getVariables(task.getId())).doesNotContainKey("toolCall");
  }

  /**
   * The argument map is read back in a later command, as a person's form or a job does, without
   * being written again: its serialized form does not depend on how it was built.
   */
  @Test
  public void theArgumentsReadInALaterCommandAreNotWrittenAgain() throws IOException {
    script.add(toolCall("freigeben",
        "{\\\"betrag\\\":1200,\\\"grund\\\":\\\"Rabatt\\\"}"));
    script.add(answer("Gefragt."));
    startWith("      <userTask id='freigeben' name='Freigeben'><extensionElements>"
        + "<camunda:inputOutput>"
        + "<camunda:inputParameter name='vorschlag'>"
        + "${toolParameter(toolCall.betrag, 'Betrag in Euro', 'integer')}</camunda:inputParameter>"
        + "<camunda:inputParameter name='warum'>${toolParameter(toolCall.grund, 'Grund')}"
        + "</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>");
    runWaitingTurn();
    Map<String, Integer> before = toolCallRevisions();
    assertThat(before).hasSize(2);

    Task task = engine.getTaskService().createTaskQuery().taskName("Freigeben").singleResult();
    assertThat(engine.getTaskService().getVariables(task.getId()))
        .containsEntry("vorschlag", 1200L)
        .containsEntry("warum", "Rabatt");

    assertThat(toolCallRevisions()).as("read, not written again").isEqualTo(before);
  }

  /** The revision of every toolCall variable, read without deserializing it. */
  protected Map<String, Integer> toolCallRevisions() {
    Map<String, Integer> revisions = new TreeMap<String, Integer>();
    for (VariableInstance variable : engine.getRuntimeService().createVariableInstanceQuery()
        .variableName("toolCall").disableCustomObjectDeserialization().list()) {
      revisions.put(variable.getId(),
          ((org.cibseven.bpm.engine.impl.persistence.entity.VariableInstanceEntity) variable)
              .getRevision());
    }
    return revisions;
  }

  /**
   * A tool that is itself an agent declares its parameter in its own input parameters, which run
   * before its first turn and against the outer call: its agent reads the argument there. Its own
   * tools declaring parameters in mappings do not hide it.
   */
  @Test
  public void aNestedAgentReadsTheOuterCallsArgumentFromItsOwnInput() throws IOException {
    script.add(toolCall("helfer", "{\\\"auftrag\\\":\\\"Pruefe die Rechnung R-17\\\"}"));
    ProcessInstance pi = startWith("      <adHocSubProcess id='helfer' name='Helfer'>"
        + "<extensionElements><camunda:inputOutput><camunda:inputParameter name='auftrag'>"
        + "${toolParameter(toolCall.auftrag, 'Der Auftrag')}</camunda:inputParameter>"
        + "</camunda:inputOutput><camunda:connector>"
        + "<camunda:connectorId>cibseven-ai-agent</camunda:connectorId><camunda:inputOutput>"
        + "<camunda:inputParameter name='message'>Auftrag: ${auftrag}</camunda:inputParameter>"
        + "<camunda:inputParameter name='model'>stub</camunda:inputParameter>"
        + "<camunda:inputParameter name='apiKey'>stub-key-innen</camunda:inputParameter>"
        + "<camunda:inputParameter name='baseUrl'>http://127.0.0.1:" + STUB_PORT
        + "/v1</camunda:inputParameter>"
        + "</camunda:inputOutput></camunda:connector></extensionElements>"
        + "<userTask id='notieren'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='notiz'>${toolParameter(toolCall.notiz)}"
        + "</camunda:inputParameter></camunda:inputOutput></extensionElements></userTask>"
        + "</adHocSubProcess>");

    runWaitingTurn();

    assertThat(requestBodies.get(0).replaceAll("\\s", ""))
        .as("the outer model is offered the parameter")
        .contains("\"auftrag\":{\"type\":\"string\",\"description\":\"DerAuftrag\"}");
    assertThat(requestBodies)
        .as("the nested agent is asked with the argument of the outer call")
        .anySatisfy(body -> assertThat(body).contains("Auftrag: Pruefe die Rechnung R-17"));
    assertThat(alive(pi)).isFalse();
  }

  /**
   * The outer call's arguments stay visible inside a nested agent whose own tools declare
   * parameters in mappings: its output parameters read them, not an empty toolCall of its own.
   */
  @Test
  public void aNestedAgentsOutputsStillSeeTheOuterCallsArguments() throws IOException {
    script.add(toolCall("helfer", "{\\\"auftrag\\\":\\\"Pruefe die Rechnung R-17\\\"}"));
    ProcessInstance pi = startWith("      <adHocSubProcess id='helfer' name='Helfer'>"
        + "<extensionElements><camunda:inputOutput><camunda:inputParameter name='auftrag'>"
        + "${toolParameter(toolCall.auftrag, 'Der Auftrag')}</camunda:inputParameter>"
        + "<camunda:outputParameter name='echo'>${toolCall.auftrag}</camunda:outputParameter>"
        + "</camunda:inputOutput><camunda:connector>"
        + "<camunda:connectorId>cibseven-ai-agent</camunda:connectorId><camunda:inputOutput>"
        + "<camunda:inputParameter name='message'>Auftrag: ${auftrag}</camunda:inputParameter>"
        + "<camunda:inputParameter name='model'>stub</camunda:inputParameter>"
        + "<camunda:inputParameter name='apiKey'>stub-key-innen</camunda:inputParameter>"
        + "<camunda:inputParameter name='baseUrl'>http://127.0.0.1:" + STUB_PORT
        + "/v1</camunda:inputParameter>"
        + "</camunda:inputOutput></camunda:connector></extensionElements>"
        + "<userTask id='notieren'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='notiz'>${toolParameter(toolCall.notiz)}"
        + "</camunda:inputParameter></camunda:inputOutput></extensionElements></userTask>"
        + "</adHocSubProcess>");

    runWaitingTurn();

    assertThat(alive(pi)).isFalse();
    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("echo").singleResult().getValue())
        .as("the nested agent's output reads the outer call's argument")
        .isEqualTo("Pruefe die Rechnung R-17");
  }

  /**
   * A parameter declared in the inputs of an embedded sub process tool reaches the activities
   * inside it (CIB7-2136).
   */
  @Test
  public void aToolParameterReachesTheInsideOfASubProcessTool() throws IOException {
    script.add(toolCall("fragen", "{\\\"frage\\\":\\\"Wie geht es?\\\"}"));
    script.add(answer("Gefragt."));
    startWith("      <subProcess id='fragen' name='Fragen'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='frage'>${toolParameter(toolCall.frage, 'Die Frage')}"
        + "</camunda:inputParameter></camunda:inputOutput></extensionElements>"
        + "<startEvent id='fs' /><sequenceFlow id='fs1' sourceRef='fs' targetRef='person' />"
        + "<userTask id='person' name='Person'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='anzeige'>${frage}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>"
        + "<sequenceFlow id='fs2' sourceRef='person' targetRef='fe' /><endEvent id='fe' />"
        + "</subProcess>");

    runWaitingTurn();

    Task task = engine.getTaskService().createTaskQuery().taskName("Person").singleResult();
    assertThat(engine.getTaskService().getVariable(task.getId(), "anzeige"))
        .isEqualTo("Wie geht es?");
  }

  /** Every instance of a multi-instance tool reads the same argument (CIB7-2136). */
  @Test
  public void aToolParameterReachesEveryInstanceOfAMultiInstanceTool() throws IOException {
    script.add(toolCall("pruefen", "{\\\"hinweis\\\":\\\"Auf Kratzer achten\\\"}"));
    script.add(answer("Gestartet."));
    startWith("      <userTask id='pruefen' name='Pruefen'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='hinweis'>${toolParameter(toolCall.hinweis, 'Hinweis')}"
        + "</camunda:inputParameter></camunda:inputOutput></extensionElements>"
        + "<multiInstanceLoopCharacteristics isSequential='false'>"
        + "<loopCardinality>2</loopCardinality></multiInstanceLoopCharacteristics></userTask>");

    runWaitingTurn();

    List<Task> tasks = engine.getTaskService().createTaskQuery().taskName("Pruefen").list();
    assertThat(tasks).hasSize(2);
    for (Task task : tasks) {
      assertThat(engine.getTaskService().getVariable(task.getId(), "hinweis"))
          .isEqualTo("Auf Kratzer achten");
    }
  }

  /** A call activity tool hands the argument from its input to the called process (CIB7-2136). */
  @Test
  public void aToolParameterReachesTheProcessACallActivityToolCalls() throws IOException {
    script.add(toolCall("erstatten", "{\\\"betrag\\\":89.9}"));
    script.add(answer("Gestartet."));
    startWith("      <callActivity id='erstatten' name='Erstatten' calledElement='buchung'>"
        + "<extensionElements><camunda:inputOutput><camunda:inputParameter name='betrag'>"
        + "${toolParameter(toolCall.betrag, 'Betrag in Euro', 'number')}</camunda:inputParameter>"
        + "</camunda:inputOutput><camunda:in source='betrag' target='betrag' />"
        + "</extensionElements></callActivity>");
    engine.getRepositoryService().createDeployment().addString("buchung.bpmn20.xml",
        "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + " targetNamespace='http://cibseven.org/buchung'>"
        + "<process id='buchung' isExecutable='true'><startEvent id='bs' />"
        + "<sequenceFlow id='b1' sourceRef='bs' targetRef='buchen' />"
        + "<userTask id='buchen' name='Buchen' />"
        + "<sequenceFlow id='b2' sourceRef='buchen' targetRef='be' /><endEvent id='be' />"
        + "</process></definitions>").deploy();

    runWaitingTurn();

    Task task = engine.getTaskService().createTaskQuery().taskName("Buchen").singleResult();
    assertThat(engine.getTaskService().getVariable(task.getId(), "betrag")).isEqualTo(89.9d);
  }

  /**
   * The connector of an AI agent task used as a tool reads the argument in its inputs
   * (CIB7-2136).
   */
  @Test
  public void aToolParameterReachesTheConnectorOfAnAgentTaskTool() throws IOException {
    script.add(toolCall("berater", "{\\\"frage\\\":\\\"Ist das fair?\\\"}"));
    startWith("      <serviceTask id='berater' name='Berater'><extensionElements><camunda:connector>"
        + "<camunda:connectorId>cibseven-ai-agent</camunda:connectorId><camunda:inputOutput>"
        + "<camunda:inputParameter name='agentName'>berater</camunda:inputParameter>"
        + "<camunda:inputParameter name='message'>Frage: "
        + "${toolParameter(toolCall.frage, 'Die Frage')}</camunda:inputParameter>"
        + "<camunda:inputParameter name='model'>stub</camunda:inputParameter>"
        + "<camunda:inputParameter name='apiKey'>stub-key-berater</camunda:inputParameter>"
        + "<camunda:inputParameter name='baseUrl'>http://127.0.0.1:" + STUB_PORT
        + "/v1</camunda:inputParameter>"
        + "<camunda:outputParameter name='beratung'>${output}</camunda:outputParameter>"
        + "</camunda:inputOutput></camunda:connector></extensionElements></serviceTask>");

    runWaitingTurn();

    assertThat(requestBodies).as("the agent task is asked with the outer call's argument")
        .anySatisfy(body -> assertThat(body).contains("Frage: Ist das fair?"));
  }

  /**
   * An asynchronous tool reads its argument in the job that starts it, from the map stored with
   * the call (CIB7-2136).
   */
  @Test
  public void anAsynchronousToolReadsItsArgumentInItsOwnJob() throws IOException {
    script.add(toolCall("freigeben", "{\\\"betrag\\\":1200}"));
    script.add(answer("Gestartet."));
    startWith("      <userTask id='freigeben' name='Freigeben' camunda:asyncBefore='true'>"
        + "<extensionElements><camunda:inputOutput><camunda:inputParameter name='vorschlag'>"
        + "${toolParameter(toolCall.betrag, 'Betrag in Euro', 'integer')}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>");

    runWaitingTurn();

    Task task = engine.getTaskService().createTaskQuery().taskName("Freigeben").singleResult();
    assertThat(engine.getTaskService().getVariable(task.getId(), "vorschlag")).isEqualTo(1200L);
  }

  /**
   * A refused tool call ends its step too: the model reads the refusal in the next step, a model
   * call and a job of its own, not again within the same job.
   */
  @Test
  public void aRefusedToolCallEndsItsStepToo() throws IOException {
    script.add(toolCall("rechnen", "{\\\"betrag\\\":1}"));
    script.add(answer("Fertig."));
    ProcessInstance pi = startSteps();

    runOneJob();
    assertThat(requests.get()).as("the step asked the model once, refusal or not").isEqualTo(1);
    assertThat(engine.getManagementService().createJobQuery().list())
        .as("the next step waits").hasSize(1);

    runOneJob();
    assertThat(requests.get()).isEqualTo(2);
    assertThat(requestBodies.get(1)).as("the next step's request carries the refusal")
        .contains("has no parameter 'betrag'");
    assertThat(alive(pi)).as("the model answered, nothing runs: the scope ended").isFalse();
  }

  /**
   * CIB7-2135: a call that omits a required parameter is refused before the activity starts, not
   * failed inside it. The model reads the refusal, naming the parameter, calls again with it
   * supplied, and the scope completes — the reproduction from the ticket.
   */
  @Test
  public void aMissingRequiredParameterIsRefusedBeforeTheActivityStarts() throws IOException {
    script.add(toolCall("suche", "{\\\"query\\\":\\\"Rom\\\"}"));
    script.add(toolCall("suche", "{\\\"query\\\":\\\"Rom\\\",\\\"limit\\\":5}"));
    script.add(answer("Fertig."));
    ProcessInstance pi = startStepsWithARequiredParameterTool();

    runOneJob();
    assertThat(requests.get()).as("the step asked the model once, refusal or not").isEqualTo(1);
    assertThat(toolRequiresParameter(requestBodies.get(0), "suche", "limit"))
        .as("the model was told 'limit' is required, in the very first request").isTrue();
    assertThat(toolRequiresParameter(requestBodies.get(0), "suche", "query"))
        .as("the reproduction's 'query' carries no marker either, so it is required too").isTrue();
    assertThat(engine.getManagementService().createJobQuery().list())
        .as("the next step waits").hasSize(1);
    assertThat(engine.getHistoryService().createHistoricActivityInstanceQuery()
        .processInstanceId(pi.getId()).activityId("suche").count())
        .as("no child started: the refusal ran before the activity").isZero();

    runOneJob();
    assertThat(requests.get()).isEqualTo(2);
    assertThat(requestBodies.get(1)).as("the next step's request names the missing parameter")
        .contains("is missing required parameter 'limit'");
    assertThat(engine.getManagementService().createJobQuery().list())
        .as("the corrected call ran the tool within this step, one more step waits").hasSize(1);

    runOneJob();
    assertThat(requests.get()).isEqualTo(3);
    assertThat(engine.getHistoryService().createHistoricActivityInstanceQuery()
        .processInstanceId(pi.getId()).activityId("suche").count())
        .as("the corrected call started the activity, once").isEqualTo(1);
    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("treffer").singleResult().getValue())
        .as("the corrected values actually reached the activation").isEqualTo("Rom-5");
    assertThat(alive(pi)).as("the model answered, nothing runs: the scope ended").isFalse();
    assertThat(engine.getRuntimeService().createIncidentQuery().processInstanceId(pi.getId()).count())
        .as("the symptom this ticket removes: no incident is raised").isZero();
  }

  /**
   * An omitted optional parameter is no refusal: the activity starts on the first call, and its
   * mapping reads {@code toolCall.query} as null.
   */
  @Test
  public void anOmittedOptionalParameterReadsAsNull() throws IOException {
    script.add(toolCall("suche", "{\\\"limit\\\":5}"));
    script.add(answer("Fertig."));
    ProcessInstance pi = startStepsWithAnOptionalParameterTool();

    runOneJob();
    assertThat(requests.get()).as("optional 'query' left out: no refusal, one call").isEqualTo(1);
    assertThat(toolRequiresParameter(requestBodies.get(0), "suche", "query"))
        .as("'query' carries '?' here, so it is absent from the wire schema's required list")
        .isFalse();
    assertThat(engine.getHistoryService().createHistoricActivityInstanceQuery()
        .processInstanceId(pi.getId()).activityId("suche").count())
        .as("the activity started on the first call, nothing refused it").isEqualTo(1);
    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("treffer").singleResult().getValue())
        .as("the mapping read 'query' as null (rendered empty) rather than failing")
        .isEqualTo("-5");

    runOneJob();
    assertThat(alive(pi)).as("the model answered, nothing runs: the scope ended").isFalse();
    assertThat(engine.getRuntimeService().createIncidentQuery().processInstanceId(pi.getId()).count())
        .as("no incident").isZero();
  }

  /**
   * Whether the request's tool schema for {@code toolName} lists {@code parameterName} as
   * required. Fails loudly if {@code toolName} is not offered in this request at all, so a
   * missing tool is never read as "not required" — the two are not the same claim.
   */
  private static boolean toolRequiresParameter(String requestBody, String toolName,
      String parameterName) throws IOException {
    for (JsonNode tool : new ObjectMapper().readTree(requestBody).path("tools")) {
      if (toolName.equals(tool.path("function").path("name").asText())) {
        for (JsonNode required : tool.path("function").path("parameters").path("required")) {
          if (parameterName.equals(required.asText())) {
            return true;
          }
        }
        return false;
      }
    }
    throw new AssertionError("tool '" + toolName + "' is not offered in this request: " + requestBody);
  }

  /**
   * Under the job executor, a step whose tool ended at once leaves exactly one next step. The
   * executor locks an exclusive job that a job of the same instance creates, as the hint that this
   * thread runs it next; that lock must not make the step schedule a second one, which would be one
   * more step and one more model call.
   */
  @Test
  public void underTheJobExecutorAToolThatEndsAtOnceLeavesOneNextStep() throws Exception {
    script.add(toolCall("rechnen", "{}"));
    script.add(toolCall("worker", "{}"));
    script.add(answer("Gestartet."));
    ProcessInstance pi = startSteps();

    JobExecutor executor =
        ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration()).getJobExecutor();
    executor.start();
    try {
      long deadline = System.currentTimeMillis() + 30000;
      while (engine.getManagementService().createJobQuery().count() > 0
          && System.currentTimeMillis() < deadline) {
        Thread.sleep(100);
      }
    } finally {
      executor.shutdown();
    }

    assertThat(requests.get()).as("three steps, one model call each").isEqualTo(3);
    assertThat(engine.getHistoryService().createHistoricJobLogQuery()
        .processInstanceId(pi.getId()).creationLog().count())
        .as("one job for entering the scope and one for each further step").isEqualTo(3);
  }

  /**
   * A step that runs without an executor's lock -- {@code executeJob}, or {@code POST
   * /job/{id}/execute} -- looks like a waiting one to a child ending meanwhile, which then
   * schedules nothing. The step read the state before the child ended, so it must not commit as if
   * the child were still running: it fails on its own job, and its rerun sees the end.
   *
   * <p>With a timer on the scope, which keeps a job on the scope's execution: without one, deleting
   * the step's job is what changes the scope's row, and that collides with the child's end anyway.
   */
  @Test
  public void aChildEndingWhileAnUnlockedStepWaitsForTheModelIsNotLost() throws Exception {
    script.add(toolCall("worker", "{}"));
    script.add(toolCall("pruefer", "{}"));
    script.add(answer("Beide laufen."));
    script.add(answer("Warte auf den Worker."));
    script.add(answer("Fertig."));
    ProcessInstance pi = start(connectorModel("",
        "<camunda:inputParameter name='message'>Frag nach.</camunda:inputParameter>", "",
        "stub-key", "      <userTask id='worker' name='Worker' />"
            + "      <userTask id='pruefer' name='Pruefer' />")
        .replace("    <sequenceFlow id='f2'", "    <boundaryEvent id='zuLange' attachedToRef='adHoc'>"
            + "<timerEventDefinition><timeDuration>PT1H</timeDuration></timerEventDefinition>"
            + "</boundaryEvent><sequenceFlow id='f3' sourceRef='zuLange' targetRef='end' />"
            + "    <sequenceFlow id='f2'"),
        Collections.<String, Object>emptyMap());
    runTurnJobs();
    complete("Pruefer");

    Job step = engine.getManagementService().createJobQuery().messages().singleResult();
    CountDownLatch release = new CountDownLatch(1);
    holdNextRequest = release;
    AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
    Thread thread = new Thread(() -> {
      try {
        engine.getManagementService().executeJob(step.getId());
      } catch (Throwable e) {
        failure.set(e);
      }
    });
    thread.start();
    try {
      assertThat(requestHeld.await(30, TimeUnit.SECONDS)).as("the step asks the model").isTrue();
      complete("Worker");
    } finally {
      release.countDown();
      thread.join(30000);
    }

    assertThat(failure.get()).as("the step read the state before the worker ended")
        .isInstanceOf(OptimisticLockingException.class);
    assertThat(engine.getManagementService().createJobQuery().messages().list())
        .as("the step waits to run again").hasSize(1);

    runTurnJobs();
    assertThat(requests.get()).as("one call for each step, one for the attempt that failed")
        .isEqualTo(5);
    assertThat(lastUserMessage(requestBodies.get(4))).as("the rerun sees that the worker ended")
        .contains("\"activityId\":\"worker\"");
    assertThat(alive(pi)).as("the model answered, nothing runs: the scope ended").isFalse();
  }

  /** Runs the waiting steps, and only them: not a timer that is not due. */
  protected void runTurnJobs() {
    for (int steps = 0; steps < TurnSteps.MAX_STEPS; steps++) {
      Job job = engine.getManagementService().createJobQuery().messages().singleResult();
      if (job == null) {
        return;
      }
      engine.getManagementService().executeJob(job.getId());
    }
    throw new AssertionError("more than " + TurnSteps.MAX_STEPS + " steps, the turn does not end");
  }

  /** Asking to end the scope is a tool call too; the final answer follows in the next step. */
  @Test
  public void completeScopeIsFollowedByAStepForTheFinalAnswer() {
    script.add(toolCall("completeScope", "{}"));
    script.add(answer("Abgeschlossen."));
    ProcessInstance pi = startSteps();

    runWaitingTurn();

    assertThat(requests.get()).isEqualTo(2);
    assertThat(alive(pi)).isFalse();
    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("agentOutput").singleResult().getValue())
        .isEqualTo("Abgeschlossen.");
  }

  /**
   * A tool that finished within its call is reported in the tool result; the state block of the
   * next step does not report it again.
   */
  @Test
  public void aToolResultIsNotReportedAgainInTheNextStep() {
    script.add(toolCall("rechnen", "{}"));
    script.add(answer("Fertig."));
    startSteps();

    runWaitingTurn();

    assertThat(lastRequestBody)
        .as("the second step's request: the tool result is there, the state block is empty")
        .contains("1200")
        .doesNotContain("finishedSinceLastTurn\\\":[{");
  }

  /**
   * A further step sends the task again, and says that the turn goes on and what the agent has
   * called in it, because the steps that called it may have left the memory window. The next turn
   * starts with nothing called.
   */
  @Test
  public void aFurtherStepSaysWhatTheTurnHasCalled() throws IOException {
    script.add(toolCall("rechnen", "{}"));
    script.add(toolCall("worker", "{}"));
    script.add(answer("Gestartet."));
    script.add(answer("Erledigt."));
    startSteps();

    runWaitingTurn();
    Task task = engine.getTaskService().createTaskQuery().singleResult();
    engine.getTaskService().complete(task.getId());
    runWaitingTurn();

    assertThat(requestBodies).hasSize(4);
    assertThat(lastUserMessage(requestBodies.get(0))).doesNotContain("calledThisTurn");
    assertThat(lastUserMessage(requestBodies.get(1)))
        .contains("\"calledThisTurn\":[\"rechnen\"]")
        .contains("This turn goes on")
        .as("the task is still sent").contains("Rechne.");
    assertThat(lastUserMessage(requestBodies.get(2)))
        .contains("\"calledThisTurn\":[\"rechnen\",\"worker\"]");
    assertThat(lastUserMessage(requestBodies.get(3)))
        .as("the next turn has called nothing yet").doesNotContain("calledThisTurn");
  }

  // --- results on the agent state (CIB7-2147)

  /** A tool that waits for a person and declares the value the person enters. */
  protected static final String WORKER_WITH_ANSWER =
      "      <userTask id='worker' name='Worker'><extensionElements><camunda:formData>"
      + "<camunda:formField id='antwort' type='string' /></camunda:formData></extensionElements>"
      + "</userTask>";

  protected ProcessInstance startWithWorker(String scopeExtensions) {
    return start(connectorModel(scopeExtensions,
        "<camunda:inputParameter name='message'>Frag nach.</camunda:inputParameter>", "",
        "stub-key", WORKER_WITH_ANSWER), Collections.<String, Object>emptyMap());
  }

  protected void completeWorker(String antwort) {
    Task task = engine.getTaskService().createTaskQuery().taskDefinitionKey("worker").singleResult();
    engine.getTaskService().complete(task.getId(),
        Collections.<String, Object>singletonMap("antwort", antwort));
  }

  /**
   * A scope's own outputCollection is the modeller's: it gets the modeller's entries only, and the
   * agent is told every result all the same.
   */
  @Test
  public void aScopesOwnOutputCollectionStaysTheModellersAndTheAgentIsStillTold() throws IOException {
    script.add(toolCall("worker", "{}"));
    script.add(answer("Gestartet."));
    script.add(answer("Erledigt."));
    ProcessInstance pi = startWithWorker("<camunda:properties>"
        + "<camunda:property name='outputCollection' value='meine' />"
        + "<camunda:property name='outputElement' value='${antwort}' />"
        + "</camunda:properties>");

    runWaitingTurn();
    completeWorker("ja");
    runWaitingTurn();

    assertThat(requestBodies).hasSize(3);
    assertThat(lastUserMessage(requestBodies.get(2)))
        .as("the next turn reports the worker's result")
        .contains("\"activityId\":\"worker\"")
        .contains("\"antwort\":\"ja\"");
    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("meine").singleResult().getValue())
        .as("the modeller's collection holds the modeller's entry only")
        .isEqualTo(Collections.singletonList("ja"));
  }

  /**
   * What the agent is told is kept on its state, not in a process variable that every tool and
   * every other agent in the process could read and rewrite.
   */
  @Test
  public void theAgentsResultsAreNoProcessVariable() {
    script.add(toolCall("worker", "{}"));
    script.add(answer("Gestartet."));
    script.add(answer("Erledigt."));
    ProcessInstance pi = startWithWorker("");

    runWaitingTurn();
    completeWorker("ja");

    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("adHocAgentResults").singleResult()
        .getExecutionId()).as("gathered when the worker ended, on the agent's state")
        .isNotEqualTo(pi.getId());
    assertThat(engine.getRuntimeService().getVariables(pi.getId()))
        .as("where every tool and every other agent reads and writes")
        .doesNotContainKeys("adHocAgentResults", "cibsevenAgenticResults");
    runWaitingTurn();

    assertThat(alive(pi)).isFalse();
    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("cibsevenAgenticResults").count())
        .isZero();
  }

  /**
   * A tool started on entry that ends while the first step waits for the model, under the job
   * executor's lock, finds the agent's state the step uses: there is one state, not one for the
   * step's conversation and another for the tool's result.
   */
  @Test
  public void aToolEndingDuringTheFirstStepFindsTheAgentsOneState() throws Exception {
    script.add(answer("Warte auf den Pruefer."));
    script.add(answer("Fertig."));
    // Variables the instance has already, as a real one does: the first of them would change the
    // instance's row, and that collision would hide what is checked here.
    Map<String, Object> variables = new HashMap<String, Object>();
    variables.put("agentOutput", "");
    variables.put("befund", "");
    ProcessInstance pi = start(connectorModel(
        "<camunda:properties><camunda:property name='activitiesToActivate' value='pruefer' />"
            + "</camunda:properties>",
        "<camunda:inputParameter name='message'>Frag nach.</camunda:inputParameter>", "",
        "stub-key", "      <userTask id='pruefer' name='Pruefer'><extensionElements><camunda:formData>"
            + "<camunda:formField id='befund' type='string' /></camunda:formData>"
            + "</extensionElements></userTask>"),
        variables);

    Job step = engine.getManagementService().createJobQuery().singleResult();
    // As the job executor would: the step's job is locked while it runs.
    ((org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
        .getCommandExecutorTxRequired().execute(commandContext -> {
          org.cibseven.bpm.engine.impl.persistence.entity.JobEntity job =
              commandContext.getJobManager().findJobById(step.getId());
          job.setLockOwner("test");
          job.setLockExpirationTime(new java.util.Date(System.currentTimeMillis() + 300000));
          return null;
        });
    CountDownLatch release = new CountDownLatch(1);
    holdNextRequest = release;
    AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
    Thread thread = new Thread(() -> {
      try {
        engine.getManagementService().executeJob(step.getId());
      } catch (Throwable e) {
        failure.set(e);
      }
    });
    thread.start();
    try {
      assertThat(requestHeld.await(30, TimeUnit.SECONDS)).as("the step asks the model").isTrue();
      completeTask(pi, "pruefer", Collections.<String, Object>singletonMap("befund", "gut"));
    } finally {
      release.countDown();
      thread.join(30000);
    }

    assertThat(failure.get()).as("the step commits after the tool's transaction").isNull();
    assertThat(engine.getRuntimeService().createVariableInstanceQuery()
        .processInstanceIdIn(pi.getId()).variableName(AdHocAgentState.STATE_MARKER).count())
        .as("one agent state for the scope").isEqualTo(1);

    runWaitingTurn();
    assertThat(lastUserMessage(requestBodies.get(1))).as("the next step reports the tool's result")
        .contains("\"befund\":\"gut\"");
    assertThat(alive(pi)).isFalse();
  }

  /**
   * A child ending while a step waits for the model does not make that step fail: the child adds
   * to the agent's results, the step moves its read position, and neither writes what the other
   * does. Repeating the step would repeat its model call (T01 of the agentic suite).
   */
  @Test
  public void aChildEndingWhileAStepWaitsForTheModelLeavesTheStepAlone() throws Exception {
    script.add(toolCall("worker", "{}"));
    script.add(toolCall("pruefer", "{}"));
    script.add(answer("Beide laufen."));
    script.add(answer("Warte auf den Pruefer."));
    script.add(answer("Fertig."));
    ProcessInstance pi = start(connectorModel("",
        "<camunda:inputParameter name='message'>Frag nach.</camunda:inputParameter>", "",
        "stub-key", WORKER_WITH_ANSWER
            + "      <userTask id='pruefer' name='Pruefer'><extensionElements><camunda:formData>"
            + "<camunda:formField id='befund' type='string' /></camunda:formData>"
            + "</extensionElements></userTask>"),
        Collections.<String, Object>emptyMap());
    runWaitingTurn();
    completeWorker("ja");

    Job step = engine.getManagementService().createJobQuery().singleResult();
    // As the job executor would: the step's job is locked while it runs.
    ((org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
        .getCommandExecutorTxRequired().execute(commandContext -> {
          org.cibseven.bpm.engine.impl.persistence.entity.JobEntity job =
              commandContext.getJobManager().findJobById(step.getId());
          job.setLockOwner("test");
          job.setLockExpirationTime(new java.util.Date(System.currentTimeMillis() + 300000));
          return null;
        });
    CountDownLatch release = new CountDownLatch(1);
    holdNextRequest = release;
    AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
    Thread thread = new Thread(() -> {
      try {
        engine.getManagementService().executeJob(step.getId());
      } catch (Throwable e) {
        failure.set(e);
      }
    });
    thread.start();
    try {
      assertThat(requestHeld.await(30, TimeUnit.SECONDS)).as("the step asks the model").isTrue();
      Task pruefer = engine.getTaskService().createTaskQuery()
          .processInstanceId(pi.getId()).taskDefinitionKey("pruefer").singleResult();
      engine.getTaskService().complete(pruefer.getId(),
          Collections.<String, Object>singletonMap("befund", "gut"));
    } finally {
      release.countDown();
      thread.join(30000);
    }

    assertThat(failure.get()).as("the step commits after the child's transaction").isNull();
    assertThat(requests.get()).isEqualTo(4);

    runWaitingTurn();
    assertThat(requests.get()).as("the child's end brought one more turn").isEqualTo(5);
    assertThat(lastUserMessage(requestBodies.get(4))).as("which reports what the child produced")
        .contains("\"befund\":\"gut\"");
    assertThat(alive(pi)).isFalse();
  }

  /**
   * A multi-instance tool ends once, as its multi-instance body, after its last instance; it is
   * reported under the activity the agent called, with what its declared variables hold then.
   */
  @Test
  public void aMultiInstanceToolIsReportedUnderTheActivityTheAgentCalled() throws IOException {
    script.add(toolCall("freigabeMI", "{}"));
    script.add(answer("Gestartet."));
    script.add(answer("Fertig."));
    ProcessInstance pi = start(connectorModel("",
        "<camunda:inputParameter name='message'>Lass freigeben.</camunda:inputParameter>", "",
        "stub-key",
        "      <userTask id='freigabeMI' name='Freigabe'><extensionElements><camunda:formData>"
        + "<camunda:formField id='votum' type='string' /></camunda:formData></extensionElements>"
        + "<multiInstanceLoopCharacteristics isSequential='false'>"
        + "<loopCardinality>2</loopCardinality></multiInstanceLoopCharacteristics></userTask>"),
        Collections.<String, Object>emptyMap());
    runWaitingTurn();

    List<Task> tasks = engine.getTaskService().createTaskQuery().processInstanceId(pi.getId()).list();
    assertThat(tasks).hasSize(2);
    engine.getTaskService().complete(tasks.get(0).getId(),
        Collections.<String, Object>singletonMap("votum", "ja"));
    assertThat(engine.getManagementService().createJobQuery().count())
        .as("one instance still open: the tool has not ended").isZero();
    engine.getTaskService().complete(tasks.get(1).getId(),
        Collections.<String, Object>singletonMap("votum", "nein"));
    runWaitingTurn();

    assertThat(requestBodies).hasSize(3);
    assertThat(lastUserMessage(requestBodies.get(2)))
        .contains("\"activityId\":\"freigabeMI\"")
        .contains("\"votum\":\"nein\"")
        .contains("multi-instance activity");
    assertThat(alive(pi)).isFalse();
  }

  /** A multi-instance tool that declares nothing is told both: reported once, and nothing shown. */
  @Test
  public void aMultiInstanceToolThatDeclaresNothingKeepsBothNotes() throws IOException {
    script.add(toolCall("freigabeMI", "{}"));
    script.add(answer("Gestartet."));
    script.add(answer("Fertig."));
    ProcessInstance pi = startWith(
        "      <userTask id='freigabeMI' name='Freigabe'><multiInstanceLoopCharacteristics"
        + " isSequential='false'><loopCardinality>2</loopCardinality>"
        + "</multiInstanceLoopCharacteristics></userTask>");
    runWaitingTurn();

    for (Task task : engine.getTaskService().createTaskQuery().processInstanceId(pi.getId()).list()) {
      engine.getTaskService().complete(task.getId());
    }
    runWaitingTurn();

    assertThat(lastUserMessage(requestBodies.get(2)))
        .contains("\"activityId\":\"freigabeMI\"")
        .contains("multi-instance activity, reported once")
        .contains("declares no output mapping");
    assertThat(alive(pi)).isFalse();
  }

  /**
   * A multi-instance task with an input parameter keeps its result on each instance's execution,
   * which is no scope and goes as the instance ends: the agent is told what the last instance held
   * there (CIB7-2215).
   */
  @Test
  public void aMultiInstanceToolsLocalResultReachesTheAgent() throws IOException {
    script.add(toolCall("freigabeMI", "{}"));
    script.add(answer("Gestartet."));
    script.add(answer("Fertig."));
    ProcessInstance pi = start(connectorModel("",
        "<camunda:inputParameter name='message'>Lass freigeben.</camunda:inputParameter>", "",
        "stub-key",
        "      <userTask id='freigabeMI' name='Freigabe'><extensionElements><camunda:properties>"
        + "<camunda:property name='adHocResultVariables' value='votum' /></camunda:properties>"
        + "<camunda:inputOutput>"
        + "<camunda:inputParameter name='votum'>${null}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements>"
        + "<multiInstanceLoopCharacteristics isSequential='false'>"
        + "<loopCardinality>2</loopCardinality></multiInstanceLoopCharacteristics></userTask>"),
        Collections.<String, Object>emptyMap());
    runWaitingTurn();

    List<Task> tasks = engine.getTaskService().createTaskQuery().processInstanceId(pi.getId()).list();
    assertThat(tasks).hasSize(2);
    engine.getTaskService().complete(tasks.get(0).getId(),
        Collections.<String, Object>singletonMap("votum", "ja"));
    engine.getTaskService().complete(tasks.get(1).getId(),
        Collections.<String, Object>singletonMap("votum", "nein"));
    runWaitingTurn();

    assertThat(requestBodies).hasSize(3);
    assertThat(lastUserMessage(requestBodies.get(2)))
        .contains("\"activityId\":\"freigabeMI\"")
        .contains("\"votum\":\"nein\"");
    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("votum").list())
        .as("the votes stay the instances' own: none reaches the process")
        .isNotEmpty()
        .allSatisfy(v -> assertThat(v.getActivityInstanceId()).isNotEqualTo(pi.getId()));
    assertThat(alive(pi)).isFalse();
  }

  /**
   * The same, run one instance after the other: the agent is told what the second instance held,
   * not the first (CIB7-2215).
   */
  @Test
  public void aSequentialMultiInstanceToolsLocalResultReachesTheAgent() throws IOException {
    script.add(toolCall("freigabeMI", "{}"));
    script.add(answer("Gestartet."));
    script.add(answer("Fertig."));
    ProcessInstance pi = start(connectorModel("",
        "<camunda:inputParameter name='message'>Lass freigeben.</camunda:inputParameter>", "",
        "stub-key",
        "      <userTask id='freigabeMI' name='Freigabe'><extensionElements><camunda:properties>"
        + "<camunda:property name='adHocResultVariables' value='votum' /></camunda:properties>"
        + "<camunda:inputOutput>"
        + "<camunda:inputParameter name='votum'>${null}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements>"
        + "<multiInstanceLoopCharacteristics isSequential='true'>"
        + "<loopCardinality>2</loopCardinality></multiInstanceLoopCharacteristics></userTask>"),
        Collections.<String, Object>emptyMap());
    runWaitingTurn();

    Task first = engine.getTaskService().createTaskQuery().processInstanceId(pi.getId())
        .singleResult();
    engine.getTaskService().complete(first.getId(),
        Collections.<String, Object>singletonMap("votum", "ja"));
    Task second = engine.getTaskService().createTaskQuery().processInstanceId(pi.getId())
        .singleResult();
    assertThat(second.getId()).isNotEqualTo(first.getId());
    engine.getTaskService().complete(second.getId(),
        Collections.<String, Object>singletonMap("votum", "nein"));
    runWaitingTurn();

    assertThat(requestBodies).hasSize(3);
    assertThat(lastUserMessage(requestBodies.get(2)))
        .contains("\"activityId\":\"freigabeMI\"")
        .contains("\"votum\":\"nein\"");
    assertThat(alive(pi)).isFalse();
  }

  /**
   * A multi-instance tool without a mapping still holds variables of its own on each instance's
   * execution: the element variable and the loop counter. One it declares as its result reaches
   * the agent, from the last instance (CIB7-2215, review F4).
   */
  @Test
  public void aMultiInstanceToolsElementVariableReachesTheAgent() throws IOException {
    script.add(toolCall("pruefenMI", "{}"));
    script.add(answer("Gestartet."));
    script.add(answer("Fertig."));
    ProcessInstance pi = start(connectorModel("",
        "<camunda:inputParameter name='message'>Lass pruefen.</camunda:inputParameter>", "",
        "stub-key",
        "      <userTask id='pruefenMI' name='Pruefen'><extensionElements><camunda:properties>"
        + "<camunda:property name='adHocResultVariables' value='artikel' /></camunda:properties>"
        + "</extensionElements><multiInstanceLoopCharacteristics isSequential='false'"
        + " camunda:collection='${liste}' camunda:elementVariable='artikel' /></userTask>"),
        Collections.<String, Object>singletonMap("liste", java.util.Arrays.asList("A", "B")));
    runWaitingTurn();

    for (Task task : engine.getTaskService().createTaskQuery().processInstanceId(pi.getId())
        .orderByTaskId().asc().list()) {
      engine.getTaskService().complete(task.getId());
    }
    runWaitingTurn();

    assertThat(lastUserMessage(requestBodies.get(2)))
        .contains("\"activityId\":\"pruefenMI\"")
        .containsPattern("\"artikel\":\"[AB]\"");
  }

  /**
   * A multi-instance call activity hands its result back onto each instance's execution with
   * {@code camunda:out local}, which is no scope and goes as the instance ends. The called process
   * does not wait, so the tool result within the call is where the agent is told it (CIB7-2215).
   */
  @Test
  public void aMultiInstanceCallActivitysLocalResultReachesTheAgentWithinTheCall()
      throws IOException {
    script.add(toolCall("berechnenMI", "{}"));
    script.add(answer("Berechnet."));
    String called = "  <process id='berechnen' isExecutable='true'>"
        + "    <startEvent id='cs' />"
        + "    <sequenceFlow id='c1' sourceRef='cs' targetRef='rechnenInnen' />"
        + "    <serviceTask id='rechnenInnen' camunda:expression='${1200}'"
        + " camunda:resultVariable='preis' />"
        + "    <sequenceFlow id='c2' sourceRef='rechnenInnen' targetRef='ce' />"
        + "    <endEvent id='ce' />"
        + "  </process>";
    ProcessInstance pi = start(connectorModel("",
        "<camunda:inputParameter name='message'>Berechne.</camunda:inputParameter>", "",
        "stub-key",
        "      <callActivity id='berechnenMI' name='Berechnen' calledElement='berechnen'>"
        + "<extensionElements><camunda:properties>"
        + "<camunda:property name='adHocResultVariables' value='preis' /></camunda:properties>"
        + "<camunda:out source='preis' target='preis' local='true' /></extensionElements>"
        + "<multiInstanceLoopCharacteristics isSequential='false'>"
        + "<loopCardinality>2</loopCardinality></multiInstanceLoopCharacteristics></callActivity>")
        .replace("</definitions>", called + "</definitions>"),
        Collections.<String, Object>emptyMap());

    runWaitingTurn();

    assertThat(requestBodies).hasSize(2);
    assertThat(toolResults(requestBodies.get(1)))
        .contains("\"status\":\"finished\"")
        .contains("\"preis\":1200");
    assertThat(engine.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(pi.getId()).count()).isZero();
    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("preis").list())
        .as("the price stays the instances' own: none reaches the process")
        .isNotEmpty()
        .allSatisfy(v -> assertThat(v.getActivityInstanceId()).isNotEqualTo(pi.getId()));
  }

  /**
   * A sub process tool whose result variable lives in its own scope, created there by an input
   * parameter; a person answers into it.
   */
  protected static final String ASK_A_PERSON =
      "      <subProcess id='fragen' name='Fragen'><extensionElements><camunda:properties>"
      + "<camunda:property name='adHocResultVariables' value='antwort' /></camunda:properties>"
      + "<camunda:inputOutput>"
      + "<camunda:inputParameter name='antwort'>${null}</camunda:inputParameter>"
      + "</camunda:inputOutput></extensionElements>"
      + "<startEvent id='fs' /><sequenceFlow id='fs1' sourceRef='fs' targetRef='person' />"
      + "<userTask id='person' name='Person' />"
      + "<sequenceFlow id='fs2' sourceRef='person' targetRef='fe' />"
      + "<endEvent id='fe' /></subProcess>";

  /** The person's answer, held in the sub process's own scope, reaches the agent (CIB7-2215). */
  @Test
  public void aSubProcessToolsLocalResultReachesTheAgent() throws IOException {
    ProcessInstance pi = askAPersonAndAnswer(ASK_A_PERSON);

    assertThat(lastUserMessage(requestBodies.get(2)))
        .contains("\"activityId\":\"fragen\"")
        .contains("\"antwort\":\"ja\"");
    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("antwort").list())
        .as("the answer stays the sub process's own: none reaches the process")
        .isNotEmpty()
        .allSatisfy(v -> assertThat(v.getActivityInstanceId()).isNotEqualTo(pi.getId()));
    assertThat(alive(pi)).isFalse();
  }

  /** The same across asyncAfter: the tool's end and the scope being told are two transactions. */
  @Test
  public void aSubProcessToolsLocalResultReachesTheAgentAcrossAsyncAfter() throws IOException {
    askAPersonAndAnswer(ASK_A_PERSON.replace("<subProcess id='fragen' name='Fragen'>",
        "<subProcess id='fragen' name='Fragen' camunda:asyncAfter='true'>"));

    assertThat(lastUserMessage(requestBodies.get(2))).contains("\"antwort\":\"ja\"");
  }

  /** A sub process tool that holds its result in its own scope and does not wait. */
  protected static final String SUM_IN_A_SUB_PROCESS =
      "      <subProcess id='summieren' name='Summieren'><extensionElements><camunda:properties>"
      + "<camunda:property name='adHocResultVariables' value='summe' /></camunda:properties>"
      + "<camunda:inputOutput>"
      + "<camunda:inputParameter name='summe'>${null}</camunda:inputParameter>"
      + "</camunda:inputOutput></extensionElements>"
      + "<startEvent id='ss' /><sequenceFlow id='ss1' sourceRef='ss' targetRef='addieren' />"
      + "<serviceTask id='addieren' camunda:expression='${1200}' camunda:resultVariable='summe' />"
      + "<sequenceFlow id='ss2' sourceRef='addieren' targetRef='se' />"
      + "<endEvent id='se' /></subProcess>";

  /**
   * It ends within the call that started it, and the tool result, the one report the agent gets,
   * carries the value (CIB7-2215).
   */
  @Test
  public void aSubProcessToolsLocalResultReachesTheAgentWithinTheCall() throws IOException {
    script.add(toolCall("summieren", "{}"));
    script.add(answer("Fertig."));
    ProcessInstance pi = start(connectorModel("",
        "<camunda:inputParameter name='message'>Summiere.</camunda:inputParameter>", "",
        "stub-key", SUM_IN_A_SUB_PROCESS), Collections.<String, Object>emptyMap());

    runWaitingTurn();

    assertThat(requestBodies).hasSize(2);
    assertThat(toolResults(requestBodies.get(1)))
        .contains("\"status\":\"finished\"")
        .contains("\"summe\":1200");
    assertThat(lastUserMessage(requestBodies.get(1)))
        .as("the state block of the next step does not report it again")
        .contains("\"finishedSinceLastTurn\":[]");
    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("summe").list())
        .as("the sum stays the sub process's own: none reaches the process")
        .isNotEmpty()
        .allSatisfy(v -> assertThat(v.getActivityInstanceId()).isNotEqualTo(pi.getId()));
    assertThat(alive(pi)).isFalse();
  }

  /** A tool with an input and an output parameter of one name. */
  protected static final String SCORE_HANDED_OUT =
      "      <userTask id='bewerten' name='Bewerten'><extensionElements><camunda:inputOutput>"
      + "<camunda:inputParameter name='score'>${0}</camunda:inputParameter>"
      + "<camunda:outputParameter name='score'>${score * 10}</camunda:outputParameter>"
      + "</camunda:inputOutput></extensionElements></userTask>";

  /**
   * The agent is told what the output parameter handed out, not the local value from before it
   * (CIB7-2215).
   */
  @Test
  public void aToolIsReportedWithWhatItsOutputParameterHandsOut() throws IOException {
    script.add(toolCall("bewerten", "{}"));
    script.add(answer("Gefragt."));
    script.add(answer("Danke."));
    ProcessInstance pi = start(connectorModel("",
        "<camunda:inputParameter name='message'>Bewerte.</camunda:inputParameter>", "",
        "stub-key", SCORE_HANDED_OUT), Collections.<String, Object>emptyMap());
    runWaitingTurn();
    engine.getTaskService().complete(
        engine.getTaskService().createTaskQuery().taskName("Bewerten").singleResult().getId(),
        Collections.<String, Object>singletonMap("score", 7));
    TurnSteps.runToTheEnd(engine, pi.getId());

    assertThat(requestBodies).hasSize(3);
    assertThat(lastUserMessage(requestBodies.get(2)))
        .contains("\"activityId\":\"bewerten\"")
        .contains("\"score\":70");
  }

  /**
   * A chain that goes on after the sub process tool, along an inner flow, and writes its result
   * again: the agent is told the newer value, not the one carried out of the sub process.
   */
  @Test
  public void aLaterWriteInTheChainWinsOverTheCarriedValue() throws IOException {
    askAPersonAndAnswer(ASK_A_PERSON
        + "      <sequenceFlow id='weiter' sourceRef='fragen' targetRef='korrigieren' />"
        + "      <serviceTask id='korrigieren' name='Korrigieren'"
        + " camunda:expression=\"${'korrigiert'}\" camunda:resultVariable='antwort' />");

    assertThat(lastUserMessage(requestBodies.get(2)))
        .contains("\"endedAt\":\"korrigieren\"")
        .contains("\"antwort\":\"korrigiert\"");
  }

  /**
   * A chain whose first activity is the tool and whose sub process, further along an inner flow,
   * holds the declared result in its own scope (CIB7-2215, review F3).
   */
  protected static String chainIntoASubProcess(String inside) {
    return "      <serviceTask id='anstossen' name='Anstossen' camunda:expression='${true}'>"
        + "<extensionElements><camunda:properties>"
        + "<camunda:property name='adHocResultVariables' value='antwort' /></camunda:properties>"
        + "</extensionElements></serviceTask>"
        + "      <sequenceFlow id='weiter' sourceRef='anstossen' targetRef='fragen' />"
        + "      <subProcess id='fragen' name='Fragen'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='antwort'>${null}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements>"
        + "<startEvent id='fs' /><sequenceFlow id='fs1' sourceRef='fs' targetRef='innen' />" + inside
        + "<sequenceFlow id='fs2' sourceRef='innen' targetRef='fe' /><endEvent id='fe' /></subProcess>";
  }

  @Test
  public void aSubProcessFurtherAlongTheChainCarriesItsResult() throws IOException {
    script.add(toolCall("anstossen", "{}"));
    script.add(answer("Gestartet."));
    script.add(answer("Danke."));
    ProcessInstance pi = start(connectorModel("",
        "<camunda:inputParameter name='message'>Frag nach.</camunda:inputParameter>", "",
        "stub-key", chainIntoASubProcess("<userTask id='innen' name='Person' />")),
        Collections.<String, Object>emptyMap());
    runWaitingTurn();
    engine.getTaskService().complete(
        engine.getTaskService().createTaskQuery().taskName("Person").singleResult().getId(),
        Collections.<String, Object>singletonMap("antwort", "ja"));
    TurnSteps.runToTheEnd(engine, pi.getId());

    assertThat(lastUserMessage(requestBodies.get(2)))
        .contains("\"activityId\":\"anstossen\"")
        .contains("\"endedAt\":\"fragen\"")
        .contains("\"antwort\":\"ja\"");
  }

  /** The same chain without a wait: it ends within the call, and the tool result has the value. */
  @Test
  public void aSubProcessFurtherAlongTheChainCarriesItsResultWithinTheCall() throws IOException {
    script.add(toolCall("anstossen", "{}"));
    script.add(answer("Fertig."));
    start(connectorModel("",
        "<camunda:inputParameter name='message'>Frag nach.</camunda:inputParameter>", "",
        "stub-key", chainIntoASubProcess("<serviceTask id='innen'"
            + " camunda:expression=\"${'ja'}\" camunda:resultVariable='antwort' />")),
        Collections.<String, Object>emptyMap());

    runWaitingTurn();

    assertThat(toolResults(requestBodies.get(1)))
        .contains("\"status\":\"finished\"")
        .contains("\"antwort\":\"ja\"");
  }

  /**
   * A tool cancelled before it ends carries nothing out: deleting the instance, even with custom
   * listeners skipped, writes no carried results onto an execution that goes in the same breath
   * (CIB7-2215, review F5).
   */
  @Test
  public void aCancelledToolCarriesNothingOut() throws IOException {
    script.add(toolCall("fragen", "{}"));
    script.add(answer("Gefragt."));
    ProcessInstance pi = start(connectorModel("",
        "<camunda:inputParameter name='message'>Frag nach.</camunda:inputParameter>", "",
        "stub-key", ASK_A_PERSON), Collections.<String, Object>emptyMap());
    runWaitingTurn();
    assertThat(engine.getTaskService().createTaskQuery().taskName("Person").count()).isOne();

    engine.getRuntimeService().deleteProcessInstance(pi.getId(), "abgebrochen", true);

    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("adHocAgentCarriedResults").count())
        .as("no carried results were written while the tool was cancelled").isZero();
  }

  /** The agent asks, a person answers into the sub process, and the agent is asked again. */
  protected ProcessInstance askAPersonAndAnswer(String tool) {
    script.add(toolCall("fragen", "{}"));
    script.add(answer("Gefragt."));
    script.add(answer("Danke."));
    ProcessInstance pi = start(connectorModel("",
        "<camunda:inputParameter name='message'>Frag nach.</camunda:inputParameter>", "",
        "stub-key", tool), Collections.<String, Object>emptyMap());
    runWaitingTurn();
    engine.getTaskService().complete(
        engine.getTaskService().createTaskQuery().taskName("Person").singleResult().getId(),
        Collections.<String, Object>singletonMap("antwort", "ja"));
    TurnSteps.runToTheEnd(engine, pi.getId());
    assertThat(requestBodies).hasSize(3);
    return pi;
  }

  /**
   * A tool whose flow goes on along an inner flow is waiting while that flow waits, and is reported
   * when the flow ends: under the activity the agent called, with the activity it ended at.
   */
  @Test
  public void aChainWaitsUntilItsFlowEndsAndIsReportedUnderTheActivityTheAgentCalled()
      throws IOException {
    script.add(toolCall("anfragen", "{}"));
    script.add(answer("Gestartet."));
    script.add(answer("Fertig."));
    ProcessInstance pi = start(connectorModel("",
        "<camunda:inputParameter name='message'>Frag an.</camunda:inputParameter>", "",
        "stub-key",
        "      <serviceTask id='anfragen' name='Anfragen' camunda:expression='${\"gesendet\"}'"
        + " camunda:resultVariable='status' />"
        + "      <sequenceFlow id='weiter' sourceRef='anfragen' targetRef='warten' />"
        + "      <userTask id='warten' name='Warten' />"),
        Collections.<String, Object>emptyMap());
    runWaitingTurn();

    assertThat(toolResults(requestBodies.get(1))).as("the call, while its flow waits")
        .contains("\"status\":\"waiting\"");
    assertThat(lastUserMessage(requestBodies.get(1)))
        .contains("\"stillRunning\":[\"anfragen\"]");

    Task warten = engine.getTaskService().createTaskQuery()
        .processInstanceId(pi.getId()).taskDefinitionKey("warten").singleResult();
    engine.getTaskService().complete(warten.getId());
    runWaitingTurn();

    assertThat(lastUserMessage(requestBodies.get(2)))
        .contains("\"activityId\":\"anfragen\"")
        .contains("\"endedAt\":\"warten\"")
        .contains("\"status\":\"gesendet\"");
    assertThat(alive(pi)).isFalse();
  }

  /** Throws the BPMN error whose code its field {@code code} names. */
  public static class ThrowsTheCode implements JavaDelegate {
    private Expression code;

    public void setCode(Expression code) {
      this.code = code;
    }

    @Override
    public void execute(DelegateExecution execution) {
      throw new BpmnError(String.valueOf(code.getValue(execution)));
    }
  }

  /** A tool that throws {@code code}, the boundary event {@code catching} on it, and its path. */
  protected static String failingTool(String code, String catching) {
    return "      <serviceTask id='buchen' name='Buchen' camunda:class='"
        + ThrowsTheCode.class.getName() + "'><extensionElements>"
        + "<camunda:field name='code' stringValue='" + code + "' /></extensionElements>"
        + "</serviceTask>"
        + "      <boundaryEvent id='fachfehler' attachedToRef='buchen'>" + catching
        + "</boundaryEvent>"
        + "      <sequenceFlow id='e1' sourceRef='fachfehler' targetRef='fehlerMerken' />"
        + "      <serviceTask id='fehlerMerken' name='Fehler merken'"
        + " camunda:expression=\"${'kein Bestand'}\" camunda:resultVariable='fehler' />";
  }

  /**
   * B11: a tool whose BPMN error is caught on its boundary, and whose error path runs to its end
   * within the call, is not reported as finished: the tool result names the error's code and the
   * boundary event (CIB7-2166).
   */
  @Test
  public void aToolEndedByABpmnErrorWithinItsCallIsReportedWithTheErrorsCode() throws IOException {
    script.add(toolCall("buchen", "{}"));
    script.add(answer("Kein Bestand."));
    startWith(failingTool("KEIN_BESTAND", "<errorEventDefinition errorRef='KEIN_BESTAND' />"));

    runWaitingTurn();

    assertThat(toolResults(requestBodies.get(1)))
        .contains("\"status\":\"interrupted\"")
        .contains("\"boundaryEvent\":\"fachfehler\"")
        .contains("\"eventType\":\"error\"")
        .contains("\"errorCode\":\"KEIN_BESTAND\"")
        .contains("\"endedAt\":\"fehlerMerken\"")
        .doesNotContain("\"status\":\"finished\"");
  }

  /**
   * A boundary event that catches every error is told the code that was thrown, which the model
   * names nowhere, without an errorCodeVariable (CIB7-2166).
   */
  @Test
  public void aCatchAllBoundaryEventReportsTheCodeThatWasThrown() throws IOException {
    script.add(toolCall("buchen", "{}"));
    script.add(answer("Gescheitert."));
    startWith(failingTool("GESPERRT", "<errorEventDefinition />"));

    runWaitingTurn();

    assertThat(toolResults(requestBodies.get(1)))
        .contains("\"status\":\"interrupted\"")
        .contains("\"errorCode\":\"GESPERRT\"");
  }

  /**
   * The modeller's errorCodeVariable still gets the code: the agent's own definition takes it over
   * with everything else the engine's had.
   */
  @Test
  public void theModellersErrorCodeVariableStillGetsTheCode() throws IOException {
    script.add(toolCall("buchen", "{}"));
    script.add(answer("Gescheitert."));
    ProcessInstance pi = startWith(failingTool("GESPERRT",
        "<errorEventDefinition camunda:errorCodeVariable='grund' />")
        .replace("<serviceTask id='fehlerMerken' name='Fehler merken'",
            "<userTask id='fehlerMerken' name='Fehler merken'")
        .replace(" camunda:expression=\"${'kein Bestand'}\" camunda:resultVariable='fehler' />",
            " />"));

    runWaitingTurn();

    assertThat(engine.getRuntimeService().getVariable(pi.getId(), "grund")).isEqualTo("GESPERRT");
    assertThat(toolResults(requestBodies.get(1)))
        .as("the error path waits for a person, so the tool still runs, interrupted")
        .contains("\"status\":\"waiting\"")
        .contains("\"interruptedBy\"")
        .contains("\"errorCode\":\"GESPERRT\"");
  }

  /** A tool that completes is reported as before: finished, with no word of an interruption. */
  @Test
  public void aToolThatCompletesIsStillReportedAsFinished() throws IOException {
    script.add(toolCall("buchen", "{}"));
    script.add(answer("Gebucht."));
    startWith("      <serviceTask id='buchen' name='Buchen' camunda:expression='${true}' />"
        + "      <boundaryEvent id='fachfehler' attachedToRef='buchen'>"
        + "<errorEventDefinition /></boundaryEvent>");

    runWaitingTurn();

    assertThat(toolResults(requestBodies.get(1)))
        .contains("\"status\":\"finished\"")
        .doesNotContain("interrupted")
        .doesNotContain("endedBy");
  }

  /**
   * B12: a waiting tool ended by the timer on its boundary is reported in a later state block as
   * interrupted by that timer, and still says where its flow ended (CIB7-2147, CIB7-2166).
   */
  @Test
  public void aToolEndedByItsTimerIsReportedAsInterruptedByIt() throws IOException {
    script.add(toolCall("freigabe", "{}"));
    script.add(answer("Warte auf die Freigabe."));
    script.add(answer("Die Freigabe kam nicht."));
    startWith("      <userTask id='freigabe' name='Freigabe' />"
        + "      <boundaryEvent id='zuLange' attachedToRef='freigabe'><timerEventDefinition>"
        + "<timeDuration>PT1H</timeDuration></timerEventDefinition></boundaryEvent>");

    // The turn's steps run every job there is, the boundary timer among them.
    runWaitingTurn();

    String later = requestBodies.subList(1, requestBodies.size()).stream()
        .map(body -> {
          try {
            return lastUserMessage(body);
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
        })
        .collect(Collectors.joining("\n"));
    assertThat(later)
        .contains("\"activityId\":\"freigabe\"")
        .contains("\"status\":\"interrupted\"")
        .contains("\"boundaryEvent\":\"zuLange\"")
        .contains("\"eventType\":\"timer\"")
        .contains("\"endedAt\":\"zuLange\"");
  }

  /**
   * A waiting tool cancelled from outside, by a process instance modification, is listed in the
   * next state block as cancelled, so the agent stops waiting for it (CIB7-2166).
   */
  @Test
  public void aToolCancelledFromOutsideIsReportedAsCancelled() throws IOException {
    script.add(toolCall("freigabe", "{}"));
    script.add(answer("Warte auf die Freigabe."));
    script.add(answer("Verstanden."));
    ProcessInstance pi = startWith("      <userTask id='freigabe' name='Freigabe' />"
        + "      <userTask id='worker' name='Worker' />");
    runWaitingTurn();
    // Someone else's work keeps the scope: cancelling its only child would cancel the scope too.
    String scope = engine.getRuntimeService().createExecutionQuery().activityId("adHoc")
        .singleResult().getId();
    engine.getRuntimeService().createAdHocSubProcessActivation(scope)
        .startActivity("worker").execute();
    int before = requestBodies.size();

    engine.getRuntimeService().createProcessInstanceModification(pi.getId())
        .cancelAllForActivity("freigabe").execute();
    runWaitingTurn();

    assertThat(requestBodies).hasSizeGreaterThan(before);
    assertThat(lastUserMessage(requestBodies.get(before)))
        .contains("\"activityId\":\"freigabe\"")
        .contains("\"status\":\"cancelled\"")
        .contains("cancelled from outside the agent");
  }

  /** A chain along an inner flow: a tool that ends at once and a person who has to act after it. */
  protected static final String CHAIN =
      "      <serviceTask id='anfragen' name='Anfragen' camunda:expression='${\"gesendet\"}'"
      + " camunda:resultVariable='status' />"
      + "      <sequenceFlow id='weiter' sourceRef='anfragen' targetRef='warten' />"
      + "      <userTask id='warten' name='Warten' />";

  protected ProcessInstance startWith(String children) {
    return start(connectorModel("",
        "<camunda:inputParameter name='message'>Erledige das.</camunda:inputParameter>", "",
        "stub-key", children), Collections.<String, Object>emptyMap());
  }

  protected void completeTask(ProcessInstance pi, String key, Map<String, Object> variables) {
    Task task = engine.getTaskService().createTaskQuery()
        .processInstanceId(pi.getId()).taskDefinitionKey(key).singleResult();
    engine.getTaskService().complete(task.getId(), variables);
  }

  /** completeScope is refused while a chain the agent started still waits: ending would cancel it. */
  @Test
  public void completeScopeIsRefusedWhileAChainStillWaits() throws IOException {
    script.add(toolCall("anfragen", "{}"));
    script.add(toolCall("completeScope", "{}"));
    script.add(answer("Warte."));
    script.add(answer("Fertig."));
    ProcessInstance pi = startWith(CHAIN);
    runWaitingTurn();

    assertThat(toolResults(requestBodies.get(2))).as("the second call, while warten waits")
        .contains("Cannot end the ad hoc sub process while");
    assertThat(engine.getTaskService().createTaskQuery().processInstanceId(pi.getId())
        .taskDefinitionKey("warten").count()).as("the chain is not cancelled").isEqualTo(1);

    completeTask(pi, "warten", Collections.<String, Object>emptyMap());
    runWaitingTurn();
    assertThat(alive(pi)).isFalse();
  }

  /**
   * A nested ad hoc scope as a tool: when it ends, the outer agent gets a turn, and its report
   * lists the inner scope with what it declares.
   */
  @Test
  public void aNestedAdHocToolIsReportedToTheOuterAgent() throws IOException {
    script.add(toolCall("innerScope", "{}"));
    script.add(answer("Gestartet."));
    script.add(answer("Fertig."));
    ProcessInstance pi = startWith(
        "      <adHocSubProcess id='innerScope' name='Innerer Bereich'><extensionElements>"
        + "<camunda:properties>"
        + "<camunda:property name='activitiesToActivate' value='innerTask' />"
        + "<camunda:property name='adHocResultVariables' value='answer' />"
        + "</camunda:properties></extensionElements>"
        + "<userTask id='innerTask'><extensionElements><camunda:formData>"
        + "<camunda:formField id='answer' type='string' /></camunda:formData>"
        + "</extensionElements></userTask></adHocSubProcess>");
    runWaitingTurn();

    completeTask(pi, "innerTask", Collections.<String, Object>singletonMap("answer", "ja"));
    runWaitingTurn();

    assertThat(requestBodies).hasSize(3);
    assertThat(lastUserMessage(requestBodies.get(2)))
        .contains("\"activityId\":\"innerScope\"")
        .contains("\"answer\":\"ja\"");
    assertThat(alive(pi)).isFalse();
  }

  /**
   * A tool writing the variables the agent keeps its results under, the old name or the new one,
   * changes nothing of what the agent is told.
   */
  @Test
  public void aToolWritingTheAgentsResultNamesChangesNothing() throws IOException {
    script.add(toolCall("faelschenAlt", "{}"));
    script.add(toolCall("faelschenNeu", "{}"));
    script.add(toolCall("worker", "{}"));
    script.add(answer("Gestartet."));
    script.add(answer("Fertig."));
    ProcessInstance pi = startWith(
        "      <serviceTask id='faelschenAlt' camunda:expression='${\"gefaelscht\"}'"
        + " camunda:resultVariable='cibsevenAgenticResults' />"
        + "      <serviceTask id='faelschenNeu' camunda:expression='${\"gefaelscht\"}'"
        + " camunda:resultVariable='adHocAgentResults' />"
        + WORKER_WITH_ANSWER);
    runWaitingTurn();

    completeWorker("ja");
    runWaitingTurn();

    assertThat(lastUserMessage(requestBodies.get(4)))
        .as("the next turn reports the worker as it ended")
        .contains("\"activityId\":\"worker\"")
        .contains("\"antwort\":\"ja\"");
    assertThat(alive(pi)).isFalse();
  }

  /**
   * A value that is not a primitive is shown as a descriptor, and keeping it for the agent does
   * not fail when the step commits.
   */
  @Test
  public void anObjectResultIsShownAsADescriptor() throws IOException {
    script.add(toolCall("pruefer", "{}"));
    script.add(answer("Gestartet."));
    script.add(answer("Fertig."));
    ProcessInstance pi = startWith(
        "      <userTask id='pruefer'><extensionElements><camunda:properties>"
        + "<camunda:property name='adHocResultVariables' value='objekt' />"
        + "</camunda:properties></extensionElements></userTask>");
    runWaitingTurn();

    HashMap<String, Object> objekt = new HashMap<String, Object>();
    objekt.put("menge", 3);
    completeTask(pi, "pruefer", Collections.<String, Object>singletonMap("objekt", objekt));
    runWaitingTurn();

    assertThat(lastUserMessage(requestBodies.get(2)))
        .contains("\"activityId\":\"pruefer\"")
        .contains("value, not shown");
    assertThat(alive(pi)).isFalse();
  }

  /**
   * Gathering a result reads it without deserializing it: the child's end is someone's
   * completion, and an object that cannot be read here -- its class gone or changed -- must not
   * fail it. The value was written in an earlier transaction, so it is read back from the database.
   */
  @Test
  public void anObjectResultThatCannotBeReadDoesNotFailTheCompletion() throws IOException {
    script.add(toolCall("pruefer", "{}"));
    script.add(answer("Gestartet."));
    script.add(answer("Fertig."));
    ProcessInstance pi = startWith(
        "      <userTask id='pruefer'><extensionElements><camunda:properties>"
        + "<camunda:property name='adHocResultVariables' value='objekt' />"
        + "</camunda:properties></extensionElements></userTask>");
    runWaitingTurn();

    Task task = engine.getTaskService().createTaskQuery()
        .processInstanceId(pi.getId()).taskDefinitionKey("pruefer").singleResult();
    engine.getTaskService().setVariable(task.getId(), "objekt", new Unreadable());
    engine.getTaskService().complete(task.getId());
    runWaitingTurn();

    assertThat(lastUserMessage(requestBodies.get(2)))
        .contains("\"activityId\":\"pruefer\"")
        .contains("value, not shown");
    assertThat(alive(pi)).isFalse();
  }

  /** Written as any object is, and never read back: as if its class had changed meanwhile. */
  public static class Unreadable implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    private void readObject(java.io.ObjectInputStream in) throws java.io.IOException {
      throw new java.io.InvalidObjectException("this class cannot read what was written");
    }
  }

  /**
   * A child's end writes the results and nothing else of the agent's state, although it reads
   * the pending list to find the tool the agent called. The engine serializes an object it read
   * again when the command ends, and writes it back if the bytes differ: a pending list that
   * came back changed would be written in the person's transaction, and collide with a step
   * that writes the agent's state at the same time. A hash map copied and grown by one entry,
   * as the list of two waiting tools was, is such a map, and whoever reads it first writes it:
   * here the person, who completes a task before the next step has read the list.
   */
  @Test
  public void aChildsEndWritesNothingOfTheAgentsStateButTheResults() throws IOException {
    script.add(toolCall("pruefA", "{}"));
    script.add(toolCall("pruefB", "{}"));
    script.add(answer("Warte."));
    ProcessInstance pi = startWith("      <userTask id='pruefA' /><userTask id='pruefB' />");
    runOneJob();
    runOneJob();
    String state = engine.getRuntimeService().createVariableInstanceQuery()
        .processInstanceIdIn(pi.getId()).variableName(AdHocAgentState.STATE_MARKER)
        .singleResult().getExecutionId();
    long before = stateWrites(pi, state);

    completeTask(pi, "pruefA", Collections.<String, Object>emptyMap());

    assertThat(stateWrites(pi, state)).as("the person's completion wrote the results only")
        .isEqualTo(before);
  }

  /**
   * The agent state carries three variables, one per writer (CIB7-2200): the state map, which
   * only a step writes; the results, which only a child's end writes; and the conversation, which
   * the connector's memory store writes. Checked while the scope still waits, after a result was
   * gathered and read.
   */
  @Test
  public void theAgentStateCarriesThreeVariables() throws IOException {
    script.add(toolCall("pruefA", "{}"));
    script.add(toolCall("pruefB", "{}"));
    script.add(answer("Warte."));
    script.add(answer("Weiter."));
    ProcessInstance pi = startWith("      <userTask id='pruefA' /><userTask id='pruefB' />");
    runWaitingTurn();
    completeTask(pi, "pruefA", Collections.<String, Object>emptyMap());
    runWaitingTurn();

    assertThat(alive(pi)).as("pruefB still waits").isTrue();
    String state = engine.getRuntimeService().createVariableInstanceQuery()
        .processInstanceIdIn(pi.getId()).variableName(AdHocAgentState.STATE_MARKER)
        .singleResult().getExecutionId();
    List<String> names = engine.getRuntimeService().createVariableInstanceQuery()
        .processInstanceIdIn(pi.getId()).list().stream()
        .filter(variable -> state.equals(variable.getExecutionId()))
        .map(VariableInstance::getName).sorted().collect(Collectors.toList());
    assertThat(names).hasSize(3);
    assertThat(names.get(0)).isEqualTo("adHocAgentResults");
    assertThat(names.get(1)).isEqualTo(AdHocAgentState.STATE_MARKER);
    assertThat(names.get(2)).startsWith("cibseven-connect-ai-agent-memory_adhoc-");
  }

  /** History updates of the agent state's variables other than the results. */
  private long stateWrites(ProcessInstance pi, String stateExecution) {
    return engine.getHistoryService().createHistoricDetailQuery()
        .processInstanceId(pi.getId()).variableUpdates().list().stream()
        .map(detail -> (HistoricVariableUpdate) detail)
        .filter(update -> stateExecution.equals(update.getExecutionId()))
        .filter(update -> !"adHocAgentResults".equals(update.getVariableName()))
        .count();
  }

  /**
   * Only a child that ends writes the results; a step that reads them writes nothing. The engine
   * serializes an object it read again when the command ends, and writes it back if the bytes
   * differ. A map whose bytes change on the way back would turn every read into a write, and the
   * next step would collide with a child appending at the same time. On Java 17 a hash map of
   * twelve entries is such a map: twelve declared result variables made one.
   */
  @Test
  public void aStepThatReadsTheResultsDoesNotWriteThem() throws IOException {
    StringBuilder names = new StringBuilder();
    Map<String, Object> values = new HashMap<>();
    for (int i = 1; i <= 12; i++) {
      String name = String.format("wert%02d", i);
      names.append(i == 1 ? "" : ",").append(name);
      values.put(name, i);
    }
    script.add(toolCall("pruefer", "{}"));
    script.add(answer("Gestartet."));
    script.add(answer("Fertig."));
    ProcessInstance pi = startWith(
        "      <userTask id='pruefer'><extensionElements><camunda:properties>"
        + "<camunda:property name='adHocResultVariables' value='" + names + "' />"
        + "</camunda:properties></extensionElements></userTask>");
    runWaitingTurn();

    completeTask(pi, "pruefer", values);
    runWaitingTurn();

    assertThat(lastUserMessage(requestBodies.get(2))).as("the step read the twelve values")
        .contains("\"wert01\":1").contains("\"wert12\":12");
    long writes = engine.getHistoryService().createHistoricDetailQuery()
        .processInstanceId(pi.getId()).variableUpdates().list().stream()
        .filter(detail -> "adHocAgentResults".equals(((HistoricVariableUpdate) detail).getVariableName()))
        .count();
    assertThat(writes).as("one write, by the child that ended").isEqualTo(1);
  }

  // --- the process application that deployed the model

  /**
   * On a shared engine the model belongs to a process application, and the connector's
   * parameters see what that application provides, as they do on a service task: a bean only its
   * EL resolver knows, and its class loader as the thread's while the expression runs.
   *
   * <p>The application below switches the class loader as a servlet or EJB process application
   * does; the embedded one alone would not, because it shares the engine's.
   */
  @Test
  public void aTurnRunsInsideTheProcessApplicationThatDeployedTheModel() {
    script.add(answer("Done."));
    final ClassLoader applicationLoader =
        new URLClassLoader(new URL[0], getClass().getClassLoader());
    final Order order = new Order();
    EmbeddedProcessApplication application = new EmbeddedProcessApplication() {
      @Override
      public ELResolver getElResolver() {
        return new ReadOnlyMapELResolver(Collections.<Object, Object>singletonMap("order", order));
      }

      @Override
      public ClassLoader getProcessApplicationClassloader() {
        return applicationLoader;
      }

      @Override
      public <T> T execute(Callable<T> callable) throws ProcessApplicationExecutionException {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(applicationLoader);
        try {
          return callable.call();
        } catch (Exception e) {
          throw new ProcessApplicationExecutionException(e);
        } finally {
          Thread.currentThread().setContextClassLoader(original);
        }
      }
    };
    start(connectorModel(
        "<camunda:inputParameter name='message'>Bearbeite ${order.summary}.</camunda:inputParameter>",
        ""), Collections.<String, Object>emptyMap(), application);

    runWaitingTurn();

    assertThat(lastToolResult()).contains("Bearbeite Bestellung B-17.");
    assertThat(order.loaderSeen)
        .as("the thread's class loader while the message was evaluated")
        .isSameAs(applicationLoader);
  }

  /** What only the process application knows. */
  public static class Order {
    volatile ClassLoader loaderSeen;

    public String getSummary() {
      loaderSeen = Thread.currentThread().getContextClassLoader();
      return "Bestellung B-17";
    }
  }

  protected static String connectorModel(String inputs, String outputs) {
    return connectorModel(inputs, outputs, "stub-key");
  }

  protected static String connectorModel(String inputs, String outputs, String apiKey) {
    return connectorModel("", inputs, outputs, apiKey);
  }

  /**
   * One user task as the tool; the agent's connector on the scope, pointed at the stub, after
   * the scope's own extension elements.
   */
  protected static String connectorModel(String scopeExtensions, String inputs, String outputs,
      String apiKey) {
    return connectorModel(scopeExtensions, inputs, outputs, apiKey,
        "      <userTask id='worker' name='Worker' />");
  }

  /** As above, with the scope's children given. */
  protected static String connectorModel(String scopeExtensions, String inputs, String outputs,
      String apiKey, String children) {
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + "             targetNamespace='http://cibseven.org/agentic-turn'>"
        + "  <process id='agenticTurn' isExecutable='true'>"
        + "    <startEvent id='start' />"
        + "    <sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + "    <adHocSubProcess id='adHoc'>"
        + "      <extensionElements>" + scopeExtensions + "<camunda:connector>"
        + "        <camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "        <camunda:inputOutput>" + inputs
        + "          <camunda:inputParameter name='model'>stub</camunda:inputParameter>"
        + "          <camunda:inputParameter name='apiKey'>" + apiKey + "</camunda:inputParameter>"
        + "          <camunda:inputParameter name='baseUrl'>http://127.0.0.1:" + STUB_PORT
        + "/v1</camunda:inputParameter>" + outputs
        + "        </camunda:inputOutput>"
        + "      </camunda:connector></extensionElements>"
        + children
        + "    </adHocSubProcess>"
        + "    <sequenceFlow id='f2' sourceRef='adHoc' targetRef='end' />"
        + "    <endEvent id='end' />"
        + "  </process>"
        + "</definitions>";
  }

  /** As above, with what follows the scope in the process: a boundary event and its path. */
  protected static String connectorModel(String scopeExtensions, String inputs, String outputs,
      String apiKey, String children, String afterScope) {
    return connectorModel(scopeExtensions, inputs, outputs, apiKey, children)
        .replace("    <sequenceFlow id='f2'", afterScope + "    <sequenceFlow id='f2'");
  }

  /** Runs the waiting turn to its end: every step of it, each a job of its own. */
  protected void runWaitingTurn() {
    Job job = engine.getManagementService().createJobQuery().singleResult();
    TurnSteps.runToTheEnd(engine, job.getProcessInstanceId());
  }

  protected boolean alive(ProcessInstance pi) {
    return engine.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(pi.getId()).singleResult() != null;
  }

  /** What the tool answered on the last call, as the stub saw it come back. */
  protected String lastToolResult() {
    return lastRequestBody;
  }

  private volatile String lastRequestBody = "";
  private final AtomicInteger requests = new AtomicInteger();
  private volatile String lastAuthorization = "";
  private final List<String> requestBodies = new CopyOnWriteArrayList<>();
  /** When set, the next request is held until it is counted down: a model that takes its time. */
  private volatile CountDownLatch holdNextRequest;
  private final CountDownLatch requestHeld = new CountDownLatch(1);

  private void respond(HttpExchange exchange) throws IOException {
    lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    requests.incrementAndGet();
    requestBodies.add(lastRequestBody);
    CountDownLatch release = holdNextRequest;
    if (release != null) {
      holdNextRequest = null;
      requestHeld.countDown();
      try {
        release.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    lastAuthorization = String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"));
    String body = script.poll();
    if (body == null) {
      body = answer("No script left.");
    }
    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, payload.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(payload);
    }
  }

  /** The texts of the tool results in a chat completion request, one after the other. */
  private static String toolResults(String requestBody) throws IOException {
    StringBuilder text = new StringBuilder();
    for (JsonNode message : new ObjectMapper().readTree(requestBody).path("messages")) {
      if ("tool".equals(message.path("role").asText())) {
        text.append(message.path("content").asText()).append('\n');
      }
    }
    return text.toString();
  }

  /** The text of the last user message in a chat completion request. */
  private static String lastUserMessage(String requestBody) throws IOException {
    String text = "";
    for (JsonNode message : new ObjectMapper().readTree(requestBody).path("messages")) {
      if ("user".equals(message.path("role").asText())) {
        text = message.path("content").asText();
      }
    }
    return text;
  }

  private static String toolCall(String name, String arguments) {
    return "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,"
        + "\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
        + "\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
        + "\"function\":{\"name\":\"" + name + "\",\"arguments\":\"" + arguments + "\"}}]},"
        + "\"finish_reason\":\"tool_calls\"}],"
        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
  }

  private static String answer(String text) {
    return "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,"
        + "\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
        + "\"content\":\"" + text + "\"},\"finish_reason\":\"stop\"}],"
        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
  }

  protected ProcessInstance start() {
    return start(model(), Collections.<String, Object>emptyMap());
  }

  /**
   * A JSON format for maps, as Spin's on an engine whose default format is JSON: a whole number
   * comes back as an Integer, whatever it was written as.
   */
  protected static class JsonMapSerializer extends AbstractObjectValueSerializer {

    private static final ObjectMapper JSON = new ObjectMapper();

    public JsonMapSerializer() {
      super(Variables.SerializationDataFormats.JSON.getName());
    }

    @Override
    public String getName() {
      return "test-json-map";
    }

    @Override
    protected String getTypeNameForDeserialized(Object deserializedObject) {
      return deserializedObject.getClass().getName();
    }

    @Override
    protected byte[] serializeToByteArray(Object deserializedObject) throws Exception {
      return JSON.writeValueAsBytes(deserializedObject);
    }

    @Override
    protected Object deserializeFromByteArray(byte[] object, String objectTypeName)
        throws Exception {
      return JSON.readValue(object, java.util.TreeMap.class);
    }

    @Override
    protected boolean isSerializationTextBased() {
      return true;
    }

    @Override
    protected boolean canSerializeValue(Object value) {
      return value instanceof java.util.Map;
    }
  }

  protected ProcessInstance start(String bpmn, java.util.Map<String, Object> variables) {
    return start(bpmn, variables, null);
  }

  /** As {@link #start(String, java.util.Map)}, deployed by {@code application} when it is given. */
  protected ProcessInstance start(String bpmn, java.util.Map<String, Object> variables,
      ProcessApplicationInterface application) {
    return start(bpmn, variables, application, null);
  }

  /** As {@link #start(String, java.util.Map, ProcessApplicationInterface)}, for {@code tenantId}. */
  protected ProcessInstance start(String bpmn, java.util.Map<String, Object> variables,
      ProcessApplicationInterface application, String tenantId) {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName("agentic-turn");
    configuration.setJdbcUrl("jdbc:h2:mem:agentic-turn;DB_CLOSE_DELAY=-1");
    // Off: the turn is run by hand, so the outcome is a step and not a race.
    configuration.setJobExecutorActivate(false);
    configuration.setHistoryTimeToLive("P30D");
    configuration.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
    configuration.setFailedJobRetryTimeCycle(engineRetryCycle);
    if (defaultSerializationFormat != null) {
      configuration.setDefaultSerializationFormat(defaultSerializationFormat);
      configuration.setCustomPreVariableSerializers(
          Collections.<TypedValueSerializer>singletonList(new JsonMapSerializer()));
    }
    configuration.setProcessEnginePlugins(
        Collections.<ProcessEnginePlugin>singletonList(new ConnectProcessEnginePlugin()));
    engine = configuration.buildProcessEngine();

    DeploymentBuilder deployment = (application == null)
        ? engine.getRepositoryService().createDeployment()
        : engine.getRepositoryService().createDeployment(application.getReference());
    if (tenantId != null) {
      deployment.tenantId(tenantId);
    }
    deployment.addString("agentic-turn.bpmn20.xml", bpmn).deploy();
    return engine.getRuntimeService().startProcessInstanceByKey("agenticTurn", variables);
  }

  private static String model() {
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + "             targetNamespace='http://cibseven.org/agentic-turn'>"
        + "  <process id='agenticTurn' isExecutable='true'>"
        + "    <startEvent id='start' />"
        + "    <sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + "    <adHocSubProcess id='adHoc'>"
        + "      <extensionElements><camunda:connector>"
        + "        <camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "        <camunda:inputOutput>"
        + "          <camunda:inputParameter name='message'>Do the work.</camunda:inputParameter>"
        + "          <camunda:inputParameter name='model'>stub</camunda:inputParameter>"
        + "          <camunda:inputParameter name='apiKey'>stub-key</camunda:inputParameter>"
        + "          <camunda:inputParameter name='baseUrl'>http://127.0.0.1:" + STUB_PORT
        + "/v1</camunda:inputParameter>"
        + "          <camunda:outputParameter name='summary'>${output}</camunda:outputParameter>"
        + "        </camunda:inputOutput>"
        + "      </camunda:connector></extensionElements>"
        + "      <userTask id='worker' name='Worker' />"
        + "      <userTask id='second' name='Second' />"
        + "      <sequenceFlow id='inner' sourceRef='worker' targetRef='second' />"
        + "    </adHocSubProcess>"
        + "    <sequenceFlow id='f2' sourceRef='adHoc' targetRef='end' />"
        + "    <endEvent id='end' />"
        + "  </process>"
        + "</definitions>";
  }
}
