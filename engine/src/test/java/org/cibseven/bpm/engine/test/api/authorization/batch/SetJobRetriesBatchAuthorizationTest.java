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
package org.cibseven.bpm.engine.test.api.authorization.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationSpec.grant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.cibseven.bpm.engine.authorization.BatchPermissions;
import org.cibseven.bpm.engine.authorization.Permissions;
import org.cibseven.bpm.engine.authorization.ProcessDefinitionPermissions;
import org.cibseven.bpm.engine.authorization.ProcessInstancePermissions;
import org.cibseven.bpm.engine.authorization.Resources;
import org.cibseven.bpm.engine.batch.Batch;
import org.cibseven.bpm.engine.batch.history.HistoricBatch;
import org.cibseven.bpm.engine.history.UserOperationLogEntry;
import org.cibseven.bpm.engine.repository.Deployment;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.JobQuery;
import org.cibseven.bpm.engine.runtime.ProcessInstanceQuery;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenarioWithCount;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationTestRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;

/**
 * @author Askar Akhmerov
 */
public class SetJobRetriesBatchAuthorizationTest extends AbstractBatchAuthorizationTest {

  protected static final String DEFINITION_XML = "org/cibseven/bpm/engine/test/api/mgmt/ManagementServiceTest.testGetJobExceptionStacktrace.bpmn20.xml";
  protected static final long BATCH_OPERATIONS = 3;
  protected static final int RETRIES = 5;

  protected void assertRetries(List<String> allJobIds, int i) {
    for (String id : allJobIds) {
      assertThat(managementService.createJobQuery().jobId(id).singleResult().getRetries()).isEqualTo(i);
    }
  }

  protected List<String> getAllJobIds() {
    ArrayList<String> result = new ArrayList<String>();
    for (Job job : managementService.createJobQuery().processDefinitionId(sourceDefinition.getId()).list()) {
      if (job.getProcessInstanceId() != null) {
        result.add(job.getId());
      }
    }
    return result;
  }

  @RegisterExtension
  @Order(1) public AuthorizationTestRule authRule = new AuthorizationTestRule(engineRule);
  @RegisterExtension
  @Order(2) public ProcessEngineTestRule testHelper = new ProcessEngineTestRule(engineRule);

  @Override
  @BeforeEach
  public void deployProcesses() {
    Deployment deploy = testHelper.deploy(DEFINITION_XML);
    sourceDefinition = engineRule.getRepositoryService()
        .createProcessDefinitionQuery().deploymentId(deploy.getId()).singleResult();
    processInstance = engineRule.getRuntimeService().startProcessInstanceById(sourceDefinition.getId());
    processInstance2 = engineRule.getRuntimeService().startProcessInstanceById(sourceDefinition.getId());
  }


