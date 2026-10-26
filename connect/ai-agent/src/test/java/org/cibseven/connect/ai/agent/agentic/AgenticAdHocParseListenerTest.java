/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
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
package org.cibseven.connect.ai.agent.agentic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.Collections;
import java.util.List;

import org.cibseven.bpm.engine.ParseException;
import org.cibseven.bpm.engine.Problem;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocSubProcessActivityBehavior;
import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocSubProcessObserver;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.runtime.Execution;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * What the parse listener does to an agentic ad hoc scope, and what it refuses.
 *
 * <p>Jobs are asserted, never executed: running one would call the agent, which this module
 * cannot see. The turn itself is covered where the agent lives.
 */
public class AgenticAdHocParseListenerTest {

  protected ProcessEngine engine;
  protected ProcessEngineConfigurationImpl config;

  @BeforeEach
  public void setUp() {
    config = new StandaloneInMemProcessEngineConfiguration();
    config.setJdbcUrl("jdbc:h2:mem:agenticParse;DB_CLOSE_DELAY=1000");
    config.setJobExecutorActivate(false);
    config.setEnforceHistoryTimeToLive(false);
    config.setProcessEnginePlugins(
        Collections.singletonList(new ConnectProcessEnginePlugin()));
    engine = config.buildProcessEngine();
  }

  @AfterEach
  public void tearDown() {
    engine.close();
  }

  @Test
  public void entersParked() {
    ProcessInstance pi = start(agentic("<userTask id='worker' />"));

    // Parked: nothing was activated, and the scope did not end on the count rule.
    assertThat(engine.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(pi.getId()).singleResult()).isNotNull();
    assertThat(engine.getTaskService().createTaskQuery().list()).isEmpty();
  }

  @Test
  public void schedulesTheFirstTurnOnEntry() {
    ProcessInstance pi = start(agentic("<userTask id='worker' />"));

    List<Job> jobs = engine.getManagementService().createJobQuery().list();
    assertThat(jobs).hasSize(1);
    assertThat(((org.cibseven.bpm.engine.impl.persistence.entity.JobEntity) jobs.get(0))
        .getJobHandlerType()).isEqualTo(AgenticTurnJobHandler.TYPE);
  }

  @Test
  public void staysAtOneJobWhileOneIsWaiting() {
    ProcessInstance pi = start(agentic("<userTask id='a' /><userTask id='b' />"));
    String scope = scopeExecutionId(pi.getId());

    engine.getRuntimeService().createAdHocSubProcessActivation(scope)
        .startActivity("a").execute();
    engine.getRuntimeService().createAdHocSubProcessActivation(scope)
        .startActivity("b").execute();
    for (Task task : engine.getTaskService().createTaskQuery().list()) {
      engine.getTaskService().complete(task.getId());
    }

    // Two children ended, and the entry job was still waiting: one turn, not three.
    assertThat(engine.getManagementService().createJobQuery().list()).hasSize(1);
  }

  @Test
  public void scheduresAFurtherTurnOnceTheWaitingOneIsGone() {
    ProcessInstance pi = start(agentic("<userTask id='worker' />"));
    String scope = scopeExecutionId(pi.getId());
    engine.getManagementService().deleteJob(
        engine.getManagementService().createJobQuery().singleResult().getId());

    engine.getRuntimeService().createAdHocSubProcessActivation(scope)
        .startActivity("worker").execute();
    engine.getTaskService().complete(
        engine.getTaskService().createTaskQuery().singleResult().getId());

    assertThat(engine.getManagementService().createJobQuery().list()).hasSize(1);
  }

  /**
   * The next turn comes from the scope's behaviour, which reports a child once, at the end of its
   * chain. Nothing of ours sits on the children: a listener there is what skipCustomListeners and a
   * transaction boundary after the child could hide (CIB7-2140).
   */
  @Test
  public void theScopeCarriesOneTriggerAndTheChildrenNoListenerOfOurs() {
    deploy(agentic("<userTask id='a' /><userTask id='b' />"
        + "<sequenceFlow id='inner' sourceRef='a' targetRef='b' />"));

    assertThat(triggersOn("adHoc")).isOne();
    assertThat(ourListenersOn("a")).isZero();
    assertThat(ourListenersOn("b")).isZero();
  }

  /**
   * A tool that is a scope of its own gets one listener of ours, which carries the results it holds
   * there out as it ends (CIB7-2215); a plain tool, and anything that is no tool, gets none.
   */
  @Test
  public void onlyAToolWithAScopeOfItsOwnCarriesItsResultsOut() {
    deploy(agentic("<subProcess id='sub'><startEvent id='s1' /><sequenceFlow id='i1' sourceRef='s1'"
        + " targetRef='e1' /><endEvent id='e1' /></subProcess><userTask id='simpleTool' />"));

    assertThat(ourListenersOn("sub")).isOne();
    assertThat(ourListenersOn("simpleTool")).isZero();
    assertThat(ourListenersOn("s1")).as("inside a tool: no tool").isZero();
  }

  /**
   * An activity further along a tool's chain, reached by an inner flow, carries its results out
   * too: the chain is reported under the tool (CIB7-2215, review F3). An event sub process does not.
   */
  @Test
  public void aScopeFurtherAlongAChainCarriesItsResultsOut() {
    deploy(agentic("<serviceTask id='anstossen' camunda:expression='${1}' />"
        + "<sequenceFlow id='weiter' sourceRef='anstossen' targetRef='fragen' />"
        + "<subProcess id='fragen'><startEvent id='fs' /><sequenceFlow id='fs1' sourceRef='fs'"
        + " targetRef='fe' /><endEvent id='fe' /></subProcess>"
        + "<subProcess id='onError' triggeredByEvent='true'>"
        + "<startEvent id='es'><errorEventDefinition /></startEvent>"
        + "<sequenceFlow id='e1' sourceRef='es' targetRef='ee' /><endEvent id='ee' /></subProcess>"));

    assertThat(ourListenersOn("fragen")).isOne();
    assertThat(ourListenersOn("anstossen")).isZero();
    assertThat(ourListenersOn("onError")).isZero();
  }

  /**
   * Inside a multi-instance body, an activity with an input or output mapping and a call activity
   * are no scope, but each instance's execution holds their variables: they carry them out too. So
   * does a multi-instance task without a mapping: each instance holds its loop variables.
   */
  @Test
  public void aMultiInstanceToolThatHoldsVariablesCarriesItsResultsOut() {
    String parallelTwice = "<multiInstanceLoopCharacteristics isSequential='false'>"
        + "<loopCardinality>2</loopCardinality></multiInstanceLoopCharacteristics>";
    deploy(agentic("<userTask id='mapped'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='votum'>${null}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements>" + parallelTwice + "</userTask>"
        + "<callActivity id='called' calledElement='anderer'>" + parallelTwice + "</callActivity>"
        + "<userTask id='bare'>" + parallelTwice + "</userTask>"));

    assertThat(ourListenersOn("mapped")).isOne();
    assertThat(ourListenersOn("called")).isOne();
    assertThat(ourListenersOn("bare")).as("the loop variables are each instance's own").isOne();
  }

  // --- what wakes the next turn (CIB7-2140) ----------------------------------

  /**
   * A tool with asyncAfter ends behind the transaction boundary after it. It was started while a
   * turn was pending, so nothing could be scheduled then; its continuation has to wake the agent.
   */
  @Test
  public void anAsyncAfterToolWakesTheAgentWhenItsContinuationHasRun() {
    ProcessInstance pi = start(agentic(
        "<serviceTask id='later' camunda:expression='${1}' camunda:asyncAfter='true' />"));
    engine.getRuntimeService().createAdHocSubProcessActivation(scopeExecutionId(pi.getId()))
        .startActivity("later").execute();
    deleteTurnJobs();

    Job continuation = engine.getManagementService().createJobQuery().singleResult();
    engine.getManagementService().executeJob(continuation.getId());

    assertThat(turnJobs()).hasSize(1);
  }

  /** A nested ad hoc scope used as a tool wakes the agent of the scope it is a tool of. */
  @Test
  public void aNestedAdHocToolWakesTheOuterAgentWhenItEnds() {
    ProcessInstance pi = start(agentic(
        "<adHocSubProcess id='nested'><userTask id='inner' /></adHocSubProcess>"));
    deleteTurnJobs();
    engine.getRuntimeService().createAdHocSubProcessActivation(scopeExecutionId(pi.getId()))
        .startActivity("nested").execute();
    String nested = engine.getRuntimeService().createExecutionQuery().processInstanceId(pi.getId())
        .activityId("nested").list().get(0).getId();
    engine.getRuntimeService().createAdHocSubProcessActivation(nested)
        .startActivity("inner").execute();

    engine.getTaskService().complete(
        engine.getTaskService().createTaskQuery().taskDefinitionKey("inner").singleResult().getId());

    assertThat(turnJobs()).hasSize(1);
  }

  /** A tool cancelled by a modification that skips custom listeners still wakes the agent. */
  @Test
  public void aToolCancelledWithoutCustomListenersWakesTheAgent() {
    ProcessInstance pi = start(agentic("<userTask id='a' /><userTask id='b' />"));
    String scope = scopeExecutionId(pi.getId());
    engine.getRuntimeService().createAdHocSubProcessActivation(scope).startActivity("a").execute();
    engine.getRuntimeService().createAdHocSubProcessActivation(scope).startActivity("b").execute();
    deleteTurnJobs();
    String a = engine.getRuntimeService().getActivityInstance(pi.getId())
        .getActivityInstances("a")[0].getId();

    engine.getRuntimeService().createProcessInstanceModification(pi.getId())
        .cancelActivityInstance(a).execute(true, false);

    assertThat(turnJobs()).hasSize(1);
  }

  /** Entering the scope without custom listeners still gives it its first turn. */
  @Test
  public void aScopeEnteredWithoutCustomListenersGetsItsFirstTurn() {
    deploy(agentic("<userTask id='worker' />"));

    engine.getRuntimeService().createProcessInstanceByKey("agenticScope")
        .startBeforeActivity("adHoc").execute(true, false);

    assertThat(turnJobs()).hasSize(1);
  }

  /** A multi-instance tool wakes the agent once, when its last instance is done. */
  @Test
  public void aMultiInstanceToolWakesTheAgentOnceWhenItsLastInstanceEnds() {
    ProcessInstance pi = start(agentic("<userTask id='mi'><multiInstanceLoopCharacteristics>"
        + "<loopCardinality>2</loopCardinality></multiInstanceLoopCharacteristics></userTask>"));
    deleteTurnJobs();
    engine.getRuntimeService().createAdHocSubProcessActivation(scopeExecutionId(pi.getId()))
        .startActivity("mi").execute();
    List<Task> instances = engine.getTaskService().createTaskQuery().list();

    engine.getTaskService().complete(instances.get(0).getId());
    assertThat(turnJobs()).as("one instance still runs").isEmpty();
    engine.getTaskService().complete(instances.get(1).getId());

    assertThat(turnJobs()).hasSize(1);
  }

  @Test
  public void refusesAnOwnCompletionCondition() {
    String model = process("<adHocSubProcess id='adHoc'>" + agentConnector(message())
        + "<userTask id='worker' />"
        + "<completionCondition xsi:type='tFormalExpression'>${false}</completionCondition>"
        + "</adHocSubProcess>");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("must not carry a completionCondition");
  }

  @Test
  public void refusesAScopeWithNothingStartable() {
    String model = process("<adHocSubProcess id='adHoc'>" + agentConnector(message())
        + "<userTask id='a' /><userTask id='b' />"
        + "<sequenceFlow id='ab' sourceRef='a' targetRef='b' />"
        + "<sequenceFlow id='ba' sourceRef='b' targetRef='a' />"
        + "</adHocSubProcess>");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("at least one directly startable child");
  }

  @Test
  public void leavesAPlainAdHocScopeAlone() {
    String model = process("<adHocSubProcess id='adHoc'><userTask id='worker' /></adHocSubProcess>");
    ProcessInstance pi = start(model);

    // No agentic property: no parking, no trigger, no job.
    assertThat(engine.getManagementService().createJobQuery().list()).isEmpty();
    assertThat(triggersOn("adHoc")).isZero();
  }

  // --- the parse-time catalogue ----------------------------------------------

  @Test
  public void theCatalogueIsBuiltAtParseTimeAndSitsOnTheScope() {
    deploy(agentic(
        "<serviceTask id='quick' name='Quick' camunda:expression='${1}'"
        + "    camunda:resultVariable='amount'>"
        + "  <documentation>Computes the amount.</documentation>"
        + "</serviceTask>"
        + "<userTask id='approve' name='Approve'>"
        + "  <extensionElements><camunda:formData>"
        + "    <camunda:formField id='decision' label='Decision' type='string' />"
        + "  </camunda:formData></extensionElements>"
        + "</userTask>"
        + "<userTask id='behind' />"
        + "<sequenceFlow id='inner' sourceRef='approve' targetRef='behind' />"));

    List<AdHocToolDescriptor> catalog = catalogOf("adHoc");

    assertThat(catalog).extracting(AdHocToolDescriptor::getActivityId)
        .as("startable children only, in document order")
        .containsExactly("quick", "approve");
    AdHocToolDescriptor quick = catalog.get(0);
    assertThat(quick.getName()).isEqualTo("Quick");
    assertThat(quick.getDocumentation()).isEqualTo("Computes the amount.");
    assertThat(quick.getResultVariables()).containsExactly("amount");
    AdHocToolDescriptor approve = catalog.get(1);
    assertThat(approve.getResultVariables())
        .as("form fields become the declared result").containsExactly("decision");
  }

  /** A connector's output parameters are what the activity writes, as on the element itself. */
  @Test
  public void aConnectorsOutputParametersAreTheToolsDeclaredResult() {
    deploy(agentic(
        "<serviceTask id='fragen' name='Fragen'>"
        + "  <extensionElements><camunda:connector>"
        + "    <camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "    <camunda:inputOutput>"
        + "      <camunda:inputParameter name='message'>Frag.</camunda:inputParameter>"
        + "      <camunda:outputParameter name='antwort'>${output}</camunda:outputParameter>"
        + "    </camunda:inputOutput>"
        + "  </camunda:connector></extensionElements>"
        + "</serviceTask>"));

    assertThat(catalogOf("adHoc").get(0).getResultVariables()).containsExactly("antwort");
  }

