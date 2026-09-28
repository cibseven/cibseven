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
package org.cibseven.connect.plugin.agentic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocToolDescriptor;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.runtime.Execution;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.cibseven.connect.plugin.impl.agentic.AgenticAdHocEndListener;
import org.cibseven.connect.plugin.impl.agentic.AgenticTurnJobHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

  @Test
  public void listensOnlyAtTheEndOfAChain() {
    deploy(agentic("<userTask id='a' /><userTask id='b' />"
        + "<sequenceFlow id='inner' sourceRef='a' targetRef='b' />"));

    assertThat(listenerCount("a")).isZero();
    assertThat(listenerCount("b")).isOne();
  }

  @Test
  public void refusesAgenticOnAPlainSubProcess() {
    String model = process("<subProcess id='plain'>" + properties()
        + "<startEvent id='subStart' /><userTask id='worker' />"
        + "<sequenceFlow id='subFlow' sourceRef='subStart' targetRef='worker' />"
        + "</subProcess>");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("only valid on an adHocSubProcess");
  }

  @Test
  public void refusesAnOwnCompletionCondition() {
    String model = process("<adHocSubProcess id='adHoc'>" + properties()
        + "<userTask id='worker' />"
        + "<completionCondition xsi:type='tFormalExpression'>${false}</completionCondition>"
        + "</adHocSubProcess>");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("must not carry a completionCondition");
  }

  @Test
  public void refusesAScopeWithNothingStartable() {
    String model = process("<adHocSubProcess id='adHoc'>" + properties()
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

    // No agentic property: no parking, no listeners, no job.
    assertThat(engine.getManagementService().createJobQuery().list()).isEmpty();
    assertThat(listenerCount("worker")).isZero();
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

  @Test
  public void aDeclaredParameterCarriesTypeAndDescription() {
    deploy(agentic(
        "<serviceTask id='fetch' name='Fetch' camunda:expression='${1}'>"
        + "  <extensionElements><camunda:properties>"
        + "    <camunda:property name='adHocToolParameter.stadt'"
        + "        value='string|Die Stadt, deren Temperatur gesucht ist' />"
        + "    <camunda:property name='adHocToolParameter.limit' value='integer' />"
        + "  </camunda:properties></extensionElements>"
        + "</serviceTask>"));

    List<AdHocToolDescriptor.Parameter> parameters = catalogOf("adHoc").get(0).getParameters();

    assertThat(parameters).hasSize(2);
    assertThat(parameters.get(0).getName()).isEqualTo("limit");
    assertThat(parameters.get(0).getType()).isEqualTo("integer");
    assertThat(parameters.get(0).getDescription()).isEmpty();
    assertThat(parameters.get(1).getName()).isEqualTo("stadt");
    assertThat(parameters.get(1).getType()).isEqualTo("string");
    assertThat(parameters.get(1).getDescription())
        .isEqualTo("Die Stadt, deren Temperatur gesucht ist");
  }

  @Test
  public void refusesAParameterWithAnUnknownType() {
    String model = agentic("<serviceTask id='fetch' camunda:expression='${1}'>"
        + "  <extensionElements><camunda:properties>"
        + "    <camunda:property name='adHocToolParameter.stadt' value='text|kaputt' />"
        + "  </camunda:properties></extensionElements>"
        + "</serviceTask>");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("'text' is not supported");
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
  public void refusesAScopeWithoutATaskForItsAgent() {
    String model = process("<adHocSubProcess id='adHoc'>"
        + "<extensionElements><camunda:properties>"
        + "<camunda:property name='cibseven.agentic.enabled' value='true' />"
        + "</camunda:properties></extensionElements>"
        + "<userTask id='worker' /></adHocSubProcess>");

    assertThatThrownBy(() -> deploy(model))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("needs a task for its agent");
  }

  /** The instruction input parameter is the dynamic form of the task, and suffices. */
  @Test
  public void anInstructionInputParameterIsATask() {
    deploy(process("<adHocSubProcess id='adHoc'>"
        + "<extensionElements>"
        + "<camunda:properties>"
        + "<camunda:property name='cibseven.agentic.enabled' value='true' />"
        + "</camunda:properties>"
        + "<camunda:inputOutput>"
        + "<camunda:inputParameter name='instruction'>Pruefe ${'die Rechnung'}"
        + "</camunda:inputParameter>"
        + "</camunda:inputOutput>"
        + "</extensionElements>"
        + "<userTask id='worker' /></adHocSubProcess>"));

    // Entering the scope evaluates the mapping and parks; the first turn waits.
    ProcessInstance pi = engine.getRuntimeService().startProcessInstanceByKey("agenticScope");
    assertThat(engine.getManagementService().createJobQuery().list()).hasSize(1);
    assertThat(engine.getRuntimeService()
        .getVariableLocal(scopeExecutionId(pi.getId()), "instruction"))
        .as("evaluated from process data when the scope was entered")
        .isEqualTo("Pruefe die Rechnung");
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
   * How many of <em>our</em> end listeners sit on the activity. Counting all of them says
   * nothing: the engine puts two on every activity of its own accord.
   *
   * <p>The deployment cache needs a command context, so this runs as a command.
   */
  protected int listenerCount(final String activityId) {
    final String definitionId = engine.getRepositoryService()
        .createProcessDefinitionQuery().latestVersion().singleResult().getId();
    return config.getCommandExecutorTxRequired().execute(
        new org.cibseven.bpm.engine.impl.interceptor.Command<Integer>() {
          @Override
          public Integer execute(org.cibseven.bpm.engine.impl.interceptor.CommandContext ctx) {
            ProcessDefinitionEntity entity = ctx.getProcessEngineConfiguration()
                .getDeploymentCache().findDeployedProcessDefinitionById(definitionId);
            ActivityImpl activity = entity.findActivity(activityId);
            int mine = 0;
            for (Object listener : activity.getListeners(
                org.cibseven.bpm.engine.delegate.ExecutionListener.EVENTNAME_END)) {
              if (listener instanceof AgenticAdHocEndListener) {
                mine++;
              }
            }
            return mine;
          }
        });
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

  protected static String properties() {
    return "<extensionElements><camunda:properties>"
        + "<camunda:property name='cibseven.agentic.enabled' value='true' />"
        + "<camunda:property name='cibseven.agentic.message' value='Do the work.' />"
        + "</camunda:properties></extensionElements>";
  }

  protected static String agentic(String children) {
    return process("<adHocSubProcess id='adHoc'>" + properties() + children + "</adHocSubProcess>");
  }

  protected static String process(String scope) {
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + "             xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'"
        + "             targetNamespace='http://cibseven.org/agentic'>"
        + "  <process id='agenticScope' isExecutable='true'>"
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
