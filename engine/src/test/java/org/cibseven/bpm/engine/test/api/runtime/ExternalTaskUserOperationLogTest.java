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
package org.cibseven.bpm.engine.test.api.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.EntityTypes;
import org.cibseven.bpm.engine.ExternalTaskService;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.batch.Batch;
import org.cibseven.bpm.engine.batch.history.HistoricBatch;
import org.cibseven.bpm.engine.externaltask.ExternalTask;
import org.cibseven.bpm.engine.history.UserOperationLogEntry;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 
 * @author Tobias Metzke
 *
 */
@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
public class ExternalTaskUserOperationLogTest {

  protected ProcessEngineRule rule = new ProvidedProcessEngineRule();
  protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(rule);

  private static String PROCESS_DEFINITION_KEY = "oneExternalTaskProcess";
  private static String PROCESS_DEFINITION_KEY_2 = "twoExternalTaskWithPriorityProcess";

  protected RuntimeService runtimeService;
  protected ExternalTaskService externalTaskService;

  @BeforeEach
  public void initServices() {
    runtimeService = rule.getRuntimeService();
    externalTaskService = rule.getExternalTaskService();
  }
  
  @AfterEach
  public void removeAllRunningAndHistoricBatches() {
    HistoryService historyService = rule.getHistoryService();
    ManagementService managementService = rule.getManagementService();
    for (Batch batch : managementService.createBatchQuery().list()) {
      managementService.deleteBatch(batch.getId(), true);
    }
    // remove history of completed batches
    for (HistoricBatch historicBatch : historyService.createHistoricBatchQuery().list()) {
      historyService.deleteHistoricBatch(historicBatch.getId());
    }
  }

  @Test
  @Deployment(resources = "org/cibseven/bpm/engine/test/api/externaltask/oneExternalTaskProcess.bpmn20.xml")
  public void testSetRetriesLogCreationForOneExternalTaskId() {
    // given
    runtimeService.startProcessInstanceByKey(PROCESS_DEFINITION_KEY);
    rule.getIdentityService().setAuthenticatedUserId("userId");

    // when
    ExternalTask externalTask = externalTaskService.createExternalTaskQuery().singleResult();
    externalTaskService.setRetries(externalTask.getId(), 5);
    rule.getIdentityService().clearAuthentication();
    // then
    List<UserOperationLogEntry> opLogEntries = rule.getHistoryService().createUserOperationLogQuery().list();
    assertEquals(1, opLogEntries.size());

    Map<String, UserOperationLogEntry> entries = asMap(opLogEntries);

    UserOperationLogEntry retriesEntry = entries.get("retries");
    assertNotNull(retriesEntry);
    assertEquals(EntityTypes.EXTERNAL_TASK, retriesEntry.getEntityType());
    assertEquals("SetExternalTaskRetries", retriesEntry.getOperationType());
    assertEquals(externalTask.getId(), retriesEntry.getExternalTaskId());
    assertEquals(externalTask.getProcessInstanceId(), retriesEntry.getProcessInstanceId());
    assertEquals(externalTask.getProcessDefinitionId(), retriesEntry.getProcessDefinitionId());
    assertEquals(externalTask.getProcessDefinitionKey(), retriesEntry.getProcessDefinitionKey());
    assertNull(retriesEntry.getOrgValue());
    assertEquals("5", retriesEntry.getNewValue());
    assertEquals(UserOperationLogEntry.CATEGORY_OPERATOR, retriesEntry.getCategory());
  }

