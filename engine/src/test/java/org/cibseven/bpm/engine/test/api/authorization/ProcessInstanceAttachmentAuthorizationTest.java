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
package org.cibseven.bpm.engine.test.api.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.cibseven.bpm.engine.authorization.Permissions.DELETE_HISTORY;
import static org.cibseven.bpm.engine.authorization.Permissions.READ;
import static org.cibseven.bpm.engine.authorization.Permissions.READ_HISTORY;
import static org.cibseven.bpm.engine.authorization.Permissions.READ_INSTANCE;
import static org.cibseven.bpm.engine.authorization.Permissions.UPDATE;
import static org.cibseven.bpm.engine.authorization.Permissions.UPDATE_INSTANCE;
import static org.cibseven.bpm.engine.authorization.Resources.HISTORIC_PROCESS_INSTANCE;
import static org.cibseven.bpm.engine.authorization.Resources.PROCESS_DEFINITION;
import static org.cibseven.bpm.engine.authorization.Resources.PROCESS_INSTANCE;

import java.util.List;

import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.authorization.HistoricProcessInstancePermissions;
import org.cibseven.bpm.engine.task.Attachment;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.Deployment;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Authorization of the process instance scoped attachment commands, see CIB7-1752.
 */
public class ProcessInstanceAttachmentAuthorizationTest extends AuthorizationTest {

  protected static final String PROCESS_KEY = "oneTaskProcess";
  protected static final String ONE_TASK_PROCESS = "org/cibseven/bpm/engine/test/api/oneTaskProcess.bpmn20.xml";

  protected static final String UNKNOWN_ID = "anUnknownId";

  /**
   * The checks are off by default, so every test below has to switch them on explicitly.
   */
  @Before
  public void enforceAttachmentPermissions() {
    processEngineConfiguration.setEnforceAttachmentPermissions(true);
  }

  @After
  public void resetAttachmentPermissions() {
    processEngineConfiguration.setEnforceAttachmentPermissions(false);
    // individual tests switch this on for the historic read fallback
    processEngineConfiguration.setEnableHistoricInstancePermissions(false);
  }

