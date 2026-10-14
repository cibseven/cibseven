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
import org.cibseven.bpm.engine.task.Attachment;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

/**
 * Tenant isolation of the attachment commands once the owning task or process instance has
 * completed, see CIB7-1752. The runtime paths are already covered by the task and process instance
 * tenant checks, these tests pin the historic fallback that CommandChecker#checkModifyHistoricTaskInstance
 * and CommandChecker#checkModifyHistoricProcessInstance guard.
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

  protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();

  protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  @Rule
  public RuleChain ruleChain = RuleChain.outerRule(engineRule).around(testRule);

  protected IdentityService identityService;
  protected RuntimeService runtimeService;
  protected TaskService taskService;
  protected HistoryService historyService;
  protected ProcessEngineConfigurationImpl processEngineConfiguration;

  @Before
  public void init() {
    identityService = engineRule.getIdentityService();
    runtimeService = engineRule.getRuntimeService();
    taskService = engineRule.getTaskService();
    historyService = engineRule.getHistoryService();
    processEngineConfiguration = engineRule.getProcessEngineConfiguration();

    // the attachment checks are off by default, and the tenant check rides along with them
    processEngineConfiguration.setEnforceAttachmentPermissions(true);
  }

  @After
  public void tearDown() {
    identityService.clearAuthentication();
    processEngineConfiguration.setEnforceAttachmentPermissions(false);
    processEngineConfiguration.setTenantCheckEnabled(false);

    for (HistoricTaskInstance instance : historyService.createHistoricTaskInstanceQuery().list()) {
      historyService.deleteHistoricTaskInstance(instance.getId());
    }
    for (HistoricProcessInstance instance : historyService.createHistoricProcessInstanceQuery().list()) {
      historyService.deleteHistoricProcessInstance(instance.getId());
    }
    processEngineConfiguration.setTenantCheckEnabled(true);
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

  // completed process instance //////////////////////////////////////////////

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
