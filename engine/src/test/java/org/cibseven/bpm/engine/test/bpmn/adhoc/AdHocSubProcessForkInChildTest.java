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
import static org.cibseven.bpm.engine.test.util.ActivityInstanceAssert.describeActivityInstanceTree;
import static org.cibseven.bpm.engine.test.util.ExecutionAssert.describeExecutionTree;

import java.util.Arrays;

import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.history.HistoricActivityInstance;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.util.ActivityInstanceAssert;
import org.cibseven.bpm.engine.test.util.ExecutionAssert;
import org.cibseven.bpm.engine.test.util.ExecutionTree;
import org.cibseven.bpm.engine.test.util.PluggableProcessEngineTest;
import org.junit.jupiter.api.Test;

/**
 * CIB7-2264. A child of an ad hoc sub process whose flow forks.
 *
 * <p>Every child runs on a concurrent execution under the scope execution, and a child that is not a
 * scope itself forks right there: its branches become further concurrent executions of the scope. The
 * scope must stay what it was -- on its own activity, in the activity instance tree with the branches
 * under it, open to further activation -- and each branch that ends is a child that ends, a branch
 * merged at a join included, while the path goes on from the join.
 */
public class AdHocSubProcessForkInChildTest extends PluggableProcessEngineTest {

  protected static final String FORK_AND_JOIN =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessForkInChildTest.forkAndJoin.bpmn20.xml";
  protected static final String WITHOUT_CONDITION =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessForkInChildTest.forkAndJoinWithoutCondition.bpmn20.xml";
  protected static final String IMPLICIT_FORK =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessForkInChildTest.implicitFork.bpmn20.xml";
  protected static final String SCOPE_CHILD =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessForkInChildTest.scopeChildForks.bpmn20.xml";
  protected static final String INCLUSIVE =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessForkInChildTest.inclusiveForkAndJoin.bpmn20.xml";

  // ---------------------------------------------------------------- the scope after a fork

  /** The case as reported: the forking child is the only child of the scope. */
  @Deployment(resources = FORK_AND_JOIN)
  @Test
  public void aForkInTheOnlyChildKeepsTheScopeWithItsBranches() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("forkAndJoin");
    String scope = scopeExecutionId(pi);

    activate(scope, "split");

