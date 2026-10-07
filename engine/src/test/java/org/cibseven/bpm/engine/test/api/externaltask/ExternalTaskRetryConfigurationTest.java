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
package org.cibseven.bpm.engine.test.api.externaltask;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.SimpleDateFormat;
import java.util.Collections;
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

  /**
   * Same cycle also used to be a common way to add backoff to an external task invocation
   * before external tasks had their own retry cycle support: the asyncBefore continuation
   * job retried according to the cycle, while the external task itself had unlimited retries.
   */
  protected BpmnModelInstance processWithAsyncBeforeAndRetryCycle(String retryCycle) {
    return Bpmn.createExecutableProcess(PROCESS_KEY)
        .startEvent()
        .serviceTask("externalTask")
          .camundaAsyncBefore()
          .camundaExternalTask(TOPIC_NAME)
          .camundaFailedJobRetryTimeCycle(retryCycle)
        .userTask("afterExternalTask")
        .endEvent()
        .done();
  }

  protected BpmnModelInstance processWithAsyncBeforeNoOwnRetryCycle() {
    return Bpmn.createExecutableProcess(PROCESS_KEY)
        .startEvent()
        .serviceTask("externalTask")
          .camundaAsyncBefore()
          .camundaExternalTask(TOPIC_NAME)
        .userTask("afterExternalTask")
        .endEvent()
        .done();
  }

  protected BpmnModelInstance processWithMultiInstanceAndRetryCycle(String retryCycle) {
    return Bpmn.createExecutableProcess(PROCESS_KEY)
        .startEvent()
        .serviceTask("externalTask")
          .camundaExternalTask(TOPIC_NAME)
          .camundaFailedJobRetryTimeCycle(retryCycle)
          .multiInstance()
            .cardinality("3")
          .multiInstanceDone()
        .userTask("afterExternalTask")
        .endEvent()
        .done();
  }

  protected BpmnModelInstance processWithAsyncBeforeMultiInstanceAndRetryCycle(String retryCycle) {
    return Bpmn.createExecutableProcess(PROCESS_KEY)
        .startEvent()
        .serviceTask("externalTask")
          .camundaAsyncBefore()
          .camundaExternalTask(TOPIC_NAME)
          .camundaFailedJobRetryTimeCycle(retryCycle)
          .multiInstance()
            .cardinality("2")
          .multiInstanceDone()
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

  @Test
  public void shouldResolveExpressionBasedRetryCycleOnCreation() {
    // given a retry cycle defined as an expression rather than a literal, resolved
    // against a process variable in scope when the external task is created
    testRule.deploy(processWithRetryCycle("${retryCycle}"));

    // when
    runtimeService.startProcessInstanceByKey(PROCESS_KEY,
        Collections.singletonMap("retryCycle", "R2/PT5M"));

    // then the expression is resolved immediately, same as a static cycle would be -
    // before the fix, the raw unresolved configuration left retries == 0, so the task
    // was never fetchable and no incident was ever raised either
    assertThat(currentExternalTask().getRetries()).isEqualTo(2);
    assertThat(fetch()).as("task must be fetchable, not stuck with retries == 0").hasSize(1);
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
  public void shouldUseConfiguredIntervalsInOrder() {
    // given a cycle with distinct intervals - a single-interval cycle like "R3/PT5M"
    // cannot reveal an off-by-one in the interval index, because index 0 is picked
    // either way; 3 intervals -> 4 attempts, same as shouldInitializeRetriesFromIntervalList
    testRule.deploy(processWithRetryCycle("PT1M,PT5M,PT10M"));
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    long start = now();

    // when the worker fetches repeatedly but never completes or reports a failure
    assertThat(fetch()).as("initial attempt").hasSize(1);

    // then each attempt is delayed by its own configured interval, in the modeled order -
    // ON TOP of the requested lock duration (lockForFetchedAttempt adds, it does not just
    // floor against it), so LOCK_TIME shows up in every expected delay below
    assertNextAttemptAfter(start, LOCK_TIME + MINUTE);
    assertNextAttemptAfter(now(), LOCK_TIME + 5 * MINUTE);
    assertNextAttemptAfter(now(), LOCK_TIME + 10 * MINUTE);

    // and all 4 configured attempts (3 intervals + 1) have been consumed
    assertThat(currentExternalTask().getRetries()).isZero();
    assertThat(externalTaskIncidentCount()).isEqualTo(1);
  }

  /**
   * lockForFetchedAttempt() used to grant max(lockDuration, interval) instead of
   * lockDuration + interval as documented, so a lockDuration that already happened to be as
   * long as (or longer than) the configured interval silently swallowed the whole retry
   * delay. See CIB-562 review finding #7.
   */
  @Test
  public void shouldAddConfiguredIntervalOnTopOfLockDurationEvenWhenLockDurationIsLonger() {
    // given: a lockDuration (10m) that already exceeds the configured interval (5m) - under
    // the old max(lockDuration, interval) formula the interval contributed nothing extra
    testRule.deploy(processWithRetryCycle("R3/PT5M"));
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    long start = now();
    long longLockDuration = 10 * MINUTE;

    // when the worker fetches with a lock duration longer than the configured interval
    assertThat(externalTaskService.fetchAndLock(5, WORKER_ID)
        .topic(TOPIC_NAME, longLockDuration)
        .execute()).as("initial attempt").hasSize(1);

    // then the task is not yet fetchable after just the lock duration elapses
    setTime(start + longLockDuration + SECOND);
    assertThat(externalTaskService.fetchAndLock(5, WORKER_ID).topic(TOPIC_NAME, longLockDuration).execute())
        .as("the configured interval must still add real delay on top of the long lock duration")
        .isEmpty();

    // then it becomes fetchable only after lockDuration + interval
    setTime(start + longLockDuration + 5 * MINUTE + SECOND);
    assertThat(externalTaskService.fetchAndLock(5, WORKER_ID).topic(TOPIC_NAME, longLockDuration).execute())
        .hasSize(1);
  }

  /**
   * lock() and lockForFetchedAttempt() used to be the same method, so the explicit
   * {@code ExternalTaskService.lock(...)} API (which hands out no new attempt) was also
   * bumped up to the configured retry interval. See CIB-562 review finding #7.
   */
  @Test
  public void shouldNotApplyRetryCycleToExplicitLock() {
    // given: a configured retry cycle, so getConfiguredRetryDelay() would return something
    // if it were (wrongly) consulted for an explicit lock
    testRule.deploy(processWithRetryCycle("R3/PT5M"));
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    LockedExternalTask task = fetch().get(0);

    // when the worker explicitly (re-)locks the task for 30s - this hands out no new attempt
    long lockCallTime = now();
    long requestedDuration = 30 * SECOND;
    externalTaskService.lock(task.getId(), WORKER_ID, requestedDuration);

    // then the lock expires after exactly the requested 30s, not after the configured 5m -
    // a retry cycle only applies to attempts that were actually fetched
    setTime(lockCallTime + requestedDuration - SECOND);
    assertThat(fetch()).as("must not be fetchable before the requested 30s lock expires").isEmpty();

    setTime(lockCallTime + requestedDuration + SECOND);
    assertThat(fetch())
        .as("must be fetchable right after the requested 30s lock expires, not after 5m")
        .hasSize(1);
  }

  /**
   * failed() used to pick the next interval from the entity's current retries value - the
   * one set at the last fetch - rather than from the value this failure itself leaves behind.
   * For a worker that reports back explicitly, those two are different numbers, so the wrong
   * interval got picked: the same (first) interval was offered twice in a row instead of
   * advancing. See CIB-562 review finding #6.
   */
  @Test
  public void shouldPickNextIntervalFromReportedRetriesNotFetchTimeRetries() {
    // given: 3 intervals - the first (1m) governs the very first lock
    testRule.deploy(processWithRetryCycle("PT1M,PT5M,PT10M"));
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    long start = now();

    // when the worker reports a failure the standard way, right after the first fetch
    LockedExternalTask task = fetch().get(0);
    externalTaskService.handleFailure(task.getId(), WORKER_ID, ERROR_MESSAGE,
        task.getRetries() - 1, MINUTE);

    // then the SECOND configured interval governs the next attempt - a repeat of the first
    // (1m) would mean the index was still being read from the stale, fetch-time retries value
    assertNextAttemptAfter(start, 5 * MINUTE);
  }

  /**
   * Once a retry cycle applies to an activity (modeled or engine-wide default), its interval
   * is what controls timing - same as for jobs, where the cycle fully owns the delay and the
   * caller has no say in it. The worker's own retryTimeout is only ever a fallback for when
   * no cycle applies at all (see shouldKeepUnlimitedRetriesWithoutConfiguration). This is
   * existing, intentional behavior; this test exists so it stays documented and covered.
   */
  @Test
  public void shouldApplyConfiguredIntervalInsteadOfWorkerRetryTimeout() {
    // given
    testRule.deploy(processWithRetryCycle("R3/PT5M"));
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    long start = now();
    LockedExternalTask task = fetch().get(0);

    // when the worker reports a failure with its own retryTimeout, unrelated to the cycle
    externalTaskService.handleFailure(task.getId(), WORKER_ID, ERROR_MESSAGE,
        task.getRetries() - 1, 30 * SECOND);

    // then the configured interval (5m) wins, not the worker's 30s
    assertNextAttemptAfter(start, 5 * MINUTE);
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

  // ------------------------------------------------- engine-wide default cycle

  @AfterEach
  public void resetEngineDefaults() {
    processEngineConfiguration.setExternalTaskFailedJobRetryTimeCycle(null);
    processEngineConfiguration.setFailedJobRetryTimeCycle(null);
  }

  @Test
  public void shouldApplyEngineDefaultRetryTimeCycleWhenNoElementCycleConfigured() {
    // given an engine-wide default and a process that does not model its own cycle
    processEngineConfiguration.setExternalTaskFailedJobRetryTimeCycle("R2/PT5M");
    testRule.deploy(processWithoutRetryCycle());

    // when
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);

    // then the engine default initialized the retries, same as a modeled cycle would
    assertThat(currentExternalTask().getRetries()).isEqualTo(2);
  }

  @Test
  public void shouldPreferElementRetryTimeCycleOverEngineDefault() {
    // given both an engine-wide default and an element-level cycle
    processEngineConfiguration.setExternalTaskFailedJobRetryTimeCycle("R2/PT5M");
    testRule.deploy(processWithRetryCycle("R4/PT10M"));

    // when
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);

    // then the modeled cycle wins
    assertThat(currentExternalTask().getRetries()).isEqualTo(4);
  }

  @Test
  public void shouldNotApplyJobDefaultRetryTimeCycleToExternalTasks() {
    // given only the (pre-existing) job-level default is configured, external tasks are
    // not supposed to pick it up implicitly - only their own dedicated default does that
    processEngineConfiguration.setFailedJobRetryTimeCycle("R2/PT5M");
    testRule.deploy(processWithoutRetryCycle());

    // when
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);

    // then behavior is unchanged: unlimited retries, no incident
    assertThat(currentExternalTask().getRetries()).isNull();
    assertThat(externalTaskIncidentCount()).isZero();
  }

  @Test
  public void shouldApplyOwnRetryCycleEvenWhenAsyncBefore() {
    // given a cycle modeled directly on an external task that also happens to be asyncBefore.
    // The CIBseven modeler only exposes the retry-cycle field once asyncBefore/After is
    // enabled, so this combination is the norm, not an edge case - an own modeled cycle must
    // always win for the external task regardless (it is independently also still applied to
    // the asyncBefore job itself, which is unaffected by any of this)
    testRule.deploy(processWithAsyncBeforeAndRetryCycle("R2/PT5M"));

    // when the asyncBefore continuation runs and creates the external task
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    testRule.executeAvailableJobs();

    // then
    assertThat(currentExternalTask().getRetries()).isEqualTo(2);
  }

  @Test
  public void shouldStillApplyEngineDefaultToExternalTaskWithAsyncBefore() {
    // given an asyncBefore external task with no modeled cycle of its own: the engine-wide
    // external task default still applies, same as when asyncBefore is not set
    processEngineConfiguration.setExternalTaskFailedJobRetryTimeCycle("R2/PT5M");
    testRule.deploy(processWithAsyncBeforeNoOwnRetryCycle());

    // when the asyncBefore continuation runs and creates the external task
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    testRule.executeAvailableJobs();

    // then
    assertThat(currentExternalTask().getRetries()).isEqualTo(2);
  }

  @Test
  public void shouldApplyModeledRetryCycleToMultiInstanceExternalTask() {
    // given a retry cycle modeled directly on the service task - the same, natural place
    // used for a plain (non multi-instance) external task - on an activity that also happens
    // to be multi-instance; before the fix this was silently ignored because the parser only
    // looked for a cycle nested inside multiInstanceLoopCharacteristics for such activities
    testRule.deploy(processWithMultiInstanceAndRetryCycle("R2/PT5M"));

    // when
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);

    // then every spawned instance picks up the modeled cycle, not unlimited retries
    assertThat(externalTaskService.createExternalTaskQuery().list())
        .as("one external task per multi-instance execution")
        .hasSize(3)
        .allSatisfy(task -> assertThat(task.getRetries()).isEqualTo(2));
  }

  @Test
  public void shouldApplyOwnRetryCycleToMultiInstanceExternalTaskEvenWhenAsyncBefore() {
    // given the same asyncBefore + own-cycle combination as
    // shouldApplyOwnRetryCycleEvenWhenAsyncBefore, but on a multi-instance body
    testRule.deploy(processWithAsyncBeforeMultiInstanceAndRetryCycle("R2/PT5M"));

    // when the asyncBefore continuations run and create the external tasks
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    testRule.executeAvailableJobs();

    // then every spawned instance picks up the modeled cycle
    assertThat(externalTaskService.createExternalTaskQuery().list())
        .hasSize(2)
        .allSatisfy(task -> assertThat(task.getRetries()).isEqualTo(2));
  }

  @Test
  public void shouldApplyRetryCycleToExternalIntermediateMessageThrowEvent() {
    // given an intermediate message throw event implemented as external task
    testRule.deploy(Bpmn.createExecutableProcess(PROCESS_KEY)
        .startEvent()
        .intermediateThrowEvent("externalTask")
          .camundaFailedJobRetryTimeCycle("R2/PT5M")
          .messageEventDefinition()
            .message("message")
            .camundaType("external")
            .camundaTopic(TOPIC_NAME)
          .messageEventDefinitionDone()
        .userTask("afterExternalTask")
        .endEvent()
        .done());

    // when
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);

    // then the cycle applies like for a service task or a message end event
    assertThat(currentExternalTask().getRetries()).isEqualTo(2);
  }

  // ------------------------------------------- errors in the retry configuration

  @Test
  public void shouldIgnoreRetryCycleExpressionWithMissingVariable() {
    // given an expression cycle whose variable is not set
    testRule.deploy(processWithRetryCycle("${retryCycle}"));

    // when the process is started without the variable
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);

    // then the task is created and handled as without a retry configuration
    assertThat(currentExternalTask().getRetries()).isNull();

    // and fetching and failing does not break, for this or any other worker of the topic
    List<LockedExternalTask> tasks = fetch();
    assertThat(tasks).hasSize(1);
    externalTaskService.handleFailure(tasks.get(0).getId(), WORKER_ID, ERROR_MESSAGE, 1, SECOND);
    setTime(now() + 2 * SECOND);
    assertThat(fetch()).hasSize(1);
    assertThat(externalTaskIncidentCount()).isZero();
  }

  @Test
  public void shouldIgnoreRetryCycleWithInvalidListEntry() {
    // given an interval list with an invalid entry - not detected by
    // ParseUtil#parseRetryIntervals, which only validates single-interval cycles
    testRule.deploy(processWithRetryCycle("PT1M,PT5X"));

    // when
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);

    // then the task is handled as without a retry configuration
    assertThat(currentExternalTask().getRetries()).isNull();
    for (int i = 0; i < 3; i++) {
      assertThat(fetch()).as("attempt %d", i + 1).hasSize(1);
      setTime(now() + LOCK_TIME + SECOND);
    }
    assertThat(externalTaskIncidentCount()).isZero();
  }

  @Test
  public void shouldIgnoreRetryCycleExpressionResolvingToInvalidList() {
    // given an expression that resolves to an interval list with an invalid entry
    testRule.deploy(processWithRetryCycle("${retryCycle}"));

    // when
    runtimeService.startProcessInstanceByKey(PROCESS_KEY,
        Collections.singletonMap("retryCycle", "PT1M,PT5X"));

    // then
    assertThat(currentExternalTask().getRetries()).isNull();
    assertThat(fetch()).hasSize(1);
    setTime(now() + LOCK_TIME + SECOND);
    assertThat(fetch()).hasSize(1);
  }

  @Test
  public void shouldHandOutOneAttemptForZeroRetryCycle() {
    // given a cycle that yields 0 retries
    testRule.deploy(processWithRetryCycle("R0/PT5M"));

    // when
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);

    // then the task gets one attempt instead of never being fetched without an incident
    assertThat(currentExternalTask().getRetries()).isEqualTo(1);
    assertThat(fetch()).hasSize(1);
    assertThat(externalTaskIncidentCount()).isEqualTo(1);
    waitForNextAttempt();
    assertThat(fetch()).isEmpty();
  }

  @Test
  public void shouldTreatNegativeRetriesOfLastAttemptAsZero() {
    // given the last attempt, handed out with retries 0
    testRule.deploy(processWithRetryCycle("R1/PT5M"));
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    LockedExternalTask task = fetch().get(0);
    assertThat(task.getRetries()).isZero();

    // when a worker using the old pattern reports getRetries() - 1
    externalTaskService.handleFailure(task.getId(), WORKER_ID, ERROR_MESSAGE, task.getRetries() - 1, SECOND);

    // then the failure is stored instead of being rejected
    ExternalTask externalTask = currentExternalTask();
    assertThat(externalTask.getRetries()).isZero();
    assertThat(externalTask.getErrorMessage()).isEqualTo(ERROR_MESSAGE);
    assertThat(externalTaskIncidentCount()).isEqualTo(1);
  }
}
