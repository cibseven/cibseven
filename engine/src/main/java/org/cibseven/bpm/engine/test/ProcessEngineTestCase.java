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
import java.util.Date;

import org.cibseven.bpm.engine.AuthorizationService;
import org.cibseven.bpm.engine.CaseService;
import org.cibseven.bpm.engine.FilterService;
import org.cibseven.bpm.engine.FormService;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.impl.test.ProcessEngineAssert;
import org.cibseven.bpm.engine.impl.test.TestHelper;
import org.cibseven.bpm.engine.impl.util.ClockUtil;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.opentest4j.AssertionFailedError;


/** Convenience for ProcessEngine and services initialization in the form of a JUnit 5 base class
 * or a JUnit 5 extension.
 *
 * <p>Usage as base class:</p>
 * <pre>
 * public class YourTest extends ProcessEngineTestCase {
 *
 *   &#64;Test
 *   &#64;Deployment
 *   public void testSomething() {
 *     runtimeService.startProcessInstanceByKey("process");
 *   }
 * }
 * </pre>
 * <p>Test methods must be annotated with {@link org.junit.jupiter.api.Test @Test}. This class no
 * longer extends {@code junit.framework.TestCase}, so JUnit 3 style {@code public void testXxx()}
 * methods are not discovered anymore. The life cycle is driven by the {@link BeforeEach} method
 * {@link #setUpProcessEngineTestCase(TestInfo)} and the {@link AfterEach} method
 * {@link #tearDownProcessEngineTestCase(TestInfo)}; subclasses that override one of them must
 * annotate the override accordingly and call {@code super}. Own set up and tear down methods of
 * subclasses (annotated with {@link BeforeEach} / {@link AfterEach}) run after the process engine
 * set up and before the process engine tear down, respectively.</p>
 *
 * <p>Usage as extension: <code>&#64;RegisterExtension ProcessEngineTestCase extension = new ProcessEngineTestCase();</code>,
 * the services are then available through the getters, e.g. {@link #getRuntimeService()}.</p>
 *
 * <p>The ProcessEngine and the services available to subclasses through protected member fields.
 * The processEngine will be initialized by default with the camunda.cfg.xml resource
 * on the classpath.  To specify a different configuration file, override the
 * {@link #getConfigurationResource()} method.
 * Process engines will be cached statically.  The first time the set up is called for a given
 * configuration resource, the process engine will be constructed.</p>
 *
 * <p>You can declare a deployment with the {@link Deployment} annotation.
 * This base class will make sure that this deployment gets deployed in the
 * set up and {@link RepositoryService#deleteDeploymentCascade(String, boolean) cascade deleted}
 * in the tear down.
 * </p>
 *
 * <p>Test methods annotated with {@link RequiredHistoryLevel} are skipped (JUnit 5 assumption)
 * if the history level of the process engine is too low.</p>
 *
 * <p>This class also lets you {@link #setCurrentTime(Date) set the current time used by the
 * process engine}. This can be handy to control the exact time that is used by the engine
 * in order to verify e.g. e.g. due dates of timers.  Or start, end and duration times
 * in the history service.  In the tear down, the internal clock will automatically be
 * reset to use the current system time rather then the time that was set during
 * a test method.  In other words, you don't have to clean up your own time messing mess ;-)
 * </p>
 *
 * @author Tom Baeyens
 * @author Falko Menge (camunda)
 */
public class ProcessEngineTestCase implements BeforeEachCallback, AfterEachCallback {

  protected String configurationResource = "camunda.cfg.xml";
  protected String configurationResourceCompat = "activiti.cfg.xml";
  protected String deploymentId = null;

  protected ProcessEngine processEngine;
  protected RepositoryService repositoryService;
  protected RuntimeService runtimeService;
  protected TaskService taskService;
  @Deprecated protected HistoryService historicDataService;
  protected HistoryService historyService;
  protected IdentityService identityService;
  protected ManagementService managementService;
  protected FormService formService;
  protected FilterService filterService;
  protected AuthorizationService authorizationService;
  protected CaseService caseService;

