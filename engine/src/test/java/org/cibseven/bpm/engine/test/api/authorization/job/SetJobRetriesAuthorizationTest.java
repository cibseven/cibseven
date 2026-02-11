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
import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationSpec.revoke;

import java.util.Collection;

import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.authorization.ProcessDefinitionPermissions;
import org.cibseven.bpm.engine.authorization.ProcessInstancePermissions;
import org.cibseven.bpm.engine.management.JobDefinition;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationTestRule;
import org.cibseven.bpm.engine.test.util.AuthorizationRuleExtension;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
@ExtendWith(AuthorizationRuleExtension.class)
public class SetJobRetriesAuthorizationTest {

  static final String TIMER_BOUNDARY_PROCESS_KEY = "timerBoundaryProcess";

  public ProcessEngineRule engineRule ;
  public AuthorizationTestRule authRule;

//  @Rule
//  public RuleChain chain = RuleChain.outerRule(engineRule).around(authRule);

  ManagementService managementService;

  @Parameter
  public AuthorizationScenario scenario;

  @Parameters(name = "Scenario {index}")
  public static Collection<AuthorizationScenario[]> scenarios() {
    return AuthorizationTestRule.asParameters(
      scenario()
        .withoutAuthorizations()
        .failsDueToRequired(
            grant(PROCESS_INSTANCE, "anyProcessInstanceId", "userId", ProcessInstancePermissions.RETRY_JOB),
            grant(PROCESS_DEFINITION, TIMER_BOUNDARY_PROCESS_KEY, "userId", ProcessDefinitionPermissions.RETRY_JOB),
            grant(PROCESS_INSTANCE, "anyProcessInstanceId", "userId", UPDATE),
            grant(PROCESS_DEFINITION, TIMER_BOUNDARY_PROCESS_KEY, "userId", UPDATE_INSTANCE)),
      scenario()
      .withAuthorizations(
            grant(PROCESS_DEFINITION, TIMER_BOUNDARY_PROCESS_KEY, "userId", UPDATE),
            revoke(PROCESS_DEFINITION, TIMER_BOUNDARY_PROCESS_KEY, "userId", ProcessDefinitionPermissions.RETRY_JOB))
      .failsDueToRequired(
            grant(PROCESS_INSTANCE, "anyProcessInstanceId", "userId", ProcessInstancePermissions.RETRY_JOB),
            grant(PROCESS_DEFINITION, TIMER_BOUNDARY_PROCESS_KEY, "userId", ProcessDefinitionPermissions.RETRY_JOB),
            grant(PROCESS_INSTANCE, "anyProcessInstanceId", "userId", UPDATE),
            grant(PROCESS_DEFINITION, TIMER_BOUNDARY_PROCESS_KEY, "userId", UPDATE_INSTANCE)),
      scenario()
        .withAuthorizations(
            grant(PROCESS_INSTANCE, "specProcessInstanceId", "userId", UPDATE, ProcessInstancePermissions.RETRY_JOB))
        .failsDueToRequired(
            grant(PROCESS_INSTANCE, "anyProcessInstanceId", "userId", ProcessInstancePermissions.RETRY_JOB),
            grant(PROCESS_DEFINITION, TIMER_BOUNDARY_PROCESS_KEY, "userId", ProcessDefinitionPermissions.RETRY_JOB),
            grant(PROCESS_INSTANCE, "anyProcessInstanceId", "userId", UPDATE),
            grant(PROCESS_DEFINITION, TIMER_BOUNDARY_PROCESS_KEY, "userId", UPDATE_INSTANCE)),
      scenario()
        .withAuthorizations(
            grant(PROCESS_INSTANCE, "anyProcessInstanceId", "userId", ProcessInstancePermissions.RETRY_JOB))
        .succeeds(),
      scenario()
        .withAuthorizations(
            grant(PROCESS_DEFINITION, TIMER_BOUNDARY_PROCESS_KEY, "userId", ProcessDefinitionPermissions.RETRY_JOB))
        .succeeds(),
      scenario()
        .withAuthorizations(
            grant(PROCESS_INSTANCE, "anyProcessInstanceId", "userId", UPDATE))
        .succeeds(),
      scenario()
        .withAuthorizations(
            grant(PROCESS_DEFINITION, TIMER_BOUNDARY_PROCESS_KEY, "userId", UPDATE_INSTANCE))
        .succeeds(),
      scenario()
        .withAuthorizations(
            grant(PROCESS_DEFINITION, "*", "userId", UPDATE_INSTANCE))
        .succeeds(),
      scenario()
        .withAuthorizations(
            grant(PROCESS_DEFINITION, "*", "userId", ProcessDefinitionPermissions.RETRY_JOB))
        .succeeds()
      );
  }

  protected String deploymentId;

  @BeforeEach
  public void setUp() throws Exception {
    managementService = engineRule.getManagementService();
    authRule.createUserAndGroup("userId", "groupId");
  }

  @AfterEach
  public void tearDown() {
    authRule.deleteUsersAndGroups();
  }

  @Test
  @Deployment(resources = {
      "org/cibseven/bpm/engine/test/api/authorization/timerBoundaryEventProcess.bpmn20.xml" })
  public void shouldSetJobRetriesByJobDefinitionId() {
    // given
    String processInstanceId = engineRule.getRuntimeService()
        .startProcessInstanceByKey(TIMER_BOUNDARY_PROCESS_KEY)
        .getId();
    JobDefinition jobDefinition = selectJobDefinitionByProcessDefinitionKey(
        TIMER_BOUNDARY_PROCESS_KEY);
    String jobId = selectJobByProcessInstanceId(processInstanceId).getId();
    managementService.setJobRetries(jobId, 0);

    // when
    authRule
      .init(scenario)
      .withUser("userId")
      .bindResource("anyProcessInstanceId", "*")
      .bindResource("specProcessInstanceId", processInstanceId)
      .start();

    managementService.setJobRetriesByJobDefinitionId(jobDefinition.getId(), 1);

    // then
    if (authRule.assertScenario(scenario)) {
      Job job = selectJobById(jobId);
      assertThat(job).isNotNull();
      assertThat(job.getRetries()).isEqualTo(1);
    }

  }

  @Test
  @Deployment(resources = {
      "org/cibseven/bpm/engine/test/api/authorization/timerBoundaryEventProcess.bpmn20.xml" })
  public void shouldSetJobRetries() {
    // given
    String processInstanceId = engineRule.getRuntimeService()
        .startProcessInstanceByKey(TIMER_BOUNDARY_PROCESS_KEY)
        .getId();
    String jobId = selectJobByProcessInstanceId(processInstanceId).getId();

    // when
    authRule
      .init(scenario)
      .withUser("userId")
      .bindResource("anyProcessInstanceId", processInstanceId)
      .bindResource("specProcessInstanceId", "unexisting")
      .start();

    managementService.setJobRetries(jobId, 1);

    // then
    if (authRule.assertScenario(scenario)) {
      Job job = selectJobById(jobId);
      assertThat(job).isNotNull();
      assertThat(job.getRetries()).isEqualTo(1);
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

  protected JobDefinition selectJobDefinitionByProcessDefinitionKey(String processDefinitionKey) {
    JobDefinition jobDefinition = managementService
        .createJobDefinitionQuery()
        .processDefinitionKey(processDefinitionKey)
        .singleResult();
    return jobDefinition;
  }

}
