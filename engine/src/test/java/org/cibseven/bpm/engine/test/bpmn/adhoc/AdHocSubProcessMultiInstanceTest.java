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
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParse;
import org.cibseven.bpm.engine.runtime.ActivityInstance;
import org.cibseven.bpm.engine.runtime.Execution;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.util.PluggableProcessEngineTest;
import org.cibseven.bpm.engine.variable.Variables;
import org.junit.jupiter.api.Test;

/**
 * CIB7-1984. Multi-instance markers on the ad hoc sub process itself: one discretionary workspace
 * per item, each with its own set of optional activities.
 *
 * <p>The construct was refused at deployment, and the refusal was about discovery rather than
 * capability: with several instances of one scope alive, a caller had no supported way to say which
 * one they meant. These tests pin the answer. A caller finds an instance by the loop's element
 * variable -- an ordinary {@code ExecutionQuery}, not a new API -- and the ad hoc commands resolve
 * that execution to the scope below it.
 */
public class AdHocSubProcessMultiInstanceTest extends PluggableProcessEngineTest {

  protected static final String PARALLEL =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessMultiInstanceTest.parallel.bpmn20.xml";
  protected static final String SEQUENTIAL =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessMultiInstanceTest.sequential.bpmn20.xml";
  protected static final String ENTRY =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessMultiInstanceTest.entry.bpmn20.xml";
  protected static final String CONDITION_LOCAL =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessMultiInstanceTest.conditionLocal.bpmn20.xml";
  protected static final String CONDITION =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessMultiInstanceTest.condition.bpmn20.xml";

  protected ProcessInstance startWithItems(String key, String... items) {
    return runtimeService.startProcessInstanceByKey(key,
        Variables.createVariables().putValue("items", Arrays.asList(items)));
  }

  /** How a caller names one workspace: the loop's element variable, through the ordinary query. */
  protected Execution instanceFor(ProcessInstance pi, String item) {
    return runtimeService.createExecutionQuery()
        .processInstanceId(pi.getId()).variableValueEquals("item", item).singleResult();
  }

  protected List<Task> tasks(ProcessInstance pi) {
    return taskService.createTaskQuery().processInstanceId(pi.getId()).list();
  }

  /**
   * Workspaces still open, read from the activity instance tree.
   *
   * <p>Not from an execution query by activity id: measured, a completed instance leaves its
   * per-item execution behind carrying the scope's activity again, inactive, so multi-instance can
   * count it. An execution query therefore keeps reporting it; the activity instance tree does not.
   */
  protected int openWorkspaces(ProcessInstance pi) {
    return runtimeService.getActivityInstance(pi.getId()).getActivityInstances("scope").length;
  }

  @Deployment(resources = PARALLEL)
  @Test
  public void aScopeCarryingMultiInstanceMarkersDeploysAndRunsOncePerItem() {
    ProcessInstance pi = startWithItems("adHocMiparallel", "a", "b", "c");

    assertThat(openWorkspaces(pi)).as("one ad hoc scope per item").isEqualTo(3);
    assertThat(tasks(pi)).as("and each of them starts nothing").isEmpty();
  }

  @Deployment(resources = PARALLEL)
  @Test
  public void anActivationReachesOnlyTheInstanceItNames() {
    ProcessInstance pi = startWithItems("adHocMiparallel", "a", "b", "c");

    Execution workspace = instanceFor(pi, "b");
    assertThat(workspace).as("the element variable names one instance").isNotNull();

    runtimeService.activateAdHocSubProcessActivities(workspace.getId(),
        Collections.singletonList("taskA"), null);

    List<Task> started = tasks(pi);
    assertThat(started).as("only the named workspace started anything").hasSize(1);
    // The task inherits the element variable by the ordinary upward walk, which is what proves it
    // was started in the right workspace rather than in whichever one the query happened to return.
    assertThat(runtimeService.getVariable(started.get(0).getExecutionId(), "item")).isEqualTo("b");
  }

  @Deployment(resources = PARALLEL)
  @Test
  public void eachWorkspaceKeepsItsOwnActivities() {
    ProcessInstance pi = startWithItems("adHocMiparallel", "a", "b", "c");

    runtimeService.activateAdHocSubProcessActivities(instanceFor(pi, "a").getId(),
        Collections.singletonList("taskA"), null);
    runtimeService.activateAdHocSubProcessActivities(instanceFor(pi, "c").getId(),
        Arrays.asList("taskA", "taskB"), null);

    assertThat(tasks(pi)).as("one in the first workspace, two in the third").hasSize(3);
    assertThat(taskService.createTaskQuery().processInstanceId(pi.getId())
        .taskDefinitionKey("taskB").count())
        .as("taskB only where it was started").isEqualTo(1L);
  }

