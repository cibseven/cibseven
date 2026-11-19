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
package org.cibseven.bpm.engine.impl.cmd;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.impl.cfg.CommandChecker;
import org.cibseven.bpm.engine.impl.identity.Authentication;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.AttachmentEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.HistoricProcessInstanceEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.HistoricTaskInstanceEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.TaskEntity;
import org.cibseven.bpm.engine.task.Attachment;

/**
 * Authorization checks for the attachment commands, gated by
 * {@link org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl#isEnforceAttachmentPermissions()},
 * which is enabled by default.
 * <p>
 * Each check resolves the owner of the attachment, the task or the process instance, from the
 * runtime tables first. Once it is gone, which is the normal state for completed tasks and process
 * instances, the check falls back to the historic instance: reads resolve READ_HISTORY on the
 * process definition or, with enableHistoricInstancePermissions, the per-instance historic
 * permission; writes resolve DELETE_HISTORY on the process definition or ALL on the historic
 * instance, since the engine offers no dedicated write permission for historic data.
 * <p>
 * An owner that can be resolved neither way, e.g. after its history was removed or for an id that
 * never existed, fails closed: only an admin may read or write such attachments. A {@code null} id
 * is not checked, the commands find nothing for it. A blank task id names no task either, but the
 * attachment table can hold attachments stored with one, which have no owner at all; looking them up
 * by that id is therefore restricted to admins as well, and a create routes a blank task id to the
 * process instance, see {@link CreateAttachmentCmd}.
 */
final class AttachmentAuthChecks {

  private AttachmentAuthChecks() {
  }

  static void checkReadTask(String taskId, CommandContext commandContext) {
    checkTask(taskId, CommandChecker::checkReadTask, CommandChecker::checkReadHistoricTaskInstance,
        commandContext);
  }

  static void checkModifyTask(String taskId, CommandContext commandContext) {
    checkTask(taskId, CommandChecker::checkTaskWork, CommandChecker::checkModifyHistoricTaskInstance,
        commandContext);
  }

  static void checkReadProcessInstance(String processInstanceId, CommandContext commandContext) {
    checkProcessInstance(processInstanceId, CommandChecker::checkReadProcessInstance,
        CommandChecker::checkReadHistoricProcessInstance, commandContext);
  }

  static void checkModifyProcessInstance(String processInstanceId, CommandContext commandContext) {
    checkProcessInstance(processInstanceId, CommandChecker::checkUpdateProcessInstance,
        CommandChecker::checkModifyHistoricProcessInstance, commandContext);
  }

  /**
   * For commands whose only input is the attachment id.
   */
  static void checkReadAttachment(AttachmentEntity attachment, CommandContext commandContext) {
    checkAttachment(attachment, AttachmentAuthChecks::checkReadTask,
        AttachmentAuthChecks::checkReadProcessInstance, commandContext);
  }

  static void checkUpdateAttachment(AttachmentEntity attachment, CommandContext commandContext) {
    checkAttachment(attachment, AttachmentAuthChecks::checkModifyTask,
        AttachmentAuthChecks::checkModifyProcessInstance, commandContext);
  }

  /**
   * For a create that names neither a task nor a process instance.
   */
  static void checkModifyWithoutOwner(CommandContext commandContext) {
    if (isEnforced(commandContext)) {
      checkUnresolvableOwner(commandContext);
    }
  }

  /**
   * An attachment created for a task may name a process instance as well, which makes it show up in
   * that instance's attachment list. Only the task is checked on create, so the process instance has
   * to be the one the task belongs to. Without an authorization check on the caller there is
   * nothing to get around, so a mismatch stays allowed then, as it always was. The tenant check
   * needs no such guard: the list of the other instance drops the attachment of a foreign tenant's
   * task, see {@link #filterReadableTaskAttachments}.
   */
  static void checkTaskBelongsToProcessInstance(String taskId, String processInstanceId,
                                                CommandContext commandContext) {
    if (!isEnforced(commandContext) || processInstanceId == null || isBlank(taskId)
        || !isAuthorizationChecked(commandContext)) {
      return;
    }

    String ownerProcessInstanceId;
    TaskEntity task = findTask(taskId, commandContext);
    if (task != null) {
      ownerProcessInstanceId = task.getProcessInstanceId();
    } else {
      HistoricTaskInstanceEntity historicTask = findHistoricTask(taskId, commandContext);
      if (historicTask == null) {
        // nothing to compare against, the task check has already restricted this to admins
        return;
      }
      ownerProcessInstanceId = historicTask.getProcessInstanceId();
    }

    if (!Objects.equals(processInstanceId, ownerProcessInstanceId)) {
      throw new BadUserRequestException("Cannot create an attachment for task '" + taskId
          + "' and process instance '" + processInstanceId
          + "', the task does not belong to that process instance.");
    }
  }

  /**
   * The attachment list of a process instance also contains the attachments of its tasks, which
   * {@link #checkReadAttachment} guards by the task. Drops those the caller may not read, so that the
   * list and the single read agree.
   */
  static List<Attachment> filterReadableTaskAttachments(List<Attachment> attachments,
                                                        CommandContext commandContext) {
    if (!isEnforced(commandContext)) {
      return attachments;
    }
    List<Attachment> readable = new ArrayList<>(attachments.size());
    for (Attachment attachment : attachments) {
      if (isBlank(attachment.getTaskId()) || canReadTask(attachment.getTaskId(), commandContext)) {
        readable.add(attachment);
      }
    }
    return readable;
  }

  /**
   * Resolves the process instance itself. A child execution is not a process instance, and its id
   * resolves to nothing once the execution has ended, so it must not be accepted in the first place.
   */
  static ExecutionEntity findProcessInstance(String processInstanceId, CommandContext commandContext) {
    if (processInstanceId == null) {
      return null;
    }
    ExecutionEntity execution = commandContext.getExecutionManager().findExecutionById(processInstanceId);
    return execution != null && execution.isProcessInstanceExecution() ? execution : null;
  }

