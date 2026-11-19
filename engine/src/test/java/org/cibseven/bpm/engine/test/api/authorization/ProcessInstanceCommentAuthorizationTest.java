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
import static org.cibseven.bpm.engine.authorization.Permissions.UPDATE;
import static org.cibseven.bpm.engine.authorization.Resources.HISTORIC_PROCESS_INSTANCE;
import static org.cibseven.bpm.engine.authorization.Resources.PROCESS_DEFINITION;
import static org.cibseven.bpm.engine.authorization.Resources.PROCESS_INSTANCE;
import static org.cibseven.bpm.engine.authorization.Resources.TASK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Collections;
import java.util.List;
import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.authorization.HistoricProcessInstancePermissions;
import org.cibseven.bpm.engine.task.Comment;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.Deployment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ProcessInstanceCommentAuthorizationTest extends AuthorizationTest {
  protected static final String ONE_TASK_PROCESS_KEY = "oneTaskProcess";
  protected static final String ONE_TASK_PROCESS = "org/cibseven/bpm/engine/test/api/oneTaskProcess.bpmn20.xml";

  protected boolean originalEnforceCommentPermissions;

  @BeforeEach
  public void enforceCommentPermissions() {
    originalEnforceCommentPermissions = processEngineConfiguration.isEnforceCommentPermissions();
    processEngineConfiguration.setEnforceCommentPermissions(true);
  }

  @AfterEach
  public void cleanUp() {
    try {
      processEngineConfiguration.getCommandExecutorTxRequired().execute(commandContext -> {
        commandContext.getCommentManager().deleteCommentsByTaskId("");
        return null;
      });
    } finally {
      processEngineConfiguration.setEnforceCommentPermissions(originalEnforceCommentPermissions);
    }
  }

  @Deployment(resources = "org/cibseven/bpm/engine/test/api/oneTaskProcess.bpmn20.xml")
  @Test
  public void testDeleteProcessInstanceTaskCommentWithoutAuthorization() {
    // given
    String processInstanceId = startProcessInstanceByKey(ONE_TASK_PROCESS_KEY).getId();
    createTask(TASK_ID);

    Comment createdComment = createComment(TASK_ID, processInstanceId, "aComment");

    try {
      // when
      taskService.deleteProcessInstanceComment(processInstanceId, createdComment.getId());
      fail("Exception expected: It should not be possible to delete a task.");
    } catch (AuthorizationException e) {
      // then
      testRule.assertTextPresent("The user with id 'test' does not have one of the following permissions: 'UPDATE'",
          e.getMessage());
    }

    // triggers a db clean up
    deleteTask(TASK_ID, true);
  }

  @Deployment(resources = "org/cibseven/bpm/engine/test/api/oneTaskProcess.bpmn20.xml")
  @Test
  public void testDeleteProcessInstanceTaskComment() {
    // given
    createTask(TASK_ID);
    String processInstanceId = startProcessInstanceByKey(ONE_TASK_PROCESS_KEY).getId();
    Comment createdComment = createComment(TASK_ID, processInstanceId, "aComment");
    createGrantAuthorization(PROCESS_INSTANCE, processInstanceId, userId, UPDATE);

    // when
    taskService.deleteProcessInstanceComment(processInstanceId, createdComment.getId());

    // then
    List<Comment> deletedCommentLst = selectProcessInstanceComments(processInstanceId);
    assertEquals(Collections.emptyList(), deletedCommentLst, "The comments list should be empty");

    // triggers a db clean up
    deleteTask(TASK_ID, true);
  }

  @Deployment(resources = "org/cibseven/bpm/engine/test/api/oneTaskProcess.bpmn20.xml")
  @Test
  public void testDeleteProcessInstanceTaskCommentsWithoutAuthorization() {
    // given
    createTask(TASK_ID);
    String processInstanceId = startProcessInstanceByKey(ONE_TASK_PROCESS_KEY).getId();
    createComment(TASK_ID, processInstanceId, "aComment");

    try {
      // when
      taskService.deleteProcessInstanceComments(processInstanceId);
      fail("Exception expected: It should not be possible to delete a comment.");
    } catch (AuthorizationException e) {
      // then
      testRule.assertTextPresent("The user with id 'test' does not have one of the following permissions: 'UPDATE'",
          e.getMessage());
    }

    // triggers a db clean up
    deleteTask(TASK_ID, true);
  }

  @Deployment(resources = "org/cibseven/bpm/engine/test/api/oneTaskProcess.bpmn20.xml")
  @Test
  public void testDeleteProcessInstanceTaskComments() {
    // given
    createTask(TASK_ID);
    String processInstanceId = startProcessInstanceByKey(ONE_TASK_PROCESS_KEY).getId();
    createComment(TASK_ID, processInstanceId, "aCommentOne");
    createComment(TASK_ID, processInstanceId, "aCommentTwo");

    createGrantAuthorization(PROCESS_INSTANCE, processInstanceId, userId, UPDATE);

    // when
    taskService.deleteProcessInstanceComments(processInstanceId);

    // then
    List<Comment> comments = selectProcessInstanceComments(processInstanceId);
    assertEquals(Collections.emptyList(), comments, "The comments list should be empty");

    // triggers a db clean up
    deleteTask(TASK_ID, true);
  }

  @Deployment(resources = "org/cibseven/bpm/engine/test/api/oneTaskProcess.bpmn20.xml")
  @Test
  public void testUpdateProcessInstanceTaskCommentWithoutAuthorization() {
    // given
    createTask(TASK_ID);
    String processInstanceId = startProcessInstanceByKey(ONE_TASK_PROCESS_KEY).getId();
    Comment createdComment = createComment(TASK_ID, processInstanceId, "originalComment");

    try {
      // when
      taskService.updateProcessInstanceComment(processInstanceId, createdComment.getId(), "updateMessage");
      fail("Exception expected: It should not be possible to delete a task.");
    } catch (AuthorizationException e) {
      // then
      testRule.assertTextPresent("The user with id 'test' does not have one of the following permissions: 'UPDATE'",
          e.getMessage());
    }

    deleteTask(TASK_ID, true);
  }

  @Deployment(resources = "org/cibseven/bpm/engine/test/api/oneTaskProcess.bpmn20.xml")
  @Test
  public void testUpdateProcessInstanceTaskComment() {
    // given
    String taskId = "myTask";
    createTask(taskId);
    String processInstanceId = startProcessInstanceByKey(ONE_TASK_PROCESS_KEY).getId();

    String commentMessage = "OriginalCommentMessage";
    String updatedMessage = "UpdatedCommentMessage";
    Comment comment = createComment(taskId, processInstanceId, commentMessage);
    createGrantAuthorization(PROCESS_INSTANCE, processInstanceId, userId, UPDATE);

    // when
    taskService.updateProcessInstanceComment(processInstanceId, comment.getId(), updatedMessage);

    // then
    List<Comment> comments = selectProcessInstanceComments(processInstanceId);
    assertFalse(comments.isEmpty(), "The comments list should not be empty");
    assertEquals(updatedMessage, comments.get(0).getFullMessage());

    // triggers a db clean up
    deleteTask(taskId, true);
  }

  @Deployment(resources = "org/cibseven/bpm/engine/test/api/oneTaskProcess.bpmn20.xml")
  @Test
  public void testDeleteProcessInstanceCommentWithoutAuthorization() {
    // given
    String processInstanceId = startProcessInstanceByKey(ONE_TASK_PROCESS_KEY).getId();

    Comment createdComment = createComment(null, processInstanceId, "aComment");

    try {
      // when
      taskService.deleteProcessInstanceComment(processInstanceId, createdComment.getId());
      fail("Exception expected: It should not be possible to delete a task.");
    } catch (AuthorizationException e) {
      // then
      testRule.assertTextPresent("The user with id 'test' does not have one of the following permissions: 'UPDATE'",
          e.getMessage());
    }
  }

  @Deployment(resources = "org/cibseven/bpm/engine/test/api/oneTaskProcess.bpmn20.xml")
  @Test
  public void testDeleteProcessInstanceComment() {
    // given
    String processInstanceId = startProcessInstanceByKey(ONE_TASK_PROCESS_KEY).getId();
    Comment createdComment = createComment(null, processInstanceId, "aComment");
    createGrantAuthorization(PROCESS_INSTANCE, processInstanceId, userId, UPDATE);

    // when
    taskService.deleteProcessInstanceComment(processInstanceId, createdComment.getId());

    // then
    List<Comment> deletedCommentLst = selectProcessInstanceComments(processInstanceId);
    assertEquals(Collections.emptyList(), deletedCommentLst, "The comments list should be empty");
  }

  @Deployment(resources = "org/cibseven/bpm/engine/test/api/oneTaskProcess.bpmn20.xml")
  @Test
  public void testDeleteProcessInstanceCommentsWithoutAuthorization() {
    // given
    String processInstanceId = startProcessInstanceByKey(ONE_TASK_PROCESS_KEY).getId();
    createComment(null, processInstanceId, "aComment");

    try {
      // when
      taskService.deleteProcessInstanceComments(processInstanceId);
      fail("Exception expected: It should not be possible to delete a comment.");
    } catch (AuthorizationException e) {
      // then
      testRule.assertTextPresent("The user with id 'test' does not have one of the following permissions: 'UPDATE'",
          e.getMessage());
    }
  }

  @Deployment(resources = "org/cibseven/bpm/engine/test/api/oneTaskProcess.bpmn20.xml")
  @Test
  public void testDeleteProcessInstanceComments() {
    // given
    String processInstanceId = startProcessInstanceByKey(ONE_TASK_PROCESS_KEY).getId();
    createComment(null, processInstanceId, "aCommentOne");
    createComment(null, processInstanceId, "aCommentTwo");

    createGrantAuthorization(PROCESS_INSTANCE, processInstanceId, userId, UPDATE);

    // when
    taskService.deleteProcessInstanceComments(processInstanceId);

    // then
    List<Comment> comments = selectProcessInstanceComments(processInstanceId);
    assertEquals(Collections.emptyList(), comments, "The comments list should be empty");
  }

  @Deployment(resources = "org/cibseven/bpm/engine/test/api/oneTaskProcess.bpmn20.xml")
  @Test
  public void testUpdateProcessInstanceCommentWithoutAuthorization() {
    // given
    String processInstanceId = startProcessInstanceByKey(ONE_TASK_PROCESS_KEY).getId();
    Comment createdComment = createComment(null, processInstanceId, "originalComment");

    try {
      // when
      taskService.updateProcessInstanceComment(processInstanceId, createdComment.getId(), "updateMessage");
      fail("Exception expected: It should not be possible to delete a task.");
    } catch (AuthorizationException e) {
      // then
      testRule.assertTextPresent("The user with id 'test' does not have one of the following permissions: 'UPDATE'",
          e.getMessage());
    }
  }

  @Deployment(resources = "org/cibseven/bpm/engine/test/api/oneTaskProcess.bpmn20.xml")
  @Test
  public void testUpdateProcessInstanceComment() {
    // given
    String processInstanceId = startProcessInstanceByKey(ONE_TASK_PROCESS_KEY).getId();

    String commentMessage = "OriginalCommentMessage";
    String updatedMessage = "UpdatedCommentMessage";
    Comment comment = createComment(null, processInstanceId, commentMessage);
    createGrantAuthorization(PROCESS_INSTANCE, processInstanceId, userId, UPDATE);

    // when
    taskService.updateProcessInstanceComment(processInstanceId, comment.getId(), updatedMessage);

    // then
    List<Comment> comments = selectProcessInstanceComments(processInstanceId);
    assertFalse(comments.isEmpty(), "The comments list should not be empty");
    assertEquals(updatedMessage, comments.get(0).getFullMessage());
  }

  // create comment, see CIB7-1757 //////////////////////////////////////////

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotCreateProcessInstanceCommentWithoutAuthorization() {
    // given
    String processInstanceId = startProcessInstanceByKey(ONE_TASK_PROCESS_KEY).getId();

    // when/then
    assertThatThrownBy(() -> taskService.createComment(null, processInstanceId, "aComment"))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'UPDATE' permission on resource '" + processInstanceId + "' of type 'ProcessInstance'");
    assertThat(selectProcessInstanceComments(processInstanceId)).isEmpty();
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldCreateProcessInstanceCommentWithUpdatePermission() {
    // given
    String processInstanceId = startProcessInstanceByKey(ONE_TASK_PROCESS_KEY).getId();
    createGrantAuthorization(PROCESS_INSTANCE, processInstanceId, userId, UPDATE);

    // when
    Comment comment = taskService.createComment(null, processInstanceId, "aComment");

    // then
    assertThat(comment).isNotNull();
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotCreateCommentForCompletedProcessInstanceWithoutHistoricPermission() {
    // given
    String processInstanceId = startProcessInstanceByKey(ONE_TASK_PROCESS_KEY).getId();
    completeTask(selectSingleTask().getId());

    // when/then
    assertThatThrownBy(() -> taskService.createComment(null, processInstanceId, "aComment"))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'DELETE_HISTORY' permission on resource '" + ONE_TASK_PROCESS_KEY
            + "' of type 'ProcessDefinition'");
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldCreateCommentForCompletedProcessInstanceWithDeleteHistory() {
    // given
    String processInstanceId = startProcessInstanceByKey(ONE_TASK_PROCESS_KEY).getId();
    completeTask(selectSingleTask().getId());
    createGrantAuthorization(PROCESS_DEFINITION, ONE_TASK_PROCESS_KEY, userId, DELETE_HISTORY);

    // when
    taskService.createComment(null, processInstanceId, "aComment");

    // then
    assertThat(selectProcessInstanceComments(processInstanceId)).hasSize(1);
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldCreateCommentForCompletedProcessInstanceWithHistoricProcessInstanceAll() {
    // given
    String processInstanceId = startProcessInstanceByKey(ONE_TASK_PROCESS_KEY).getId();
    completeTask(selectSingleTask().getId());
    createGrantAuthorization(HISTORIC_PROCESS_INSTANCE, processInstanceId, userId,
        HistoricProcessInstancePermissions.ALL);

    // when
    taskService.createComment(null, processInstanceId, "aComment");

    // then
    assertThat(selectProcessInstanceComments(processInstanceId)).hasSize(1);
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldCheckProcessInstanceWhenTaskIdIsBlank() {
    // given
    String processInstanceId = startProcessInstanceByKey(ONE_TASK_PROCESS_KEY).getId();

    // when/then
    assertThatThrownBy(() -> taskService.createComment("", processInstanceId, "aComment"))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'UPDATE' permission on resource '" + processInstanceId + "' of type 'ProcessInstance'");
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldCreateCommentWithBlankTaskIdWithUpdatePermissionOnProcessInstance() {
    // given
    String processInstanceId = startProcessInstanceByKey(ONE_TASK_PROCESS_KEY).getId();
    createGrantAuthorization(PROCESS_INSTANCE, processInstanceId, userId, UPDATE);

    // when
    taskService.createComment("", processInstanceId, "aComment");

    // then
    assertThat(selectProcessInstanceComments(processInstanceId)).hasSize(1);
  }

  @Test
  public void shouldNotCreateCommentWithoutOwnerWithoutAdmin() {
    // when/then
    assertThatThrownBy(() -> taskService.createComment("", null, "aComment"))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("Required admin authenticated group or user");
  }

  @Test
  public void shouldNotReadCommentsOfBlankTaskIdWithoutAdmin() {
    // given
    createComment("", null, "aComment");

    // when/then
    assertThatThrownBy(() -> taskService.getTaskComments(""))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("Required admin authenticated group or user");
  }

  @Test
  public void shouldNotCreateCommentForUnknownProcessInstanceWithoutAdmin() {
    // when/then
    assertThatThrownBy(() -> taskService.createComment(null, "anUnknownId", "aComment"))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("Required admin authenticated group or user");
  }

  // read comments, see CIB7-1757 ///////////////////////////////////////////

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotGetProcessInstanceCommentsWithoutAuthorization() {
    // given
    String processInstanceId = startProcessInstanceByKey(ONE_TASK_PROCESS_KEY).getId();
    createComment(null, processInstanceId, "aComment");

    // when/then
    assertThatThrownBy(() -> taskService.getProcessInstanceComments(processInstanceId))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'READ' permission on resource '" + processInstanceId + "' of type 'ProcessInstance'");
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldGetProcessInstanceCommentsWithReadPermission() {
    // given
    String processInstanceId = startProcessInstanceByKey(ONE_TASK_PROCESS_KEY).getId();
    createComment(null, processInstanceId, "aComment");
    createGrantAuthorization(PROCESS_INSTANCE, processInstanceId, userId, READ);

    // when/then
    assertThat(taskService.getProcessInstanceComments(processInstanceId)).hasSize(1);
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldDropTaskCommentsTheCallerCannotReadFromProcessInstanceComments() {
    // given
    String processInstanceId = startProcessInstanceByKey(ONE_TASK_PROCESS_KEY).getId();
    Task task = selectSingleTask();
    createComment(null, processInstanceId, "aProcessInstanceComment");
    createComment(task.getId(), processInstanceId, "aTaskComment");
    createGrantAuthorization(PROCESS_INSTANCE, processInstanceId, userId, READ);

    // when
    List<Comment> comments = taskService.getProcessInstanceComments(processInstanceId);

    // then
    assertThat(comments).extracting(Comment::getFullMessage).containsExactly("aProcessInstanceComment");
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldKeepTaskCommentsTheCallerCanReadInProcessInstanceComments() {
    // given
    String processInstanceId = startProcessInstanceByKey(ONE_TASK_PROCESS_KEY).getId();
    Task task = selectSingleTask();
    createComment(null, processInstanceId, "aProcessInstanceComment");
    createComment(task.getId(), processInstanceId, "aTaskComment");
    createGrantAuthorization(PROCESS_INSTANCE, processInstanceId, userId, READ);
    createGrantAuthorization(TASK, task.getId(), userId, READ);

    // when/then
    assertThat(taskService.getProcessInstanceComments(processInstanceId)).hasSize(2);
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotGetCommentsOfCompletedProcessInstanceWithoutReadHistory() {
    // given
    String processInstanceId = startProcessInstanceByKey(ONE_TASK_PROCESS_KEY).getId();
    createComment(null, processInstanceId, "aComment");
    completeTask(selectSingleTask().getId());

    // when/then
    assertThatThrownBy(() -> taskService.getProcessInstanceComments(processInstanceId))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("'READ_HISTORY' permission on resource '" + ONE_TASK_PROCESS_KEY
            + "' of type 'ProcessDefinition'");
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldGetCommentsOfCompletedProcessInstanceWithReadHistory() {
    // given
    String processInstanceId = startProcessInstanceByKey(ONE_TASK_PROCESS_KEY).getId();
    createComment(null, processInstanceId, "aComment");
    completeTask(selectSingleTask().getId());
    createGrantAuthorization(PROCESS_DEFINITION, ONE_TASK_PROCESS_KEY, userId, READ_HISTORY);

    // when/then
    assertThat(taskService.getProcessInstanceComments(processInstanceId)).hasSize(1);
  }

  @Test
  @Deployment(resources = ONE_TASK_PROCESS)
  public void shouldNotCheckProcessInstanceCommentsWhenEnforcementDisabled() {
    // given
    processEngineConfiguration.setEnforceCommentPermissions(false);
    String processInstanceId = startProcessInstanceByKey(ONE_TASK_PROCESS_KEY).getId();
    Task task = selectSingleTask();
    createComment(task.getId(), processInstanceId, "aTaskComment");

    // when
    taskService.createComment(null, processInstanceId, "aComment");

    // then
    assertThat(taskService.getProcessInstanceComments(processInstanceId)).hasSize(2);
  }

}
