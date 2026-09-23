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
import static org.assertj.core.api.Assertions.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.ParseException;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.repository.Deployment;
import org.cibseven.bpm.engine.runtime.ActivityInstance;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.history.HistoricVariableInstance;
import org.cibseven.bpm.engine.runtime.VariableInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.util.PluggableProcessEngineTest;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the ad hoc sub process runtime against the scenarios the feature is specified to support.
 *
 * <p>Written as an acceptance probe, not as the feature's own suite: every assertion states what the
 * PRD / spine requires, so a failure names a gap rather than a regression.
 *
 * <p>The rule for a construct the engine does not implement: do not weaken an assertion to make a
 * test pass, and do not delete one as unsupported -- either documents the defect instead of catching
 * it. Where the construct is declined rather than merely unimplemented, the behaviour test is
 * replaced by a test of the refusal, and the sub-task that would lift the refusal is named in its
 * javadoc so the original assertions can be restored with it.
 */
public class AdHocSubProcessScenarioTest extends PluggableProcessEngineTest {

  protected static final String INVALID = "org/cibseven/bpm/engine/test/bpmn/adhoc/invalid/";

  protected List<String> deploymentsToClean = new ArrayList<String>();

  @AfterEach
  public void cleanUp() {
    for (String id : deploymentsToClean) {
      repositoryService.deleteDeployment(id, true);
    }
    deploymentsToClean.clear();
  }

  protected void activate(String processInstanceId, String... activityIds) {
    org.cibseven.bpm.engine.runtime.ProcessInstanceModificationBuilder builder =
        runtimeService.createProcessInstanceModification(processInstanceId);
    for (String activityId : activityIds) {
      builder.startBeforeActivity(activityId);
    }
    builder.execute();
  }

  protected List<String> startableOf(ProcessInstance pi) {
    org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl scope =
        ((org.cibseven.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity) repositoryService
            .getProcessDefinition(pi.getProcessDefinitionId())).findActivity("adHoc");
    return scope.getProperties().get(
        org.cibseven.bpm.engine.impl.bpmn.helper.BpmnProperties.AD_HOC_STARTABLE_ACTIVITIES);
  }

  protected Task task(String taskDefinitionKey) {
    return taskService.createTaskQuery().taskDefinitionKey(taskDefinitionKey).singleResult();
  }

