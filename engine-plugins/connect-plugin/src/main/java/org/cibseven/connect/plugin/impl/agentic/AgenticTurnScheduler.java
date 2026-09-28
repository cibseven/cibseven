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
package org.cibseven.connect.plugin.impl.agentic;

import java.util.List;

import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.JobEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.MessageEntity;

/** Schedules a turn, at most one open per scope. */
public final class AgenticTurnScheduler {

  private AgenticTurnScheduler() {
  }

  /**
   * Inserts a turn job for {@code scopeExecution} unless one is already waiting.
   *
   * <p>Three children ending at once fire three listeners and mean one thing: there is something
   * new to look at. The waiting job sees all three when it runs, because it reads the state rather
   * than the signal.
   */
  public static void scheduleTurn(ExecutionEntity scopeExecution) {
    if (!AgenticScopes.isAgentic(scopeExecution)) {
      return;
    }

    CommandContext commandContext = Context.getCommandContext();
    String scopeExecutionId = scopeExecution.getId();

    List<JobEntity> pending = commandContext.getJobManager().findJobsByConfiguration(
        AgenticTurnJobHandler.TYPE, scopeExecutionId, scopeExecution.getTenantId());
    if (!pending.isEmpty()) {
      return;
    }

    MessageEntity message = new MessageEntity();
    message.setJobHandlerType(AgenticTurnJobHandler.TYPE);
    message.setJobHandlerConfiguration(
        new AgenticTurnJobHandler.Configuration(scopeExecutionId));
    message.setExecution(scopeExecution);

    commandContext.getJobManager().send(message);
  }
}