  @Test
  @Deployment(resources = "org/cibseven/bpm/engine/test/api/externaltask/oneExternalTaskProcess.bpmn20.xml")
  public void testSetRetriesLogCreationSync() {
    // given
    runtimeService.startProcessInstanceByKey(PROCESS_DEFINITION_KEY);
    runtimeService.startProcessInstanceByKey(PROCESS_DEFINITION_KEY);
    
    List<ExternalTask> list = externalTaskService.createExternalTaskQuery().list();
    List<String> externalTaskIds = new ArrayList<String>();

    for (ExternalTask task : list) {
      externalTaskIds.add(task.getId());
    }

    // when
    rule.getIdentityService().setAuthenticatedUserId("userId");
    externalTaskService.setRetries(externalTaskIds, 5);
    rule.getIdentityService().clearAuthentication();
    // then
    List<UserOperationLogEntry> opLogEntries = rule.getHistoryService().createUserOperationLogQuery().list();
    assertEquals(3, opLogEntries.size());

    Map<String, UserOperationLogEntry> entries = asMap(opLogEntries);

    UserOperationLogEntry asyncEntry = entries.get("async");
    assertNotNull(asyncEntry);
    assertEquals(EntityTypes.EXTERNAL_TASK, asyncEntry.getEntityType());
    assertEquals("SetExternalTaskRetries", asyncEntry.getOperationType());
    assertNull(asyncEntry.getExternalTaskId());
    assertNull(asyncEntry.getProcessDefinitionId());
    assertNull(asyncEntry.getProcessDefinitionKey());
    assertNull(asyncEntry.getProcessInstanceId());
    assertNull(asyncEntry.getOrgValue());
    assertEquals("false", asyncEntry.getNewValue());
    assertEquals(UserOperationLogEntry.CATEGORY_OPERATOR, asyncEntry.getCategory());

    UserOperationLogEntry numInstancesEntry = entries.get("nrOfInstances");
    assertNotNull(numInstancesEntry);
    assertEquals(EntityTypes.EXTERNAL_TASK, numInstancesEntry.getEntityType());
    assertEquals("SetExternalTaskRetries", numInstancesEntry.getOperationType());
    assertNull(numInstancesEntry.getExternalTaskId());
    assertNull(numInstancesEntry.getProcessDefinitionId());
    assertNull(numInstancesEntry.getProcessDefinitionKey());
    assertNull(numInstancesEntry.getProcessInstanceId());
    assertNull(numInstancesEntry.getOrgValue());
    assertEquals("2", numInstancesEntry.getNewValue());
    assertEquals(UserOperationLogEntry.CATEGORY_OPERATOR, numInstancesEntry.getCategory());

    UserOperationLogEntry retriesEntry = entries.get("retries");
    assertNotNull(retriesEntry);
    assertEquals(EntityTypes.EXTERNAL_TASK, retriesEntry.getEntityType());
    assertEquals("SetExternalTaskRetries", retriesEntry.getOperationType());
    assertNull(retriesEntry.getExternalTaskId());
    assertNull(retriesEntry.getProcessDefinitionId());
    assertNull(retriesEntry.getProcessDefinitionKey());
    assertNull(retriesEntry.getProcessInstanceId());
    assertNull(retriesEntry.getOrgValue());
    assertEquals("5", retriesEntry.getNewValue());
    assertEquals(asyncEntry.getOperationId(), retriesEntry.getOperationId());
    assertEquals(UserOperationLogEntry.CATEGORY_OPERATOR, retriesEntry.getCategory());
  }

  @Test
  @Deployment(resources = "org/cibseven/bpm/engine/test/api/externaltask/oneExternalTaskProcess.bpmn20.xml")
  public void testSetRetriesLogCreationAsync() {
    // given
    runtimeService.startProcessInstanceByKey(PROCESS_DEFINITION_KEY);
    runtimeService.startProcessInstanceByKey(PROCESS_DEFINITION_KEY);

    // when
    rule.getIdentityService().setAuthenticatedUserId("userId");
    externalTaskService.setRetriesAsync(null, externalTaskService.createExternalTaskQuery(), 5);
    rule.getIdentityService().clearAuthentication();
    // then
    List<UserOperationLogEntry> opLogEntries = rule.getHistoryService().createUserOperationLogQuery().list();
    assertEquals(3, opLogEntries.size());

    Map<String, UserOperationLogEntry> entries = asMap(opLogEntries);

    UserOperationLogEntry asyncEntry = entries.get("async");
    assertNotNull(asyncEntry);
    assertEquals(EntityTypes.EXTERNAL_TASK, asyncEntry.getEntityType());
    assertEquals("SetExternalTaskRetries", asyncEntry.getOperationType());
    assertNull(asyncEntry.getExternalTaskId());
    assertNull(asyncEntry.getProcessDefinitionId());
    assertNull(asyncEntry.getProcessDefinitionKey());
    assertNull(asyncEntry.getProcessInstanceId());
    assertNull(asyncEntry.getOrgValue());
    assertEquals("true", asyncEntry.getNewValue());
    assertEquals(UserOperationLogEntry.CATEGORY_OPERATOR, asyncEntry.getCategory());

    UserOperationLogEntry numInstancesEntry = entries.get("nrOfInstances");
    assertNotNull(numInstancesEntry);
    assertEquals(EntityTypes.EXTERNAL_TASK, numInstancesEntry.getEntityType());
    assertEquals("SetExternalTaskRetries", numInstancesEntry.getOperationType());
    assertNull(numInstancesEntry.getExternalTaskId());
    assertNull(numInstancesEntry.getProcessDefinitionId());
    assertNull(numInstancesEntry.getProcessDefinitionKey());
    assertNull(numInstancesEntry.getProcessInstanceId());
    assertNull(numInstancesEntry.getOrgValue());
    assertEquals("2", numInstancesEntry.getNewValue());
    assertEquals(UserOperationLogEntry.CATEGORY_OPERATOR, numInstancesEntry.getCategory());

    UserOperationLogEntry retriesEntry = entries.get("retries");
    assertNotNull(retriesEntry);
    assertEquals(EntityTypes.EXTERNAL_TASK, retriesEntry.getEntityType());
    assertEquals("SetExternalTaskRetries", retriesEntry.getOperationType());
    assertNull(retriesEntry.getExternalTaskId());
    assertNull(retriesEntry.getProcessDefinitionId());
    assertNull(retriesEntry.getProcessDefinitionKey());
    assertNull(retriesEntry.getProcessInstanceId());
    assertNull(retriesEntry.getOrgValue());
    assertEquals("5", retriesEntry.getNewValue());
    assertEquals(asyncEntry.getOperationId(), retriesEntry.getOperationId());
    assertEquals(UserOperationLogEntry.CATEGORY_OPERATOR, retriesEntry.getCategory());
  }
  
