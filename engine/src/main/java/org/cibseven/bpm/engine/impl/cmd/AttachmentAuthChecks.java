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

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.cibseven.bpm.engine.impl.cfg.CommandChecker;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.AttachmentEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.HistoricProcessInstanceEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.HistoricTaskInstanceEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.TaskEntity;

/**
 * Authorization checks for the attachment commands, gated by
 * {@link org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl#isEnforceAttachmentPermissions()},
 * which is disabled by default.
 * <p>
 * The {@code ...IfExists} methods resolve the runtime entity first. Once it is gone, which is the
 * normal state for completed tasks and process instances, they fall back to the historic instance:
 * reads resolve READ_HISTORY on the process definition or the per-instance historic permission,
 * writes resolve DELETE_HISTORY on the process definition or ALL on the historic instance, since
 * the engine offers no dedicated write permission for historic data.
 */
final class AttachmentAuthChecks {

  private AttachmentAuthChecks() {
  }

  static void checkReadTaskIfExists(String taskId, CommandContext commandContext) {
    if (!isEnforced(commandContext)) {
      return;
    }
    TaskEntity task = findTask(taskId, commandContext);
    if (task != null) {
      forEachChecker(commandContext, checker -> checker.checkReadTask(task));
    } else {
      checkReadHistoricTask(taskId, commandContext);
    }
  }

  static void checkModifyTaskIfExists(String taskId, CommandContext commandContext) {
    if (!isEnforced(commandContext)) {
      return;
    }
    TaskEntity task = findTask(taskId, commandContext);
    if (task != null) {
      forEachChecker(commandContext, checker -> checker.checkTaskWork(task));
    } else {
      checkModifyHistoricTask(taskId, commandContext);
    }
  }

  static void checkReadProcessInstanceIfExists(String processInstanceId, CommandContext commandContext) {
    if (!isEnforced(commandContext)) {
      return;
    }
    ExecutionEntity processInstance = findProcessInstance(processInstanceId, commandContext);
    if (processInstance != null) {
      forEachChecker(commandContext, checker -> checker.checkReadProcessInstance(processInstance));
    } else {
      checkReadHistoricProcessInstance(processInstanceId, commandContext);
    }
  }

  static void checkModifyProcessInstanceIfExists(String processInstanceId, CommandContext commandContext) {
    if (!isEnforced(commandContext)) {
      return;
    }
    ExecutionEntity processInstance = findProcessInstance(processInstanceId, commandContext);
    if (processInstance != null) {
      forEachChecker(commandContext, checker -> checker.checkUpdateProcessInstance(processInstance));
    } else {
      checkModifyHistoricProcessInstance(processInstanceId, commandContext);
    }
  }

  /**
   * For commands whose only input is the attachment id.
   */
  static void checkReadAttachment(AttachmentEntity attachment, CommandContext commandContext) {
    checkAttachment(attachment, AttachmentAuthChecks::checkReadTaskIfExists,
        AttachmentAuthChecks::checkReadProcessInstanceIfExists, commandContext);
  }

  static void checkUpdateAttachment(AttachmentEntity attachment, CommandContext commandContext) {
    checkAttachment(attachment, AttachmentAuthChecks::checkModifyTaskIfExists,
        AttachmentAuthChecks::checkModifyProcessInstanceIfExists, commandContext);
  }

  private static void checkAttachment(AttachmentEntity attachment,
                                      BiConsumer<String, CommandContext> taskCheck,
                                      BiConsumer<String, CommandContext> processInstanceCheck,
                                      CommandContext commandContext) {
    // tolerate a missing attachment so that the callers keep their existing behaviour for an
    // unknown attachment id instead of failing here
    if (attachment == null) {
      return;
    }
    // an attachment may carry a task id, a process instance id or both; the task is the finer resource
    if (!isBlank(attachment.getTaskId())) {
      taskCheck.accept(attachment.getTaskId(), commandContext);
    } else if (attachment.getProcessInstanceId() != null) {
      processInstanceCheck.accept(attachment.getProcessInstanceId(), commandContext);
    }
  }

  /**
   * Read fallback for a task that is no longer in the runtime tables.
   */
  private static void checkReadHistoricTask(String taskId, CommandContext commandContext) {
    if (isBlank(taskId)) {
      return;
    }
    HistoricTaskInstanceEntity historicTask = commandContext
        .getHistoricTaskInstanceManager()
        .findHistoricTaskInstanceById(taskId);
    if (historicTask != null) {
      forEachChecker(commandContext, checker -> checker.checkReadHistoricTaskInstance(historicTask));
    }
  }

  /**
   * Read fallback for a process instance that is no longer in the runtime tables.
   */
  private static void checkReadHistoricProcessInstance(String processInstanceId, CommandContext commandContext) {
    if (processInstanceId == null) {
      return;
    }
    HistoricProcessInstanceEntity historicProcessInstance = commandContext
        .getHistoricProcessInstanceManager()
        .findHistoricProcessInstance(processInstanceId);
    if (historicProcessInstance != null) {
      forEachChecker(commandContext,
          checker -> checker.checkReadHistoricProcessInstance(historicProcessInstance));
    }
  }

  /**
   * Write fallback for a task that is no longer in the runtime tables. The engine has no dedicated
   * write permission for historic data, so CommandChecker#checkModifyHistoricTaskInstance accepts
   * either DELETE_HISTORY on the process definition or ALL on the historic task, whether the caller
   * is creating, saving or deleting an attachment.
   */
  private static void checkModifyHistoricTask(String taskId, CommandContext commandContext) {
    if (isBlank(taskId)) {
      return;
    }
    HistoricTaskInstanceEntity historicTask = commandContext
            .getHistoricTaskInstanceManager()
            .findHistoricTaskInstanceById(taskId);
    if (historicTask != null) {
      forEachChecker(commandContext, checker -> checker.checkModifyHistoricTaskInstance(historicTask));
    }
  }

  /**
   * Write fallback for a process instance that is no longer in the runtime tables. Same reasoning
   * as {@link #checkModifyHistoricTask}.
   */
  private static void checkModifyHistoricProcessInstance(String processInstanceId, CommandContext commandContext) {
    if (isBlank(processInstanceId)) {
      return;
    }
    HistoricProcessInstanceEntity historicProcessInstance = commandContext
        .getHistoricProcessInstanceManager()
        .findHistoricProcessInstance(processInstanceId);
    if (historicProcessInstance != null) {
      forEachChecker(commandContext,
          checker -> checker.checkModifyHistoricProcessInstance(historicProcessInstance));
    }
  }

  /**
   * TaskManager#findTaskById rejects a null id, hence the guard.
   */
  private static TaskEntity findTask(String taskId, CommandContext commandContext) {
    return isBlank(taskId) ? null : commandContext.getTaskManager().findTaskById(taskId);
  }

  private static ExecutionEntity findProcessInstance(String processInstanceId, CommandContext commandContext) {
    return processInstanceId == null ? null
        : commandContext.getExecutionManager().findExecutionById(processInstanceId);
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
}
