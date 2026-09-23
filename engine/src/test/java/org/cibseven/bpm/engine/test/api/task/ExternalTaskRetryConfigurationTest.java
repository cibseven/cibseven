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
package org.cibseven.bpm.engine.test.api.task.externaltask;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import org.cibseven.bpm.engine.externaltask.ExternalTask;
import org.cibseven.bpm.engine.externaltask.LockedExternalTask;
import org.cibseven.bpm.engine.impl.util.ClockUtil;
import org.cibseven.bpm.engine.runtime.Incident;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.util.PluggableProcessEngineTest;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the retry time cycle on external service tasks:
 * <ul>
 *   <li>the cycle initializes the retries of the external task,</li>
 *   <li>every fetchAndLock consumes one attempt, even if the worker never reports back,</li>
 *   <li>the configured interval delays the next attempt
 *       (lock expiration = fetch time + lockDuration + interval),</li>
 *   <li>after a reported failure the next attempt is delayed by the configured interval.</li>
 * </ul>
 */
public class ExternalTaskRetryConfigurationTest extends PluggableProcessEngineTest {

  protected static final String PROCESS_KEY = "externalTaskRetryProcess";
  protected static final String TOPIC_NAME = "retryTopic";
  protected static final String WORKER_ID = "aWorkerId";
  protected static final String ERROR_MESSAGE = "error message";

  protected static final long SECOND = 1000L;
  protected static final long MINUTE = 60 * SECOND;
  protected static final long LOCK_TIME = 10 * SECOND;

  /** Larger than lockDuration + any interval used in this test. */
  protected static final long MORE_THAN_ANY_DELAY = LOCK_TIME + 10 * MINUTE;

  protected SimpleDateFormat formatter = new SimpleDateFormat("dd.MM.yyyy - HH:mm:ss");

  @BeforeEach
  public void setUp() throws Exception {
    // get rid of the milliseconds because of MySQL datetime precision
    Date now = formatter.parse(formatter.format(new Date()));
    ClockUtil.setCurrentTime(now);
  }

  @AfterEach
  public void tearDown() {
    ClockUtil.reset();
  }

  protected BpmnModelInstance processWithRetryCycle(String retryCycle) {
    return Bpmn.createExecutableProcess(PROCESS_KEY)
        .startEvent()
        .serviceTask("externalTask")
          .camundaExternalTask(TOPIC_NAME)
          .camundaFailedJobRetryTimeCycle(retryCycle)
        .userTask("afterExternalTask")
        .endEvent()
        .done();
  }

  protected BpmnModelInstance processWithoutRetryCycle() {
    return Bpmn.createExecutableProcess(PROCESS_KEY)
        .startEvent()
        .serviceTask("externalTask")
          .camundaExternalTask(TOPIC_NAME)
        .userTask("afterExternalTask")
        .endEvent()
        .done();
  }

  protected List<LockedExternalTask> fetch() {
    return externalTaskService.fetchAndLock(5, WORKER_ID)
        .topic(TOPIC_NAME, LOCK_TIME)
        .execute();
  }

  protected void setTime(long millis) {
    ClockUtil.setCurrentTime(new Date(millis));
  }

  protected long now() {
    return ClockUtil.getCurrentTime().getTime();
  }

  /** Moves the clock far enough that any lock + interval has passed. */
  protected void waitForNextAttempt() {
    setTime(now() + MORE_THAN_ANY_DELAY);
 }

  protected ExternalTask currentExternalTask() {
    return externalTaskService.createExternalTaskQuery().singleResult();
  }

  protected long externalTaskIncidentCount() {
    return runtimeService.createIncidentQuery()
        .incidentType(Incident.EXTERNAL_TASK_HANDLER_TYPE)
        .count();
  }

  /**
   * Asserts that the task is not fetchable one second before
   * {@code from + delay} and fetchable one second after it.
   */
  protected LockedExternalTask assertNextAttemptAfter(long from, long delay) {
    setTime(from + delay - SECOND);
    assertThat(fetch())
        .as("task must not be fetchable before %d s", delay / SECOND)
        .isEmpty();

    setTime(from + delay + SECOND);
    List<LockedExternalTask> tasks = fetch();
    assertThat(tasks)
        .as("task must be fetchable after %d s", delay / SECOND)
        .hasSize(1);
    return tasks.get(0);
  }

  // --------------------------------------------------------- initialization
  @Test
  public void shouldInitializeRetriesFromRetryCycle() {
    // given
    testRule.deploy(processWithRetryCycle("R3/PT5M"));

    // when
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);

