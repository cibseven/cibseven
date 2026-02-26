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

import org.cibseven.bpm.application.ProcessApplicationReference;
import org.cibseven.bpm.application.impl.EmbeddedProcessApplication;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.impl.application.ProcessApplicationManager;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cmmn.entity.repository.CaseDefinitionEntity;
import org.cibseven.bpm.engine.impl.context.ProcessApplicationContextUtil;
import org.cibseven.bpm.engine.impl.dmn.entity.repository.DecisionDefinitionEntity;
import org.cibseven.bpm.engine.impl.dmn.entity.repository.DecisionRequirementsDefinitionEntity;
import org.cibseven.bpm.engine.impl.interceptor.Command;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.deploy.cache.DeploymentCache;
import org.cibseven.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.cibseven.bpm.engine.repository.Deployment;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;


public class RedeploymentRegistrationTest {

  protected static final String DEPLOYMENT_NAME = "my-deployment";

  protected static final String BPMN_RESOURCE_1 = "org/cibseven/bpm/engine/test/api/repository/processOne.bpmn20.xml";
  protected static final String BPMN_RESOURCE_2 = "org/cibseven/bpm/engine/test/api/repository/processTwo.bpmn20.xml";

  protected static final String CMMN_RESOURCE_1 = "org/cibseven/bpm/engine/test/api/cmmn/oneTaskCase.cmmn";
  protected static final String CMMN_RESOURCE_2 = "org/cibseven/bpm/engine/test/api/cmmn/twoTaskCase.cmmn";

  protected static final String DMN_RESOURCE_1 = "org/cibseven/bpm/engine/test/dmn/deployment/DecisionDefinitionDeployerTest.testDmnDeployment.dmn11.xml";
  protected static final String DMN_RESOURCE_2 = "org/cibseven/bpm/engine/test/dmn/deployment/dmnScore.dmn11.xml";

  protected static final String DRD_RESOURCE_1 = "org/cibseven/bpm/engine/test/dmn/deployment/drdScore.dmn11.xml";
  protected static final String DRD_RESOURCE_2 = "org/cibseven/bpm/engine/test/dmn/deployment/drdDish.dmn11.xml";

  protected EmbeddedProcessApplication processApplication;

  @RegisterExtension
  protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  protected RepositoryService repositoryService;
  protected ProcessEngineConfigurationImpl processEngineConfiguration;

  public static Stream<Arguments> scenarios() {
    return Stream.of(
      Arguments.of(BPMN_RESOURCE_1, BPMN_RESOURCE_2, "processOne", "processTwo", processDefinitionTestProvider()),
      Arguments.of(CMMN_RESOURCE_1, CMMN_RESOURCE_2, "oneTaskCase", "twoTaskCase", caseDefinitionTestProvider()),
      Arguments.of(DMN_RESOURCE_1, DMN_RESOURCE_2, "decision", "score-decision", decisionDefinitionTestProvider()),
      Arguments.of(DRD_RESOURCE_1, DRD_RESOURCE_2, "score", "dish", decisionRequirementsDefinitionTestProvider())
    );
  }

