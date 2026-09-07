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
import static org.assertj.core.api.Assertions.entry;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import org.cibseven.bpm.engine.runtime.ActivityInstance;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.runtime.VariableInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.junit.Rule;
import org.junit.Test;

/**
 * {@link AdHocLoopState} — the agent's own bookkeeping for one ad hoc scope.
 *
 * <p>Engine-backed rather than mocked, because every claim this class makes is a
 * claim about engine mechanics: that the state is not a process variable, and
 * that a child of the scope cannot reach it. A mocked {@code ExecutionEntity}
 * would let both pass while being false.
 *
 * <p>The state is read and written inside a command, via {@link #inScope}, because
 * that is the only place an {@code ExecutionEntity} can resolve its activity — the
 * activity comes from the deployment cache, which hangs off the command context.
 * It is also where the connector's tool runs, so the test exercises the same
 * conditions rather than a friendlier set.
 *
 * <p>No driver activity is configured here. The driver is covered by the engine's
 * own tests, and leaving it out keeps a re-activation from adding executions in
 * the middle of an assertion about bookkeeping.
 */
public class AdHocLoopStateTest {

  /**
   * Built once for the whole class. JUnit 4 instantiates the test class per test
   * method, so a per-instance engine would try to create the schema again on the
   * same in-memory database and fail on the second test.
   */
  private static final ProcessEngine ENGINE = buildInMemoryEngine();

  private static boolean deployed;

  @Rule
  public ProcessEngineRule engineRule = new ProcessEngineRule(ENGINE);

  private static ProcessEngine buildInMemoryEngine() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setJdbcUrl("jdbc:h2:mem:adhoc-loop-state-test;DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    // Deployment is refused without one, and the model below carries none because
    // the loop state has nothing to do with history cleanup.
    configuration.setHistoryTimeToLive("P30D");
    configuration.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
    return configuration.buildProcessEngine();
  }

