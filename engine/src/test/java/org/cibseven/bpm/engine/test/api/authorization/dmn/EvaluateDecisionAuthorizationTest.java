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
package org.cibseven.bpm.engine.test.api.authorization.dmn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario.scenario;
import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationSpec.grant;

import java.util.Collection;

import org.cibseven.bpm.dmn.engine.DmnDecisionTableResult;
import org.cibseven.bpm.engine.authorization.Permissions;
import org.cibseven.bpm.engine.authorization.Resources;
import org.cibseven.bpm.engine.repository.DecisionDefinition;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.engine.variable.VariableMap;
import org.cibseven.bpm.engine.variable.Variables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * @author Philipp Ossler
 */
public class EvaluateDecisionAuthorizationTest {

  protected static final String DMN_FILE = "org/cibseven/bpm/engine/test/api/dmn/Example.dmn";
  protected static final String DECISION_DEFINITION_KEY = "decision";

  @RegisterExtension
  @Order(1) public ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  @Order(2) public AuthorizationTestRule authRule = new AuthorizationTestRule(engineRule);

  public static Collection<AuthorizationScenario[]> scenarios() {
    return AuthorizationTestRule.asParameters(
      scenario()
        .withoutAuthorizations()
        .failsDueToRequired(
          grant(Resources.DECISION_DEFINITION, DECISION_DEFINITION_KEY, "userId", Permissions.CREATE_INSTANCE)),
      scenario()
        .withAuthorizations(
          grant(Resources.DECISION_DEFINITION, DECISION_DEFINITION_KEY, "userId", Permissions.CREATE_INSTANCE))
        .succeeds(),
      scenario()
        .withAuthorizations(
          grant(Resources.DECISION_DEFINITION, "*", "userId", Permissions.CREATE_INSTANCE))
        .succeeds()
      );
  }

  @BeforeEach
  public void setUp() {
    authRule.createUserAndGroup("userId", "groupId");
  }

  @AfterEach
  public void tearDown() {
    authRule.deleteUsersAndGroups();
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @Deployment(resources = DMN_FILE)
  public void evaluateDecisionById(AuthorizationScenario scenario) {

    // given
    DecisionDefinition decisionDefinition = engineRule.getRepositoryService().createDecisionDefinitionQuery().singleResult();

    // when
    authRule.init(scenario).withUser("userId").bindResource("decisionDefinitionKey", DECISION_DEFINITION_KEY).start();

    DmnDecisionTableResult decisionResult = engineRule.getDecisionService().evaluateDecisionTableById(decisionDefinition.getId(), createVariables());

    // then
    if (authRule.assertScenario(scenario)) {
      assertThatDecisionHasExpectedResult(decisionResult);
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @Deployment(resources = DMN_FILE)
  public void evaluateDecisionByKey(AuthorizationScenario scenario) {

    // given
    DecisionDefinition decisionDefinition = engineRule.getRepositoryService().createDecisionDefinitionQuery().singleResult();

    // when
    authRule.init(scenario).withUser("userId").bindResource("decisionDefinitionKey", DECISION_DEFINITION_KEY).start();

    DmnDecisionTableResult decisionResult = engineRule.getDecisionService().evaluateDecisionTableByKey(decisionDefinition.getKey(), createVariables());

    // then
    if (authRule.assertScenario(scenario)) {
      assertThatDecisionHasExpectedResult(decisionResult);
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @Deployment(resources = DMN_FILE)
  public void evaluateDecisionByKeyAndVersion(AuthorizationScenario scenario) {

    // given
    DecisionDefinition decisionDefinition = engineRule.getRepositoryService().createDecisionDefinitionQuery().singleResult();

    // when
    authRule.init(scenario).withUser("userId").bindResource("decisionDefinitionKey", DECISION_DEFINITION_KEY).start();

    DmnDecisionTableResult decisionResult = engineRule.getDecisionService().evaluateDecisionTableByKeyAndVersion(decisionDefinition.getKey(),
        decisionDefinition.getVersion(), createVariables());

    // then
    if (authRule.assertScenario(scenario)) {
      assertThatDecisionHasExpectedResult(decisionResult);
    }
  }

  protected VariableMap createVariables() {
    return Variables.createVariables().putValue("status", "silver").putValue("sum", 723);
  }

  protected void assertThatDecisionHasExpectedResult(DmnDecisionTableResult decisionResult) {
    assertThat(decisionResult).isNotNull();
    assertThat(decisionResult).hasSize(1);
    String value = decisionResult.getSingleResult().getFirstEntry();
    assertThat(value).isEqualTo("ok");
  }

}
