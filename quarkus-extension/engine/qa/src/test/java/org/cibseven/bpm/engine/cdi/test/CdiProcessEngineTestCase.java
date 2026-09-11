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
package org.cibseven.bpm.engine.cdi.test;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.test.QuarkusUnitTest;
import org.cibseven.bpm.BpmPlatform;
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
import org.cibseven.bpm.engine.cdi.impl.util.ProgrammaticBeanLookup;
import org.cibseven.bpm.engine.experimental.InjectProcessVariable;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.test.TestHelper;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.quarkus.engine.extension.QuarkusProcessEngineConfiguration;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.BeanManager;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class CdiProcessEngineTestCase {

  @RegisterExtension
  protected static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
          .addAsResource("application.properties")
          .addPackages(true, CdiProcessEngineTestCase.class.getPackage())
          .addPackages(true, InjectProcessVariable.class.getPackage()));

  protected String deploymentId;

  protected BeanManager beanManager;

  protected ProcessEngine processEngine;
  protected FormService formService;
  protected HistoryService historyService;
  protected IdentityService identityService;
  protected ManagementService managementService;
  protected RepositoryService repositoryService;
  protected RuntimeService runtimeService;
  protected TaskService taskService;
  protected AuthorizationService authorizationService;
  protected FilterService filterService;
  protected ExternalTaskService externalTaskService;
  protected CaseService caseService;
  protected DecisionService decisionService;

  protected ProcessEngineConfigurationImpl processEngineConfiguration;

  protected Set<InstanceHandle<?>> beanInstanceHandles = new HashSet<>();

  @BeforeEach
  public void before(TestInfo testInfo) throws Throwable {
    Set<String> processEngineNames = BpmPlatform.getProcessEngineService()
        .getProcessEngineNames();
    if (processEngineNames.size() != 1) throw new RuntimeException(
        "Expected exactly one process engine to be registered, but found: " + processEngineNames.size());
    processEngine =
        BpmPlatform.getProcessEngineService().getProcessEngine(processEngineNames.stream().findFirst()
            .orElseThrow(() -> new RuntimeException("No process engine registered")));
    Arc.container().requestContext().activate();
    beanManager = Arc.container().beanManager();
    processEngineConfiguration =
        (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
    processEngineConfiguration.setEnableExpressionsInAdhocQueries(true);
    formService = processEngine.getFormService();
    historyService = processEngine.getHistoryService();
    identityService = processEngine.getIdentityService();
    managementService = processEngine.getManagementService();
    repositoryService = processEngine.getRepositoryService();
    runtimeService = processEngine.getRuntimeService();
    taskService = processEngine.getTaskService();
    authorizationService = processEngine.getAuthorizationService();
    filterService = processEngine.getFilterService();
    externalTaskService = processEngine.getExternalTaskService();
    caseService = processEngine.getCaseService();
    decisionService = processEngine.getDecisionService();

    Method testMethod = testInfo.getTestMethod().orElse(null);
    assertThat(testMethod).isNotNull();

    String[] resources = getDeploymentResources(testMethod.getDeclaringClass(), testMethod);
    deploymentId = deploy(testMethod.getDeclaringClass(), testMethod.getName(), resources);
  }

  private String[] getDeploymentResources(Class<?> testClass, Method method) throws Throwable {
    for (var annotation : method.getAnnotations()) {
      // Handle proxy-wrapped annotations due to multiple classloaders (test jar vs quarkus QA)
      if (Proxy.isProxyClass(annotation.getClass())
          && Deployment.class.getName().equals(annotation.annotationType().getName())) {
        String[] resources = (String[]) Proxy.getInvocationHandler(annotation)
            .invoke(annotation, Deployment.class.getDeclaredMethod("resources"), null);
        if (resources.length > 0) return resources;
        return new String[] { TestHelper.getBpmnProcessDefinitionResource(testClass, method.getName()) };
      } else if (annotation instanceof Deployment) {
        Deployment deploymentAnnotation = (Deployment) annotation;
        String[] resources = deploymentAnnotation.resources();
        if (resources.length > 0) return resources;
        return new String[] { TestHelper.getBpmnProcessDefinitionResource(testClass, method.getName()) };
      }
    }
    return null;
  }

  @AfterEach
  public void after() {
    Arc.container().requestContext().deactivate();

    beanInstanceHandles.forEach(bean -> {
      try {
        bean.destroy();
      } catch (UnsupportedOperationException ignored) {
        // Eagerly destroying InjectableBusinessProcessContext is unsupported
        // See https://jira.camunda.com/browse/CAM-13755
      }
    });

    beanInstanceHandles.clear();

    if (deploymentId != null) {
      repositoryService.deleteDeployment(deploymentId, true, true, true);
      deploymentId = null;
    }

    beanManager = null;
    processEngineConfiguration = null;
    formService = null;
    historyService = null;
    identityService = null;
    managementService = null;
    repositoryService = null;
    runtimeService = null;
    taskService = null;
    authorizationService = null;
    filterService = null;
    externalTaskService = null;
    caseService = null;
    decisionService = null;
  }

  protected BeanManager getBeanManager() {
    return ProgrammaticBeanLookup.lookup(BeanManager.class);
  }

  protected <T> T getBeanInstance(Class<T> clazz) {
    InjectableInstance<T> select = Arc.container().select(clazz);
    InstanceHandle<T> handle = select.getHandle();
    beanInstanceHandles.add(handle);
    return handle.get();
  }

  protected Object getBeanInstance(String name) {
    InstanceHandle<Object> instance = Arc.container().instance(name);
    beanInstanceHandles.add(instance);
    return instance.get();
  }

  public String deploy(Class<?> testClass, String methodName, String[] resources) {
    if (resources != null) {
      return TestHelper.annotationDeploymentSetUp(processEngine, resources, testClass, methodName);
    }
    return null;
  }

  public void waitForJobExecutorToProcessAllJobs(long maxMillisToWait, long intervalMillis) {
    TestHelper.waitForJobExecutorToProcessAllJobs(processEngineConfiguration, maxMillisToWait, intervalMillis);
  }

  @ApplicationScoped
  static class EngineConfigurer {

    @Produces
    public QuarkusProcessEngineConfiguration customEngineConfig() {
      QuarkusProcessEngineConfiguration engineConfig = new QuarkusProcessEngineConfiguration();
      engineConfig.setJobExecutorActivate(false);
      return engineConfig;
    }

  }

}
