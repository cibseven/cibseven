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
import org.cibseven.bpm.engine.impl.persistence.entity.TaskEntity;

/**
 * Authorization checks for the attachment commands.
 * <p>
 * All checks are gated by
 * {@link org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl#isEnforceAttachmentPermissions()},
 * which is disabled by default. While disabled the commands behave exactly as before CIB7-1752,
 * including the tenant checks, and no additional entity is resolved.
 * <p>
 * The {@code ...IfExists} methods skip the check when the runtime entity is gone, which is the
 * normal state for completed tasks and process instances. Their attachments survive in
 * ACT_HI_ATTACHMENT and stay readable without a permission. Closing that would require a history
 * check, which CommandChecker does not offer today. See CIB7-1752.
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
