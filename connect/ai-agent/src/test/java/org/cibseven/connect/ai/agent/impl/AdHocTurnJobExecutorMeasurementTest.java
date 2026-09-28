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
import java.util.Collections;
import java.util.List;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocAgentState;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.interceptor.Command;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.PvmActivity;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.connect.ScriptedAgent;
import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A measurement, not a feature test.
 *
 * <p>Running the ad-hoc agent suite against a real distribution once failed on every model:
 * by the time the agent ran, the walk to an execution carrying
 * {@code AdHocSubProcessActivityBehavior} found nothing, and the engine's own
 * {@code AdHocAgentState.findAdHocScope} returned null at the same moment. Two guesses were
 * measured and refuted there — the classloaders were identical, and the failure was not in
 * connector code, because the engine-side walk failed too.
 *
 * <p>What every other test of this ticket has in common is
 * {@code setJobExecutorActivate(false)}: the turn's job is either never run or run by hand
 * through {@code executeJob}. None of them lets a live job executor run a turn. This class
 * closes that gap by measuring the same situation two ways, so the difference between them
 * would be the answer:
 *
 * <ol>
 *   <li>{@link #measureATurnUnderALiveJobExecutor()} — the distribution's configuration.</li>
 *   <li>{@link #measureATurnWithTheJobRunByHand()} — what the other tests do.</li>
 * </ol>
 *
 * <p>Both assert the same thing, so a failure names which configuration breaks and prints
 * the tree that was actually observed.
 */
public class AdHocTurnJobExecutorMeasurementTest {

  /** What the turn saw when it ran. Written from the executing thread. */
  static volatile String observed;

  /** True when the turn could find the scope it belongs to. */
  static volatile Boolean foundScope;

  private final List<ProcessEngine> engines = new ArrayList<>();

  /**
   * Records the execution tree the turn can see, the way
   * {@code AdHocSubProcessTool.requireAdHocScope} walks it.
   */
  private static ScriptedAgent.Turn probe() {
    return parameters -> {
      ExecutionEntity self = Context.getExecutionContext().getExecution();
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
      walk.append("  ==> findAdHocScope=").append(scope == null ? "null" : scope.getId());
      observed = walk.toString();
      // Something has to be started, or the scope ends under the live executor before the
      // assertions can look at it.
      new AdHocSubProcessTool().startActivity("waits", Collections.<String, Object>emptyMap());
      return "measured";
    };
  }

  @BeforeEach
  public void reset() {
    observed = null;
    foundScope = null;
  }

  @AfterEach
  public void closeEngines() {
    ScriptedAgent.uninstall();
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
    configuration.setProcessEnginePlugins(
        Collections.<ProcessEnginePlugin>singletonList(new ConnectProcessEnginePlugin()));
    ProcessEngine built = configuration.buildProcessEngine();
    engines.add(built);
    // After the engine, not before: the plugin rebuilds the connector registry as it starts.
    ScriptedAgent.install(probe());
    return built;
  }

  private static String model(String processId) {
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + " xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + " targetNamespace='http://cibseven.org/measure'>"
        + "<process id='" + processId + "' isExecutable='true'>"
        + "  <startEvent id='start' />"
        + "  <sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + "  <adHocSubProcess id='adHoc'>"
        + "    <extensionElements><camunda:properties>"
        + "      <camunda:property name='cibseven.agentic.enabled' value='true' />"
        + "      <camunda:property name='cibseven.agentic.message' value='Measure.' />"
        + "    </camunda:properties></extensionElements>"
        + "    <userTask id='waits' name='Waits' />"
        + "  </adHocSubProcess>"
        + "  <sequenceFlow id='f2' sourceRef='adHoc' targetRef='end' />"
        + "  <endEvent id='end' />"
        + "</process></definitions>";
  }

  private ProcessInstance start(ProcessEngine engine, String processId) {
    engine.getRepositoryService().createDeployment()
        .addString(processId + ".bpmn20.xml", model(processId))
        .deploy();
    ProcessStarterToolContext.setEngine(engine);
    return engine.getRuntimeService().startProcessInstanceByKey(processId);
  }

  /** Waits until the turn has run, or gives up. */
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

  /** The distribution's configuration: a live job executor picks the turn up. */
  @Test
  public void measureATurnUnderALiveJobExecutor() {
    ProcessEngine engine = engine("measure-live", true);

    start(engine, "measureLive");
    awaitProbe(20000);

    report("turn under a LIVE job executor");
    assertThat(observed).as("the turn never ran").isNotNull();
    assertThat(foundScope)
        .as("the turn could not find its scope, walk was:" + observed)
        .isTrue();
  }

  /** What the other tests do: the same job, executed by hand. */
  @Test
  public void measureATurnWithTheJobRunByHand() {
    ProcessEngine engine = engine("measure-manual", false);

    ProcessInstance instance = start(engine, "measureManual");
    List<Job> jobs = engine.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(jobs).as("the turn's job").hasSize(1);
    engine.getManagementService().executeJob(jobs.get(0).getId());

    report("turn, job run by hand");
    assertThat(observed).as("the turn never ran").isNotNull();
    assertThat(foundScope)
        .as("the turn could not find its scope, walk was:" + observed)
        .isTrue();
  }

  // --- the condition the distribution runs in -------------------------------

  /**
   * An inactive scope execution must report the same activity through
   * {@code getActivityId()} and {@code getActivity()}.
   *
   * <p>This is the suspicion the database forced. In a running distribution the agent's
   * upward walk reported the enclosing scope as {@code activityId=end} with a
   * {@code NoneEndEventActivityBehavior}, while the row for that very execution held
   * {@code ACT_ID_=adHoc} and {@code IS_SCOPE_=TRUE}. Both the connector's walk and the
   * engine's own {@code AdHocAgentState.findAdHocScope} decide on
   * {@code getActivity().getActivityBehavior()}, so if those two disagree, both are wrong
   * together — which is exactly what was observed.
   *
   * <p>Reproduced the way the distribution does it: a turn is a job, so after the start
   * command the scope executions are inactive and the turn happens in a later command that
   * loads them from the database rather than from the starting command's cache.
   */
  @Test
  public void anInactiveScopeExecutionReportsItsActivityConsistently() {
    ProcessEngine engine = engine("measure-inactive", false);
    ProcessInstance instance = start(engine, "measureInactive");

    List<Job> jobs = engine.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(jobs).as("the turn's job").hasSize(1);
    final String turnExecutionId = jobs.get(0).getExecutionId();

    // A separate command, so nothing is served from the start command's cache.
    String report = ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
        .getCommandExecutorTxRequired().execute(new Command<String>() {
          @Override
          public String execute(CommandContext commandContext) {
            StringBuilder mismatches = new StringBuilder();
            StringBuilder walk = new StringBuilder();
            ExecutionEntity probe = commandContext.getExecutionManager()
                .findExecutionById(turnExecutionId);
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
   * And the walk itself must find the scope under those conditions — the same assertion the
   * connector makes, from a command that loaded the tree fresh.
   */
  @Test
  public void theScopeIsFoundFromACommandThatLoadedTheTreeFresh() {
    ProcessEngine engine = engine("measure-fresh", false);
    ProcessInstance instance = start(engine, "measureFresh");

    List<Job> jobs = engine.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).list();
    final String turnExecutionId = jobs.get(0).getExecutionId();

    String found = ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
        .getCommandExecutorTxRequired().execute(new Command<String>() {
          @Override
          public String execute(CommandContext commandContext) {
            ExecutionEntity turn = commandContext.getExecutionManager()
                .findExecutionById(turnExecutionId);
            ExecutionEntity scope = AdHocAgentState.findAdHocScope(turn);
            return (scope == null) ? null : scope.getActivityId();
          }
        });

    assertThat(found).as("findAdHocScope could not reach the scope").isEqualTo("adHoc");
  }
}
