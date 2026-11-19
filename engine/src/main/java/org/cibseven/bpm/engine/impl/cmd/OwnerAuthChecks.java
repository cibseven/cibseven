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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.impl.cfg.CommandChecker;
import org.cibseven.bpm.engine.impl.identity.Authentication;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.HistoricProcessInstanceEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.HistoricTaskInstanceEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.TaskEntity;

/**
 * Checks against the task or process instance that owns attachments or comments. Runtime first,
 * then the history: reads like the historic queries, writes need DELETE_HISTORY or ALL on the
 * historic instance. An owner that cannot be resolved, or a blank task id, is admin only.
 */
final class OwnerAuthChecks {

  private OwnerAuthChecks() {
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

  static void checkUnresolvableOwner(CommandContext commandContext) {
    commandContext.getAuthorizationManager().checkCamundaAdmin();
  }

  /**
   * Only the task is checked on create, so a named process instance must be the task's own.
   *
   * @param what names the created data in the error message, e.g. "an attachment"
   */
  static void checkTaskBelongsToProcessInstance(String taskId, String processInstanceId, String what,
                                                CommandContext commandContext) {
    if (processInstanceId == null || isBlank(taskId) || !isAuthorizationChecked(commandContext)) {
      return;
    }

    String ownerProcessInstanceId;
    TaskEntity task = findTask(taskId, commandContext);
    if (task != null) {
      ownerProcessInstanceId = task.getProcessInstanceId();
    } else {
      HistoricTaskInstanceEntity historicTask = findHistoricTask(taskId, commandContext);
      if (historicTask == null) {
        // the task check already limited this to admins
        return;
      }
      ownerProcessInstanceId = historicTask.getProcessInstanceId();
    }

    if (!Objects.equals(processInstanceId, ownerProcessInstanceId)) {
      throw new BadUserRequestException("Cannot create " + what + " for task '" + taskId
          + "' and process instance '" + processInstanceId
          + "', the task does not belong to that process instance.");
    }
  }

  /**
   * Drops entries of tasks the caller may not read. Each task is checked once.
   */
  static <T> List<T> filterReadableByTask(List<T> entries, Function<T, String> taskIdOf,
                                          CommandContext commandContext) {
    Map<String, Boolean> readableByTaskId = new HashMap<>();
    List<T> readable = new ArrayList<>(entries.size());
    for (T entry : entries) {
      String taskId = taskIdOf.apply(entry);
      if (isBlank(taskId)
          || readableByTaskId.computeIfAbsent(taskId, id -> canReadTask(id, commandContext))) {
        readable.add(entry);
      }
    }
    return readable;
  }

  /**
   * Only a process instance, not a child execution.
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

  /**
   * TaskManager#findTaskById rejects a null id.
   */
  static TaskEntity findTask(String taskId, CommandContext commandContext) {
    return isBlank(taskId) ? null : commandContext.getTaskManager().findTaskById(taskId);
  }

  static HistoricTaskInstanceEntity findHistoricTask(String taskId, CommandContext commandContext) {
    return isBlank(taskId) ? null
        : commandContext.getHistoricTaskInstanceManager().findHistoricTaskInstanceById(taskId);
  }

  static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static void checkTask(String taskId,
                                BiConsumer<CommandChecker, TaskEntity> runtimeCheck,
                                BiConsumer<CommandChecker, HistoricTaskInstanceEntity> historicCheck,
                                CommandContext commandContext) {
    if (taskId == null) {
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
    if (processInstanceId == null) {
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

  /**
   * The tenant is compared here because the tenant check throws a plain ProcessEngineException.
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

  private static void forEachChecker(CommandContext commandContext, Consumer<CommandChecker> action) {
    for (CommandChecker checker : commandContext.getProcessEngineConfiguration().getCommandCheckers()) {
      action.accept(checker);
    }
  }

  /**
   * Same condition as AuthorizationManager#isAuthCheckExecuted.
   */
  private static boolean isAuthorizationChecked(CommandContext commandContext) {
    Authentication authentication = commandContext.getAuthentication();
    return commandContext.getProcessEngineConfiguration().isAuthorizationEnabled()
        && commandContext.isAuthorizationCheckEnabled()
        && authentication != null
        && authentication.getUserId() != null;
  }
}
