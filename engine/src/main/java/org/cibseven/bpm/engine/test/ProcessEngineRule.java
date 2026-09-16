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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

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
import org.cibseven.bpm.engine.impl.test.RequiredDatabase;
import org.cibseven.bpm.engine.impl.util.ClockUtil;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Convenience for ProcessEngine and services initialization in the form of a
 * JUnit Jupiter extension.
 * <p>
 * Usage:
 * </p>
 *
 * <pre>
 * public class YourTest {
 *
 *   &#64;RegisterExtension
 *   public ProcessEngineRule processEngineRule = new ProcessEngineRule();
 *
 *   ...
 * }
 * </pre>
 * <p>
 * The ProcessEngine and the services will be made available to the test class
 * through the getters of the processEngineRule. The processEngine will be
 * initialized by default with the camunda.cfg.xml resource on the classpath. To
 * specify a different configuration file, pass the resource location in
 * {@link #ProcessEngineRule(String) the appropriate constructor}. Process
 * engines will be cached statically. Right before the first time the setUp is
 * called for a given configuration resource, the process engine will be
 * constructed.
 * </p>
 * <p>
 * You can declare a deployment with the {@link Deployment} annotation. This
 * base class will make sure that this deployment gets deployed before the setUp
 * and {@link RepositoryService#deleteDeployment(String, boolean) cascade
 * deleted} after the tearDown. If you add a deployment programmatically in your
 * test, you have to make it known to the processEngineRule by calling
 * {@link ProcessEngineRule#manageDeployment(org.cibseven.bpm.engine.repository.Deployment)}
 * to have it cleaned up automatically.
 * </p>
 * <p>
 * The processEngineRule also lets you
 * {@link ProcessEngineRule#setCurrentTime(Date) set the current time used by
 * the process engine}. This can be handy to control the exact time that is used
 * by the engine in order to verify e.g., due dates of timers. Or start, end
 * and duration times in the history service. In the tearDown, the internal
 * clock will automatically be reset to use the current system time rather then
 * the time that was set during a test method. In other words, you don't have to
 * clean up your own time messing mess ;-)
 * </p>
 * <p>
 * If you need the history service for your tests then you can specify the
 * required history level of the test method or class, using the
 * {@link RequiredHistoryLevel} annotation. If the current history level of the
 * process engine is lower than the specified one then the test is skipped.
 * </p>
 *
 * @author Tom Baeyens
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

    if (!context.getTestMethod().isPresent() || !context.getTestClass().isPresent()) {
      // not a regular test method (e.g. class-level lifecycle callback) - nothing more to do
      return;
    }
    Method method = context.getTestMethod().get();
    Class<?> testClass = context.getTestClass().get();

    RequiredHistoryLevel reqHistoryLevel = context.getElement().map(
        element -> element.getAnnotation(RequiredHistoryLevel.class)).orElse(null);
    boolean hasRequiredHistoryLevel = TestHelper.annotationRequiredHistoryLevelCheck(processEngine,
        reqHistoryLevel, testClass, method.getName());

    RequiredDatabase requiredDatabase = method.getAnnotation(RequiredDatabase.class);
    boolean runsWithRequiredDatabase = TestHelper.annotationRequiredDatabaseCheck(processEngine,
    requiredDatabase, testClass, method.getName());
    assumeTrue(hasRequiredHistoryLevel, "ignored because the current history level is too low");
    assumeTrue(runsWithRequiredDatabase, "ignored because the database doesn't match the required ones");


    //from starting(Description description) method
    String methodName = method.getName();
    if (methodName != null) {
      // cut off method variant suffix "[variant name]" for parameterized tests
      int methodNameVariantStart = methodName.indexOf('[');
      int methodNameEnd = methodNameVariantStart < 0 ? methodName.length() : methodNameVariantStart;
      methodName = methodName.substring(0, methodNameEnd);
    }
    deploymentId = TestHelper.annotationDeploymentSetUp(processEngine, testClass, methodName, method.getParameterTypes());
  
  }

  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    identityService.clearAuthentication();
    processEngine.getProcessEngineConfiguration().setTenantCheckEnabled(true);
    TestHelper.annotationDeploymentTearDown(processEngine, deploymentId, context.getRequiredTestClass(), context.getRequiredTestMethod().getName());
    for (String additionalDeployment : additionalDeployments) {
      TestHelper.deleteDeployment(processEngine, additionalDeployment);
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
