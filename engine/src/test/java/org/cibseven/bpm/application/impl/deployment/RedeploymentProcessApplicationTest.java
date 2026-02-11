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
package org.cibseven.bpm.application.impl.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import org.cibseven.bpm.application.ProcessApplicationExecutionException;
import org.cibseven.bpm.application.ProcessApplicationReference;
import org.cibseven.bpm.application.impl.EmbeddedProcessApplication;
import org.cibseven.bpm.engine.CaseService;
import org.cibseven.bpm.engine.DecisionService;
import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.repository.Deployment;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
//import org.cibseven.bpm.engine.test.ProvidedProcessEngineRuleSingleExtension;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.engine.variable.Variables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * @author Roman Smirnov
 *
 */
//@ExtendWith(ProvidedProcessEngineRuleSingleExtension.class)
public class RedeploymentProcessApplicationTest {

  protected static final String DEPLOYMENT_NAME = "my-deployment";

  protected static final String BPMN_RESOURCE_1 = "org/cibseven/bpm/engine/test/api/repository/processOne.bpmn20.xml";
  protected static final String BPMN_RESOURCE_2 = "org/cibseven/bpm/engine/test/api/repository/processTwo.bpmn20.xml";

  protected static final String CMMN_RESOURCE_1 = "org/cibseven/bpm/engine/test/api/cmmn/oneTaskCase.cmmn";
  protected static final String CMMN_RESOURCE_2 = "org/cibseven/bpm/engine/test/api/cmmn/twoTaskCase.cmmn";

  protected static final String DMN_RESOURCE_1 = "org/cibseven/bpm/engine/test/dmn/deployment/DecisionDefinitionDeployerTest.testDmnDeployment.dmn11.xml";
  protected static final String DMN_RESOURCE_2 = "org/cibseven/bpm/engine/test/dmn/deployment/dmnScore.dmn11.xml";

  protected static final String DRD_RESOURCE_1 = "org/cibseven/bpm/engine/test/dmn/deployment/drdScore.dmn11.xml";
  protected static final String DRD_RESOURCE_2 = "org/cibseven/bpm/engine/test/dmn/deployment/drdDish.dmn11.xml";

  @RegisterExtension
  public ProcessEngineRule engineRule = new ProvidedProcessEngineRule();

  protected static RepositoryService repositoryService;
  protected static RuntimeService runtimeService;
  protected static CaseService caseService;
  protected static DecisionService decisionService;
  protected static ManagementService managementService;

  public boolean enforceHistoryTimeToLive;

  public final List<Deployment> deploymentsToCleanup = new ArrayList<>();

  public static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> scenarios() {
    return java.util.stream.Stream.of(
      org.junit.jupiter.params.provider.Arguments.of(BPMN_RESOURCE_1, BPMN_RESOURCE_2, "processOne", "processTwo", processDefinitionTestProvider()),
      org.junit.jupiter.params.provider.Arguments.of(CMMN_RESOURCE_1, CMMN_RESOURCE_2, "oneTaskCase", "twoTaskCase", caseDefinitionTestProvider()),
      org.junit.jupiter.params.provider.Arguments.of(DMN_RESOURCE_1, DMN_RESOURCE_2, "decision", "score-decision", decisionDefinitionTestProvider()),
      org.junit.jupiter.params.provider.Arguments.of(DRD_RESOURCE_1, DRD_RESOURCE_2, "score", "dish", decisionRequirementsDefinitionTestProvider())
    );
  }

  @BeforeEach
  public void init() throws Exception {
    repositoryService = engineRule.getRepositoryService();
    runtimeService = engineRule.getRuntimeService();
    caseService = engineRule.getCaseService();
    decisionService = engineRule.getDecisionService();
    managementService = engineRule.getManagementService();

    enforceHistoryTimeToLive = engineRule.getProcessEngineConfiguration().isEnforceHistoryTimeToLive();
  }

