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

package org.camunda.bpm.engine.impl.cmd;

import static org.camunda.bpm.engine.impl.util.EnsureUtil.ensureNotNull;

import java.io.Serializable;
import java.util.function.Consumer;
import org.camunda.bpm.engine.history.UserOperationLogEntry;
import org.camunda.bpm.engine.impl.cfg.CommandChecker;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.impl.persistence.entity.TaskEntity;
import org.camunda.bpm.engine.impl.persistence.entity.TaskManager;
import org.camunda.bpm.engine.task.Task;

public class SetTaskPropertyCmd implements Command<Void>, Serializable {

  private final String taskId;
  private final Consumer<Task> consumer;

  public SetTaskPropertyCmd(String taskId, Consumer<Task> consumer) {
    this.taskId = taskId;
    this.consumer = consumer;
  }

  public Void execute(CommandContext context) {
    TaskEntity task = validateAndGet(taskId, context);

    consumer.accept(task);

    task.triggerUpdateEvent();
    task.logUserOperation(UserOperationLogEntry.OPERATION_TYPE_SET_NAME);

    return null;
  }

  protected TaskEntity validateAndGet(String taskId, CommandContext context) {
    ensureNotNull("taskId", taskId);

    TaskManager taskManager = context.getTaskManager();
    TaskEntity task = taskManager.findTaskById(taskId);

    ensureNotNull("Cannot find task with id " + taskId, "task", task);
    checkTaskPriority(task, context);

    return task;
  }

  protected void checkTaskPriority(TaskEntity task, CommandContext commandContext) {
    for (CommandChecker checker : commandContext.getProcessEngineConfiguration().getCommandCheckers()) {
      checker.checkTaskAssign(task);
    }
  }
}