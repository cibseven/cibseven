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
import org.cibseven.bpm.engine.task.Attachment;
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
 * Tenant isolation of the attachment commands, see CIB7-1752: on running tasks and process instances
 * through the existing runtime checks, and once the owner has completed through the historic read and
 * modify checks on CommandChecker.
 */
@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
public class MultiTenancyAttachmentCmdsTenantCheckTest {

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

  @BeforeEach
  public void init() {
    identityService = engineRule.getIdentityService();
    runtimeService = engineRule.getRuntimeService();
    taskService = engineRule.getTaskService();
    historyService = engineRule.getHistoryService();
    processEngineConfiguration = engineRule.getProcessEngineConfiguration();

    // the tenant check rides along with the attachment checks, which are on by default;
    // switched on explicitly so that no test depends on another one having restored the default
    processEngineConfiguration.setEnforceAttachmentPermissions(true);
  }

  @AfterEach
  public void tearDown() {
    identityService.clearAuthentication();
    processEngineConfiguration.setEnforceAttachmentPermissions(true);
    processEngineConfiguration.setTenantCheckEnabled(false);

    for (ProcessInstance instance : runtimeService.createProcessInstanceQuery().list()) {
      runtimeService.deleteProcessInstance(instance.getId(), null);
    }
    for (HistoricTaskInstance instance : historyService.createHistoricTaskInstanceQuery().list()) {
      historyService.deleteHistoricTaskInstance(instance.getId());
    }
    for (HistoricProcessInstance instance : historyService.createHistoricProcessInstanceQuery().list()) {
      historyService.deleteHistoricProcessInstance(instance.getId());
    }
    processEngineConfiguration.setTenantCheckEnabled(true);
  }

  // running task ////////////////////////////////////////////////////////////

