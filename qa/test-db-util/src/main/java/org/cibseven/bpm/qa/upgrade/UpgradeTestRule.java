/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
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
package org.cibseven.bpm.qa.upgrade;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.cibseven.bpm.engine.AuthorizationService;
import org.cibseven.bpm.engine.CaseService;
import org.cibseven.bpm.engine.DecisionService;
import org.cibseven.bpm.engine.ExternalTaskService;
import org.cibseven.bpm.engine.FilterService;
import org.cibseven.bpm.engine.FormService;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.history.HistoricIncidentQuery;
import org.cibseven.bpm.engine.history.HistoricProcessInstance;
import org.cibseven.bpm.engine.impl.ProcessEngineImpl;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.test.TestHelper;
import org.cibseven.bpm.engine.management.JobDefinitionQuery;
import org.cibseven.bpm.engine.runtime.CaseExecutionQuery;
import org.cibseven.bpm.engine.runtime.CaseInstance;
import org.cibseven.bpm.engine.runtime.CaseInstanceQuery;
import org.cibseven.bpm.engine.runtime.ExecutionQuery;
import org.cibseven.bpm.engine.runtime.IncidentQuery;
import org.cibseven.bpm.engine.runtime.JobQuery;
import org.cibseven.bpm.engine.runtime.MessageCorrelationBuilder;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.runtime.ProcessInstanceQuery;
import org.cibseven.bpm.engine.task.TaskQuery;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 extension for the upgrade/rolling-update test suites.
 *
 * <p>Deliberately does NOT extend the engine's {@code ProcessEngineRule}: this
 * module is compiled against the oldest supported engine (1.1.0, see the BOM
 * import in the pom), whose rule is still JUnit-4-based. Instead the engine is
 * bootstrapped here via {@link TestHelper} using only APIs that exist in every
 * engine version, so the compiled class runs against old and current engines
 * alike while the JUnit 5 wiring comes from this module's own dependency.</p>
 *
 * @author Thorben Lindhauer
 */
public class UpgradeTestRule implements BeforeEachCallback {

  protected String configurationResource;

  protected ProcessEngine processEngine;
  protected ProcessEngineConfigurationImpl processEngineConfiguration;
  protected RepositoryService repositoryService;
  protected RuntimeService runtimeService;
  protected TaskService taskService;
  protected HistoryService historyService;
  protected IdentityService identityService;
  protected ManagementService managementService;
  protected FormService formService;
  protected FilterService filterService;
  protected AuthorizationService authorizationService;
  protected CaseService caseService;
  protected ExternalTaskService externalTaskService;
  protected DecisionService decisionService;

  protected String scenarioTestedByClass = null;
  protected String scenarioName;
  protected String tag;

  public UpgradeTestRule() {
    this("camunda.cfg.xml");
  }

  public UpgradeTestRule(String configurationResource) {
    this.configurationResource = configurationResource;
  }

  protected void initializeProcessEngine() {
    if (processEngine == null) {
      processEngine = TestHelper.getProcessEngine(configurationResource);
      processEngineConfiguration = ((ProcessEngineImpl) processEngine).getProcessEngineConfiguration();
      repositoryService = processEngine.getRepositoryService();
      runtimeService = processEngine.getRuntimeService();
      taskService = processEngine.getTaskService();
      historyService = processEngine.getHistoryService();
      identityService = processEngine.getIdentityService();
      managementService = processEngine.getManagementService();
      formService = processEngine.getFormService();
      filterService = processEngine.getFilterService();
      authorizationService = processEngine.getAuthorizationService();
      caseService = processEngine.getCaseService();
      externalTaskService = processEngine.getExternalTaskService();
      decisionService = processEngine.getDecisionService();
    }
  }

  @Override
  public void beforeEach(ExtensionContext context) throws Exception {
    Class<?> testClass = context.getTestClass()
        .orElseThrow(() -> new IllegalStateException("testClass not set"));
    Method testMethod = context.getTestMethod().orElse(null);

    if (scenarioTestedByClass == null) {
      ScenarioUnderTest testScenarioClassAnnotation = testClass.getAnnotation(ScenarioUnderTest.class);
      if (testScenarioClassAnnotation != null) {
        scenarioTestedByClass = testScenarioClassAnnotation.value();
      }
    }

    ScenarioUnderTest testScenarioAnnotation = testMethod != null ? testMethod.getAnnotation(ScenarioUnderTest.class) : null;
    if (testScenarioAnnotation != null) {
      if (scenarioTestedByClass != null) {
        scenarioName = scenarioTestedByClass + "." + testScenarioAnnotation.value();
      } else {
        scenarioName = testScenarioAnnotation.value();
      }
    }

    // method annotation overrides class annotation
    Origin originAnnotation = testMethod != null ? testMethod.getAnnotation(Origin.class) : null;
    if (originAnnotation == null) {
      originAnnotation = testClass.getAnnotation(Origin.class);
    }

    if (originAnnotation != null) {
      tag = originAnnotation.value();
    }

    if (scenarioName == null) {
      throw new RuntimeException("Could not determine scenario under test for test " + context.getDisplayName());
    }

    initializeProcessEngine();
  }