  @Test
  @Deployment(resources = "org/cibseven/bpm/engine/test/api/externaltask/externalTaskPriorityExpression.bpmn20.xml")
  public void testSetPriorityLogCreation() {
    // given
    runtimeService.startProcessInstanceByKey(PROCESS_DEFINITION_KEY_2, Collections.<String, Object>singletonMap("priority", 14));
    ExternalTask externalTask = externalTaskService.createExternalTaskQuery().priorityHigherThanOrEquals(1).singleResult();
    
    // when
    rule.getIdentityService().setAuthenticatedUserId("userId");
    externalTaskService.setPriority(externalTask.getId(), 78L);
    rule.getIdentityService().clearAuthentication();
    
    // then
    List<UserOperationLogEntry> opLogEntries = rule.getHistoryService().createUserOperationLogQuery().list();
    assertEquals(1, opLogEntries.size());

    UserOperationLogEntry entry = opLogEntries.get(0);
    assertNotNull(entry);
    assertEquals(EntityTypes.EXTERNAL_TASK, entry.getEntityType());
    assertEquals(UserOperationLogEntry.OPERATION_TYPE_SET_PRIORITY, entry.getOperationType());
    assertEquals(externalTask.getId(), entry.getExternalTaskId());
    assertEquals(externalTask.getProcessInstanceId(), entry.getProcessInstanceId());
    assertEquals(externalTask.getProcessDefinitionId(), entry.getProcessDefinitionId());
    assertEquals(externalTask.getProcessDefinitionKey(), entry.getProcessDefinitionKey());
    assertEquals("priority", entry.getProperty());
    assertEquals("14", entry.getOrgValue());
    assertEquals("78", entry.getNewValue());
    assertEquals(UserOperationLogEntry.CATEGORY_OPERATOR, entry.getCategory());
  }
  
  @Test
  @Deployment(resources = "org/cibseven/bpm/engine/test/api/externaltask/oneExternalTaskProcess.bpmn20.xml")
  public void testUnlockLogCreation() {
    // given
    runtimeService.startProcessInstanceByKey(PROCESS_DEFINITION_KEY);
    ExternalTask externalTask = externalTaskService.createExternalTaskQuery().singleResult();
    externalTaskService.fetchAndLock(1, "aWorker").topic(externalTask.getTopicName(), 3000L).execute();
    
    // when
    rule.getIdentityService().setAuthenticatedUserId("userId");
    externalTaskService.unlock(externalTask.getId());
    rule.getIdentityService().clearAuthentication();
    
    // then
    List<UserOperationLogEntry> opLogEntries = rule.getHistoryService().createUserOperationLogQuery().list();
    assertEquals(1, opLogEntries.size());

    UserOperationLogEntry entry = opLogEntries.get(0);
    assertNotNull(entry);
    assertEquals(EntityTypes.EXTERNAL_TASK, entry.getEntityType());
    assertEquals(UserOperationLogEntry.OPERATION_TYPE_UNLOCK, entry.getOperationType());
    assertEquals(externalTask.getId(), entry.getExternalTaskId());
    assertEquals(externalTask.getProcessInstanceId(), entry.getProcessInstanceId());
    assertEquals(externalTask.getProcessDefinitionId(), entry.getProcessDefinitionId());
    assertEquals(externalTask.getProcessDefinitionKey(), entry.getProcessDefinitionKey());
    assertNull(entry.getProperty());
    assertNull(entry.getOrgValue());
    assertNull(entry.getNewValue());
    assertEquals(UserOperationLogEntry.CATEGORY_OPERATOR, entry.getCategory());
  }

  protected Map<String, UserOperationLogEntry> asMap(List<UserOperationLogEntry> logEntries) {
    Map<String, UserOperationLogEntry> map = new HashMap<String, UserOperationLogEntry>();

    for (UserOperationLogEntry entry : logEntries) {

      UserOperationLogEntry previousValue = map.put(entry.getProperty(), entry);
      if (previousValue != null) {
        fail("expected only entry for every property");
      }
    }

    return map;
  }
}