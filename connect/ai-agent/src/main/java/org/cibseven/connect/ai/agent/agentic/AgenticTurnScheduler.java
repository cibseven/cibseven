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
package org.cibseven.connect.ai.agent.agentic;

import java.util.Date;

import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.db.entitymanager.cache.CachedDbEntity;
import org.cibseven.bpm.engine.impl.db.entitymanager.cache.DbEntityState;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.JobEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.MessageEntity;
import org.cibseven.bpm.engine.impl.util.ClockUtil;

/**
 * Schedules the next step of an agentic scope, unless one is already waiting to run.
 *
 * <p>A step is one model call (CIB7-2148). It is scheduled when the scope is entered, when a child
 * ends or is removed, and by a step whose model called tools and has not answered yet.
 */
public final class AgenticTurnScheduler {

  private AgenticTurnScheduler() {
  }

  /**
   * Inserts a step job for {@code scopeExecution} unless one is already waiting.
   *
   * <p>Waiting means not yet taken by an executor and not the job running now. Three children
   * ending at once fire three times and mean one thing, there is something new to look at, and
   * the waiting job sees all three because it reads the state rather than the signal. A job that
   * is running does not count: it may have read the state before this change, and skipping here
   * would lose the change for good. The worst that follows is one step more: a job taken but not
   * started yet may still read the change, and the job inserted here then finds nothing new. When
   * the taken one ends the turn, that is a turn of its own, which counts against maxTurns and costs
   * a model call.
   *
   * <p>The scope's own jobs are read from the execution, which also holds the ones inserted earlier
   * in this transaction -- a database query would not see them, and a step whose tool ended
   * synchronously would schedule twice. Such a job is waiting whatever its lock says: inside the job
   * executor, an exclusive job that a job of the same process instance creates is locked at once, as
   * the hint that this thread runs it next ({@code JobManager.hintJobExecutor}).
   *
   * <p>A waiting job that was there before is relied upon, so its revision is raised: should it be
   * running after all -- without a lock, through {@code executeJob}; past its lock; or taken by an
   * executor after it was read here -- it read the state before this change, and it fails on its
   * own job instead of committing over it, and runs again. Nothing else need collide with a step
   * that only answers.
   */
  public static void scheduleTurn(ExecutionEntity scopeExecution) {
    if (!AgenticScopes.isAgentic(scopeExecution)) {
      return;
    }

    CommandContext commandContext = Context.getCommandContext();
    JobEntity current = commandContext.getCurrentJob();
    for (JobEntity job : scopeExecution.getJobs()) {
      if (!AgenticTurnJobHandler.TYPE.equals(job.getJobHandlerType())
          || (current != null && current.getId().equals(job.getId()))) {
        continue;
      }
      if (isInsertedNow(commandContext, job)) {
        return;
      }
      if (!isTakenByAnExecutor(job)) {
        commandContext.getDbEntityManager().forceUpdate(job);
        return;
      }
    }

    MessageEntity message = new MessageEntity();
    message.setJobHandlerType(AgenticTurnJobHandler.TYPE);
    message.setJobHandlerConfiguration(
        new AgenticTurnJobHandler.Configuration(scopeExecution.getId()));
    message.setExecution(scopeExecution);
    // What a job declaration would set and setExecution does not (CIB7-2149): the tenant, so a
    // tenant's turn belongs to it, and the process definition, so a query by definition finds the
    // turn and a failed one can find its scope's retry cycle. The job definition is CIB7-2210.
    message.setTenantId(scopeExecution.getTenantId());
    message.setProcessDefinitionId(scopeExecution.getProcessDefinitionId());
    message.setProcessDefinitionKey(scopeExecution.getProcessDefinition().getKey());

    commandContext.getJobManager().send(message);
  }

  /** Inserted in this transaction, so not running anywhere yet. */
  protected static boolean isInsertedNow(CommandContext commandContext, JobEntity job) {
    CachedDbEntity cached = commandContext.getDbEntityManager().getDbEntityCache().getCachedEntity(job);
    return cached != null && cached.getEntityState() == DbEntityState.TRANSIENT;
  }

  /** Locked by a job executor that has not let go of it: running, or about to. */
  protected static boolean isTakenByAnExecutor(JobEntity job) {
    Date lockExpiration = job.getLockExpirationTime();
    return job.getLockOwner() != null && lockExpiration != null
        && lockExpiration.after(ClockUtil.getCurrentTime());
  }
}
