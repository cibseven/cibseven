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
package org.cibseven.bpm.engine.test.api.runtime.migration.batch;

import static org.cibseven.bpm.engine.test.api.runtime.migration.ModifiableBpmnModelInstance.modify;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.EntityTypes;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.batch.Batch;
import org.cibseven.bpm.engine.history.UserOperationLogEntry;
import org.cibseven.bpm.engine.migration.MigrationPlan;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.api.runtime.migration.MigrationTestRule;
import org.cibseven.bpm.engine.test.api.runtime.migration.models.ProcessModels;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.Test;


/**
 * @author Thorben Lindhauer
 *
 */
@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
public class BatchMigrationUserOperationLogTest {

  public static final String USER_ID = "userId";

  protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  protected MigrationTestRule migrationRule = new MigrationTestRule(engineRule);

  protected BatchMigrationHelper batchHelper = new BatchMigrationHelper(engineRule, migrationRule);

//  @Rule
//  public RuleChain ruleChain = RuleChain.outerRule(engineRule).around(migrationRule);

  @AfterEach
  public void removeBatches() {
    batchHelper.removeAllRunningAndHistoricBatches();
  }

  @Test
  public void testLogCreation() {
    // given
    ProcessDefinition sourceProcessDefinition = migrationRule.deployAndGetDefinition(ProcessModels.ONE_TASK_PROCESS);
    ProcessDefinition targetProcessDefinition = migrationRule.deployAndGetDefinition(
        modify(ProcessModels.ONE_TASK_PROCESS).changeElementId(ProcessModels.PROCESS_KEY, "new" + ProcessModels.PROCESS_KEY));

    MigrationPlan migrationPlan = engineRule.getRuntimeService().createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapEqualActivities()
      .build();

    ProcessInstance processInstance = engineRule.getRuntimeService().startProcessInstanceById(sourceProcessDefinition.getId());

    // when
    engineRule.getIdentityService().setAuthenticatedUserId(USER_ID);
    engineRule.getRuntimeService()
      .newMigration(migrationPlan)
      .processInstanceIds(Arrays.asList(processInstance.getId()))
      .executeAsync();
    engineRule.getIdentityService().clearAuthentication();

    // then
    List<UserOperationLogEntry> opLogEntries = engineRule.getHistoryService().createUserOperationLogQuery().list();
    Assertions.assertEquals(3, opLogEntries.size());

    Map<String, UserOperationLogEntry> entries = asMap(opLogEntries);

    UserOperationLogEntry procDefEntry = entries.get("processDefinitionId");
    Assertions.assertNotNull(procDefEntry);
    Assertions.assertEquals("ProcessInstance", procDefEntry.getEntityType());
    Assertions.assertEquals("Migrate", procDefEntry.getOperationType());
    Assertions.assertEquals(sourceProcessDefinition.getId(), procDefEntry.getProcessDefinitionId());
    Assertions.assertEquals(sourceProcessDefinition.getKey(), procDefEntry.getProcessDefinitionKey());
    Assertions.assertNull(procDefEntry.getProcessInstanceId());
    Assertions.assertEquals(sourceProcessDefinition.getId(), procDefEntry.getOrgValue());
    Assertions.assertEquals(targetProcessDefinition.getId(), procDefEntry.getNewValue());
    Assertions.assertEquals(UserOperationLogEntry.CATEGORY_OPERATOR, procDefEntry.getCategory());

    UserOperationLogEntry asyncEntry = entries.get("async");
    Assertions.assertNotNull(asyncEntry);
    Assertions.assertEquals("ProcessInstance", asyncEntry.getEntityType());
    Assertions.assertEquals("Migrate", asyncEntry.getOperationType());
    Assertions.assertEquals(sourceProcessDefinition.getId(), asyncEntry.getProcessDefinitionId());
    Assertions.assertEquals(sourceProcessDefinition.getKey(), asyncEntry.getProcessDefinitionKey());
    Assertions.assertNull(asyncEntry.getProcessInstanceId());
    Assertions.assertNull(asyncEntry.getOrgValue());
    Assertions.assertEquals("true", asyncEntry.getNewValue());
    Assertions.assertEquals(UserOperationLogEntry.CATEGORY_OPERATOR, asyncEntry.getCategory());

    UserOperationLogEntry numInstancesEntry = entries.get("nrOfInstances");
    Assertions.assertNotNull(numInstancesEntry);
    Assertions.assertEquals("ProcessInstance", numInstancesEntry.getEntityType());
    Assertions.assertEquals("Migrate", numInstancesEntry.getOperationType());
    Assertions.assertEquals(sourceProcessDefinition.getId(), numInstancesEntry.getProcessDefinitionId());
    Assertions.assertEquals(sourceProcessDefinition.getKey(), numInstancesEntry.getProcessDefinitionKey());
    Assertions.assertNull(numInstancesEntry.getProcessInstanceId());
    Assertions.assertNull(numInstancesEntry.getOrgValue());
    Assertions.assertEquals("1", numInstancesEntry.getNewValue());
    Assertions.assertEquals(UserOperationLogEntry.CATEGORY_OPERATOR, numInstancesEntry.getCategory());

    Assertions.assertEquals(procDefEntry.getOperationId(), asyncEntry.getOperationId());
    Assertions.assertEquals(asyncEntry.getOperationId(), numInstancesEntry.getOperationId());
  }

