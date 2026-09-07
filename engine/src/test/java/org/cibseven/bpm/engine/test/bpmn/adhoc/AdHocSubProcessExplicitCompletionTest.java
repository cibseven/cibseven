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
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.cibseven.bpm.engine.ParseException;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.runtime.Execution;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.util.PluggableProcessEngineTest;
import org.junit.Test;

/**
 * Parking an ad hoc sub process — {@code camunda:property explicitCompletionOnly}.
 *
 * <p>Without a completion condition an ad hoc scope ends as soon as nothing is active and something
 * was activated. A scope worked turn by turn has to survive a turn in which nothing is active, and
 * expressing that with a completion condition written so it can never hold is untrue in the model —
 * Cockpit then shows a completion condition that will never fire — and easy to forget.
 *
 * <p>The property is the third completion rule. What it must not disturb is the other two, so the
 * tests here pin both the new behaviour and the unchanged one.
 */
public class AdHocSubProcessExplicitCompletionTest extends PluggableProcessEngineTest {

  protected static final String PARKED =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessExplicitCompletionTest.parked.bpmn20.xml";

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

  protected boolean isRunning(String processInstanceId) {
    return runtimeService.createProcessInstanceQuery()
        .processInstanceId(processInstanceId).singleResult() != null;
  }

  // ─── the rule itself ──────────────────────────────────────────────────────────

  /**
   * The core of the property. Without it this instance would end here: nothing is active and one
   * child was activated, which is exactly the condition the count-based rule completes on.
   */
  @Deployment(resources = PARKED)
  @Test
  public void aParkedScopeSurvivesItsLastChildEnding() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocParked");
    String scope = scopeExecutionId(pi.getId());

    runtimeService.triggerAdHocActivities(scope, Collections.singletonList("taskA"));
    taskService.complete(task("taskA").getId());