  static HistoricProcessInstanceEntity findHistoricProcessInstance(String processInstanceId,
                                                                   CommandContext commandContext) {
    return processInstanceId == null ? null
        : commandContext.getHistoricProcessInstanceManager().findHistoricProcessInstance(processInstanceId);
  }

  private static void checkTask(String taskId,
                                BiConsumer<CommandChecker, TaskEntity> runtimeCheck,
                                BiConsumer<CommandChecker, HistoricTaskInstanceEntity> historicCheck,
                                CommandContext commandContext) {
    if (!isEnforced(commandContext) || taskId == null) {
      return;
    }
    TaskEntity task = findTask(taskId, commandContext);
    if (task != null) {
      forEachChecker(commandContext, checker -> runtimeCheck.accept(checker, task));
      return;
    }
    HistoricTaskInstanceEntity historicTask = findHistoricTask(taskId, commandContext);
    if (historicTask != null) {
      forEachChecker(commandContext, checker -> historicCheck.accept(checker, historicTask));
    } else {
      checkUnresolvableOwner(commandContext);
    }
  }

  private static void checkProcessInstance(String processInstanceId,
                                           BiConsumer<CommandChecker, ExecutionEntity> runtimeCheck,
                                           BiConsumer<CommandChecker, HistoricProcessInstanceEntity> historicCheck,
                                           CommandContext commandContext) {
    if (!isEnforced(commandContext) || processInstanceId == null) {
      return;
    }
    ExecutionEntity processInstance = findProcessInstance(processInstanceId, commandContext);
    if (processInstance != null) {
      forEachChecker(commandContext, checker -> runtimeCheck.accept(checker, processInstance));
      return;
    }
    HistoricProcessInstanceEntity historicProcessInstance =
        findHistoricProcessInstance(processInstanceId, commandContext);
    if (historicProcessInstance != null) {
      forEachChecker(commandContext, checker -> historicCheck.accept(checker, historicProcessInstance));
    } else {
      checkUnresolvableOwner(commandContext);
    }
  }

  private static void checkAttachment(AttachmentEntity attachment,
                                      BiConsumer<String, CommandContext> taskCheck,
                                      BiConsumer<String, CommandContext> processInstanceCheck,
                                      CommandContext commandContext) {
    // tolerate a missing attachment so that the callers keep their existing behaviour for an
    // unknown attachment id instead of failing here
    if (attachment == null || !isEnforced(commandContext)) {
      return;
    }
    // an attachment may carry a task id, a process instance id or both; the task is the finer resource
    if (!isBlank(attachment.getTaskId())) {
      taskCheck.accept(attachment.getTaskId(), commandContext);
    } else if (attachment.getProcessInstanceId() != null) {
      processInstanceCheck.accept(attachment.getProcessInstanceId(), commandContext);
    } else {
      checkUnresolvableOwner(commandContext);
    }
  }

  /**
   * The tenant check rejects with a plain ProcessEngineException, which cannot be told apart from
   * other engine failures, so the tenant is compared here and only the AuthorizationException is
   * caught.
   */
  private static boolean canReadTask(String taskId, CommandContext commandContext) {
    if (!isAuthenticatedTenant(findTaskTenantId(taskId, commandContext), commandContext)) {
      return false;
    }
    try {
      checkReadTask(taskId, commandContext);
      return true;
    } catch (AuthorizationException e) {
      return false;
    }
  }

  private static String findTaskTenantId(String taskId, CommandContext commandContext) {
    TaskEntity task = findTask(taskId, commandContext);
    if (task != null) {
      return task.getTenantId();
    }
    HistoricTaskInstanceEntity historicTask = findHistoricTask(taskId, commandContext);
    return historicTask != null ? historicTask.getTenantId() : null;
  }

  private static boolean isAuthenticatedTenant(String tenantId, CommandContext commandContext) {
    return commandContext.getTenantManager().isAuthenticatedTenant(tenantId);
  }

  /**
   * Without an owner there is no permission to check, so only an admin gets through.
   */
  private static void checkUnresolvableOwner(CommandContext commandContext) {
    commandContext.getAuthorizationManager().checkCamundaAdmin();
  }

  /**
   * TaskManager#findTaskById rejects a null id, hence the guard.
   */
  private static TaskEntity findTask(String taskId, CommandContext commandContext) {
    return isBlank(taskId) ? null : commandContext.getTaskManager().findTaskById(taskId);
  }

  static HistoricTaskInstanceEntity findHistoricTask(String taskId, CommandContext commandContext) {
    return isBlank(taskId) ? null
        : commandContext.getHistoricTaskInstanceManager().findHistoricTaskInstanceById(taskId);
  }

  private static void forEachChecker(CommandContext commandContext, Consumer<CommandChecker> action) {
    for (CommandChecker checker : commandContext.getProcessEngineConfiguration().getCommandCheckers()) {
      action.accept(checker);
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static boolean isEnforced(CommandContext commandContext) {
    return commandContext.getProcessEngineConfiguration().isEnforceAttachmentPermissions();
  }

  /**
   * Whether the authorization check runs for the current caller, the same condition
   * AuthorizationManager#isAuthCheckExecuted applies.
   */
  private static boolean isAuthorizationChecked(CommandContext commandContext) {
    Authentication authentication = commandContext.getAuthentication();
    return commandContext.getProcessEngineConfiguration().isAuthorizationEnabled()
        && commandContext.isAuthorizationCheckEnabled()
        && authentication != null
        && authentication.getUserId() != null;
  }
}
