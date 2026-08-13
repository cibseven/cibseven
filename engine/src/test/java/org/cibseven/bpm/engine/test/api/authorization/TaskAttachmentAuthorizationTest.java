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
import static org.cibseven.bpm.engine.authorization.Permissions.READ_TASK;
import static org.cibseven.bpm.engine.authorization.Permissions.TASK_WORK;
import static org.cibseven.bpm.engine.authorization.Permissions.UPDATE;
import static org.cibseven.bpm.engine.authorization.Permissions.UPDATE_TASK;
import static org.cibseven.bpm.engine.authorization.Resources.HISTORIC_PROCESS_INSTANCE;
import static org.cibseven.bpm.engine.authorization.Resources.HISTORIC_TASK;
import static org.cibseven.bpm.engine.authorization.Resources.PROCESS_DEFINITION;
import static org.cibseven.bpm.engine.authorization.Resources.TASK;

import java.util.List;
import java.util.concurrent.Callable;

import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.authorization.HistoricProcessInstancePermissions;
import org.cibseven.bpm.engine.authorization.HistoricTaskPermissions;
import org.cibseven.bpm.engine.task.Attachment;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.test.Deployment;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Authorization of the task scoped attachment commands, see CIB7-1752.
 */
public class TaskAttachmentAuthorizationTest extends AuthorizationTest {

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

  /**
   * Standalone tasks are not covered by the deployment cleanup, so drop them including their
   * attachments. Tests that expect an AuthorizationException leave the attachment behind.
   */
  @After
  public void deleteStandaloneTask() {
    runWithoutAuthorization((Callable<Void>) () -> {
      Task task = taskService.createTaskQuery().taskId(TASK_ID).singleResult();
      if (task != null) {
        taskService.deleteTask(TASK_ID, true);
      }
      // a completed standalone task leaves history behind that no deployment cleanup covers
      if (historyService.createHistoricTaskInstanceQuery().taskId(TASK_ID).count() > 0) {
        historyService.deleteHistoricTaskInstance(TASK_ID);
      }
      return null;
    });
  }

  // create attachment ///////////////////////////////////////////////////////

  @Test
  public void shouldNotCreateStandaloneTaskAttachmentWithoutAuthorization() {
    // given
    createTask(TASK_ID);

    // when/then
    assertThatThrownBy(() -> taskService.createAttachment("foo", TASK_ID, null, "aName", "aDescription",
        "http://cibseven.org"))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'TASK_WORK' permission on resource 'myTask' of type 'Task'")
        .hasMessageContaining("'UPDATE' permission on resource 'myTask' of type 'Task'");
  }

  @Test
  public void shouldCreateStandaloneTaskAttachmentWithUpdatePermission() {
    // given
    createTask(TASK_ID);
    createGrantAuthorization(TASK, TASK_ID, userId, UPDATE);

    // when
    Attachment attachment = taskService.createAttachment("foo", TASK_ID, null, "aName", "aDescription",
        "http://cibseven.org");

    // then
    assertThat(attachment).isNotNull();
  }

  @Test
  public void shouldCreateStandaloneTaskAttachmentWithTaskWorkPermission() {
    // given
    createTask(TASK_ID);
    createGrantAuthorization(TASK, TASK_ID, userId, TASK_WORK);

    // when
    Attachment attachment = taskService.createAttachment("foo", TASK_ID, null, "aName", "aDescription",
        "http://cibseven.org");

    // then
    assertThat(attachment).isNotNull();
  }