  @Deployment(resources = PARALLEL)
  @Test
  public void oneWorkspaceCompletesWithoutEndingTheOthers() {
    ProcessInstance pi = startWithItems("adHocMiparallel", "a", "b", "c");

    runtimeService.activateAdHocSubProcessActivities(instanceFor(pi, "a").getId(),
        Collections.singletonList("taskA"), null);
    runtimeService.activateAdHocSubProcessActivities(instanceFor(pi, "b").getId(),
        Collections.singletonList("taskA"), null);

    Task inA = taskService.createTaskQuery().processInstanceId(pi.getId()).list().stream()
        .filter(t -> "a".equals(runtimeService.getVariable(t.getExecutionId(), "item")))
        .findFirst().orElseThrow(() -> new AssertionError("no task in workspace a"));
    taskService.complete(inA.getId());

    assertThat(openWorkspaces(pi))
        .as("the completed workspace is gone, the others are not").isEqualTo(2);
    assertThat(tasks(pi)).as("the task in b is untouched").hasSize(1);
  }

  @Deployment(resources = PARALLEL)
  @Test
  public void theProcessContinuesWhenEveryWorkspaceHasCompleted() {
    ProcessInstance pi = startWithItems("adHocMiparallel", "a", "b");

    for (String item : Arrays.asList("a", "b")) {
      Execution workspace = instanceFor(pi, item);
      runtimeService.activateAdHocSubProcessActivities(workspace.getId(),
          Collections.singletonList("taskA"), null);
    }
    for (Task task : tasks(pi)) {
      taskService.complete(task.getId());
    }

    testRule.assertProcessEnded(pi.getId());
  }

  /** The explicit completion command takes the same route, so a workspace can be closed by name. */
  @Deployment(resources = PARALLEL)
  @Test
  public void aWorkspaceIsCompletedByNamingIt() {
    ProcessInstance pi = startWithItems("adHocMiparallel", "a", "b", "c");

    runtimeService.activateAdHocSubProcessActivities(instanceFor(pi, "b").getId(),
        Collections.singletonList("taskA"), null);

    runtimeService.completeAdHocSubProcess(instanceFor(pi, "b").getId());

    assertThat(openWorkspaces(pi)).as("only the named workspace closed").isEqualTo(2);
    assertThat(tasks(pi)).as("its running activity was cancelled with it").isEmpty();
  }

  /** The body holds every instance, so naming it is ambiguous and says so. */
  @Deployment(resources = PARALLEL)
  @Test
  public void namingTheMultiInstanceBodyIsRefusedByCount() {
    ProcessInstance pi = startWithItems("adHocMiparallel", "a", "b", "c");

    // The body carries no activity of its own once the instances are created, so it is not
    // reachable by an execution query; the activity instance tree is where a caller finds it.
    ActivityInstance[] bodies = runtimeService.getActivityInstance(pi.getId())
        .getActivityInstances(BpmnParse.getIdForMiBody("scope"));
    assertThat(bodies).as("the multi-instance body is in the activity instance tree").hasSize(1);
    String bodyExecutionId = bodies[0].getExecutionIds()[0];

    try {
      runtimeService.activateAdHocSubProcessActivities(bodyExecutionId,
          Collections.singletonList("taskA"), null);
      fail("naming the body must be refused");
    } catch (BadUserRequestException e) {
      testRule.assertTextPresent("holds 3 instances of ad hoc sub process 'scope'", e.getMessage());
      testRule.assertTextPresent("element variable", e.getMessage());
    }
  }

  /** Without multi-instance there was only ever one scope, and that refusal is unchanged. */
  @Deployment(resources = PARALLEL)
  @Test
  public void namingTheProcessInstanceIsStillRefused() {
    ProcessInstance pi = startWithItems("adHocMiparallel", "a", "b", "c");

    try {
      runtimeService.activateAdHocSubProcessActivities(pi.getId(),
          Collections.singletonList("taskA"), null);
      fail("naming the process instance must be refused");
    } catch (BadUserRequestException e) {
      testRule.assertTextPresent("is not an ad hoc sub process instance", e.getMessage());
    }
  }

  /** A workspace that has closed is still found by its item, and must not be acted on. */
  @Deployment(resources = PARALLEL)
  @Test
  public void namingAWorkspaceThatHasCompletedIsRefused() {
    ProcessInstance pi = startWithItems("adHocMiparallel", "a", "b", "c");

    runtimeService.activateAdHocSubProcessActivities(instanceFor(pi, "a").getId(),
        Collections.singletonList("taskA"), null);
    taskService.complete(tasks(pi).get(0).getId());

    Execution closed = instanceFor(pi, "a");
    assertThat(closed).as("the item still names an execution after its workspace closed").isNotNull();

    try {
      runtimeService.activateAdHocSubProcessActivities(closed.getId(),
          Collections.singletonList("taskB"), null);
      fail("a completed workspace must be refused");
    } catch (BadUserRequestException e) {
      testRule.assertTextPresent("has already completed", e.getMessage());
    }
  }