  @AfterEach
  public void tearDown() {
    engineRule.getProcessEngineConfiguration().setEnforceHistoryTimeToLive(enforceHistoryTimeToLive);

    if (!deploymentsToCleanup.isEmpty()) {
      deleteDeployments(deploymentsToCleanup);
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void definitionOnePreviousDeploymentWithPA(String resource1, String resource2, String definitionKey1, String definitionKey2, TestProvider testProvider) {
    // given

    MyEmbeddedProcessApplication application = new MyEmbeddedProcessApplication();

    // first deployment
    Deployment deployment1 = repositoryService
        .createDeployment(application.getReference())
        .name(DEPLOYMENT_NAME)
        .addClasspathResource(resource1)
        .deploy();

    // second deployment
    Deployment deployment2 = repositoryService
        .createDeployment()
        .name(DEPLOYMENT_NAME)
        .addDeploymentResources(deployment1.getId())
        .deploy();

    assertEquals(2, testProvider.countDefinitionsByKey(definitionKey1));

    // when
    testProvider.createInstanceByDefinitionKey(definitionKey1);

    // then
    assertTrue(application.isCalled());

    deploymentsToCleanup.addAll(Arrays.asList(deployment1, deployment2));
  }

  @Test
  public void redeploymentShouldFailOnNullHTTLAndEnforceHistoryTimeToLiveTrue() {
    // given
    Deployment deployment1 = null;
    Deployment deployment2 = null;
    try {
      MyEmbeddedProcessApplication application = new MyEmbeddedProcessApplication();
      engineRule.getProcessEngineConfiguration().setEnforceHistoryTimeToLive(false);

      // first deployment allows null HTTL
      deployment1 = repositoryService
          .createDeployment(application.getReference())
          .name(DEPLOYMENT_NAME)
          .addClasspathResource(BPMN_RESOURCE_1)
          .deploy();

      // enforceHistoryTimeToLive=true should prevent deployment2 from getting deployed
      engineRule.getProcessEngineConfiguration().setEnforceHistoryTimeToLive(true);

      // when - second deployment
      deployment2 = repositoryService
          .createDeployment()
          .name(DEPLOYMENT_NAME)
          .addDeploymentResources(deployment1.getId())
          .deploy();

      fail("The second deployment should have thrown an exception due to mandatory enforcement of historyTimeToLive");
    } catch (Exception e) {
      // then
      assertThat(e)
          .withFailMessage("Deployment2 should throw ProcessEngineException due to mandatory historyTimeToLive")
          .isInstanceOf(ProcessEngineException.class);
    } finally {

      // cleanup
      if (deployment1 != null) {
        deploymentsToCleanup.add(deployment1);
      }

      if (deployment2 != null) {
        deploymentsToCleanup.add(deployment2);
      }
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void definitionTwoPreviousDeploymentWithPA(String resource1, String resource2, String definitionKey1, String definitionKey2, TestProvider testProvider) {
    // given

    // first deployment
    MyEmbeddedProcessApplication application1 = new MyEmbeddedProcessApplication();
    Deployment deployment1 = repositoryService
        .createDeployment(application1.getReference())
        .name(DEPLOYMENT_NAME)
        .addClasspathResource(resource1)
        .deploy();

    // second deployment
    MyEmbeddedProcessApplication application2 = new MyEmbeddedProcessApplication();
    Deployment deployment2 = repositoryService
        .createDeployment(application2.getReference())
        .name(DEPLOYMENT_NAME)
        .addClasspathResource(resource1)
        .deploy();

    // second deployment
    Deployment deployment3 = repositoryService
        .createDeployment()
        .name(DEPLOYMENT_NAME)
        .addDeploymentResources(deployment1.getId())
        .deploy();

    assertEquals(3, testProvider.countDefinitionsByKey(definitionKey1));

    // when
    testProvider.createInstanceByDefinitionKey(definitionKey1);

    // then
    assertFalse(application1.isCalled());
    assertTrue(application2.isCalled());

    deploymentsToCleanup.addAll(Arrays.asList(deployment1, deployment2, deployment3));
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void definitionTwoPreviousDeploymentFirstDeploymentWithPA(String resource1, String resource2, String definitionKey1, String definitionKey2, TestProvider testProvider) {
    // given

    // first deployment
    MyEmbeddedProcessApplication application1 = new MyEmbeddedProcessApplication();
    Deployment deployment1 = repositoryService
        .createDeployment(application1.getReference())
        .name(DEPLOYMENT_NAME)
        .addClasspathResource(resource1)
        .deploy();

    // second deployment
    Deployment deployment2 = repositoryService
        .createDeployment()
        .name(DEPLOYMENT_NAME)
        .addClasspathResource(resource1)
        .deploy();

    // second deployment
    Deployment deployment3 = repositoryService
        .createDeployment()
        .name(DEPLOYMENT_NAME)
        .addDeploymentResources(deployment1.getId())
        .deploy();

    assertEquals(3, testProvider.countDefinitionsByKey(definitionKey1));

    // when
    testProvider.createInstanceByDefinitionKey(definitionKey1);

    // then
    assertTrue(application1.isCalled());

    deploymentsToCleanup.addAll(Arrays.asList(deployment1, deployment2, deployment3));
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void definitionTwoPreviousDeploymentDeleteSecondDeployment(String resource1, String resource2, String definitionKey1, String definitionKey2, TestProvider testProvider) {
    // given

    // first deployment
    MyEmbeddedProcessApplication application1 = new MyEmbeddedProcessApplication();
    Deployment deployment1 = repositoryService
        .createDeployment(application1.getReference())
        .name(DEPLOYMENT_NAME)
        .addClasspathResource(resource1)
        .deploy();

    // second deployment
    MyEmbeddedProcessApplication application2 = new MyEmbeddedProcessApplication();
    Deployment deployment2 = repositoryService
        .createDeployment(application2.getReference())
        .name(DEPLOYMENT_NAME)
        .addClasspathResource(resource1)
        .deploy();

    // second deployment
    Deployment deployment3 = repositoryService
        .createDeployment()
        .name(DEPLOYMENT_NAME)
        .addDeploymentResources(deployment1.getId())
        .deploy();

    assertEquals(3, testProvider.countDefinitionsByKey(definitionKey1));

    // when
    deleteDeployments(deployment2);
    testProvider.createInstanceByDefinitionKey(definitionKey1);

    // then
    assertTrue(application1.isCalled());
    assertFalse(application2.isCalled());

    deploymentsToCleanup.addAll(Arrays.asList(deployment1, deployment3));
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void definitionTwoPreviousDeploymentUnregisterSecondPA(String resource1, String resource2, String definitionKey1, String definitionKey2, TestProvider testProvider) {
    // given

    // first deployment
    MyEmbeddedProcessApplication application1 = new MyEmbeddedProcessApplication();
    Deployment deployment1 = repositoryService
        .createDeployment(application1.getReference())
        .name(DEPLOYMENT_NAME)
        .addClasspathResource(resource1)
        .deploy();

    // second deployment
    MyEmbeddedProcessApplication application2 = new MyEmbeddedProcessApplication();
    Deployment deployment2 = repositoryService
        .createDeployment(application2.getReference())
        .name(DEPLOYMENT_NAME)
        .addClasspathResource(resource1)
        .deploy();

    // second deployment
    Deployment deployment3 = repositoryService
        .createDeployment()
        .name(DEPLOYMENT_NAME)
        .addDeploymentResources(deployment1.getId())
        .deploy();

    assertEquals(3, testProvider.countDefinitionsByKey(definitionKey1));

    // when
    managementService.unregisterProcessApplication(deployment2.getId(), true);
    testProvider.createInstanceByDefinitionKey(definitionKey1);

    // then
    assertTrue(application1.isCalled());
    assertFalse(application2.isCalled());

    deploymentsToCleanup.addAll(Arrays.asList(deployment1, deployment2, deployment3));
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void definitionTwoDifferentPreviousDeploymentsWithDifferentPA(String resource1, String resource2, String definitionKey1, String definitionKey2, TestProvider testProvider) {
    // given

    // first deployment
    MyEmbeddedProcessApplication application1 = new MyEmbeddedProcessApplication();
    Deployment deployment1 = repositoryService
        .createDeployment(application1.getReference())
        .name(DEPLOYMENT_NAME + "-1")
        .addClasspathResource(resource1)
        .deploy();

    // second deployment
    MyEmbeddedProcessApplication application2 = new MyEmbeddedProcessApplication();
    Deployment deployment2 = repositoryService
        .createDeployment(application2.getReference())
        .name(DEPLOYMENT_NAME + "-2")
        .addClasspathResource(resource2)
        .deploy();

    // second deployment
    Deployment deployment3 = repositoryService
        .createDeployment()
        .name(DEPLOYMENT_NAME + "-3")
        .addDeploymentResources(deployment1.getId())
        .addDeploymentResources(deployment2.getId())
        .deploy();

    assertEquals(2, testProvider.countDefinitionsByKey(definitionKey1));
    assertEquals(2, testProvider.countDefinitionsByKey(definitionKey2));

    // when (1)
    testProvider.createInstanceByDefinitionKey(definitionKey1);

    // then (1)
    assertTrue(application1.isCalled());
    assertFalse(application2.isCalled());

    // reset flag
    application1.setCalled(false);

    // when (2)
    testProvider.createInstanceByDefinitionKey(definitionKey2);

    // then (2)
    assertFalse(application1.isCalled());
    assertTrue(application2.isCalled());

    deploymentsToCleanup.addAll(Arrays.asList(deployment1, deployment2, deployment3));
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void definitionTwoPreviousDeploymentsWithDifferentPA(String resource1, String resource2, String definitionKey1, String definitionKey2, TestProvider testProvider) {
    // given

    // first deployment
    MyEmbeddedProcessApplication application1 = new MyEmbeddedProcessApplication();
    Deployment deployment1 = repositoryService
      .createDeployment(application1.getReference())
      .name(DEPLOYMENT_NAME)
      .addClasspathResource(resource1)
      .addClasspathResource(resource2)
      .deploy();

    // second deployment
    MyEmbeddedProcessApplication application2 = new MyEmbeddedProcessApplication();
    Deployment deployment2 = repositoryService
        .createDeployment(application2.getReference())
        .name(DEPLOYMENT_NAME)
        .addClasspathResource(resource1)
        .deploy();

    // third deployment
    Deployment deployment3 = repositoryService
        .createDeployment()
        .name(DEPLOYMENT_NAME)
        .addDeploymentResources(deployment1.getId())
        .deploy();

    assertEquals(3, testProvider.countDefinitionsByKey(definitionKey1));
    assertEquals(2, testProvider.countDefinitionsByKey(definitionKey2));

    // when (1)
    testProvider.createInstanceByDefinitionKey(definitionKey1);

    // then (1)
    assertFalse(application1.isCalled());
    assertTrue(application2.isCalled());

    // reset flag
    application2.setCalled(false);

    // when (2)
    testProvider.createInstanceByDefinitionKey(definitionKey2);

    // then (2)
    assertTrue(application1.isCalled());
    assertFalse(application2.isCalled());

    deploymentsToCleanup.addAll(Arrays.asList(deployment1, deployment2, deployment3));
  }

  protected void deleteDeployments(List<Deployment> deployments) {
    Deployment[] array = new Deployment[deployments.size()];
    array = deployments.toArray(array);

    deleteDeployments(array);
  }

  protected void deleteDeployments(Deployment... deployments){
    for (Deployment deployment : deployments) {
      repositoryService.deleteDeployment(deployment.getId(), true);
      managementService.unregisterProcessApplication(deployment.getId(), false);
    }
  }

  protected interface TestProvider {
    long countDefinitionsByKey(String definitionKey);

    void createInstanceByDefinitionKey(String definitionKey);
  }

  protected static TestProvider processDefinitionTestProvider() {
    return new TestProvider() {

      public long countDefinitionsByKey(String definitionKey) {
        return repositoryService.createProcessDefinitionQuery().processDefinitionKey(definitionKey).count();
      }

      public void createInstanceByDefinitionKey(String definitionKey) {
        runtimeService.startProcessInstanceByKey(definitionKey, Variables.createVariables()
            .putValue("a", 1).putValue("b", 1));
      }

    };
  }

  protected static TestProvider caseDefinitionTestProvider() {
    return new TestProvider() {

      public long countDefinitionsByKey(String definitionKey) {
        return repositoryService.createCaseDefinitionQuery().caseDefinitionKey(definitionKey).count();
      }

      public void createInstanceByDefinitionKey(String definitionKey) {
        caseService.createCaseInstanceByKey(definitionKey);
      }

    };
  }

  protected static TestProvider decisionDefinitionTestProvider() {
    return new TestProvider() {

      public long countDefinitionsByKey(String definitionKey) {
        return repositoryService.createDecisionDefinitionQuery().decisionDefinitionKey(definitionKey).count();
      }

      public void createInstanceByDefinitionKey(String definitionKey) {
        decisionService.evaluateDecisionTableByKey(definitionKey)
          .variables(Variables.createVariables().putValue("input", "john"))
          .evaluate();
      }

    };
  }

  protected static TestProvider decisionRequirementsDefinitionTestProvider() {
    return new TestProvider() {

      public long countDefinitionsByKey(String definitionKey) {
        return repositoryService.createDecisionRequirementsDefinitionQuery().decisionRequirementsDefinitionKey(definitionKey).count();
      }

      public void createInstanceByDefinitionKey(String definitionKey) {
        decisionService.evaluateDecisionTableByKey(definitionKey + "-decision")
          .variables(Variables.createVariables()
              .putValue("temperature", 21)
              .putValue("dayType", "Weekend")
              .putValue("input", "John"))
          .evaluate();
      }

    };
  }

  public class MyEmbeddedProcessApplication extends EmbeddedProcessApplication {

    protected ProcessApplicationReference reference;
    protected boolean called;

    @Override
    public ProcessApplicationReference getReference() {
      if (reference == null) {
        reference = super.getReference();
      }
      return reference;
    }

    @Override
    public <T> T execute(Callable<T> callable) throws ProcessApplicationExecutionException {
      called = true;
      return super.execute(callable);
    }

    public boolean isCalled() {
      return called;
    }

    public void setCalled(boolean called) {
      this.called = called;
    }

  }

}
