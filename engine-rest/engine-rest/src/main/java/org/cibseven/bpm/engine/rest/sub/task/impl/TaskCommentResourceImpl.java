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
package org.cibseven.bpm.engine.rest.sub.task.impl;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;
import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.exception.NullValueException;
import org.cibseven.bpm.engine.history.HistoricTaskInstance;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.identity.Authentication;
import org.cibseven.bpm.engine.rest.TaskRestService;
import org.cibseven.bpm.engine.rest.dto.task.CommentDto;
import org.cibseven.bpm.engine.rest.exception.InvalidRequestException;
import org.cibseven.bpm.engine.rest.sub.task.TaskCommentResource;
import org.cibseven.bpm.engine.task.Comment;

public class TaskCommentResourceImpl implements TaskCommentResource {

  private ProcessEngine engine;
  private String taskId;
  private String rootResourcePath;

  public TaskCommentResourceImpl(ProcessEngine engine, String taskId, String rootResourcePath) {
    this.engine = engine;
    this.taskId = taskId;
    this.rootResourcePath = rootResourcePath;
  }

  public List<CommentDto> getComments() {
    if (!isHistoryEnabled()) {
      return Collections.emptyList();
    }

    ensureTaskExists(Status.NOT_FOUND);

    List<Comment> taskComments = engine.getTaskService().getTaskComments(taskId);

    List<CommentDto> comments = new ArrayList<CommentDto>();
    for (Comment comment : taskComments) {
      comments.add(CommentDto.fromComment(comment));
    }

    return comments;
  }

  public CommentDto getComment(String commentId) {
    ensureHistoryEnabled(Status.NOT_FOUND);

    Comment comment = engine.getTaskService().getTaskComment(taskId, commentId);
    if (comment == null) {
      throw new InvalidRequestException(Status.NOT_FOUND, "Task comment with id " + commentId + " does not exist for task id '" + taskId + "'.");
    }

    return CommentDto.fromComment(comment);
  }

  public void deleteComment(String commentId) {
    ensureHistoryEnabled(Status.FORBIDDEN);
    ensureTaskExists(Status.NOT_FOUND);

    TaskService taskService = engine.getTaskService();
    try {
      taskService.deleteTaskComment(taskId, commentId);
    } catch (AuthorizationException e) {
      throw e;
    } catch (NullValueException e) {
      throw new InvalidRequestException(Status.BAD_REQUEST, e.getMessage());
    }
  }

  public void updateComment(CommentDto comment) {
    ensureHistoryEnabled(Status.FORBIDDEN);
    ensureTaskExists(Status.NOT_FOUND);

    try {
      engine.getTaskService().updateTaskComment(taskId, comment.getId(), comment.getMessage());
    } catch (AuthorizationException e) {
      throw e;
    } catch (NullValueException e) {
      throw new InvalidRequestException(Status.BAD_REQUEST, e.getMessage());
    }
  }

  public void deleteComments() {
    ensureHistoryEnabled(Status.FORBIDDEN);
    ensureTaskExists(Status.NOT_FOUND);
    TaskService taskService = engine.getTaskService();

    try {
      taskService.deleteTaskComments(taskId);
    } catch (AuthorizationException e) {
      throw e;
    } catch (NullValueException e) {
      throw new InvalidRequestException(Status.BAD_REQUEST, e.getMessage());
    }
  }

  public CommentDto createComment(UriInfo uriInfo, CommentDto commentDto) {
    ensureHistoryEnabled(Status.FORBIDDEN);
    ensureTaskExists(Status.BAD_REQUEST);

    Comment comment;

    String processInstanceId = commentDto.getProcessInstanceId();
    try {
      comment = engine.getTaskService().createComment(taskId, processInstanceId, commentDto.getMessage());
    }
    catch (ProcessEngineException e) {
      throw new InvalidRequestException(Status.BAD_REQUEST, e, "Not enough parameters submitted");
    }

    URI uri = uriInfo.getBaseUriBuilder()
      .path(rootResourcePath)
      .path(TaskRestService.PATH)
      .path(taskId + "/comment/" + comment.getId())
      .build();

    CommentDto resultDto = CommentDto.fromComment(comment);

    // GET /
    resultDto.addReflexiveLink(uri, HttpMethod.GET, "self");

    return resultDto;
  }

  private boolean isHistoryEnabled() {
    IdentityService identityService = engine.getIdentityService();
    Authentication currentAuthentication = identityService.getCurrentAuthentication();
    try {
      identityService.clearAuthentication();
      int historyLevel = engine.getManagementService().getHistoryLevel();
      return historyLevel > ProcessEngineConfigurationImpl.HISTORYLEVEL_NONE;
    } finally {
      identityService.setAuthentication(currentAuthentication);
    }
  }

  private void ensureHistoryEnabled(Status status) {
    if (!isHistoryEnabled()) {
      throw new InvalidRequestException(status, "History is not enabled");
    }
  }

  private void ensureTaskExists(Status status) {
    HistoricTaskInstance historicTaskInstance = engine.getHistoryService().createHistoricTaskInstanceQuery().taskId(taskId).singleResult();
    if (historicTaskInstance == null) {
      throw new InvalidRequestException(status, "No task found for task id " + taskId);
    }
  }

}