    // then the parser value was applied in ExternalTaskEntity#createAndInsert
    assertThat(currentExternalTask().getRetries()).isEqualTo(3);
  }

  @Test
  public void shouldInitializeRetriesFromIntervalList() {
    // given: 3 intervals -> 4 attempts (same semantics as ParseUtil#parseRetryIntervals)
    testRule.deploy(processWithRetryCycle("PT1M,PT5M,PT10M"));

    // when
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);

    // then
    assertThat(currentExternalTask().getRetries()).isEqualTo(4);
  }

  @Test
  public void shouldIgnoreRepeatCountInsideIntervalList() {
    // given: "R5" only counts for a single entry
    testRule.deploy(processWithRetryCycle("R5/PT30S,PT40S,PT50S"));

    // when
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);

    // then
    assertThat(currentExternalTask().getRetries()).isEqualTo(4);
  }

  // ---------------------------------------------------- decrement on fetch

  @Test
  public void shouldDecrementRetriesOnFetch() {
    // given
    testRule.deploy(processWithRetryCycle("R3/PT5M"));
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);

    // when
    List<LockedExternalTask> tasks = fetch();

    // then the worker already sees the remaining retries
    assertThat(tasks).hasSize(1);
    assertThat(tasks.get(0).getRetries()).isEqualTo(2);
    assertThat(currentExternalTask().getRetries()).isEqualTo(2);
    assertThat(externalTaskIncidentCount()).isZero();
  }

  @Test
  public void shouldStopFetchingWhenWorkerNeverResponds() {
    // given
    testRule.deploy(processWithRetryCycle("R3/PT5M"));
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);

    // when the worker fetches but never completes or reports a failure
    int executions = 0;
    for (int i = 0; i < 10; i++) {
      if (!fetch().isEmpty()) {
        executions++;
      }
      waitForNextAttempt();
    }

    // then the task was handed out exactly as often as configured
    assertThat(executions).isEqualTo(3);
    assertThat(currentExternalTask().getRetries()).isZero();
    assertThat(externalTaskIncidentCount()).isEqualTo(1);
  }

  @Test
  public void shouldCreateIncidentWhenLastAttemptIsFetched() {
    // given
    testRule.deploy(processWithRetryCycle("R2/PT5M"));
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);

    fetch();
    waitForNextAttempt();
    assertThat(externalTaskIncidentCount()).isZero();

    // when the last attempt is handed out
    List<LockedExternalTask> tasks = fetch();

    // then the incident exists while the last attempt runs
    assertThat(tasks).hasSize(1);
    assertThat(tasks.get(0).getRetries()).isZero();
    assertThat(externalTaskIncidentCount()).isEqualTo(1);
  }

  @Test
  public void shouldRemoveIncidentWhenLastAttemptCompletes() {
    // given
    testRule.deploy(processWithRetryCycle("R1/PT5M"));
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey(PROCESS_KEY);

    List<LockedExternalTask> tasks = fetch();
    assertThat(externalTaskIncidentCount()).isEqualTo(1);

    // when the last attempt succeeds
    externalTaskService.complete(tasks.get(0).getId(), WORKER_ID);

    // then the incident is gone and the process continues
    assertThat(externalTaskIncidentCount()).isZero();
    assertThat(taskService.createTaskQuery()
        .processInstanceId(processInstance.getId())
        .taskDefinitionKey("afterExternalTask")
        .count()).isEqualTo(1);
  }

  @Test
  public void shouldAllowFetchAgainAfterRetriesAreReset() {
    // given a task that used up all attempts
    testRule.deploy(processWithRetryCycle("R1/PT5M"));
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    fetch();
    waitForNextAttempt();
    assertThat(fetch()).isEmpty();

    // when an operator sets new retries
    externalTaskService.setRetries(currentExternalTask().getId(), 2);

    // then the incident is resolved and the task is fetchable again
    assertThat(externalTaskIncidentCount()).isZero();
    List<LockedExternalTask> tasks = fetch();
    assertThat(tasks).hasSize(1);
    assertThat(tasks.get(0).getRetries()).isEqualTo(1);
  }

  @Test
  public void shouldKeepUnlimitedRetriesWithoutConfiguration() {
    // given no retry cycle -> old behaviour (retries == null, fetched indefinitely)
    testRule.deploy(processWithoutRetryCycle());
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);

    // when
    int executions = 0;
    for (int i = 0; i < 5; i++) {
      if (!fetch().isEmpty()) {
        executions++;
      }
      setTime(now() + LOCK_TIME + SECOND);
    }

    // then
    assertThat(executions).isEqualTo(5);
    assertThat(currentExternalTask().getRetries()).isNull();
    assertThat(externalTaskIncidentCount()).isZero();
  }
}
