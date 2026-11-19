/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
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
package org.cibseven.bpm.engine.test.api.multitenancy.tenantcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;

import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.history.HistoricProcessInstance;
import org.cibseven.bpm.engine.history.HistoricTaskInstance;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Comment;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Tenant isolation of creating and reading comments, see CIB7-1757.
 */
@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
public class MultiTenancyCommentCmdsTenantCheckTest {

  protected static final String TENANT_ONE = "tenant1";
  protected static final String TENANT_TWO = "tenant2";

  protected static final String PROCESS_DEFINITION_KEY = "oneTaskProcess";

  protected static final BpmnModelInstance ONE_TASK_PROCESS = Bpmn
      .createExecutableProcess(PROCESS_DEFINITION_KEY)
      .startEvent()
      .userTask("task1")
      .endEvent()
      .done();

  @RegisterExtension
  @Order(1) protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  @Order(2) protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  protected IdentityService identityService;
  protected RuntimeService runtimeService;
  protected TaskService taskService;
  protected HistoryService historyService;
  protected ProcessEngineConfigurationImpl processEngineConfiguration;

  protected boolean originalEnforceCommentPermissions;
  protected boolean originalTenantCheckEnabled;

  @BeforeEach
  public void init() {
    identityService = engineRule.getIdentityService();
    runtimeService = engineRule.getRuntimeService();
    taskService = engineRule.getTaskService();
    historyService = engineRule.getHistoryService();
    processEngineConfiguration = engineRule.getProcessEngineConfiguration();

    originalEnforceCommentPermissions = processEngineConfiguration.isEnforceCommentPermissions();
    originalTenantCheckEnabled = processEngineConfiguration.isTenantCheckEnabled();

    processEngineConfiguration.setEnforceCommentPermissions(true);
    processEngineConfiguration.setTenantCheckEnabled(true);
  }

  @AfterEach
  public void tearDown() {
    identityService.clearAuthentication();
    processEngineConfiguration.setTenantCheckEnabled(false);
    try {
      for (ProcessInstance instance : runtimeService.createProcessInstanceQuery().list()) {
        runtimeService.deleteProcessInstance(instance.getId(), null);
      }
      for (Task task : taskService.createTaskQuery().list()) {
        taskService.deleteTask(task.getId(), true);
      }
      for (HistoricTaskInstance instance : historyService.createHistoricTaskInstanceQuery().list()) {
        historyService.deleteHistoricTaskInstance(instance.getId());
      }
      for (HistoricProcessInstance instance : historyService.createHistoricProcessInstanceQuery().list()) {
        historyService.deleteHistoricProcessInstance(instance.getId());
      }
    } finally {
      processEngineConfiguration.setTenantCheckEnabled(originalTenantCheckEnabled);
      processEngineConfiguration.setEnforceCommentPermissions(originalEnforceCommentPermissions);
    }
  }

  // running task ////////////////////////////////////////////////////////////

