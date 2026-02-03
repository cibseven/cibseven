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
package org.cibseven.bpm.engine.test;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
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
import org.cibseven.bpm.engine.ProcessEngineServices;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.impl.ProcessEngineImpl;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.diagnostics.PlatformDiagnosticsRegistry;
 import org.cibseven.bpm.engine.impl.test.TestHelper;
import org.cibseven.bpm.engine.impl.util.ClockUtil;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 compatible ProcessEngineRule as a JUnit Jupiter extension.
 */
public class ProcessEngineRule implements BeforeEachCallback, AfterEachCallback, ProcessEngineServices {

  protected String configurationResource = "camunda.cfg.xml";
  protected String configurationResourceCompat = "activiti.cfg.xml";
  protected String deploymentId = null;
  protected List<String> additionalDeployments = new ArrayList<>();

  protected boolean ensureCleanAfterTest = false;

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

  public ProcessEngineRule() {
    this(false);
  }

  public ProcessEngineRule(boolean ensureCleanAfterTest) {
    this.ensureCleanAfterTest = ensureCleanAfterTest;
  }

  public ProcessEngineRule(String configurationResource) {
    this(configurationResource, false);
  }

  public ProcessEngineRule(String configurationResource, boolean ensureCleanAfterTest) {
    this.configurationResource = configurationResource;
    this.ensureCleanAfterTest = ensureCleanAfterTest;
  }

  public ProcessEngineRule(ProcessEngine processEngine) {
    this(processEngine, false);
  }

  public ProcessEngineRule(ProcessEngine processEngine, boolean ensureCleanAfterTest) {
    this.processEngine = processEngine;
    this.ensureCleanAfterTest = ensureCleanAfterTest;
  }

  @Override
  public void beforeEach(ExtensionContext context) throws Exception {
    if (processEngine == null) {
      initializeProcessEngine();
    }
    initializeServices();
    // Optionally handle deployment setup if needed for JUnit 5
    // Deployment annotation handling can be added here if required
  }

  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    identityService.clearAuthentication();
    processEngine.getProcessEngineConfiguration().setTenantCheckEnabled(true);
    // Optionally handle deployment teardown if needed for JUnit 5
    if (deploymentId != null) {
      TestHelper.annotationDeploymentTearDown(processEngine, deploymentId, context.getRequiredTestClass(), context.getRequiredTestMethod().getName());
      for (String additionalDeployment : additionalDeployments) {
        TestHelper.deleteDeployment(processEngine, additionalDeployment);
      }
    }
    if (ensureCleanAfterTest) {
      TestHelper.assertAndEnsureCleanDbAndCache(processEngine);
    }
    TestHelper.resetIdGenerator(processEngineConfiguration);
    ClockUtil.reset();
    clearServiceReferences();
    PlatformDiagnosticsRegistry.clear();
  }

  protected void initializeProcessEngine() {
    try {
      processEngine = TestHelper.getProcessEngine(configurationResource);
    } catch (RuntimeException ex) {
      if (ex.getCause() != null && ex.getCause() instanceof FileNotFoundException) {
        processEngine = TestHelper.getProcessEngine(configurationResourceCompat);
      } else {
        throw ex;
      }
    }
  }

  protected void initializeServices() {
    processEngineConfiguration = ((ProcessEngineImpl) processEngine).getProcessEngineConfiguration();
    repositoryService = processEngine.getRepositoryService();
    runtimeService = processEngine.getRuntimeService();
    taskService = processEngine.getTaskService();
    historyService = processEngine.getHistoryService();
    identityService = processEngine.getIdentityService();
    managementService = processEngine.getManagementService();
    formService = processEngine.getFormService();
    authorizationService = processEngine.getAuthorizationService();
    caseService = processEngine.getCaseService();
    filterService = processEngine.getFilterService();
    externalTaskService = processEngine.getExternalTaskService();
    decisionService = processEngine.getDecisionService();
  }

  protected void clearServiceReferences() {
    processEngineConfiguration = null;
    repositoryService = null;
    runtimeService = null;
    taskService = null;
    formService = null;
    historyService = null;
    identityService = null;
    managementService = null;
    authorizationService = null;
    caseService = null;
    filterService = null;
    externalTaskService = null;
    decisionService = null;
  }

  public void setCurrentTime(Date currentTime) {
    ClockUtil.setCurrentTime(currentTime);
  }

  public String getConfigurationResource() {
    return configurationResource;
  }

  public void setConfigurationResource(String configurationResource) {
    this.configurationResource = configurationResource;
  }

  public ProcessEngine getProcessEngine() {
    return processEngine;
  }

  public void setProcessEngine(ProcessEngine processEngine) {
    this.processEngine = processEngine;
  }

  public ProcessEngineConfigurationImpl getProcessEngineConfiguration() {
    return processEngineConfiguration;
  }

  public void setProcessEngineConfiguration(ProcessEngineConfigurationImpl processEngineConfiguration) {
    this.processEngineConfiguration = processEngineConfiguration;
  }

  @Override
  public RepositoryService getRepositoryService() {
    return repositoryService;
  }

  public void setRepositoryService(RepositoryService repositoryService) {
    this.repositoryService = repositoryService;
  }

  @Override
  public RuntimeService getRuntimeService() {
    return runtimeService;
  }

  public void setRuntimeService(RuntimeService runtimeService) {
    this.runtimeService = runtimeService;
  }

  @Override
  public TaskService getTaskService() {
    return taskService;
  }

  public void setTaskService(TaskService taskService) {
    this.taskService = taskService;
  }

  @Override
  public HistoryService getHistoryService() {
    return historyService;
  }

  public void setHistoryService(HistoryService historyService) {
    this.historyService = historyService;
  }

  /**
   * @see #setHistoryService(HistoryService)
   * @param historicService
   *          the historiy service instance
   */
  public void setHistoricDataService(HistoryService historicService) {
    this.setHistoryService(historicService);
  }

  @Override
  public IdentityService getIdentityService() {
    return identityService;
  }

  public void setIdentityService(IdentityService identityService) {
    this.identityService = identityService;
  }

  @Override
  public ManagementService getManagementService() {
    return managementService;
  }

  @Override
  public AuthorizationService getAuthorizationService() {
    return authorizationService;
  }

  public void setAuthorizationService(AuthorizationService authorizationService) {
    this.authorizationService = authorizationService;
  }

  @Override
  public CaseService getCaseService() {
    return caseService;
  }

  public void setCaseService(CaseService caseService) {
    this.caseService = caseService;
  }

  @Override
  public FormService getFormService() {
    return formService;
  }

  public void setFormService(FormService formService) {
    this.formService = formService;
  }

  public void setManagementService(ManagementService managementService) {
    this.managementService = managementService;
  }

  @Override
  public FilterService getFilterService() {
    return filterService;
  }

  public void setFilterService(FilterService filterService) {
    this.filterService = filterService;
  }

  @Override
  public ExternalTaskService getExternalTaskService() {
    return externalTaskService;
  }

  public void setExternalTaskService(ExternalTaskService externalTaskService) {
    this.externalTaskService = externalTaskService;
  }

  @Override
  public DecisionService getDecisionService() {
    return decisionService;
  }

  public void setDecisionService(DecisionService decisionService) {
    this.decisionService = decisionService;
  }

  public void manageDeployment(org.cibseven.bpm.engine.repository.Deployment deployment) {
    this.additionalDeployments.add(deployment.getId());
  }

}