  @Test
  public void testNoCreationOnSyncBatchJobExecution() {
    // given
    ProcessDefinition sourceProcessDefinition = migrationRule.deployAndGetDefinition(ProcessModels.ONE_TASK_PROCESS);
    ProcessDefinition targetProcessDefinition = migrationRule.deployAndGetDefinition(
        modify(ProcessModels.ONE_TASK_PROCESS).changeElementId(ProcessModels.PROCESS_KEY, "new" + ProcessModels.PROCESS_KEY));

    MigrationPlan migrationPlan = engineRule.getRuntimeService().createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapEqualActivities()
      .build();

    ProcessInstance processInstance = engineRule.getRuntimeService().startProcessInstanceById(sourceProcessDefinition.getId());
    Batch batch = engineRule.getRuntimeService()
      .newMigration(migrationPlan)
      .processInstanceIds(Arrays.asList(processInstance.getId()))
      .executeAsync();
    batchHelper.completeSeedJobs(batch);

    // when
    engineRule.getIdentityService().setAuthenticatedUserId(USER_ID);
    batchHelper.executeJobs(batch);
    engineRule.getIdentityService().clearAuthentication();

    // then
    Assertions.assertEquals(0, engineRule.getHistoryService().createUserOperationLogQuery().entityType(EntityTypes.PROCESS_INSTANCE).count());
  }

  @Test
  public void testNoCreationOnJobExecutorBatchJobExecution() {
    // given
    ProcessDefinition sourceProcessDefinition = migrationRule.deployAndGetDefinition(ProcessModels.ONE_TASK_PROCESS);
    ProcessDefinition targetProcessDefinition = migrationRule.deployAndGetDefinition(
        modify(ProcessModels.ONE_TASK_PROCESS).changeElementId(ProcessModels.PROCESS_KEY, "new" + ProcessModels.PROCESS_KEY));

    MigrationPlan migrationPlan = engineRule.getRuntimeService().createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapEqualActivities()
      .build();

    ProcessInstance processInstance = engineRule.getRuntimeService().startProcessInstanceById(sourceProcessDefinition.getId());
    engineRule.getRuntimeService()
      .newMigration(migrationPlan)
      .processInstanceIds(Arrays.asList(processInstance.getId()))
      .executeAsync();

    // when
    migrationRule.waitForJobExecutorToProcessAllJobs(5000L);

    // then
    Assertions.assertEquals(0, engineRule.getHistoryService().createUserOperationLogQuery().count());
  }

  protected Map<String, UserOperationLogEntry> asMap(List<UserOperationLogEntry> logEntries) {
    Map<String, UserOperationLogEntry> map = new HashMap<String, UserOperationLogEntry>();

    for (UserOperationLogEntry entry : logEntries) {

      UserOperationLogEntry previousValue = map.put(entry.getProperty(), entry);
      if (previousValue != null) {
        Assertions.fail("expected only entry for every property");
      }
    }

    return map;
  }
}
