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

import static org.assertj.core.api.Assertions.assertThat;
import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario.scenario;
import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationSpec.grant;

import java.util.Collection;
import java.util.List;

import org.cibseven.bpm.engine.authorization.Permissions;
import org.cibseven.bpm.engine.authorization.Resources;
import org.cibseven.bpm.engine.externaltask.LockedExternalTask;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationSpec;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Please note that if you want to reuse Rule and other fields you should create abstract class
 * and pack it there.
 *
 * @see HandleExternalTaskAuthorizationTest
 *
 * @author Askar Akhmerov
 */
public class GetErrorDetailsAuthorizationTest {

  protected static final String ERROR_DETAILS = "theDetails";

  protected String deploymentId;
  protected String currentDetails;

  // Add fields for engineRule and authRule to be injected by the extension
  @RegisterExtension
  @Order(1) private ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  @Order(2) private AuthorizationTestRule authRule = new AuthorizationTestRule(engineRule);

  public static Collection<AuthorizationScenario> scenarios() {
    return List.of(
      scenario()
        .withoutAuthorizations()
        .failsDueToRequired(
          grant(Resources.PROCESS_INSTANCE, "processInstanceId", "userId", Permissions.READ),
          grant(Resources.PROCESS_DEFINITION, "oneExternalTaskProcess", "userId", Permissions.READ_INSTANCE)),
      scenario()
        .withAuthorizations(
          grant(Resources.PROCESS_INSTANCE, "processInstanceId", "userId", Permissions.READ))
        .succeeds(),
      scenario()
        .withAuthorizations(
          grant(Resources.PROCESS_INSTANCE, "*", "userId", Permissions.READ))
        .succeeds(),
      scenario()
        .withAuthorizations(
          grant(Resources.PROCESS_DEFINITION, "processDefinitionKey", "userId", Permissions.READ_INSTANCE))
        .succeeds(),
      scenario()
        .withAuthorizations(
          grant(Resources.PROCESS_DEFINITION, "*", "userId", Permissions.READ_INSTANCE))
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
  public void testCompleteExternalTask(AuthorizationScenario scenario) {

    // given
    ProcessInstance processInstance = engineRule.getRuntimeService().startProcessInstanceByKey("oneExternalTaskProcess");
    List<LockedExternalTask> tasks = engineRule.getExternalTaskService()
        .fetchAndLock(5, "workerId")
        .topic("externalTaskTopic", 5000L)
        .execute();

    LockedExternalTask task = tasks.get(0);

    //preconditions method
    engineRule.getExternalTaskService().handleFailure(task.getId(),task.getWorkerId(),"anError",ERROR_DETAILS,1,1000L);

    // when
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("processInstanceId", processInstance.getId())
        .bindResource("processDefinitionKey", "oneExternalTaskProcess")
        .start();

    //execution method
    currentDetails = engineRule.getExternalTaskService().getExternalTaskErrorDetails(task.getId());

    // then
    if (authRule.assertScenario(scenario)) {
      //assertion method
      assertThat(currentDetails).isEqualTo(ERROR_DETAILS);
    }
  }

}