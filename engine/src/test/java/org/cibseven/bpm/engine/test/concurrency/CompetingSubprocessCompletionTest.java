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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.cibseven.bpm.engine.OptimisticLockingException;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.impl.ProcessEngineLogger;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cmd.CompleteTaskCmd;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;


/**
 * @author Tom Baeyens
 */
public class CompetingSubprocessCompletionTest {

  private static Logger LOG = ProcessEngineLogger.TEST_LOGGER.getLogger();

  @RegisterExtension
  protected ProvidedProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  protected ProcessEngineConfigurationImpl processEngineConfiguration;
  protected RuntimeService runtimeService;
  protected TaskService taskService;

  static ControllableThread activeThread;


  @BeforeEach
  public void initializeServices() {
    processEngineConfiguration = engineRule.getProcessEngineConfiguration();
    runtimeService = engineRule.getRuntimeService();
    taskService = engineRule.getTaskService();
  }

  public class CompleteTaskThread extends ControllableThread {
    String taskId;
    OptimisticLockingException exception;
    public CompleteTaskThread(String taskId) {
      this.taskId = taskId;
    }
    public synchronized void startAndWaitUntilControlIsReturned() {
      activeThread = this;
      super.startAndWaitUntilControlIsReturned();
    }
    public void run() {
      try {
        processEngineConfiguration
          .getCommandExecutorTxRequired()
          .execute(new ControlledCommand(activeThread, new CompleteTaskCmd(taskId, null)));

      } catch (OptimisticLockingException e) {
        this.exception = e;
      }
      LOG.debug(getName()+" ends");
    }
  }

  /**
   * This test requires a minimum of three concurrent executions to avoid
   * that all threads attempt compaction by which synchronization happens "by accident"
   */
  @Deployment
  @Test
  public void testCompetingSubprocessEnd() throws Exception {
    runtimeService.startProcessInstanceByKey("CompetingSubprocessEndProcess");

    List<Task> tasks = taskService.createTaskQuery().list();
    assertEquals(3, tasks.size());

    LOG.debug("test thread starts thread one");
    CompleteTaskThread threadOne = new CompleteTaskThread(tasks.get(0).getId());
    threadOne.startAndWaitUntilControlIsReturned();

    LOG.debug("test thread continues to start thread two");
    CompleteTaskThread threadTwo = new CompleteTaskThread(tasks.get(1).getId());
    threadTwo.startAndWaitUntilControlIsReturned();

    LOG.debug("test thread notifies thread 1");
    threadOne.proceedAndWaitTillDone();
    assertNull(threadOne.exception);

    LOG.debug("test thread notifies thread 2");
    threadTwo.proceedAndWaitTillDone();
    assertNotNull(threadTwo.exception);
    testRule.assertTextPresent("was updated by another transaction concurrently", threadTwo.exception.getMessage());
  }

}
