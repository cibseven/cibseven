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

import java.util.List;
import java.util.function.BiConsumer;

import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.AttachmentEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.HistoricProcessInstanceEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.HistoricTaskInstanceEntity;
import org.cibseven.bpm.engine.task.Attachment;

/**
 * Attachment checks, gated by
 * {@link org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl#isEnforceAttachmentPermissions()}.
 * See {@link OwnerAuthChecks}.
 */
final class AttachmentAuthChecks {

  private AttachmentAuthChecks() {
  }

  static void checkReadTask(String taskId, CommandContext commandContext) {
    if (isEnforced(commandContext)) {
      OwnerAuthChecks.checkReadTask(taskId, commandContext);
    }
  }

  static void checkModifyTask(String taskId, CommandContext commandContext) {
    if (isEnforced(commandContext)) {
      OwnerAuthChecks.checkModifyTask(taskId, commandContext);
    }
  }

  static void checkReadProcessInstance(String processInstanceId, CommandContext commandContext) {
    if (isEnforced(commandContext)) {
      OwnerAuthChecks.checkReadProcessInstance(processInstanceId, commandContext);
    }
  }

  static void checkModifyProcessInstance(String processInstanceId, CommandContext commandContext) {
    if (isEnforced(commandContext)) {
      OwnerAuthChecks.checkModifyProcessInstance(processInstanceId, commandContext);
    }
  }

  /**
   * For commands whose only input is the attachment id.
   */
  static void checkReadAttachment(AttachmentEntity attachment, CommandContext commandContext) {
    checkAttachment(attachment, OwnerAuthChecks::checkReadTask,
        OwnerAuthChecks::checkReadProcessInstance, commandContext);
  }

  static void checkUpdateAttachment(AttachmentEntity attachment, CommandContext commandContext) {
    checkAttachment(attachment, OwnerAuthChecks::checkModifyTask,
        OwnerAuthChecks::checkModifyProcessInstance, commandContext);
  }

  /**
   * For a create that names neither a task nor a process instance.
   */
  static void checkModifyWithoutOwner(CommandContext commandContext) {
    if (isEnforced(commandContext)) {
      OwnerAuthChecks.checkUnresolvableOwner(commandContext);
    }
  }

  static void checkTaskBelongsToProcessInstance(String taskId, String processInstanceId,
                                                CommandContext commandContext) {
    if (isEnforced(commandContext)) {
      OwnerAuthChecks.checkTaskBelongsToProcessInstance(taskId, processInstanceId, "an attachment",
          commandContext);
    }
  }

  static List<Attachment> filterReadableTaskAttachments(List<Attachment> attachments,
                                                        CommandContext commandContext) {
    if (!isEnforced(commandContext)) {
      return attachments;
    }
    return OwnerAuthChecks.filterReadableByTask(attachments, Attachment::getTaskId, commandContext);
  }

  static ExecutionEntity findProcessInstance(String processInstanceId, CommandContext commandContext) {
    return OwnerAuthChecks.findProcessInstance(processInstanceId, commandContext);
  }

  static HistoricProcessInstanceEntity findHistoricProcessInstance(String processInstanceId,
                                                                   CommandContext commandContext) {
    return OwnerAuthChecks.findHistoricProcessInstance(processInstanceId, commandContext);
  }

  static HistoricTaskInstanceEntity findHistoricTask(String taskId, CommandContext commandContext) {
    return OwnerAuthChecks.findHistoricTask(taskId, commandContext);
  }

  private static void checkAttachment(AttachmentEntity attachment,
                                      BiConsumer<String, CommandContext> taskCheck,
                                      BiConsumer<String, CommandContext> processInstanceCheck,
                                      CommandContext commandContext) {
    // unknown attachment ids keep their existing behaviour
    if (attachment == null || !isEnforced(commandContext)) {
      return;
    }
    // the task is the finer resource
    if (!OwnerAuthChecks.isBlank(attachment.getTaskId())) {
      taskCheck.accept(attachment.getTaskId(), commandContext);
    } else if (attachment.getProcessInstanceId() != null) {
      processInstanceCheck.accept(attachment.getProcessInstanceId(), commandContext);
    } else {
      OwnerAuthChecks.checkUnresolvableOwner(commandContext);
    }
  }

  private static boolean isEnforced(CommandContext commandContext) {
    return commandContext.getProcessEngineConfiguration().isEnforceAttachmentPermissions();
  }
}