  // --- the property adHocToolParameter.<name> is not read (CIB7-2369)

  /**
   * A tool parameter is declared only with toolParameter(...) in an input mapping: a property
   * adHocToolParameter.<name> is an unknown property like any other, and declares nothing.
   */
  @Test
  public void aToolParameterPropertyDeclaresNothing() {
    deploy(agentic("<serviceTask id='fetch' name='Fetch' camunda:expression='${1}'>"
        + "  <extensionElements><camunda:properties>"
        + "    <camunda:property name='adHocToolParameter.stadt'"
        + "        value='string|Die Stadt, deren Temperatur gesucht ist' />"
        + "  </camunda:properties></extensionElements>"
        + "</serviceTask>"));

    assertThat(catalogOf("adHoc").get(0).getParameters()).isEmpty();
  }

  // --- a tool parameter declared where an input mapping uses it (CIB7-2136)

  /**
   * toolParameter(toolCall.<name>, description, type) in an input mapping declares the parameter,
   * in the activity's mapping as in a connector's on it, nested in a map too.
   */
  @Test
  public void aParameterIsDeclaredWhereAnInputMappingUsesIt() {
    deploy(agentic("<userTask id='freigeben'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='vorschlag'>"
        + "${toolParameter(toolCall.betrag, 'Betrag in Euro', 'integer')}"
        + "</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>"
        + "<serviceTask id='melden'><extensionElements><camunda:connector>"
        + "<camunda:connectorId>http-connector</camunda:connectorId><camunda:inputOutput>"
        + "<camunda:inputParameter name='payload'><camunda:map>"
        + "<camunda:entry key='grund'>${toolParameter(toolCall.grund, \"Warum\", \"number\")}"
        + "</camunda:entry></camunda:map></camunda:inputParameter>"
        + "</camunda:inputOutput></camunda:connector></extensionElements></serviceTask>"));

    AdHocToolDescriptor.Parameter betrag = catalogOf("adHoc").get(0).getParameters().get(0);
    assertThat(betrag.getName()).isEqualTo("betrag");
    assertThat(betrag.getType()).isEqualTo("integer");
    assertThat(betrag.getDescription()).isEqualTo("Betrag in Euro");
    AdHocToolDescriptor.Parameter grund = catalogOf("adHoc").get(1).getParameters().get(0);
    assertThat(grund.getName()).isEqualTo("grund");
    assertThat(grund.getDescription()).isEqualTo("Warum");
    assertThat(grund.getType()).as("a type in double quotes").isEqualTo("number");
  }

  @Test
  public void refusesAToolParameterWhoseDescriptionIsNoLiteral() {
    String model = agentic("<userTask id='freigeben'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='vorschlag'>"
        + "${toolParameter(toolCall.betrag, beschreibung, 'integer')}"
        + "</camunda:inputParameter></camunda:inputOutput></extensionElements></userTask>");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("passes beschreibung as the description, which is no literal"
            + " in quotes");
  }