  protected long activeInstances(String processInstanceId) {
    return runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).count();
  }

  protected String deploy(String resource) {
    Deployment deployment = repositoryService.createDeployment()
        .addClasspathResource(INVALID + resource)
        .deploy();
    deploymentsToClean.add(deployment.getId());
    return deployment.getId();
  }

  // ---------------------------------------------------------------- FR-11, FR-16a

  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testEnterWaitActivateComplete() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocBaseline");

    // The scope is entered and waits with no children.
    assertThat(taskService.createTaskQuery().count()).isZero();
    ActivityInstance tree = runtimeService.getActivityInstance(pi.getId());
    assertThat(tree.getChildActivityInstances()).hasSize(1);
    assertThat(tree.getChildActivityInstances()[0].getActivityId()).isEqualTo("adHoc");

    activate(pi.getId(), "taskA");
    assertThat(taskService.createTaskQuery().count()).isEqualTo(1);

    taskService.complete(task("taskA").getId());

    // Nothing active, one activation happened -> the scope leaves and the flow continues.
    assertThat(task("afterAdHoc")).isNotNull();
    taskService.complete(task("afterAdHoc").getId());
    testRule.assertProcessEnded(pi.getId());
  }

  // ---------------------------------------------------------------- FR-12

  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testTwoConcurrentChildren() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocConcurrent");

    activate(pi.getId(), "taskA", "taskB");
    assertThat(taskService.createTaskQuery().count())
        .as("two children activated in one call must both be active")
        .isEqualTo(2);

    ActivityInstance tree = runtimeService.getActivityInstance(pi.getId());
    ActivityInstance scope = tree.getChildActivityInstances()[0];
    assertThat(scope.getChildActivityInstances())
        .as("both children must appear in the activity instance tree")
        .hasSize(2);

    taskService.complete(task("taskA").getId());
    assertThat(activeInstances(pi.getId()))
        .as("the scope must keep waiting while taskB is still active")
        .isEqualTo(1);
    assertThat(taskService.createTaskQuery().count()).isEqualTo(1);

    taskService.complete(task("taskB").getId());
    testRule.assertProcessEnded(pi.getId());
  }

  @org.cibseven.bpm.engine.test.Deployment(resources =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessScenarioTest.testTwoConcurrentChildren.bpmn20.xml")
  @Test
  public void testSeparateActivationCalls() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocConcurrent");

    activate(pi.getId(), "taskA");
    activate(pi.getId(), "taskB");
    assertThat(taskService.createTaskQuery().count())
        .as("two children activated in two separate commands must both be active")
        .isEqualTo(2);

    taskService.complete(task("taskA").getId());
    taskService.complete(task("taskB").getId());
    testRule.assertProcessEnded(pi.getId());
  }

  @org.cibseven.bpm.engine.test.Deployment(resources =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessScenarioTest.testTwoConcurrentChildren.bpmn20.xml")
  @Test
  public void testSameChildActivatedTwice() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocConcurrent");

    activate(pi.getId(), "taskA");
    activate(pi.getId(), "taskA");
    assertThat(taskService.createTaskQuery().taskDefinitionKey("taskA").count())
        .as("activating the same child twice must yield two instances of it")
        .isEqualTo(2);

    for (Task t : taskService.createTaskQuery().list()) {
      taskService.complete(t.getId());
    }
    testRule.assertProcessEnded(pi.getId());
  }

  // ---------------------------------------------------------------- FR-14, FR-16, FR-16b

  // Asserts on a historic activity instance for the cancelled child.
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_ACTIVITY)
  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testCompletionConditionCancelsRemaining() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocCancelTrue");

    activate(pi.getId(), "taskA", "taskB");
    assertThat(taskService.createTaskQuery().count()).isEqualTo(2);

    Map<String, Object> vars = new HashMap<String, Object>();
    vars.put("enough", true);
    taskService.complete(task("taskA").getId(), vars);

    // cancelRemainingInstances = true -> taskB is cancelled and the scope leaves immediately.
    assertThat(taskService.createTaskQuery().count())
        .as("remaining children must be cancelled")
        .isZero();
    testRule.assertProcessEnded(pi.getId());

    // Gone from the runtime is not the whole requirement. A cancelled child must still appear in
    // history, or an audit of what the instance did loses the activity entirely; asserting only the
    // runtime count would pass for an implementation that deleted it without a trace.
    assertThat(historyService.createHistoricActivityInstanceQuery()
        .processInstanceId(pi.getId()).activityId("taskB").count())
        .as("a cancelled child must still be recorded in history")
        .isEqualTo(1);
  }

  // ---------------------------------------------------------------- FR-15

  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testCompletionConditionWaitsForRemaining() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocCancelFalse");

    activate(pi.getId(), "taskA", "taskB");
    assertThat(taskService.createTaskQuery().count()).isEqualTo(2);

    Map<String, Object> vars = new HashMap<String, Object>();
    vars.put("enough", true);
    taskService.complete(task("taskA").getId(), vars);

    // cancelRemainingInstances = false -> taskB keeps running, the scope waits for it.
    assertThat(task("taskB"))
        .as("cancelRemainingInstances=false must leave the straggler running")
        .isNotNull();
    assertThat(activeInstances(pi.getId())).isEqualTo(1);

    taskService.complete(task("taskB").getId());
    testRule.assertProcessEnded(pi.getId());
  }

  /**
   * CIB7-1961. The same attribute, with children that are scopes.
   *
   * <p>A child is a scope when it is an embedded sub process or when it carries an io mapping, and
   * such a child is inactive with a null activity while its own scope runs. Testing only
   * {@code isActive()} misses it, so the ad hoc scope used to leave while that subtree was still
   * alive and deleting its execution then violated ACT_FK_EXE_PARENT -- taking down the very
   * operation that satisfied the condition and leaving the instance unfinishable.
   */
  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testCancelFalseWithScopeChildren() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocCancelFalseScopeChildren");

    activate(pi.getId(), "taskA", "taskB", "taskC");
    assertThat(taskService.createTaskQuery().count()).isEqualTo(3);

    Map<String, Object> vars = new HashMap<String, Object>();
    vars.put("done", true);
    taskService.complete(task("taskA").getId(), vars);

    // The completion that satisfies the condition must itself succeed: this is the call that used
    // to come back as a persistence error and roll back.
    assertThat(task("taskA"))
        .as("the task whose completion satisfied the condition must be completed, not rolled back")
        .isNull();

    // cancelRemainingInstances="false" -> both survivors keep running and the scope waits.
    assertThat(task("taskB"))
        .as("cancelRemainingInstances=false must leave a scope-child straggler running")
        .isNotNull();
    assertThat(task("taskC")).isNotNull();
    assertThat(activeInstances(pi.getId())).isEqualTo(1);

    taskService.complete(task("taskB").getId());
    assertThat(activeInstances(pi.getId()))
        .as("one straggler left, so the scope must still be waiting")
        .isEqualTo(1);

    taskService.complete(task("taskC").getId());
    testRule.assertProcessEnded(pi.getId());
  }

  // ---------------------------------------------------------------- re-activation after a lull

  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testConditionFalseKeepsWaiting() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocNeverDone");

    activate(pi.getId(), "taskA");
    taskService.complete(task("taskA").getId());

    // Condition is false, so the scope must still be waiting with nothing active.
    assertThat(activeInstances(pi.getId()))
        .as("an unsatisfied completion condition must keep the scope alive")
        .isEqualTo(1);

    // ... and it must still be possible to activate another child.
    activate(pi.getId(), "taskB");
    assertThat(task("taskB"))
        .as("the scope must remain targetable for further activation")
        .isNotNull();

    Map<String, Object> vars = new HashMap<String, Object>();
    vars.put("enough", true);
    taskService.complete(task("taskB").getId(), vars);
    testRule.assertProcessEnded(pi.getId());
  }

  /**
   * A completion condition that names a variable no child has set yet is evaluated on the FIRST
   * child completion. JUEL cannot resolve the identifier, the exception escapes from inside a PVM
   * atomic operation, and the whole {@code complete task} command rolls back — the child cannot be
   * completed at all until the variable exists.
   */
  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testNaiveConditionBlocksChildCompletion() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocNaiveCondition");
    activate(pi.getId(), "taskA");

    String taskId = task("taskA").getId();
    try {
      taskService.complete(taskId);
      fail("expected the unresolvable completion condition to fail the command");
    } catch (org.cibseven.bpm.engine.ProcessEngineException e) {
      // The expression has to appear in the message: it is the only thing connecting the failure
      // back to the completion condition, since this fires on completing a task and not on deploy.
      testRule.assertTextPresent("Unknown property used in expression: ${enough}", e.getMessage());
    }

    // The command rolled back: the task is still there and cannot be completed.
    assertThat(task("taskA")).as("the child completion was rolled back").isNotNull();
    assertThat(activeInstances(pi.getId())).isEqualTo(1);
  }

  /**
   * The other half of {@link #testNaiveConditionBlocksChildCompletion}: the unguarded condition is
   * not a permanent deadlock. {@code complete(taskId, variables)} stores the supplied variables
   * before signalling the task, so a caller that passes the variable the condition names resolves it
   * and the completion succeeds.
   *
   * <p>Which is why the guidance is the defensive {@code hasVariable} form rather than "always pass
   * the variable": passing it works only while <em>every</em> completion path remembers to. A human
   * completing the task in Tasklist with no such form field, or a REST call with no variables, brings
   * the failure straight back.
   */
  @org.cibseven.bpm.engine.test.Deployment(resources =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessScenarioTest.testNaiveConditionBlocksChildCompletion.bpmn20.xml")
  @Test
  public void testNaiveConditionResolvesWhenTheCompletionSuppliesTheVariable() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocNaiveCondition");
    activate(pi.getId(), "taskA");

    Map<String, Object> vars = new HashMap<String, Object>();
    vars.put("enough", false);
    taskService.complete(task("taskA").getId(), vars);

    // Resolved to false, so the child completed and the scope is still waiting -- the same command
    // that throws without the variable succeeds with it.
    assertThat(task("taskA")).as("the child completed once the variable was supplied").isNull();
    assertThat(activeInstances(pi.getId())).isEqualTo(1);
  }

  /**
   * The ad hoc behaviour's "keep waiting" branch never inactivates or removes the child execution
   * that just ended — unlike {@code ParallelMultiInstanceActivityBehavior}, which does both. An
   * agentic loop activates a child per turn, so anything left behind per turn accumulates for the
   * lifetime of the instance.
   */
  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testRepeatedActivationDoesNotAccumulateExecutions() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocRepeat");

    long onEntry = runtimeService.createExecutionQuery().processInstanceId(pi.getId()).count();

    activate(pi.getId(), "taskA");
    taskService.complete(task("taskA").getId());
    long afterFirstTurn = runtimeService.createExecutionQuery().processInstanceId(pi.getId()).count();

    for (int turn = 1; turn < 5; turn++) {
      activate(pi.getId(), "taskA");
      taskService.complete(task("taskA").getId());
    }

    long afterFiveTurns = runtimeService.createExecutionQuery().processInstanceId(pi.getId()).count();
    ActivityInstance tree = runtimeService.getActivityInstance(pi.getId());
    ActivityInstance scope = tree.getChildActivityInstances()[0];
    int phantomChildren = scope.getChildActivityInstances().length;

    // Both facts are asserted, so neither hides the other.
    assertThat(phantomChildren)
        .as("the activity instance tree must show no children once every turn has ended")
        .isZero();
    // The invariant is that a turn leaves nothing behind, so turns 2..5 must add nothing to what
    // turn 1 produced. Comparing against the count on entry would instead measure the one-off
    // state marker, which the first activation creates and no later turn repeats.
    assertThat(afterFiveTurns)
        .as("turns 2..5 must not leave executions behind")
        .isEqualTo(afterFirstTurn);
    assertThat(afterFirstTurn)
        .as("the first activation adds exactly one lasting execution: the completion-state marker")
        .isEqualTo(onEntry + 1);

    Map<String, Object> vars = new HashMap<String, Object>();
    vars.put("enough", true);
    activate(pi.getId(), "taskB");
    taskService.complete(task("taskB").getId(), vars);
    testRule.assertProcessEnded(pi.getId());
  }

  // ---------------------------------------------------------------- FR-17

  // ---------------------------------------------------------------- async continuation

  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testAsyncBeforeScope() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocAsync");

    Job job = managementService.createJobQuery().processInstanceId(pi.getId()).singleResult();
    assertThat(job).as("asyncBefore on the scope must create a job").isNotNull();
    managementService.executeJob(job.getId());

    activate(pi.getId(), "taskA");
    taskService.complete(task("taskA").getId());
    testRule.assertProcessEnded(pi.getId());
  }

  // ---------------------------------------------------------------- synchronous child

  // Asserts on a historic variable instance, which exists only at full history. Without this the
  // test fails on a lower-history configuration instead of being skipped.
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testSynchronousChildActivation() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocSyncChild");

    // A synchronous child runs to completion inside the activation command. Nothing else is
    // active afterwards, so FR-16a should complete the scope right there.
    activate(pi.getId(), "sync");

    assertThat(historyService.createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("syncRan").count())
        .as("the synchronous child must have run").isEqualTo(1);
    testRule.assertProcessEnded(pi.getId());
  }

  // ---------------------------------------------------------------- nested scope child

  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testEmbeddedSubProcessChild() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocNested");

    activate(pi.getId(), "inner");
    assertThat(task("innerTask"))
        .as("an embedded sub process must be activatable as an ad hoc child")
        .isNotNull();

    taskService.complete(task("innerTask").getId());
    testRule.assertProcessEnded(pi.getId());
  }

  // ---------------------------------------------------------------- a scope child beside a sibling
  //
  // Without a completion condition the scope leaves once something was activated and nothing is
  // active. A child that is itself a scope runs one level below an inactive concurrent execution, so
  // "nothing is active" has to count that execution as running. The three shapes below are the ways
  // a child becomes a scope; the second and third are ordinary tasks, which is what made this common.

  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testScopeChildWithSiblingKeepsTheScopeOpen() {
    assertSiblingDoesNotEndTheScope("adHocScopeChildWithSibling", "inner", "innerTask");
  }

  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testChildWithBoundaryEventKeepsTheScopeOpen() {
    assertSiblingDoesNotEndTheScope("adHocChildWithBoundary", "guarded", "guarded");
  }

  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testChildWithIoMappingKeepsTheScopeOpen() {
    assertSiblingDoesNotEndTheScope("adHocChildWithIoMapping", "mapped", "mapped");
  }

  /**
   * Activates a scope child and a plain sibling, completes the sibling, and asserts the scope is still
   * open with the child's work running -- then that completing the child is what finally lets it go.
   */
  protected void assertSiblingDoesNotEndTheScope(String processKey, String child, String childTask) {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey(processKey);
    activate(pi.getId(), child, "quick");

    taskService.complete(task("quick").getId());

    assertThat(task(childTask))
        .as("completing the sibling must not cancel the scope child's running work")
        .isNotNull();
    assertThat(task("after"))
        .as("and the scope must not have been left while that work runs")
        .isNull();

    taskService.complete(task(childTask).getId());
    assertThat(task("after"))
        .as("once the child's work is done, nothing is active and the scope leaves")
        .isNotNull();
  }

  // ---------------------------------------------------------------- FR-13, history

  // Asserts on historic activity and process instances, so it needs activity-level history.
  @org.cibseven.bpm.engine.test.Deployment(resources =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessScenarioTest.testTwoConcurrentChildren.bpmn20.xml")
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_ACTIVITY)
  @Test
  public void testHistoryRecordsScopeAndChildren() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocConcurrent");

    activate(pi.getId(), "taskA", "taskB");
    taskService.complete(task("taskA").getId());
    taskService.complete(task("taskB").getId());
    testRule.assertProcessEnded(pi.getId());

    assertThat(historyService.createHistoricActivityInstanceQuery()
        .processInstanceId(pi.getId()).activityId("adHoc").count())
        .as("the ad hoc scope itself must be in history").isEqualTo(1);
    assertThat(historyService.createHistoricActivityInstanceQuery()
        .processInstanceId(pi.getId()).activityId("taskA").count())
        .as("each activated child must be in history").isEqualTo(1);
    assertThat(historyService.createHistoricActivityInstanceQuery()
        .processInstanceId(pi.getId()).activityId("taskB").count())
        .isEqualTo(1);
    assertThat(historyService.createHistoricProcessInstanceQuery()
        .processInstanceId(pi.getId()).singleResult().getEndTime()).isNotNull();
  }

  // ---------------------------------------------------------------- internal state leakage

  @org.cibseven.bpm.engine.test.Deployment(resources =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessScenarioTest.testTwoConcurrentChildren.bpmn20.xml")
  @Test
  public void testActivationCounterIsVisibleByDecision() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocConcurrent");
    activate(pi.getId(), "taskA");

    // DECIDED (CIB7-1850): the counter stays in the variable store and stays visible. Hiding it
    // needs a schema change, which is out under the no-breaking-changes constraint, and the engine's
    // own multi-instance exposes nrOfInstances / nrOfActiveInstances the same way. It is no longer
    // writable by a child -- see testCounterTamperingCannotStallTheScope -- and its visibility is
    // disclosed in the release notes. Asserted so the decision is pinned rather than assumed.
    assertThat(runtimeService.createVariableInstanceQuery()
        .processInstanceIdIn(pi.getId())
        .variableName("nrOfActivatedInstances")
        .count())
        .as("the activation counter is visible, by decision, as multi-instance's counters are")
        .isEqualTo(1);
  }

  /**
   * A child of the scope can overwrite the activation counter: {@code execution.setVariable} walks up
   * the execution hierarchy and finds it on the scope execution. Whether the counter should be
   * reachable at all is {@code CIB7-1850}'s decision, and
   * {@link #testCounterTamperingCannotStallTheScope} pins the consequence.
   *
   * <p>Independent of that decision, the tamper must not crash the engine, and this is the only test
   * that holds that line. The write arrives from a JUEL expression as a {@code Long}, so reading the
   * counter with a plain {@code (Integer)} cast throws {@code ClassCastException} from inside a PVM
   * atomic operation — out through the activation call, with the transaction already dirty. It pins
   * the defensive {@code Number} read in {@code AdHocSubProcessActivityBehavior.getActivatedCount}.
   */
  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testCounterTamperingDoesNotCrashTheEngine() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocTamper");

    activate(pi.getId(), "taskA");
    assertThat(task("taskA")).isNotNull();

    // The tamper is synchronous, so it writes the counter and ends -- and the scope reads the counter
    // back to decide completion -- while this call is still on the stack. A non-defensive read
    // surfaces right here.
    activate(pi.getId(), "tamper");

    // CIB7-1850: the child's write no longer reaches the engine's copy. setVariable walks
    // up the parent chain, the counter now lives on a marker execution that is a *sibling* of the
    // children, so the write falls through to the process instance and creates a second variable
    // of the same name. Two of them existing is the proof that ours was not overwritten.
    List<VariableInstance> counters = runtimeService.createVariableInstanceQuery()
        .processInstanceIdIn(pi.getId())
        .variableName("nrOfActivatedInstances")
        .list();
    assertThat(counters).as("the child's write landed beside the engine's copy, not on it").hasSize(2);

    List<Object> values = new ArrayList<Object>();
    for (VariableInstance counter : counters) {
      values.add(counter.getValue());
    }
    // 0L is the child's own copy, written from JUEL and therefore a Long. 2 is the engine's,
    // still an Integer and still counting both activations -- i.e. the tamper missed it.
    assertThat(values).containsExactlyInAnyOrder(0L, 2);

    // ... and the scope came through it intact.
    assertThat(task("taskA")).isNotNull();
  }

  /**
   * The other half of the tamper: surviving it is not the same as being unaffected by it. One
   * activation genuinely happened, so once nothing is active the scope must leave — even though a
   * child reset the count that records it.
   */
  @org.cibseven.bpm.engine.test.Deployment(resources =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessScenarioTest.testCounterTamperingDoesNotCrashTheEngine.bpmn20.xml")
  @Test
  public void testCounterTamperingCannotStallTheScope() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocTamper");

    activate(pi.getId(), "taskA");
    activate(pi.getId(), "tamper");

    taskService.complete(task("taskA").getId());

    testRule.assertProcessEnded(pi.getId());
  }

  /**
   * FR-15, the half we honour: once the engine has <em>observed</em> the completion condition hold,
   * the scope starts nothing further.
   *
   * <p>The sequence matters. With {@code cancelRemainingInstances="false"} the scope does not leave
   * when the condition fires — it waits for its survivors — so there is a window in which it is
   * still addressable and must refuse. Before CIB7-1850 it accepted, and a client could hold the
   * instance open indefinitely by activating into a scope that had already finished deciding.
   *
   * <p>The refusal lives in the behaviour rather than in {@code ActivateAdHocSubProcessActivitiesCmd}, so
   * process instance modification is refused too. This test drives modification for exactly that
   * reason.
   */
  @org.cibseven.bpm.engine.test.Deployment(resources =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessReviewProbeTest.probeCancelFalseFurtherActivation.bpmn20.xml")
  @Test
  public void testActivationRefusedOnceTheConditionWasObserved() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("probeCancelFalse");
    activate(pi.getId(), "slow", "extra");

    // Ending a child is what makes the engine evaluate the condition. cancelRemainingInstances is
    // false, so 'slow' survives and the scope stays open.
    Map<String, Object> vars = new HashMap<String, Object>();
    vars.put("enough", true);
    taskService.complete(task("extra").getId(), vars);

    assertThat(task("slow")).as("the survivor keeps running").isNotNull();
    assertThat(activeInstances(pi.getId())).as("and the scope has not left yet").isEqualTo(1);

    try {
      activate(pi.getId(), "extra");
      fail("the scope must refuse activation once its completion condition has been observed");
    } catch (org.cibseven.bpm.engine.ProcessEngineException e) {
      testRule.assertTextPresent("has already satisfied its completion condition", e.getMessage());
    }

    // Refused, not broken: the survivor is untouched and completing it still ends the scope.
    assertThat(task("slow")).isNotNull();
    taskService.complete(task("slow").getId());
    testRule.assertProcessEnded(pi.getId());
  }

  /**
   * The agentic loop, which is the shape this feature exists for: a caller activates one tool per
   * turn, reads the result, decides again, and ends the scope itself when it is done.
   *
   * <p>Two things have to hold for that to work, and this pins both. The scope must survive a turn in
   * which nothing is left active — a never-true completion condition is what buys that, and is the
   * pure-BPMN equivalent of the {@code autoComplete="false"} extension we chose not to add. And the
   * activation refusal added for FR-15 must not fire here: it evaluates the condition, the condition
   * is false, so turn after turn is allowed. A refusal that triggered on a parked scope would make
   * this pattern unusable.
   */
  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testAgenticLoopDrivenByExplicitCompletion() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocAgentLoop");

    // Turn 1. Nothing is active when it ends, and the scope must still be there.
    activate(pi.getId(), "toolA");
    taskService.complete(task("toolA").getId());
    assertThat(activeInstances(pi.getId())).as("the parked scope survives an empty turn").isEqualTo(1);

    // Turn 2, into a scope that has already run a tool and has no active children.
    activate(pi.getId(), "toolB");
    assertThat(task("toolB")).as("a further turn is allowed while the condition is false").isNotNull();
    taskService.complete(task("toolB").getId());
    assertThat(activeInstances(pi.getId())).isEqualTo(1);

    // The same tool again, since an ad hoc activity may be performed more than once.
    activate(pi.getId(), "toolA");
    taskService.complete(task("toolA").getId());
    assertThat(activeInstances(pi.getId())).isEqualTo(1);

    // The caller decides the loop is finished. Nothing else can end this scope.
    runtimeService.completeAdHocSubProcess(
        runtimeService.createExecutionQuery().processInstanceId(pi.getId())
            .activityId("adHoc").singleResult().getId());
    testRule.assertProcessEnded(pi.getId());
  }

  /**
   * "Once the condition holds" is a latch, and it has to be one for completion as well as for
   * activation. With {@code cancelRemainingInstances="false"} the scope stays open while its
   * survivors finish, so the condition is evaluated again when the last one ends — and by then the
   * variable it reads may have changed back. Re-deciding at that point would park the scope forever
   * on a completion that had already been determined.
   */
  @org.cibseven.bpm.engine.test.Deployment(resources =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessReviewProbeTest.probeCancelFalseFurtherActivation.bpmn20.xml")
  @Test
  public void testCompletionIsLatchedWhenTheConditionGoesFalseAgain() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("probeCancelFalse");
    activate(pi.getId(), "slow", "extra");

    // 'extra' ends with the condition true -> completion is decided, but 'slow' survives.
    Map<String, Object> vars = new HashMap<String, Object>();
    vars.put("enough", true);
    taskService.complete(task("extra").getId(), vars);
    assertThat(task("slow")).as("the survivor keeps running").isNotNull();

    // Something moves the variable back before the survivor finishes.
    runtimeService.setVariable(pi.getId(), "enough", false);

    taskService.complete(task("slow").getId());
    testRule.assertProcessEnded(pi.getId());
  }

  // ---------------------------------------------------------------- deletion / cleanup

  @org.cibseven.bpm.engine.test.Deployment(resources =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessScenarioTest.testTwoConcurrentChildren.bpmn20.xml")
  @Test
  public void testDeleteInstanceWithWaitingScope() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocConcurrent");
    runtimeService.deleteProcessInstance(pi.getId(), "cleanup");
    testRule.assertProcessEnded(pi.getId());
  }

  /**
   * After a synchronous child has run and ended, the ad hoc scope's own activity instance must still
   * describe the scope. The instance id keeps the {@code adHoc:} prefix, so a wrong activityId here
   * means the runtime tree reports the scope as the child activity that last ran — which is what
   * Cockpit and every {@code getActivityInstance} caller reads.
   */
  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testScopeKeepsItsOwnActivityIdentity() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocIdentity");

    activate(pi.getId(), "taskA");
    activate(pi.getId(), "sync");

    ActivityInstance scope = runtimeService.getActivityInstance(pi.getId()).getChildActivityInstances()[0];

    assertThat(scope.getActivityId())
        .as("the ad hoc scope's activity instance must report the scope, not the last child that ran")
        .isEqualTo("adHoc");
    assertThat(scope.getActivityType()).isEqualTo("adHocSubProcess");
  }

  // ------------------------------------------------- constructs the parser does not reject

  /** Ad hoc inside ad hoc: no design decision covers it. */
  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testNestedAdHocScope() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocNestedAdHoc");

    activate(pi.getId(), "innerTask");
    assertThat(task("innerTask"))
        .as("activating a grandchild of a nested ad hoc scope must work")
        .isNotNull();

    taskService.complete(task("innerTask").getId());
    testRule.assertProcessEnded(pi.getId());
  }

  /** An intermediate catch event as an ad hoc child. */
  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testIntermediateCatchEventChild() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocCatchChild");

    activate(pi.getId(), "waitForMsg");
    assertThat(runtimeService.createEventSubscriptionQuery()
        .processInstanceId(pi.getId()).eventName("theMessage").count())
        .as("activating a message catch child must create a subscription").isEqualTo(1);

    runtimeService.createMessageCorrelation("theMessage")
        .processInstanceId(pi.getId()).correlate();
    testRule.assertProcessEnded(pi.getId());
  }

  /**
   * camunda:inputOutput on the ad hoc scope itself. The whitelist in
   * checkActivityInputOutputSupported enumerates tag names literally, and adHocSubProcess was
   * missing from it, so this was rejected at deployment even though subProcess is allowed and
   * tAdHocSubProcess extends tSubProcess.
   */
  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testIoMappingOnTheScopeItself() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocScopeIoMapping");

    assertThat(runtimeService.createVariableInstanceQuery()
        .processInstanceIdIn(pi.getId()).variableName("budget").count())
        .as("an input parameter on the ad hoc scope must be applied on entry")
        .isEqualTo(1);

    activate(pi.getId(), "taskA");
    taskService.complete(task("taskA").getId());

    testRule.assertProcessEnded(pi.getId());
    assertThat(historyService.createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("spent").count())
        .as("an output parameter on the ad hoc scope must be applied when the scope leaves")
        .isEqualTo(1);
  }

  // ---------------------------------------------------------------- FR-9 / FR-10 rejections

  @Test
  public void testSequentialOrderingIsRejected() {
    try {
      deploy("orderingSequential.bpmn20.xml");
      fail("ordering='Sequential' must be rejected at deployment");
    } catch (ParseException e) {
      testRule.assertTextPresent("ordering='Sequential' is not supported", e.getMessage());
      // The refusal points at what does exist. CIB7-1882 made pairwise ordering possible, and a
      // modeller reaching for Sequential is asking for order -- leaving them with a flat "no" now
      // withholds an answer we have.
      testRule.assertTextPresent("connect them with a sequence flow", e.getMessage());
    }
  }

  @Test
  public void testParallelOrderingIsAccepted() {
    deploy("orderingParallel.bpmn20.xml");
    assertThat(repositoryService.createProcessDefinitionQuery()
        .processDefinitionKey("okOrdering").count()).isEqualTo(1);
  }

  @Test
  public void testInnerStartEventIsRejected() {
    try {
      deploy("innerStartEvent.bpmn20.xml");
      fail("an inner start event must be rejected at deployment");
    } catch (ParseException e) {
      testRule.assertTextPresent("startEvent is not supported inside an ad hoc sub process", e.getMessage());
    }
  }

  @Test
  public void testInnerEndEventIsRejected() {
    try {
      deploy("innerEndEvent.bpmn20.xml");
      fail("an inner end event must be rejected at deployment");
    } catch (ParseException e) {
      testRule.assertTextPresent("endEvent is not supported inside an ad hoc sub process", e.getMessage());
    }
  }

  @Test
  public void testChildlessScopeIsRejected() {
    try {
      deploy("childlessScope.bpmn20.xml");
      fail("a childless ad hoc scope must be rejected at deployment");
    } catch (ParseException e) {
      testRule.assertTextPresent("at least one activity is required", e.getMessage());
    }
  }

  /**
   * The generic exclusive-gateway check runs inside an ad hoc scope as it does everywhere else. It used
   * to be switched off there, correctly, while gateways inside a scope were refused by name -- its
   * message would only have sent a modeller to add a flow that was refused too. Once CIB7-1882 made
   * gateways and inner flows legal, the switch was protecting nothing and hiding everything: a gateway
   * with nowhere to go deployed inside a scope and failed only when a token reached it.
   */
  @Test
  public void testGatewayWithoutOutgoingFlowIsRejected() {
    try {
      deploy("gatewayWithoutOutgoingFlow.bpmn20.xml");
      fail("an exclusive gateway with no outgoing flow must be rejected inside a scope, as outside one");
    } catch (ParseException e) {
      testRule.assertTextPresent("Exclusive Gateway 'gw' has no outgoing sequence flows", e.getMessage());
    }
  }

  /** The rest of the same check comes back with it, not only the dead-end case. */
  @Test
  public void testGatewayWithSingleConditionalFlowIsRejected() {
    try {
      deploy("gatewaySingleConditionalFlow.bpmn20.xml");
      fail("a single outgoing flow carrying a condition must be rejected inside a scope, as outside one");
    } catch (ParseException e) {
      testRule.assertTextPresent("has only one outgoing sequence flow", e.getMessage());
    }
  }

  @Test
  public void testTriggeredByEventIsRejected() {
    try {
      deploy("triggeredByEvent.bpmn20.xml");
      fail("triggeredByEvent='true' on an ad hoc sub process must be rejected at deployment");
    } catch (ParseException e) {
      testRule.assertTextPresent("triggeredByEvent='true' is not allowed", e.getMessage());
    }
  }

  /**
   * CIB7-1882. BPMN 2.0.0 section 10.3.5 p.182: the performance of the first task must be followed
   * by a performance of the second, though not necessarily immediately. The rest of the scope stays
   * ad hoc while one pair is ordered.
   */
  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testInnerSequenceFlowIsPerformedInOrder() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocInnerFlow");

    // The target of an inner flow is reached from its predecessor, so it is not directly startable.
    assertThat(startableOf(pi)).containsExactly("first", "independent");

    activate(pi.getId(), "first");
    assertThat(task("second")).as("the target must not start with its predecessor").isNull();

    taskService.complete(task("first").getId());
    assertThat(task("second"))
        .as("completing the source must be followed by a performance of the target")
        .isNotNull();

    taskService.complete(task("second").getId());
    testRule.assertProcessEnded(pi.getId());
  }

  /**
   * CIB7-1882. The completion condition is satisfied by the very completion that takes an inner
   * flow, under the default {@code cancelRemainingInstances="true"}: the scope is done, so the
   * target is not performed. That is what the CIB7-1850 latch implies -- once the condition holds
   * the scope starts nothing further.
   */
  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testConditionSatisfiedAtAnInnerTransition() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocInnerFlowCondition");

    activate(pi.getId(), "first");
    Map<String, Object> vars = new HashMap<String, Object>();
    vars.put("done", true);
    taskService.complete(task("first").getId(), vars);

    assertThat(task("second"))
        .as("the condition held as the flow was taken, so the target must not be performed")
        .isNull();
    testRule.assertProcessEnded(pi.getId());
  }

  /**
   * The same moment with {@code cancelRemainingInstances="false"}: the scope waits for what is in
   * flight, so the target of the flow is performed. This is the guarantee BPMN 2.0.0 section 10.3.5
   * p.182 attaches to an inner flow, and the attribute is what chooses between the two readings.
   */
  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testConditionAtAnInnerTransitionAwaitsWhenNotCancelling() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocInnerFlowConditionAwaits");

    activate(pi.getId(), "first");
    Map<String, Object> vars = new HashMap<String, Object>();
    vars.put("done", true);
    taskService.complete(task("first").getId(), vars);

    assertThat(task("second"))
        .as("cancelRemainingInstances=false must let the flow's obligation be discharged")
        .isNotNull();

    taskService.complete(task("second").getId());
    testRule.assertProcessEnded(pi.getId());
  }

  /** CIB7-1882. A gateway is reached along an inner flow and routes along one of its own. */
  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testGatewayInsideScopeRoutes() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocGatewayRoutes");

    activate(pi.getId(), "triage");
    Map<String, Object> vars = new HashMap<String, Object>();
    vars.put("urgent", true);
    taskService.complete(task("triage").getId(), vars);

    assertThat(task("escalated")).as("the gateway must route along the satisfied condition").isNotNull();
    assertThat(task("standard")).isNull();

    taskService.complete(task("escalated").getId());
    testRule.assertProcessEnded(pi.getId());
  }

  /**
   * An error raised by an ad hoc child must reach a boundary error event on the scope, and taking
   * that boundary event must cancel the scope, including a child that is still active. Only an
   * interrupting timer on the scope was covered before, so error propagation out of the scope was
   * untested, and that is the path where a live sibling is most likely to be stranded.
   */
  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testBoundaryErrorFromChild() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocBoundaryError");

    activate(pi.getId(), "taskA");
    assertThat(task("taskA")).as("taskA must be active before the error").isNotNull();

    // Synchronous: it runs and throws BpmnError("23") during activation.
    activate(pi.getId(), "boom");

    assertThat(task("handled"))
        .as("an error from an ad hoc child must be caught by a boundary event on the scope")
        .isNotNull();
    assertThat(task("taskA"))
        .as("taking the boundary event must cancel the child that was still active")
        .isNull();

    taskService.complete(task("handled").getId());
    testRule.assertProcessEnded(pi.getId());
  }

  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testBoundaryTimerOnScope() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocBoundary");

    activate(pi.getId(), "taskA");
    assertThat(task("taskA")).isNotNull();

    Job timer = managementService.createJobQuery().processInstanceId(pi.getId()).singleResult();
    assertThat(timer).as("a boundary timer on an ad hoc scope must create a job").isNotNull();

    managementService.executeJob(timer.getId());

    assertThat(task("taskA"))
        .as("an interrupting boundary event must cancel the children")
        .isNull();
    assertThat(task("escalated"))
        .as("the boundary event's outgoing flow must be taken")
        .isNotNull();

    taskService.complete(task("escalated").getId());
    testRule.assertProcessEnded(pi.getId());
  }

  /**
   * A boundary event on a CHILD of the scope is neither rejected at deployment nor covered by any
   * design decision. This records what the engine actually does with it.
   */
  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testBoundaryEventOnChild() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocChildBoundary");
    activate(pi.getId(), "taskA");

    assertThat(task("taskA")).isNotNull();
    Job timer = managementService.createJobQuery().processInstanceId(pi.getId()).singleResult();
    assertThat(timer).as("a boundary timer on an ad hoc child must create a job").isNotNull();

    // The boundary event has no outgoing flow, so firing it can only end the child.
    managementService.executeJob(timer.getId());
    assertThat(activeInstances(pi.getId()))
        .as("firing a child boundary event must not strand the instance")
        .isZero();
  }

  /**
   * Compensation reaching a completed child of an ad hoc scope.
   *
   * <p>Two halves of the parser meet here and neither had ever been executed: the scope is marked
   * as consuming compensation, and a child carrying {@code isForCompensation} is excluded from the
   * startable set because it is reached by compensation being thrown rather than by being started.
   */
  @org.cibseven.bpm.engine.test.Deployment
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  @Test
  public void testCompensationOfAdHocChild() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocCompensation");

    activate(pi.getId(), "taskA");
    taskService.complete(task("taskA").getId());

    // No completion condition: the scope leaves once something ran and nothing is active, and the
    // outgoing flow parks at a user task so the compensation handler can be seen registered.
    assertThat(task("wait")).as("the scope must have completed and moved on").isNotNull();
    // The exact number is the engine's business -- an embedded sub process with the identical model
    // registers two as well. What matters is that the scope handed them up rather than losing them.
    assertThat(runtimeService.createEventSubscriptionQuery()
        .processInstanceId(pi.getId()).eventType("compensate").count())
        .as("completing an ad hoc child with a compensation boundary event must register a handler")
        .isPositive();

    taskService.complete(task("wait").getId());
    testRule.assertProcessEnded(pi.getId());

    HistoricVariableInstance compensated = historyService.createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("compensated").singleResult();
    assertThat(compensated)
        .as("throwing compensation must reach the handler inside the ad hoc scope")
        .isNotNull();
    assertThat(compensated.getValue()).isEqualTo(true);
  }

  /**
   * A non-interrupting event sub process inside the scope is refused at deployment (CIB7-1967).
   *
   * <p>It is refused rather than documented because the failure depends on the order in which the
   * two finish. Completing the scope's own activity first works; completing the event sub process
   * first fails in the persistence layer and leaves a task that can never be completed. A model
   * therefore passes a test run and fails in production, and nothing a modeller can write enforces
   * the order.
   *
   * <p>The runtime cause is that the engine expands concurrency for a non-interrupting event sub
   * process — it inserts a branch execution at scope level and moves the scope's activity onto it —
   * while this behaviour reads the unexpanded shape. Fixing that is the remaining half of
   * CIB7-1967; when it lands, this refusal goes and the behaviour test it replaced comes back. Its
   * assertions are preserved in that ticket.
   */
  @Test
  public void testNonInterruptingEventSubProcessIsRejected() {
    try {
      deploy("nonInterruptingEventSubProcess.bpmn20.xml");
      fail("a non-interrupting event sub process inside an ad hoc sub process must be rejected");
    } catch (ParseException e) {
      testRule.assertTextPresent("is non-interrupting, which is not supported", e.getMessage());
      testRule.assertTextPresent("Make it interrupting", e.getMessage());
    }
  }

  /**
   * A non-interrupting boundary event on one of the scope's activities is refused for the same
   * reason as a non-interrupting event sub process (CIB7-1967).
   *
   * <p>Found by looking for other ways into the same failure after the event sub process was
   * refused. Measured on a distribution before this refusal existed: the handler starts alongside
   * the activity it is attached to, and completing it while that activity is still running answers
   * 500 from the persistence layer, leaving a task that can never be completed. Identical symptom,
   * different element.
   *
   * <p>An interrupting boundary event is unaffected -- it cancels the activity instead of running
   * beside it -- and a boundary event on the ad hoc sub process itself is outside the scope
   * entirely, so neither is touched.
   */
  @Test
  public void testNonInterruptingBoundaryEventIsRejected() {
    try {
      deploy("nonInterruptingBoundaryEvent.bpmn20.xml");
      fail("a non-interrupting boundary event inside an ad hoc sub process must be rejected");
    } catch (ParseException e) {
      testRule.assertTextPresent("is non-interrupting, which is not supported", e.getMessage());
      testRule.assertTextPresent("the boundary event", e.getMessage());
    }
  }

  /** The same, interrupting: firing it must cancel what the scope has running. */
  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testInterruptingEventSubProcessInsideScope() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocInterruptingEventSubProcess");

    activate(pi.getId(), "taskA", "taskB");
    assertThat(taskService.createTaskQuery().count()).isEqualTo(2);

    runtimeService.createMessageCorrelation("theMessage")
        .processInstanceId(pi.getId()).correlate();

    assertThat(task("handled")).isNotNull();
    assertThat(task("taskA"))
        .as("an interrupting event sub process must cancel the scope's running children")
        .isNull();
    assertThat(task("taskB")).isNull();

    taskService.complete(task("handled").getId());
    testRule.assertProcessEnded(pi.getId());
  }

  /**
   * CIB7-2074. The same interruption, on a scope parked by a never-true completion condition.
   *
   * <p>It used to go back to waiting: the interruption cancelled the children and the scope then
   * asked its completion condition whether it might leave, which said no. Nothing was left running
   * and nothing could recover it from inside the model -- in the agentic shape the caller that would
   * have completed it is the driver, which the interruption had just cancelled.
   *
   * <p>An interruption is not a question about discretionary work. There is none left to ask about.
   */
  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void testInterruptingEventSubProcessLeavesAParkedScope() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocInterruptingParked");

    activate(pi.getId(), "taskA");
    assertThat(task("taskA")).isNotNull();

    runtimeService.createMessageCorrelation("theMessage")
        .processInstanceId(pi.getId()).correlate();
    assertThat(task("taskA")).as("the interruption cancels the scope's work").isNull();

    taskService.complete(task("handled").getId());

    testRule.assertProcessEnded(pi.getId());
  }

}
