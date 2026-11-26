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
package org.cibseven.bpm.engine.test.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.ExternalTaskService;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.OptimisticLockingException;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.externaltask.LockedExternalTask;
import org.cibseven.bpm.engine.history.HistoricExternalTaskLog;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cmd.FetchExternalTasksCmd;
import org.cibseven.bpm.engine.impl.externaltask.TopicFetchInstruction;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.ExternalTaskEntity;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.util.ProcessEngineBootstrapRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Order;

import org.junit.jupiter.api.Test;


/**
 * @author Thorben Lindhauer
 *
 */
public class CompetingExternalTaskFetchingTest {

  @RegisterExtension
  @Order(3) public static ProcessEngineBootstrapRule bootstrapRule = new ProcessEngineBootstrapRule();
  @RegisterExtension
  @Order(7) protected ProvidedProcessEngineRule engineRule = new ProvidedProcessEngineRule(bootstrapRule);
  @RegisterExtension
  @Order(9) public ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  protected ProcessEngineConfigurationImpl processEngineConfiguration;
  protected RuntimeService runtimeService;
  protected HistoryService historyService;

  @BeforeEach
  public void initializeServices() {
    processEngineConfiguration = engineRule.getProcessEngineConfiguration();
    runtimeService = engineRule.getRuntimeService();
    historyService = engineRule.getHistoryService();
  }

  public class ExternalTaskFetcherThread extends ControllableThread {

    protected String workerId;
    protected int results;
    protected String topic;

    protected List<LockedExternalTask> fetchedTasks = Collections.emptyList();
    /** any exception of the fetch, not only an {@link OptimisticLockingException} */
    protected RuntimeException exception;

    public ExternalTaskFetcherThread(String workerId, int results, String topic) {
      this.workerId = workerId;
      this.results = results;
      this.topic = topic;
    }

    public void run() {
      Map<String, TopicFetchInstruction> instructions = new HashMap<String, TopicFetchInstruction>();

      TopicFetchInstruction instruction = new TopicFetchInstruction(topic, 10000L);
      instructions.put(topic, instruction);

      try {
        fetchedTasks = processEngineConfiguration.getCommandExecutorTxRequired()
            .execute(new ControlledFetchExternalTasksCmd(this, workerId, results, instructions));
      } catch (RuntimeException e) {
        exception = e;
      }
    }
  }

  /**
   * Returns control to the test thread after the tasks are locked in memory, but before
   * anything is flushed. With history level FULL the command flushes the locks itself
   * (before it writes the 'fetched' log), so pausing after {@code execute()} - as
   * {@link ControlledCommand} does - would be too late: the second thread would block
   * on the row lock of the first one.
   */
  public static class ControlledFetchExternalTasksCmd extends FetchExternalTasksCmd {

    protected ControllableThread controllableThread;

    public ControlledFetchExternalTasksCmd(ControllableThread controllableThread, String workerId,
                                           int maxResults, Map<String, TopicFetchInstruction> instructions) {
      super(workerId, maxResults, instructions);
      this.controllableThread = controllableThread;
    }

    @Override
    protected void produceFetchedEvents(CommandContext commandContext,
                                        List<ExternalTaskEntity> fetchedEventTasks,
                                        List<LockedExternalTask> result) {
      controllableThread.returnControlToTestThreadAndWait();
      super.produceFetchedEvents(commandContext, fetchedEventTasks, result);
    }
  }

  /**
   * Returns control to the test thread once, after the execution of the first task is read
   * but before its process instance is resolved: the window in which another worker can
   * complete the task and end the process instance (CIB7-2798).
   */
  public static class PausingBeforeProcessInstanceFetchCmd extends FetchExternalTasksCmd {

    protected ControllableThread controllableThread;
    protected String pausedProcessInstanceId;

    public PausingBeforeProcessInstanceFetchCmd(ControllableThread controllableThread, String workerId,
                                                int maxResults, Map<String, TopicFetchInstruction> instructions) {
      super(workerId, maxResults, instructions);
      this.controllableThread = controllableThread;
    }

    @Override
    protected ExecutionEntity getProcessInstance(ExecutionEntity execution) {
      if (pausedProcessInstanceId == null) {
        pausedProcessInstanceId = execution.getProcessInstanceId();
        controllableThread.returnControlToTestThreadAndWait();
      }
      return super.getProcessInstance(execution);
    }
  }

  public class PausingFetcherThread extends ControllableThread {

    protected PausingBeforeProcessInstanceFetchCmd cmd;
    protected List<LockedExternalTask> fetchedTasks = Collections.emptyList();
    protected RuntimeException exception;

    public PausingFetcherThread(String workerId) {
      Map<String, TopicFetchInstruction> instructions = new HashMap<>();
      instructions.put("externalTaskTopic", new TopicFetchInstruction("externalTaskTopic", 10000L));
      cmd = new PausingBeforeProcessInstanceFetchCmd(this, workerId, 5, instructions);
    }

    @Override
    public void run() {
      try {
        fetchedTasks = processEngineConfiguration.getCommandExecutorTxRequired().execute(cmd);
      } catch (RuntimeException e) {
        exception = e;
      }
    }
  }

  @Deployment(resources = "org/cibseven/bpm/engine/test/concurrency/CompetingExternalTaskFetchingTest.externalTaskInSubProcess.bpmn20.xml")
  @Test
  public void testFetchingTaskWhoseProcessInstanceEndedConcurrently() {
    assertFetchSkipsTaskWhoseProcessInstanceEndedConcurrently();
  }