  @Test
  public void shouldNotCreateStandaloneTaskAttachmentWithReadPermissionOnly() {
    // given
    createTask(TASK_ID);
    createGrantAuthorization(TASK, TASK_ID, userId, READ);

    // when/then
    assertThatThrownBy(() -> taskService.createAttachment("foo", TASK_ID, null, "aName", "aDescription",
        "http://cibseven.org"))
        .isInstanceOf(AuthorizationException.class);
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotCreateProcessTaskAttachmentWithoutAuthorization() {
    // given
    startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();

    // when/then
    assertThatThrownBy(() -> taskService.createAttachment("foo", task.getId(), null, "aName", "aDescription",
        "http://cibseven.org"))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'UPDATE_TASK' permission on resource 'oneTaskProcess' of type 'ProcessDefinition'");
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldCreateProcessTaskAttachmentWithUpdatePermissionOnTask() {
    // given
    startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    createGrantAuthorization(TASK, task.getId(), userId, UPDATE);

    // when
    Attachment attachment = taskService.createAttachment("foo", task.getId(), null, "aName", "aDescription",
        "http://cibseven.org");

    // then
    assertThat(attachment).isNotNull();
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldCreateProcessTaskAttachmentWithUpdateTaskPermissionOnProcessDefinition() {
    // given
    startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    createGrantAuthorization(PROCESS_DEFINITION, PROCESS_KEY, userId, UPDATE_TASK);

    // when
    Attachment attachment = taskService.createAttachment("foo", task.getId(), null, "aName", "aDescription",
        "http://cibseven.org");

    // then
    assertThat(attachment).isNotNull();
  }

  @Test
  public void shouldCreateAttachmentForUnknownTaskWithoutAuthorization() {
    // documents the current behaviour: no task can be resolved, so no permission can be checked
    // and an orphan attachment is created

    // when
    Attachment attachment = taskService.createAttachment("foo", UNKNOWN_ID, null, "aName", "aDescription",
        "http://cibseven.org");

    // then
    assertThat(attachment).isNotNull();

    deleteAttachment(attachment.getId());
  }

  // delete attachment ///////////////////////////////////////////////////////

  @Test
  public void shouldNotDeleteStandaloneTaskAttachmentWithoutAuthorization() {
    // given
    createTask(TASK_ID);
    Attachment attachment = createAttachment(TASK_ID, null);

    // when/then
    assertThatThrownBy(() -> taskService.deleteAttachment(attachment.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'TASK_WORK' permission on resource 'myTask' of type 'Task'");

    assertThat(selectAttachment(attachment.getId())).isNotNull();
  }

  @Test
  public void shouldDeleteStandaloneTaskAttachmentWithUpdatePermission() {
    // given
    createTask(TASK_ID);
    Attachment attachment = createAttachment(TASK_ID, null);
    createGrantAuthorization(TASK, TASK_ID, userId, UPDATE);

    // when
    taskService.deleteAttachment(attachment.getId());

    // then
    assertThat(selectAttachment(attachment.getId())).isNull();
  }

  @Test
  public void shouldNotDeleteStandaloneTaskAttachmentByTaskIdWithoutAuthorization() {
    // given
    createTask(TASK_ID);
    Attachment attachment = createAttachment(TASK_ID, null);

    // when/then
    assertThatThrownBy(() -> taskService.deleteTaskAttachment(TASK_ID, attachment.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'TASK_WORK' permission on resource 'myTask' of type 'Task'");

    assertThat(selectAttachment(attachment.getId())).isNotNull();
  }

  @Test
  public void shouldDeleteStandaloneTaskAttachmentByTaskIdWithUpdatePermission() {
    // given
    createTask(TASK_ID);
    Attachment attachment = createAttachment(TASK_ID, null);
    createGrantAuthorization(TASK, TASK_ID, userId, UPDATE);

    // when
    taskService.deleteTaskAttachment(TASK_ID, attachment.getId());

    // then
    assertThat(selectAttachment(attachment.getId())).isNull();
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotDeleteProcessTaskAttachmentWithoutAuthorization() {
    // given
    startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    Attachment attachment = createAttachment(task.getId(), null);

    // when/then
    assertThatThrownBy(() -> taskService.deleteAttachment(attachment.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'UPDATE_TASK' permission on resource 'oneTaskProcess' of type 'ProcessDefinition'");
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldDeleteProcessTaskAttachmentWithUpdateTaskPermissionOnProcessDefinition() {
    // given
    startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    Attachment attachment = createAttachment(task.getId(), null);
    createGrantAuthorization(PROCESS_DEFINITION, PROCESS_KEY, userId, UPDATE_TASK);

    // when
    taskService.deleteAttachment(attachment.getId());

    // then
    assertThat(selectAttachment(attachment.getId())).isNull();
  }

  // save attachment /////////////////////////////////////////////////////////

  @Test
  public void shouldNotSaveStandaloneTaskAttachmentWithoutAuthorization() {
    // given
    createTask(TASK_ID);
    Attachment attachment = createAttachment(TASK_ID, null);
    attachment.setName("renamedByAttacker");

    // when/then
    assertThatThrownBy(() -> taskService.saveAttachment(attachment))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'TASK_WORK' permission on resource 'myTask' of type 'Task'");

    assertThat(selectAttachment(attachment.getId()).getName()).isEqualTo("aName");
  }

  @Test
  public void shouldNotSaveStandaloneTaskAttachmentWithReadPermissionOnly() {
    // given
    createTask(TASK_ID);
    Attachment attachment = createAttachment(TASK_ID, null);
    attachment.setName("renamedByAttacker");
    createGrantAuthorization(TASK, TASK_ID, userId, READ);

    // when/then
    assertThatThrownBy(() -> taskService.saveAttachment(attachment))
        .isInstanceOf(AuthorizationException.class);
  }

  @Test
  public void shouldSaveStandaloneTaskAttachmentWithUpdatePermission() {
    // given
    createTask(TASK_ID);
    Attachment attachment = createAttachment(TASK_ID, null);
    attachment.setName("updatedName");
    attachment.setDescription("updatedDescription");
    createGrantAuthorization(TASK, TASK_ID, userId, UPDATE);

    // when
    taskService.saveAttachment(attachment);

    // then
    Attachment updated = selectAttachment(attachment.getId());
    assertThat(updated.getName()).isEqualTo("updatedName");
    assertThat(updated.getDescription()).isEqualTo("updatedDescription");
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotSaveProcessTaskAttachmentWithoutAuthorization() {
    // given
    startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    Attachment attachment = createAttachment(task.getId(), null);
    attachment.setName("renamedByAttacker");

    // when/then
    assertThatThrownBy(() -> taskService.saveAttachment(attachment))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'UPDATE_TASK' permission on resource 'oneTaskProcess' of type 'ProcessDefinition'");
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldSaveProcessTaskAttachmentWithUpdateTaskPermissionOnProcessDefinition() {
    // given
    startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    Attachment attachment = createAttachment(task.getId(), null);
    attachment.setName("updatedName");
    createGrantAuthorization(PROCESS_DEFINITION, PROCESS_KEY, userId, UPDATE_TASK);

    // when
    taskService.saveAttachment(attachment);

    // then
    assertThat(selectAttachment(attachment.getId()).getName()).isEqualTo("updatedName");
  }

  // get task attachments ////////////////////////////////////////////////////

  @Test
  public void shouldNotGetStandaloneTaskAttachmentsWithoutAuthorization() {
    // given
    createTask(TASK_ID);
    createAttachment(TASK_ID, null);

    // when/then
    assertThatThrownBy(() -> taskService.getTaskAttachments(TASK_ID))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'READ' permission on resource 'myTask' of type 'Task'");
  }

  @Test
  public void shouldNotGetStandaloneTaskAttachmentsWithUpdatePermissionOnly() {
    // reading requires READ, the permission that allows writing is not enough
    // given
    createTask(TASK_ID);
    createAttachment(TASK_ID, null);
    createGrantAuthorization(TASK, TASK_ID, userId, UPDATE);

    // when/then
    assertThatThrownBy(() -> taskService.getTaskAttachments(TASK_ID))
        .isInstanceOf(AuthorizationException.class);
  }

  @Test
  public void shouldGetStandaloneTaskAttachmentsWithReadPermission() {
    // given
    createTask(TASK_ID);
    createAttachment(TASK_ID, null);
    createGrantAuthorization(TASK, TASK_ID, userId, READ);

    // when
    List<Attachment> attachments = taskService.getTaskAttachments(TASK_ID);

    // then
    assertThat(attachments).hasSize(1);
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotGetProcessTaskAttachmentsWithoutAuthorization() {
    // given
    startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    createAttachment(task.getId(), null);

    // when/then
    assertThatThrownBy(() -> taskService.getTaskAttachments(task.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'READ_TASK' permission on resource 'oneTaskProcess' of type 'ProcessDefinition'");
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldGetProcessTaskAttachmentsWithReadTaskPermissionOnProcessDefinition() {
    // given
    startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    createAttachment(task.getId(), null);
    createGrantAuthorization(PROCESS_DEFINITION, PROCESS_KEY, userId, READ_TASK);

    // when
    List<Attachment> attachments = taskService.getTaskAttachments(task.getId());

    // then
    assertThat(attachments).hasSize(1);
  }

  @Test
  public void shouldGetEmptyAttachmentsForUnknownTaskWithoutAuthorization() {
    // no task can be resolved, so there is nothing to check and nothing to return
    assertThat(taskService.getTaskAttachments(UNKNOWN_ID)).isEmpty();
  }

  @Test
  public void shouldGetEmptyAttachmentsForNullTaskIdWithoutAuthorization() {
    // TaskManager#findTaskById rejects a null id, the command must not resolve the task in that case
    assertThat(taskService.getTaskAttachments(null)).isEmpty();
  }

  // get single task attachment //////////////////////////////////////////////

  @Test
  public void shouldNotGetStandaloneTaskAttachmentWithoutAuthorization() {
    // given
    createTask(TASK_ID);
    Attachment attachment = createAttachment(TASK_ID, null);

    // when/then
    assertThatThrownBy(() -> taskService.getTaskAttachment(TASK_ID, attachment.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'READ' permission on resource 'myTask' of type 'Task'");
  }

  @Test
  public void shouldGetStandaloneTaskAttachmentWithReadPermission() {
    // given
    createTask(TASK_ID);
    Attachment attachment = createAttachment(TASK_ID, null);
    createGrantAuthorization(TASK, TASK_ID, userId, READ);

    // when/then
    assertThat(taskService.getTaskAttachment(TASK_ID, attachment.getId())).isNotNull();
  }

  @Test
  public void shouldGetNullTaskAttachmentForNullParametersWithoutAuthorization() {
    // TaskManager#findTaskById rejects a null id, the command must not resolve the task in that case
    assertThat(taskService.getTaskAttachment(null, null)).isNull();
  }

  @Test
  public void shouldGetNullTaskAttachmentForUnknownAttachmentIdWithReadPermission() {
    // given
    createTask(TASK_ID);
    createGrantAuthorization(TASK, TASK_ID, userId, READ);

    // when/then
    assertThat(taskService.getTaskAttachment(TASK_ID, UNKNOWN_ID)).isNull();
  }

  // get task attachment content /////////////////////////////////////////////

  @Test
  public void shouldNotGetStandaloneTaskAttachmentContentWithoutAuthorization() {
    // given
    createTask(TASK_ID);
    Attachment attachment = createAttachment(TASK_ID, null);

    // when/then
    assertThatThrownBy(() -> taskService.getTaskAttachmentContent(TASK_ID, attachment.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'READ' permission on resource 'myTask' of type 'Task'");
  }

  @Test
  public void shouldGetStandaloneTaskAttachmentContentWithReadPermission() {
    // given
    createTask(TASK_ID);
    Attachment attachment = createAttachment(TASK_ID, null);
    createGrantAuthorization(TASK, TASK_ID, userId, READ);

    // when/then
    assertThat(taskService.getTaskAttachmentContent(TASK_ID, attachment.getId())).isNotNull();
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotGetProcessTaskAttachmentContentWithoutAuthorization() {
    // given
    startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    Attachment attachment = createAttachment(task.getId(), null);

    // when/then
    assertThatThrownBy(() -> taskService.getTaskAttachmentContent(task.getId(), attachment.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'READ_TASK' permission on resource 'oneTaskProcess' of type 'ProcessDefinition'");
  }

  @Test
  public void shouldGetNullContentForNullParametersWithoutAuthorization() {
    // TaskManager#findTaskById rejects a null id, the command must not resolve the task in that case
    assertThat(taskService.getTaskAttachmentContent(null, null)).isNull();
  }

  @Test
  public void shouldGetNullContentForUrlAttachmentWithReadPermission() {
    // given
    createTask(TASK_ID);
    Attachment attachment = createAttachmentWithUrl(TASK_ID, null);
    createGrantAuthorization(TASK, TASK_ID, userId, READ);

    // when/then
    assertThat(taskService.getTaskAttachmentContent(TASK_ID, attachment.getId())).isNull();
  }

  // get attachment by id ////////////////////////////////////////////////////

  @Test
  public void shouldNotGetTaskScopedAttachmentByIdWithoutAuthorization() {
    // given
    createTask(TASK_ID);
    Attachment attachment = createAttachment(TASK_ID, null);

    // when/then
    assertThatThrownBy(() -> taskService.getAttachment(attachment.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'READ' permission on resource 'myTask' of type 'Task'");
  }

  @Test
  public void shouldGetTaskScopedAttachmentByIdWithReadPermission() {
    // given
    createTask(TASK_ID);
    Attachment attachment = createAttachment(TASK_ID, null);
    createGrantAuthorization(TASK, TASK_ID, userId, READ);

    // when/then
    assertThat(taskService.getAttachment(attachment.getId())).isNotNull();
  }

  @Test
  public void shouldGetNullAttachmentForUnknownAttachmentIdWithoutAuthorization() {
    // the attachment carries the resource to check, so an unknown id cannot be checked at all
    assertThat(taskService.getAttachment(UNKNOWN_ID)).isNull();
  }

  // get attachment content by id ////////////////////////////////////////////

  @Test
  public void shouldNotGetTaskScopedAttachmentContentByIdWithoutAuthorization() {
    // given
    createTask(TASK_ID);
    Attachment attachment = createAttachment(TASK_ID, null);

    // when/then
    assertThatThrownBy(() -> taskService.getAttachmentContent(attachment.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'READ' permission on resource 'myTask' of type 'Task'");
  }

  @Test
  public void shouldGetTaskScopedAttachmentContentByIdWithReadPermission() {
    // given
    createTask(TASK_ID);
    Attachment attachment = createAttachment(TASK_ID, null);
    createGrantAuthorization(TASK, TASK_ID, userId, READ);

    // when/then
    assertThat(taskService.getAttachmentContent(attachment.getId())).isNotNull();
  }

  // dispatch order //////////////////////////////////////////////////////////

  @Test
  public void shouldCheckTaskAndNotProcessInstanceWhenAttachmentCarriesBothIds() {
    // an attachment may carry an arbitrary process instance id next to its task id, the task wins
    // given
    createTask(TASK_ID);
    Attachment attachment = createAttachment(TASK_ID, UNKNOWN_ID);
    createGrantAuthorization(TASK, TASK_ID, userId, READ);

    // when/then
    assertThat(taskService.getAttachment(attachment.getId())).isNotNull();
  }

  @Test
  public void shouldRejectAttachmentCarryingBothIdsWithoutTaskPermission() {
    // given
    createTask(TASK_ID);
    Attachment attachment = createAttachment(TASK_ID, UNKNOWN_ID);

    // when/then
    assertThatThrownBy(() -> taskService.getAttachment(attachment.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'READ' permission on resource 'myTask' of type 'Task'");
  }

  // known limitation ////////////////////////////////////////////////////////

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotReadAttachmentOfCompletedTaskWithoutAnyHistoricPermission() {
    // given
    startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    Attachment attachment = createAttachment(task.getId(), null);
    completeTask(task.getId());

    // when/then
    assertThatThrownBy(() -> taskService.getAttachment(attachment.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'READ_HISTORY' permission on resource '" + PROCESS_KEY + "' of type 'ProcessDefinition'")
        .hasMessageContaining("'READ' permission on resource '" + task.getId() + "' of type 'HistoricTask'");
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldReadAttachmentOfCompletedTaskWithReadHistoryOnProcessDefinition() {
    // given
    startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    Attachment attachment = createAttachment(task.getId(), null);
    completeTask(task.getId());
    createGrantAuthorization(PROCESS_DEFINITION, PROCESS_KEY, userId, READ_HISTORY);

    // when/then
    assertThat(taskService.getTaskAttachments(task.getId())).hasSize(1);
    assertThat(taskService.getTaskAttachment(task.getId(), attachment.getId())).isNotNull();
    assertThat(taskService.getAttachment(attachment.getId())).isNotNull();
    assertThat(taskService.getAttachmentContent(attachment.getId())).isNotNull();
  }

  // historic read fallback //////////////////////////////////////////////////

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotReadAttachmentOfCompletedTaskWithoutHistoricReadPermission() {
    // given
    processEngineConfiguration.setEnableHistoricInstancePermissions(true);
    startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    Attachment attachment = createAttachment(task.getId(), null);
    completeTask(task.getId());

    // when/then
    assertThatThrownBy(() -> taskService.getAttachment(attachment.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'READ' permission on resource '" + task.getId() + "' of type 'HistoricTask'");
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldReadAttachmentOfCompletedTaskWithHistoricReadPermission() {
    // given
    processEngineConfiguration.setEnableHistoricInstancePermissions(true);
    startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    Attachment attachment = createAttachment(task.getId(), null);
    completeTask(task.getId());
    createGrantAuthorization(HISTORIC_TASK, task.getId(), userId, HistoricTaskPermissions.READ);

    // when/then
    assertThat(taskService.getAttachment(attachment.getId())).isNotNull();
    assertThat(taskService.getAttachmentContent(attachment.getId())).isNotNull();
    assertThat(taskService.getTaskAttachments(task.getId())).hasSize(1);
    assertThat(taskService.getTaskAttachment(task.getId(), attachment.getId())).isNotNull();
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldReadAttachmentOfCompletedTaskWithHistoricProcessInstanceReadPermission() {
    // the read check mirrors configureHistoricTaskInstanceQuery, which also lets the permission on
    // the owning historic process instance grant access to the task
    // given
    processEngineConfiguration.setEnableHistoricInstancePermissions(true);
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    Task task = selectSingleTask();
    Attachment attachment = createAttachment(task.getId(), null);
    completeTask(task.getId());
    createGrantAuthorization(HISTORIC_PROCESS_INSTANCE, processInstanceId, userId,
        HistoricProcessInstancePermissions.READ);

    // when/then
    assertThat(taskService.getAttachment(attachment.getId())).isNotNull();
    assertThat(taskService.getAttachmentContent(attachment.getId())).isNotNull();
    assertThat(taskService.getTaskAttachments(task.getId())).hasSize(1);
    assertThat(taskService.getTaskAttachment(task.getId(), attachment.getId())).isNotNull();
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldListAllThreeWaysInWhenReadOfCompletedTaskAttachmentIsDenied() {
    // given
    processEngineConfiguration.setEnableHistoricInstancePermissions(true);
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    Task task = selectSingleTask();
    Attachment attachment = createAttachment(task.getId(), null);
    completeTask(task.getId());

    // when/then
    assertThatThrownBy(() -> taskService.getAttachment(attachment.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'READ_HISTORY' permission on resource '" + PROCESS_KEY + "' of type 'ProcessDefinition'")
        .hasMessageContaining("'READ' permission on resource '" + processInstanceId + "' of type 'HistoricProcessInstance'")
        .hasMessageContaining("'READ' permission on resource '" + task.getId() + "' of type 'HistoricTask'");
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotDeleteCompletedTaskAttachmentWithoutDeleteHistory() {
    // given
    startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    Attachment attachment = createAttachment(task.getId(), null);
    completeTask(task.getId());

    // when/then
    assertThatThrownBy(() -> taskService.deleteAttachment(attachment.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'DELETE_HISTORY' permission on resource '" + PROCESS_KEY + "' of type 'ProcessDefinition'");
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldDeleteCompletedTaskAttachmentWithDeleteHistory() {
    // given
    startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    Attachment attachment = createAttachment(task.getId(), null);
    completeTask(task.getId());
    createGrantAuthorization(PROCESS_DEFINITION, PROCESS_KEY, userId, DELETE_HISTORY);

    // when
    taskService.deleteAttachment(attachment.getId());

    // then
    assertThat(selectAttachment(attachment.getId())).isNull();
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotSaveCompletedTaskAttachmentWithoutDeleteHistory() {
    // saving mutates historic data just like deleting, so it takes the same permission
    // given
    startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    Attachment attachment = createAttachment(task.getId(), null);
    completeTask(task.getId());
    attachment.setName("aNewName");

    // when/then
    assertThatThrownBy(() -> taskService.saveAttachment(attachment))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'DELETE_HISTORY' permission on resource '" + PROCESS_KEY + "' of type 'ProcessDefinition'");
  }

  @Test
  public void shouldNotDeleteCompletedStandaloneTaskAttachmentWithoutHistoricTaskAll() {
    // a standalone task carries no process definition key, so DELETE_HISTORY can never match and
    // ALL on the historic task is the only way in
    // given
    createTask(TASK_ID);
    Attachment attachment = createAttachment(TASK_ID, null);
    completeTask(TASK_ID);

    // when/then
    assertThatThrownBy(() -> taskService.deleteAttachment(attachment.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'ALL' permission on resource '" + TASK_ID + "' of type 'HistoricTask'");
  }

  @Test
  public void shouldDeleteCompletedStandaloneTaskAttachmentWithHistoricTaskAll() {
    // given
    createTask(TASK_ID);
    Attachment attachment = createAttachment(TASK_ID, null);
    completeTask(TASK_ID);
    createGrantAuthorization(HISTORIC_TASK, TASK_ID, userId, HistoricTaskPermissions.ALL);

    // when
    taskService.deleteAttachment(attachment.getId());

    // then
    assertThat(selectAttachment(attachment.getId())).isNull();
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldSaveCompletedTaskAttachmentWithHistoricTaskAll() {
    // the non-delete way in: ALL on the historic task instead of DELETE_HISTORY on the definition
    // given
    startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    Attachment attachment = createAttachment(task.getId(), null);
    completeTask(task.getId());
    createGrantAuthorization(HISTORIC_TASK, task.getId(), userId, HistoricTaskPermissions.ALL);
    attachment.setName("aNewName");

    // when
    taskService.saveAttachment(attachment);

    // then
    assertThat(selectAttachment(attachment.getId()).getName()).isEqualTo("aNewName");
  }

  // enforceAttachmentPermissions disabled /////////////////////////////////////

  @Test
  public void shouldDisableAttachmentPermissionsByDefault() {
    assertThat(new StandaloneInMemProcessEngineConfiguration().isEnforceAttachmentPermissions()).isFalse();
  }

  @Test
  public void shouldNotCheckReadsWhenEnforcementDisabled() {
    // given
    processEngineConfiguration.setEnforceAttachmentPermissions(false);
    createTask(TASK_ID);
    Attachment attachment = createAttachment(TASK_ID, null);

    // when/then - the behaviour before CIB7-1752, no permission needed at all
    assertThat(taskService.getTaskAttachments(TASK_ID)).hasSize(1);
    assertThat(taskService.getTaskAttachment(TASK_ID, attachment.getId())).isNotNull();
    assertThat(taskService.getTaskAttachmentContent(TASK_ID, attachment.getId())).isNotNull();
    assertThat(taskService.getAttachment(attachment.getId())).isNotNull();
    assertThat(taskService.getAttachmentContent(attachment.getId())).isNotNull();
  }

  @Test
  public void shouldNotCheckCreateWhenEnforcementDisabled() {
    // given
    processEngineConfiguration.setEnforceAttachmentPermissions(false);
    createTask(TASK_ID);

    // when
    Attachment attachment = taskService.createAttachment("foo", TASK_ID, null, "aName", "aDescription",
        "http://cibseven.org");

    // then
    assertThat(attachment).isNotNull();
  }

  @Test
  public void shouldNotCheckSaveWhenEnforcementDisabled() {
    // given
    processEngineConfiguration.setEnforceAttachmentPermissions(false);
    createTask(TASK_ID);
    Attachment attachment = createAttachment(TASK_ID, null);
    attachment.setName("updatedName");

    // when
    taskService.saveAttachment(attachment);

    // then - the behaviour before CIB7-1752, no permission needed at all
    assertThat(selectAttachment(attachment.getId()).getName()).isEqualTo("updatedName");
  }

  @Test
  public void shouldNotCheckDeleteWhenEnforcementDisabled() {
    // given
    processEngineConfiguration.setEnforceAttachmentPermissions(false);
    createTask(TASK_ID);
    Attachment attachment = createAttachment(TASK_ID, null);

    // when
    taskService.deleteAttachment(attachment.getId());

    // then
    assertThat(selectAttachment(attachment.getId())).isNull();
  }

  @Test
  public void shouldReadAttachmentOfCompletedStandaloneTaskWithoutHistoricInstancePermissions() {
    // a standalone task carries no process definition key, so with enableHistoricInstancePermissions
    // off neither branch of the disjunction can ever match. Denying here would be stricter than the
    // historic queries, which return such a task without any permission.
    // given
    createTask(TASK_ID);
    Attachment attachment = createAttachment(TASK_ID, null);
    completeTask(TASK_ID);

    // when/then
    assertThat(taskService.getAttachment(attachment.getId())).isNotNull();
    assertThat(taskService.getAttachmentContent(attachment.getId())).isNotNull();
    assertThat(taskService.getTaskAttachments(TASK_ID)).hasSize(1);
    // the engine's own historic query agrees: no permission needed for this task
    assertThat(historyService.createHistoricTaskInstanceQuery().taskId(TASK_ID).singleResult()).isNotNull();
  }

  @Test
  public void shouldNotReadAttachmentOfCompletedStandaloneTaskWithHistoricInstancePermissions() {
    // with the flag on, the per-instance permission exists and is enforced
    // given
    processEngineConfiguration.setEnableHistoricInstancePermissions(true);
    createTask(TASK_ID);
    Attachment attachment = createAttachment(TASK_ID, null);
    completeTask(TASK_ID);

    // when/then
    assertThatThrownBy(() -> taskService.getAttachment(attachment.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'READ' permission on resource '" + TASK_ID + "' of type 'HistoricTask'");
  }
}
