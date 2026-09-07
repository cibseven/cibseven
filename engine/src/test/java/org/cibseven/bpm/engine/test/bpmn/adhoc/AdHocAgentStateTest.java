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
package org.cibseven.bpm.engine.test.bpmn.adhoc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;

import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocAgentState;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.runtime.Execution;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.runtime.VariableInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.util.PluggableProcessEngineTest;
import org.junit.Test;

/**
 * {@link AdHocAgentState} — where a client that drives an ad hoc scope keeps its own state.
 *
 * <p>In an agentic scope the child activities <em>are</em> the agent's tools, so they are the
 * untrusted party. {@code setVariable} walks strictly up the parent chain, and the scope execution
 * is an ancestor of every child, so anything stored there is reachable and writable by all of them —
 * a tool that clears a pending list can make the agent believe its work is done. The execution this
 * class returns is a sibling of those children instead.
 *
 * <p>The second half of these tests is about the separation from the engine's own state execution,
 * which holds the activation counter. Sharing one would stop {@code getActivatedCount}'s
 * pre-relocation fallback from firing, and a scope without a completion condition would then never
 * complete again. That is the failure {@link #agentStateDoesNotDisturbTheActivationCounter} guards.
 */
public class AdHocAgentStateTest extends PluggableProcessEngineTest {

  protected static final String PENDING = "adHocAgentPending";