  @Deployment(resources = "org/cibseven/bpm/engine/test/concurrency/CompetingExternalTaskFetchingTest.externalTaskInSubProcess.bpmn20.xml")
  @Test
  public void testFetchingTaskWhoseProcessInstanceEndedConcurrentlyWithoutTreePrefetch() {
    boolean prefetch = processEngineConfiguration.isExecutionTreePrefetchEnabled();
    processEngineConfiguration.setExecutionTreePrefetchEnabled(false);
    try {
      assertFetchSkipsTaskWhoseProcessInstanceEndedConcurrently();
    } finally {
      processEngineConfiguration.setExecutionTreePrefetchEnabled(prefetch);
    }
  }

  protected void assertFetchSkipsTaskWhoseProcessInstanceEndedConcurrently() {
    String firstId = runtimeService.startProcessInstanceByKey("externalTaskInSubProcess").getId();
    String secondId = runtimeService.startProcessInstanceByKey("externalTaskInSubProcess").getId();

    // worker A has read the execution of one task and pauses before reading its process instance
    PausingFetcherThread workerA = new PausingFetcherThread("workerA");
    workerA.startAndWaitUntilControlIsReturned();
    String endedId = workerA.cmd.pausedProcessInstanceId;
    String remainingId = endedId.equals(firstId) ? secondId : firstId;

    // meanwhile worker B locks and completes that task, which ends the process instance
    ExternalTaskService externalTaskService = engineRule.getExternalTaskService();
    String endedTaskId = externalTaskService.createExternalTaskQuery()
        .processInstanceId(endedId).singleResult().getId();
    externalTaskService.lock(endedTaskId, "workerB", 10000L);
    externalTaskService.complete(endedTaskId, "workerB");
    assertEquals(0, runtimeService.createProcessInstanceQuery().processInstanceId(endedId).count());

    // worker A skips that task and returns the other one without an error
    workerA.proceedAndWaitTillDone();
    assertNull(workerA.exception);
    assertEquals(1, workerA.fetchedTasks.size());
    assertEquals(remainingId, workerA.fetchedTasks.get(0).getProcessInstanceId());
    assertEquals("workerA", externalTaskService.createExternalTaskQuery()
        .processInstanceId(remainingId).singleResult().getWorkerId());
  }

  @Deployment
  @Test
  public void testCompetingExternalTaskFetching() {
    runtimeService.startProcessInstanceByKey("oneExternalTaskProcess");

    ExternalTaskFetcherThread thread1 = new ExternalTaskFetcherThread("thread1", 5, "externalTaskTopic");
    ExternalTaskFetcherThread thread2 = new ExternalTaskFetcherThread("thread2", 5, "externalTaskTopic");

    // both threads fetch the same task and wait before flushing the lock
    thread1.startAndWaitUntilControlIsReturned();
    thread2.startAndWaitUntilControlIsReturned();

    // thread1 succeeds
    thread1.proceedAndWaitTillDone();
    assertNull(thread1.exception);
    assertEquals(1, thread1.fetchedTasks.size());

    // thread2 does not succeed in locking the job
    thread2.proceedAndWaitTillDone();
    assertEquals(0, thread2.fetchedTasks.size());
    // but does not fail with an OptimisticLockingException
    assertNull(thread2.exception);
  }

  @Deployment(resources = "org/cibseven/bpm/engine/test/concurrency/CompetingExternalTaskFetchingTest.testCompetingExternalTaskFetching.bpmn20.xml")
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  @Test
  public void testCompetingExternalTaskFetchingWritesFetchedLogOnlyForWinner() {
    runtimeService.startProcessInstanceByKey("oneExternalTaskProcess");

    ExternalTaskFetcherThread thread1 = new ExternalTaskFetcherThread("thread1", 5, "externalTaskTopic");
    ExternalTaskFetcherThread thread2 = new ExternalTaskFetcherThread("thread2", 5, "externalTaskTopic");

    thread1.startAndWaitUntilControlIsReturned();
    thread2.startAndWaitUntilControlIsReturned();

    thread1.proceedAndWaitTillDone();
    thread2.proceedAndWaitTillDone();
    assertNull(thread1.exception);
    assertNull(thread2.exception);
    assertEquals(1, thread1.fetchedTasks.size());
    assertEquals(0, thread2.fetchedTasks.size());

    // only the worker that got the lock is logged
    List<HistoricExternalTaskLog> fetchedLogs = historyService.createHistoricExternalTaskLogQuery()
        .fetchedLog()
        .list();
    assertEquals(1, fetchedLogs.size());
    assertEquals("thread1", fetchedLogs.get(0).getWorkerId());
  }

  @Deployment(resources = "org/cibseven/bpm/engine/test/concurrency/CompetingExternalTaskFetchingTest.testCompetingExternalTaskFetching.bpmn20.xml")
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  @Test
  public void testFetchingConcurrentlyDeletedExternalTaskWritesNoFetchedLog() {
    String processInstanceId = runtimeService.startProcessInstanceByKey("oneExternalTaskProcess").getId();

    ExternalTaskFetcherThread thread = new ExternalTaskFetcherThread("thread1", 5, "externalTaskTopic");

    // the thread selects the task and waits before flushing the lock
    thread.startAndWaitUntilControlIsReturned();

    // meanwhile the task is deleted
    runtimeService.deleteProcessInstance(processInstanceId, null);

    thread.proceedAndWaitTillDone();
    assertNull(thread.exception);
    assertEquals(0, thread.fetchedTasks.size());

    // no 'fetched' entry after 'deleted'
    assertEquals(0, historyService.createHistoricExternalTaskLogQuery().fetchedLog().count());
    assertEquals(1, historyService.createHistoricExternalTaskLogQuery().deletionLog().count());
  }
}
