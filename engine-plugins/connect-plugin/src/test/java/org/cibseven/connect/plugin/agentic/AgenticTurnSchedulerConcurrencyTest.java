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
package org.cibseven.connect.plugin.agentic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.cibseven.bpm.engine.OptimisticLockingException;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.runtime.Execution;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Two children of one agentic scope finishing in two transactions at the same time.
 *
 * <p>The scheduler keeps at most one open turn per scope, and it does so by looking for
 * a waiting job before inserting one. Check-then-insert is not atomic across
 * transactions, so the question this class answers by measurement rather than by
 * argument: can two concurrent completions both see none and both insert one?
 *
 * <p>Every other test of the scheduler completes its children one after another on one
 * thread, where the check is trivially correct. This one runs them on two threads that
 * are released together, which is the only shape in which the invariant can break.
 *
 * <h3>What was measured</h3>
 * They do overlap, and the invariant holds anyway — but not because of the check. In
 * every run one thread lost on an optimistic lock while writing the agent state, and
 * its whole transaction rolled back, the job insert with it. Both completions
 * necessarily touch the same state execution, so the engine serializes any two
 * transactions that could schedule a turn for one scope, and the check only has to
 * catch the ordinary case of two children ending one after another.
 *
 * <p>The losing completion rolls back too, and a real client retries it — the ordinary
 * answer to concurrent work on one scope, not something an agentic scope introduces.
 * That is why this test asserts the invariant rather than that both completions
 * succeed.
 */
public class AgenticTurnSchedulerConcurrencyTest {

  /** Shared by both threads: an in-memory database two engines-worth of commands can hit. */
  private static final String JDBC_URL = "jdbc:h2:mem:agentic-concurrency;DB_CLOSE_DELAY=-1";

  protected ProcessEngine engine;
  protected ProcessEngineConfigurationImpl config;

  @BeforeEach
  public void setUp() {
    config = new StandaloneInMemProcessEngineConfiguration();
    config.setJdbcUrl(JDBC_URL);
    config.setJobExecutorActivate(false);
    config.setEnforceHistoryTimeToLive(false);
    config.setProcessEnginePlugins(
        Collections.singletonList(new ConnectProcessEnginePlugin()));
    engine = config.buildProcessEngine();
  }

  @AfterEach
  public void tearDown() {
    engine.close();
  }

  /**
   * The invariant under real concurrency.
   *
   * <p>Both completions are released by the same latch, so they overlap. Whatever the
   * database does to them — serialize, or let both through and have one lose on the
   * optimistic lock — the scope must end up with at most one turn job. A second one
   * would mean a second model call for the same state, which is the cost this guards.
   */
  @Test
  public void twoChildrenFinishingAtOnceScheduleOneTurn() throws Exception {
    ProcessInstance pi = start();
    String scope = scopeExecutionId(pi.getId());
    engine.getRuntimeService().createAdHocSubProcessActivation(scope)
        .startActivity("a").execute();
    engine.getRuntimeService().createAdHocSubProcessActivation(scope)
        .startActivity("b").execute();

    // The entry job would satisfy the check on its own, so it goes: without it, both
    // threads really do find nothing waiting.
    engine.getManagementService().deleteJob(
        engine.getManagementService().createJobQuery().singleResult().getId());

    List<Task> open = engine.getTaskService().createTaskQuery().list();
    assertThat(open).as("two children waiting").hasSize(2);

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService threads = Executors.newFixedThreadPool(2);
    try {
      Future<Throwable> first = threads.submit(complete(open.get(0).getId(), ready, go));
      Future<Throwable> second = threads.submit(complete(open.get(1).getId(), ready, go));

      assertThat(ready.await(20, TimeUnit.SECONDS)).as("both threads reached the start").isTrue();
      go.countDown();

      Throwable firstFailure = first.get(30, TimeUnit.SECONDS);
      Throwable secondFailure = second.get(30, TimeUnit.SECONDS);

      // One may lose on the optimistic lock -- that is the engine serializing them, and
      // the completion it rolled back is retried by a real client. Both failing is not
      // acceptable: nothing would have happened at all.
      assertThat(firstFailure == null || secondFailure == null)
          .as("at least one completion has to get through; both failed: "
              + firstFailure + " / " + secondFailure)
          .isTrue();
      for (Throwable failure : new Throwable[] {firstFailure, secondFailure}) {
        if (failure != null) {
          assertThat(failure).as("only an optimistic lock may stop a completion")
              .isInstanceOf(OptimisticLockingException.class);
        }
      }
    } finally {
      threads.shutdownNow();
    }

    List<Job> jobs = engine.getManagementService().createJobQuery().list();
    assertThat(jobs)
        .as("at most one turn for a scope, whatever the timing was")
        .hasSizeLessThanOrEqualTo(1);
  }

  /** Completes one task once {@code go} fires, and reports what it ran into. */
  protected Callable<Throwable> complete(final String taskId, final CountDownLatch ready,
      final CountDownLatch go) {
    return new Callable<Throwable>() {
      @Override
      public Throwable call() {
        ready.countDown();
        try {
          go.await(20, TimeUnit.SECONDS);
          engine.getTaskService().complete(taskId);
          return null;
        } catch (Throwable failure) {
          return failure;
        }
      }
    };
  }

  protected String scopeExecutionId(String processInstanceId) {
    for (Execution execution : engine.getRuntimeService().createExecutionQuery()
        .processInstanceId(processInstanceId).list()) {
      if ("adHoc".equals(((ExecutionEntity) execution).getActivityId())) {
        return execution.getId();
      }
    }
    throw new AssertionError("no execution sitting on the ad hoc scope");
  }

  protected ProcessInstance start() {
    engine.getRepositoryService().createDeployment()
        .addString("agentic-concurrency.bpmn20.xml",
            "<?xml version='1.0' encoding='UTF-8'?>"
            + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
            + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
            + "             targetNamespace='http://cibseven.org/agentic'>"
            + "  <process id='agenticConcurrency' isExecutable='true'>"
            + "    <startEvent id='start' />"
            + "    <sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
            + "    <adHocSubProcess id='adHoc'>"
            + "      <extensionElements><camunda:properties>"
            + "        <camunda:property name='cibseven.agentic.enabled' value='true' />"
            + "        <camunda:property name='cibseven.agentic.message' value='Do the work.' />"
            + "      </camunda:properties></extensionElements>"
            + "      <userTask id='a' /><userTask id='b' />"
            + "    </adHocSubProcess>"
            + "    <sequenceFlow id='f2' sourceRef='adHoc' targetRef='end' />"
            + "    <endEvent id='end' />"
            + "  </process>"
            + "</definitions>").deploy();
    return engine.getRuntimeService().startProcessInstanceByKey("agenticConcurrency");
  }
}
