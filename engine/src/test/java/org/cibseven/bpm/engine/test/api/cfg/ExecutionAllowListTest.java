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
package org.cibseven.bpm.engine.test.api.cfg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import javax.script.ScriptEngine;

import org.cibseven.bpm.application.ProcessApplicationInterface;
import org.cibseven.bpm.application.impl.EmbeddedProcessApplication;
import org.cibseven.bpm.dmn.engine.DmnDecisionTableResult;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.ParseException;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.repository.Deployment;
import org.cibseven.bpm.engine.repository.DeploymentBuilder;
import org.cibseven.bpm.engine.repository.DeploymentWithDefinitions;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.cibseven.commons.testing.ProcessEngineLoggingRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import ch.qos.logback.classic.Level;

/**
 * executionAllowList: allow list for the built-in task types and the script languages (CIB7-2653).
 */
public class ExecutionAllowListTest {

  protected static final String SHELL_PROCESS =
      "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
      + " xmlns:camunda=\"http://camunda.org/schema/1.0/bpmn\" targetNamespace=\"Examples\">"
      + "<process id=\"shellProcess\" isExecutable=\"true\" camunda:historyTimeToLive=\"5\">"
      + "<startEvent id=\"start\" />"
      + "<sequenceFlow id=\"flow1\" sourceRef=\"start\" targetRef=\"shellTask\" />"
      + "<serviceTask id=\"shellTask\" camunda:type=\"shell\">"
      + "<extensionElements>"
      + "<camunda:field name=\"command\" stringValue=\"echo\" />"
      + "<camunda:field name=\"wait\" stringValue=\"true\" />"
      + "</extensionElements>"
      + "</serviceTask>"
      + "<sequenceFlow id=\"flow2\" sourceRef=\"shellTask\" targetRef=\"end\" />"
      + "<endEvent id=\"end\" />"
      + "</process>"
      + "</definitions>";

  protected static final String MAIL_PROCESS =
      "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
      + " xmlns:camunda=\"http://camunda.org/schema/1.0/bpmn\" targetNamespace=\"Examples\">"
      + "<process id=\"mailProcess\" isExecutable=\"true\" camunda:historyTimeToLive=\"5\">"
      + "<startEvent id=\"start\" />"
      + "<sequenceFlow id=\"flow1\" sourceRef=\"start\" targetRef=\"mailTask\" />"
      + "<serviceTask id=\"mailTask\" camunda:type=\"mail\">"
      + "<extensionElements>"
      + "<camunda:field name=\"to\" stringValue=\"someone@example.org\" />"
      + "<camunda:field name=\"text\" stringValue=\"hello\" />"
      + "</extensionElements>"
      + "</serviceTask>"
      + "<sequenceFlow id=\"flow2\" sourceRef=\"mailTask\" targetRef=\"end\" />"
      + "<endEvent id=\"end\" />"
      + "</process>"
      + "</definitions>";

  protected static final String GROOVY_DECISION =
      "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\""
      + " id=\"definitions\" name=\"definitions\" namespace=\"http://camunda.org/schema/1.0/dmn\">"
      + "<decision id=\"groovyDecision\" name=\"Groovy Decision\">"
      + "<decisionTable id=\"decisionTable\">"
      + "<input id=\"input1\">"
      + "<inputExpression id=\"inputExpression1\" typeRef=\"string\" expressionLanguage=\"groovy\">"
      + "<text>status</text>"
      + "</inputExpression>"
      + "</input>"
      + "<output id=\"output1\" name=\"result\" typeRef=\"string\" />"
      + "<rule id=\"rule1\">"
      + "<inputEntry id=\"inputEntry1\"><text>\"gold\"</text></inputEntry>"
      + "<outputEntry id=\"outputEntry1\"><text>\"ok\"</text></outputEntry>"
      + "</rule>"
      + "</decisionTable>"
      + "</decision>"
      + "</definitions>";

