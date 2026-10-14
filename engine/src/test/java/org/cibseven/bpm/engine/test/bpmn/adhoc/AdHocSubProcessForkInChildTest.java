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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.cibseven.bpm.engine.test.util.ActivityInstanceAssert.describeActivityInstanceTree;
import static org.cibseven.bpm.engine.test.util.ExecutionAssert.describeExecutionTree;

import java.util.Arrays;
import java.util.List;

import org.cibseven.bpm.engine.BadUserRequestException;
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
import org.cibseven.bpm.engine.variable.Variables;
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
  protected static final String FORK_JOIN_TWICE =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessForkInChildTest.forkJoinTwice.bpmn20.xml";
  protected static final String WITHOUT_A_FORK =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessForkInChildTest.childrenJoinWithoutAFork.bpmn20.xml";
  protected static final String ASYNC_JOIN =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessForkInChildTest.forkAndAsyncJoin.bpmn20.xml";
  protected static final String UNTIL_ENOUGH =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessForkInChildTest.forkAndJoinUntilEnough.bpmn20.xml";
  protected static final String UNTIL_ENOUGH_WAITING =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessForkInChildTest.forkAndJoinUntilEnoughWaiting.bpmn20.xml";
  protected static final String STRANDED_JOIN =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessForkInChildTest.strandedJoin.bpmn20.xml";
  protected static final String INCLUSIVE_SINGLE_FLOW =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessForkInChildTest.inclusiveSingleFlow.bpmn20.xml";
  protected static final String CONDITIONAL_FLOWS =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessForkInChildTest.conditionalFlows.bpmn20.xml";
  protected static final String EXCLUSIVE_BEFORE_A_JOIN =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessForkInChildTest.exclusiveBeforeAJoin.bpmn20.xml";

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
        .as("the path, once, where it ends; not the branch merged at the join").isEqualTo(Arrays.asList("after"));
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
    assertThat(runtimeService.getVariable(pi.getId(), "results"))
        .as("a path is gathered where it ends, though another branch of its start still runs")
        .isEqualTo(Arrays.asList("left"));
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

  /**
   * A branch can make the condition true while the other branch still runs. The condition is
   * consulted as that branch takes the flow to the join, and the scope completes there: the branch
   * never waits at the join, the running branch is cancelled, and nothing is left unfinished in the
   * history.
   */
  @Deployment(resources = UNTIL_ENOUGH)
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_ACTIVITY)
  @Test
  public void aConditionThatHoldsOnABranchCompletesTheScope() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("forkAndJoinUntilEnough");
    activate(scopeExecutionId(pi), "split");

    taskService.complete(task("right").getId(), Variables.createVariables().putValue("enough", true));

    testRule.assertProcessEnded(pi.getId());
    assertThat(historyService.createHistoricActivityInstanceQuery().processInstanceId(pi.getId())
        .unfinished().list()).as("nothing may be left running in the history").isEmpty();
    assertThat(historic(pi, "left").isCanceled()).as("the branch still running").isTrue();
    assertThat(historic(pi, "join")).as("the branch that made it true never reaches the join").isNull();
  }

  /**
   * With {@code cancelRemainingInstances="false"} the scope starts nothing further once the condition
   * holds, but lets the work that runs finish: the other branch, the join, and the path that goes on
   * from it, which is the same start's work.
   */
  @Deployment(resources = UNTIL_ENOUGH_WAITING)
  @Test
  public void aConditionThatHoldsOnABranchLetsTheStartFinish() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("forkAndJoinUntilEnoughWaiting");
    String scope = scopeExecutionId(pi);
    activate(scope, "split");

    taskService.complete(task("right").getId(), Variables.createVariables().putValue("enough", true));
    testRule.assertProcessNotEnded(pi.getId());
    assertThatThrownBy(() -> activate(scope, "other"))
        .as("the scope starts nothing further once the condition held")
        .isInstanceOf(BadUserRequestException.class)
        .hasMessageContaining("has already satisfied its completion condition");

    complete("left");
    assertThat(task("after")).as("the path of the same start goes on from the join").isNotNull();
    testRule.assertProcessNotEnded(pi.getId());

    complete("after");
    testRule.assertProcessEnded(pi.getId());
  }

  // ---------------------------------------------------------------- two starts of a forking child

  /**
   * The branches of two starts of one forking child are siblings under the scope. A join merges only
   * the branches of one start: the two left branches waiting at it are no pair.
   */
  @Deployment(resources = FORK_AND_JOIN)
  @Test
  public void twoStartsOfAForkingChildEachJoinTheirOwnBranches() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("forkAndJoin");
    String scope = scopeExecutionId(pi);
    activate(scope, "split");
    activate(scope, "split");

    completeEach("left");
    assertThat(count("after")).as("no start has both of its branches at the join").isZero();
    ActivityInstanceAssert.assertThat(runtimeService.getActivityInstance(pi.getId())).hasStructure(
        describeActivityInstanceTree(pi.getProcessDefinitionId())
          .beginScope("adHoc")
            .activity("join")
            .activity("join")
            .activity("right")
            .activity("right")
        .done());

    completeOne("right");
    assertThat(count("after")).as("the start whose branches are both at the join goes on").isEqualTo(1);
    assertThat(count("right")).isEqualTo(1);

    completeOne("right");
    assertThat(count("after")).isEqualTo(2);

    completeEach("after");
    @SuppressWarnings("unchecked")
    List<Object> results = (List<Object>) runtimeService.getVariable(pi.getId(), "results");
    assertThat(results)
        .as("each start's path, once, where it ends")
        .containsExactly("after", "after");
    activate(scope, "other");
    assertThat(task("other")).as("the scope must still take activations").isNotNull();
  }

  /** An inclusive join waits only for branches of its own start that can still reach it. */
  @Deployment(resources = INCLUSIVE)
  @Test
  public void anInclusiveJoinWaitsOnlyForItsOwnStart() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("inclusiveForkAndJoin");
    String scope = scopeExecutionId(pi);
    activate(scope, "split");
    activate(scope, "split");

    completeEach("left");
    assertThat(count("after")).as("each left waits for the right of its own start").isZero();

    completeOne("right");
    assertThat(count("after")).as("the other start's right does not keep this join waiting")
        .isEqualTo(1);

    completeOne("right");
    completeEach("after");
    testRule.assertProcessEnded(pi.getId());
  }

  /** The branch that goes on from a join still belongs to its start at the next join. */
  @Deployment(resources = FORK_JOIN_TWICE)
  @Test
  public void aStartThatJoinsTwiceKeepsItsBranchesTogether() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("forkJoinTwice");
    String scope = scopeExecutionId(pi);
    activate(scope, "split");
    activate(scope, "split");

    completeEach("a");
    completeEach("b");
    assertThat(count("d")).isEqualTo(2);

    completeEach("d");
    assertThat(count("e")).as("each d waits at the second join for the c of its own start").isZero();

    completeOne("c");
    assertThat(count("e")).isEqualTo(1);
    completeOne("c");
    assertThat(count("e")).isEqualTo(2);
  }

  /** Children that never forked still meet at a join, as they did before the fork had a start. */
  @Deployment(resources = WITHOUT_A_FORK)
  @Test
  public void childrenThatNeverForkedStillJoinEachOther() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("childrenJoinWithoutAFork");
    String scope = scopeExecutionId(pi);
    activate(scope, "x");
    activate(scope, "y");

    complete("x");
    assertThat(count("z")).isZero();
    complete("y");
    assertThat(count("z")).isEqualTo(1);
  }

  /**
   * A branch of a fork waits at a join for a child that is never started. Without a completion
   * condition the scope stays open, because the waiting branch is running; completing the scope
   * cancels it.
   */
  @Deployment(resources = STRANDED_JOIN)
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_ACTIVITY)
  @Test
  public void aJoinThatNeverFiresKeepsAScopeWithoutAConditionOpen() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("strandedJoin");
    String scope = scopeExecutionId(pi);
    activate(scope, "split");

    complete("right");
    complete("left");
    testRule.assertProcessNotEnded(pi.getId());
    ActivityInstanceAssert.assertThat(runtimeService.getActivityInstance(pi.getId())).hasStructure(
        describeActivityInstanceTree(pi.getProcessDefinitionId())
          .beginScope("adHoc")
            .activity("join")
        .done());

    runtimeService.completeAdHocSubProcess(scope);
    testRule.assertProcessEnded(pi.getId());
    assertThat(historic(pi, "join").isCanceled()).isTrue();
  }

  /**
   * A child started on its own does not meet a branch of a fork at a join: the join waits for a
   * branch of the same start, which never comes.
   */
  @Deployment(resources = STRANDED_JOIN)
  @Test
  public void aChildStartedOnItsOwnDoesNotMeetABranchOfAFork() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("strandedJoin");
    String scope = scopeExecutionId(pi);
    activate(scope, "split");
    activate(scope, "x");

    complete("left");
    complete("x");
    assertThat(count("after")).as("the branch of the fork and x are no pair").isZero();
    ActivityInstanceAssert.assertThat(runtimeService.getActivityInstance(pi.getId())).hasStructure(
        describeActivityInstanceTree(pi.getProcessDefinitionId())
          .beginScope("adHoc")
            .activity("join")
            .activity("join")
            .activity("right")
        .done());
  }

  /** A branch that reaches the join in a job of its own still knows its start. */
  @Deployment(resources = ASYNC_JOIN)
  @Test
  public void anAsynchronousJoinStillTellsTheStartsApart() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("forkAndAsyncJoin");
    String scope = scopeExecutionId(pi);
    activate(scope, "split");
    activate(scope, "split");

    completeEach("left");
    testRule.executeAvailableJobs();
    assertThat(count("after")).isZero();

    completeOne("right");
    testRule.executeAvailableJobs();
    assertThat(count("after")).isEqualTo(1);

    completeOne("right");
    testRule.executeAvailableJobs();
    assertThat(count("after")).isEqualTo(2);
  }

  // ---------------------------------------------------------------- a fork that takes one flow

  /**
   * An inclusive split whose conditions select one flow forks all the same. Its path carries its
   * activation, so the join does not wait for another activation that is still before the split.
   */
  @Deployment(resources = INCLUSIVE_SINGLE_FLOW)
  @Test
  public void anActivationThatTakesOneFlowDoesNotWaitForAnotherOne() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("inclusiveSingleFlow");
    String scope = scopeExecutionId(pi);
    activate(scope, "split");
    activate(scope, "split");

    completeOne("split", true, false);
    complete("left");
    assertThat(count("after"))
        .as("the join must not wait for the activation that is still at the split")
        .isEqualTo(1);

    completeOne("split", true, true);
    complete("left");
    complete("right");
    assertThat(count("after")).isEqualTo(2);

    completeEach("after");
    assertThat(results(pi)).as("each activation's path, once, where it ends")
        .containsExactly("after", "after");
  }

  /** The other order: a path that took one flow passes the join where a forked activation waits. */
  @Deployment(resources = INCLUSIVE_SINGLE_FLOW)
  @Test
  public void anActivationThatTakesOneFlowPassesAJoinWhereAForkedOneWaits() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("inclusiveSingleFlow");
    String scope = scopeExecutionId(pi);
    activate(scope, "split");
    activate(scope, "split");

    completeOne("split", true, true);
    complete("left");
    assertThat(count("after")).isZero();

    completeOne("split", true, false);
    complete("left");
    assertThat(count("after"))
        .as("the path that took one flow goes on alone, the forked activation still waits")
        .isEqualTo(1);

    complete("right");
    assertThat(count("after")).isEqualTo(2);
  }

  /** Two activations that each take one flow are two paths, not the two branches of one join. */
  @Deployment(resources = INCLUSIVE_SINGLE_FLOW)
  @Test
  public void twoActivationsThatTakeOneFlowEachAreNotMergedAtTheJoin() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("inclusiveSingleFlow");
    String scope = scopeExecutionId(pi);
    activate(scope, "split");
    activate(scope, "split");

    completeOne("split", true, false);
    completeOne("split", true, false);
    completeEach("left");
    assertThat(count("after")).as("two activations, two paths").isEqualTo(2);

    completeEach("after");
    assertThat(results(pi)).containsExactly("after", "after");
  }

  /** Conditional flows of a task fork as an inclusive gateway does, also when one condition holds. */
  @Deployment(resources = CONDITIONAL_FLOWS)
  @Test
  public void conditionalFlowsThatSelectOneFlowForkAllTheSame() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("conditionalFlows");
    String scope = scopeExecutionId(pi);
    activate(scope, "split");
    activate(scope, "split");

    completeOne("split", true, false);
    complete("left");
    assertThat(count("after"))
        .as("the join must not wait for the activation that is still at the split")
        .isEqualTo(1);
  }

  /**
   * An exclusive gateway chooses one flow by definition and is no fork: the path of a child that
   * passes one still meets a child activated on its own at a join.
   */
  @Deployment(resources = EXCLUSIVE_BEFORE_A_JOIN)
  @Test
  public void anExclusiveGatewayIsNoFork() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("exclusiveBeforeAJoin");
    String scope = scopeExecutionId(pi);
    activate(scope, "x");
    activate(scope, "y");

    complete("x");
    complete("a");
    assertThat(count("z")).isZero();
    complete("y");
    assertThat(count("z")).isEqualTo(1);
  }

  // ---------------------------------------------------------------- a child started by modification

  /**
   * The branches of a fork carry their activation in the local variable {@code adHocActivationId}: the
   * id of an execution the activation created, the same on every branch.
   */
  @Deployment(resources = FORK_AND_JOIN)
  @Test
  public void theBranchesOfAForkCarryTheIdOfTheirActivation() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("forkAndJoin");
    activate(scopeExecutionId(pi), "split");

    String left = task("left").getExecutionId();
    String right = task("right").getExecutionId();
    Object label = runtimeService.getVariableLocal(left, "adHocActivationId");
    assertThat(label).isInstanceOf(String.class).isIn(left, right);
    assertThat(runtimeService.getVariableLocal(right, "adHocActivationId")).isEqualTo(label);
  }

  /**
   * A child started by process instance modification belongs to no activation. Started again that
   * way, a branch of a fork no longer meets the other branch of its activation at the join.
   */
  @Deployment(resources = FORK_AND_JOIN)
  @Test
  public void aBranchStartedAgainByModificationBelongsToNoActivation() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("forkAndJoin");
    activate(scopeExecutionId(pi), "split");

    runtimeService.createProcessInstanceModification(pi.getId())
        .cancelActivityInstance(activityInstanceId(pi, "left"))
        .startBeforeActivity("left")
        .execute();
    complete("left");
    complete("right");

    assertThat(count("after")).as("the branches of no activation and of one never meet").isZero();
  }

  /**
   * The workaround: the instruction gives the new child the label of the activation, as a local
   * variable, and the branch meets the other branch of that activation at the join again.
   */
  @Deployment(resources = FORK_AND_JOIN)
  @Test
  public void aBranchStartedAgainWithItsActivationsLabelJoinsIt() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("forkAndJoin");
    activate(scopeExecutionId(pi), "split");
    Object label = runtimeService.getVariableLocal(task("right").getExecutionId(), "adHocActivationId");

    runtimeService.createProcessInstanceModification(pi.getId())
        .cancelActivityInstance(activityInstanceId(pi, "left"))
        .startBeforeActivity("left")
        .setVariableLocal("adHocActivationId", label)
        .execute();
    complete("left");
    complete("right");

    assertThat(count("after")).isEqualTo(1);
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

  protected long count(String key) {
    return taskService.createTaskQuery().taskDefinitionKey(key).count();
  }

  /** Completes one of the tasks of an activity, whichever the query returns first. */
  protected void completeOne(String key) {
    taskService.complete(taskService.createTaskQuery().taskDefinitionKey(key).list().get(0).getId());
  }

  protected String activityInstanceId(ProcessInstance pi, String activityId) {
    return runtimeService.getActivityInstance(pi.getId()).getActivityInstances(activityId)[0].getId();
  }

  /** Completes one of the tasks of an activity, with the conditions of the split after it. */
  protected void completeOne(String key, boolean goLeft, boolean goRight) {
    taskService.complete(taskService.createTaskQuery().taskDefinitionKey(key).list().get(0).getId(),
        Variables.createVariables().putValue("goLeft", goLeft).putValue("goRight", goRight));
  }

  @SuppressWarnings("unchecked")
  protected List<Object> results(ProcessInstance pi) {
    return (List<Object>) runtimeService.getVariable(pi.getId(), "results");
  }

  protected void completeEach(String key) {
    for (Task task : taskService.createTaskQuery().taskDefinitionKey(key).list()) {
      taskService.complete(task.getId());
    }
  }

  /** The single historic instance of an activity; for a join, the one of the branch that waited. */
  protected HistoricActivityInstance historic(ProcessInstance pi, String activityId) {
    return historyService.createHistoricActivityInstanceQuery().processInstanceId(pi.getId())
        .activityId(activityId).singleResult();
  }
}
