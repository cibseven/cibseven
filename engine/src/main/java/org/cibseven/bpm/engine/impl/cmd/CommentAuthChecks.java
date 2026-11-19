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
package org.cibseven.bpm.engine.impl.cmd;

import java.util.List;

import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.task.Comment;

/**
 * Checks for creating and reading comments, gated by
 * {@link org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl#isEnforceCommentPermissions()}.
 * Update and delete check on their own. See {@link OwnerAuthChecks}.
 */
final class CommentAuthChecks {

  private CommentAuthChecks() {
  }

  static void checkReadTask(String taskId, CommandContext commandContext) {
    if (isEnforced(commandContext)) {
      OwnerAuthChecks.checkReadTask(taskId, commandContext);
    }
  }

  static void checkReadProcessInstance(String processInstanceId, CommandContext commandContext) {
    if (isEnforced(commandContext)) {
      OwnerAuthChecks.checkReadProcessInstance(processInstanceId, commandContext);
    }
  }

  /**
   * The task owns the comment if one is named, otherwise the process instance.
   */
  static void checkCreate(String taskId, String processInstanceId, CommandContext commandContext) {
    if (!isEnforced(commandContext)) {
      return;
    }
    if (!OwnerAuthChecks.isBlank(taskId)) {
      OwnerAuthChecks.checkModifyTask(taskId, commandContext);
      OwnerAuthChecks.checkTaskBelongsToProcessInstance(taskId, processInstanceId, "a comment",
          commandContext);
    } else if (processInstanceId != null) {
      OwnerAuthChecks.checkModifyProcessInstance(processInstanceId, commandContext);
    } else {
      // blank task id and no process instance: no owner
      OwnerAuthChecks.checkUnresolvableOwner(commandContext);
    }
  }

  static List<Comment> filterReadableTaskComments(List<Comment> comments, CommandContext commandContext) {
    if (!isEnforced(commandContext)) {
      return comments;
    }
    return OwnerAuthChecks.filterReadableByTask(comments, Comment::getTaskId, commandContext);
  }

  private static boolean isEnforced(CommandContext commandContext) {
    return commandContext.getProcessEngineConfiguration().isEnforceCommentPermissions();
  }
}
