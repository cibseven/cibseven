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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;

import org.cibseven.bpm.client.impl.EngineClient;
import org.cibseven.bpm.client.task.impl.ExternalTaskImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class SyncedExternalTaskServiceImplTest {

  protected static final String WORKER_ID = "workerId";

  protected EngineClient engineClient;
  protected SyncedExternalTaskServiceImpl externalTaskService;

  @BeforeEach
  public void setUp() {
    engineClient = Mockito.mock(EngineClient.class);
    Mockito.when(engineClient.getWorkerId()).thenReturn(WORKER_ID);
    externalTaskService = new SyncedExternalTaskServiceImpl(engineClient);
  }

  @Test
  public void shouldUpdateWorkerIdAndLockExpirationTimeOnLock() {
    // given
    ExternalTaskImpl task = new ExternalTaskImpl();
    task.setId("taskId");

    long beforeCall = System.currentTimeMillis();

    // when
    externalTaskService.lock(task, 30_000L);

    // then
    Mockito.verify(engineClient).lock("taskId", 30_000L);
    assertEquals(WORKER_ID, task.getWorkerId());
    assertTrue(task.getLockExpirationTime().getTime() >= beforeCall + 30_000L);
  }

  @Test
  public void shouldClearWorkerIdAndLockExpirationTimeOnUnlock() {
    // given
    ExternalTaskImpl task = new ExternalTaskImpl();
    task.setId("taskId");
    task.setWorkerId(WORKER_ID);
    task.setLockExpirationTime(new Date());

    // when
    externalTaskService.unlock(task);

    // then
    Mockito.verify(engineClient).unlock("taskId");
    assertNull(task.getWorkerId());
    assertNull(task.getLockExpirationTime());
  }

  @Test
  public void shouldUpdateLockExpirationTimeOnExtendLock() {
    // given
    ExternalTaskImpl task = new ExternalTaskImpl();
    task.setId("taskId");
    task.setLockExpirationTime(new Date(0L));

    long beforeCall = System.currentTimeMillis();

    // when
    externalTaskService.extendLock(task, 60_000L);

    // then
    Mockito.verify(engineClient).extendLock("taskId", 60_000L);
    assertTrue(task.getLockExpirationTime().getTime() >= beforeCall + 60_000L);
  }

  @Test
  public void shouldUpdateErrorMessageRetriesAndLockExpirationTimeOnHandleFailure() {
    // given
    ExternalTaskImpl task = new ExternalTaskImpl();
    task.setId("taskId");
    task.setErrorDetails("previous details");

    long beforeCall = System.currentTimeMillis();

    // when
    externalTaskService.handleFailure(task, "error message", "error details", 3, 5_000L);

    // then
    Mockito.verify(engineClient).failure("taskId", "error message", "error details", 3, 5_000L, null, null);
    assertEquals("error message", task.getErrorMessage());
    assertEquals("error details", task.getErrorDetails());
    assertEquals(3, task.getRetries());
    assertTrue(task.getLockExpirationTime().getTime() >= beforeCall + 5_000L);
  }

  @Test
  public void shouldKeepPreviousErrorDetailsOnHandleFailureWithNullErrorDetails() {
    // given: the engine itself leaves errorDetails untouched when null is passed
    ExternalTaskImpl task = new ExternalTaskImpl();
    task.setId("taskId");
    task.setErrorDetails("previous details");

    // when
    externalTaskService.handleFailure(task, "error message", null, 3, 5_000L);

    // then
    assertEquals("previous details", task.getErrorDetails());
  }

  @Test
  public void shouldIgnoreExternalTaskImplementationsOtherThanExternalTaskImpl() {
    // given: an ExternalTask that is not an ExternalTaskImpl
    org.cibseven.bpm.client.task.ExternalTask task = Mockito.mock(org.cibseven.bpm.client.task.ExternalTask.class);
    Mockito.when(task.getId()).thenReturn("taskId");

    // when
    externalTaskService.extendLock(task, 60_000L);

    // then: no exception, delegate call still happens
    Mockito.verify(engineClient).extendLock("taskId", 60_000L);
    assertNull(task.getLockExpirationTime());
  }

}