  @Deployment(resources = SEQUENTIAL)
  @Test
  public void sequentialMarkersGiveOneWorkspaceAtATime() {
    ProcessInstance pi = startWithItems("adHocMisequential", "a", "b");

    assertThat(openWorkspaces(pi)).as("only the first workspace exists").isEqualTo(1);
    assertThat(instanceFor(pi, "a")).as("and it is the first item").isNotNull();
    assertThat(instanceFor(pi, "b")).as("the second has not started").isNull();

    runtimeService.activateAdHocSubProcessActivities(instanceFor(pi, "a").getId(),
        Collections.singletonList("taskA"), null);
    taskService.complete(tasks(pi).get(0).getId());

    assertThat(instanceFor(pi, "b")).as("completing the first starts the second").isNotNull();
  }

  /**
   * A completion condition is decided per workspace, but the variable it reads is not per workspace.
   *
   * <p>Measured, because the halves pull in different directions and neither is obvious. Satisfying
   * the condition in one workspace completes that one and leaves the others running -- {@code
   * cancelRemainingInstances} does not reach across instances. But the variable was written by an
   * ordinary task completion, so it walked up to the process instance, where every workspace's
   * condition reads it. The others therefore keep the work they already have and can never be given
   * more: activation is refused for all of them, and each ends as soon as its current work does.
   *
   * <p>A modeller who wants the workspaces independent has to keep the condition's variable out of
   * the shared scope, which the engine cannot do for them.
   */
  @Deployment(resources = CONDITION)
  @Test
  public void aCompletionConditionIsDecidedPerWorkspaceButReadsAVariableSharedByAll() {
    ProcessInstance pi = startWithItems("adHocMiCondition", "a", "b", "c");
    for (String item : Arrays.asList("a", "b", "c")) {
      runtimeService.activateAdHocSubProcessActivities(instanceFor(pi, item).getId(),
          Collections.singletonList("review"), null);
    }
    assertThat(openWorkspaces(pi)).isEqualTo(3);

    Task inA = tasks(pi).stream()
        .filter(t -> "a".equals(runtimeService.getVariable(t.getExecutionId(), "item")))
        .findFirst().orElseThrow(() -> new AssertionError("no task in workspace a"));
    taskService.complete(inA.getId(), Variables.createVariables().putValue("settled", true));

    assertThat(openWorkspaces(pi)).as("only the workspace whose child ended completed").isEqualTo(2);
    assertThat(tasks(pi)).as("the others keep the work they already had").hasSize(2);

    try {
      runtimeService.activateAdHocSubProcessActivities(instanceFor(pi, "b").getId(),
          Collections.singletonList("archive"), null);
      fail("a workspace whose condition holds must start nothing further");
    } catch (BadUserRequestException e) {
      testRule.assertTextPresent("has already satisfied its completion condition", e.getMessage());
    }
  }

  /**
   * And the way out of it: an input mapping on the scope.
   *
   * <p>{@code camunda:inputParameter} gives every instance a local copy of the variable, so the
   * upward walk from a task completion stops at that instance's own scope execution instead of
   * carrying on to the process instance. Each workspace then decides for itself, and settling one
   * leaves the others not merely running but still open to activation.
   *
   * <p>Worth pinning as a test rather than leaving as advice, because the difference between this
   * and the case above is one element in the model and nothing in the engine, and a reader who
   * meets the frozen version first will conclude the shape is unusable.
   */
  @Deployment(resources = CONDITION_LOCAL)
  @Test
  public void anInputMappingOnTheScopeGivesEachWorkspaceItsOwnCondition() {
    ProcessInstance pi = startWithItems("adHocMiConditionLocal", "a", "b", "c");
    for (String item : Arrays.asList("a", "b", "c")) {
      runtimeService.activateAdHocSubProcessActivities(instanceFor(pi, item).getId(),
          Collections.singletonList("review"), null);
    }

    Task inA = tasks(pi).stream()
        .filter(t -> "a".equals(runtimeService.getVariable(t.getExecutionId(), "item")))
        .findFirst().orElseThrow(() -> new AssertionError("no task in workspace a"));
    taskService.complete(inA.getId(), Variables.createVariables().putValue("settled", true));

    assertThat(openWorkspaces(pi)).as("only the settled workspace completed").isEqualTo(2);

    // the difference from the shared case: this does not throw
    runtimeService.activateAdHocSubProcessActivities(instanceFor(pi, "b").getId(),
        Collections.singletonList("archive"), null);
    assertThat(tasks(pi)).as("an untouched workspace still accepts work").hasSize(3);
  }
  /** The entry list is declared once and applies to every instance of the scope. */
  @Deployment(resources = ENTRY)
  @Test
  public void entryActivationOpensEveryWorkspace() {
    ProcessInstance pi = startWithItems("adHocMiEntry", "a", "b", "c");

    List<Task> started = tasks(pi);
    assertThat(started).as("every workspace opened with its first activity running").hasSize(3);
    assertThat(started).extracting(Task::getTaskDefinitionKey).containsOnly("triage");
    assertThat(started)
        .extracting(t -> runtimeService.getVariable(t.getExecutionId(), "item"))
        .as("one per item, not three in one workspace")
        .containsExactlyInAnyOrder("a", "b", "c");
  }


}