  /** Writes agent state the way the connector's loop state does. */
  public static class WriteAgentState implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
      ExecutionEntity scope = AdHocAgentState.findAdHocScope((ExecutionEntity) execution);
      if (scope == null) {
        throw new AssertionError("the delegate did not find an enclosing ad hoc scope");
      }
      AdHocAgentState.findOrCreate(scope).setVariableLocal(PENDING, "mine");
    }
  }

  protected Task task(String key) {
    return taskService.createTaskQuery().taskDefinitionKey(key).singleResult();
  }

  protected String scopeExecutionId(String processInstanceId) {
    for (Execution execution : runtimeService.createExecutionQuery()
        .processInstanceId(processInstanceId).list()) {
      if ("adHoc".equals(((ExecutionEntity) execution).getActivityId())) {
        return execution.getId();
      }
    }
    throw new AssertionError("no execution sitting on the ad hoc scope");
  }

  protected List<VariableInstance> variables(String processInstanceId, String name) {
    return runtimeService.createVariableInstanceQuery()
        .processInstanceIdIn(processInstanceId)
        .variableName(name)
        .list();
  }

  protected VariableInstance single(String processInstanceId, String name) {
    List<VariableInstance> found = variables(processInstanceId, name);
    assertThat(found).as("exactly one variable named " + name).hasSize(1);
    return found.get(0);
  }

  // ─── where the state lives ────────────────────────────────────────────────────

  /**
   * The state must sit on neither the process instance nor the scope execution. Those are the two
   * places a child reaches by walking up, and either would make the state writable by every tool.
   */
  @Deployment(resources = "org/cibseven/bpm/engine/test/bpmn/adhoc/"
      + "AdHocAgentStateTest.parked.bpmn20.xml")
  @Test
  public void agentStateLivesBesideTheChildrenRatherThanAboveThem() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocAgentState");
    String scope = scopeExecutionId(pi.getId());

    runtimeService.triggerAdHocActivities(scope, Collections.singletonList("writeState"));

    VariableInstance state = single(pi.getId(), PENDING);
    assertThat(state.getValue()).isEqualTo("mine");
    assertThat(state.getExecutionId())
        .as("not the process instance, which every child reaches by walking up")
        .isNotEqualTo(pi.getId());
    assertThat(state.getExecutionId())
        .as("not the scope execution either, which is every child's parent")
        .isNotEqualTo(scope);
  }

  /**
   * A scope nobody writes to carries no extra execution and no extra variable. The state execution
   * is created on first use precisely so that an ordinary ad hoc scope is unaffected.
   */
  @Deployment(resources = "org/cibseven/bpm/engine/test/bpmn/adhoc/"
      + "AdHocAgentStateTest.parked.bpmn20.xml")
  @Test
  public void noAgentStateExecutionWithoutAWrite() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocAgentState");

    runtimeService.triggerAdHocActivities(scopeExecutionId(pi.getId()),
        Collections.singletonList("workerA"));

    assertThat(variables(pi.getId(), AdHocAgentState.STATE_MARKER))
        .as("nothing wrote agent state, so there is no marker")
        .isEmpty();
    assertThat(variables(pi.getId(), PENDING)).isEmpty();
  }

  // ─── the tamper ───────────────────────────────────────────────────────────────

  /**
   * A tool writing the same name must not reach the agent's copy. Its write walks up, finds the name
   * on no ancestor — the state execution is a sibling, not an ancestor — and creates a second
   * variable at the process instance. Two of them existing is the proof that the first was not
   * overwritten.
   */
  @Deployment(resources = "org/cibseven/bpm/engine/test/bpmn/adhoc/"
      + "AdHocAgentStateTest.parked.bpmn20.xml")
  @Test
  public void aChildCannotOverwriteAgentState() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocAgentState");
    String scope = scopeExecutionId(pi.getId());

    runtimeService.triggerAdHocActivities(scope, Collections.singletonList("writeState"));
    runtimeService.triggerAdHocActivities(scope, Collections.singletonList("tamper"));

    List<VariableInstance> found = variables(pi.getId(), PENDING);
    assertThat(found).as("the tool's write landed beside the agent's copy, not on it").hasSize(2);

    boolean agentCopyIntact = false;
    for (VariableInstance variable : found) {
      if ("mine".equals(variable.getValue())) {
        agentCopyIntact = true;
        assertThat(variable.getExecutionId())
            .as("and the intact one is not the process instance")
            .isNotEqualTo(pi.getId());
      }
    }
    assertThat(agentCopyIntact).as("the agent's value survived the tamper").isTrue();
  }

  /** Reading it back through the same helper must still find the agent's value, not the tool's. */
  @Deployment(resources = "org/cibseven/bpm/engine/test/bpmn/adhoc/"
      + "AdHocAgentStateTest.parked.bpmn20.xml")
  @Test
  public void theHelperStillFindsTheAgentsValueAfterATamper() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocAgentState");
    String scope = scopeExecutionId(pi.getId());

    runtimeService.triggerAdHocActivities(scope, Collections.singletonList("writeState"));
    runtimeService.triggerAdHocActivities(scope, Collections.singletonList("tamper"));
    // Writing again is what a further turn does. It must land on the same execution as the first
    // write rather than creating a third variable.
    runtimeService.triggerAdHocActivities(scope, Collections.singletonList("writeState"));

    assertThat(variables(pi.getId(), PENDING))
        .as("still one copy for the agent and one for the tool")
        .hasSize(2);
  }

  // ─── separation from the engine's own state ───────────────────────────────────

  /**
   * The regression this separation exists to prevent. {@code getActivatedCount} falls back to the
   * pre-relocation location when it finds no state execution, which is how an instance started by an
   * older build keeps completing. If agent state and the counter shared one execution, the presence
   * of agent state would make that lookup succeed with no counter on it, read as zero, and a scope
   * on the count-based rule would never complete again.
   *
   * <p>The scope here is deliberately <em>not</em> parked, so completion is the observable outcome.
   */
  @Deployment(resources = "org/cibseven/bpm/engine/test/bpmn/adhoc/"
      + "AdHocAgentStateTest.notParked.bpmn20.xml")
  @Test
  public void agentStateDoesNotDisturbTheActivationCounter() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocAgentStateNotParked");

    // Synchronous: it writes agent state and ends inside this call, and the scope then decides
    // completion on the counter while agent state is already in place.
    runtimeService.triggerAdHocActivities(scopeExecutionId(pi.getId()),
        Collections.singletonList("writeState"));

    assertThat(runtimeService.createProcessInstanceQuery()
        .processInstanceId(pi.getId()).singleResult())
        .as("the counter was read correctly and the scope completed")
        .isNull();
  }

  /**
   * Both kinds of state coexist on separate executions. Asserted through the variables rather than
   * through the execution tree, because that is what a reader can check without engine internals.
   */
  @Deployment(resources = "org/cibseven/bpm/engine/test/bpmn/adhoc/"
      + "AdHocAgentStateTest.parked.bpmn20.xml")
  @Test
  public void theTwoKindsOfStateSitOnDifferentExecutions() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocAgentState");
    String scope = scopeExecutionId(pi.getId());

    runtimeService.triggerAdHocActivities(scope, Collections.singletonList("writeState"));

    VariableInstance counter = single(pi.getId(), "nrOfActivatedInstances");
    VariableInstance state = single(pi.getId(), PENDING);

    assertThat(counter.getExecutionId())
        .as("the engine's counter and the agent's state are held apart")
        .isNotEqualTo(state.getExecutionId());
    assertThat(counter.getValue()).as("and the counter is correct").isEqualTo(1);
  }

  /**
   * The marker is written on creation, and it is what makes the execution recognisable when the
   * payload's own name is not fixed — a conversation is keyed by a memory id, so recognition cannot
   * work by looking for the payload.
   */
  @Deployment(resources = "org/cibseven/bpm/engine/test/bpmn/adhoc/"
      + "AdHocAgentStateTest.parked.bpmn20.xml")
  @Test
  public void theStateExecutionCarriesItsMarker() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocAgentState");

    runtimeService.triggerAdHocActivities(scopeExecutionId(pi.getId()),
        Collections.singletonList("writeState"));

    VariableInstance marker = single(pi.getId(), AdHocAgentState.STATE_MARKER);
    assertThat(marker.getValue()).isEqualTo(true);
    assertThat(marker.getExecutionId())
        .as("on the same execution as the payload")
        .isEqualTo(single(pi.getId(), PENDING).getExecutionId());
  }

  /**
   * Completing the scope must dispose of the state execution with everything else. The four setters
   * that create it — activity cleared, inactive, not concurrent, event scope — exist so that the
   * engine's own iteration, completion checks and delete cascade neither trip over it nor leave it
   * behind.
   */
  @Deployment(resources = "org/cibseven/bpm/engine/test/bpmn/adhoc/"
      + "AdHocAgentStateTest.parked.bpmn20.xml")
  @Test
  public void completingTheScopeDisposesOfTheStateExecution() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocAgentState");
    String scope = scopeExecutionId(pi.getId());

    runtimeService.triggerAdHocActivities(scope, Collections.singletonList("writeState"));
    runtimeService.completeAdHocSubProcess(scope);

    assertThat(runtimeService.createProcessInstanceQuery()
        .processInstanceId(pi.getId()).singleResult())
        .as("the scope left cleanly, with no execution left holding it open")
        .isNull();
    assertThat(variables(pi.getId(), PENDING))
        .as("and the runtime variable is gone with it")
        .isEmpty();
  }

  /**
   * The state execution must not make the scope look busy. It is an event-scope execution precisely
   * because child iteration, completion checks and delete cascade all use the non-event-scope view;
   * were it visible there, a scope on the count-based rule would never find "nothing active".
   */
  @Deployment(resources = "org/cibseven/bpm/engine/test/bpmn/adhoc/"
      + "AdHocAgentStateTest.notParked.bpmn20.xml")
  @Test
  public void theStateExecutionDoesNotCountAsAnActiveChild() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocAgentStateNotParked");

    // Both in one call, so the writer's end finds the waiting child active and the scope does not
    // complete yet. That leaves the state execution as the only thing besides the waiting child.
    runtimeService.triggerAdHocActivities(scopeExecutionId(pi.getId()),
        java.util.Arrays.asList("writeState", "workerA"));
    assertThat(task("workerA")).as("the waiting child kept the scope open").isNotNull();
    assertThat(variables(pi.getId(), PENDING)).as("and agent state exists").hasSize(1);

    taskService.complete(task("workerA").getId());

    assertThat(runtimeService.createProcessInstanceQuery()
        .processInstanceId(pi.getId()).singleResult())
        .as("with the waiting child gone, the state execution did not make the scope look busy")
        .isNull();
  }
}