  /**
   * Writes the loop state's own variable names the way a child activity of the
   * scope would: through the ordinary delegate API, which walks up the parent
   * chain. This is the attempt the design has to defeat.
   */
  public static class ChildWritesPending implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
      execution.setVariable("adHocAgentPending", Collections.singletonMap("forged", "forged"));
      execution.setVariable("adHocAgentTurns", Integer.valueOf(99));
    }
  }

  // --- helpers ---------------------------------------------------------------

  /** Work to do against the scope execution, inside a command. */
  private interface ScopeWork<T> {
    T run(ExecutionEntity scope);
  }

  /**
   * Runs {@code work} against the ad hoc scope's execution inside a command, and
   * returns its result. Anything written is flushed when the command ends, so a
   * later call reads it back from the database rather than from the heap.
   */
  private <T> T inScope(final String processInstanceId, final ScopeWork<T> work) {
    return ((ProcessEngineConfigurationImpl) ENGINE.getProcessEngineConfiguration())
        .getCommandExecutorTxRequired().execute(new Command<T>() {
          @Override
          public T execute(CommandContext commandContext) {
            return work.run(scopeOf(commandContext, processInstanceId));
          }
        });
  }

  /** The scope's execution, found the way the connector finds it. */
  private static ExecutionEntity scopeOf(CommandContext commandContext, String processInstanceId) {
    List<ExecutionEntity> executions = commandContext.getExecutionManager()
        .findExecutionsByProcessInstanceId(processInstanceId);
    for (ExecutionEntity execution : executions) {
      ExecutionEntity scope = AdHocAgentState.findAdHocScope(execution);
      if (scope != null) {
        return scope;
      }
    }
    throw new AssertionError("no ad hoc scope execution in " + processInstanceId);
  }

  private static final String CHILD_CLASS = ChildWritesPending.class.getName();

  /** A parked scope with a waiting child and a child that forges the loop state. */
  private static String scopeProcess() {
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + " xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + " targetNamespace='http://cibseven.org/adhoc-loop-state'>"
        + "<process id='loopState' isExecutable='true'>"
        + "  <startEvent id='start' />"
        + "  <sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + "  <adHocSubProcess id='adHoc'>"
        + "    <extensionElements><camunda:properties>"
        + "      <camunda:property name='explicitCompletionOnly' value='true' />"
        + "    </camunda:properties></extensionElements>"
        + "    <userTask id='waits' name='Waits' />"
        + "    <serviceTask id='child' name='Child' camunda:class='" + CHILD_CLASS + "' />"
        + "  </adHocSubProcess>"
        + "  <sequenceFlow id='f2' sourceRef='adHoc' targetRef='end' />"
        + "  <endEvent id='end' />"
        + "</process></definitions>";
  }

  private ProcessInstance start() {
    if (!deployed) {
      ENGINE.getRepositoryService().createDeployment()
          .addString("loopstate.bpmn20.xml", scopeProcess())
          .deploy();
      deployed = true;
    }
    return ENGINE.getRuntimeService().startProcessInstanceByKey("loopState");
  }

  /** Starts one child through the activation API and returns its activity instance id. */
  private String startChild(ProcessInstance instance, String activityId) {
    String scopeExecutionId = inScope(instance.getId(), new ScopeWork<String>() {
      @Override
      public String run(ExecutionEntity scope) {
        return scope.getId();
      }
    });
    List<String> ids = ENGINE.getRuntimeService().triggerAdHocActivities(
        scopeExecutionId, Collections.singletonList(activityId), null);
    assertThat(ids).hasSize(1);
    return ids.get(0);
  }

  private Set<String> runningIds(ProcessInstance instance) {
    Set<String> ids = new HashSet<>();
    collect(ENGINE.getRuntimeService().getActivityInstance(instance.getId()), ids);
    return ids;
  }

  private static void collect(ActivityInstance node, Set<String> ids) {
    if (node == null) {
      return;
    }
    ids.add(node.getId());
    for (ActivityInstance child : node.getChildActivityInstances()) {
      collect(child, ids);
    }
  }

  private Map<String, String> pending(ProcessInstance instance) {
    return inScope(instance.getId(), new ScopeWork<Map<String, String>>() {
      @Override
      public Map<String, String> run(ExecutionEntity scope) {
        return AdHocLoopState.pending(scope);
      }
    });
  }

  private int turns(ProcessInstance instance) {
    return inScope(instance.getId(), new ScopeWork<Integer>() {
      @Override
      public Integer run(ExecutionEntity scope) {
        return Integer.valueOf(AdHocLoopState.turns(scope));
      }
    }).intValue();
  }

  private void addPending(ProcessInstance instance, final String activityInstanceId,
      final String activityId) {
    inScope(instance.getId(), new ScopeWork<Void>() {
      @Override
      public Void run(ExecutionEntity scope) {
        AdHocLoopState.addPending(scope, activityInstanceId, activityId);
        return null;
      }
    });
  }

  private void countTurn(ProcessInstance instance) {
    inScope(instance.getId(), new ScopeWork<Void>() {
      @Override
      public Void run(ExecutionEntity scope) {
        AdHocLoopState.countTurn(scope);
        return null;
      }
    });
  }

  private Map<String, String> harvest(ProcessInstance instance, final Set<String> stillRunning) {
    return inScope(instance.getId(), new ScopeWork<Map<String, String>>() {
      @Override
      public Map<String, String> run(ExecutionEntity scope) {
        return AdHocLoopState.harvestFinished(scope, stillRunning);
      }
    });
  }

  // --- the pending list ------------------------------------------------------

  @Test
  public void pendingIsEmptyBeforeAnythingIsRecorded() {
    ProcessInstance instance = start();

    assertThat(pending(instance)).isEmpty();
  }

  @Test
  public void aStartedActivityIsRecorded() {
    ProcessInstance instance = start();
    String activityInstanceId = startChild(instance, "waits");

    addPending(instance, activityInstanceId, "waits");

    assertThat(pending(instance)).containsExactly(entry(activityInstanceId, "waits"));
  }

  /**
   * The activation API returns null for an activity it did not start, which
   * happens when an earlier one in the same call ran synchronously and ended the
   * scope. There is then nothing to wait for, and a null key would block
   * completeScope for the rest of the instance's life.
   */
  @Test
  public void aNullActivityInstanceIdIsIgnored() {
    ProcessInstance instance = start();

    addPending(instance, null, "waits");

    assertThat(pending(instance)).isEmpty();
  }

  @Test
  public void recordingTheSameActivityInstanceTwiceKeepsOneEntry() {
    ProcessInstance instance = start();
    String activityInstanceId = startChild(instance, "waits");

    addPending(instance, activityInstanceId, "waits");
    addPending(instance, activityInstanceId, "waits");

    assertThat(pending(instance)).hasSize(1);
  }

  /** Written in one command, read back in the next, so it came from the database. */
  @Test
  public void theStateSurvivesTheCommandThatWroteIt() {
    ProcessInstance instance = start();
    String activityInstanceId = startChild(instance, "waits");

    addPending(instance, activityInstanceId, "waits");
    countTurn(instance);

    assertThat(pending(instance)).containsKey(activityInstanceId);
    assertThat(turns(instance)).isEqualTo(1);
  }

  // --- reconciliation --------------------------------------------------------

  @Test
  public void harvestKeepsWhatIsStillRunning() {
    ProcessInstance instance = start();
    String activityInstanceId = startChild(instance, "waits");
    addPending(instance, activityInstanceId, "waits");

    Map<String, String> finished = harvest(instance, runningIds(instance));

    assertThat(finished).isEmpty();
    assertThat(pending(instance)).containsKey(activityInstanceId);
  }

  @Test
  public void harvestRemovesAndReturnsWhatIsNoLongerRunning() {
    ProcessInstance instance = start();
    String activityInstanceId = startChild(instance, "waits");
    addPending(instance, activityInstanceId, "waits");

    Task task = ENGINE.getTaskService().createTaskQuery()
        .processInstanceId(instance.getId()).taskDefinitionKey("waits").singleResult();
    ENGINE.getTaskService().complete(task.getId());

    Map<String, String> finished = harvest(instance, runningIds(instance));

    assertThat(finished).containsExactly(entry(activityInstanceId, "waits"));
    assertThat(pending(instance)).isEmpty();
  }

  @Test
  public void harvestOnAnEmptyPendingListReturnsEmpty() {
    ProcessInstance instance = start();

    assertThat(harvest(instance, runningIds(instance))).isEmpty();
  }

  /**
   * One of two started activities finishing leaves the other pending. This is the
   * case a naive "clear the list once something ended" would get wrong, and it is
   * what stops the agent ending a scope over a task a person still holds.
   */
  @Test
  public void harvestSeparatesTheFinishedFromTheRunning() {
    ProcessInstance instance = start();
    String first = startChild(instance, "waits");
    String second = startChild(instance, "waits");
    addPending(instance, first, "waits");
    addPending(instance, second, "waits");

    List<Task> tasks = ENGINE.getTaskService().createTaskQuery()
        .processInstanceId(instance.getId()).taskDefinitionKey("waits").list();
    assertThat(tasks).hasSize(2);
    ENGINE.getTaskService().complete(tasks.get(0).getId());

    Map<String, String> finished = harvest(instance, runningIds(instance));

    assertThat(finished).hasSize(1);
    assertThat(pending(instance)).hasSize(1);
    assertThat(finished.keySet()).doesNotContainAnyElementsOf(pending(instance).keySet());
  }

  // --- the turn counter ------------------------------------------------------

  @Test
  public void turnsStartAtZero() {
    ProcessInstance instance = start();

    assertThat(turns(instance)).isZero();
  }

  @Test
  public void countingATurnRaisesTheCount() {
    ProcessInstance instance = start();

    countTurn(instance);
    countTurn(instance);

    assertThat(turns(instance)).isEqualTo(2);
  }

  // --- the two claims that carry the design ---------------------------------

  /**
   * The state is not reachable from the process instance, so it is absent from an
   * output mapping on the scope, from an expression evaluated anywhere in the
   * instance, and from {@code RuntimeService.getVariable}.
   *
   * <p>That is a narrower claim than "not a process variable", and the narrower
   * one is the true one — see {@link #theStateIsVisibleToAnAdministrativeQuery}.
   * It holds because variable resolution walks strictly <em>up</em> the parent
   * chain and the agent state execution is a sibling of the scope's children, not
   * an ancestor of anything.
   */
  @Test
  public void theStateIsNotReachableFromTheProcessInstance() {
    ProcessInstance instance = start();
    addPending(instance, startChild(instance, "waits"), "waits");
    countTurn(instance);

    assertThat(ENGINE.getRuntimeService().getVariable(instance.getId(), "adHocAgentPending"))
        .isNull();
    assertThat(ENGINE.getRuntimeService().getVariable(instance.getId(), "adHocAgentTurns"))
        .isNull();
    assertThat(ENGINE.getRuntimeService().getVariables(instance.getId()))
        .doesNotContainKeys("adHocAgentPending", "adHocAgentTurns", "adHocAgentState");
  }

  /**
   * The state <em>is</em> an ordinary variable instance, on its own execution, and
   * an administrative query finds it. Recorded deliberately rather than asserted
   * away: the design protects the state from the scope's children, not from an
   * operator, and someone reading Cockpit's variable tab will see these three
   * names.
   *
   * <p>The second half is the separation from the engine's own state execution,
   * which carries the activation counter. Sharing one execution would stop
   * {@code getActivatedCount}'s pre-relocation fallback from firing, and a scope
   * without a completion condition would then never complete again.
   */
  @Test
  public void theStateIsVisibleToAnAdministrativeQuery() {
    ProcessInstance instance = start();
    addPending(instance, startChild(instance, "waits"), "waits");
    countTurn(instance);

    List<VariableInstance> visible = ENGINE.getRuntimeService()
        .createVariableInstanceQuery().processInstanceIdIn(instance.getId()).list();

    Set<String> agentStateExecutions = new HashSet<>();
    Set<String> counterExecutions = new HashSet<>();
    Set<String> names = new HashSet<>();
    for (VariableInstance variable : visible) {
      names.add(variable.getName());
      if (variable.getName().startsWith("adHocAgent")) {
        agentStateExecutions.add(variable.getExecutionId());
      } else if ("nrOfActivatedInstances".equals(variable.getName())) {
        counterExecutions.add(variable.getExecutionId());
      }
    }

    assertThat(names).contains("adHocAgentState", "adHocAgentPending", "adHocAgentTurns");
    // All three on one execution, and that execution is neither the process
    // instance nor the scope.
    assertThat(agentStateExecutions).hasSize(1);
    assertThat(agentStateExecutions).doesNotContain(instance.getId());
    assertThat(agentStateExecutions).doesNotContain(scopeExecutionId(instance));
    // And not the execution the engine keeps its activation counter on.
    assertThat(counterExecutions).hasSize(1);
    assertThat(agentStateExecutions).doesNotContainAnyElementsOf(counterExecutions);
  }

  private String scopeExecutionId(ProcessInstance instance) {
    return inScope(instance.getId(), new ScopeWork<String>() {
      @Override
      public String run(ExecutionEntity scope) {
        return scope.getId();
      }
    });
  }

  /**
   * A child of the scope writing the loop state's own variable names does not
   * change what the agent reads.
   *
   * <p>This is the whole reason the state sits on a sibling execution rather than
   * on the scope: {@code setVariable} walks up the parent chain, and the scope is
   * an ancestor of every child, so a tool that can clear the pending list can
   * make the agent believe its work is done.
   */
  @Test
  public void aChildOfTheScopeCannotOverwriteTheLoopState() {
    ProcessInstance instance = start();
    String activityInstanceId = startChild(instance, "waits");
    addPending(instance, activityInstanceId, "waits");
    countTurn(instance);

    // Runs synchronously and writes both names on its way through.
    startChildSynchronously(instance, "child");

    assertThat(pending(instance)).containsOnlyKeys(activityInstanceId);
    assertThat(pending(instance)).doesNotContainKey("forged");
    assertThat(turns(instance)).isEqualTo(1);
  }

  /** The forging child ends inside the activation call, so no id comes back. */
  private void startChildSynchronously(ProcessInstance instance, String activityId) {
    String scopeExecutionId = inScope(instance.getId(), new ScopeWork<String>() {
      @Override
      public String run(ExecutionEntity scope) {
        return scope.getId();
      }
    });
    ENGINE.getRuntimeService().triggerAdHocActivities(
        scopeExecutionId, Collections.singletonList(activityId), null);
  }
}
