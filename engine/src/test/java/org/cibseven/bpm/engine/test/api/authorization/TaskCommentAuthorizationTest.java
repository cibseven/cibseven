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
import static org.cibseven.bpm.engine.authorization.Resources.HISTORIC_TASK;
import static org.cibseven.bpm.engine.authorization.Resources.PROCESS_DEFINITION;
import static org.cibseven.bpm.engine.authorization.Resources.TASK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.authorization.Groups;
import org.cibseven.bpm.engine.authorization.HistoricTaskPermissions;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Comment;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.Deployment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TaskCommentAuthorizationTest extends AuthorizationTest {

  protected static final String PROCESS_KEY = "oneTaskProcess";
  protected static final String ONE_TASK_PROCESS = "org/cibseven/bpm/engine/test/api/oneTaskProcess.bpmn20.xml";

  protected static final String UNKNOWN_ID = "anUnknownId";

  protected boolean originalEnforceCommentPermissions;
  protected boolean originalEnableHistoricInstancePermissions;

  @BeforeEach
  public void enforceCommentPermissions() {
    originalEnforceCommentPermissions = processEngineConfiguration.isEnforceCommentPermissions();
    originalEnableHistoricInstancePermissions = processEngineConfiguration.isEnableHistoricInstancePermissions();
    processEngineConfiguration.setEnforceCommentPermissions(true);
  }

  /**
   * Removes what the deployment cleanup misses: standalone tasks and comments without a task.
   */
  @AfterEach
  public void cleanUp() {
    try {
      runWithoutAuthorization((Callable<Void>) () -> {
        Task task = taskService.createTaskQuery().taskId(TASK_ID).singleResult();
        if (task != null) {
          taskService.deleteTask(TASK_ID, true);
        }
        if (historyService.createHistoricTaskInstanceQuery().taskId(TASK_ID).count() > 0) {
          historyService.deleteHistoricTaskInstance(TASK_ID);
        }
        deleteCommentsOfTask(UNKNOWN_ID);
        deleteCommentsOfTask("");
        return null;
      });
    } finally {
      processEngineConfiguration.setEnforceCommentPermissions(originalEnforceCommentPermissions);
      processEngineConfiguration.setEnableHistoricInstancePermissions(originalEnableHistoricInstancePermissions);
    }
  }

  @Test
  public void testDeleteTaskCommentWithoutAuthorization() {
    // given
    createTask(TASK_ID);
    Comment createdComment = createComment(TASK_ID, null, "aComment");

    try {
      // when
      taskService.deleteTaskComment(TASK_ID, createdComment.getId());
      fail("Exception expected: It should not be possible to delete a comment.");
    } catch (AuthorizationException e) {
      // then
      testRule.assertTextPresent(
          "The user with id 'test' does not have one of the following permissions: 'TASK_WORK' permission on resource 'myTask' of type 'Task' or 'UPDATE' permission on resource 'myTask' of type 'Task'",
          e.getMessage());
    }

    // triggers a db clean up
    deleteTask(TASK_ID, true);
  }

  @Test
  public void testDeleteTaskComment() {
    // given
    createTask(TASK_ID);
    Comment createdComment = createComment(TASK_ID, null, "aComment");
    createGrantAuthorization(TASK, TASK_ID, userId, UPDATE);

    // when
    taskService.deleteTaskComment(TASK_ID, createdComment.getId());

    // then
    Comment shouldBeDeleletedComment = selectTaskComment(TASK_ID, createdComment.getId());
    assertNull(shouldBeDeleletedComment);

    // triggers a db clean up
    deleteTask(TASK_ID, true);
  }

  @Test
  public void testDeleteTaskCommentsWithoutAuthorization() {
    // given
    createTask(TASK_ID);
    createComment(TASK_ID, null, "aComment");

    try {
      // when
      taskService.deleteTaskComments(TASK_ID);
      fail("Exception expected: It should not be possible to delete a comment.");
    } catch (AuthorizationException e) {
      // then
      testRule.assertTextPresent(
          "The user with id 'test' does not have one of the following permissions: 'TASK_WORK' permission on resource 'myTask' of type 'Task' or 'UPDATE' permission on resource 'myTask' of type 'Task'",
          e.getMessage());
    }

    // triggers a db clean up
    deleteTask(TASK_ID, true);
  }

  @Test
  public void testDeleteTaskComments() {
    // given
    createTask(TASK_ID);
    createComment(TASK_ID, null, "aCommentOne");
    createComment(TASK_ID, null, "aCommentTwo");

    createGrantAuthorization(TASK, TASK_ID, userId, UPDATE);

    // when
    taskService.deleteTaskComments(TASK_ID);

    // then
    List<Comment> comments = selectTaskComments(TASK_ID);
    assertEquals(Collections.emptyList(), comments, "The comments list should be empty");

    // triggers a db clean up
    deleteTask(TASK_ID, true);
  }

  @Test
  public void testUpdateTaskCommentWithoutAuthorization() {
    // given
    createTask(TASK_ID);
    Comment createdComment = createComment(TASK_ID, null, "originalComment");

    try {
      // when
      taskService.updateTaskComment(TASK_ID, createdComment.getId(), "updateMessage");
      fail("Exception expected: It should not be possible to delete a comment.");
    } catch (AuthorizationException e) {
      // then
      testRule.assertTextPresent(
          "The user with id 'test' does not have one of the following permissions: 'TASK_WORK' permission on resource 'myTask' of type 'Task' or 'UPDATE' permission on resource 'myTask' of type 'Task'",
          e.getMessage());
    }

    // triggers a db clean up
    deleteTask(TASK_ID, true);
  }

  @Test
  public void testUpdateTaskComment() {
    // given
    createTask(TASK_ID);
    String commentMessage = "OriginalCommentMessage";
    String updatedMessage = "UpdatedCommentMessage";
    Comment comment = createComment(TASK_ID, null, commentMessage);
    createGrantAuthorization(TASK, TASK_ID, userId, UPDATE);

    // when
    taskService.updateTaskComment(TASK_ID, comment.getId(), updatedMessage);

    // then
    List<Comment> comments = selectTaskComments(TASK_ID);
    assertFalse(comments.isEmpty(), "The comments list should not be empty");
    assertEquals(updatedMessage, comments.get(0).getFullMessage());

    // triggers a db clean up
    deleteTask(TASK_ID, true);
  }

  @Test
  @Deployment(resources = "org/cibseven/bpm/engine/test/api/oneTaskProcess.bpmn20.xml")
  public void testDeleteProcessTaskCommentWithoutAuthorization() {
    // given
    ProcessInstance processInstance = startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    Comment createdComment = createComment(task.getId(), processInstance.getId(), "aComment");

    try {
      // when
      taskService.deleteTaskComment(task.getId(), createdComment.getId());
      fail("Exception expected: It should not be possible to delete a comment.");
    } catch (AuthorizationException e) {
      // then
      testRule.assertTextPresent(
          "The user with id 'test' does not have one of the following permissions: 'TASK_WORK' permission on resource",
          e.getMessage());
    }
  }

  @Test
  @Deployment(resources = "org/cibseven/bpm/engine/test/api/oneTaskProcess.bpmn20.xml")
  public void testDeleteProcessTaskComment() {
    // given
    ProcessInstance processInstance = startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    task.setAssignee("demo");
    Comment createdComment = createComment(task.getId(), processInstance.getId(), "aComment");
    createGrantAuthorization(TASK, task.getId(), userId, UPDATE);

    // when
    taskService.deleteTaskComment(task.getId(), createdComment.getId());

    // then
    Comment shouldBeDeleletedComment = selectTaskComment(task.getId(), createdComment.getId());
    assertNull(shouldBeDeleletedComment);
  }

  @Test
  @Deployment(resources = "org/cibseven/bpm/engine/test/api/oneTaskProcess.bpmn20.xml")
  public void testDeleteProcessTaskCommentsWithoutAuthorization() {
    // given
    ProcessInstance processInstance = startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    createComment(task.getId(), processInstance.getId(), "aComment");

    try {
      // when
      taskService.deleteTaskComments(task.getId());
      fail("Exception expected: It should not be possible to delete a comment.");
    } catch (AuthorizationException e) {
      // then
      testRule.assertTextPresent(
          "The user with id 'test' does not have one of the following permissions: 'TASK_WORK' permission on resource",
          e.getMessage());
    }
  }

  @Test
  @Deployment(resources = "org/cibseven/bpm/engine/test/api/oneTaskProcess.bpmn20.xml")
  public void testDeleteProcessTaskComments() {
    // given
    ProcessInstance processInstance = startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    createComment(task.getId(), processInstance.getId(), "aCommentOne");
    createComment(task.getId(), processInstance.getId(), "aCommentTwo");

    createGrantAuthorization(TASK, task.getId(), userId, UPDATE);

    // when
    taskService.deleteTaskComments(task.getId());

    // then
    List<Comment> comments = selectTaskComments(task.getId());
    assertEquals(Collections.emptyList(), comments, "The comments list should be empty");
  }

  @Test
  @Deployment(resources = "org/cibseven/bpm/engine/test/api/oneTaskProcess.bpmn20.xml")
  public void testUpdateProcessTaskCommentWithoutAuthorization() {
    // given
    ProcessInstance processInstance = startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();

    Comment createdComment = createComment(task.getId(), processInstance.getId(), "originalComment");

    try {
      // when
      taskService.updateTaskComment(task.getId(), createdComment.getId(), "updateMessage");
      fail("Exception expected: It should not be possible to delete a comment.");
    } catch (AuthorizationException e) {
      // then
      testRule.assertTextPresent(
          "The user with id 'test' does not have one of the following permissions: 'TASK_WORK' permission on resource",
          e.getMessage());
    }
  }

  @Test
  @Deployment(resources = "org/cibseven/bpm/engine/test/api/oneTaskProcess.bpmn20.xml")
  public void testUpdateProcessTaskComment() {
    // given
    ProcessInstance processInstance = startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    task.setAssignee("demo");

    String commentMessage = "OriginalCommentMessage";
    String updatedMessage = "UpdatedCommentMessage";
    Comment comment = createComment(task.getId(), processInstance.getId(), commentMessage);
    createGrantAuthorization(TASK, task.getId(), userId, UPDATE);

    // when
    taskService.updateTaskComment(task.getId(), comment.getId(), updatedMessage);

    // then
    List<Comment> comments = selectTaskComments(task.getId());
    assertFalse(comments.isEmpty(), "The comments list should not be empty");
    assertEquals(updatedMessage, comments.get(0).getFullMessage());
  }

  // create comment, see CIB7-1757 //////////////////////////////////////////

  @Test
  public void shouldNotCreateStandaloneTaskCommentWithoutAuthorization() {
    // given
    createTask(TASK_ID);

    // when/then
    assertThatThrownBy(() -> taskService.createComment(TASK_ID, null, "aComment"))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'TASK_WORK' permission on resource 'myTask' of type 'Task'")
        .hasMessageContaining("'UPDATE' permission on resource 'myTask' of type 'Task'");
    assertThat(selectTaskComments(TASK_ID)).isEmpty();
  }

  @Test
  public void shouldCreateStandaloneTaskCommentWithTaskWorkPermission() {
    // given
    createTask(TASK_ID);
    createGrantAuthorization(TASK, TASK_ID, userId, TASK_WORK);

    // when
    Comment comment = taskService.createComment(TASK_ID, null, "aComment");

    // then
    assertThat(comment).isNotNull();
    assertThat(selectTaskComments(TASK_ID)).hasSize(1);
  }

  @Test
  public void shouldNotCreateStandaloneTaskCommentWithReadPermissionOnly() {
    // given
    createTask(TASK_ID);
    createGrantAuthorization(TASK, TASK_ID, userId, READ);

    // when/then
    assertThatThrownBy(() -> taskService.createComment(TASK_ID, null, "aComment"))
        .isInstanceOf(AuthorizationException.class);
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotCreateProcessTaskCommentWithoutAuthorization() {
    // given
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    Task task = selectSingleTask();

    // when/then
    assertThatThrownBy(() -> taskService.createComment(task.getId(), processInstanceId, "aComment"))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'UPDATE_TASK' permission on resource 'oneTaskProcess' of type 'ProcessDefinition'");
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldCreateProcessTaskCommentWithUpdateTaskPermissionOnProcessDefinition() {
    // given
    String processInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    Task task = selectSingleTask();
    createGrantAuthorization(PROCESS_DEFINITION, PROCESS_KEY, userId, UPDATE_TASK);

    // when
    Comment comment = taskService.createComment(task.getId(), processInstanceId, "aComment");

    // then
    assertThat(comment).isNotNull();
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotCreateCommentForTaskAndForeignProcessInstance() {
    // given
    String foreignProcessInstanceId = startProcessInstanceByKey(PROCESS_KEY).getId();
    createTask(TASK_ID);
    createGrantAuthorization(TASK, TASK_ID, userId, UPDATE);

    // when/then
    assertThatThrownBy(() -> taskService.createComment(TASK_ID, foreignProcessInstanceId, "aComment"))
        .isInstanceOf(BadUserRequestException.class)
        .hasMessageContaining("Cannot create a comment for task 'myTask' and process instance '"
            + foreignProcessInstanceId + "'");
    assertThat(selectProcessInstanceComments(foreignProcessInstanceId)).isEmpty();
  }

  @Test
  public void shouldNotCreateCommentForUnknownTaskWithoutAdmin() {
    // when/then
    assertThatThrownBy(() -> taskService.createComment(UNKNOWN_ID, null, "aComment"))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("Required admin authenticated group or user");
  }

  @Test
  public void shouldCreateCommentForUnknownTaskAsAdmin() {
    // given
    identityService.setAuthentication(userId, Collections.singletonList(Groups.CAMUNDA_ADMIN));

    // when
    Comment comment = taskService.createComment(UNKNOWN_ID, null, "aComment");

    // then
    assertThat(comment).isNotNull();
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotCreateCommentForCompletedTaskWithoutHistoricPermission() {
    // comments are history data, so the runtime check alone would protect nothing
    // given
    startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    completeTask(task.getId());

    // when/then
    assertThatThrownBy(() -> taskService.createComment(task.getId(), null, "aComment"))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'DELETE_HISTORY' permission on resource '" + PROCESS_KEY + "' of type 'ProcessDefinition'");
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldCreateCommentForCompletedTaskWithHistoricTaskAll() {
    // given
    startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    completeTask(task.getId());
    createGrantAuthorization(HISTORIC_TASK, task.getId(), userId, HistoricTaskPermissions.ALL);

    // when
    Comment comment = taskService.createComment(task.getId(), null, "aComment");

    // then
    assertThat(comment).isNotNull();
  }

  @Test
  public void shouldNotCreateCommentForCompletedStandaloneTaskWithoutHistoricTaskAll() {
    // given
    createTask(TASK_ID);
    completeTask(TASK_ID);

    // when/then
    assertThatThrownBy(() -> taskService.createComment(TASK_ID, null, "aComment"))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'ALL' permission on resource '" + TASK_ID + "' of type 'HistoricTask'");
  }

  @Test
  public void shouldCreateCommentForCompletedStandaloneTaskWithHistoricTaskAll() {
    // given
    createTask(TASK_ID);
    completeTask(TASK_ID);
    createGrantAuthorization(HISTORIC_TASK, TASK_ID, userId, HistoricTaskPermissions.ALL);

    // when
    taskService.createComment(TASK_ID, null, "aComment");

    // then
    assertThat(selectTaskComments(TASK_ID)).hasSize(1);
  }

  // read comments, see CIB7-1757 ///////////////////////////////////////////

  @Test
  public void shouldNotGetStandaloneTaskCommentsWithoutAuthorization() {
    // given
    createTask(TASK_ID);
    createComment(TASK_ID, null, "aComment");

    // when/then
    assertThatThrownBy(() -> taskService.getTaskComments(TASK_ID))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'READ' permission on resource 'myTask' of type 'Task'");
  }

  @Test
  public void shouldNotGetStandaloneTaskCommentsWithUpdatePermissionOnly() {
    // given
    createTask(TASK_ID);
    createComment(TASK_ID, null, "aComment");
    createGrantAuthorization(TASK, TASK_ID, userId, UPDATE);

    // when/then
    assertThatThrownBy(() -> taskService.getTaskComments(TASK_ID))
        .isInstanceOf(AuthorizationException.class);
  }

  @Test
  public void shouldGetStandaloneTaskCommentsWithReadPermission() {
    // given
    createTask(TASK_ID);
    createComment(TASK_ID, null, "aComment");
    createGrantAuthorization(TASK, TASK_ID, userId, READ);

    // when/then
    assertThat(taskService.getTaskComments(TASK_ID)).hasSize(1);
  }

  @Test
  public void shouldNotGetSingleStandaloneTaskCommentWithoutAuthorization() {
    // given
    createTask(TASK_ID);
    Comment comment = createComment(TASK_ID, null, "aComment");

    // when/then
    assertThatThrownBy(() -> taskService.getTaskComment(TASK_ID, comment.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'READ' permission on resource 'myTask' of type 'Task'");
  }

  @Test
  public void shouldGetSingleStandaloneTaskCommentWithReadPermission() {
    // given
    createTask(TASK_ID);
    Comment comment = createComment(TASK_ID, null, "aComment");
    createGrantAuthorization(TASK, TASK_ID, userId, READ);

    // when/then
    assertThat(taskService.getTaskComment(TASK_ID, comment.getId()).getFullMessage()).isEqualTo("aComment");
  }

  @Test
  public void shouldNotGetTaskEventsWithoutAuthorization() {
    // given
    createTask(TASK_ID);
    createComment(TASK_ID, null, "aComment");

    // when/then
    assertThatThrownBy(() -> taskService.getTaskEvents(TASK_ID))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'READ' permission on resource 'myTask' of type 'Task'");
  }

  @Test
  public void shouldGetTaskEventsWithReadPermission() {
    // given
    createTask(TASK_ID);
    createComment(TASK_ID, null, "aComment");
    createGrantAuthorization(TASK, TASK_ID, userId, READ);

    // when/then
    assertThat(taskService.getTaskEvents(TASK_ID)).isNotEmpty();
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotGetProcessTaskCommentsWithoutAuthorization() {
    // given
    startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    Comment comment = createComment(task.getId(), null, "aComment");

    // when/then
    assertThatThrownBy(() -> taskService.getTaskComments(task.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'READ_TASK' permission on resource 'oneTaskProcess' of type 'ProcessDefinition'");
    assertThatThrownBy(() -> taskService.getTaskComment(task.getId(), comment.getId()))
        .isInstanceOf(AuthorizationException.class);
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldGetProcessTaskCommentsWithReadTaskPermissionOnProcessDefinition() {
    // given
    startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    Comment comment = createComment(task.getId(), null, "aComment");
    createGrantAuthorization(PROCESS_DEFINITION, PROCESS_KEY, userId, READ_TASK);

    // when/then
    assertThat(taskService.getTaskComments(task.getId())).hasSize(1);
    assertThat(taskService.getTaskComment(task.getId(), comment.getId())).isNotNull();
  }

  @Test
  public void shouldNotGetCommentsForUnknownTaskWithoutAdmin() {
    // given
    Comment comment = createComment(UNKNOWN_ID, null, "aComment");

    // when/then
    assertThatThrownBy(() -> taskService.getTaskComments(UNKNOWN_ID))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("Required admin authenticated group or user");
    assertThatThrownBy(() -> taskService.getTaskComment(UNKNOWN_ID, comment.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("Required admin authenticated group or user");
  }

  @Test
  public void shouldGetEmptyCommentsForNullTaskIdWithoutAuthorization() {
    assertThat(taskService.getTaskComments(null)).isEmpty();
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotReadCommentOfCompletedTaskWithoutReadHistory() {
    // given
    startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    Comment comment = createComment(task.getId(), null, "aComment");
    completeTask(task.getId());

    // when/then
    assertThatThrownBy(() -> taskService.getTaskComment(task.getId(), comment.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'READ_HISTORY' permission on resource '" + PROCESS_KEY + "' of type 'ProcessDefinition'");
    assertThatThrownBy(() -> taskService.getTaskComments(task.getId()))
        .isInstanceOf(AuthorizationException.class);
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldReadCommentOfCompletedTaskWithReadHistory() {
    // given
    startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    Comment comment = createComment(task.getId(), null, "aComment");
    completeTask(task.getId());
    createGrantAuthorization(PROCESS_DEFINITION, PROCESS_KEY, userId, READ_HISTORY);

    // when/then
    assertThat(taskService.getTaskComment(task.getId(), comment.getId())).isNotNull();
    assertThat(taskService.getTaskComments(task.getId())).hasSize(1);
    assertThat(taskService.getTaskEvents(task.getId())).isNotEmpty();
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldReadCommentOfCompletedTaskWithHistoricTaskReadPermission() {
    // given
    processEngineConfiguration.setEnableHistoricInstancePermissions(true);
    startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    Comment comment = createComment(task.getId(), null, "aComment");
    completeTask(task.getId());
    createGrantAuthorization(HISTORIC_TASK, task.getId(), userId, HistoricTaskPermissions.READ);

    // when/then
    assertThat(taskService.getTaskComment(task.getId(), comment.getId())).isNotNull();
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldCreateCommentForCompletedTaskWithDeleteHistory() {
    // given
    startProcessInstanceByKey(PROCESS_KEY);
    Task task = selectSingleTask();
    completeTask(task.getId());
    createGrantAuthorization(PROCESS_DEFINITION, PROCESS_KEY, userId, DELETE_HISTORY);

    // when
    taskService.createComment(task.getId(), null, "aComment");

    // then
    assertThat(selectTaskComments(task.getId())).hasSize(1);
  }

  @Test
  public void shouldReadCommentOfCompletedStandaloneTaskLikeTheHistoricTaskQuery() {
    // as visible as the task in the history, see CIB7-2521
    // given
    createTask(TASK_ID);
    Comment comment = createComment(TASK_ID, null, "aComment");
    completeTask(TASK_ID);

    // when/then
    assertThat(historyService.createHistoricTaskInstanceQuery().taskId(TASK_ID).singleResult()).isNotNull();
    assertThat(taskService.getTaskComment(TASK_ID, comment.getId())).isNotNull();
    assertThat(taskService.getTaskComments(TASK_ID)).hasSize(1);
    assertThat(taskService.getTaskEvents(TASK_ID)).isNotEmpty();
  }

  @Test
  public void shouldNotReadCommentOfCompletedStandaloneTaskWithHistoricInstancePermissions() {
    // given
    processEngineConfiguration.setEnableHistoricInstancePermissions(true);
    createTask(TASK_ID);
    Comment comment = createComment(TASK_ID, null, "aComment");
    completeTask(TASK_ID);

    // when/then
    assertThat(historyService.createHistoricTaskInstanceQuery().taskId(TASK_ID).count()).isZero();
    assertThatThrownBy(() -> taskService.getTaskComment(TASK_ID, comment.getId()))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'READ' permission on resource '" + TASK_ID + "' of type 'HistoricTask'");
    assertThatThrownBy(() -> taskService.getTaskComments(TASK_ID))
        .isInstanceOf(AuthorizationException.class);
  }

  // enforceCommentPermissions disabled //////////////////////////////////////

  @Test
  public void shouldEnableCommentPermissionsByDefault() {
    assertThat(new StandaloneInMemProcessEngineConfiguration().isEnforceCommentPermissions()).isTrue();
  }

  @Test
  public void shouldNotCheckReadsWhenEnforcementDisabled() {
    // given
    processEngineConfiguration.setEnforceCommentPermissions(false);
    createTask(TASK_ID);
    Comment comment = createComment(TASK_ID, null, "aComment");

    // when/then
    assertThat(taskService.getTaskComments(TASK_ID)).hasSize(1);
    assertThat(taskService.getTaskComment(TASK_ID, comment.getId())).isNotNull();
    assertThat(taskService.getTaskEvents(TASK_ID)).isNotEmpty();
  }

  @Test
  public void shouldNotCheckCreateWhenEnforcementDisabled() {
    // given
    processEngineConfiguration.setEnforceCommentPermissions(false);
    createTask(TASK_ID);

    // when
    Comment comment = taskService.createComment(TASK_ID, null, "aComment");

    // then
    assertThat(comment).isNotNull();
  }

  @Test
  public void shouldStillCheckDeleteWhenEnforcementDisabled() {
    // given
    processEngineConfiguration.setEnforceCommentPermissions(false);
    createTask(TASK_ID);
    Comment comment = createComment(TASK_ID, null, "aComment");

    // when/then
    assertThatThrownBy(() -> taskService.deleteTaskComment(TASK_ID, comment.getId()))
        .isInstanceOf(AuthorizationException.class);
  }

  // helpers /////////////////////////////////////////////////////////////////

  protected void deleteCommentsOfTask(String taskId) {
    processEngineConfiguration.getCommandExecutorTxRequired().execute(commandContext -> {
      commandContext.getCommentManager().deleteCommentsByTaskId(taskId);
      return null;
    });
  }

}
