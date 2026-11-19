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

import java.util.Date;

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.persistence.entity.ExternalTaskEntity;
import org.cibseven.bpm.engine.impl.util.ClockUtil;
import org.cibseven.bpm.engine.impl.util.EnsureUtil;

public class LockExternalTaskCmd extends HandleExternalTaskCmd {

  protected long lockDuration;

  public LockExternalTaskCmd(String externalTaskId, String workerId, long lockDuration) {
    super(externalTaskId, workerId);
    this.lockDuration = lockDuration;
  }

  @Override
  protected void execute(ExternalTaskEntity externalTask) {
    // a worker that locks its own task again only extends the lock: no new 'fetched' entry.
    // Exception: after a failure the task keeps the worker id until the retry pause ends;
    // locking it during that pause starts a new attempt, which needs its own 'fetched' entry
    // so that the processing time of each attempt can be read from the log.
    boolean relockByOwner = isLockedByThisWorker(externalTask) && !isInRetryPause(externalTask);

    externalTask.lock(workerId, lockDuration);

    // Safe to fire right away: a concurrent modification is not ignored here,
    // it rolls back the whole transaction including the history event
    if (!relockByOwner) {
      externalTask.produceHistoricExternalTaskFetchedEvent();
    }
  }

  /**
   * @return <code>true</code> if the task is currently locked by this worker
   *   and the lock has not expired yet
   */
  protected boolean isLockedByThisWorker(ExternalTaskEntity externalTask) {
    Date existingLockExpirationTime = externalTask.getLockExpirationTime();
    return workerId.equals(externalTask.getWorkerId())
        && existingLockExpirationTime != null
        && !ClockUtil.getCurrentTime().after(existingLockExpirationTime);
  }

  /**
   * @return <code>true</code> if the last attempt of the task ended with a failure,
   *   so the current lock is only the retry pause, see
   *   {@link org.cibseven.bpm.engine.impl.persistence.entity.HistoricExternalTaskLogManager#isLastFetchedAttemptFailed}
   */
  protected boolean isInRetryPause(ExternalTaskEntity externalTask) {
    return Context.getCommandContext()
        .getHistoricExternalTaskLogManager()
        .isLastFetchedAttemptFailed(externalTask);
  }

  @Override
  public String getErrorMessageOnWrongWorkerAccess() {
    return "External Task " + externalTaskId + " cannot be locked by worker '" + workerId;
  }

  /*
    Report a worker violation only if another worker has locked the task,
    and the lock expiration time is still not expired.
   */
  @Override
  protected boolean validateWorkerViolation(ExternalTaskEntity externalTask) {
    String existingWorkerId = externalTask.getWorkerId();
    Date existingLockExpirationTime = externalTask.getLockExpirationTime();

    // check if another worker is attempting to lock the same task
    boolean workerValidation = existingWorkerId != null && !workerId.equals(existingWorkerId);
    // and check if an existing lock is already expired
    boolean lockValidation = existingLockExpirationTime != null
        && !ClockUtil.getCurrentTime().after(existingLockExpirationTime);

    return workerValidation && lockValidation;
  }

  @Override
  protected void validateInput() {
    super.validateInput();
    EnsureUtil.ensurePositive(BadUserRequestException.class, "lockDuration", lockDuration);
  }
}