  // getters used by the upgrade/rolling-update test suites ////////

  public ProcessEngine getProcessEngine() {
    return processEngine;
  }

  public ProcessEngineConfigurationImpl getProcessEngineConfiguration() {
    return processEngineConfiguration;
  }

  public RepositoryService getRepositoryService() {
    return repositoryService;
  }

  public RuntimeService getRuntimeService() {
    return runtimeService;
  }

  public TaskService getTaskService() {
    return taskService;
  }

  public HistoryService getHistoryService() {
    return historyService;
  }

  public IdentityService getIdentityService() {
    return identityService;
  }

  public ManagementService getManagementService() {
    return managementService;
  }

  public FormService getFormService() {
    return formService;
  }

  public FilterService getFilterService() {
    return filterService;
  }

  public AuthorizationService getAuthorizationService() {
    return authorizationService;
  }

  public CaseService getCaseService() {
    return caseService;
  }

  public ExternalTaskService getExternalTaskService() {
    return externalTaskService;
  }

  public DecisionService getDecisionService() {
    return decisionService;
  }

  public TaskQuery taskQuery() {
    return taskService.createTaskQuery().processInstanceBusinessKey(getBuisnessKey());
  }

  public ExecutionQuery executionQuery() {
    return runtimeService.createExecutionQuery().processInstanceBusinessKey(getBuisnessKey());
  }

  public JobQuery jobQuery() {
    ProcessInstance instance = processInstance();
    return managementService.createJobQuery().processInstanceId(instance.getId());
  }

  public JobDefinitionQuery jobDefinitionQuery() {
    ProcessInstance instance = processInstance();
    return managementService.createJobDefinitionQuery()
            .processDefinitionId(instance.getProcessDefinitionId());
  }

  public IncidentQuery incidentQuery() {
    ProcessInstance processInstance = processInstance();
    return runtimeService.createIncidentQuery()
        .processInstanceId(processInstance.getId());
  }

  public ProcessInstanceQuery processInstanceQuery() {
    return runtimeService
            .createProcessInstanceQuery()
            .processInstanceBusinessKey(getBuisnessKey());
  }

  public ProcessInstance processInstance() {
    ProcessInstance instance = processInstanceQuery().singleResult();

    if (instance == null) {
      throw new RuntimeException("There is no process instance for scenario " + getBuisnessKey());
    }

    return instance;
  }

  public HistoricProcessInstance historicProcessInstance() {
    HistoricProcessInstance historicProcessInstance = historyService
            .createHistoricProcessInstanceQuery()
            .processInstanceBusinessKey(getBuisnessKey())
            .singleResult();

    if (historicProcessInstance == null) {
      throw new RuntimeException("There is no historic process instance for scenario " + getBuisnessKey());
    }

    return historicProcessInstance;
  }

  public HistoricIncidentQuery historicIncidentQuery() {
    ProcessInstance processInstance = processInstance();
    return historyService.createHistoricIncidentQuery()
        .processInstanceId(processInstance.getId());
  }

  public MessageCorrelationBuilder messageCorrelation(String messageName) {
    return runtimeService.createMessageCorrelation(messageName).processInstanceBusinessKey(getBuisnessKey());
  }

  public void assertScenarioEnded() {
    assertTrue(processInstanceQuery().singleResult() == null,
            "Process instance for scenario " + getBuisnessKey() + " should have ended");
  }

  // case //////////////////////////////////////////////////
  public CaseInstanceQuery caseInstanceQuery() {
    return caseService
            .createCaseInstanceQuery()
            .caseInstanceBusinessKey(getBuisnessKey());
  }

  public CaseExecutionQuery caseExecutionQuery() {
    return caseService
            .createCaseExecutionQuery()
            .caseInstanceBusinessKey(getBuisnessKey());
  }

  public CaseInstance caseInstance() {
    CaseInstance instance = caseInstanceQuery().singleResult();

    if (instance == null) {
      throw new RuntimeException("There is no case instance for scenario " + getBuisnessKey());
    }

    return instance;
  }

  public String getScenarioName() {
    return scenarioName;
  }

  public String getBuisnessKey() {
    if (tag != null) {
      return tag + '.' + scenarioName;
    }
    return scenarioName;
  }

  public String getTag() {
    return tag;
  }

  public void setTag(String tag) {
    this.tag = tag;
  }

}