  protected static final String EXPLICIT_JUEL_FEEL_DECISION =
      "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\""
      + " id=\"definitions\" name=\"definitions\" namespace=\"http://camunda.org/schema/1.0/dmn\">"
      + "<decision id=\"explicitDecision\" name=\"Explicit Decision\">"
      + "<decisionTable id=\"decisionTable\">"
      + "<input id=\"input1\">"
      + "<inputExpression id=\"inputExpression1\" typeRef=\"string\" expressionLanguage=\"juel\">"
      + "<text>${status}</text>"
      + "</inputExpression>"
      + "</input>"
      + "<output id=\"output1\" name=\"result\" typeRef=\"string\" />"
      + "<rule id=\"rule1\">"
      + "<inputEntry id=\"inputEntry1\" expressionLanguage=\"feel\"><text>\"gold\"</text></inputEntry>"
      + "<outputEntry id=\"outputEntry1\" expressionLanguage=\"juel\"><text>${'ok'}</text></outputEntry>"
      + "</rule>"
      + "</decisionTable>"
      + "</decision>"
      + "</definitions>";

  protected static final BpmnModelInstance EXTERNAL_TASK_PROCESS = Bpmn.createExecutableProcess("externalTaskProcess")
      .camundaHistoryTimeToLive(5)
      .startEvent()
      .serviceTask("externalTask").camundaExternalTask("topic")
      .endEvent()
      .done();

  protected static final BpmnModelInstance GROOVY_SCRIPT_PROCESS = Bpmn.createExecutableProcess("groovyScriptProcess")
      .camundaHistoryTimeToLive(5)
      .startEvent()
      .scriptTask("script").scriptFormat("groovy").scriptText("execution.setVariable('scriptRan', true)")
      .userTask("wait")
      .endEvent()
      .done();

