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

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.EntityTypes;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.batch.Batch;
import org.cibseven.bpm.engine.history.UserOperationLogEntry;
import org.cibseven.bpm.engine.impl.util.ClockUtil;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;


/**
 *
 * @author Anna Pazola
 *
 */
@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
public class RestartProcessInstanceUserOperationLogTest {

  protected ProcessEngineRule rule = new ProvidedProcessEngineRule();
  protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(rule);
  protected BatchRestartHelper helper = new BatchRestartHelper(rule);

//  @Rule
//  public RuleChain ruleChain = RuleChain.outerRule(rule).around(testRule);

  protected RuntimeService runtimeService;
  protected BpmnModelInstance instance;
  protected static final Date START_DATE = new Date(1457326800000L);

  @BeforeEach
  public void initServices() {
    runtimeService = rule.getRuntimeService();
  }

  @BeforeEach
  public void setClock() {
    ClockUtil.setCurrentTime(START_DATE);
  }

  @AfterEach
  public void resetEngineConfig() {
    rule.getProcessEngineConfiguration()
        .setRestrictUserOperationLogToAuthenticatedUsers(true);
  }

  @BeforeEach
  public void createBpmnModelInstance() {
    this.instance = Bpmn.createExecutableProcess("process1")
        .startEvent("start")
        .userTask("user1")
        .sequenceFlowId("seq")
        .userTask("user2")
        .endEvent("end")
        .done();
  }

  @AfterEach
  public void resetClock() {
    ClockUtil.reset();
  }

  @AfterEach
  public void removeBatches() {
    helper.removeAllRunningAndHistoricBatches();
  }
  @Test
  public void testLogCreationAsync() {
    // given
    ProcessDefinition processDefinition = testRule.deployAndGetDefinition(instance);
    rule.getIdentityService().setAuthenticatedUserId("userId");

    ProcessInstance processInstance1 = runtimeService.startProcessInstanceByKey("process1");
    ProcessInstance processInstance2 = runtimeService.startProcessInstanceByKey("process1");

    runtimeService.deleteProcessInstance(processInstance1.getId(), "test");
    runtimeService.deleteProcessInstance(processInstance2.getId(), "test");

    // when
    runtimeService.restartProcessInstances(processDefinition.getId()).startAfterActivity("user1").processInstanceIds(processInstance1.getId(), processInstance2.getId()).executeAsync();
    rule.getIdentityService().clearAuthentication();

    // then
    List<UserOperationLogEntry> opLogEntries = rule.getHistoryService().createUserOperationLogQuery().operationType("RestartProcessInstance").list();
    Assertions.assertEquals(2, opLogEntries.size());

    Map<String, UserOperationLogEntry> entries = asMap(opLogEntries);


    UserOperationLogEntry asyncEntry = entries.get("async");
    Assertions.assertNotNull(asyncEntry);
    Assertions.assertEquals("ProcessInstance", asyncEntry.getEntityType());
    Assertions.assertEquals("RestartProcessInstance", asyncEntry.getOperationType());
    Assertions.assertEquals(processDefinition.getId(), asyncEntry.getProcessDefinitionId());
    Assertions.assertEquals(processDefinition.getKey(), asyncEntry.getProcessDefinitionKey());
    Assertions.assertNull(asyncEntry.getProcessInstanceId());
    Assertions.assertNull(asyncEntry.getOrgValue());
    Assertions.assertEquals("true", asyncEntry.getNewValue());
    Assertions.assertEquals(UserOperationLogEntry.CATEGORY_OPERATOR, asyncEntry.getCategory());

    UserOperationLogEntry numInstancesEntry = entries.get("nrOfInstances");
    Assertions.assertNotNull(numInstancesEntry);
    Assertions.assertEquals("ProcessInstance", numInstancesEntry.getEntityType());
    Assertions.assertEquals("RestartProcessInstance", numInstancesEntry.getOperationType());
    Assertions.assertEquals(processDefinition.getId(), numInstancesEntry.getProcessDefinitionId());
    Assertions.assertEquals(processDefinition.getKey(), numInstancesEntry.getProcessDefinitionKey());
    Assertions.assertNull(numInstancesEntry.getProcessInstanceId());
    Assertions.assertNull(numInstancesEntry.getOrgValue());
    Assertions.assertEquals("2", numInstancesEntry.getNewValue());
    Assertions.assertEquals(UserOperationLogEntry.CATEGORY_OPERATOR, numInstancesEntry.getCategory());

    Assertions.assertEquals(asyncEntry.getOperationId(), numInstancesEntry.getOperationId());
  }