  @Test
  public void failToCreateAttachmentForRunningTaskOfOtherTenant() {
    String taskId = startProcessInstanceAndGetTaskId(TENANT_ONE);

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_TWO));

    // when/then
    assertThatThrownBy(() -> taskService.createAttachment("foo", taskId, null, "aName", "aDescription",
        "http://cibseven.org"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot work on task '" + taskId + "'");
  }

  @Test
  public void failToReadAttachmentOfRunningTaskOfOtherTenant() {
    String taskId = startProcessInstanceAndGetTaskId(TENANT_ONE);
    Attachment attachment = createAttachment(taskId, null);

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_TWO));

    // when/then
    assertThatThrownBy(() -> taskService.getAttachment(attachment.getId()))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot read the task '" + taskId + "'");
    assertThatThrownBy(() -> taskService.getTaskAttachments(taskId))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot read the task '" + taskId + "'");
    assertThatThrownBy(() -> taskService.getTaskAttachmentContent(taskId, attachment.getId()))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot read the task '" + taskId + "'");
  }

  @Test
  public void failToDeleteAttachmentOfRunningTaskOfOtherTenant() {
    String taskId = startProcessInstanceAndGetTaskId(TENANT_ONE);
    Attachment attachment = createAttachment(taskId, null);

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_TWO));

    // when/then
    assertThatThrownBy(() -> taskService.deleteAttachment(attachment.getId()))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot work on task '" + taskId + "'");
  }

  @Test
  public void workWithAttachmentOfRunningTaskWithAuthenticatedTenant() {
    String taskId = startProcessInstanceAndGetTaskId(TENANT_ONE);

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_ONE));

    // when
    Attachment attachment = taskService.createAttachment("foo", taskId, null, "aName", "aDescription",
        "http://cibseven.org");

    // then
    assertThat(taskService.getTaskAttachments(taskId)).hasSize(1);
    assertThat(taskService.getAttachment(attachment.getId())).isNotNull();

    taskService.deleteAttachment(attachment.getId());
    assertThat(taskService.getTaskAttachments(taskId)).isEmpty();
  }

  // running process instance ////////////////////////////////////////////////

  @Test
  public void failToCreateAttachmentForRunningProcessInstanceOfOtherTenant() {
    String processInstanceId = startProcessInstance(TENANT_ONE);

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_TWO));

    // when/then
    assertThatThrownBy(() -> taskService.createAttachment("foo", null, processInstanceId, "aName",
        "aDescription", "http://cibseven.org"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot update the process instance '" + processInstanceId + "'");
  }

  @Test
  public void failToReadAttachmentOfRunningProcessInstanceOfOtherTenant() {
    String processInstanceId = startProcessInstance(TENANT_ONE);
    Attachment attachment = createAttachment(null, processInstanceId);

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_TWO));

    // when/then
    assertThatThrownBy(() -> taskService.getAttachment(attachment.getId()))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot read the process instance '" + processInstanceId + "'");
    assertThatThrownBy(() -> taskService.getProcessInstanceAttachments(processInstanceId))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot read the process instance '" + processInstanceId + "'");
  }

  @Test
  public void workWithAttachmentOfRunningProcessInstanceWithAuthenticatedTenant() {
    String processInstanceId = startProcessInstance(TENANT_ONE);

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_ONE));

    // when
    Attachment attachment = taskService.createAttachment("foo", null, processInstanceId, "aName",
        "aDescription", "http://cibseven.org");

    // then
    assertThat(taskService.getProcessInstanceAttachments(processInstanceId)).hasSize(1);

    taskService.deleteAttachment(attachment.getId());
    assertThat(taskService.getProcessInstanceAttachments(processInstanceId)).isEmpty();
  }

  @Test
  public void filterTaskAttachmentOfOtherTenantFromProcessInstanceAttachments() {
    // an attachment created before the enforcement may tie a task of another tenant to the instance;
    // the list drops it instead of failing as a whole
    String processInstanceId = startProcessInstance(TENANT_ONE);
    Attachment instanceAttachment = createAttachment(null, processInstanceId);

    Task foreignTask = taskService.newTask();
    foreignTask.setTenantId(TENANT_TWO);
    taskService.saveTask(foreignTask);
    processEngineConfiguration.setEnforceAttachmentPermissions(false);
    createAttachment(foreignTask.getId(), processInstanceId);
    processEngineConfiguration.setEnforceAttachmentPermissions(true);

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_ONE));

    // when/then
    assertThat(taskService.getProcessInstanceAttachments(processInstanceId))
        .extracting(Attachment::getId)
        .containsExactly(instanceAttachment.getId());

    identityService.clearAuthentication();
    taskService.deleteTask(foreignTask.getId(), true);
  }

  // completed standalone task ///////////////////////////////////////////////

  @Test
  public void failToDeleteAttachmentOfCompletedTaskOfOtherTenant() {
    String attachmentId = createAttachmentForCompletedTask(TENANT_ONE);

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_TWO));

    // when/then
    assertThatThrownBy(() -> taskService.deleteAttachment(attachmentId))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot modify the historic task instance");
  }

  @Test
  public void failToSaveAttachmentOfCompletedTaskWithNoAuthenticatedTenant() {
    String attachmentId = createAttachmentForCompletedTask(TENANT_ONE);
    Attachment attachment = taskService.getAttachment(attachmentId);
    attachment.setName("aNewName");

    identityService.setAuthentication("user", null, null);

    // when/then
    assertThatThrownBy(() -> taskService.saveAttachment(attachment))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot modify the historic task instance");
  }

  @Test
  public void deleteAttachmentOfCompletedTaskWithAuthenticatedTenant() {
    String attachmentId = createAttachmentForCompletedTask(TENANT_ONE);

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_ONE));

    // when
    taskService.deleteAttachment(attachmentId);

    // then
    identityService.clearAuthentication();
    assertThat(taskService.getAttachment(attachmentId)).isNull();
  }

  @Test
  public void deleteAttachmentOfCompletedTaskWithDisabledTenantCheck() {
    String attachmentId = createAttachmentForCompletedTask(TENANT_ONE);

    identityService.setAuthentication("user", null, null);
    processEngineConfiguration.setTenantCheckEnabled(false);

    // when
    taskService.deleteAttachment(attachmentId);

    // then
    assertThat(taskService.getAttachment(attachmentId)).isNull();
  }

  @Test
  public void failToReadAttachmentOfCompletedTaskOfOtherTenant() {
    String attachmentId = createAttachmentForCompletedTask(TENANT_ONE);
    String taskId = taskService.getAttachment(attachmentId).getTaskId();

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_TWO));

    // when/then
    assertThatThrownBy(() -> taskService.getAttachment(attachmentId))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot read the historic task instance");
    assertThatThrownBy(() -> taskService.getAttachmentContent(attachmentId))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot read the historic task instance");
    assertThatThrownBy(() -> taskService.getTaskAttachments(taskId))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot read the historic task instance");
  }

  @Test
  public void readAttachmentOfCompletedTaskWithAuthenticatedTenant() {
    String attachmentId = createAttachmentForCompletedTask(TENANT_ONE);
    String taskId = taskService.getAttachment(attachmentId).getTaskId();

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_ONE));

    // when/then
    assertThat(taskService.getAttachment(attachmentId)).isNotNull();
    assertThat(taskService.getTaskAttachments(taskId)).hasSize(1);
  }

  // completed process instance //////////////////////////////////////////////

  @Test
  public void failToReadAttachmentOfCompletedProcessInstanceOfOtherTenant() {
    String attachmentId = createAttachmentForCompletedProcessInstance(TENANT_ONE);
    String processInstanceId = taskService.getAttachment(attachmentId).getProcessInstanceId();

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_TWO));

    // when/then
    assertThatThrownBy(() -> taskService.getAttachment(attachmentId))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot read the historic process instance");
    assertThatThrownBy(() -> taskService.getProcessInstanceAttachments(processInstanceId))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot read the historic process instance");
  }

  @Test
  public void readAttachmentOfCompletedProcessInstanceWithAuthenticatedTenant() {
    String attachmentId = createAttachmentForCompletedProcessInstance(TENANT_ONE);
    String processInstanceId = taskService.getAttachment(attachmentId).getProcessInstanceId();

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_ONE));

    // when/then
    assertThat(taskService.getAttachment(attachmentId)).isNotNull();
    assertThat(taskService.getProcessInstanceAttachments(processInstanceId)).hasSize(1);
  }

  @Test
  public void failToDeleteAttachmentOfCompletedProcessInstanceOfOtherTenant() {
    String attachmentId = createAttachmentForCompletedProcessInstance(TENANT_ONE);

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_TWO));

    // when/then
    assertThatThrownBy(() -> taskService.deleteAttachment(attachmentId))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot modify the historic process instance");
  }

  @Test
  public void deleteAttachmentOfCompletedProcessInstanceWithAuthenticatedTenant() {
    String attachmentId = createAttachmentForCompletedProcessInstance(TENANT_ONE);

    identityService.setAuthentication("user", null, Collections.singletonList(TENANT_ONE));

    // when
    taskService.deleteAttachment(attachmentId);

    // then
    identityService.clearAuthentication();
    assertThat(taskService.getAttachment(attachmentId)).isNull();
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

  /**
   * Creates the attachment before any tenant is authenticated, so that no tenant check applies.
   */
  protected Attachment createAttachment(String taskId, String processInstanceId) {
    return taskService.createAttachment("foo", taskId, processInstanceId, "aName", "aDescription",
        "http://cibseven.org");
  }

  /**
   * Attaches to a standalone task and completes it, so that only the historic task remains.
   */
  protected String createAttachmentForCompletedTask(String tenantId) {
    Task task = taskService.newTask();
    task.setTenantId(tenantId);
    taskService.saveTask(task);

    Attachment attachment = taskService.createAttachment("foo", task.getId(), null, "aName",
        "aDescription", "http://cibseven.org");

    taskService.complete(task.getId());

    return attachment.getId();
  }

  protected String createAttachmentForCompletedProcessInstance(String tenantId) {
    testRule.deployForTenant(tenantId, ONE_TASK_PROCESS);

    String processInstanceId = runtimeService.createProcessInstanceByKey(PROCESS_DEFINITION_KEY)
        .processDefinitionTenantId(tenantId)
        .execute()
        .getId();

    Attachment attachment = taskService.createAttachment("foo", null, processInstanceId, "aName",
        "aDescription", "http://cibseven.org");

    Task task = taskService.createTaskQuery().processInstanceId(processInstanceId).singleResult();
    taskService.complete(task.getId());

    return attachment.getId();
  }
}
