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
package org.cibseven.bpm.engine.test.junit5;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;

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
import org.cibseven.bpm.engine.impl.ProcessEngineLogger;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.diagnostics.PlatformDiagnosticsRegistry;
import org.cibseven.bpm.engine.impl.test.TestHelper;
import org.cibseven.bpm.engine.impl.util.ClockUtil;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;
import org.junit.jupiter.api.extension.TestWatcher;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.commons.support.HierarchyTraversalMode;
import org.junit.platform.commons.support.ReflectionSupport;
import org.opentest4j.TestAbortedException;
import org.slf4j.Logger;

/**
 * Junit 5 Extension to create and inject a {@link ProcessEngine} into the test class.
 * <p>
 * If you provide a {@code camunda.cfg.xml} file on the classpath. This file is used to configure the process engine.
 * <br>
 * Usage:
 * </p>
 * <pre>
 * <code>@ExtendWith(ProcessEngineExtension.class)</code>
 * public class YourTest {
 *
 *   // provide a property where the extension can inject the process engine
 *   public ProcessEngine processEngine;
 *
 *   ...
 * }
 * </pre>
 *
 * <p>
 * If you want to choose the {@code camunda.cfg.xml} file that is used in the test programmatically,
 * you can register the extension directly and use the builder pattern to configure it.
 * <br>
 * Usage with configuration:
 *
 * </p>
 * Usage:
 * <p>
 * <pre>
 * <code>@RegisterExtension</code>
 * ProcessEngineExtension extension = ProcessEngineExtension.builder()
 *    .configurationResource("myConfigurationFile.xml")
 *    .build();}
 * </pre>
 * </p>
 * <p>
 * You can declare a deployment with the {@link Deployment} annotation. This
 * base class will make sure that this deployment gets deployed before the setUp
 * and {@link RepositoryService#deleteDeployment(String, boolean) cascade
 * deleted} after the tearDown.
 * </p>
 * <p>
 * If you need the history service for your tests then you can specify the
 * required history level of the test method or class, using the
 * {@link RequiredHistoryLevel} annotation. If the current history level of the
 * process engine is lower than the specified one then the test is skipped.
 * </p>
 * <p>
 * JUnit Jupiter calls the {@code @BeforeEach} methods of the test class before
 * the deployment is created and the {@code @AfterEach} methods after it is
 * deleted. The extension does not call them itself, unless a test runner skips
 * them: Arquillian's JUnit 5 integration does so for an embedded container that
 * runs the test through the Arquillian "Local" protocol (e.g.
 * {@code arquillian-weld-embedded}). Only for such a setup, let the extension
 * call them with {@link #invokeBeforeAndAfterEachMethods(boolean)}.
 * </p>
 */