  @Test
  public void refusesAParameterDeclaredWithTwoTypes() {
    String model = agentic("<userTask id='freigeben'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='vorschlag'>"
        + "${toolParameter(toolCall.betrag, 'Betrag in Euro', 'integer')}</camunda:inputParameter>"
        + "<camunda:inputParameter name='text'>"
        + "${toolParameter(toolCall.betrag, 'Betrag als Text', 'string')}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("'betrag' is declared with two types, 'integer' and 'string'");
  }

  /** A call without a type takes the type another call of the tool gives, in either order. */
  @Test
  public void aCallWithoutATypeTakesTheTypeAnotherCallGives() {
    deploy(agentic("<userTask id='erst'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='a'>${toolParameter(toolCall.betrag)}</camunda:inputParameter>"
        + "<camunda:inputParameter name='b'>"
        + "${toolParameter(toolCall.betrag, 'Betrag in Euro', 'integer')}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>"
        + "<userTask id='dann'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='b'>"
        + "${toolParameter(toolCall.betrag, 'Betrag in Euro', 'integer')}</camunda:inputParameter>"
        + "<camunda:inputParameter name='a'>${toolParameter(toolCall.betrag)}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>"));

    for (AdHocToolDescriptor tool : catalogOf("adHoc")) {
      assertThat(tool.getParameters()).hasSize(1);
      assertThat(tool.getParameters().get(0).getType()).as(tool.getActivityId())
          .isEqualTo("integer");
      assertThat(tool.getParameters().get(0).getDescription()).isEqualTo("Betrag in Euro");
    }
  }

  /** A lone literal is the description: a type word there is refused, with the call that types it. */
  @Test
  public void refusesATypeWordAsTheOnlyLiteral() {
    String model = agentic("<userTask id='zaehlen'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='menge'>${toolParameter(toolCall.menge, 'integer')}"
        + "</camunda:inputParameter></camunda:inputOutput></extensionElements></userTask>");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("'integer' reads like a type")
        .hasMessageContaining("toolParameter(toolCall.menge, '', 'integer')");
  }

  /** toolCall holds the arguments of a call: no parameter may take its name. */
  @Test
  public void refusesTheReservedNameToolCall() {
    String inAMapping = agentic("<userTask id='a'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='x'>${toolParameter(toolCall.toolCall)}"
        + "</camunda:inputParameter></camunda:inputOutput></extensionElements></userTask>");

    assertThatThrownBy(() -> deploy(inAMapping)).isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("'toolCall' cannot be a parameter name");
  }

  /** A name declared in a mapping becomes a field of the tool's schema: 64 characters at most. */
  @Test
  public void refusesAParameterNameInAMappingLongerThan64Characters() {
    String longest = "a" + "b".repeat(63);

    assertThatThrownBy(() -> deploy(withInput("${toolParameter(toolCall." + longest + "c)}")))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("child 'a': the parameter name '" + longest + "c' is longer than 64"
            + " characters");
    deploy(withInput("${toolParameter(toolCall." + longest + ")}"));
    assertThat(catalogOf("adHoc").get(0).getParameters())
        .extracting(AdHocToolDescriptor.Parameter::getName).containsExactly(longest);
  }

  @Test
  public void refusesAnUnknownTypeInAMapping() {
    String model = agentic("<userTask id='freigeben'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='vorschlag'>"
        + "${toolParameter(toolCall.betrag, 'Betrag', 'money')}"
        + "</camunda:inputParameter></camunda:inputOutput></extensionElements></userTask>");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("'money' is not supported. Use one of [string, integer, number,"
            + " boolean].");
  }

  /** The engine's own type words are refused with the type that means the same here. */
  @Test
  public void anEngineTypeWordIsRefusedWithTheTypeToUse() {
    assertThatThrownBy(() -> deploy(withInput("${toolParameter(toolCall.menge, '', 'long')}")))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("the type 'long' is not supported")
        .hasMessageContaining("A whole number is 'integer'; it arrives as a Long.");
    assertThatThrownBy(() -> deploy(withInput("${toolParameter(toolCall.preis, '', 'double')}")))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("A number with a fraction is 'number'; it arrives as a Double.");
  }

  /** The description and the type may be left out; the type is then string. */
  @Test
  public void theDescriptionAndTheTypeMayBeLeftOut() {
    deploy(agentic("<userTask id='notieren'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='a'>${toolParameter(toolCall.stichwort)}</camunda:inputParameter>"
        + "<camunda:inputParameter name='b'>${toolParameter(toolCall.text, 'Der Text der Notiz')}"
        + "</camunda:inputParameter></camunda:inputOutput></extensionElements></userTask>"));

    List<AdHocToolDescriptor.Parameter> parameters = catalogOf("adHoc").get(0).getParameters();
    assertThat(parameters).extracting(AdHocToolDescriptor.Parameter::getName)
        .containsExactly("stichwort", "text");
    assertThat(parameters).extracting(AdHocToolDescriptor.Parameter::getType)
        .containsExactly("string", "string");
    assertThat(parameters.get(0).getDescription()).isEmpty();
    assertThat(parameters.get(1).getDescription()).isEqualTo("Der Text der Notiz");
  }

  /** A tool with output parameters hands out exactly those (CIB7-2239). */
  @Test
  public void aToolWithOutputParametersDeclaresThoseOnly() {
    deploy(agentic("<userTask id='freigeben'><extensionElements><camunda:inputOutput>"
        + "<camunda:outputParameter name='freigegeben'>${entscheidung == 'ja'}</camunda:outputParameter>"
        + "</camunda:inputOutput><camunda:formData>"
        + "<camunda:formField id='entscheidung' type='string' />"
        + "<camunda:formField id='notiz' type='string' />"
        + "</camunda:formData></extensionElements></userTask>"
        + "<serviceTask id='rechnen' camunda:expression='${1}' camunda:resultVariable='zwischen'>"
        + "<extensionElements><camunda:inputOutput>"
        + "<camunda:outputParameter name='summe'>${zwischen * 2}</camunda:outputParameter>"
        + "</camunda:inputOutput></extensionElements></serviceTask>"));

    assertThat(catalogOf("adHoc").get(0).getResultVariables()).containsExactly("freigegeben");
    assertThat(catalogOf("adHoc").get(1).getResultVariables()).containsExactly("summe");
  }

  /**
   * What the tool writes besides its outputs is not shown to the agent: a warning at deployment
   * names it. A tool that lists its results itself, or has no outputs, gets none.
   */
  @Test
  public void aResultTheOutputsDoNotHandOutIsNamedAtDeployment() {
    Logger logger = (Logger) LoggerFactory.getLogger(AgenticToolCatalogParser.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      deploy(agentic("<userTask id='freigeben'><extensionElements><camunda:inputOutput>"
          + "<camunda:outputParameter name='freigegeben'>${entscheidung == 'ja'}"
          + "</camunda:outputParameter></camunda:inputOutput><camunda:formData>"
          + "<camunda:formField id='entscheidung' type='string' />"
          + "<camunda:formField id='notiz' type='string' />"
          + "<camunda:formField id='betrag' type='long'><camunda:validation>"
          + "<camunda:constraint name='readonly' /></camunda:validation></camunda:formField>"
          + "</camunda:formData></extensionElements></userTask>"
          + "<serviceTask id='gelistet' camunda:expression='${1}' camunda:resultVariable='wert'>"
          + "<extensionElements><camunda:properties>"
          + "<camunda:property name='adHocResultVariables' value='summe' />"
          + "</camunda:properties><camunda:inputOutput>"
          + "<camunda:outputParameter name='summe'>${wert}</camunda:outputParameter>"
          + "</camunda:inputOutput></extensionElements></serviceTask>"
          + "<serviceTask id='ohneAusgaben' camunda:expression='${1}'"
          + " camunda:resultVariable='ergebnis' />"));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }

    assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
        .containsExactly("Process 'agenticScope', ad hoc sub process 'adHoc', tool 'freigeben'"
            + " (line 1): its output parameters [freigegeben] are what the agent is told when it"
            + " ends, so [entscheidung, notiz] that the tool also writes are not shown to the agent."
            + " Hand them out through an output parameter, or list what the agent should see in"
            + " adHocResultVariables.");
  }

  /** A read-only form field only shows a value; what a person fills in is the result. */
  @Test
  public void aReadOnlyFormFieldIsNoResult() {
    deploy(agentic("<userTask id='freigeben'><extensionElements><camunda:formData>"
        + "<camunda:formField id='betrag' type='long'><camunda:validation>"
        + "<camunda:constraint name='readonly' /></camunda:validation></camunda:formField>"
        + "<camunda:formField id='freigegeben' type='boolean' />"
        + "</camunda:formData></extensionElements></userTask>"));

    assertThat(catalogOf("adHoc").get(0).getResultVariables()).containsExactly("freigegeben");
  }

  /**
   * A tool someone other than the agent starts has no arguments: its mapping reads them as null
   * from the empty toolCall the scope keeps, instead of failing on a map that is not there.
   */
  @Test
  public void aToolStartedByOthersReadsItsParametersAsNull() {
    ProcessInstance pi = start(agentic("<userTask id='erst' name='Erst' />"
        + "<userTask id='notieren' name='Notieren'>"
        + "<extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='stichwort'>${toolParameter(toolCall.stichwort)}"
        + "</camunda:inputParameter></camunda:inputOutput></extensionElements></userTask>"));
    String scope = scopeExecutionId(pi.getId());

    engine.getRuntimeService().createAdHocSubProcessActivation(scope)
        .startActivity("notieren").execute();

    org.cibseven.bpm.engine.task.Task task = engine.getTaskService().createTaskQuery()
        .taskDefinitionKey("notieren").singleResult();
    assertThat(task.getName()).isEqualTo("Notieren");
    assertThat(engine.getTaskService().getVariables(task.getId())).containsEntry("stichwort", null);
    assertThat(engine.getRuntimeService().getVariableLocal(scope, "toolCall"))
        .isEqualTo(java.util.Collections.emptyMap());
  }

  /** A scope whose tools declare no parameter keeps no toolCall. */
  @Test
  public void aScopeWithoutParametersKeepsNoToolCall() {
    ProcessInstance pi = start(agentic("<userTask id='notieren' />"));

    assertThat(engine.getRuntimeService().getVariableLocal(scopeExecutionId(pi.getId()),
        "toolCall")).isNull();
  }

  /**
   * Outside an agentic scope nothing passes toolCall, so a declaration there would deploy and fail
   * when it runs: refused, naming the process and the element.
   */
  @Test
  public void refusesAToolParameterOutsideEveryAgenticScope() {
    String inAPlainProcess = process("plainProcess",
        "<serviceTask id='plain' camunda:expression='${1}'>"
        + "<extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='betrag'>${toolParameter(toolCall.betrag)}"
        + "</camunda:inputParameter></camunda:inputOutput></extensionElements></serviceTask>");
    String inAPlainAdHocScope = process("plainProcess", "<adHocSubProcess id='adHoc'>"
        + "<userTask id='frage'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='betrag'>${toolParameter(toolCall.betrag, 'Betrag')}"
        + "</camunda:inputParameter></camunda:inputOutput></extensionElements></userTask>"
        + "</adHocSubProcess>");
    String inAnAttribute = process("plainProcess", "<serviceTask id='plain'"
        + " camunda:expression='${toolParameter(toolCall.betrag)}' />");

    assertThatThrownBy(() -> deploy(inAPlainProcess))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Process 'plainProcess', element 'plain': toolParameter(...) in"
            + " inputParameter 'betrag' declares nothing")
        .hasMessageContaining("nothing passes toolCall here");
    assertThatThrownBy(() -> deploy(inAPlainAdHocScope))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Process 'plainProcess', element 'frage': toolParameter(...) in"
            + " inputParameter 'betrag' declares nothing");
    assertThatThrownBy(() -> deploy(inAnAttribute))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Process 'plainProcess': toolParameter(...) in the attribute"
            + " expression of serviceTask 'plain' declares nothing");
  }

  /** Outside every agent, a call in a listener, a condition or a timer declares nothing either. */
  @Test
  public void refusesAToolParameterInAListenerAConditionOrATimerOutsideEveryAgent() {
    String inAListener = process("plainProcess",
        "<serviceTask id='plain' camunda:expression='${1}'><extensionElements>"
        + "<camunda:executionListener event='start' expression=\"${toolParameter(toolCall.x)}\" />"
        + "</extensionElements></serviceTask>");
    String inACondition = process("plainProcess",
        "<serviceTask id='plain' camunda:expression='${1}' />"
        + "<sequenceFlow id='bedingt' sourceRef='plain' targetRef='end'>"
        + "<conditionExpression xsi:type='tFormalExpression'>"
        + "${toolParameter(toolCall.ok) == true}</conditionExpression></sequenceFlow>");
    String inATimer = process("plainProcess",
        "<serviceTask id='plain' camunda:expression='${1}' />"
        + "<boundaryEvent id='frist' attachedToRef='plain'><timerEventDefinition>"
        + "<timeDuration>${toolParameter(toolCall.dauer)}</timeDuration>"
        + "</timerEventDefinition></boundaryEvent>");

    assertThatThrownBy(() -> deploy(inAListener)).isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Process 'plainProcess', element 'plain': toolParameter(...) in the"
            + " attribute expression of executionListener declares nothing");
    assertThatThrownBy(() -> deploy(inACondition)).isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Process 'plainProcess', element 'bedingt': toolParameter(...) in"
            + " conditionExpression declares nothing");
    assertThatThrownBy(() -> deploy(inATimer)).isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Process 'plainProcess', element 'frist': toolParameter(...) in"
            + " timeDuration declares nothing");
  }

  /**
   * An agentic scope that is no tool of another is started by the process, not by an agent: its
   * own input parameters get no toolCall either.
   */
  @Test
  public void refusesAToolParameterInTheOwnInputsOfATopLevelAgent() {
    String model = process("<adHocSubProcess id='adHoc'><extensionElements>"
        + "<camunda:inputOutput><camunda:inputParameter name='auftrag'>"
        + "${toolParameter(toolCall.auftrag)}</camunda:inputParameter></camunda:inputOutput>"
        + connectorElement(message()) + "</extensionElements>"
        + "<userTask id='notieren' /></adHocSubProcess>");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Process 'agenticScope', element 'adHoc': toolParameter(...) in"
            + " inputParameter 'auftrag' declares nothing");
  }

  /**
   * An attribute of the process itself, as its job priority, is evaluated by the engine: a call
   * of the function there declares nothing either, and is refused.
   */
  @Test
  public void refusesAToolParameterInAnAttributeOfTheProcess() {
    String model = "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + " xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + " targetNamespace='http://cibseven.org/agentic'>"
        + "<process id='plainProcess' isExecutable='true'"
        + " camunda:jobPriority=\"${toolParameter(toolCall.prioritaet, '', 'integer')}\">"
        + "<startEvent id='start' /><sequenceFlow id='f1' sourceRef='start' targetRef='plain' />"
        + "<userTask id='plain' camunda:asyncBefore='true' />"
        + "<sequenceFlow id='f2' sourceRef='plain' targetRef='end' /><endEvent id='end' />"
        + "</process></definitions>";

    assertThatThrownBy(() -> deploy(model)).isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Process 'plainProcess': toolParameter(...) in the attribute"
            + " jobPriority of process 'plainProcess' declares nothing");
  }

  /** Documentation and string literals are no code: the function named there calls nothing. */
  @Test
  public void aToolParameterInTextOrALiteralOutsideAnAgentDeploys() {
    deploy(process("plainProcess", "<serviceTask id='plain' camunda:expression='${1}'>"
        + "<documentation>Declare with toolParameter(toolCall.betrag) in a tool.</documentation>"
        + "<extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='hinweis'>${'toolParameter(toolCall.betrag)'}"
        + "</camunda:inputParameter></camunda:inputOutput></extensionElements></serviceTask>"));
  }

  /**
   * A delegate field's fixed value, {@code stringValue} or {@code camunda:string}, is handed on as
   * it is written: no call or read there means anything, in a plain process or in a tool. A field
   * given as {@code camunda:expression} is one, and is checked.
   */
  @Test
  public void aDelegateFieldsFixedValueIsNoExpression() {
    deploy(agentic("<serviceTask id='schreiben' camunda:class='java.lang.Object'>"
        + "<extensionElements><camunda:field name='anrede' stringValue='${toolCall.anrede}' />"
        + "<camunda:field name='vorlage'><camunda:string>Guten Tag ${toolCall.name}"
        + "</camunda:string></camunda:field></extensionElements></serviceTask>"));
    assertThat(catalogOf("adHoc").get(0).getParameters()).isEmpty();
    deploy(process("plainProcess", "<serviceTask id='plain' camunda:class='java.lang.Object'>"
        + "<extensionElements>"
        + "<camunda:field name='a' stringValue='${toolParameter(toolCall.betrag)}' />"
        + "<camunda:field name='b'><camunda:string>${toolParameter(toolCall.betrag)}"
        + "</camunda:string></camunda:field></extensionElements></serviceTask>"));

    assertThatThrownBy(() -> deploy(agentic("<serviceTask id='schreiben'"
        + " camunda:class='java.lang.Object'><extensionElements>"
        + "<camunda:field name='vorlage'><camunda:expression>${toolCall.name}"
        + "</camunda:expression></camunda:field></extensionElements></serviceTask>")))
        .hasMessageContaining("toolCall.name is read");
  }

  /**
   * A method named toolParameter is someone else's, and so is the word in a script no agent
   * starts: no script engine resolves it to the function. Neither refuses a plain process.
   */
  @Test
  public void aMethodOrAScriptFunctionNamedToolParameterOutsideAnAgentDeploys() {
    deploy(process("plainProcess", "<serviceTask id='plain'"
        + " camunda:expression=\"${settings.toolParameter('timeout')}\">"
        + "<extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='a'>${settings . toolParameter(\"retries\")}"
        + "</camunda:inputParameter>"
        + "<camunda:inputParameter name='b'><camunda:script scriptFormat='groovy'>"
        + "def toolParameter(x) { x }; toolParameter(1)</camunda:script></camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></serviceTask>"));
  }

  /**
   * The colon of a condition is no prefix: a call in either branch of {@code ?:} declares its
   * parameter (CIB7-2136).
   */
  @Test
  public void aCallInEitherBranchOfAConditionDeclares() {
    deploy(agentic("<userTask id='rechnen'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='a'>"
        + "${flag ? '' : toolParameter(toolCall.betrag, 'Betrag', 'integer')}"
        + "</camunda:inputParameter>"
        + "<camunda:inputParameter name='b'>"
        + "${flag ? toolParameter(toolCall.grund, 'Grund') : ''}</camunda:inputParameter>"
        + "<camunda:inputParameter name='c'>${flag?'':toolParameter(toolCall.menge)}"
        + "</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>"));

    assertThat(catalogOf("adHoc").get(0).getParameters())
        .extracting(AdHocToolDescriptor.Parameter::getName)
        .containsExactly("betrag", "grund", "menge");
  }

  /**
   * Before the colon of a condition, a number or a word of EL is no prefix either, written without
   * spaces too: only a name is, as in {@code fn:toolParameter(...)}.
   */
  @Test
  public void aNumberOrAKeywordBeforeTheColonOfAConditionIsNoPrefix() {
    deploy(agentic("<userTask id='rechnen'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='a'>"
        + "${flag ? 0:toolParameter(toolCall.betrag, 'Betrag', 'integer')}</camunda:inputParameter>"
        + "<camunda:inputParameter name='b'>"
        + "${flag ? true:toolParameter(toolCall.dringend, '', 'boolean')}</camunda:inputParameter>"
        + "<camunda:inputParameter name='c'>${flag ? null:toolParameter(toolCall.notiz)}"
        + "</camunda:inputParameter>"
        + "<camunda:inputParameter name='d'>${toolCall.betrag}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>"));

    assertThat(catalogOf("adHoc").get(0).getParameters())
        .extracting(AdHocToolDescriptor.Parameter::getName, AdHocToolDescriptor.Parameter::getType)
        .containsExactly(tuple("betrag", "integer"), tuple("dringend", "boolean"),
            tuple("notiz", "string"));
  }

  /**
   * A property before the colon of a condition is no prefix, with spaces around its dot too: EL
   * reads the name after a dot as the property.
   */
  @Test
  public void aPropertyWithSpacesAroundItsDotBeforeTheColonIsNoPrefix() {
    deploy(agentic("<userTask id='rechnen'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='a'>"
        + "${flag ? auftrag . vorgabe : toolParameter(toolCall.betrag)}</camunda:inputParameter>"
        + "<camunda:inputParameter name='b'>${toolCall.betrag}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>"));

    assertThat(catalogOf("adHoc").get(0).getParameters())
        .extracting(AdHocToolDescriptor.Parameter::getName).containsExactly("betrag");
  }

  /** The key of a map's entry is an expression the engine evaluates: a call there declares. */
  @Test
  public void aCallInTheKeyOfAMapEntryDeclares() {
    deploy(agentic("<userTask id='melden'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='payload'><camunda:map>"
        + "<camunda:entry key=\"${toolParameter(toolCall.feld, 'Name des Felds')}\">"
        + "wert</camunda:entry>"
        + "</camunda:map></camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>"));

    AdHocToolDescriptor.Parameter feld = catalogOf("adHoc").get(0).getParameters().get(0);
    assertThat(feld.getName()).isEqualTo("feld");
    assertThat(feld.getDescription()).isEqualTo("Name des Felds");
  }

  /**
   * A condition written in a script language is script code, not an expression: outside every
   * agent it is left alone, and along a tool's flow its text is not read as reads of toolCall.
   */
  @Test
  public void aConditionInAScriptLanguageIsNoExpression() {
    deploy(process("plainProcess", "<serviceTask id='plain' camunda:expression='${1}' />"
        + "<sequenceFlow id='bedingt' sourceRef='plain' targetRef='end'>"
        + "<conditionExpression xsi:type='tFormalExpression' language='groovy'>"
        + "'${toolParameter(toolCall.x)}' != null</conditionExpression></sequenceFlow>"));
    deploy(agentic("<serviceTask id='anfragen' camunda:expression='${1}' />"
        + "<sequenceFlow id='weiter' sourceRef='anfragen' targetRef='pruefen'>"
        + "<conditionExpression xsi:type='tFormalExpression' language='groovy'>"
        + "'${toolCall.xx}' != null</conditionExpression></sequenceFlow>"
        + "<userTask id='pruefen' />"));
  }

  /**
   * A script in JUEL is evaluated as an expression, so a read of toolCall there is checked: in a
   * condition with language juel, and in a script task, whose language is JUEL when it names none.
   */
  @Test
  public void aReadInAJuelScriptIsChecked() {
    String tool = "<userTask id='anfragen'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='betrag'>${toolParameter(toolCall.betrag, '', 'integer')}"
        + "</camunda:inputParameter></camunda:inputOutput></extensionElements></userTask>";
    deploy(agentic(tool + "<sequenceFlow id='weiter' sourceRef='anfragen' targetRef='pruefen'>"
        + "<conditionExpression xsi:type='tFormalExpression' language='juel'>"
        + "${toolCall.betrag > 1}</conditionExpression></sequenceFlow><userTask id='pruefen' />"));

    assertThatThrownBy(() -> deploy(agentic(tool
        + "<sequenceFlow id='weiter' sourceRef='anfragen' targetRef='pruefen'>"
        + "<conditionExpression xsi:type='tFormalExpression' language='juel'>"
        + "${toolCall.betrga > 1}</conditionExpression></sequenceFlow><userTask id='pruefen' />")))
        .as("in a condition in JUEL").hasMessageContaining("toolCall.betrga is read");
    assertThatThrownBy(() -> deploy(agentic(tool
        + "<sequenceFlow id='weiter' sourceRef='anfragen' targetRef='pruefen' />"
        + "<scriptTask id='pruefen'><script>${toolCall.betrga}</script></scriptTask>")))
        .as("in a script task that names no language")
        .hasMessageContaining("toolCall.betrga is read");
  }

  /**
   * A dynamic script, whose source starts with ${, is an expression that computes the script's
   * code: toolCall there is refused, the model's argument would run as code, and outside every
   * agent a call there is refused as in any expression.
   */
  @Test
  public void refusesToolCallInADynamicScriptSource() {
    assertThatThrownBy(() -> deploy(agentic("<userTask id='rechnen'><extensionElements>"
        + "<camunda:inputOutput><camunda:inputParameter name='betrag'>"
        + "${toolParameter(toolCall.betrag)}</camunda:inputParameter>"
        + "<camunda:inputParameter name='code'><camunda:script scriptFormat='groovy'>"
        + "${toolCall.betrag}</camunda:script></camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>")))
        .hasMessageContaining("Ad hoc sub process 'adHoc', child 'rechnen': script is a dynamic"
            + " script")
        .hasMessageContaining("so the model's argument would run as script code");
    assertThatThrownBy(() -> deploy(process("plainProcess",
        "<serviceTask id='plain' camunda:expression='${1}' />"
        + "<sequenceFlow id='bedingt' sourceRef='plain' targetRef='end'>"
        + "<conditionExpression xsi:type='tFormalExpression' language='groovy'>"
        + "${toolParameter(toolCall.x)}</conditionExpression></sequenceFlow>")))
        .as("outside every agent").hasMessageContaining("declares nothing");
  }

  /**
   * A script's attributes are expressions where the engine evaluates them: a dynamic resource
   * that misspells a read is refused, and so is a call there.
   */
  @Test
  public void aReadOrACallInADynamicScriptResourceIsChecked() {
    deploy(agentic("<userTask id='rechnen'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='pfad'>${toolParameter(toolCall.pfad, 'Script path')}"
        + "</camunda:inputParameter>"
        + "<camunda:inputParameter name='ergebnis'><camunda:script scriptFormat='groovy'"
        + " resource='${toolCall.pfad}' /></camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>"));

    assertThatThrownBy(() -> deploy(agentic("<userTask id='rechnen'><extensionElements>"
        + "<camunda:inputOutput><camunda:inputParameter name='ergebnis'>"
        + "<camunda:script scriptFormat='groovy' resource='${toolCall.pfad}' />"
        + "</camunda:inputParameter></camunda:inputOutput></extensionElements></userTask>")))
        .hasMessageContaining("toolCall.pfad is read");
    assertThatThrownBy(() -> deploy(agentic("<userTask id='rechnen'><extensionElements>"
        + "<camunda:inputOutput><camunda:inputParameter name='ergebnis'>"
        + "<camunda:script scriptFormat='groovy' resource='${toolParameter(toolCall.pfad)}' />"
        + "</camunda:inputParameter></camunda:inputOutput></extensionElements></userTask>")))
        .hasMessageContaining("toolParameter(...) in the attribute resource of script declares"
            + " nothing");
  }

  /** In a tool, a method named toolParameter is no call of the function either. */
  @Test
  public void aMethodNamedToolParameterInAToolIsNoDeclaration() {
    deploy(agentic("<userTask id='notieren'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='frist'>${settings.toolParameter('timeout')}"
        + "</camunda:inputParameter>"
        + "<camunda:inputParameter name='versuche'><camunda:script scriptFormat='groovy'>"
        + "settings.toolParameter('retries')</camunda:script></camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>"));

    assertThat(catalogOf("adHoc").get(0).getParameters()).isEmpty();
  }

  /**
   * A refusal of a call or a read is reported on an element with an id, the activity that holds
   * an input parameter or a script, so that the parse error names an element a tool can find.
   */
  @Test
  public void aRefusalOfAToolParameterNamesAnElementWithAnId() {
    assertThat(mainElementOf(process("plainProcess",
        "<serviceTask id='plain' camunda:expression='${1}'><extensionElements>"
        + "<camunda:inputOutput><camunda:inputParameter name='betrag'>"
        + "${toolParameter(toolCall.betrag)}</camunda:inputParameter></camunda:inputOutput>"
        + "</extensionElements></serviceTask>")))
        .as("outside every agent").isEqualTo("plain");
    assertThat(mainElementOf(agentic("<userTask id='freigeben'><extensionElements>"
        + "<camunda:inputOutput><camunda:outputParameter name='x'>${toolParameter(toolCall.x)}"
        + "</camunda:outputParameter></camunda:inputOutput></extensionElements></userTask>")))
        .as("in an output of a tool").isEqualTo("freigeben");
    assertThat(mainElementOf(agentic("<userTask id='rechnen'><extensionElements>"
        + "<camunda:inputOutput><camunda:inputParameter name='doppelt'>"
        + "<camunda:script scriptFormat='groovy'>toolParameter(toolCall.zahl) * 2</camunda:script>"
        + "</camunda:inputParameter></camunda:inputOutput></extensionElements></userTask>")))
        .as("in a script").isEqualTo("rechnen");
    assertThat(mainElementOf(agentic("<adHocSubProcess id='helfer'><extensionElements>"
        + connectorElement("<camunda:inputParameter name='message'>Auftrag: ${toolCall.auftrag}"
            + "</camunda:inputParameter>")
        + "</extensionElements><userTask id='innen' /></adHocSubProcess>")))
        .as("in a nested agent's connector").isEqualTo("helfer");
  }

  /** The id of the element the first parse error of a refused model is reported on. */
  protected String mainElementOf(String model) {
    try {
      deploy(model);
    } catch (ParseException e) {
      return e.getResorceReports().get(0).getErrors().get(0).getMainElementId();
    }
    throw new AssertionError("the model deployed");
  }

  /** Script code is no expression: a call there would be offered to the model, then fail. */
  @Test
  public void refusesAToolParameterInAScript() {
    String model = agentic("<userTask id='rechnen'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='doppelt'><camunda:script scriptFormat='groovy'>"
        + "toolParameter(toolCall.zahl) * 2</camunda:script></camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Ad hoc sub process 'adHoc', child 'rechnen': toolParameter(...)"
            + " in a script declares nothing, and no script engine knows the function, not even"
            + " JUEL's")
        .hasMessageContaining("e.g. amount = ${toolParameter(toolCall.amount)}, and read the"
            + " variable amount in the activity's script or an output parameter")
        .hasMessageContaining("is not checked at deployment, and misspelt it reads null");
  }

  /**
   * A call anywhere the catalogue does not read declares nothing, so the model would never send
   * the value: refused, with the place to declare it.
   */
  @Test
  public void refusesAToolParameterWhereTheCatalogueDoesNotReadIt() {
    String inAnExpression = agentic("<serviceTask id='lager'"
        + " camunda:expression=\"${toolParameter(toolCall.sku) == 'SKU-1'}\" />");
    String inACallActivitysIn = agentic("<callActivity id='buchen' calledElement='buchung'>"
        + "<extensionElements><camunda:in sourceExpression=\"${toolParameter(toolCall.betrag)}\""
        + " target='betrag' /></extensionElements></callActivity>");
    String inAnOutput = agentic("<userTask id='freigeben'><extensionElements><camunda:inputOutput>"
        + "<camunda:outputParameter name='x'>${toolParameter(toolCall.x)}</camunda:outputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>");
    String insideASubProcessTool = agentic("<subProcess id='fragen'>"
        + "<startEvent id='fs' /><sequenceFlow id='fs1' sourceRef='fs' targetRef='person' />"
        + "<userTask id='person'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='frage'>${toolParameter(toolCall.frage)}"
        + "</camunda:inputParameter></camunda:inputOutput></extensionElements></userTask>"
        + "<sequenceFlow id='fs2' sourceRef='person' targetRef='fe' /><endEvent id='fe' />"
        + "</subProcess>");
    String furtherAlongAChain = agentic("<serviceTask id='anfragen' camunda:expression='${1}' />"
        + "<sequenceFlow id='weiter' sourceRef='anfragen' targetRef='warten' />"
        + "<userTask id='warten'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='x'>${toolParameter(toolCall.x)}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>");

    assertThatThrownBy(() -> deploy(inAnExpression)).isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("the attribute expression of serviceTask 'lager' declares nothing")
        .hasMessageContaining("Declare it in an input parameter of 'lager'");
    assertThatThrownBy(() -> deploy(inACallActivitysIn)).isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("the attribute sourceExpression of in declares nothing");
    assertThatThrownBy(() -> deploy(inAnOutput)).isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("child 'freigeben': toolParameter(...) in outputParameter 'x'"
            + " declares nothing");
    assertThatThrownBy(() -> deploy(insideASubProcessTool))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("child 'fragen': toolParameter(...) in inputParameter 'frage'"
            + " declares nothing")
        .hasMessageContaining("Declare it in an input parameter of 'fragen'");
    assertThatThrownBy(() -> deploy(furtherAlongAChain)).isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Ad hoc sub process 'adHoc', child 'warten': toolParameter(...) in"
            + " inputParameter 'x' declares nothing")
        .hasMessageContaining("Declare it in an input parameter of an activity the agent starts");
  }

  /** Only expression code counts: plain text and documentation may mention the function. */
  @Test
  public void aCallOutsideExpressionCodeIsNoDeclaration() {
    deploy(agentic("<userTask id='notieren'>"
        + "<documentation>Liest ${toolParameter(toolCall.x)} nicht.</documentation>"
        + "<extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='hinweis'>Schreib toolParameter(toolCall.y) hin."
        + "</camunda:inputParameter>"
        + "<camunda:inputParameter name='text'>${'toolParameter(toolCall.z)'}"
        + "</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>"));

    assertThat(catalogOf("adHoc").get(0).getParameters()).isEmpty();
  }

  /** A misspelt read would give null: a read must name a parameter the tool declares there. */
  @Test
  public void refusesAReadOfToolCallThatNamesNoDeclaredParameter() {
    String misspelt = agentic("<userTask id='freigeben'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='a'>"
        + "${toolParameter(toolCall.betrag, 'Betrag', 'integer')}</camunda:inputParameter>"
        + "<camunda:inputParameter name='b'>${toolCall.betraq * 2}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>");

    assertThatThrownBy(() -> deploy(misspelt)).isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("toolCall.betraq is read, but no toolParameter(toolCall.betraq,"
            + " ...) of this tool declares it");
  }

  /**
   * The same check holds outside the input mappings: in an output parameter, inside a sub process
   * tool, in a call activity's mappings. A read of a declared name there passes.
   */
  @Test
  public void refusesAMisspeltReadOfToolCallAnywhereInTheTool() {
    String inside = agentic("<subProcess id='fragen'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='frage'>${toolParameter(toolCall.frage, 'Die Frage')}"
        + "</camunda:inputParameter></camunda:inputOutput></extensionElements>"
        + "<startEvent id='fs' /><sequenceFlow id='fs1' sourceRef='fs' targetRef='person' />"
        + "<userTask id='person'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='anzeige'>${toolCall.fraeg}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>"
        + "<sequenceFlow id='fs2' sourceRef='person' targetRef='fe' /><endEvent id='fe' />"
        + "</subProcess>");
    String inAnOutput = agentic("<userTask id='freigeben'><extensionElements>"
        + "<camunda:inputOutput><camunda:inputParameter name='a'>"
        + "${toolParameter(toolCall.betrag, 'Betrag', 'integer')}</camunda:inputParameter>"
        + "<camunda:outputParameter name='b'>${toolCall.betarg}</camunda:outputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>");
    String inACallActivity = agentic("<callActivity id='weitergeben' calledElement='anderer'>"
        + "<extensionElements><camunda:inputOutput><camunda:inputParameter name='auftrag'>"
        + "${toolParameter(toolCall.auftrag)}</camunda:inputParameter></camunda:inputOutput>"
        + "<camunda:in sourceExpression='${toolCall.auftarg}' target='auftrag' />"
        + "</extensionElements></callActivity>");
    String declared = agentic("<userTask id='freigeben'><extensionElements>"
        + "<camunda:inputOutput><camunda:inputParameter name='a'>"
        + "${toolParameter(toolCall.betrag, 'Betrag', 'integer')}</camunda:inputParameter>"
        + "<camunda:outputParameter name='b'>${toolCall.betrag}</camunda:outputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>");

    assertThatThrownBy(() -> deploy(inside)).isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("toolCall.fraeg is read, but no toolParameter(toolCall.fraeg,");
    assertThatThrownBy(() -> deploy(inAnOutput)).isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("toolCall.betarg is read, but no toolParameter(toolCall.betarg,");
    assertThatThrownBy(() -> deploy(inACallActivity)).isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("toolCall.auftarg is read, but no toolParameter(toolCall.auftarg,");
    deploy(declared);
  }

  /**
   * An activity a tool's flow reaches runs on the tool's performance and sees the call's
   * toolCall: a read there names a parameter a tool whose flow reaches it declares. So does a
   * read in the flow's condition and on the path of a boundary event (CIB7-2136).
   */
  @Test
  public void refusesAMisspeltReadOfToolCallInAFollower() {
    String tool = "<userTask id='anfragen'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='a'>"
        + "${toolParameter(toolCall.betrag, 'Betrag', 'integer')}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>";
    String follower = "<sequenceFlow id='weiter' sourceRef='anfragen' targetRef='pruefen' />"
        + "<userTask id='pruefen'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='b'>%s</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>";
    String inACondition = tool
        + "<sequenceFlow id='weiter' sourceRef='anfragen' targetRef='pruefen'>"
        + "<conditionExpression xsi:type='tFormalExpression'>${toolCall.betrga > 0}"
        + "</conditionExpression></sequenceFlow><userTask id='pruefen' />";
    String onABoundaryPath = tool
        + "<boundaryEvent id='frist' attachedToRef='anfragen'><timerEventDefinition>"
        + "<timeDuration>PT1H</timeDuration></timerEventDefinition></boundaryEvent>"
        + "<sequenceFlow id='danach' sourceRef='frist' targetRef='mahnen' />"
        + "<userTask id='mahnen'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='c'>${toolCall.btrag}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>";

    deploy(agentic(tool + String.format(follower, "${toolCall.betrag * 2}")));
    assertThatThrownBy(() -> deploy(agentic(tool + String.format(follower, "${toolCall.betraq}"))))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Ad hoc sub process 'adHoc', child 'pruefen': toolCall.betraq is"
            + " read, but no toolParameter(toolCall.betraq, ...) of 'anfragen', whose flow"
            + " reaches it, declares it.");
    assertThatThrownBy(() -> deploy(agentic(inACondition)))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("child 'weiter': toolCall.betrga is read");
    assertThatThrownBy(() -> deploy(agentic(onABoundaryPath)))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("child 'mahnen': toolCall.btrag is read");
  }

  /** An activity the flows of two tools reach may read what either of them declares. */
  @Test
  public void aFollowerOfTwoToolsReadsWhatEitherDeclares() {
    String model = "<userTask id='erst'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='a'>${toolParameter(toolCall.menge)}"
        + "</camunda:inputParameter></camunda:inputOutput></extensionElements></userTask>"
        + "<userTask id='zweit'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='a'>${toolParameter(toolCall.preis)}"
        + "</camunda:inputParameter></camunda:inputOutput></extensionElements></userTask>"
        + "<sequenceFlow id='e1' sourceRef='erst' targetRef='zusammen' />"
        + "<sequenceFlow id='z1' sourceRef='zweit' targetRef='zusammen' />"
        + "<userTask id='zusammen'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='b'>%s</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>";

    deploy(agentic(String.format(model, "${toolCall.menge} x ${toolCall.preis}")));
    assertThatThrownBy(() -> deploy(agentic(String.format(model, "${toolCall.rabatt}"))))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("no toolParameter(toolCall.rabatt, ...) of 'erst' or 'zweit', whose"
            + " flows reach it, declares it.");
  }

  /**
   * A tool's flow goes on across a pair of link events, as the engine links them: the activity
   * after the catching one reads what the tool declares, and a misspelt read there is refused.
   */
  @Test
  public void aReadAcrossAPairOfLinkEventsIsTheToolsToJudge() {
    String model = "<userTask id='anfragen'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='a'>${toolParameter(toolCall.betrag)}"
        + "</camunda:inputParameter></camunda:inputOutput></extensionElements></userTask>"
        + "<sequenceFlow id='e1' sourceRef='anfragen' targetRef='weg' />"
        + "<intermediateThrowEvent id='weg'><linkEventDefinition name='weiter' />"
        + "</intermediateThrowEvent>"
        + "<intermediateCatchEvent id='hier'><linkEventDefinition name='weiter' />"
        + "</intermediateCatchEvent>"
        + "<sequenceFlow id='e2' sourceRef='hier' targetRef='pruefen' />"
        + "<userTask id='pruefen'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='b'>%s</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>";

    deploy(agentic(String.format(model, "${toolCall.betrag}")));
    assertThatThrownBy(() -> deploy(agentic(String.format(model, "${toolCall.betrga}"))))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("no toolParameter(toolCall.betrga, ...) of 'anfragen', whose flow"
            + " reaches it, declares it.");
  }

  /** Where no tool's flow reaches, no call's arguments arrive: a read of one would read null. */
  @Test
  public void refusesAReadOfToolCallWhereNoToolsFlowReaches() {
    String model = agentic("<userTask id='notieren' />"
        + "<userTask id='hin'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='a'>${toolCall.wert}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>"
        + "<sequenceFlow id='h1' sourceRef='hin' targetRef='her' />"
        + "<userTask id='her' />"
        + "<sequenceFlow id='h2' sourceRef='her' targetRef='hin' />");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("child 'hin': toolCall.wert is read, but no tool's flow reaches"
            + " 'hin': no call's arguments arrive there, and it would read null.");
  }

  /**
   * A method of the map reads no argument of its name, and a toolCall that is a property of
   * something else is not the map: neither is a misspelt read (CIB7-2136).
   */
  @Test
  public void aMethodOfToolCallOrAToolCallOfSomethingElseIsNoRead() {
    deploy(agentic("<userTask id='freigeben'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='a'>"
        + "${toolParameter(toolCall.betrag, 'Betrag', 'integer')}</camunda:inputParameter>"
        + "<camunda:inputParameter name='b'>${toolCall.containsKey('betrag')}"
        + "</camunda:inputParameter>"
        + "<camunda:inputParameter name='c'>${toolCall.size() > 0 and not toolCall.isEmpty()}"
        + "</camunda:inputParameter>"
        + "<camunda:inputParameter name='d'>${vorgang.toolCall.id}</camunda:inputParameter>"
        + "<camunda:inputParameter name='e'>${vorgang . toolCall['id']}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>"
        + "<adHocSubProcess id='helfer'><extensionElements>"
        + connectorElement("<camunda:inputParameter name='message'>Vorgang: ${vorgang.toolCall.id}"
            + "</camunda:inputParameter>")
        + "</extensionElements><userTask id='innen' /></adHocSubProcess>"));

    assertThat(catalogOf("adHoc").get(0).getParameters())
        .extracting(AdHocToolDescriptor.Parameter::getName).containsExactly("betrag");
  }

  /** A read names the whole identifier, as EL reads it: no declared name is a prefix of it. */
  @Test
  public void refusesAReadWhoseNameOnlyStartsWithADeclaredOne() {
    String model = withInputs("${toolParameter(toolCall.betrag, 'Betrag', 'integer')}",
        "${toolCall.betrag\u00e9}");

    assertThatThrownBy(() -> deploy(model)).isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("toolCall.betrag\u00e9 is read");
  }

  /**
   * A read in brackets with a literal key is checked as one after a dot; a key computed in the
   * brackets cannot be told at deployment and is left alone (CIB7-2136).
   */
  @Test
  public void refusesAMisspeltReadOfToolCallInBrackets() {
    String inSingleQuotes = withInputs(
        "${toolParameter(toolCall.betrag, 'Betrag', 'integer')}", "${toolCall['betraq'] * 2}");
    String inDoubleQuotes = withInputs(
        "${toolParameter(toolCall.betrag, 'Betrag', 'integer')}", "${toolCall [ \"betarg\" ]}");

    assertThatThrownBy(() -> deploy(inSingleQuotes)).isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("toolCall['betraq'] is read, but no toolParameter(toolCall.betraq,");
    assertThatThrownBy(() -> deploy(inDoubleQuotes)).isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("toolCall['betarg'] is read, but no toolParameter(toolCall.betarg,");
    deploy(withInputs("${toolParameter(toolCall.betrag, 'Betrag', 'integer')}",
        "${toolCall[\"betrag\"] + toolCall[schluessel]}"));
  }

  protected static String withInputs(String first, String second) {
    return agentic("<userTask id='a'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='x'>" + first + "</camunda:inputParameter>"
        + "<camunda:inputParameter name='y'>" + second + "</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>");
  }

  /** A call the catalogue cannot read is refused with its cause. */
  @Test
  public void aRefusalNamesWhatIsWrongWithTheCall() {
    assertThatThrownBy(() -> deploy(withInput("${toolParameter(betrag)}")))
        .hasMessageContaining("does not start with toolCall.<name>");
    assertThatThrownBy(() -> deploy(withInput("${toolParameter(toolcall.betrag)}")))
        .hasMessageContaining("writes toolcall for toolCall");
    assertThatThrownBy(() -> deploy(withInput("${toolParameter(toolCall.begr\u00fcndung)}")))
        .hasMessageContaining("no umlauts");
    assertThatThrownBy(() -> deploy(
        withInput("${toolParameter(toolCall.x, 'a', 'string', 'b')}")))
        .hasMessageContaining("more than three arguments");
    assertThatThrownBy(() -> deploy(withInput("${toolParameter(toolCall.betrag * 2)}")))
        .hasMessageContaining("passes an expression as the value");
    assertThatThrownBy(() -> deploy(withInput("${toolParameter(toolCall.mein-name)}")))
        .hasMessageContaining("no '-'");
    assertThatThrownBy(() -> deploy(withInput("${toolParameter(toolCall['menge'], 'Menge')}")))
        .hasMessageContaining("reads the argument in brackets; write it as toolCall.menge.");
    assertThatThrownBy(() -> deploy(withInput("${toolParameter(toolCall.x, 'a' + 'b')}")))
        .hasMessageContaining("is not in the form read at deployment.");
  }

  protected static String withInput(String expression) {
    return agentic("<userTask id='a'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='x'>" + expression + "</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>");
  }

  /**
   * A tool that is itself an agent reads its connector's inputs on every turn of its own scope,
   * not when the outer call starts it, so a call there declares nothing the model is offered:
   * refused, pointing to the agent's own inputs.
   */
  @Test
  public void refusesAToolParameterInANestedAgentsConnector() {
    String model = agentic("<adHocSubProcess id='helfer'><extensionElements>"
        + connectorElement("<camunda:inputParameter name='message'>"
            + "${toolParameter(toolCall.auftrag, 'Der Auftrag')}</camunda:inputParameter>")
        + "</extensionElements><userTask id='innen' /></adHocSubProcess>");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("'helfer' is an agent itself")
        .hasMessageContaining("Its connector's inputs are read on every turn of 'helfer', not when"
            + " it is started, and declare no parameter")
        .hasMessageContaining("Declare the parameter in the input parameters of 'helfer' itself,"
            + " e.g. request = ${toolParameter(toolCall.request, 'What to do')}");

    String aRead = agentic("<adHocSubProcess id='helfer'><extensionElements>"
        + connectorElement("<camunda:inputParameter name='message'>Auftrag: ${toolCall.auftrag}"
            + "</camunda:inputParameter>")
        + "</extensionElements><userTask id='innen' /></adHocSubProcess>");
    assertThatThrownBy(() -> deploy(aRead))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("'helfer' is an agent itself");
  }

  /**
   * The rule for an agent's connector holds for an agent anywhere in or along a tool, not only
   * for a tool that is an agent itself: a read of toolCall there is refused, naming that agent.
   */
  @Test
  public void refusesToolCallInTheConnectorOfAnAgentDeeperInOrAlongATool() {
    String inside = agentic("<subProcess id='bereich'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='r'>${toolParameter(toolCall.request, 'Request')}"
        + "</camunda:inputParameter></camunda:inputOutput></extensionElements>"
        + "<startEvent id='bs' /><sequenceFlow id='b1' sourceRef='bs' targetRef='helfer' />"
        + "<adHocSubProcess id='helfer'><extensionElements>"
        + connectorElement("<camunda:inputParameter name='message'>Work on ${toolCall.requset}"
            + "</camunda:inputParameter>")
        + "</extensionElements><userTask id='innen' /></adHocSubProcess>"
        + "<sequenceFlow id='b2' sourceRef='helfer' targetRef='be' /><endEvent id='be' />"
        + "</subProcess>");
    String along = agentic("<serviceTask id='anfragen' camunda:expression='${1}'>"
        + "<extensionElements><camunda:inputOutput><camunda:inputParameter name='r'>"
        + "${toolParameter(toolCall.request, 'Request')}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></serviceTask>"
        + "<sequenceFlow id='weiter' sourceRef='anfragen' targetRef='helfer' />"
        + "<adHocSubProcess id='helfer'><extensionElements>"
        + connectorElement("<camunda:inputParameter name='message'>Work on ${toolCall.request}"
            + "</camunda:inputParameter>")
        + "</extensionElements><userTask id='innen' /></adHocSubProcess>");

    assertThatThrownBy(() -> deploy(inside)).isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("child 'bereich': 'helfer' is an agent itself");
    assertThatThrownBy(() -> deploy(along)).isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("child 'helfer': 'helfer' is an agent itself");
  }

  /** The nested agent's own input parameters are where its parameter is declared. */
  @Test
  public void aNestedAgentDeclaresItsParameterInItsOwnInputs() {
    deploy(agentic("<adHocSubProcess id='helfer'><extensionElements>"
        + "<camunda:inputOutput><camunda:inputParameter name='auftrag'>"
        + "${toolParameter(toolCall.auftrag, 'Der Auftrag')}</camunda:inputParameter>"
        + "</camunda:inputOutput>"
        + connectorElement("<camunda:inputParameter name='message'>Auftrag: ${auftrag}"
            + "</camunda:inputParameter><camunda:inputParameter name='instruction'>Der toolCall"
            + " des Aufrufers steht nicht im Text.</camunda:inputParameter>")
        + "</extensionElements><userTask id='innen' /></adHocSubProcess>"));

    AdHocToolDescriptor.Parameter auftrag = catalogOf("adHoc").get(0).getParameters().get(0);
    assertThat(auftrag.getName()).isEqualTo("auftrag");
    assertThat(auftrag.getDescription()).isEqualTo("Der Auftrag");
  }

  /** A deferred expression counts too; an escaped one is plain text (CIB7-2136). */
  @Test
  public void aDeferredCallCountsAndAnEscapedOneDoesNot() {
    deploy(agentic("<userTask id='notieren'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='a'>#{toolParameter(toolCall.stichwort)}"
        + "</camunda:inputParameter>"
        + "<camunda:inputParameter name='b'>\\${toolParameter(toolCall.text)}"
        + "</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>"));

    assertThat(catalogOf("adHoc").get(0).getParameters())
        .extracting(AdHocToolDescriptor.Parameter::getName).containsExactly("stichwort");
  }

  /** A call in a listener, a condition or a timer inside the scope declares nothing. */
  @Test
  public void refusesAToolParameterInAListenerAConditionOrATimer() {
    String inAListener = agentic("<userTask id='notieren'><extensionElements>"
        + "<camunda:executionListener event='start' expression=\"${toolParameter(toolCall.x)}\" />"
        + "</extensionElements></userTask>");
    String inACondition = agentic("<serviceTask id='anfragen' camunda:expression='${1}' />"
        + "<sequenceFlow id='weiter' sourceRef='anfragen' targetRef='warten'>"
        + "<conditionExpression xsi:type='tFormalExpression'>"
        + "${toolParameter(toolCall.ok) == true}</conditionExpression></sequenceFlow>"
        + "<userTask id='warten' />");
    String inATimer = agentic("<userTask id='notieren' />"
        + "<boundaryEvent id='frist' attachedToRef='notieren'><timerEventDefinition>"
        + "<timeDuration>${toolParameter(toolCall.dauer)}</timeDuration>"
        + "</timerEventDefinition></boundaryEvent>");

    assertThatThrownBy(() -> deploy(inAListener)).hasMessageContaining("child 'notieren':"
        + " toolParameter(...) in the attribute expression of executionListener declares nothing");
    assertThatThrownBy(() -> deploy(inACondition)).hasMessageContaining("child 'weiter':"
        + " toolParameter(...) in conditionExpression declares nothing");
    assertThatThrownBy(() -> deploy(inATimer)).hasMessageContaining("child 'frist':"
        + " toolParameter(...) in timeDuration declares nothing");
  }

  /** An agent further down, inside a sub process tool, declares nothing in its inputs. */
  @Test
  public void refusesAToolParameterInTheInputsOfAnAgentInsideASubProcessTool() {
    String model = agentic("<subProcess id='bereich'><startEvent id='bs' />"
        + "<sequenceFlow id='b1' sourceRef='bs' targetRef='helfer' />"
        + "<adHocSubProcess id='helfer'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='auftrag'>${toolParameter(toolCall.auftrag)}"
        + "</camunda:inputParameter></camunda:inputOutput>" + connectorElement(message())
        + "</extensionElements><userTask id='innen' /></adHocSubProcess>"
        + "<sequenceFlow id='b2' sourceRef='helfer' targetRef='be' /><endEvent id='be' />"
        + "</subProcess>");

    assertThatThrownBy(() -> deploy(model))
        .hasMessageContaining("declares nothing")
        .hasMessageContaining("an input parameter of 'bereich'");
  }

  /**
   * The connector of an agentic scope that is no tool of another is read on every turn of its
   * agent, where no tool call's arguments arrive: toolCall there, in an expression or a script,
   * would read null and is refused.
   */
  @Test
  public void refusesToolCallInTheConnectorOfTheScopeItself() {
    String tool = "<userTask id='anfragen'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='a'>${toolParameter(toolCall.betrag)}"
        + "</camunda:inputParameter></camunda:inputOutput></extensionElements></userTask>";

    assertThatThrownBy(() -> deploy(process("<adHocSubProcess id='adHoc'>"
        + agentConnector("<camunda:inputParameter name='message'>Betrag: ${toolCall.betrag}"
            + "</camunda:inputParameter>") + tool + "</adHocSubProcess>")))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Process 'agenticScope', element 'adHoc': the input parameter"
            + " 'message' of its connector reads toolCall")
        .hasMessageContaining("where no tool call's arguments arrive, so it would read null");
    assertThatThrownBy(() -> deploy(process("<adHocSubProcess id='adHoc'>"
        + agentConnector("<camunda:inputParameter name='message'>"
            + "<camunda:script scriptFormat='groovy'>'Betrag: ' + toolCall.betrag</camunda:script>"
            + "</camunda:inputParameter>") + tool + "</adHocSubProcess>")))
        .as("in a script").hasMessageContaining("of its connector reads toolCall");
  }

  /**
   * The own input and output parameters of an agentic scope that is no tool of another are read
   * when it is entered and when it ends, where no tool call's arguments arrive: toolCall there is
   * refused, as in its connector.
   */
  @Test
  public void refusesToolCallInTheScopesOwnInputsAndOutputs() {
    String tool = "<userTask id='anfragen'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='a'>${toolParameter(toolCall.betrag)}"
        + "</camunda:inputParameter></camunda:inputOutput></extensionElements></userTask>";

    assertThatThrownBy(() -> deploy(process("<adHocSubProcess id='adHoc'><extensionElements>"
        + "<camunda:inputOutput><camunda:inputParameter name='kopie'>${toolCall.betraq}"
        + "</camunda:inputParameter></camunda:inputOutput>" + connectorElement(message())
        + "</extensionElements>" + tool + "</adHocSubProcess>")))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Process 'agenticScope', element 'adHoc': the input parameter"
            + " 'kopie' reads toolCall. The scope's own mappings are read when it is entered and"
            + " when it ends");
    assertThatThrownBy(() -> deploy(process("<adHocSubProcess id='adHoc'><extensionElements>"
        + "<camunda:inputOutput><camunda:outputParameter name='ergebnis'>${toolCall.betrag}"
        + "</camunda:outputParameter></camunda:inputOutput>" + connectorElement(message())
        + "</extensionElements>" + tool + "</adHocSubProcess>")))
        .as("an output").hasMessageContaining("the output parameter 'ergebnis' reads toolCall");
  }

  /**
   * toolCall in a script of a nested agent's connector is refused: the connector's inputs are read
   * on every turn of that agent, not when the outer call starts it, and declare nothing.
   */
  @Test
  public void refusesToolCallInAScriptOfANestedAgentsConnector() {
    String model = agentic("<adHocSubProcess id='helfer'><extensionElements>"
        + connectorElement("<camunda:inputParameter name='message'>"
            + "<camunda:script scriptFormat='groovy'>'Auftrag: ' + toolCall.auftrag"
            + "</camunda:script></camunda:inputParameter>")
        + "</extensionElements><userTask id='innen' /></adHocSubProcess>");

    assertThatThrownBy(() -> deploy(model)).hasMessageContaining("'helfer' is an agent itself");
  }

  /**
   * A literal is read as EL reads it: a backslash escapes a backslash or the enclosing quote, and
   * the description is the text with the escapes undone.
   */
  @Test
  public void aLiteralWithEscapesIsReadAsElReadsIt() {
    deploy(agentic("<userTask id='ablegen'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='a'>"
        + "${toolParameter(toolCall.pfad, 'Folder, as C:\\\\reports', 'string')}"
        + "</camunda:inputParameter>"
        + "<camunda:inputParameter name='b'>"
        + "${toolParameter(toolCall.kunde, 'The customer\\'s \"id\"')}</camunda:inputParameter>"
        + "<camunda:inputParameter name='c'>"
        + "${toolParameter(toolCall.notiz, \"Say \\\"hi\\\"\", \"string\")}"
        + "</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>"));

    assertThat(catalogOf("adHoc").get(0).getParameters())
        .extracting(AdHocToolDescriptor.Parameter::getName,
            AdHocToolDescriptor.Parameter::getDescription)
        .containsExactly(tuple("kunde", "The customer's \"id\""),
            tuple("notiz", "Say \"hi\""), tuple("pfad", "Folder, as C:\\reports"));
  }

  /**
   * A description given once counts for every call of the name, a call without one takes it, and
   * the same one given again agrees.
   */
  @Test
  public void aDescriptionGivenOnceCountsForEveryCall() {
    deploy(agentic("<userTask id='a'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='x'>${toolParameter(toolCall.wert)}</camunda:inputParameter>"
        + "<camunda:inputParameter name='y'>${toolParameter(toolCall.wert, 'Erste')}"
        + "</camunda:inputParameter>"
        + "<camunda:inputParameter name='z'>${toolParameter(toolCall.wert, 'Erste')}"
        + "</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>"));

    assertThat(catalogOf("adHoc").get(0).getParameters().get(0).getDescription())
        .isEqualTo("Erste");
  }

  /** Two different descriptions of one parameter are a conflict, as two types are. */
  @Test
  public void refusesAParameterDeclaredWithTwoDescriptions() {
    String model = agentic("<userTask id='a'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='y'>${toolParameter(toolCall.wert, 'Erste')}"
        + "</camunda:inputParameter>"
        + "<camunda:inputParameter name='z'>${toolParameter(toolCall.wert, 'Zweite')}"
        + "</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>");

    assertThatThrownBy(() -> deploy(model)).isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("child 'a', parameter 'wert' is declared with two descriptions,"
            + " 'Erste' and 'Zweite'");
  }

  @Test
  public void refusesAChildIdThatCannotBeAToolName() {
    String model = agentic("<userTask id='mit.punkt' />");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("cannot be a tool name");
  }

  @Test
  public void refusesAChildCollidingWithTheBuiltInTool() {
    String model = agentic("<userTask id='completeScope' />");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("collides with the built-in tool");
  }

  @Test
  public void refusesAnUnparseableBlockingMark() {
    String model = agentic("<userTask id='gated'>"
        + "  <extensionElements><camunda:properties>"
        + "    <camunda:property name='adHocBlockedWhileOthersRun' value='yes' />"
        + "  </camunda:properties></extensionElements>"
        + "</userTask>");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("must be 'true' or 'false'");
  }

  /**
   * What a tool reads, from its model (CIB7-2169): the variable a simple expression of its input
   * mapping names, or the root of a path; what a connector on it maps in; the sources of a call
   * activity. Not its own parameters, which the model passes, not what EL names itself, and not
   * what an expression only computes.
   */
  @Test
  public void aToolsReadsComeFromItsMappings() {
    deploy(agentic("<serviceTask id='pruefen' camunda:expression='${1}'>"
        + "<extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='nummer'>${kundeId}</camunda:inputParameter>"
        + "<camunda:inputParameter name='ort'>${kunde.adresse.ort}</camunda:inputParameter>"
        + "<camunda:inputParameter name='begriff'>${toolParameter(toolCall.suche, 'Suchbegriff')}"
        + "</camunda:inputParameter>"
        + "<camunda:inputParameter name='wer'>${execution.id}</camunda:inputParameter>"
        + "<camunda:inputParameter name='summe'>${a + b}</camunda:inputParameter>"
        + "<camunda:inputParameter name='fest'>42</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></serviceTask>"
        + "<callActivity id='unterprozess' calledElement='anderer'><extensionElements>"
        + "<camunda:in source='auftrag' target='auftrag' />"
        + "<camunda:in sourceExpression='${kundeId}' target='kunde' />"
        + "</extensionElements></callActivity>"));

    assertThat(readsOf("pruefen")).containsExactly("kundeId", "kunde");
    assertThat(readsOf("unterprozess")).containsExactly("auftrag", "kundeId");
  }

  /**
   * A parameter declared in an input mapping arrives in {@code toolCall}, the call's own map
   * (CIB7-2136): reading {@code toolCall.<name>}, plainly or through {@code toolParameter}, reads
   * what the model passed, which is no process data the tool reads.
   */
  @Test
  public void aReadOfTheCallsOwnArgumentsIsNoProcessData() {
    deploy(agentic("<serviceTask id='pruefen' camunda:expression='${1}'>"
        + "<extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='betrag'>"
        + "${toolParameter(toolCall.betrag, 'Betrag', 'integer')}</camunda:inputParameter>"
        + "<camunda:inputParameter name='nochmal'>${toolCall.betrag}</camunda:inputParameter>"
        + "<camunda:inputParameter name='nummer'>${kundeId}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></serviceTask>"));

    assertThat(readsOf("pruefen")).containsExactly("kundeId");
  }

  /**
   * A parameter declared in a mapping arrives only in toolCall and shadows nothing: a read of its
   * name elsewhere in the tool reads the process variable, which the description names.
   */
  @Test
  public void aProcessVariableNamedLikeAMappingParameterIsStillARead() {
    deploy(agentic("<serviceTask id='pruefen' camunda:expression='${1}'>"
        + "<extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='vorschlag'>"
        + "${toolParameter(toolCall.betrag, 'Betrag', 'integer')}</camunda:inputParameter>"
        + "<camunda:inputParameter name='alt'>${betrag}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></serviceTask>"));

    assertThat(readsOf("pruefen")).containsExactly("betrag");
  }

  /**
   * A connector's inputs and a call activity's {@code camunda:in} read the activity's own
   * execution, where its input parameters are local: a name the activity creates for itself is no
   * process data it reads. What those input parameters read is.
   */
  @Test
  public void aNameTheToolCreatesForItselfIsNoRead() {
    deploy(agentic("<serviceTask id='senden'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='payload'>${kundeId}</camunda:inputParameter>"
        + "</camunda:inputOutput><camunda:connector>"
        + "<camunda:connectorId>http-connector</camunda:connectorId><camunda:inputOutput>"
        + "<camunda:inputParameter name='body'>${payload}</camunda:inputParameter>"
        + "<camunda:inputParameter name='url'>${zielUrl}</camunda:inputParameter>"
        + "</camunda:inputOutput></camunda:connector></extensionElements></serviceTask>"
        + "<callActivity id='weitergeben' calledElement='anderer'><extensionElements>"
        + "<camunda:inputOutput>"
        + "<camunda:inputParameter name='a'>${x}</camunda:inputParameter>"
        + "</camunda:inputOutput>"
        + "<camunda:in source='a' target='a' local='true' />"
        + "<camunda:in source='auftrag' target='auftrag' />"
        + "</extensionElements></callActivity>"));

    assertThat(readsOf("senden")).containsExactly("kundeId", "zielUrl");
    assertThat(readsOf("weitergeben")).containsExactly("x", "auftrag");
  }

  /**
   * A multi-instance tool reads the collection it runs over, or the number of instances; its
   * element variable and the loop's own counters are no process data, each instance has them.
   */
  @Test
  public void aMultiInstanceToolReadsItsCollectionNotItsLoopVariables() {
    deploy(agentic("<userTask id='jeKunde'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='id'>${kunde.id}</camunda:inputParameter>"
        + "<camunda:inputParameter name='nr'>${loopCounter}</camunda:inputParameter>"
        + "<camunda:inputParameter name='land'>${land}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements>"
        + "<multiInstanceLoopCharacteristics isSequential='false'"
        + " camunda:collection='${kunden}' camunda:elementVariable='kunde' />"
        + "</userTask>"
        + "<userTask id='nachZahl'>"
        + "<multiInstanceLoopCharacteristics isSequential='true'>"
        + "<loopCardinality>${anzahl}</loopCardinality></multiInstanceLoopCharacteristics>"
        + "</userTask>"
        + "<userTask id='nachName'>"
        + "<multiInstanceLoopCharacteristics isSequential='true'"
        + " camunda:collection='bestellungen' camunda:elementVariable='bestellung' />"
        + "</userTask>"));

    assertThat(readsOf("jeKunde")).containsExactly("kunden", "land");
    assertThat(readsOf("nachZahl")).containsExactly("anzahl");
    assertThat(readsOf("nachName")).containsExactly("bestellungen");
  }

  protected List<String> readsOf(String activityId) {
    for (AdHocToolDescriptor descriptor : catalogOf("adHoc")) {
      if (descriptor.getActivityId().equals(activityId)) {
        return descriptor.getReadVariables();
      }
    }
    throw new AssertionError("no tool " + activityId);
  }

  // --- the agent's connector on the scope

  @Test
  public void theAgentsConnectorOnTheScopeMakesItAgentic() {
    deploy(connectorScope("<userTask id='worker' />"));

    engine.getRuntimeService().startProcessInstanceByKey("agenticScope");

    assertThat(engine.getManagementService().createJobQuery().list())
        .as("entered and parked, the first turn waits").hasSize(1);
    assertThat(catalogOf("adHoc")).extracting(AdHocToolDescriptor::getActivityId)
        .containsExactly("worker");
  }

  @Test
  public void theLimitsStayPropertiesOfTheScope() {
    deploy(connectorScope("<userTask id='worker' />",
        "<camunda:properties><camunda:property name='cibseven.agentic.maxTurns' value='3' />"
        + "</camunda:properties>", ""));

    engine.getRuntimeService().startProcessInstanceByKey("agenticScope");

    assertThat(engine.getManagementService().createJobQuery().list()).hasSize(1);
  }

  // ─── CIB7-2137: where the conversation lives ──────────────────────────────────

  @Test
  public void refusesAnUnknownConversationValue() {
    String model = connectorScope("<userTask id='worker' />",
        "<camunda:properties><camunda:property name='cibseven.agentic.conversation'"
        + " value='sometimes' /></camunda:properties>", "");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("'sometimes' is not a value of cibseven.agentic.conversation");
  }

  @Test
  public void refusesMaxTurnsTotalWithoutPerActivity() {
    String model = connectorScope("<userTask id='worker' />",
        "<camunda:properties><camunda:property name='cibseven.agentic.maxTurnsTotal'"
        + " value='5' /></camunda:properties>", "");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("bounds the turns of every entry together");
  }

  @Test
  public void acceptsBlankMaxTurnsTotalAsUnsetInEitherMode() {
    for (String mode : new String[] { "perEntry", "perActivity" }) {
      for (String value : new String[] { "", "   ", "&#9;&#10;" }) {
        deploy(connectorScope("<userTask id='worker' />",
            "<camunda:properties><camunda:property name='cibseven.agentic.conversation'"
            + " value='" + mode + "' /><camunda:property name='cibseven.agentic.maxTurnsTotal'"
            + " value='" + value + "' /></camunda:properties>", ""));
      }
    }
  }

  @Test
  public void refusesNonEmptyInvalidMaxTurnsTotalIncludingOverflow() {
    for (String value : new String[] { "0", "-1", "abc", "1.5", "2147483648" }) {
      String model = connectorScope("<userTask id='worker' />",
          "<camunda:properties><camunda:property name='cibseven.agentic.conversation'"
          + " value='perActivity' /><camunda:property name='cibseven.agentic.maxTurnsTotal'"
          + " value='" + value + "' /></camunda:properties>", "");
      assertThatThrownBy(() -> deploy(model)).isInstanceOf(ProcessEngineException.class)
          .hasMessageContaining("must be a positive integer");
    }
  }

  @Test
  public void refusesAnOverlongPerActivityIdBeforeTheFirstInstance() {
    String model = connectorScope("<userTask id='worker' />",
        "<camunda:properties><camunda:property name='cibseven.agentic.conversation'"
        + " value='perActivity' /></camunda:properties>", "")
        .replace("'adHoc'", "'" + "a".repeat(180) + "'");
    assertThatThrownBy(() -> deploy(model)).isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("255-character limit").hasMessageContaining("Shorten the activity id");
  }

  @Test
  public void refusesPerActivityOnAParallelMultiInstanceScope() {
    String model = connectorScope(
        "<multiInstanceLoopCharacteristics isSequential='false'><loopCardinality>2</loopCardinality>"
        + "</multiInstanceLoopCharacteristics><userTask id='worker' />",
        "<camunda:properties><camunda:property name='cibseven.agentic.conversation'"
        + " value='perActivity' /></camunda:properties>", "");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("is not supported on a parallel multi-instance scope");
  }

  @Test
  public void acceptsPerActivityOnASequentialMultiInstanceScope() {
    String model = connectorScope(
        "<multiInstanceLoopCharacteristics isSequential='true'><loopCardinality>2</loopCardinality>"
        + "</multiInstanceLoopCharacteristics><userTask id='worker' />",
        "<camunda:properties><camunda:property name='cibseven.agentic.conversation'"
        + " value='perActivity' /></camunda:properties>", "");

    deploy(model);
    engine.getRuntimeService().startProcessInstanceByKey("agenticScope");

    assertThat(engine.getManagementService().createJobQuery().list()).hasSize(1);
  }

  // --- what a limit does to the scope (CIB7-2151)

  @Test
  public void refusesAnUnknownOnLimit() {
    String model = connectorScope("<userTask id='worker' />", onLimit("throw"), "");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("'throw' is not a value of cibseven.agentic.onLimit");
  }

  @Test
  public void acceptsOnLimitContinueWithoutABoundaryEvent() {
    deploy(connectorScope("<userTask id='worker' />", onLimit("continue"), ""));
  }

  /** The engine would end the scope silently for an error nothing catches. */
  @Test
  public void refusesOnLimitErrorWithoutABoundaryErrorEvent() {
    String model = connectorScope("<userTask id='worker' />", onLimit("error"), "");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("no boundary error event on it catches turnLimit, callLimit");
  }

  @Test
  public void refusesOnLimitErrorWhenTheBoundaryCatchesOnlySomeLimits() {
    String model = withBoundary(connectorScope("<userTask id='worker' />", onLimit("error"), ""),
        limitBoundary("onCallLimit", "callLimit"));

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("catches turnLimit.");
  }

  @Test
  public void acceptsOnLimitErrorWithABoundaryThatCatchesEveryError() {
    deploy(withBoundary(connectorScope("<userTask id='worker' />", onLimit("error"), ""),
        limitBoundary("onLimit", null)));
  }

  @Test
  public void acceptsOnLimitErrorWithABoundaryPerLimit() {
    deploy(withBoundary(connectorScope("<userTask id='worker' />", onLimit("error"), ""),
        limitBoundary("onTurnLimit", "turnLimit") + limitBoundary("onCallLimit", "callLimit")));
  }

  /** The total is a limit only where the entries share a conversation and it is set. */
  @Test
  public void refusesOnLimitErrorWhenTheTotalTurnLimitIsNotCaught() {
    String properties = "<camunda:properties>"
        + "<camunda:property name='cibseven.agentic.onLimit' value='error' />"
        + "<camunda:property name='cibseven.agentic.conversation' value='perActivity' />"
        + "<camunda:property name='cibseven.agentic.maxTurnsTotal' value='5' />"
        + "</camunda:properties>";
    String model = withBoundary(connectorScope("<userTask id='worker' />", properties, ""),
        limitBoundary("onTurnLimit", "turnLimit") + limitBoundary("onCallLimit", "callLimit"));

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("catches totalTurnLimit.");
  }

  /** An error event sub process inside the agent is no boundary event on it. */
  @Test
  public void refusesOnLimitErrorCaughtOnlyInsideTheScope() {
    String model = connectorScope("<userTask id='worker' />" + innerErrorHandler(null),
        onLimit("error"), "");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("the event sub process 'onError' inside it catches"
            + " turnLimit, callLimit first");
  }

  /**
   * The engine offers an error to the event sub processes inside a scope before the boundary
   * events on it: with both catching every error, the limit never reaches the boundary.
   */
  @Test
  public void refusesOnLimitErrorWhenAnInnerEventSubProcessCatchesTheLimitFirst() {
    String model = withBoundary(connectorScope("<userTask id='worker' />"
        + innerErrorHandler(null), onLimit("error"), ""), limitBoundary("onLimit", null));

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("the event sub process 'onError' inside it catches"
            + " turnLimit, callLimit first");
  }

  /** An event sub process ranks above a boundary event, even one that names the code. */
  @Test
  public void refusesOnLimitErrorWhenAnInnerEventSubProcessOutranksABoundaryPerLimit() {
    String model = withBoundary(connectorScope("<userTask id='worker' />"
        + innerErrorHandler(null), onLimit("error"), ""),
        limitBoundary("onTurnLimit", "turnLimit") + limitBoundary("onCallLimit", "callLimit"));

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("the event sub process 'onError' inside it catches"
            + " turnLimit, callLimit first");
  }

  /**
   * A limit nothing catches and a limit an event sub process inside takes first are named in one
   * refusal (CIB7-2151, review).
   */
  @Test
  public void refusesOnLimitErrorNamingTheUncaughtAndTheInnerCatchAtOnce() {
    String model = connectorScope("<userTask id='worker' />" + innerErrorHandler("turnLimit"),
        onLimit("error"), "");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("no boundary error event on it catches callLimit.")
        .hasMessageContaining("And the event sub process 'onError' inside it catches"
            + " turnLimit first.")
        .hasMessageContaining("Let 'onError' catch only its own error codes");
  }

  /** Each event sub process inside that takes a limit first is named. */
  @Test
  public void refusesOnLimitErrorNamingEveryInnerEventSubProcess() {
    String model = withBoundary(connectorScope("<userTask id='worker' />"
        + innerErrorHandler("onTurn", "turnLimit") + innerErrorHandler("onCall", "callLimit"),
        onLimit("error"), ""), limitBoundary("onLimit", null));

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("the event sub process 'onTurn' inside it catches turnLimit first,"
            + " and the event sub process 'onCall' inside it catches callLimit first.")
        .hasMessageContaining("Let each of them catch only its own error codes")
        .hasMessageNotContaining("no boundary error event");
  }

  /** An event sub process for an error of its own leaves the limits to the boundary. */
  @Test
  public void acceptsOnLimitErrorWithAnInnerEventSubProcessForAnotherCode() {
    deploy(withBoundary(connectorScope("<userTask id='worker' />"
        + innerErrorHandler("toolFailed"), onLimit("error"), ""), limitBoundary("onLimit", null)));
  }

  @Test
  public void onLimitWithoutTheConnectorMarksTheScopeAsAnAgent() {
    String model = process("<adHocSubProcess id='adHoc'><extensionElements>" + onLimit("error")
        + "</extensionElements><userTask id='worker' /></adHocSubProcess>");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("carries no connector 'cibseven-ai-agent'");
  }

  protected static String onLimit(String value) {
    return "<camunda:properties><camunda:property name='cibseven.agentic.onLimit' value='"
        + value + "' /></camunda:properties>";
  }

  /** A boundary error event on the scope, catching {@code code}, or every error when it is null. */
  protected static String limitBoundary(String id, String code) {
    return "<boundaryEvent id='" + id + "' attachedToRef='adHoc'><errorEventDefinition"
        + (code == null ? "" : " errorRef='" + code + "'") + " /></boundaryEvent>";
  }

  /** An error event sub process inside the scope, catching {@code code}, or all when it is null. */
  protected static String innerErrorHandler(String code) {
    return innerErrorHandler("onError", code);
  }

  /** The same, as the event sub process {@code id}, so that a scope can hold more than one. */
  protected static String innerErrorHandler(String id, String code) {
    return "<subProcess id='" + id + "' triggeredByEvent='true'>"
        + "<startEvent id='" + id + "Start'><errorEventDefinition"
        + (code == null ? "" : " errorRef='" + code + "'") + " /></startEvent>"
        + "<sequenceFlow id='" + id + "Flow' sourceRef='" + id + "Start' targetRef='" + id
        + "End' />"
        + "<endEvent id='" + id + "End' /></subProcess>";
  }

  protected static String withBoundary(String model, String boundary) {
    return model.replace("<sequenceFlow id='f2'", boundary + "<sequenceFlow id='f2'");
  }

  @Test
  public void acceptsThePerActivityMinimumAndUnspecifiedContextualDefaults() {
    deploy(memoryWindowModel(2, "7"));
    deploy(memoryWindowModel(2, null));
    deploy(memoryWindowModel(30, null));
  }

  @Test
  public void refusesALiteralPerActivityMemoryWindowBelowItsMinimum() {
    assertThatThrownBy(() -> deploy(memoryWindowModel(2, "6")))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Ad hoc sub process 'adHoc'")
        .hasMessageContaining("chatMemoryMaxMessages must be at least 7 messages")
        .hasMessageContaining("but is 6");
  }

  @Test
  public void refusesAWindowRequirementThatExceedsTheSupportedIntegerSize() {
    String model = memoryWindowModel(Integer.MAX_VALUE, null);
    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Ad hoc sub process 'adHoc'")
        .hasMessageContaining("requires a minimum of 4294967297 messages")
        .hasMessageContaining("exceeding the supported integer window size");
  }

  @Test
  public void doesNotEvaluateAConfiguredWindowExpressionDuringDeployment() {
    deploy(memoryWindowModel(2, "${missingWindow}"));
    assertThat(engine.getManagementService().createJobQuery().list()).isEmpty();
  }

  @Test
  public void refusesTheAgentsConnectorOnAPlainSubProcess() {
    String model = process("<subProcess id='plain'>" + agentConnector(message())
        + "<startEvent id='s' /><sequenceFlow id='s1' sourceRef='s' targetRef='worker' />"
        + "<userTask id='worker' /><sequenceFlow id='s2' sourceRef='worker' targetRef='e' />"
        + "<endEvent id='e' /></subProcess>");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("only valid on an adHocSubProcess")
        .hasMessageContaining("Make it an ad hoc sub process, or remove the agent from it");
  }

  /** The template link and the limits mark an agent as much as the connector does. */
  @Test
  public void refusesTheAgentsTemplateOrLimitsOnAPlainSubProcess() {
    String inner = "<startEvent id='s' /><sequenceFlow id='s1' sourceRef='s' targetRef='worker' />"
        + "<userTask id='worker' /><sequenceFlow id='s2' sourceRef='worker' targetRef='e' />"
        + "<endEvent id='e' />";
    for (String scope : new String[] {
        "<subProcess id='plain' camunda:modelerTemplate='"
            + AgenticAdHocParseListener.AD_HOC_TEMPLATE_ID + "'>" + inner + "</subProcess>",
        "<subProcess id='plain'><extensionElements><camunda:properties>"
            + "<camunda:property name='cibseven.agentic.maxTurns' value='3' />"
            + "</camunda:properties></extensionElements>" + inner + "</subProcess>"}) {
      String model = process(scope);

      assertThatThrownBy(() -> deploy(model))
          .as(scope.contains("modelerTemplate") ? "the template link" : "a limit")
          .isInstanceOf(ProcessEngineException.class)
          .hasMessageContaining("only valid on an adHocSubProcess");
    }
  }

  /**
   * A misspelt connector id on its own is only reported (CIB7-2180), but a scope the template
   * marks as an agent is refused: it would otherwise wait for an agent that never comes.
   */
  @Test
  public void refusesAMisspeltConnectorOnAScopeTheTemplateMarks() {
    String model = process("<adHocSubProcess id='adHoc' camunda:modelerTemplate='"
        + AgenticAdHocParseListener.AD_HOC_TEMPLATE_ID + "'><extensionElements><camunda:connector>"
        + "<camunda:connectorId>cibseven-ai-agnet</camunda:connectorId><camunda:inputOutput>"
        + message() + "</camunda:inputOutput></camunda:connector></extensionElements>"
        + "<userTask id='worker' /></adHocSubProcess>");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("carries no connector 'cibseven-ai-agent'");
  }

  /** What a modeler leaves behind when it drops the connector of a copied or retyped scope. */
  @Test
  public void refusesTheAgentsTemplateWithoutItsConnector() {
    String model = process("<adHocSubProcess id='adHoc' camunda:modelerTemplate='"
        + AgenticAdHocParseListener.AD_HOC_TEMPLATE_ID + "' camunda:modelerTemplateVersion='3'>"
        + "<userTask id='worker' /></adHocSubProcess>");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("is marked as an AI agent (the template")
        .hasMessageContaining("carries no connector 'cibseven-ai-agent'")
        .hasMessageContaining("copied and pasted, or when its type is changed")
        .hasMessageContaining("Apply the template");
  }

  @Test
  public void refusesTheAgentsLimitsWithoutItsConnector() {
    String model = process("<adHocSubProcess id='adHoc'><extensionElements><camunda:properties>"
        + "<camunda:property name='cibseven.agentic.maxTurns' value='3' />"
        + "</camunda:properties></extensionElements><userTask id='worker' /></adHocSubProcess>");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("the agent's limits cibseven.agentic.maxTurns")
        .hasMessageContaining("carries no connector 'cibseven-ai-agent'");
  }

  @Test
  public void refusesAConnectorWithoutATask() {
    String model = process("<adHocSubProcess id='adHoc'>"
        + agentConnector("<camunda:inputParameter name='model'>gpt</camunda:inputParameter>")
        + "<userTask id='worker' /></adHocSubProcess>");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("needs a task for its agent")
        .hasMessageContaining("Add the input parameter 'message'");
  }

  /** What the template writes before the field is filled in: the parameter, without a value. */
  @Test
  public void refusesAConnectorWithAnEmptyTask() {
    String model = process("<adHocSubProcess id='adHoc'>"
        + agentConnector("<camunda:inputParameter name='message' />"
            + "<camunda:inputParameter name='instruction' />")
        + "<userTask id='worker' /></adHocSubProcess>");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("the input parameter 'message'")
        .hasMessageContaining("is empty")
        .hasMessageContaining("Fill in the agent's task");
  }

  /** The scope's tools are its children; a class registered on the connector is refused. */
  @Test
  public void refusesToolClassesOnTheScopesConnector() {
    String model = connectorScope("<userTask id='worker' />", "", "")
        .replace("<camunda:inputParameter name='message'>",
            "<camunda:inputParameter name='toolClasses'>org.example.Tool</camunda:inputParameter>"
            + "<camunda:inputParameter name='message'>");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("'toolClasses'")
        .hasMessageContaining("Its tools are its own activities");
  }

  @Test
  public void refusesAConnectorThatSetsTheConversation() {
    for (String loopOwned : new String[] {"memoryId", "useChatMemory"}) {
      String model = process("<adHocSubProcess id='adHoc'>"
          + agentConnector(message()
              + "<camunda:inputParameter name='" + loopOwned + "'>x</camunda:inputParameter>")
          + "<userTask id='worker' /></adHocSubProcess>");

      assertThatThrownBy(() -> deploy(model))
          .as(loopOwned)
          .isInstanceOf(ProcessEngineException.class)
          .hasMessageContaining("'" + loopOwned + "'")
          .hasMessageContaining("is set by the agentic loop itself");
    }
  }

  @Test
  public void refusesTheTaskAsAnInputParameterOfTheScope() {
    for (String taskParameter : new String[] {"instruction", "message"}) {
      String model = connectorScope("<userTask id='worker' />", "",
          "<camunda:inputOutput><camunda:inputParameter name='" + taskParameter + "'>Sei genau."
          + "</camunda:inputParameter></camunda:inputOutput>");

      assertThatThrownBy(() -> deploy(model))
          .as(taskParameter)
          .isInstanceOf(ProcessEngineException.class)
          .hasMessageContaining("'" + taskParameter + "' is an input parameter of the scope")
          .hasMessageContaining("Move it into the input parameters of the connector");
    }
  }

  /**
   * A refusal is a parse error on the element, as the engine's own are: the REST API answers 400
   * with the element, line and column, and a modeler can point at it.
   */
  @Test
  public void aRefusalIsAParseErrorOnTheScope() {
    String model = process("<adHocSubProcess id='adHoc'>"
        + agentConnector("<camunda:inputParameter name='model'>gpt</camunda:inputParameter>")
        + "<userTask id='worker' /></adHocSubProcess>");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOfSatisfying(ParseException.class, e -> {
          Problem problem = e.getResorceReports().get(0).getErrors().get(0);
          assertThat(problem.getMainElementId()).isEqualTo("adHoc");
          assertThat(problem.getLine()).isPositive();
          assertThat(problem.getColumn()).isPositive();
        });
  }

  /** A tool's refusal points at the tool, not at the scope. */
  @Test
  public void aToolRefusalIsAParseErrorOnTheTool() {
    for (String child : new String[] {
        "<userTask id='mit.punkt' />",
        "<userTask id='completeScope' />",
        "<userTask id='gated'><extensionElements><camunda:properties>"
            + "<camunda:property name='adHocBlockedWhileOthersRun' value='yes' />"
            + "</camunda:properties></extensionElements></userTask>",
        "<userTask id='typed'><extensionElements><camunda:inputOutput>"
            + "<camunda:inputParameter name='datum'>${toolParameter(toolCall.datum, '', 'date')}"
            + "</camunda:inputParameter></camunda:inputOutput></extensionElements></userTask>"}) {
      String model = agentic(child);
      String childId = child.replaceAll("^<userTask id='([^']+)'.*", "$1");

      assertThatThrownBy(() -> deploy(model))
          .as(childId)
          .isInstanceOfSatisfying(ParseException.class, e -> assertThat(
              e.getResorceReports().get(0).getErrors().get(0).getMainElementId())
              .isEqualTo(childId));
    }
  }

  @Test
  public void leavesAnotherConnectorOnAnAdHocScopeAlone() {
    deploy(process("<adHocSubProcess id='adHoc'><extensionElements><camunda:connector>"
        + "<camunda:connectorId>http-connector</camunda:connectorId></camunda:connector>"
        + "</extensionElements><userTask id='worker' /></adHocSubProcess>"));

    engine.getRuntimeService().startProcessInstanceByKey("agenticScope");

    assertThat(engine.getManagementService().createJobQuery().list())
        .as("not an agent: reported in the log, deployed as before").isEmpty();
  }

  protected static String message() {
    return "<camunda:inputParameter name='message'>Do the work.</camunda:inputParameter>";
  }

  protected static String agentConnector(String inputs) {
    return "<extensionElements>" + connectorElement(inputs) + "</extensionElements>";
  }

  protected static String connectorElement(String inputs) {
    return "<camunda:connector><camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "<camunda:inputOutput>" + inputs + "</camunda:inputOutput></camunda:connector>";
  }

  protected static String connectorScope(String children) {
    return connectorScope(children, "", "");
  }

  /** An ad hoc scope with the agent's connector, plus further extension elements of the scope. */
  protected static String connectorScope(String children, String properties, String scopeIo) {
    return process("<adHocSubProcess id='adHoc'><extensionElements>" + properties + scopeIo
        + connectorElement(message()) + "</extensionElements>" + children + "</adHocSubProcess>");
  }

  private static String memoryWindowModel(int maxModelCalls, String window) {
    String properties = "<camunda:properties>"
        + "<camunda:property name='cibseven.agentic.conversation' value='perActivity' />"
        + "<camunda:property name='cibseven.agentic.maxModelCalls' value='"
        + maxModelCalls + "' /></camunda:properties>";
    String additionalInput = (window == null) ? ""
        : "<camunda:inputParameter name='chatMemoryMaxMessages'>" + window
            + "</camunda:inputParameter>";
    String model = connectorScope("<userTask id='worker' />", properties, "");
    return model.replace("</camunda:inputParameter></camunda:inputOutput>",
        "</camunda:inputParameter>" + additionalInput + "</camunda:inputOutput>");
  }

  /** The catalogue of the latest deployed definition's scope. Needs a command context. */
  protected List<AdHocToolDescriptor> catalogOf(final String activityId) {
    final String definitionId = engine.getRepositoryService()
        .createProcessDefinitionQuery().latestVersion().singleResult().getId();
    return config.getCommandExecutorTxRequired().execute(
        new org.cibseven.bpm.engine.impl.interceptor.Command<List<AdHocToolDescriptor>>() {
          @Override
          public List<AdHocToolDescriptor> execute(
              org.cibseven.bpm.engine.impl.interceptor.CommandContext ctx) {
            ProcessDefinitionEntity entity = ctx.getProcessEngineConfiguration()
                .getDeploymentCache().findDeployedProcessDefinitionById(definitionId);
            return entity.findActivity(activityId).getProperties()
                .get(AdHocToolDescriptor.CATALOG);
          }
        });
  }

  /**
   * How many listeners of ours sit on the activity, custom or built in. Counting all of them says
   * nothing: the engine puts two on every activity of its own accord.
   *
   * <p>The deployment cache needs a command context, so this runs as a command.
   */
  protected int ourListenersOn(final String activityId) {
    return onActivity(activityId, activity -> {
      int mine = 0;
      for (String event : new String[] {
          org.cibseven.bpm.engine.delegate.ExecutionListener.EVENTNAME_START,
          org.cibseven.bpm.engine.delegate.ExecutionListener.EVENTNAME_END}) {
        for (Object listener : activity.getListeners(event)) {
          if (listener.getClass().getName().startsWith("org.cibseven.connect.ai.agent.")) {
            mine++;
          }
        }
      }
      return mine;
    });
  }

  /** How many turn triggers the activity's ad hoc behaviour tells of its children. */
  protected int triggersOn(final String activityId) {
    return onActivity(activityId, activity -> {
      int triggers = 0;
      for (AdHocSubProcessObserver observer
          : ((AdHocSubProcessActivityBehavior) activity.getActivityBehavior()).getObservers()) {
        if (observer instanceof AgenticTurnTrigger) {
          triggers++;
        }
      }
      return triggers;
    });
  }

  /** Looks at an activity of the latest deployed definition. Needs a command context. */
  protected int onActivity(final String activityId,
      final java.util.function.ToIntFunction<ActivityImpl> count) {
    final String definitionId = engine.getRepositoryService()
        .createProcessDefinitionQuery().latestVersion().singleResult().getId();
    return config.getCommandExecutorTxRequired().execute(
        new org.cibseven.bpm.engine.impl.interceptor.Command<Integer>() {
          @Override
          public Integer execute(org.cibseven.bpm.engine.impl.interceptor.CommandContext ctx) {
            ProcessDefinitionEntity entity = ctx.getProcessEngineConfiguration()
                .getDeploymentCache().findDeployedProcessDefinitionById(definitionId);
            return count.applyAsInt(entity.findActivity(activityId));
          }
        });
  }

  protected List<Job> turnJobs() {
    List<Job> turns = new java.util.ArrayList<Job>();
    for (Job job : engine.getManagementService().createJobQuery().list()) {
      if (AgenticTurnJobHandler.TYPE.equals(
          ((org.cibseven.bpm.engine.impl.persistence.entity.JobEntity) job).getJobHandlerType())) {
        turns.add(job);
      }
    }
    return turns;
  }

  /** As if the turns that were waiting had run: what follows must schedule its own. */
  protected void deleteTurnJobs() {
    for (Job job : turnJobs()) {
      engine.getManagementService().deleteJob(job.getId());
    }
  }


  protected String scopeExecutionId(String processInstanceId) {
    for (Execution execution : engine.getRuntimeService().createExecutionQuery()
        .processInstanceId(processInstanceId).list()) {
      if ("adHoc".equals(((ExecutionEntity) execution).getActivityId())) {
        return execution.getId();
      }
    }
    throw new AssertionError("no execution sitting on the ad hoc scope");
  }

  protected ProcessInstance start(String model) {
    deploy(model);
    return engine.getRuntimeService().startProcessInstanceByKey("agenticScope");
  }

  protected void deploy(String model) {
    engine.getRepositoryService().createDeployment()
        .addString("agentic.bpmn20.xml", model).deploy();
  }

  protected static String agentic(String children) {
    return process("<adHocSubProcess id='adHoc'>" + agentConnector(message()) + children
        + "</adHocSubProcess>");
  }

  protected static String process(String scope) {
    return process("agenticScope", scope);
  }

  /** The process around the scope, under an id of its own: a plain one is no agentic scope. */
  protected static String process(String processId, String scope) {
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + "             xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'"
        + "             targetNamespace='http://cibseven.org/agentic'>"
        + "  <process id='" + processId + "' isExecutable='true'>"
        + "    <startEvent id='start' />"
        + "    <sequenceFlow id='f1' sourceRef='start' targetRef='"
        + (scope.contains("id='plain'") ? "plain" : "adHoc") + "' />"
        + scope
        + "    <sequenceFlow id='f2' sourceRef='"
        + (scope.contains("id='plain'") ? "plain" : "adHoc") + "' targetRef='end' />"
        + "    <endEvent id='end' />"
        + "  </process>"
        + "</definitions>";
  }
}
