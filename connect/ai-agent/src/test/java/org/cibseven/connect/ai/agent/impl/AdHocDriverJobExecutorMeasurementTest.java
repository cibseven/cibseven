/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.cibseven.connect.ai.agent.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocAgentState;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.interceptor.Command;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.PvmActivity;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A measurement, not a feature test.
 *
 * <p>Running the ad-hoc agent suite against a real distribution failed on every
 * model: by the time the connector executed, walking up from the driver's
 * execution never reached an execution carrying
 * {@code AdHocSubProcessActivityBehavior}, and the engine's own
 * {@code AdHocAgentState.findAdHocScope} returned null at the same moment. Two
 * guesses were measured and refuted there — the classloaders were identical, and
 * the failure is not in connector code because the engine-side walk failed too.
 *
 * <p>What every existing test in this ticket has in common is
 * {@code setJobExecutorActivate(false)}: the driver's job is either never run or
 * run by hand through {@code executeJob}. None of them ever let a live job
 * executor run the driver. This class closes that gap by measuring the same
 * situation three ways, so the difference between them is the answer:
 *
 * <ol>
 *   <li>{@link #measureAsyncDriverUnderALiveJobExecutor()} — the distribution's
 *       configuration.</li>
 *   <li>{@link #measureAsyncDriverWithTheJobRunByHand()} — what the existing
 *       tests do, and what passes today.</li>
 *   <li>{@link #measureSynchronousDriver()} — no job at all, to tell whether
 *       {@code asyncBefore} is the trigger.</li>
 * </ol>
 *
 * <p>All three assert the same thing, so a failure names which configuration
 * breaks and prints the tree that was actually observed.
 */
public class AdHocDriverJobExecutorMeasurementTest {

  /** What the driver saw when it ran. Written from the executing thread. */
  static volatile String observed;

  /** True when the driver could find its enclosing scope. */
  static volatile Boolean foundScope;

  /**
   * Stands in for the agent. Records the execution tree it can see, the way
   * {@code AdHocSubProcessTool.requireAdHocScope} would walk it.
   */
  public static class Probe implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
      ExecutionEntity self = (ExecutionEntity) execution;
      StringBuilder walk = new StringBuilder();
      ExecutionEntity probe = self;
      int depth = 0;
      while (probe != null && depth < 10) {
        PvmActivity activity = probe.getActivity();
        Object behaviour = (activity == null) ? null : activity.getActivityBehavior();
        walk.append(" [").append(depth).append("] activity=")
            .append(activity == null ? "null" : activity.getId())
            .append(" behaviour=")
            .append(behaviour == null ? "null" : behaviour.getClass().getSimpleName());
        probe = probe.getParent();
        depth++;
      }
      ExecutionEntity scope = AdHocAgentState.findAdHocScope(self);
      foundScope = Boolean.valueOf(scope != null);
      walk.append("  ==> findAdHocScope=")
          .append(scope == null ? "null" : scope.getId());
      observed = walk.toString();
    }
  }

  private final List<ProcessEngine> engines = new ArrayList<>();

  @Before
  public void reset() {
    observed = null;
    foundScope = null;
  }

  @After
  public void closeEngines() {
    for (ProcessEngine engine : engines) {
      engine.close();
    }
    engines.clear();
  }

  /** A fresh engine per measurement, so the executor setting is not shared. */
  private ProcessEngine engine(String name, boolean jobExecutorActive) {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName(name);
    configuration.setJdbcUrl("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(jobExecutorActive);
    configuration.setHistoryTimeToLive("P30D");
    configuration.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
    ProcessEngine built = configuration.buildProcessEngine();
    engines.add(built);
    return built;
  }

  private static String model(String processId, boolean driverAsync) {
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + " xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + " targetNamespace='http://cibseven.org/measure'>"
        + "<process id='" + processId + "' isExecutable='true'>"
        + "  <startEvent id='start' />"
        + "  <sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + "  <adHocSubProcess id='adHoc'>"
        + "    <extensionElements><camunda:properties>"
        + "      <camunda:property name='explicitCompletionOnly' value='true' />"
        + "      <camunda:property name='adHocDriverActivity' value='agent' />"
        + "      <camunda:property name='activeActivityIds' value='agent' />"
        + "    </camunda:properties></extensionElements>"
        + "    <serviceTask id='agent' name='Agent'"
        + "        camunda:class='" + Probe.class.getName() + "'"
        + (driverAsync ? " camunda:asyncBefore='true'" : "") + " />"
        + "    <userTask id='waits' name='Waits' />"
        + "  </adHocSubProcess>"
        + "  <sequenceFlow id='f2' sourceRef='adHoc' targetRef='end' />"
        + "  <endEvent id='end' />"
        + "</process></definitions>";
  }

  private ProcessInstance start(ProcessEngine engine, String processId, boolean driverAsync) {
    engine.getRepositoryService().createDeployment()
        .addString(processId + ".bpmn20.xml", model(processId, driverAsync))
        .deploy();
    return engine.getRuntimeService().startProcessInstanceByKey(processId);
  }

  /** Waits until the driver has run, or gives up. */
  private static void awaitProbe(long millis) {
    long deadline = System.currentTimeMillis() + millis;
    while (observed == null && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private static void report(String label) {
    System.out.println("MEASUREMENT " + label + ":" + observed);
  }

  /**
   * The distribution's configuration: {@code asyncBefore} driver, live job
   * executor, entry activation. This is the one that failed in the running
   * application.
   */
  @Test
  public void measureAsyncDriverUnderALiveJobExecutor() {
    ProcessEngine engine = engine("measure-live", true);

    start(engine, "measureLive", true);
    awaitProbe(20000);

    report("async driver, LIVE job executor");
    assertThat(observed).as("the driver never ran").isNotNull();
    assertThat(foundScope)
        .as("driver could not find its scope, walk was:" + observed)
        .isTrue();
  }

  /** What the existing tests do: the same job, executed by hand. */
  @Test
  public void measureAsyncDriverWithTheJobRunByHand() {
    ProcessEngine engine = engine("measure-manual", false);

    ProcessInstance instance = start(engine, "measureManual", true);
    List<Job> jobs = engine.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(jobs).as("the driver's job").hasSize(1);
    engine.getManagementService().executeJob(jobs.get(0).getId());

    report("async driver, job run by hand");
    assertThat(observed).as("the driver never ran").isNotNull();
    assertThat(foundScope)
        .as("driver could not find its scope, walk was:" + observed)
        .isTrue();
  }

  /** No job at all, to tell whether asyncBefore is what makes the difference. */
  @Test
  public void measureSynchronousDriver() {
    ProcessEngine engine = engine("measure-sync", false);

    start(engine, "measureSync", false);

    report("synchronous driver");
    assertThat(observed).as("the driver never ran").isNotNull();
    assertThat(foundScope)
        .as("driver could not find its scope, walk was:" + observed)
        .isTrue();
  }

  // --- the condition the distribution runs in -------------------------------

  /**
   * An inactive scope execution must report the same activity through
   * {@code getActivityId()} and {@code getActivity()}.
   *
   * <p>This is the suspicion the database forced. In a running distribution the
   * agent's upward walk reported the enclosing scope as {@code activityId=end}
   * with a {@code NoneEndEventActivityBehavior}, while the row for that very
   * execution held {@code ACT_ID_=adHoc} and {@code IS_SCOPE_=TRUE}. Both the
   * connector's walk and the engine's own {@code AdHocAgentState.findAdHocScope}
   * decide on {@code getActivity().getActivityBehavior()}, so if those two
   * disagree, both are wrong together — which is exactly what was observed.
   *
   * <p>Reproduced the way the distribution does it: the driver is
   * {@code asyncBefore}, so after the start command the scope executions are
   * inactive and the agent's turn happens in a later command that loads them
   * from the database rather than from the starting command's cache.
   */
  @Test
  public void anInactiveScopeExecutionReportsItsActivityConsistently() {
    ProcessEngine engine = engine("measure-inactive", false);
    ProcessInstance instance = start(engine, "measureInactive", true);

    List<Job> jobs = engine.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(jobs).as("the driver's job").hasSize(1);
    final String agentExecutionId = jobs.get(0).getExecutionId();

    // A separate command, so nothing is served from the start command's cache.
    String report = ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
        .getCommandExecutorTxRequired().execute(new Command<String>() {
          @Override
          public String execute(CommandContext commandContext) {
            StringBuilder mismatches = new StringBuilder();
            StringBuilder walk = new StringBuilder();
            ExecutionEntity probe = commandContext.getExecutionManager()
                .findExecutionById(agentExecutionId);
            int depth = 0;
            while (probe != null && depth < 10) {
              String fromRow = probe.getActivityId();
              PvmActivity resolved = probe.getActivity();
              String fromObject = (resolved == null) ? null : resolved.getId();
              walk.append(" [").append(depth).append("] row=").append(fromRow)
                  .append(" resolved=").append(fromObject)
                  .append(" isScope=").append(probe.isScope())
                  .append(" isActive=").append(probe.isActive());
              boolean same = (fromRow == null) ? fromObject == null : fromRow.equals(fromObject);
              if (!same) {
                mismatches.append(" [").append(depth).append("] row=").append(fromRow)
                          .append(" but getActivity() said ").append(fromObject);
              }
              probe = probe.getParent();
              depth++;
            }
            System.out.println("MEASUREMENT inactive scope walk:" + walk);
            return mismatches.toString();
          }
        });

    assertThat(report)
        .as("getActivityId() and getActivity() disagree, which is what breaks the"
            + " upward walk in a distribution")
        .isEmpty();
  }

  /**
   * And the walk itself must find the scope under those conditions — the same
   * assertion the connector makes, from a command that loaded the tree fresh.
   */
  @Test
  public void theScopeIsFoundFromACommandThatLoadedTheTreeFresh() {
    ProcessEngine engine = engine("measure-fresh", false);
    ProcessInstance instance = start(engine, "measureFresh", true);

    List<Job> jobs = engine.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).list();
    final String agentExecutionId = jobs.get(0).getExecutionId();

    String found = ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
        .getCommandExecutorTxRequired().execute(new Command<String>() {
          @Override
          public String execute(CommandContext commandContext) {
            ExecutionEntity agent = commandContext.getExecutionManager()
                .findExecutionById(agentExecutionId);
            ExecutionEntity scope = AdHocAgentState.findAdHocScope(agent);
            return (scope == null) ? null : scope.getActivityId();
          }
        });

    assertThat(found).as("findAdHocScope could not reach the scope").isEqualTo("adHoc");
  }
}