  // create attachment ///////////////////////////////////////////////////////

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotCreateProcessInstanceAttachmentWithoutAuthorization() {
    // given
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();

    // when/then
    assertThatThrownBy(() -> taskService.createAttachment("foo", null, processInstanceId, "aName", "aDescription",
        "http://cibseven.org"))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'UPDATE' permission on resource '" + processInstanceId + "' of type 'ProcessInstance'")
        .hasMessageContaining("'UPDATE_INSTANCE' permission on resource 'oneTaskProcess' of type 'ProcessDefinition'");
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldCreateProcessInstanceAttachmentWithUpdatePermission() {
    // given
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    createGrantAuthorization(PROCESS_INSTANCE, processInstanceId, userId, UPDATE);

    // when
    Attachment attachment = taskService.createAttachment("foo", null, processInstanceId, "aName", "aDescription",
        "http://cibseven.org");

    // then
    assertThat(attachment).isNotNull();
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldCreateProcessInstanceAttachmentWithUpdateInstancePermissionOnProcessDefinition() {
    // given
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    createGrantAuthorization(PROCESS_DEFINITION, PROCESS_KEY, userId, UPDATE_INSTANCE);

    // when
    Attachment attachment = taskService.createAttachment("foo", null, processInstanceId, "aName", "aDescription",
        "http://cibseven.org");

    // then
    assertThat(attachment).isNotNull();
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotCreateProcessInstanceAttachmentWithReadPermissionOnly() {
    // given
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    createGrantAuthorization(PROCESS_INSTANCE, processInstanceId, userId, READ);

    // when/then
    assertThatThrownBy(() -> taskService.createAttachment("foo", null, processInstanceId, "aName", "aDescription",
        "http://cibseven.org"))
        .isInstanceOf(AuthorizationException.class);
  }

  // get process instance attachments /////////////////////////////////////////

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotGetProcessInstanceAttachmentsWithoutAuthorization() {
    // given
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    createAttachment(null, processInstanceId);

    // when/then
    assertThatThrownBy(() -> taskService.getProcessInstanceAttachments(processInstanceId))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'READ' permission on resource '" + processInstanceId + "' of type 'ProcessInstance'")
        .hasMessageContaining("'READ_INSTANCE' permission on resource 'oneTaskProcess' of type 'ProcessDefinition'");
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotGetProcessInstanceAttachmentsWithUpdatePermissionOnly() {
    // reading requires READ, the permission that allows writing is not enough
    // given
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    createAttachment(null, processInstanceId);
    createGrantAuthorization(PROCESS_INSTANCE, processInstanceId, userId, UPDATE);

    // when/then
    assertThatThrownBy(() -> taskService.getProcessInstanceAttachments(processInstanceId))
        .isInstanceOf(AuthorizationException.class);
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldGetProcessInstanceAttachmentsWithReadPermission() {
    // given
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    createAttachment(null, processInstanceId);
    createGrantAuthorization(PROCESS_INSTANCE, processInstanceId, userId, READ);

    // when
    List<Attachment> attachments = taskService.getProcessInstanceAttachments(processInstanceId);

    // then
    assertThat(attachments).hasSize(1);
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldGetProcessInstanceAttachmentsWithReadInstancePermissionOnProcessDefinition() {
    // given
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    createAttachment(null, processInstanceId);
    createGrantAuthorization(PROCESS_DEFINITION, PROCESS_KEY, userId, READ_INSTANCE);

    // when
    List<Attachment> attachments = taskService.getProcessInstanceAttachments(processInstanceId);

    // then
    assertThat(attachments).hasSize(1);
  }

  @Test
  public void shouldGetEmptyAttachmentsForUnknownProcessInstanceWithoutAuthorization() {
    // no process instance can be resolved, so there is nothing to check and nothing to return
    assertThat(taskService.getProcessInstanceAttachments(UNKNOWN_ID)).isEmpty();
  }

  @Test
  public void shouldGetEmptyAttachmentsForNullProcessInstanceIdWithoutAuthorization() {
    assertThat(taskService.getProcessInstanceAttachments(null)).isEmpty();
  }

  // delete attachment ///////////////////////////////////////////////////////

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotDeleteProcessInstanceAttachmentWithoutAuthorization() {
    // given
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    Attachment attachment = createAttachment(null, processInstanceId);

    // when/then
    assertThatThrownBy(() -> taskService.deleteAttachment(attachment.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'UPDATE' permission on resource '" + processInstanceId + "' of type 'ProcessInstance'");

    assertThat(selectAttachment(attachment.getId())).isNotNull();
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldDeleteProcessInstanceAttachmentWithUpdatePermission() {
    // given
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    Attachment attachment = createAttachment(null, processInstanceId);
    createGrantAuthorization(PROCESS_INSTANCE, processInstanceId, userId, UPDATE);

    // when
    taskService.deleteAttachment(attachment.getId());

    // then
    assertThat(selectAttachment(attachment.getId())).isNull();
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldDeleteProcessInstanceAttachmentWithUpdateInstancePermissionOnProcessDefinition() {
    // given
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    Attachment attachment = createAttachment(null, processInstanceId);
    createGrantAuthorization(PROCESS_DEFINITION, PROCESS_KEY, userId, UPDATE_INSTANCE);

    // when
    taskService.deleteAttachment(attachment.getId());

    // then
    assertThat(selectAttachment(attachment.getId())).isNull();
  }

  // save attachment /////////////////////////////////////////////////////////

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotSaveProcessInstanceAttachmentWithoutAuthorization() {
    // given
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    Attachment attachment = createAttachment(null, processInstanceId);
    attachment.setName("renamedByAttacker");

    // when/then
    assertThatThrownBy(() -> taskService.saveAttachment(attachment))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'UPDATE' permission on resource '" + processInstanceId + "' of type 'ProcessInstance'");

    assertThat(selectAttachment(attachment.getId()).getName()).isEqualTo("aName");
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldSaveProcessInstanceAttachmentWithUpdatePermission() {
    // given
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    Attachment attachment = createAttachment(null, processInstanceId);
    attachment.setName("updatedName");
    createGrantAuthorization(PROCESS_INSTANCE, processInstanceId, userId, UPDATE);

    // when
    taskService.saveAttachment(attachment);

    // then
    assertThat(selectAttachment(attachment.getId()).getName()).isEqualTo("updatedName");
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldSaveProcessInstanceAttachmentWithUpdateInstancePermissionOnProcessDefinition() {
    // given
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    Attachment attachment = createAttachment(null, processInstanceId);
    attachment.setName("updatedName");
    createGrantAuthorization(PROCESS_DEFINITION, PROCESS_KEY, userId, UPDATE_INSTANCE);

    // when
    taskService.saveAttachment(attachment);

    // then
    assertThat(selectAttachment(attachment.getId()).getName()).isEqualTo("updatedName");
  }

  // get attachment by id ////////////////////////////////////////////////////

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotGetProcessInstanceScopedAttachmentByIdWithoutAuthorization() {
    // given
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    Attachment attachment = createAttachment(null, processInstanceId);

    // when/then
    assertThatThrownBy(() -> taskService.getAttachment(attachment.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'READ' permission on resource '" + processInstanceId + "' of type 'ProcessInstance'");
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldGetProcessInstanceScopedAttachmentByIdWithReadPermission() {
    // given
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    Attachment attachment = createAttachment(null, processInstanceId);
    createGrantAuthorization(PROCESS_INSTANCE, processInstanceId, userId, READ);

    // when/then
    assertThat(taskService.getAttachment(attachment.getId())).isNotNull();
  }

  // get attachment content by id ////////////////////////////////////////////

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotGetProcessInstanceScopedAttachmentContentByIdWithoutAuthorization() {
    // given
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    Attachment attachment = createAttachment(null, processInstanceId);

    // when/then
    assertThatThrownBy(() -> taskService.getAttachmentContent(attachment.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'READ' permission on resource '" + processInstanceId + "' of type 'ProcessInstance'");
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldGetProcessInstanceScopedAttachmentContentByIdWithReadPermission() {
    // given
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    Attachment attachment = createAttachment(null, processInstanceId);
    createGrantAuthorization(PROCESS_INSTANCE, processInstanceId, userId, READ);

    // when/then
    assertThat(taskService.getAttachmentContent(attachment.getId())).isNotNull();
  }

  // enforceAttachmentPermissions disabled /////////////////////////////////////

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotCheckReadsWhenEnforcementDisabled() {
    // given
    processEngineConfiguration.setEnforceAttachmentPermissions(false);
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    Attachment attachment = createAttachment(null, processInstanceId);

    // when/then - the behaviour before CIB7-1752, no permission needed at all
    assertThat(taskService.getProcessInstanceAttachments(processInstanceId)).hasSize(1);
    assertThat(taskService.getAttachment(attachment.getId())).isNotNull();
    assertThat(taskService.getAttachmentContent(attachment.getId())).isNotNull();
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotCheckCreateWhenEnforcementDisabled() {
    // given
    processEngineConfiguration.setEnforceAttachmentPermissions(false);
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();

    // when
    Attachment attachment = taskService.createAttachment("foo", null, processInstanceId, "aName", "aDescription",
        "http://cibseven.org");

    // then
    assertThat(attachment).isNotNull();
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotCheckSaveWhenEnforcementDisabled() {
    // given
    processEngineConfiguration.setEnforceAttachmentPermissions(false);
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    Attachment attachment = createAttachment(null, processInstanceId);
    attachment.setName("updatedName");

    // when
    taskService.saveAttachment(attachment);

    // then - the behaviour before CIB7-1752, no permission needed at all
    assertThat(selectAttachment(attachment.getId()).getName()).isEqualTo("updatedName");
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotCheckDeleteWhenEnforcementDisabled() {
    // given
    processEngineConfiguration.setEnforceAttachmentPermissions(false);
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    Attachment attachment = createAttachment(null, processInstanceId);

    // when
    taskService.deleteAttachment(attachment.getId());

    // then
    assertThat(selectAttachment(attachment.getId())).isNull();
  }

  // known limitation ////////////////////////////////////////////////////////

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotReadAttachmentOfCompletedProcessInstanceWithoutAnyHistoricPermission() {
    // given
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    Attachment attachment = createAttachment(null, processInstanceId);
    Task task = selectSingleTask();
    completeTask(task.getId());

    // when/then
    assertThatThrownBy(() -> taskService.getAttachment(attachment.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'READ_HISTORY' permission on resource '" + PROCESS_KEY + "' of type 'ProcessDefinition'")
        .hasMessageContaining(
            "'READ' permission on resource '" + processInstanceId + "' of type 'HistoricProcessInstance'");
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldReadAttachmentOfCompletedProcessInstanceWithReadHistoryOnProcessDefinition() {
    // given
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    Attachment attachment = createAttachment(null, processInstanceId);
    Task task = selectSingleTask();
    completeTask(task.getId());
    createGrantAuthorization(PROCESS_DEFINITION, PROCESS_KEY, userId, READ_HISTORY);

    // when/then
    assertThat(taskService.getProcessInstanceAttachments(processInstanceId)).hasSize(1);
    assertThat(taskService.getAttachment(attachment.getId())).isNotNull();
    assertThat(taskService.getAttachmentContent(attachment.getId())).isNotNull();
  }

  // historic read fallback //////////////////////////////////////////////////

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotReadAttachmentOfCompletedProcessInstanceWithoutHistoricReadPermission() {
    // given
    processEngineConfiguration.setEnableHistoricInstancePermissions(true);
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    Attachment attachment = createAttachment(null, processInstanceId);
    Task task = selectSingleTask();
    completeTask(task.getId());

    // when/then
    assertThatThrownBy(() -> taskService.getAttachment(attachment.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining(
            "'READ' permission on resource '" + processInstanceId + "' of type 'HistoricProcessInstance'");
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldReadAttachmentOfCompletedProcessInstanceWithHistoricReadPermission() {
    // given
    processEngineConfiguration.setEnableHistoricInstancePermissions(true);
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    Attachment attachment = createAttachment(null, processInstanceId);
    Task task = selectSingleTask();
    completeTask(task.getId());
    createGrantAuthorization(HISTORIC_PROCESS_INSTANCE, processInstanceId, userId,
        HistoricProcessInstancePermissions.READ);

    // when/then
    assertThat(taskService.getAttachment(attachment.getId())).isNotNull();
    assertThat(taskService.getAttachmentContent(attachment.getId())).isNotNull();
    assertThat(taskService.getProcessInstanceAttachments(processInstanceId)).hasSize(1);
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotDeleteCompletedProcessInstanceAttachmentWithoutDeleteHistory() {
    // given
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    Attachment attachment = createAttachment(null, processInstanceId);
    Task task = selectSingleTask();
    completeTask(task.getId());

    // when/then
    assertThatThrownBy(() -> taskService.deleteAttachment(attachment.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'DELETE_HISTORY' permission on resource '" + PROCESS_KEY + "' of type 'ProcessDefinition'");
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldDeleteCompletedProcessInstanceAttachmentWithDeleteHistory() {
    // given
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    Attachment attachment = createAttachment(null, processInstanceId);
    Task task = selectSingleTask();
    completeTask(task.getId());
    createGrantAuthorization(PROCESS_DEFINITION, PROCESS_KEY, userId, DELETE_HISTORY);

    // when
    taskService.deleteAttachment(attachment.getId());

    // then
    assertThat(selectAttachment(attachment.getId())).isNull();
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotSaveCompletedProcessInstanceAttachmentWithoutDeleteHistory() {
    // saving mutates historic data just like deleting, so it takes the same permission
    // given
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    Attachment attachment = createAttachment(null, processInstanceId);
    Task task = selectSingleTask();
    completeTask(task.getId());
    attachment.setName("aNewName");

    // when/then
    assertThatThrownBy(() -> taskService.saveAttachment(attachment))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'DELETE_HISTORY' permission on resource '" + PROCESS_KEY + "' of type 'ProcessDefinition'");
  }
}
