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
package org.cibseven.bpm.engine.test.api.authorization.migration;

import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario.scenario;
import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationSpec.grant;
import static org.cibseven.bpm.engine.test.api.runtime.migration.ModifiableBpmnModelInstance.modify;

import java.util.Collection;

import org.cibseven.bpm.engine.authorization.Permissions;
import org.cibseven.bpm.engine.authorization.Resources;
import org.cibseven.bpm.engine.migration.MigrationPlan;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationTestRule;
import org.cibseven.bpm.engine.test.api.runtime.migration.models.ProcessModels;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * @author Thorben Lindhauer
 *
 */
public class CreateMigrationPlanAuthorizationTest {

  @RegisterExtension
  @Order(1) protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  @Order(2) protected AuthorizationTestRule authRule = new AuthorizationTestRule(engineRule);
  @RegisterExtension
  @Order(3) protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  public static Collection<AuthorizationScenario[]> scenarios() {
    return AuthorizationTestRule.asParameters(
      scenario()
        .withoutAuthorizations()
        .failsDueToRequired(
          grant(Resources.PROCESS_DEFINITION, "sourceDefinitionKey", "userId", Permissions.READ)),
      scenario()
        .withAuthorizations(
          grant(Resources.PROCESS_DEFINITION, "sourceDefinitionKey", "userId", Permissions.READ))
        .failsDueToRequired(
          grant(Resources.PROCESS_DEFINITION, "targetDefinitionKey", "userId", Permissions.READ)),
      scenario()
        .withAuthorizations(
          grant(Resources.PROCESS_DEFINITION, "targetDefinitionKey", "userId", Permissions.READ))
        .failsDueToRequired(
            grant(Resources.PROCESS_DEFINITION, "sourceDefinitionKey", "userId", Permissions.READ)),
      scenario()
        .withAuthorizations(
          grant(Resources.PROCESS_DEFINITION, "sourceDefinitionKey", "userId", Permissions.READ),
          grant(Resources.PROCESS_DEFINITION, "targetDefinitionKey", "userId", Permissions.READ))
        .succeeds(),
      scenario()
        .withAuthorizations(
          grant(Resources.PROCESS_DEFINITION, "*", "userId", Permissions.READ))
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
  @Deployment(resources = "org/cibseven/bpm/engine/test/api/authorization/oneIncidentProcess.bpmn20.xml")
  public void testCreate(AuthorizationScenario scenario) {

    // given
    ProcessDefinition sourceDefinition = testRule.deployAndGetDefinition(ProcessModels.ONE_TASK_PROCESS);
    ProcessDefinition targetDefinition = testRule.deployAndGetDefinition(modify(ProcessModels.ONE_TASK_PROCESS)
        .changeElementId(ProcessModels.PROCESS_KEY, "new" + ProcessModels.PROCESS_KEY));

    // when
    authRule
      .init(scenario)
      .withUser("userId")
      .bindResource("sourceDefinitionKey", sourceDefinition.getKey())
      .bindResource("targetDefinitionKey", targetDefinition.getKey())
      .start();

    MigrationPlan migrationPlan = engineRule.getRuntimeService()
      .createMigrationPlan(sourceDefinition.getId(), targetDefinition.getId())
      .mapEqualActivities()
      .build();

    // then
    if (authRule.assertScenario(scenario)) {
      Assertions.assertNotNull(migrationPlan);
    }

  }
}