public class ProcessEngineExtension implements TestWatcher,
    TestInstancePostProcessor, BeforeTestExecutionCallback, AfterTestExecutionCallback,
    AfterAllCallback, ParameterResolver, ProcessEngineServices {

  protected static final Logger LOG = ProcessEngineLogger.TEST_LOGGER.getLogger();

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

  protected String configurationResource = "camunda.cfg.xml";
  protected String deploymentId;
  protected boolean ensureCleanAfterTest = false;
  protected boolean invokeBeforeAndAfterEachMethods = false;
  protected List<String> additionalDeployments = new ArrayList<>();

  // SETUP

  protected void initializeProcessEngine() {
    processEngine = TestHelper.getProcessEngine(configurationResource);
    processEngineConfiguration = (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
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

  // TEST EXECUTION

  @Override
  public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    return parameterContext.getParameter().getType().equals(ProcessEngine.class);
  }

  @Override
  public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    if (ProcessEngine.class.equals(parameterContext.getParameter().getType())) {
      LOG.debug("resolve the processEngine as parameter");
      return getProcessEngine();
    } else {
      return null;
    }
  }

  @Override
  public void beforeTestExecution(ExtensionContext context) {
    LOG.debug("beforeTestExecution: {}", context.getDisplayName());

    final Method testMethod = context.getTestMethod().orElseThrow(illegalStateException("testMethod not set"));
    final Class<?> testClass = context.getTestClass().orElseThrow(illegalStateException("testClass not set"));

    if (invokeBeforeAndAfterEachMethods) {
      context.getTestInstance().ifPresent(this::invokeBeforeEachMethods);
    }

    deploymentId = TestHelper.annotationDeploymentSetUp(processEngine, testClass, testMethod.getName(), null, testMethod.getParameterTypes());
    boolean hasRequiredHistoryLevel = TestHelper.annotationRequiredHistoryLevelCheck(processEngine, testClass, testMethod.getName(), testMethod.getParameterTypes());
    boolean hasRequiredDatabase = TestHelper.annotationRequiredDatabaseCheck(processEngine, testClass, testMethod.getName(), testMethod.getParameterTypes());

    Assumptions.assumeTrue(hasRequiredHistoryLevel, "ignored because the current history level is too low");
    Assumptions.assumeTrue(hasRequiredDatabase, "ignored because the database doesn't match the required ones");
  }

  @Override
  public void afterTestExecution(ExtensionContext context) {
    final String testMethod = context.getTestMethod().orElseThrow(illegalStateException("testMethod not set")).getName();
    final Class<?> testClass = context.getTestClass().orElseThrow(illegalStateException("testClass not set"));

   TestHelper.annotationDeploymentTearDown(processEngine, deploymentId, testClass, testMethod);
   deploymentId = null;
   for (String additionalDeployment : additionalDeployments) {
     TestHelper.deleteDeployment(processEngine, additionalDeployment);
   }
   additionalDeployments.clear();

   TestHelper.resetIdGenerator(processEngineConfiguration);
   ClockUtil.reset();
   PlatformDiagnosticsRegistry.clear();

   Throwable failure = null;
   if (invokeBeforeAndAfterEachMethods) {
     try {
       context.getTestInstance().ifPresent(this::invokeAfterEachMethods);
     } catch (Throwable t) {
       failure = t;
     }
   }

   // finally clear database and fail test if database is dirty, also after a failing @AfterEach method
   if (ensureCleanAfterTest) {
     try {
       TestHelper.assertAndEnsureCleanDbAndCache(processEngine);
     } catch (Throwable t) {
       failure = addSuppressed(failure, t);
     }
   }
   if (failure != null) {
     rethrow(failure);
   }
  }

  @Override
  public void afterAll(ExtensionContext context) throws Exception {
    clearServiceReferences();
  }

  @Override
  public void postProcessTestInstance(Object testInstance, ExtensionContext context) {
    if (processEngine == null) {
      initializeProcessEngine();
    }
    Arrays.stream(testInstance.getClass().getDeclaredFields())
    .filter(field -> field.getType() == ProcessEngine.class)
    .forEach(field -> inject(testInstance, field));
  }

  /**
   * Invokes the {@code @BeforeEach} methods of the test instance, superclass
   * methods first (the order of JUnit Jupiter). Only used if
   * {@link #invokeBeforeAndAfterEachMethods(boolean)} is enabled.
   */
  protected void invokeBeforeEachMethods(Object testInstance) {
    invokeAnnotatedMethods(testInstance, BeforeEach.class, HierarchyTraversalMode.TOP_DOWN, false);
  }

  /**
   * Invokes the {@code @AfterEach} methods of the test instance, subclass
   * methods first (the order of JUnit Jupiter). Only used if
   * {@link #invokeBeforeAndAfterEachMethods(boolean)} is enabled.
   */
  protected void invokeAfterEachMethods(Object testInstance) {
    invokeAnnotatedMethods(testInstance, AfterEach.class, HierarchyTraversalMode.BOTTOM_UP, true);
  }

  /**
   * Invokes the annotated methods like JUnit Jupiter does: exceptions are passed
   * on unchanged, so a failed assumption aborts the test instead of failing it.
   * The first failing {@code @BeforeEach} method stops the remaining ones; all
   * {@code @AfterEach} methods run, and further failures are added as suppressed
   * exceptions to the first real failure (see {@link #addSuppressed(Throwable, Throwable)}).
   */
  private void invokeAnnotatedMethods(Object testInstance, Class<? extends Annotation> annotation,
      HierarchyTraversalMode traversalMode, boolean continueAfterFailure) {
    String annotationLabel = "@" + annotation.getSimpleName();
    Throwable failure = null;
    for (Method method : AnnotationSupport.findAnnotatedMethods(testInstance.getClass(), annotation, traversalMode)) {
      if (method.getParameterCount() != 0) {
        LOG.warn("{} method '{}' has parameters and will not be invoked automatically.", annotationLabel, method.getName());
        continue;
      }
      try {
        ReflectionSupport.invokeMethod(method, testInstance);
      } catch (Throwable t) {
        if (!continueAfterFailure) {
          throw t;
        }
        failure = addSuppressed(failure, t);
      }
    }
    if (failure != null) {
      rethrow(failure);
    }
  }

  /**
   * Combines two throwables the way JUnit Jupiter's own throwable collection does:
   * a real failure takes precedence over an aborted assumption, so the test is
   * reported as failed and not as aborted; the same instance thrown twice is kept
   * once, because {@link Throwable#addSuppressed(Throwable)} rejects
   * self-suppression.
   */
  private static Throwable addSuppressed(Throwable first, Throwable next) {
    if (first == null || first == next) {
      return first == null ? next : first;
    }
    if (first instanceof TestAbortedException && !(next instanceof TestAbortedException)) {
      next.addSuppressed(first);
      return next;
    }
    first.addSuppressed(next);
    return first;
  }

  /** Throws the throwable unchanged, also a checked one (as JUnit Jupiter's own lifecycle method invocation does). */
  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void rethrow(Throwable throwable) throws T {
    throw (T) throwable;
  }

  // FLUENT BUILDER

  public static ProcessEngineExtension builder() {
    return new ProcessEngineExtension();
  }

  public ProcessEngineExtension configurationResource(String configurationResource) {
    this.configurationResource = configurationResource;
    return this;
  }

  public ProcessEngineExtension useProcessEngine(ProcessEngine engine) {
    this.setProcessEngine(engine);
    return this;
  }

  public ProcessEngineExtension ensureCleanAfterTest(boolean ensureCleanAfterTest) {
    this.ensureCleanAfterTest = ensureCleanAfterTest;
    return this;
  }

  /**
   * Lets the extension invoke the {@code @BeforeEach} and {@code @AfterEach}
   * methods of the test instance itself: the {@code @BeforeEach} methods right
   * before the {@link Deployment} is created, the {@code @AfterEach} methods
   * right after it is deleted. Methods with parameters are not invoked.
   * <p>
   * Default: {@code false}. Enable it only if the test runner skips these
   * methods, e.g. Arquillian's JUnit 5 integration with an embedded container
   * that uses the Arquillian "Local" protocol such as
   * {@code arquillian-weld-embedded}. In a plain JUnit Jupiter run, JUnit already
   * calls them, so enabling it would call each of them twice per test.
   * </p>
   */
  public ProcessEngineExtension invokeBeforeAndAfterEachMethods(boolean invokeBeforeAndAfterEachMethods) {
    this.invokeBeforeAndAfterEachMethods = invokeBeforeAndAfterEachMethods;
    return this;
  }

  public ProcessEngineExtension manageDeployment(org.cibseven.bpm.engine.repository.Deployment deployment) {
    this.additionalDeployments.add(deployment.getId());
    return this;
  }

  public ProcessEngineExtension build() {
    if (processEngine == null) {
      initializeProcessEngine();
    }
    initializeServices();
    return this;
  }

  // HELPER

  protected Supplier<IllegalStateException> illegalStateException(String msg) {
    return () -> new IllegalStateException(msg);
  }

  protected void inject(Object instance, Field field) {
    field.setAccessible(true);
    try {
      field.set(instance, processEngine);
    } catch (IllegalAccessException iae) {
      throw new RuntimeException(iae);
    }
  }

  // GETTER / SETTER

  public void setCurrentTime(Date currentTime) {
    ClockUtil.setCurrentTime(currentTime);
  }

  public ProcessEngine getProcessEngine() {
    return processEngine;
  }

  public void setProcessEngine(ProcessEngine processEngine) {
    this.processEngine = processEngine;
    initializeServices();
  }

  public String getConfigurationResource() {
    return configurationResource;
  }

  public ProcessEngineConfigurationImpl getProcessEngineConfiguration() {
    return processEngineConfiguration;
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

  public void setManagementService(ManagementService managementService) {
    this.managementService = managementService;
  }

  @Override
  public FormService getFormService() {
    return formService;
  }

  public void setFormService(FormService formService) {
    this.formService = formService;
  }

  @Override
  public FilterService getFilterService() {
    return filterService;
  }

  public void setFilterService(FilterService filterService) {
    this.filterService = filterService;
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

  public void setProcessEngineConfiguration(ProcessEngineConfigurationImpl processEngineConfiguration) {
    this.processEngineConfiguration = processEngineConfiguration;
  }

  public void setConfigurationResource(String configurationResource) {
    this.configurationResource = configurationResource;
  }

  public String getDeploymentId() {
    return deploymentId;
  }

  public boolean isEnsureCleanAfterTest() {
    return ensureCleanAfterTest;
  }

  public void setEnsureCleanAfterTest(boolean ensureCleanAfterTest) {
    this.ensureCleanAfterTest = ensureCleanAfterTest;
  }

}
