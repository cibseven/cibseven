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
package org.cibseven.bpm.engine.test.api.runtime.migration.history;

import static org.cibseven.bpm.engine.test.api.runtime.migration.ModifiableBpmnModelInstance.modify;

import java.util.Arrays;

import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.history.HistoricIncident;
import org.cibseven.bpm.engine.management.JobDefinition;
import org.cibseven.bpm.engine.migration.MigrationPlan;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.runtime.ActivityInstance;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.api.runtime.migration.MigrationTestRule;
import org.cibseven.bpm.engine.test.api.runtime.migration.models.AsyncProcessModels;
import org.cibseven.bpm.engine.test.api.runtime.migration.models.ProcessModels;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;


/**
 * @author Thorben Lindhauer
 *
 */
@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
public class MigrationHistoricIncidentTest {
  @RegisterExtension
  protected ProcessEngineRule rule = new ProvidedProcessEngineRule();
  @RegisterExtension
  protected MigrationTestRule testHelper = new MigrationTestRule(rule);

  protected RuntimeService runtimeService;
  protected HistoryService historyService;
  protected ManagementService managementService;
  protected RepositoryService repositoryService;

  @BeforeEach
  public void initServices() {
    historyService = rule.getHistoryService();
    runtimeService = rule.getRuntimeService();
    managementService = rule.getManagementService();
    repositoryService = rule.getRepositoryService();
  }

  @Test
  public void testMigrateHistoricIncident() {
    // given
    ProcessDefinition sourceProcess = testHelper.deployAndGetDefinition(AsyncProcessModels.ASYNC_BEFORE_USER_TASK_PROCESS);
    ProcessDefinition targetProcess = testHelper.deployAndGetDefinition(modify(AsyncProcessModels.ASYNC_BEFORE_USER_TASK_PROCESS)
      .changeElementId(ProcessModels.PROCESS_KEY, "new" + ProcessModels.PROCESS_KEY)
      .changeElementId("userTask", "newUserTask"));

    JobDefinition targetJobDefinition =
        managementService
          .createJobDefinitionQuery()
          .processDefinitionId(targetProcess.getId())
          .singleResult();

    MigrationPlan migrationPlan = runtimeService.createMigrationPlan(sourceProcess.getId(), targetProcess.getId())
      .mapActivities("userTask", "newUserTask")
      .build();

    ProcessInstance processInstance = runtimeService.startProcessInstanceById(sourceProcess.getId());

    Job job = managementService.createJobQuery().singleResult();
    managementService.setJobRetries(job.getId(), 0);

    HistoricIncident incidentBeforeMigration = historyService.createHistoricIncidentQuery().singleResult();

    // when
    runtimeService.newMigration(migrationPlan)
      .processInstanceIds(Arrays.asList(processInstance.getId()))
      .execute();

    // then
    HistoricIncident historicIncident = historyService.createHistoricIncidentQuery().singleResult();
    Assertions.assertNotNull(historicIncident);

    Assertions.assertEquals("newUserTask", historicIncident.getActivityId());
    Assertions.assertEquals(targetJobDefinition.getId(), historicIncident.getJobDefinitionId());
    Assertions.assertEquals(targetProcess.getId(), historicIncident.getProcessDefinitionId());
    Assertions.assertEquals(targetProcess.getKey(), historicIncident.getProcessDefinitionKey());
    Assertions.assertEquals(processInstance.getId(), historicIncident.getExecutionId());

    // and other properties have not changed
    Assertions.assertEquals(incidentBeforeMigration.getCreateTime(), historicIncident.getCreateTime());
    Assertions.assertEquals(incidentBeforeMigration.getProcessInstanceId(), historicIncident.getProcessInstanceId());

  }

  @Test
  public void testMigrateHistoricIncidentAddScope() {
    // given
    ProcessDefinition sourceProcess = testHelper.deployAndGetDefinition(AsyncProcessModels.ASYNC_BEFORE_USER_TASK_PROCESS);
    ProcessDefinition targetProcess = testHelper.deployAndGetDefinition(AsyncProcessModels.ASYNC_BEFORE_SUBPROCESS_USER_TASK_PROCESS);

    MigrationPlan migrationPlan = runtimeService.createMigrationPlan(sourceProcess.getId(), targetProcess.getId())
      .mapActivities("userTask", "userTask")
      .build();

    ProcessInstance processInstance = runtimeService.startProcessInstanceById(sourceProcess.getId());

    Job job = managementService.createJobQuery().singleResult();
    managementService.setJobRetries(job.getId(), 0);

    // when
    runtimeService.newMigration(migrationPlan)
      .processInstanceIds(Arrays.asList(processInstance.getId()))
      .execute();

    // then
    ActivityInstance activityInstance = runtimeService.getActivityInstance(processInstance.getId());

    HistoricIncident historicIncident = historyService.createHistoricIncidentQuery().singleResult();
    Assertions.assertNotNull(historicIncident);
    Assertions.assertEquals(
        activityInstance.getTransitionInstances("userTask")[0].getExecutionId(),
        historicIncident.getExecutionId());
  }
}
