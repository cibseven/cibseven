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
package org.cibseven.bpm.engine.test.api.authorization.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cibseven.bpm.engine.authorization.Permissions.UPDATE;
import static org.cibseven.bpm.engine.authorization.Permissions.UPDATE_INSTANCE;
import static org.cibseven.bpm.engine.authorization.Resources.PROCESS_DEFINITION;
import static org.cibseven.bpm.engine.authorization.Resources.PROCESS_INSTANCE;
import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario.scenario;
import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationSpec.grant;

import java.util.Collection;

import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.management.JobDefinition;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class UpdateJobAuthorizationTest {

  static final String TIMER_BOUNDARY_PROCESS_KEY = "timerBoundaryProcess";

  @RegisterExtension
  @Order(1) public ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  @Order(2) public AuthorizationTestRule authRule = new AuthorizationTestRule(engineRule);

  ManagementService managementService;
  RuntimeService runtimeService;

  public static Collection<AuthorizationScenario[]> scenarios() {
    return AuthorizationTestRule.asParameters(
      scenario()
        .withoutAuthorizations()
        .failsDueToRequired(
            grant(PROCESS_INSTANCE, "processInstanceId", "userId", UPDATE),
            grant(PROCESS_DEFINITION, TIMER_BOUNDARY_PROCESS_KEY, "userId", UPDATE_INSTANCE)),
      scenario()
        .withAuthorizations(
            grant(PROCESS_INSTANCE, "processInstanceId", "userId", UPDATE))
        .succeeds(),
      scenario()
        .withAuthorizations(
            grant(PROCESS_DEFINITION, TIMER_BOUNDARY_PROCESS_KEY, "userId", UPDATE_INSTANCE))
        .succeeds(),
        scenario()
        .withAuthorizations(
            grant(PROCESS_INSTANCE, "someProcessInstanceId", "userId", UPDATE))
        .failsDueToRequired(
            grant(PROCESS_INSTANCE, "processInstanceId", "userId", UPDATE),
            grant(PROCESS_DEFINITION, TIMER_BOUNDARY_PROCESS_KEY, "userId", UPDATE_INSTANCE))
        .succeeds()
      );
  }

  protected String deploymentId;

  @BeforeEach
  public void setUp() throws Exception {
    managementService = engineRule.getManagementService();
    runtimeService = engineRule.getRuntimeService();
    authRule.createUserAndGroup("userId", "groupId");
  }

  @AfterEach
  public void tearDown() {
    authRule.deleteUsersAndGroups();
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @Deployment(resources = {
      "org/cibseven/bpm/engine/test/api/authorization/timerBoundaryEventProcess.bpmn20.xml" })
  public void shouldExecuteJob(AuthorizationScenario scenario) {
    // given
    String processInstanceId = runtimeService
        .startProcessInstanceByKey(TIMER_BOUNDARY_PROCESS_KEY)
        .getId();
    String jobId = selectJobByProcessInstanceId(processInstanceId).getId();

    // when
    authRule
      .init(scenario)
      .withUser("userId")
      .bindResource("processInstanceId", processInstanceId)
      .start();

    managementService.executeJob(jobId);

    // then
    if (authRule.assertScenario(scenario)) {
      String taskDefinitionKey = engineRule.getTaskService()
          .createTaskQuery()
          .singleResult()
          .getTaskDefinitionKey();
      assertThat(taskDefinitionKey).isEqualTo("taskAfterBoundaryEvent");
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @Deployment(resources = {
      "org/cibseven/bpm/engine/test/api/authorization/timerBoundaryEventProcess.bpmn20.xml" })
  public void shouldSuspendJobById(AuthorizationScenario scenario) {
    // given
    String processInstanceId = runtimeService
        .startProcessInstanceByKey(TIMER_BOUNDARY_PROCESS_KEY)
        .getId();
    String jobId = selectJobByProcessInstanceId(processInstanceId).getId();

    // when
    authRule
      .init(scenario)
      .withUser("userId")
      .bindResource("processInstanceId", processInstanceId)
      .bindResource("someProcessInstanceId", "unexisting")
      .start();

    managementService.suspendJobById(jobId);

    // then
    if (authRule.assertScenario(scenario)) {
      Job job = selectJobById(jobId);
      assertThat(job.isSuspended()).isTrue();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @Deployment(resources = {
      "org/cibseven/bpm/engine/test/api/authorization/timerBoundaryEventProcess.bpmn20.xml" })
  public void shouldActivateJobById(AuthorizationScenario scenario) {
    // given
    String processInstanceId = runtimeService
        .startProcessInstanceByKey(TIMER_BOUNDARY_PROCESS_KEY)
        .getId();
    String jobId = selectJobByProcessInstanceId(processInstanceId).getId();

    // when
    authRule
      .init(scenario)
      .withUser("userId")
      .bindResource("processInstanceId", processInstanceId)
      .bindResource("someProcessInstanceId", "unexisting")
      .start();

    managementService.activateJobById(jobId);

    // then
    if (authRule.assertScenario(scenario)) {
      Job job = selectJobById(jobId);
      assertThat(job.isSuspended()).isFalse();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @Deployment(resources = {
      "org/cibseven/bpm/engine/test/api/authorization/timerBoundaryEventProcess.bpmn20.xml" })
  public void shouldSuspendJobByProcessInstanceId(AuthorizationScenario scenario) {
    // given
    String processInstanceId = runtimeService
        .startProcessInstanceByKey(TIMER_BOUNDARY_PROCESS_KEY)
        .getId();
    String jobId = selectJobByProcessInstanceId(processInstanceId).getId();

    // when
    authRule
    .init(scenario)
    .withUser("userId")
    .bindResource("processInstanceId", processInstanceId)
    .bindResource("someProcessInstanceId", "unexisting")
    .start();

    managementService.suspendJobByProcessInstanceId(processInstanceId);

    // then
    if (authRule.assertScenario(scenario)) {
      Job job = selectJobById(jobId);
      assertThat(job.isSuspended()).isTrue();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @Deployment(resources = {
      "org/cibseven/bpm/engine/test/api/authorization/timerBoundaryEventProcess.bpmn20.xml" })
  public void shouldActivateJobByProcessInstanceId(AuthorizationScenario scenario) {
    // given
    String processInstanceId = runtimeService
        .startProcessInstanceByKey(TIMER_BOUNDARY_PROCESS_KEY)
        .getId();
    String jobId = selectJobByProcessInstanceId(processInstanceId).getId();

    // when
    authRule
    .init(scenario)
    .withUser("userId")
    .bindResource("processInstanceId", processInstanceId)
    .bindResource("someProcessInstanceId", "unexisting")
    .start();

    managementService.activateJobByProcessInstanceId(processInstanceId);

    // then
    if (authRule.assertScenario(scenario)) {
      Job job = selectJobById(jobId);
      assertThat(job.isSuspended()).isFalse();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @Deployment(resources = {
      "org/cibseven/bpm/engine/test/api/authorization/timerBoundaryEventProcess.bpmn20.xml" })
  public void shouldSuspendJobByJobDefinitionId(AuthorizationScenario scenario) {
    // given
    String processInstanceId = runtimeService
        .startProcessInstanceByKey(TIMER_BOUNDARY_PROCESS_KEY)
        .getId();
    String jobDefinitionId = selectJobDefinitionIdByProcessDefinitionKey(
        TIMER_BOUNDARY_PROCESS_KEY);

    // when
    authRule
    .init(scenario)
    .withUser("userId")
    .bindResource("processInstanceId", "*")
    .bindResource("someProcessInstanceId", processInstanceId)
    .start();

    managementService.suspendJobByJobDefinitionId(jobDefinitionId);

    // then
    if (authRule.assertScenario(scenario)) {
      Job job = selectJobByProcessInstanceId(processInstanceId);
      assertThat(job.isSuspended()).isTrue();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @Deployment(resources = {
      "org/cibseven/bpm/engine/test/api/authorization/timerBoundaryEventProcess.bpmn20.xml" })
  public void shouldActivateJobByJobDefinitionId(AuthorizationScenario scenario) {
    // given
    String processInstanceId = runtimeService
        .startProcessInstanceByKey(TIMER_BOUNDARY_PROCESS_KEY)
        .getId();
    String jobDefinitionId = selectJobDefinitionIdByProcessDefinitionKey(
        TIMER_BOUNDARY_PROCESS_KEY);

    // when
    authRule
    .init(scenario)
    .withUser("userId")
    .bindResource("processInstanceId", "*")
    .bindResource("someProcessInstanceId", processInstanceId)
    .start();

    managementService.activateJobByJobDefinitionId(jobDefinitionId);

    // then
    if (authRule.assertScenario(scenario)) {
      Job job = selectJobByProcessInstanceId(processInstanceId);
      assertThat(job.isSuspended()).isFalse();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @Deployment(resources = {
      "org/cibseven/bpm/engine/test/api/authorization/timerBoundaryEventProcess.bpmn20.xml" })
  public void shouldSuspendJobByProcessDefinitionId(AuthorizationScenario scenario) {
    // given
    ProcessInstance processInstance = runtimeService
        .startProcessInstanceByKey(TIMER_BOUNDARY_PROCESS_KEY);

    // when
    authRule
    .init(scenario)
    .withUser("userId")
    .bindResource("processInstanceId", "*")
    .bindResource("someProcessInstanceId", processInstance.getId())
    .start();

    managementService.suspendJobByProcessDefinitionId(processInstance.getProcessDefinitionId());

    // then
    if (authRule.assertScenario(scenario)) {
      Job job = selectJobByProcessInstanceId(processInstance.getId());
      assertThat(job.isSuspended()).isTrue();
    }
  }


  @ParameterizedTest
  @MethodSource("scenarios")
  @Deployment(resources = {
      "org/cibseven/bpm/engine/test/api/authorization/timerBoundaryEventProcess.bpmn20.xml" })
  public void shouldActivateJobByProcessDefinitionId(AuthorizationScenario scenario) {
    // given
    ProcessInstance processInstance = runtimeService
        .startProcessInstanceByKey(TIMER_BOUNDARY_PROCESS_KEY);

    // when
    authRule
    .init(scenario)
    .withUser("userId")
    .bindResource("processInstanceId", "*")
    .bindResource("someProcessInstanceId", processInstance.getId())
    .start();

    managementService.activateJobByProcessDefinitionId(processInstance.getProcessDefinitionId());

    // then
    if (authRule.assertScenario(scenario)) {
      Job job = selectJobByProcessInstanceId(processInstance.getId());
      assertThat(job.isSuspended()).isFalse();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @Deployment(resources = {
      "org/cibseven/bpm/engine/test/api/authorization/timerBoundaryEventProcess.bpmn20.xml" })
  public void shouldSuspendJobByProcessDefinitionKey(AuthorizationScenario scenario) {
    // given
    String processInstanceId = runtimeService
        .startProcessInstanceByKey(TIMER_BOUNDARY_PROCESS_KEY)
        .getId();

    // when
    authRule
    .init(scenario)
    .withUser("userId")
    .bindResource("processInstanceId", "*")
    .bindResource("someProcessInstanceId", processInstanceId)
    .start();

    managementService.suspendJobByProcessDefinitionKey(TIMER_BOUNDARY_PROCESS_KEY);

    // then
    if (authRule.assertScenario(scenario)) {
      Job job = selectJobByProcessInstanceId(processInstanceId);
      assertThat(job.isSuspended()).isTrue();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @Deployment(resources = {
      "org/cibseven/bpm/engine/test/api/authorization/timerBoundaryEventProcess.bpmn20.xml" })
  public void shouldActivateJobByProcessDefinitionKey(AuthorizationScenario scenario) {
    // given
    String processInstanceId = runtimeService
        .startProcessInstanceByKey(TIMER_BOUNDARY_PROCESS_KEY)
        .getId();

    // when
    authRule
    .init(scenario)
    .withUser("userId")
    .bindResource("processInstanceId", "*")
    .bindResource("someProcessInstanceId", processInstanceId)
    .start();

    managementService.activateJobByProcessDefinitionKey(TIMER_BOUNDARY_PROCESS_KEY);

    // then
    if (authRule.assertScenario(scenario)) {
      Job job = selectJobByProcessInstanceId(processInstanceId);
      assertThat(job.isSuspended()).isFalse();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @Deployment(resources = {
      "org/cibseven/bpm/engine/test/api/authorization/timerBoundaryEventProcess.bpmn20.xml" })
  public void shouldSetJobDueDate(AuthorizationScenario scenario) {
    // given
    String processInstanceId = runtimeService
        .startProcessInstanceByKey(TIMER_BOUNDARY_PROCESS_KEY)
        .getId();
    String jobId = selectJobByProcessInstanceId(processInstanceId).getId();

    // when
    authRule
      .init(scenario)
      .withUser("userId")
      .bindResource("processInstanceId", processInstanceId)
      .bindResource("someProcessInstanceId", "unexisting")
      .start();

    managementService.setJobDuedate(jobId, null);

    // then
    if (authRule.assertScenario(scenario)) {
      Job job = selectJobById(jobId);
      assertThat(job.getDuedate()).isNull();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @Deployment(resources = {
      "org/cibseven/bpm/engine/test/api/authorization/timerBoundaryEventProcess.bpmn20.xml" })
  public void shouldDeleteJob(AuthorizationScenario scenario) {
    // given
    String processInstanceId = runtimeService
        .startProcessInstanceByKey(TIMER_BOUNDARY_PROCESS_KEY)
        .getId();
    String jobId = selectJobByProcessInstanceId(processInstanceId).getId();

    // when
    authRule
      .init(scenario)
      .withUser("userId")
      .bindResource("processInstanceId", processInstanceId)
      .bindResource("someProcessInstanceId", "unexisting")
      .start();

    managementService.deleteJob(jobId);

    // then
    if (authRule.assertScenario(scenario)) {
      Job job = selectJobByProcessInstanceId(processInstanceId);
      assertThat(job).isNull();
    }
  }

  // helper /////////////////////////////////////////////////////

  protected Job selectJobByProcessInstanceId(String processInstanceId) {
    Job job = managementService
        .createJobQuery()
        .processInstanceId(processInstanceId)
        .singleResult();
    return job;
  }

  protected Job selectJobById(String jobId) {
    Job job = managementService
        .createJobQuery()
        .jobId(jobId)
        .singleResult();
    return job;
  }

  protected String selectJobDefinitionIdByProcessDefinitionKey(String processDefinitionKey) {
    JobDefinition jobDefinition = managementService
        .createJobDefinitionQuery()
        .processDefinitionKey(processDefinitionKey)
        .singleResult();
    return jobDefinition.getId();
  }

}
