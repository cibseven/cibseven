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
package org.cibseven.bpm.engine.test.api.authorization.externaltask;

import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario.scenario;
import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationSpec.grant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.cibseven.bpm.engine.authorization.Permissions;
import org.cibseven.bpm.engine.authorization.Resources;
import org.cibseven.bpm.engine.externaltask.ExternalTask;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class SetExternalTasksRetriesAuthorizationTest {

  @RegisterExtension
  @Order(1) public ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  @Order(2) public AuthorizationTestRule authRule = new AuthorizationTestRule(engineRule);

  public static Collection<AuthorizationScenario[]> scenarios() {
    return AuthorizationTestRule.asParameters(
      scenario()
      .withAuthorizations(
          grant(Resources.PROCESS_INSTANCE, "processInstanceId2", "userId", Permissions.UPDATE))
        .withoutAuthorizations()
        .failsDueToRequired(
          grant(Resources.PROCESS_INSTANCE, "processInstanceId1", "userId", Permissions.UPDATE),
          grant(Resources.PROCESS_DEFINITION, "oneExternalTaskProcess", "userId", Permissions.UPDATE_INSTANCE)),
      scenario()
        .withAuthorizations(
          grant(Resources.PROCESS_INSTANCE, "processInstanceId1", "userId", Permissions.UPDATE),
          grant(Resources.PROCESS_INSTANCE, "processInstanceId2", "userId", Permissions.UPDATE))
        .succeeds(),
      scenario()
        .withAuthorizations(
          grant(Resources.PROCESS_INSTANCE, "*", "userId", Permissions.UPDATE))
        .succeeds(),
      scenario()
        .withAuthorizations(
          grant(Resources.PROCESS_DEFINITION, "processDefinitionKey", "userId", Permissions.UPDATE_INSTANCE))
        .succeeds(),
      scenario()
        .withAuthorizations(
          grant(Resources.PROCESS_DEFINITION, "*", "userId", Permissions.UPDATE_INSTANCE))
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
  @Deployment(resources = "org/cibseven/bpm/engine/test/api/externaltask/oneExternalTaskProcess.bpmn20.xml")
  public void testSetRetrieSync(AuthorizationScenario scenario) {

    // given
    ProcessInstance processInstance1 = engineRule.getRuntimeService().startProcessInstanceByKey("oneExternalTaskProcess");
    ProcessInstance processInstance2 = engineRule.getRuntimeService().startProcessInstanceByKey("oneExternalTaskProcess");
    List<ExternalTask> tasks = engineRule.getExternalTaskService().createExternalTaskQuery().list();

    // when
    authRule
      .init(scenario)
      .withUser("userId")
      .bindResource("processInstanceId1", processInstance1.getId())
      .bindResource("processInstanceId2", processInstance2.getId())
      .bindResource("processDefinitionKey", "oneExternalTaskProcess")
      .start();
    
    ArrayList<String> externalTaskIds = new ArrayList<String>();
    externalTaskIds.add(tasks.get(0).getId());
    externalTaskIds.add(tasks.get(1).getId());
    
    engineRule.getExternalTaskService().setRetries(externalTaskIds, 5);

    // then
    if (authRule.assertScenario(scenario)) {
      tasks = engineRule.getExternalTaskService().createExternalTaskQuery().list();
      Assertions.assertEquals(5, (int) tasks.get(0).getRetries());
      Assertions.assertEquals(5, (int) tasks.get(1).getRetries());
    }

  }
}