    assertThat(isRunning(pi.getId())).as("the scope did not end with its last child").isTrue();
    assertThat(scopeExecutionId(pi.getId()))
        .as("and it is still addressable, so a further activation can reach it")
        .isEqualTo(scope);
  }

  /**
   * Repeated, because "survives once" and "survives every time" are different claims: a latch set on
   * the first idle period would pass the previous test and fail here.
   */
  @Deployment(resources = PARKED)
  @Test
  public void aParkedScopeSurvivesSeveralIdlePeriods() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocParked");
    String scope = scopeExecutionId(pi.getId());

    for (int round = 1; round <= 3; round++) {
      runtimeService.triggerAdHocActivities(scope, Collections.singletonList("taskA"));
      assertThat(task("taskA")).as("round " + round + " started").isNotNull();
      taskService.complete(task("taskA").getId());
      assertThat(isRunning(pi.getId())).as("still alive after round " + round).isTrue();
    }
  }

  /**
   * A synchronous child is the sharper case: it is activated and finished inside one call, so the
   * scope sees "nothing active" without any wait state in between.
   */
  @Deployment(resources = PARKED)
  @Test
  public void aParkedScopeSurvivesASynchronousChild() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocParked");

    runtimeService.triggerAdHocActivities(scopeExecutionId(pi.getId()),
        Collections.singletonList("sync"));

    assertThat(runtimeService.getVariable(pi.getId(), "ran")).as("the child really ran").isEqualTo(true);
    assertThat(isRunning(pi.getId())).as("and the scope is still parked").isTrue();
  }

  /** Nothing has been activated at all. The scope waits rather than ending on entry. */
  @Deployment(resources = PARKED)
  @Test
  public void aParkedScopeWaitsWithNothingActivated() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocParked");

    assertThat(isRunning(pi.getId())).isTrue();
    assertThat(taskService.createTaskQuery().count()).as("entry starts nothing").isZero();
  }

  // ─── the way out ──────────────────────────────────────────────────────────────

  /** With the property, this is the only way out. */
  @Deployment(resources = PARKED)
  @Test
  public void completeAdHocSubProcessEndsAParkedScope() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocParked");
    String scope = scopeExecutionId(pi.getId());

    runtimeService.triggerAdHocActivities(scope, Collections.singletonList("taskA"));
    taskService.complete(task("taskA").getId());
    runtimeService.completeAdHocSubProcess(scope);

    assertThat(isRunning(pi.getId())).as("the scope left and the process ran to its end").isFalse();
  }

  /**
   * A scope that never activated anything still ends on request. Worth its own test because the
   * count-based rule refuses to complete such a scope by design, and the way out must not inherit
   * that refusal.
   */
  @Deployment(resources = PARKED)
  @Test
  public void completeAdHocSubProcessEndsAScopeThatNeverActivatedAnything() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocParked");

    runtimeService.completeAdHocSubProcess(scopeExecutionId(pi.getId()));

    assertThat(isRunning(pi.getId())).isFalse();
  }

  /**
   * Running children are cancelled, whatever {@code cancelRemainingInstances} says — that attribute
   * governs what happens when a completion condition is satisfied, not what happens when a performer
   * says the scope is done. An agent has to be told this, because the child it cancels can be a task
   * a person is working on.
   */
  @Deployment(resources = PARKED)
  @Test
  public void completeAdHocSubProcessCancelsWhatIsStillRunning() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocParked");
    String scope = scopeExecutionId(pi.getId());

    runtimeService.triggerAdHocActivities(scope, Arrays.asList("taskA", "taskB"));
    assertThat(taskService.createTaskQuery().count()).isEqualTo(2);

    runtimeService.completeAdHocSubProcess(scope);

    assertThat(taskService.createTaskQuery().count()).as("both were cancelled").isZero();
    assertThat(isRunning(pi.getId())).isFalse();
  }

  /** Variables handed to the completion call reach the scope before it leaves. */
  @Deployment(resources = PARKED)
  @Test
  public void completeAdHocSubProcessAcceptsVariables() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocParked");

    runtimeService.completeAdHocSubProcess(scopeExecutionId(pi.getId()),
        Collections.<String, Object>singletonMap("stopReason", "done"));

    assertThat(historyService.createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("stopReason").singleResult().getValue())
        .isEqualTo("done");
  }

  // ─── the other two rules must be unchanged ────────────────────────────────────

  /**
   * {@code false} must mean exactly what an absent property means. Reading it as "set" would park
   * every scope that ever switched the property off instead of deleting it.
   */
  @Deployment(resources = "org/cibseven/bpm/engine/test/bpmn/adhoc/"
      + "AdHocSubProcessExplicitCompletionTest.notParked.bpmn20.xml")
  @Test
  public void explicitCompletionOnlyFalseBehavesLikeAnAbsentProperty() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocNotParked");

    runtimeService.triggerAdHocActivities(scopeExecutionId(pi.getId()),
        Collections.singletonList("taskA"));
    taskService.complete(task("taskA").getId());

    assertThat(isRunning(pi.getId()))
        .as("the count-based rule still ends the scope when nothing is active")
        .isFalse();
  }

  // ─── deployment refusals ──────────────────────────────────────────────────────

  /**
   * Two answers to the same question. Accepting both would mean ignoring one silently, and it would
   * be inconsistent at runtime: once a condition has held, further activation is refused, while this
   * property exists precisely so that activation stays possible for the life of the instance.
   */
  @Test
  public void theCombinationWithACompletionConditionIsRefused() {
    try {
      testRule.deploy("org/cibseven/bpm/engine/test/bpmn/adhoc/"
          + "AdHocSubProcessExplicitCompletionTest.parkedAndCondition.bpmn20.xml");
      fail("explicitCompletionOnly together with a completionCondition must be refused");
    } catch (ParseException e) {
      testRule.assertTextPresent("explicitCompletionOnly", e.getMessage());
      testRule.assertTextPresent("completionCondition", e.getMessage());
      testRule.assertTextPresent("cannot both be set", e.getMessage());
    }
  }

  /**
   * A typo is refused rather than read as false. Read as false it would deploy a scope that ends the
   * moment its last child does — the failure the property prevents — and it would only show up in
   * production.
   */
  @Test
  public void anUnparseableValueIsRefused() {
    try {
      testRule.deploy("org/cibseven/bpm/engine/test/bpmn/adhoc/"
          + "AdHocSubProcessExplicitCompletionTest.badValue.bpmn20.xml");
      fail("a value that is neither true nor false must be refused");
    } catch (ParseException e) {
      testRule.assertTextPresent("explicitCompletionOnly", e.getMessage());
      testRule.assertTextPresent("must be 'true' or 'false'", e.getMessage());
      testRule.assertTextPresent("ture", e.getMessage());
    }
  }

  // ─── composition with the other property in the same block ────────────────────

  /**
   * Parking and declarative entry activation are read from one {@code camunda:properties} block, and
   * the combination is the one an agentic scope uses. A parser that returned early after the first
   * property would pass every other test here and fail this one.
   */
  @Deployment(resources = "org/cibseven/bpm/engine/test/bpmn/adhoc/"
      + "AdHocSubProcessExplicitCompletionTest.parkedWithEntryActivation.bpmn20.xml")
  @Test
  public void parkingComposesWithEntryActivation() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocParkedEntry");

    assertThat(task("taskA")).as("entry activation still runs").isNotNull();

    taskService.complete(task("taskA").getId());
    assertThat(isRunning(pi.getId())).as("and the scope is parked afterwards").isTrue();

    List<String> ids = runtimeService.triggerAdHocActivities(scopeExecutionId(pi.getId()),
        Collections.singletonList("taskB"));
    assertThat(ids).as("further activation is possible").hasSize(1);
    assertThat(task("taskB")).isNotNull();
  }

  /**
   * The activation counter is unaffected by parking. It still counts every activation, which matters
   * because the count-based rule is what a scope falls back to if the property is ever removed from
   * the model while instances are running — a migration the validator refuses, but the counter's own
   * correctness should not depend on that refusal.
   */
  @Deployment(resources = PARKED)
  @Test
  public void parkingDoesNotDisturbTheActivationCounter() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocParked");
    String scope = scopeExecutionId(pi.getId());

    runtimeService.triggerAdHocActivities(scope, Collections.singletonList("taskA"));
    taskService.complete(task("taskA").getId());
    runtimeService.triggerAdHocActivities(scope, Collections.singletonList("taskB"));

    assertThat(runtimeService.createVariableInstanceQuery()
        .processInstanceIdIn(pi.getId())
        .variableName("nrOfActivatedInstances")
        .list())
        .as("one counter, held by the engine")
        .hasSize(1);
    assertThat(runtimeService.createVariableInstanceQuery()
        .processInstanceIdIn(pi.getId())
        .variableName("nrOfActivatedInstances")
        .singleResult().getValue())
        .as("two activations counted")
        .isEqualTo(2);
  }
}