  @Test
  public void failToCreateCommentForRunningTaskOfOtherTenant() {
    String taskId = startProcessInstanceAndGetTaskId(TENANT_ONE);

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_TWO));

    // when/then
    assertThatThrownBy(() -> taskService.createComment(taskId, null, "aComment"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot work on task '" + taskId + "'");
  }

  @Test
  public void failToReadCommentOfRunningTaskOfOtherTenant() {
    String taskId = startProcessInstanceAndGetTaskId(TENANT_ONE);
    Comment comment = taskService.createComment(taskId, null, "aComment");

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_TWO));

    // when/then
    assertThatThrownBy(() -> taskService.getTaskComment(taskId, comment.getId()))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot read the task '" + taskId + "'");
    assertThatThrownBy(() -> taskService.getTaskComments(taskId))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot read the task '" + taskId + "'");
    assertThatThrownBy(() -> taskService.getTaskEvents(taskId))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot read the task '" + taskId + "'");
  }

  @Test
  public void workWithCommentOfRunningTaskWithAuthenticatedTenant() {
    String taskId = startProcessInstanceAndGetTaskId(TENANT_ONE);

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_ONE));

    // when
    Comment comment = taskService.createComment(taskId, null, "aComment");

    // then
    assertThat(taskService.getTaskComments(taskId)).hasSize(1);
    assertThat(taskService.getTaskComment(taskId, comment.getId())).isNotNull();
  }

  // running process instance ////////////////////////////////////////////////

  @Test
  public void failToCreateCommentForRunningProcessInstanceOfOtherTenant() {
    String processInstanceId = startProcessInstance(TENANT_ONE);

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_TWO));

    // when/then
    assertThatThrownBy(() -> taskService.createComment(null, processInstanceId, "aComment"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot update the process instance '" + processInstanceId + "'");
  }

  @Test
  public void failToReadCommentsOfRunningProcessInstanceOfOtherTenant() {
    String processInstanceId = startProcessInstance(TENANT_ONE);
    taskService.createComment(null, processInstanceId, "aComment");

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_TWO));

    // when/then
    assertThatThrownBy(() -> taskService.getProcessInstanceComments(processInstanceId))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot read the process instance '" + processInstanceId + "'");
  }

  @Test
  public void workWithCommentsOfRunningProcessInstanceWithAuthenticatedTenant() {
    String processInstanceId = startProcessInstance(TENANT_ONE);

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_ONE));

    // when
    taskService.createComment(null, processInstanceId, "aComment");

    // then
    assertThat(taskService.getProcessInstanceComments(processInstanceId)).hasSize(1);
  }

  @Test
  public void filterTaskCommentOfOtherTenantFromProcessInstanceComments() {
    // created without authentication, so not checked
    String processInstanceId = startProcessInstance(TENANT_ONE);
    Comment instanceComment = taskService.createComment(null, processInstanceId, "aComment");

    Task foreignTask = taskService.newTask();
    foreignTask.setTenantId(TENANT_TWO);
    taskService.saveTask(foreignTask);
    taskService.createComment(foreignTask.getId(), processInstanceId, "aForeignComment");

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_ONE));

    // when/then
    assertThat(taskService.getProcessInstanceComments(processInstanceId))
        .extracting(Comment::getId)
        .containsExactly(instanceComment.getId());
  }

  // completed task //////////////////////////////////////////////////////////

  @Test
  public void failToReadCommentOfCompletedTaskOfOtherTenant() {
    Task task = createCompletedTaskWithComment(TENANT_ONE);

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_TWO));

    // when/then
    assertThatThrownBy(() -> taskService.getTaskComments(task.getId()))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot read the historic task instance");
  }

  @Test
  public void failToCreateCommentForCompletedTaskOfOtherTenant() {
    Task task = createCompletedTaskWithComment(TENANT_ONE);

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_TWO));

    // when/then
    assertThatThrownBy(() -> taskService.createComment(task.getId(), null, "aComment"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot modify the historic task instance");
  }

  @Test
  public void workWithCommentOfCompletedTaskWithAuthenticatedTenant() {
    Task task = createCompletedTaskWithComment(TENANT_ONE);

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_ONE));

    // when
    taskService.createComment(task.getId(), null, "aSecondComment");

    // then
    assertThat(taskService.getTaskComments(task.getId())).hasSize(2);
  }

  // completed process instance //////////////////////////////////////////////

  @Test
  public void failToReadCommentsOfCompletedProcessInstanceOfOtherTenant() {
    String processInstanceId = createCompletedProcessInstanceWithComment(TENANT_ONE);

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_TWO));

    // when/then
    assertThatThrownBy(() -> taskService.getProcessInstanceComments(processInstanceId))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot read the historic process instance");
  }

  @Test
  public void readCommentsOfCompletedProcessInstanceWithAuthenticatedTenant() {
    String processInstanceId = createCompletedProcessInstanceWithComment(TENANT_ONE);

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_ONE));

    // when/then
    assertThat(taskService.getProcessInstanceComments(processInstanceId)).hasSize(1);
  }

  @Test
  public void readCommentsOfCompletedProcessInstanceWithDisabledTenantCheck() {
    String processInstanceId = createCompletedProcessInstanceWithComment(TENANT_ONE);

    identityService.setAuthentication("user", null, null);
    processEngineConfiguration.setTenantCheckEnabled(false);

    // when/then
    assertThat(taskService.getProcessInstanceComments(processInstanceId)).hasSize(1);
  }

  // helpers /////////////////////////////////////////////////////////////////

  protected String startProcessInstance(String tenantId) {
    testRule.deployForTenant(tenantId, ONE_TASK_PROCESS);

    return runtimeService.createProcessInstanceByKey(PROCESS_DEFINITION_KEY)
        .processDefinitionTenantId(tenantId)
        .execute()
        .getId();
  }

  protected String startProcessInstanceAndGetTaskId(String tenantId) {
    String processInstanceId = startProcessInstance(tenantId);
    return taskService.createTaskQuery().processInstanceId(processInstanceId).singleResult().getId();
  }

  protected Task createCompletedTaskWithComment(String tenantId) {
    Task task = taskService.newTask();
    task.setTenantId(tenantId);
    taskService.saveTask(task);

    taskService.createComment(task.getId(), null, "aComment");
    taskService.complete(task.getId());

    return task;
  }

  protected String createCompletedProcessInstanceWithComment(String tenantId) {
    String processInstanceId = startProcessInstance(tenantId);
    taskService.createComment(null, processInstanceId, "aComment");

    Task task = taskService.createTaskQuery().processInstanceId(processInstanceId).singleResult();
    taskService.complete(task.getId());

    return processInstanceId;
  }
}