  @RegisterExtension
  @Order(1) protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  @Order(2) protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);
  @RegisterExtension
  @Order(3) protected ProcessEngineLoggingRule loggingRule = new ProcessEngineLoggingRule()
      .watch("org.cibseven.bpm.engine.security", Level.WARN);

  protected ProcessEngineConfigurationImpl configuration;
  /** An engine with another expressionHardeningMode, which is fixed once an engine is built (CIB7-2652). */
  protected ProcessEngine modeEngine;

  @BeforeEach
  public void setUp() {
    configuration = engineRule.getProcessEngineConfiguration();
  }

  @AfterEach
  public void tearDown() {
    configuration.setExecutionAllowList(null);
    // the mode of the built engine is enforce, setting it again is no change
    configuration.setExpressionHardeningMode(ProcessEngineConfigurationImpl.EXPRESSION_HARDENING_MODE_ENFORCE);
    if (modeEngine != null) {
      for (Deployment deployment : modeEngine.getRepositoryService().createDeploymentQuery().list()) {
        modeEngine.getRepositoryService().deleteDeployment(deployment.getId(), true);
      }
      modeEngine.close();
      modeEngine = null;
    }
  }

  // configuration

  @Test
  public void shouldBeOffByDefault() {
    ProcessEngineConfigurationImpl defaults = new StandaloneInMemProcessEngineConfiguration();

    assertThat(defaults.getExecutionAllowList()).isEmpty();
    assertThat(defaults.getExpressionHardeningMode())
        .isEqualTo(ProcessEngineConfigurationImpl.EXPRESSION_HARDENING_MODE_ENFORCE);
  }

  @Test
  public void shouldParseCommaSeparatedListIgnoringCaseAndBlanks() {
    configuration.setExecutionAllowList(" External, MAIL ,,groovy ");

    assertThat(configuration.getExecutionAllowList()).containsExactlyInAnyOrder("external", "mail", "groovy");
  }

  @Test
  public void shouldTreatEmptyStringAsNoCheck() {
    configuration.setExecutionAllowList(" , ");

    assertThat(configuration.getExecutionAllowList()).isEmpty();
  }

  @Test
  public void shouldRejectUnknownHardeningMode() {
    assertThatThrownBy(() -> configuration.setExpressionHardeningMode("block"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Invalid expressionHardeningMode 'block'");
  }

  @Test
  public void shouldBeConfigurableFromConfigurationResource() {
    ProcessEngineConfigurationImpl fromResource = (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
        .createProcessEngineConfigurationFromResource("org/cibseven/bpm/engine/test/api/cfg/execution-allow-list.camunda.cfg.xml");

    assertThat(fromResource.getExecutionAllowList()).containsExactlyInAnyOrder("external", "groovy");
    assertThat(fromResource.getExpressionHardeningMode())
        .isEqualTo(ProcessEngineConfigurationImpl.EXPRESSION_HARDENING_MODE_REPORT);
  }

  @Test
  public void shouldReportInvalidEntryAsWritten() {
    assertThatThrownBy(() -> configuration.setExecutionAllowList("External, -Shell"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Invalid executionAllowList entry '-Shell'");
  }

  @Test
  public void shouldUseDefaultHardeningModeForEmptyValue() {
    // not built: the mode is fixed once the engine is built (CIB7-2652)
    ProcessEngineConfigurationImpl configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration.setExpressionHardeningMode(ProcessEngineConfigurationImpl.EXPRESSION_HARDENING_MODE_OFF);
    configuration.setExpressionHardeningMode(" ");
    assertThat(configuration.getExpressionHardeningMode())
        .isEqualTo(ProcessEngineConfigurationImpl.EXPRESSION_HARDENING_MODE_ENFORCE);

    configuration.setExpressionHardeningMode(ProcessEngineConfigurationImpl.EXPRESSION_HARDENING_MODE_OFF);
    configuration.setExpressionHardeningMode(null);
    assertThat(configuration.getExpressionHardeningMode())
        .isEqualTo(ProcessEngineConfigurationImpl.EXPRESSION_HARDENING_MODE_ENFORCE);
  }

  @Test
  public void shouldRejectDenyListSyntax() {
    // "-shell" would switch the check on and allow nothing but the name "-shell"
    assertThatThrownBy(() -> configuration.setExecutionAllowList("external, -shell"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Invalid executionAllowList entry '-shell'");

    assertThat(configuration.getExecutionAllowList()).isEmpty();
  }

  @Test
  public void shouldRejectWildcards() {
    assertThatThrownBy(() -> configuration.setExecutionAllowList("java*"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Invalid executionAllowList entry 'java*'");
  }

  @Test
  public void shouldAcceptHardeningModeIgnoringCase() {
    // not built: the mode is fixed once the engine is built (CIB7-2652)
    ProcessEngineConfigurationImpl configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration.setExpressionHardeningMode(" Report ");

    assertThat(configuration.getExpressionHardeningMode())
        .isEqualTo(ProcessEngineConfigurationImpl.EXPRESSION_HARDENING_MODE_REPORT);
  }

  // empty list: no check

  @Test
  public void shouldAllowEverythingWhenListIsEmpty() {
    deployString("shell.bpmn", SHELL_PROCESS);
    deployString("mail.bpmn", MAIL_PROCESS);
    testRule.deploy(EXTERNAL_TASK_PROCESS, GROOVY_SCRIPT_PROCESS);

    engineRule.getRuntimeService().startProcessInstanceByKey("externalTaskProcess");
    ProcessInstance instance = engineRule.getRuntimeService().startProcessInstanceByKey("groovyScriptProcess");

    assertThat(engineRule.getExternalTaskService().createExternalTaskQuery().count()).isEqualTo(1);
    assertThat(engineRule.getRuntimeService().getVariable(instance.getId(), "scriptRan")).isEqualTo(true);
  }

  // task types at deployment

  @Test
  public void shouldRejectDeploymentOfShellTaskNotInList() {
    configuration.setExecutionAllowList("external, mail, juel, groovy, javascript, feel");

    assertThatThrownBy(() -> deployString("shell.bpmn", SHELL_PROCESS))
        .isInstanceOf(ParseException.class)
        .hasMessageContaining("ENGINE-25003")
        .hasMessageContaining("The task type 'shell' of activity 'shellTask' is not allowed by the executionAllowList");
  }

  @Test
  public void shouldRejectDeploymentOfMailTaskNotInList() {
    configuration.setExecutionAllowList("external");

    assertThatThrownBy(() -> deployString("mail.bpmn", MAIL_PROCESS))
        .isInstanceOf(ParseException.class)
        .hasMessageContaining("The task type 'mail' of activity 'mailTask' is not allowed by the executionAllowList");
  }

  @Test
  public void shouldRejectDeploymentOfExternalTaskNotInList() {
    configuration.setExecutionAllowList("mail");

    assertThatThrownBy(() -> testRule.deploy(EXTERNAL_TASK_PROCESS))
        .isInstanceOf(ParseException.class)
        .hasMessageContaining("The task type 'external' of activity 'externalTask' is not allowed by the executionAllowList");
  }

  @Test
  public void shouldRejectTaskTypeOnEveryServiceTaskLikeElement() {
    configuration.setExecutionAllowList("mail");

    BpmnModelInstance sendTask = Bpmn.createExecutableProcess("sendTaskProcess").camundaHistoryTimeToLive(5)
        .startEvent().sendTask("sendTask").camundaType("external").camundaTopic("topic").endEvent().done();
    BpmnModelInstance businessRuleTask = Bpmn.createExecutableProcess("businessRuleTaskProcess").camundaHistoryTimeToLive(5)
        .startEvent().businessRuleTask("businessRuleTask").camundaType("external").camundaTopic("topic").endEvent().done();
    BpmnModelInstance messageThrowEvent = Bpmn.createExecutableProcess("messageThrowProcess").camundaHistoryTimeToLive(5)
        .startEvent().intermediateThrowEvent("messageThrowEvent")
        .messageEventDefinition().camundaType("external").camundaTopic("topic").messageEventDefinitionDone()
        .endEvent().done();
    BpmnModelInstance messageEndEvent = Bpmn.createExecutableProcess("messageEndProcess").camundaHistoryTimeToLive(5)
        .startEvent().endEvent("messageEndEvent")
        .messageEventDefinition().camundaType("external").camundaTopic("topic").done();

    assertThatThrownBy(() -> testRule.deploy(sendTask))
        .isInstanceOf(ParseException.class).hasMessageContaining("of activity 'sendTask' is not allowed by the executionAllowList");
    assertThatThrownBy(() -> testRule.deploy(businessRuleTask))
        .isInstanceOf(ParseException.class).hasMessageContaining("of activity 'businessRuleTask' is not allowed by the executionAllowList");
    assertThatThrownBy(() -> testRule.deploy(messageThrowEvent))
        .isInstanceOf(ParseException.class).hasMessageContaining("of activity 'messageThrowEvent' is not allowed by the executionAllowList");
    assertThatThrownBy(() -> testRule.deploy(messageEndEvent))
        .isInstanceOf(ParseException.class).hasMessageContaining("of activity 'messageEndEvent' is not allowed by the executionAllowList");
  }

  @Test
  public void shouldRejectRedeploymentOfExistingDeployment() {
    String deploymentId = deployString("shell.bpmn", SHELL_PROCESS).getId();
    configuration.setExecutionAllowList("external");

    // a redeployment is a new deployment: checked like any other
    assertThatThrownBy(() -> testRule.deploy(engineRule.getRepositoryService().createDeployment()
        .addDeploymentResources(deploymentId)))
        .isInstanceOf(ParseException.class)
        .hasMessageContaining("The task type 'shell' of activity 'shellTask' is not allowed by the executionAllowList");
  }

  @Test
  public void shouldAllowDeploymentOfListedTaskTypes() {
    configuration.setExecutionAllowList("SHELL, Mail, external");

    deployString("shell.bpmn", SHELL_PROCESS);
    deployString("mail.bpmn", MAIL_PROCESS);
    testRule.deploy(EXTERNAL_TASK_PROCESS);

    assertThat(engineRule.getRepositoryService().createProcessDefinitionQuery().count()).isEqualTo(3);
  }

  // task types at execution: deployments made before the list was set

  @Test
  public void shouldBlockShellTaskOfExistingDeploymentAtExecution() {
    deployString("shell.bpmn", SHELL_PROCESS);
    configuration.setExecutionAllowList("external, mail");

    assertThatThrownBy(() -> engineRule.getRuntimeService().startProcessInstanceByKey("shellProcess"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("ENGINE-25003")
        .hasMessageContaining("The task type 'shell' of activity 'shellTask' is not allowed by the executionAllowList");
    assertThat(engineRule.getRuntimeService().createProcessInstanceQuery().count()).isZero();
  }

  @Test
  public void shouldBlockMailTaskOfExistingDeploymentAtExecution() {
    deployString("mail.bpmn", MAIL_PROCESS);
    configuration.setExecutionAllowList("external");

    assertThatThrownBy(() -> engineRule.getRuntimeService().startProcessInstanceByKey("mailProcess"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("The task type 'mail' of activity 'mailTask' is not allowed by the executionAllowList");
  }

  @Test
  public void shouldBlockExternalTaskOfExistingDeploymentAtExecution() {
    testRule.deploy(EXTERNAL_TASK_PROCESS);
    configuration.setExecutionAllowList("mail");

    assertThatThrownBy(() -> engineRule.getRuntimeService().startProcessInstanceByKey("externalTaskProcess"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("The task type 'external' of activity 'externalTask' is not allowed by the executionAllowList");
    assertThat(engineRule.getExternalTaskService().createExternalTaskQuery().count()).isZero();
  }

  @Test
  public void shouldNotExposeAllowListInMessages() {
    configuration.setExecutionAllowList("external, groovy");

    assertThatThrownBy(() -> deployString("shell.bpmn", SHELL_PROCESS))
        .isInstanceOf(ParseException.class)
        .hasMessageContaining("The task type 'shell' of activity 'shellTask' is not allowed by the executionAllowList")
        .hasMessageNotContaining("external")
        .hasMessageNotContaining("groovy");

    configuration.setExecutionAllowList("external");
    testRule.deploy(GROOVY_SCRIPT_PROCESS);
    assertThatThrownBy(() -> engineRule.getRuntimeService().startProcessInstanceByKey("groovyScriptProcess"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("The script language 'groovy' is not allowed by the executionAllowList")
        .hasMessageNotContaining("external");
  }

  @Test
  public void shouldKeepExistingDeploymentReadableAfterCacheEviction() {
    String processDefinitionId = deployString("shell.bpmn", SHELL_PROCESS)
        .getDeployedProcessDefinitions().get(0).getId();
    configuration.setExecutionAllowList("external");
    configuration.getDeploymentCache().discardProcessDefinitionCache();

    // parsed again from the existing deployment: not rejected
    assertThat(engineRule.getRepositoryService().getBpmnModelInstance(processDefinitionId)).isNotNull();
    assertThat(engineRule.getRepositoryService().getProcessDefinition(processDefinitionId)).isNotNull();
  }

  @Test
  public void shouldRunListedExternalTask() {
    configuration.setExecutionAllowList("external");
    testRule.deploy(EXTERNAL_TASK_PROCESS);

    engineRule.getRuntimeService().startProcessInstanceByKey("externalTaskProcess");

    assertThat(engineRule.getExternalTaskService().createExternalTaskQuery().count()).isEqualTo(1);
  }

  // script languages

  @Test
  public void shouldBlockScriptLanguageNotInList() {
    configuration.setExecutionAllowList("external, juel");
    testRule.deploy(GROOVY_SCRIPT_PROCESS);

    assertThatThrownBy(() -> engineRule.getRuntimeService().startProcessInstanceByKey("groovyScriptProcess"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("ENGINE-25003")
        .hasMessageContaining("The script language 'groovy' is not allowed by the executionAllowList");
  }

  @Test
  public void shouldBlockScriptLanguageInProcessApplication() {
    // the process application path resolves the engine without the ScriptEngineResolver;
    // the check must still apply there
    configuration.setExecutionAllowList("external");
    EmbeddedProcessApplication processApplication = new EmbeddedProcessApplication();

    assertThatThrownBy(() -> getScriptEngineWithin(processApplication, "groovy"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("ENGINE-25003")
        .hasMessageContaining("The script language 'groovy' is not allowed by the executionAllowList");
  }

  @Test
  public void shouldResolveListedScriptLanguageFromProcessApplication() {
    configuration.setExecutionAllowList("groovy");
    EmbeddedProcessApplication processApplication = new EmbeddedProcessApplication();

    ScriptEngine engine = getScriptEngineWithin(processApplication, "groovy");

    // the engine comes from the process application, so the check above ran on that path
    assertThat(engine).isSameAs(processApplication.getScriptEngineForName("groovy", true));
  }

  @Test
  public void shouldBlockScriptOfProcessApplicationDeploymentAtExecution() {
    EmbeddedProcessApplication processApplication = new EmbeddedProcessApplication();
    testRule.deploy(engineRule.getRepositoryService().createDeployment(processApplication.getReference())
        .addModelInstance("groovy.bpmn", GROOVY_SCRIPT_PROCESS));
    configuration.setExecutionAllowList("external");

    assertThatThrownBy(() -> engineRule.getRuntimeService().startProcessInstanceByKey("groovyScriptProcess"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("The script language 'groovy' is not allowed by the executionAllowList");
  }

  @Test
  public void shouldRunListedScriptLanguage() {
    configuration.setExecutionAllowList("Groovy");
    testRule.deploy(GROOVY_SCRIPT_PROCESS);

    ProcessInstance instance = engineRule.getRuntimeService().startProcessInstanceByKey("groovyScriptProcess");

    assertThat(engineRule.getRuntimeService().getVariable(instance.getId(), "scriptRan")).isEqualTo(true);
  }

  @Test
  public void shouldAlwaysAllowDmnDefaultExpressionLanguages() {
    // juel and feel are the DMN defaults; they are not resolved via the script engines
    configuration.setExecutionAllowList("external");
    testRule.deploy("org/cibseven/bpm/engine/test/api/dmn/Example.dmn");

    DmnDecisionTableResult result = engineRule.getDecisionService().evaluateDecisionTableByKey("decision",
        Variables.createVariables().putValue("status", "bronze").putValue("sum", 1000));

    assertThat(result.getSingleResult().getEntryMap()).containsEntry("result", "notok");
  }

  @Test
  public void shouldAlwaysAllowExplicitJuelAndFeelInDmn() {
    // juel and feel in DMN are evaluated via EL and FEEL, not via the script engines:
    // always allowed, also when the DMN states them explicitly
    configuration.setExecutionAllowList("external");
    deployString("explicit.dmn", EXPLICIT_JUEL_FEEL_DECISION);

    DmnDecisionTableResult result = engineRule.getDecisionService().evaluateDecisionTableByKey("explicitDecision",
        Variables.createVariables().putValue("status", "gold"));

    assertThat(result.getSingleResult().getEntryMap()).containsEntry("result", "ok");
  }

  @Test
  public void shouldBlockScriptLanguageExplicitlySpecifiedInDmn() {
    configuration.setExecutionAllowList("external");
    deployString("groovy.dmn", GROOVY_DECISION);
    Map<String, Object> variables = Variables.createVariables().putValue("status", "gold");

    assertThatThrownBy(() -> engineRule.getDecisionService().evaluateDecisionTableByKey("groovyDecision", variables))
        // the DMN engine wraps every evaluation error; the allow-list violation is the root cause
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Exception while evaluating decision with key 'groovyDecision'")
        .rootCause()
        .hasMessageContaining("ENGINE-25003")
        .hasMessageContaining("The script language 'groovy' is not allowed by the executionAllowList");
  }

  // expressionHardeningMode

  @Test
  public void shouldOnlyReportViolationsInReportMode() {
    ProcessEngine engine = buildEngine(ProcessEngineConfigurationImpl.EXPRESSION_HARDENING_MODE_REPORT);
    ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration()).setExecutionAllowList("external");

    engine.getRepositoryService().createDeployment().addString("mail.bpmn", MAIL_PROCESS).deploy();
    engine.getRepositoryService().createDeployment().addModelInstance("groovy.bpmn", GROOVY_SCRIPT_PROCESS).deploy();
    ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey("groovyScriptProcess");

    assertThat(engine.getRuntimeService().getVariable(instance.getId(), "scriptRan")).isEqualTo(true);
    assertThat(loggingRule.getFilteredLog(
        "ENGINE-25004 The task type 'mail' of activity 'mailTask' is not in the executionAllowList")).hasSize(1);
    assertThat(loggingRule.getFilteredLog(
        "ENGINE-25004 The script language 'groovy' is not in the executionAllowList")).hasSize(1);
  }

  @Test
  public void shouldOnlyReportViolationOfExistingDeploymentAtExecution() {
    ProcessEngine engine = buildEngine(ProcessEngineConfigurationImpl.EXPRESSION_HARDENING_MODE_REPORT);
    engine.getRepositoryService().createDeployment().addModelInstance("external.bpmn", EXTERNAL_TASK_PROCESS).deploy();
    ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration()).setExecutionAllowList("mail");

    engine.getRuntimeService().startProcessInstanceByKey("externalTaskProcess");

    assertThat(engine.getExternalTaskService().createExternalTaskQuery().count()).isEqualTo(1);
    assertThat(loggingRule.getFilteredLog(
        "ENGINE-25004 The task type 'external' of activity 'externalTask' is not in the executionAllowList")).hasSize(1);
  }

  @Test
  public void shouldNotLogWhenAllowed() {
    configuration.setExecutionAllowList("groovy");
    testRule.deploy(GROOVY_SCRIPT_PROCESS);

    engineRule.getRuntimeService().startProcessInstanceByKey("groovyScriptProcess");

    assertThat(loggingRule.getFilteredLog("ENGINE-25003")).isEmpty();
    assertThat(loggingRule.getFilteredLog("ENGINE-25004")).isEmpty();
  }

  @Test
  public void shouldNotCheckInOffMode() {
    ProcessEngine engine = buildEngine(ProcessEngineConfigurationImpl.EXPRESSION_HARDENING_MODE_OFF);
    ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration()).setExecutionAllowList("external");

    engine.getRepositoryService().createDeployment().addString("shell.bpmn", SHELL_PROCESS).deploy();
    engine.getRepositoryService().createDeployment().addModelInstance("groovy.bpmn", GROOVY_SCRIPT_PROCESS).deploy();
    ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey("groovyScriptProcess");

    assertThat(engine.getRuntimeService().getVariable(instance.getId(), "scriptRan")).isEqualTo(true);
    assertThat(loggingRule.getFilteredLog("ENGINE-25003")).isEmpty();
    assertThat(loggingRule.getFilteredLog("ENGINE-25004")).isEmpty();
  }

  protected ScriptEngine getScriptEngineWithin(ProcessApplicationInterface processApplication, String language) {
    return configuration.getCommandExecutorTxRequired().execute(commandContext ->
        Context.executeWithinProcessApplication(
            () -> configuration.getScriptingEngines().getScriptEngineForLanguage(language),
            processApplication.getReference()));
  }

  /**
   * An engine in the given mode, without allow list: the mode is fixed once an engine is built (CIB7-2652),
   * the allow list can still be changed.
   */
  protected ProcessEngine buildEngine(String expressionHardeningMode) {
    ProcessEngineConfigurationImpl modeConfiguration = (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
        .createProcessEngineConfigurationFromResource("org/cibseven/bpm/engine/test/api/cfg/execution-allow-list.camunda.cfg.xml");
    modeConfiguration.setExecutionAllowList(null);
    modeConfiguration.setExpressionHardeningMode(expressionHardeningMode);
    modeConfiguration.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_CREATE_DROP);
    modeEngine = modeConfiguration.buildProcessEngine();
    return modeEngine;
  }

  protected DeploymentWithDefinitions deployString(String resourceName, String content) {
    DeploymentBuilder builder = engineRule.getRepositoryService().createDeployment().addString(resourceName, content);
    return testRule.deploy(builder);
  }

}