    assertThat(activityIdOf(scope)).as("the scope execution must stay on the scope").isEqualTo("adHoc");
    ActivityInstanceAssert.assertThat(runtimeService.getActivityInstance(pi.getId())).hasStructure(
        describeActivityInstanceTree(pi.getProcessDefinitionId())
          .beginScope("adHoc")
            .activity("left")
            .activity("right")
        .done());
    ExecutionAssert.assertThat(ExecutionTree.forExecution(pi.getId(), processEngine)).matches(
        describeExecutionTree(null).scope()
          .child("adHoc").scope().id(scope)
            .child("left").concurrent().noScope().up()
            .child("right").concurrent().noScope().up()
            .child(null).scope().eventScope().up()
        .done());
  }

  /**
   * The same fork beside a running child, which the engine handled before: the scope already had
   * several children then, and the branch was added beside them. Both ways must give the same tree.
   */
  @Deployment(resources = FORK_AND_JOIN)
  @Test
  public void aForkBesideARunningChildGivesTheSameTree() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("forkAndJoin");
    String scope = scopeExecutionId(pi);

    activate(scope, "other");
    activate(scope, "split");

    ActivityInstanceAssert.assertThat(runtimeService.getActivityInstance(pi.getId())).hasStructure(
        describeActivityInstanceTree(pi.getProcessDefinitionId())
          .beginScope("adHoc")
            .activity("other")
            .activity("left")
            .activity("right")
        .done());
    ExecutionAssert.assertThat(ExecutionTree.forExecution(pi.getId(), processEngine)).matches(
        describeExecutionTree(null).scope()
          .child("adHoc").scope().id(scope)
            .child("other").concurrent().noScope().up()
            .child("left").concurrent().noScope().up()
            .child("right").concurrent().noScope().up()
            .child(null).scope().eventScope().up()
        .done());
  }

  @Deployment(resources = FORK_AND_JOIN)
  @Test
  public void theScopeCanBeActivatedAgainWhileTheBranchesRun() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("forkAndJoin");
    String scope = scopeExecutionId(pi);
    activate(scope, "split");

    activate(scope, "other");

    ActivityInstanceAssert.assertThat(runtimeService.getActivityInstance(pi.getId())).hasStructure(
        describeActivityInstanceTree(pi.getProcessDefinitionId())
          .beginScope("adHoc")
            .activity("left")
            .activity("right")
            .activity("other")
        .done());
  }

  /** A child that is a scope hands its fork to its execution under the ad hoc scope as it leaves. */
  @Deployment(resources = SCOPE_CHILD)
  @Test
  public void aScopeChildThatForksKeepsTheScopeWithItsBranches() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("scopeChildForks");
    String scope = scopeExecutionId(pi);

    activate(scope, "split");

    assertThat(activityIdOf(scope)).isEqualTo("adHoc");
    ActivityInstanceAssert.assertThat(runtimeService.getActivityInstance(pi.getId())).hasStructure(
        describeActivityInstanceTree(pi.getProcessDefinitionId())
          .beginScope("adHoc")
            .activity("left")
            .activity("right")
        .done());

    complete("left");
    complete("right");
    complete("after");
    testRule.assertProcessEnded(pi.getId());
  }

  // ---------------------------------------------------------------- branches that end

  /**
   * Each branch that ends leaves the scope as a child, and the branch a join merges into the other
   * ends at the join. The path goes on from there, and once it has ended nothing is left behind.
   */
  @Deployment(resources = FORK_AND_JOIN)
  @Test
  public void aBranchMergedAtTheJoinEndsThereAndThePathGoesOn() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("forkAndJoin");
    String scope = scopeExecutionId(pi);
    activate(scope, "split");

    complete("left");
    ActivityInstanceAssert.assertThat(runtimeService.getActivityInstance(pi.getId())).hasStructure(
        describeActivityInstanceTree(pi.getProcessDefinitionId())
          .beginScope("adHoc")
            .activity("join")
            .activity("right")
        .done());

    complete("right");
    assertThat(task("after")).as("the path must go on from the join").isNotNull();
    ExecutionAssert.assertThat(ExecutionTree.forExecution(pi.getId(), processEngine)).matches(
        describeExecutionTree(null).scope()
          .child("adHoc").scope().id(scope)
            .child("after").concurrent().noScope().up()
            .child(null).scope().eventScope().up()
        .done());

    complete("after");
    assertThat(runtimeService.getVariable(pi.getId(), "results"))
        .as("the branch merged at the join, then the end of the path").isEqualTo(Arrays.asList("join", "after"));
    ExecutionAssert.assertThat(ExecutionTree.forExecution(pi.getId(), processEngine)).matches(
        describeExecutionTree(null).scope()
          .child("adHoc").scope().id(scope)
            .child(null).scope().eventScope().up()
        .done());

    activate(scope, "other");
    assertThat(task("other")).as("the scope must still take activations").isNotNull();
  }

  @Deployment(resources = IMPLICIT_FORK)
  @Test
  public void branchesOfAForkWithoutAGatewayEndApart() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("implicitFork");
    String scope = scopeExecutionId(pi);

    activate(scope, "split");

    assertThat(activityIdOf(scope)).isEqualTo("adHoc");
    ActivityInstanceAssert.assertThat(runtimeService.getActivityInstance(pi.getId())).hasStructure(
        describeActivityInstanceTree(pi.getProcessDefinitionId())
          .beginScope("adHoc")
            .activity("left")
            .activity("right")
        .done());

    complete("left");
    testRule.assertProcessNotEnded(pi.getId());
    complete("right");
    testRule.assertProcessEnded(pi.getId());
  }

  // ---------------------------------------------------------------- completion

  /**
   * Without a condition the scope completes when nothing is left running. A branch merged at the join
   * leaves the other one carrying the path, so the scope must not complete there.
   */
  @Deployment(resources = WITHOUT_CONDITION)
  @Test
  public void withoutAConditionTheScopeCompletesWhenTheJoinedPathEnds() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("forkAndJoinWithoutCondition");
    activate(scopeExecutionId(pi), "split");

    complete("left");
    complete("right");
    testRule.assertProcessNotEnded(pi.getId());
    assertThat(task("after")).as("the path must go on from the join").isNotNull();

    complete("after");
    testRule.assertProcessEnded(pi.getId());
  }

  @Deployment(resources = INCLUSIVE)
  @Test
  public void anInclusiveForkAndJoinKeepTheScopeTheSameWay() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("inclusiveForkAndJoin");
    String scope = scopeExecutionId(pi);

    activate(scope, "split");

    assertThat(activityIdOf(scope)).isEqualTo("adHoc");
    ActivityInstanceAssert.assertThat(runtimeService.getActivityInstance(pi.getId())).hasStructure(
        describeActivityInstanceTree(pi.getProcessDefinitionId())
          .beginScope("adHoc")
            .activity("left")
            .activity("right")
        .done());

    complete("left");
    complete("right");
    testRule.assertProcessNotEnded(pi.getId());
    complete("after");
    testRule.assertProcessEnded(pi.getId());
  }

  /** Completing the scope cancels a branch waiting at the join, as it cancels one still running. */
  @Deployment(resources = FORK_AND_JOIN)
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_ACTIVITY)
  @Test
  public void aBranchWaitingAtTheJoinIsCancelledWhenTheScopeCompletes() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("forkAndJoin");
    String scope = scopeExecutionId(pi);
    activate(scope, "split");
    complete("left");

    runtimeService.completeAdHocSubProcess(scope);

    testRule.assertProcessEnded(pi.getId());
    assertThat(historyService.createHistoricActivityInstanceQuery().processInstanceId(pi.getId())
        .unfinished().list()).as("nothing may be left running in the history").isEmpty();
    assertThat(historic(pi, "join").isCanceled()).as("the branch waiting at the join").isTrue();
    assertThat(historic(pi, "right").isCanceled()).as("the branch still running").isTrue();
  }

  /** Branches belong to the scope's activity instance, in the history as in the runtime tree. */
  @Deployment(resources = FORK_AND_JOIN)
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_ACTIVITY)
  @Test
  public void theHistoryPutsEveryBranchUnderTheScope() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("forkAndJoin");
    activate(scopeExecutionId(pi), "split");
    complete("left");
    complete("right");
    complete("after");

    String scopeInstance = historic(pi, "adHoc").getId();
    for (HistoricActivityInstance instance : historyService.createHistoricActivityInstanceQuery()
        .processInstanceId(pi.getId()).list()) {
      if (Arrays.asList("split", "fork", "left", "right", "join", "after").contains(instance.getActivityId())) {
        assertThat(instance.getParentActivityInstanceId()).as(instance.getActivityId())
            .isEqualTo(scopeInstance);
        assertThat(instance.getEndTime()).as(instance.getActivityId()).isNotNull();
      }
    }
  }

  // ---------------------------------------------------------------- helpers

  protected String scopeExecutionId(ProcessInstance pi) {
    return runtimeService.createExecutionQuery().processInstanceId(pi.getId()).activityId("adHoc")
        .singleResult().getId();
  }

  protected String activityIdOf(String executionId) {
    return ((ExecutionEntity) runtimeService.createExecutionQuery().executionId(executionId).singleResult())
        .getActivityId();
  }

  protected void activate(String scopeExecutionId, String activityId) {
    runtimeService.createAdHocSubProcessActivation(scopeExecutionId).startActivity(activityId).execute();
  }

  protected Task task(String key) {
    return taskService.createTaskQuery().taskDefinitionKey(key).singleResult();
  }

  protected void complete(String key) {
    taskService.complete(task(key).getId());
  }

  /** The single historic instance of an activity; for a join, the one of the branch that waited. */
  protected HistoricActivityInstance historic(ProcessInstance pi, String activityId) {
    return historyService.createHistoricActivityInstanceQuery().processInstanceId(pi.getId())
        .activityId(activityId).singleResult();
  }
}
