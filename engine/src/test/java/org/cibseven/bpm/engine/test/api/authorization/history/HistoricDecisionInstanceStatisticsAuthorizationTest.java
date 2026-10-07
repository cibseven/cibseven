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
package org.cibseven.bpm.engine.test.api.authorization.history;

import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario.scenario;
import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationSpec.grant;

import java.util.Collection;

import org.cibseven.bpm.engine.DecisionService;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.authorization.Permissions;
import org.cibseven.bpm.engine.authorization.Resources;
import org.cibseven.bpm.engine.repository.DecisionRequirementsDefinition;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationTestRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.engine.variable.Variables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * @author Askar Akhmerov
 */
public class HistoricDecisionInstanceStatisticsAuthorizationTest {

  protected static final String DISH_DRG_DMN = "org/cibseven/bpm/engine/test/dmn/deployment/drdDish.dmn11.xml";

  @RegisterExtension
  @Order(1) protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  @Order(2) protected AuthorizationTestRule authRule = new AuthorizationTestRule(engineRule);
  @RegisterExtension
  @Order(3) protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);
  protected DecisionService decisionService;
  protected HistoryService historyService;
  protected RepositoryService repositoryService;

  protected DecisionRequirementsDefinition decisionRequirementsDefinition;

  public static Collection<AuthorizationScenario[]> scenarios() {
    return AuthorizationTestRule.asParameters(
        scenario()
            .withoutAuthorizations()
            .failsDueToRequired(
                grant(Resources.DECISION_REQUIREMENTS_DEFINITION, "dish", "userId", Permissions.READ)
            ),
        scenario()
            .withAuthorizations(
                grant(Resources.DECISION_REQUIREMENTS_DEFINITION, "drd", "userId", Permissions.READ)
            ).succeeds()
    );
  }

  @BeforeEach
  public void setUp() {
    testRule.deploy(DISH_DRG_DMN);
    decisionService = engineRule.getDecisionService();
    historyService = engineRule.getHistoryService();
    repositoryService = engineRule.getRepositoryService();

    authRule.createUserAndGroup("userId", "groupId");

    decisionService.evaluateDecisionTableByKey("dish-decision")
        .variables(Variables.createVariables().putValue("temperature", 21).putValue("dayType", "Weekend"))
        .evaluate();

    decisionRequirementsDefinition = repositoryService.createDecisionRequirementsDefinitionQuery().singleResult();
  }

  @AfterEach
  public void tearDown() {
    authRule.deleteUsersAndGroups();
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testHistoricDecisionStatistics(AuthorizationScenario scenario) {
    //given
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("drd", "*")
        .start();

    // when
    historyService.createHistoricDecisionInstanceStatisticsQuery(
        decisionRequirementsDefinition.getId()).list();

    // then
    authRule.assertScenario(scenario);
  }

}