  public static Collection<AuthorizationScenario[]> scenarios() {
    return AuthorizationTestRule.asParameters(
        AuthorizationScenarioWithCount.scenario()
            .withCount(3)
            .withAuthorizations(
                grant(Resources.BATCH, "*", "userId", Permissions.CREATE),
                grant(Resources.PROCESS_INSTANCE, "processInstance1", "userId", Permissions.READ, Permissions.UPDATE),
                grant(Resources.PROCESS_INSTANCE, "processInstance2", "userId", Permissions.READ)
            )
            .failsDueToRequired(
                grant(Resources.PROCESS_INSTANCE, "processInstance2", "userId", Permissions.UPDATE),
                grant(Resources.PROCESS_DEFINITION, "exceptionInJobExecution", "userId", Permissions.UPDATE_INSTANCE),
                grant(Resources.PROCESS_INSTANCE, "processInstance2", "userId", ProcessInstancePermissions.RETRY_JOB),
                grant(Resources.PROCESS_DEFINITION, "exceptionInJobExecution", "userId", ProcessDefinitionPermissions.RETRY_JOB)
            ),
        AuthorizationScenarioWithCount.scenario()
            .withCount(5)
            .withAuthorizations(
                grant(Resources.BATCH, "*", "userId", Permissions.CREATE),
                grant(Resources.PROCESS_INSTANCE, "processInstance1", "userId", Permissions.ALL),
                grant(Resources.PROCESS_INSTANCE, "processInstance2", "userId", Permissions.ALL)
            ).succeeds(),
        AuthorizationScenarioWithCount.scenario()
            .withCount(5)
            .withAuthorizations(
                grant(Resources.BATCH, "*", "userId", Permissions.CREATE),
                grant(Resources.PROCESS_DEFINITION, "Process", "userId", Permissions.READ_INSTANCE, Permissions.UPDATE_INSTANCE)
            ).succeeds(),
        AuthorizationScenarioWithCount.scenario()
            .withCount(5)
            .withAuthorizations(
                grant(Resources.BATCH, "*", "userId", BatchPermissions.CREATE_BATCH_SET_JOB_RETRIES),
                grant(Resources.PROCESS_DEFINITION, "Process", "userId", Permissions.READ_INSTANCE, Permissions.UPDATE_INSTANCE)
            ).succeeds()
    );
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testWithTwoInvocationsJobsListBased(AuthorizationScenarioWithCount scenario) {
    engineRule.getProcessEngineConfiguration().setInvocationsPerBatchJob(2);
    setupAndExecuteJobsListBasedTest(scenario);

    // then
    assertScenario(scenario);

    assertRetries(getAllJobIds(), Long.valueOf(scenario.getCount()).intValue());
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testWithTwoInvocationsJobsQueryBased(AuthorizationScenarioWithCount scenario) {
    engineRule.getProcessEngineConfiguration().setInvocationsPerBatchJob(2);
    setupAndExecuteJobsQueryBasedTest(scenario);

    // then
    assertScenario(scenario);

    assertRetries(getAllJobIds(), Long.valueOf(scenario.getCount()).intValue());
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testJobsListBased(AuthorizationScenarioWithCount scenario) {
    setupAndExecuteJobsListBasedTest(scenario);
    // then
    assertScenario(scenario);
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testJobsListQueryBased(AuthorizationScenarioWithCount scenario) {
    setupAndExecuteJobsQueryBasedTest(scenario);
    // then
    assertScenario(scenario);
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testWithTwoInvocationsProcessListBased(AuthorizationScenarioWithCount scenario) {
    engineRule.getProcessEngineConfiguration().setInvocationsPerBatchJob(2);
    setupAndExecuteProcessListBasedTest(scenario);

    // then
    assertScenario(scenario);

    assertRetries(getAllJobIds(), Long.valueOf(scenario.getCount()).intValue());
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testWithTwoInvocationsProcessQueryBased(AuthorizationScenarioWithCount scenario) {
    engineRule.getProcessEngineConfiguration().setInvocationsPerBatchJob(2);
    setupAndExecuteJobsQueryBasedTest(scenario);

    // then
    assertScenario(scenario);

    assertRetries(getAllJobIds(), Long.valueOf(scenario.getCount()).intValue());
  }

  private void setupAndExecuteProcessListBasedTest(AuthorizationScenarioWithCount scenario) {
    //given
    List<String> processInstances = Arrays.asList(new String[]{processInstance.getId(), processInstance2.getId()});
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("Process", sourceDefinition.getKey())
        .bindResource("processInstance1", processInstance.getId())
        .bindResource("processInstance2", processInstance2.getId())
        .start();

    // when
    batch = managementService.setJobRetriesAsync(
        processInstances, (ProcessInstanceQuery) null, RETRIES);

    executeSeedAndBatchJobs();
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testProcessList(AuthorizationScenarioWithCount scenario) {
    setupAndExecuteProcessListBasedTest(scenario);
    // then
    assertScenario(scenario);
  }

  protected void setupAndExecuteJobsListBasedTest(AuthorizationScenarioWithCount scenario) {
    //given
    List<String> allJobIds = getAllJobIds();
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("Process", sourceDefinition.getKey())
        .bindResource("processInstance1", processInstance.getId())
        .bindResource("processInstance2", processInstance2.getId())
        .start();

    // when
    batch = managementService.setJobRetriesAsync(
        allJobIds, RETRIES);

    executeSeedAndBatchJobs();
  }

  protected void setupAndExecuteJobsQueryBasedTest(AuthorizationScenarioWithCount scenario) {
    //given
    JobQuery jobQuery = managementService.createJobQuery();
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("Process", sourceDefinition.getKey())
        .bindResource("processInstance1", processInstance.getId())
        .bindResource("processInstance2", processInstance2.getId())
        .start();

    // when

    batch = managementService.setJobRetriesAsync(
        jobQuery, RETRIES);

    executeSeedAndBatchJobs();
  }

  protected void assertScenario(AuthorizationScenarioWithCount scenario) {
    if (authRule.assertScenario(scenario)) {
      Batch batch = engineRule.getManagementService().createBatchQuery().singleResult();
      assertEquals("userId", batch.getCreateUserId());

      if (testHelper.isHistoryLevelFull()) {
        assertThat(engineRule.getHistoryService().createUserOperationLogQuery().operationType(UserOperationLogEntry.OPERATION_TYPE_SET_JOB_RETRIES).count())
          .isEqualTo(BATCH_OPERATIONS);
        HistoricBatch historicBatch = engineRule.getHistoryService().createHistoricBatchQuery().list().get(0);
        assertEquals("userId", historicBatch.getCreateUserId());
      }
      assertRetries(getAllJobIds(), 5);
    }
  }
}