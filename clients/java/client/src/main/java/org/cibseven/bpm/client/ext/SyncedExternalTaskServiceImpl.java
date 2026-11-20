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
package org.cibseven.bpm.client.ext;

import java.util.Date;

import org.cibseven.bpm.client.impl.EngineClient;
import org.cibseven.bpm.client.task.ExternalTask;
import org.cibseven.bpm.client.task.impl.ExternalTaskImpl;
import org.cibseven.bpm.client.task.impl.ExternalTaskServiceImpl;

/**
 * <p>An extended {@link ExternalTaskServiceImpl} that keeps the local {@link ExternalTask}
 * object in sync with the state changes the engine applies as a side effect of
 * {@link #lock(ExternalTask, long) lock}, {@link #unlock(ExternalTask) unlock},
 * {@link #extendLock(ExternalTask, long) extendLock} and
 * {@link #handleFailure(ExternalTask, String, String, int, long) handleFailure}.
 *
 * <p>By default, the client never updates the local task after fetch-and-lock: those
 * REST resources respond with no body, so there is nothing to deserialize back onto the
 * task. This class closes that gap by locally re-computing the values the engine is known
 * to set (see {@code org.cibseven.bpm.engine.impl.persistence.entity.ExternalTaskEntity})
 * and writing them back onto the task, field by field:
 * <ul>
 *   <li>{@code lock}/{@code extendLock}/{@code handleFailure} update {@code lockExpirationTime}
 *       ({@code now + duration})
 *   <li>{@code lock} additionally sets {@code workerId} to this client's worker id;
 *       {@code unlock} clears both {@code workerId} and {@code lockExpirationTime}
 *   <li>{@code handleFailure} additionally updates {@code errorMessage} and {@code retries}
 *       unconditionally, and {@code errorDetails} only if a non-null value was passed -
 *       mirroring the engine's own null-check, which otherwise leaves the previous value in place
 * </ul>
 *
 * <p><b>Not covered</b>, and intentionally so:
 * <ul>
 *   <li>the id-based overloads ({@code lock}/{@code extendLock}/{@code handleFailure} taking an
 *       external task id instead of an {@link ExternalTask}) - there is no local task instance
 *       to update. In this codebase the only call sites for these overloads pass the id of a
 *       task that is available in the very same call (e.g. {@code client.extendLock(task.getId(), ...)}
 *       right next to {@code task}) - callers in that situation should simply use the
 *       {@link ExternalTask}-based overload instead, which this class does keep in sync.
 *   <li>{@code complete} and {@code handleBpmnError} - these typically make the engine delete
 *       the external task entity entirely; there is no field value to sync to, only a task that
 *       should no longer be used
 *   <li>{@code setVariables} - the task's fetched {@code variables} are a fetch-time snapshot
 *       computed by the REST resource, not a persisted property of the external task itself, so
 *       there is nothing on the entity for this class to mirror
 * </ul>
 *
 * <p><b>Note:</b> the computed values are client-side estimates, not values confirmed by the
 * server response (which is empty). They can drift slightly from the actual values stored in
 * the engine's database in case of clock skew between client and server.
 *
 * <p>To use this extension, bootstrap the client with {@link SyncedExternalTaskClientBuilder}
 * instead of {@link org.cibseven.bpm.client.ExternalTaskClient#create()}.
 */
public class SyncedExternalTaskServiceImpl extends ExternalTaskServiceImpl {

  public SyncedExternalTaskServiceImpl(EngineClient engineClient) {
    super(engineClient);
  }

  @Override
  public void lock(ExternalTask externalTask, long lockDuration) {
    super.lock(externalTask, lockDuration);
    applyLock(externalTask, lockDuration);
  }

  @Override
  public void unlock(ExternalTask externalTask) {
    super.unlock(externalTask);
    applyUnlock(externalTask);
  }

  @Override
  public void extendLock(ExternalTask externalTask, long newDuration) {
    super.extendLock(externalTask, newDuration);
    applyExtendLock(externalTask, newDuration);
  }

  @Override
  public void handleFailure(ExternalTask externalTask, String errorMessage, String errorDetails, int retries, long retryTimeout) {
    super.handleFailure(externalTask, errorMessage, errorDetails, retries, retryTimeout);
    applyFailure(externalTask, errorMessage, errorDetails, retries, retryTimeout);
  }

  private void applyLock(ExternalTask externalTask, long lockDuration) {
    if (externalTask instanceof ExternalTaskImpl) {
      ExternalTaskImpl task = (ExternalTaskImpl) externalTask;
      task.setWorkerId(engineClient.getWorkerId());
      task.setLockExpirationTime(computeExpiration(lockDuration));
    }
  }

  private void applyUnlock(ExternalTask externalTask) {
    if (externalTask instanceof ExternalTaskImpl) {
      ExternalTaskImpl task = (ExternalTaskImpl) externalTask;
      task.setWorkerId(null);
      task.setLockExpirationTime(null);
    }
  }

  private void applyExtendLock(ExternalTask externalTask, long newDuration) {
    if (externalTask instanceof ExternalTaskImpl) {
      ((ExternalTaskImpl) externalTask).setLockExpirationTime(computeExpiration(newDuration));
    }
  }

  private void applyFailure(ExternalTask externalTask, String errorMessage, String errorDetails, int retries, long retryTimeout) {
    if (externalTask instanceof ExternalTaskImpl) {
      ExternalTaskImpl task = (ExternalTaskImpl) externalTask;
      task.setErrorMessage(errorMessage);

      // mirrors the engine, which also leaves the previous errorDetails in place if null is passed
      if (errorDetails != null) {
        task.setErrorDetails(errorDetails);
      }

      task.setRetries(retries);
      task.setLockExpirationTime(computeExpiration(retryTimeout));
    }
  }

  private Date computeExpiration(long durationInMillis) {
    return new Date(System.currentTimeMillis() + durationInMillis);
  }

}