  @BeforeEach
  public void init() throws Exception {
    repositoryService = engineRule.getRepositoryService();
    processEngineConfiguration = engineRule.getProcessEngineConfiguration();

    processApplication = new EmbeddedProcessApplication();
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void registrationNotFoundByDeploymentId(String resource1, String resource2, String definitionKey1, String definitionKey2, TestProvider testProvider) {
    // given
    ProcessApplicationReference reference = processApplication.getReference();

    Deployment deployment1 = repositoryService
      .createDeployment(reference)
      .name(DEPLOYMENT_NAME)
      .addClasspathResource(resource1)
      .deploy();

    assertEquals(reference, getProcessApplicationForDeployment(deployment1.getId()));

    // when
    Deployment deployment2 = repositoryService
        .createDeployment()
        .name(DEPLOYMENT_NAME)
        .addDeploymentResources(deployment1.getId())
        .deploy();

    // then
    assertNull(getProcessApplicationForDeployment(deployment2.getId()));
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void registrationNotFoundByDefinition(String resource1, String resource2, String definitionKey1, String definitionKey2, TestProvider testProvider) {
    // given

    // first deployment
    Deployment deployment1 = repositoryService
      .createDeployment()
      .name(DEPLOYMENT_NAME)
      .addClasspathResource(resource1)
      .deploy();

    // second deployment
    repositoryService
      .createDeployment()
      .name(DEPLOYMENT_NAME)
      .addClasspathResource(resource1)
      .deploy();

    // when
    repositoryService
        .createDeployment()
        .name(DEPLOYMENT_NAME)
        .addDeploymentResources(deployment1.getId())
        .deploy();

    String definitionId = testProvider.getLatestDefinitionIdByKey(repositoryService, definitionKey1);

    // then
    assertNull(getProcessApplicationForDefinition(definitionId, testProvider));
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void registrationFoundByDeploymentId(String resource1, String resource2, String definitionKey1, String definitionKey2, TestProvider testProvider) {
    // given
    ProcessApplicationReference reference1 = processApplication.getReference();

    Deployment deployment1 = repositoryService
      .createDeployment(reference1)
      .name(DEPLOYMENT_NAME)
      .addClasspathResource(resource1)
      .deploy();

    assertEquals(reference1, getProcessApplicationForDeployment(deployment1.getId()));

    // when
    ProcessApplicationReference reference2 = processApplication.getReference();

    Deployment deployment2 = repositoryService
        .createDeployment(reference2)
        .name(DEPLOYMENT_NAME)
        .addDeploymentResources(deployment1.getId())
        .deploy();

    // then
    assertEquals(reference2, getProcessApplicationForDeployment(deployment2.getId()));
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void registrationFoundFromPreviousDefinition(String resource1, String resource2, String definitionKey1, String definitionKey2, TestProvider testProvider) {
    // given
    ProcessApplicationReference reference = processApplication.getReference();
    Deployment deployment1 = repositoryService
      .createDeployment(reference)
      .name(DEPLOYMENT_NAME)
      .addClasspathResource(resource1)
      .deploy();

    // when
    Deployment deployment2 = repositoryService
        .createDeployment()
        .name(DEPLOYMENT_NAME)
        .addDeploymentResources(deployment1.getId())
        .deploy();

    String definitionId = testProvider.getLatestDefinitionIdByKey(repositoryService, definitionKey1);

    // then
    assertEquals(reference, getProcessApplicationForDefinition(definitionId, testProvider));

    // and the reference is not cached
    assertNull(getProcessApplicationForDeployment(deployment2.getId()));
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void registrationFoundFromLatestDeployment(String resource1, String resource2, String definitionKey1, String definitionKey2, TestProvider testProvider) {
    // given
    ProcessApplicationReference reference1 = processApplication.getReference();
    Deployment deployment1 = repositoryService
      .createDeployment(reference1)
      .name(DEPLOYMENT_NAME)
      .addClasspathResource(resource1)
      .deploy();

    // when
    ProcessApplicationReference reference2 = processApplication.getReference();
    Deployment deployment2 = repositoryService
        .createDeployment(reference2)
        .name(DEPLOYMENT_NAME)
        .addDeploymentResources(deployment1.getId())
        .deploy();

    String definitionId = testProvider.getLatestDefinitionIdByKey(repositoryService, definitionKey1);

    // then
    assertEquals(reference2, getProcessApplicationForDefinition(definitionId, testProvider));
    assertEquals(reference2, getProcessApplicationForDeployment(deployment2.getId()));
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void registrationFoundOnlyForOneProcessDefinition(String resource1, String resource2, String definitionKey1, String definitionKey2, TestProvider testProvider) {
    // given

    // first deployment
    Deployment deployment1 = repositoryService
      .createDeployment()
      .name(DEPLOYMENT_NAME)
      .addClasspathResource(resource1)
      .addClasspathResource(resource2)
      .deploy();

    // second deployment
    ProcessApplicationReference reference2 = processApplication.getReference();
    repositoryService
        .createDeployment(reference2)
        .name(DEPLOYMENT_NAME)
        .addClasspathResource(resource1)
        .deploy();

    // when
    repositoryService
        .createDeployment()
        .name(DEPLOYMENT_NAME)
        .addDeploymentResources(deployment1.getId())
        .deploy();

    String firstDefinitionId = testProvider.getLatestDefinitionIdByKey(repositoryService, definitionKey1);
    String secondDefinitionId = testProvider.getLatestDefinitionIdByKey(repositoryService, definitionKey2);

    // then
    assertEquals(reference2, getProcessApplicationForDefinition(firstDefinitionId, testProvider));
    assertNull(getProcessApplicationForDefinition(secondDefinitionId, testProvider));
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void registrationFoundFromDifferentDeployment(String resource1, String resource2, String definitionKey1, String definitionKey2, TestProvider testProvider) {
    // given

    // first deployment
    ProcessApplicationReference reference1 = processApplication.getReference();
    Deployment deployment1 = repositoryService
      .createDeployment(reference1)
      .name(DEPLOYMENT_NAME)
      .addClasspathResource(resource1)
      .addClasspathResource(resource2)
      .deploy();

    // second deployment
    ProcessApplicationReference reference2 = processApplication.getReference();
    repositoryService
        .createDeployment(reference2)
        .name(DEPLOYMENT_NAME)
        .addClasspathResource(resource1)
        .deploy();

    // when
    repositoryService
        .createDeployment()
        .name(DEPLOYMENT_NAME)
        .addDeploymentResources(deployment1.getId())
        .deploy();

    String firstDefinitionId = testProvider.getLatestDefinitionIdByKey(repositoryService, definitionKey1);
    String secondDefinitionId = testProvider.getLatestDefinitionIdByKey(repositoryService, definitionKey2);

    // then
    assertEquals(reference2, getProcessApplicationForDefinition(firstDefinitionId, testProvider));
    assertEquals(reference1, getProcessApplicationForDefinition(secondDefinitionId, testProvider));
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void registrationFoundFromSameDeployment(String resource1, String resource2, String definitionKey1, String definitionKey2, TestProvider testProvider) {
    // given

    // first deployment
    ProcessApplicationReference reference1 = processApplication.getReference();
    Deployment deployment1 = repositoryService
      .createDeployment(reference1)
      .name(DEPLOYMENT_NAME)
      .addClasspathResource(resource1)
      .addClasspathResource(resource2)
      .deploy();

    // second deployment
    repositoryService
        .createDeployment()
        .name(DEPLOYMENT_NAME)
        .addClasspathResource(resource1)
        .deploy();

    repositoryService
        .createDeployment()
        .name(DEPLOYMENT_NAME)
        .addClasspathResource(resource2)
        .deploy();

    // when
    repositoryService
        .createDeployment()
        .name(DEPLOYMENT_NAME)
        .addDeploymentResources(deployment1.getId())
        .deploy();

    String firstDefinitionId = testProvider.getLatestDefinitionIdByKey(repositoryService, definitionKey1);
    String secondDefinitionId = testProvider.getLatestDefinitionIdByKey(repositoryService, definitionKey1);

    // then
    assertEquals(reference1, getProcessApplicationForDefinition(firstDefinitionId, testProvider));
    assertEquals(reference1, getProcessApplicationForDefinition(secondDefinitionId, testProvider));
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void registrationFoundFromDifferentDeployments(String resource1, String resource2, String definitionKey1, String definitionKey2, TestProvider testProvider) {
    // given

    // first deployment
    ProcessApplicationReference reference1 = processApplication.getReference();
    Deployment deployment1 = repositoryService
      .createDeployment(reference1)
      .name(DEPLOYMENT_NAME + "-1")
      .addClasspathResource(resource1)
      .deploy();

    // second deployment
    ProcessApplicationReference reference2 = processApplication.getReference();
    repositoryService
        .createDeployment(reference2)
        .name(DEPLOYMENT_NAME + "-2")
        .addClasspathResource(resource2)
        .deploy();

    // when
    repositoryService
        .createDeployment()
        .name(DEPLOYMENT_NAME)
        .addDeploymentResources(deployment1.getId())
        .deploy();

    String firstDefinitionId = testProvider.getLatestDefinitionIdByKey(repositoryService, definitionKey1);
    String secondDefinitionId = testProvider.getLatestDefinitionIdByKey(repositoryService, definitionKey2);

    // then
    assertEquals(reference1, getProcessApplicationForDefinition(firstDefinitionId, testProvider));
    assertEquals(reference2, getProcessApplicationForDefinition(secondDefinitionId, testProvider));
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void registrationNotFoundWhenDeletingDeployment(String resource1, String resource2, String definitionKey1, String definitionKey2, TestProvider testProvider) {
    // given

    // first deployment
    Deployment deployment1 = repositoryService
      .createDeployment()
      .name(DEPLOYMENT_NAME)
      .addClasspathResource(resource1)
      .deploy();

    // second deployment
    ProcessApplicationReference reference2 = processApplication.getReference();
    Deployment deployment2 = repositoryService
        .createDeployment(reference2)
        .name(DEPLOYMENT_NAME)
        .addDeploymentResources(deployment1.getId())
        .deploy();

    // when (1)
    // third deployment
    repositoryService
        .createDeployment()
        .name(DEPLOYMENT_NAME)
        .addDeploymentResources(deployment1.getId())
        .deploy();

    String firstDefinitionId = testProvider.getLatestDefinitionIdByKey(repositoryService, definitionKey1);

    // then (1)
    assertEquals(reference2, getProcessApplicationForDefinition(firstDefinitionId, testProvider));

    // when (2)
    deleteDeployment(deployment2);

    // then (2)
    assertNull(getProcessApplicationForDefinition(firstDefinitionId, testProvider));
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void registrationFoundAfterDiscardingDeploymentCache(String resource1, String resource2, String definitionKey1, String definitionKey2, TestProvider testProvider) {
    // given

    // first deployment
    Deployment deployment1 = repositoryService
      .createDeployment()
      .name(DEPLOYMENT_NAME)
      .addClasspathResource(resource1)
      .deploy();

    // second deployment
    ProcessApplicationReference reference2 = processApplication.getReference();
    repositoryService
        .createDeployment(reference2)
        .name(DEPLOYMENT_NAME)
        .addDeploymentResources(deployment1.getId())
        .deploy();

    // when (1)
    // third deployment
    repositoryService
        .createDeployment()
        .name(DEPLOYMENT_NAME)
        .addDeploymentResources(deployment1.getId())
        .deploy();

    String firstDefinitionId = testProvider.getLatestDefinitionIdByKey(repositoryService, definitionKey1);

    // then (1)
    assertEquals(reference2, getProcessApplicationForDefinition(firstDefinitionId, testProvider));

    // when (2)
    discardDefinitionCache();

    // then (2)
    assertEquals(reference2, getProcessApplicationForDefinition(firstDefinitionId, testProvider));
  }

  // helper ///////////////////////////////////////////

  @AfterEach
  public void cleanUp() {
    for (Deployment deployment : repositoryService.createDeploymentQuery().list()) {
      deleteDeployment(deployment);
    }
  }

  protected void deleteDeployment(Deployment deployment) {
    repositoryService.deleteDeployment(deployment.getId(), true);
    engineRule.getManagementService().unregisterProcessApplication(deployment.getId(), false);
  }

  protected ProcessApplicationReference getProcessApplicationForDeployment(String deploymentId) {
    ProcessApplicationManager processApplicationManager = processEngineConfiguration.getProcessApplicationManager();
    return processApplicationManager.getProcessApplicationForDeployment(deploymentId);
  }

  protected void discardDefinitionCache() {
    processEngineConfiguration.getDeploymentCache().discardProcessDefinitionCache();
    processEngineConfiguration.getDeploymentCache().discardCaseDefinitionCache();
    processEngineConfiguration.getDeploymentCache().discardDecisionDefinitionCache();
    processEngineConfiguration.getDeploymentCache().discardDecisionRequirementsDefinitionCache();
  }

  protected ProcessApplicationReference getProcessApplicationForDefinition(String definitionId, TestProvider testProvider) {
    return processEngineConfiguration.getCommandExecutorTxRequired().execute(
        testProvider.createGetProcessApplicationCommand(definitionId));
  }

  private interface TestProvider {
    Command<ProcessApplicationReference> createGetProcessApplicationCommand(String definitionId);

    String getLatestDefinitionIdByKey(RepositoryService repositoryService, String key);
  }

  protected static TestProvider processDefinitionTestProvider() {
    return new TestProvider() {

      @Override
      public Command<ProcessApplicationReference> createGetProcessApplicationCommand(final String definitionId) {
        return new Command<ProcessApplicationReference>() {

          public ProcessApplicationReference execute(CommandContext commandContext) {
            ProcessEngineConfigurationImpl configuration = commandContext.getProcessEngineConfiguration();
            DeploymentCache deploymentCache = configuration.getDeploymentCache();
            ProcessDefinitionEntity definition = deploymentCache.findDeployedProcessDefinitionById(definitionId);
            return ProcessApplicationContextUtil.getTargetProcessApplication(definition);
          }
        };
      }

      @Override
      public String getLatestDefinitionIdByKey(RepositoryService repositoryService, String key) {
        return repositoryService.createProcessDefinitionQuery().processDefinitionKey(key).latestVersion().singleResult().getId();
      }

    };
  }

  protected static TestProvider caseDefinitionTestProvider() {
    return new TestProvider() {

      @Override
      public Command<ProcessApplicationReference> createGetProcessApplicationCommand(final String definitionId) {
        return new Command<ProcessApplicationReference>() {

          public ProcessApplicationReference execute(CommandContext commandContext) {
            ProcessEngineConfigurationImpl configuration = commandContext.getProcessEngineConfiguration();
            DeploymentCache deploymentCache = configuration.getDeploymentCache();
            CaseDefinitionEntity definition = deploymentCache.findDeployedCaseDefinitionById(definitionId);
            return ProcessApplicationContextUtil.getTargetProcessApplication(definition);
          }
        };
      }

      @Override
      public String getLatestDefinitionIdByKey(RepositoryService repositoryService, String key) {
        return repositoryService.createCaseDefinitionQuery().caseDefinitionKey(key).latestVersion().singleResult().getId();
      }

    };
  }

  protected static TestProvider decisionDefinitionTestProvider() {
    return new TestProvider() {

      @Override
      public Command<ProcessApplicationReference> createGetProcessApplicationCommand(final String definitionId) {
        return new Command<ProcessApplicationReference>() {

          public ProcessApplicationReference execute(CommandContext commandContext) {
            ProcessEngineConfigurationImpl configuration = commandContext.getProcessEngineConfiguration();
            DeploymentCache deploymentCache = configuration.getDeploymentCache();
            DecisionDefinitionEntity definition = deploymentCache.findDeployedDecisionDefinitionById(definitionId);
            return ProcessApplicationContextUtil.getTargetProcessApplication(definition);
          }
        };
      }

      @Override
      public String getLatestDefinitionIdByKey(RepositoryService repositoryService, String key) {
        return repositoryService.createDecisionDefinitionQuery().decisionDefinitionKey(key).latestVersion().singleResult().getId();
      }

    };
  }

  protected static TestProvider decisionRequirementsDefinitionTestProvider() {
    return new TestProvider() {

      @Override
      public Command<ProcessApplicationReference> createGetProcessApplicationCommand(final String definitionId) {
        return new Command<ProcessApplicationReference>() {

          public ProcessApplicationReference execute(CommandContext commandContext) {
            ProcessEngineConfigurationImpl configuration = commandContext.getProcessEngineConfiguration();
            DeploymentCache deploymentCache = configuration.getDeploymentCache();
            DecisionRequirementsDefinitionEntity definition = deploymentCache.findDeployedDecisionRequirementsDefinitionById(definitionId);
            return ProcessApplicationContextUtil.getTargetProcessApplication(definition);
          }
        };
      }

      @Override
      public String getLatestDefinitionIdByKey(RepositoryService repositoryService, String key) {
        return repositoryService.createDecisionRequirementsDefinitionQuery().decisionRequirementsDefinitionKey(key).latestVersion().singleResult().getId();
      }

    };
  }

}
