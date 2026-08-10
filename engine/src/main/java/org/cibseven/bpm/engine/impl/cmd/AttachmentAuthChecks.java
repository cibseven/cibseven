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
 * Authorization checks for the attachment commands.
 * <p>
 * All checks are gated by
 * {@link org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl#isEnforceAttachmentPermissions()},
 * which is disabled by default. While disabled the commands behave exactly as before CIB7-1752,
 * including the tenant checks, and no additional entity is resolved.
 * <p>
 * The {@code ...IfExists} methods resolve the runtime entity first. When it is gone, which is the
 * normal state for completed tasks and process instances whose attachments survive in
 * ACT_HI_ATTACHMENT, the behaviour differs per operation:
 * <ul>
 * <li><b>Read</b> falls back to the historic instance via
 * {@link CommandChecker#checkReadHistoricTaskInstance} and
 * {@link CommandChecker#checkReadHistoricProcessInstance}. That fallback additionally requires
 * {@code enableHistoricInstancePermissions}, because the per-instance HISTORIC_TASK and
 * HISTORIC_PROCESS_INSTANCE authorizations it resolves against are only created while that flag is
 * set. With the flag off, historic reads stay unchecked.</li>
 * <li><b>Update and delete</b> are <b>not</b> checked once the runtime entity is gone. There is no
 * historic counterpart to check against: {@code HistoricTaskPermissions} and
 * {@code HistoricProcessInstancePermissions} only define READ. Attachments of completed tasks and
 * process instances therefore remain updatable and deletable without a permission, which is the
 * original CIB7-1752 exposure narrowed to historic data. Closing it needs a product decision, since
 * the options are either a coarse DELETE_HISTORY check on the process definition (which does
 * nothing at all for standalone tasks, they carry no process definition key) or new permission
 * values on the historic resources.</li>
 * </ul>
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

  static void checkTaskWorkIfExists(String taskId, CommandContext commandContext) {
    if (!isEnforced(commandContext)) {
      return;
    }
    TaskEntity task = findTask(taskId, commandContext);
    if (task != null) {
      forEachChecker(commandContext, checker -> checker.checkTaskWork(task));
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

  static void checkUpdateProcessInstanceIfExists(String processInstanceId, CommandContext commandContext) {
    if (!isEnforced(commandContext)) {
      return;
    }
    ExecutionEntity processInstance = findProcessInstance(processInstanceId, commandContext);
    if (processInstance != null) {
      forEachChecker(commandContext, checker -> checker.checkUpdateProcessInstance(processInstance));
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
    checkAttachment(attachment, AttachmentAuthChecks::checkTaskWorkIfExists,
        AttachmentAuthChecks::checkUpdateProcessInstanceIfExists, commandContext);
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
   * Read fallback for a task that is no longer in the runtime tables. Requires
   * {@code enableHistoricInstancePermissions}: the per-instance HISTORIC_TASK authorizations this
   * resolves against are only created while that flag is set, so checking without it would deny
   * every caller instead of the unauthorized ones.
   */
  private static void checkReadHistoricTask(String taskId, CommandContext commandContext) {
    if (isBlank(taskId) || !isHistoricEnforced(commandContext)) {
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
   * Read fallback for a process instance that is no longer in the runtime tables. Same
   * {@code enableHistoricInstancePermissions} requirement as {@link #checkReadHistoricTask}.
   */
  private static void checkReadHistoricProcessInstance(String processInstanceId, CommandContext commandContext) {
    if (processInstanceId == null || !isHistoricEnforced(commandContext)) {
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

  private static boolean isHistoricEnforced(CommandContext commandContext) {
    return commandContext.getProcessEngineConfiguration().isEnableHistoricInstancePermissions();
  }
}