  protected boolean skipTest = false;

  /** uses 'camunda.cfg.xml' as it's configuration resource */
  public ProcessEngineTestCase() {
  }

  public void assertProcessEnded(final String processInstanceId) {
    ProcessEngineAssert.assertProcessEnded(processEngine, processInstanceId);
  }

  /**
   * Set up if this class is registered as JUnit 5 extension (<code>&#64;RegisterExtension</code>).
   */
  @Override
  public void beforeEach(ExtensionContext context) throws Exception {
    doSetUp(context.getRequiredTestClass(), context.getRequiredTestMethod());
  }

  /**
   * Set up if this class is used as base class of a test class.
   */
  @BeforeEach
  public void setUpProcessEngineTestCase(TestInfo testInfo) {
    doSetUp(getClass(), testInfo.getTestMethod().orElseThrow(
        () -> new IllegalStateException("ProcessEngineTestCase supports test methods only")));
  }

  private void doSetUp(Class<?> testClass, Method testMethod) {
    if (processEngine==null) {
      initializeProcessEngine();
      initializeServices();
    }

    boolean hasRequiredHistoryLevel = TestHelper.annotationRequiredHistoryLevelCheck(processEngine, testClass, testMethod.getName(), testMethod.getParameterTypes());
    // ignore test case when current history level is too low
    skipTest = !hasRequiredHistoryLevel;
    Assumptions.assumeTrue(hasRequiredHistoryLevel, "ignored because the current history level is too low");

    deploymentId = TestHelper.annotationDeploymentSetUp(processEngine, testClass, testMethod.getName(), testMethod.getParameterTypes());
  }

  protected void initializeProcessEngine() {
    try {
      processEngine = TestHelper.getProcessEngine(getConfigurationResource());
    } catch (RuntimeException ex) {
      if (ex.getCause() != null && ex.getCause() instanceof FileNotFoundException) {
        processEngine = ProcessEngineConfiguration
            .createProcessEngineConfigurationFromResource(configurationResourceCompat)
            .buildProcessEngine();
      } else {
        throw ex;
      }
    }
  }

  protected void initializeServices() {
    repositoryService = processEngine.getRepositoryService();
    runtimeService = processEngine.getRuntimeService();
    taskService = processEngine.getTaskService();
    historicDataService = processEngine.getHistoryService();
    historyService = processEngine.getHistoryService();
    identityService = processEngine.getIdentityService();
    managementService = processEngine.getManagementService();
    formService = processEngine.getFormService();
    filterService = processEngine.getFilterService();
    authorizationService = processEngine.getAuthorizationService();
    caseService = processEngine.getCaseService();
  }

  /**
   * Tear down if this class is registered as JUnit 5 extension (<code>&#64;RegisterExtension</code>).
   */
  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    doTearDown(context.getRequiredTestClass(), context.getRequiredTestMethod().getName());
  }

  /**
   * Tear down if this class is used as base class of a test class.
   */
  @AfterEach
  public void tearDownProcessEngineTestCase(TestInfo testInfo) {
    doTearDown(getClass(), testInfo.getTestMethod().map(Method::getName).orElse(null));
  }

  private void doTearDown(Class<?> testClass, String testMethodName) {
    try {
      TestHelper.annotationDeploymentTearDown(processEngine, deploymentId, testClass, testMethodName);
    } finally {
      deploymentId = null;
      ClockUtil.reset();
    }
  }

  public static void closeProcessEngines() {
    TestHelper.closeProcessEngines();
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

  public String getConfigurationResourceCompat() {
    return configurationResourceCompat;
  }

  public String getDeploymentId() {
    return deploymentId;
  }

  public ProcessEngine getProcessEngine() {
    return processEngine;
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

  public HistoryService getHistoricDataService() {
    return historicDataService;
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

  public boolean isSkipTest() {
    return skipTest;
  }


}