  @Test
  public void testLogCreationSync() {
    // given
    ProcessDefinition processDefinition = testRule.deployAndGetDefinition(instance);
    rule.getIdentityService().setAuthenticatedUserId("userId");

    ProcessInstance processInstance1 = runtimeService.startProcessInstanceByKey("process1");
    ProcessInstance processInstance2 = runtimeService.startProcessInstanceByKey("process1");

    runtimeService.deleteProcessInstance(processInstance1.getId(), "test");
    runtimeService.deleteProcessInstance(processInstance2.getId(), "test");

    // when
    runtimeService.restartProcessInstances(processDefinition.getId()).startAfterActivity("user1").processInstanceIds(processInstance1.getId(), processInstance2.getId()).execute();
    rule.getIdentityService().clearAuthentication();

    // then
    List<UserOperationLogEntry> opLogEntries = rule.getHistoryService().createUserOperationLogQuery().operationType("RestartProcessInstance").list();
    Assertions.assertEquals(2, opLogEntries.size());

    Map<String, UserOperationLogEntry> entries = asMap(opLogEntries);


    UserOperationLogEntry asyncEntry = entries.get("async");
    Assertions.assertNotNull(asyncEntry);
    Assertions.assertEquals("ProcessInstance", asyncEntry.getEntityType());
    Assertions.assertEquals("RestartProcessInstance", asyncEntry.getOperationType());
    Assertions.assertEquals(processDefinition.getId(), asyncEntry.getProcessDefinitionId());
    Assertions.assertEquals(processDefinition.getKey(), asyncEntry.getProcessDefinitionKey());
    Assertions.assertNull(asyncEntry.getProcessInstanceId());
    Assertions.assertNull(asyncEntry.getOrgValue());
    Assertions.assertEquals("false", asyncEntry.getNewValue());
    Assertions.assertEquals(UserOperationLogEntry.CATEGORY_OPERATOR, asyncEntry.getCategory());

    UserOperationLogEntry numInstancesEntry = entries.get("nrOfInstances");
    Assertions.assertNotNull(numInstancesEntry);
    Assertions.assertEquals("ProcessInstance", numInstancesEntry.getEntityType());
    Assertions.assertEquals("RestartProcessInstance", numInstancesEntry.getOperationType());
    Assertions.assertEquals(processDefinition.getId(), numInstancesEntry.getProcessDefinitionId());
    Assertions.assertEquals(processDefinition.getKey(), numInstancesEntry.getProcessDefinitionKey());
    Assertions.assertNull(numInstancesEntry.getProcessInstanceId());
    Assertions.assertNull(numInstancesEntry.getOrgValue());
    Assertions.assertEquals("2", numInstancesEntry.getNewValue());
    Assertions.assertEquals(UserOperationLogEntry.CATEGORY_OPERATOR, numInstancesEntry.getCategory());

    Assertions.assertEquals(asyncEntry.getOperationId(), numInstancesEntry.getOperationId());
  }

  @Test
  public void testNoCreationOnSyncBatchJobExecution() {
    // given
    ProcessDefinition processDefinition = testRule.deployAndGetDefinition(instance);

    ProcessInstance processInstance = runtimeService.startProcessInstanceById(processDefinition.getId());
    runtimeService.deleteProcessInstance(processInstance.getId(), "test");
    Batch batch = runtimeService.restartProcessInstances(processDefinition.getId()).startBeforeActivity("user1").processInstanceIds(processInstance.getId()).executeAsync();

    helper.completeSeedJobs(batch);

    // when
    rule.getIdentityService().setAuthenticatedUserId("userId");
    helper.executeJobs(batch);
    rule.getIdentityService().clearAuthentication();

    // then
    Assertions.assertEquals(0, rule.getHistoryService().createUserOperationLogQuery().entityType(EntityTypes.PROCESS_INSTANCE).count());
  }

  @Test
  public void shouldNotLogOnSyncExecutionUnauthenticated() {
    // given
    ProcessDefinition processDefinition = testRule.deployAndGetDefinition(instance);

    ProcessInstance processInstance = runtimeService.startProcessInstanceById(processDefinition.getId());
    runtimeService.deleteProcessInstance(processInstance.getId(), "test");
    Batch batch = runtimeService.restartProcessInstances(processDefinition.getId()).startBeforeActivity("user1").processInstanceIds(processInstance.getId()).executeAsync();

    helper.completeSeedJobs(batch);

    rule.getProcessEngineConfiguration().setRestrictUserOperationLogToAuthenticatedUsers(false);

    // when
    helper.executeJobs(batch);

    // then
    Assertions.assertEquals(0, rule.getHistoryService().createUserOperationLogQuery().entityType(EntityTypes.PROCESS_INSTANCE).count());
  }

  @Test
  public void testNoCreationOnJobExecutorBatchJobExecution() {
    // given
    ProcessDefinition processDefinition = testRule.deployAndGetDefinition(instance);

    ProcessInstance processInstance = runtimeService.startProcessInstanceById(processDefinition.getId());
    runtimeService.deleteProcessInstance(processInstance.getId(), "test");
    runtimeService.restartProcessInstances(processDefinition.getId())
      .startAfterActivity("user1")
      .processInstanceIds(Arrays.asList(processInstance.getId()))
      .executeAsync();

    // when
    testRule.waitForJobExecutorToProcessAllJobs(5000L);

    // then
    Assertions.assertEquals(0, rule.getHistoryService().createUserOperationLogQuery().count());
  }

  @Test
  public void shouldNotLogOnExecutionUnauthenticated() {
    // given
    ProcessDefinition processDefinition = testRule.deployAndGetDefinition(instance);

    ProcessInstance processInstance = runtimeService.startProcessInstanceById(processDefinition.getId());
    runtimeService.deleteProcessInstance(processInstance.getId(), "test");
    runtimeService.restartProcessInstances(processDefinition.getId())
      .startAfterActivity("user1")
      .processInstanceIds(Arrays.asList(processInstance.getId()))
      .executeAsync();

    rule.getProcessEngineConfiguration().setRestrictUserOperationLogToAuthenticatedUsers(false);

    // when
    testRule.waitForJobExecutorToProcessAllJobs(5000L);

    // then
    Assertions.assertEquals(0, rule.getHistoryService().createUserOperationLogQuery().count());
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
