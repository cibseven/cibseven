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
import java.util.Collections;

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
import org.junit.jupiter.api.Test;

/**
 * CIB7-2269. A non-interrupting event sub process inside the scope, and a non-interrupting boundary
 * event on one of its activities.
 *
 * <p>Both start work beside what the scope is running. That work runs on a concurrent execution of
 * the scope of its own, so it is one more child: the scope stays what it was, a running handler
 * keeps it open, and the handler's end is a child's end. Neither handler is started by an
 * activation, so neither makes a scope count as activated; and an event sub process, being an event
 * handler rather than a performance, is not gathered, while a boundary path is a branch of the
 * activity it is attached to and is.
 */
public class AdHocSubProcessNonInterruptingHandlerTest extends PluggableProcessEngineTest {

  protected static final String EVENT_SUB_PROCESS =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessNonInterruptingHandlerTest.eventSubProcess.bpmn20.xml";
  protected static final String WITH_CONDITION =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessNonInterruptingHandlerTest.eventSubProcessWithCondition.bpmn20.xml";
  protected static final String WAITING =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessNonInterruptingHandlerTest.eventSubProcessWaiting.bpmn20.xml";
  protected static final String INTERRUPTED =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessNonInterruptingHandlerTest.interruptedHandler.bpmn20.xml";
  protected static final String BOUNDARY_EVENT =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessNonInterruptingHandlerTest.boundaryEvent.bpmn20.xml";
  protected static final String BOUNDARY_PATH_MEETS_ITS_ACTIVITY =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessNonInterruptingHandlerTest.boundaryPathMeetsItsActivity.bpmn20.xml";
  protected static final String BOUNDARY_PATH_MEETS_FORK =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessNonInterruptingHandlerTest.boundaryPathMeetsFork.bpmn20.xml";
  protected static final String BOUNDARY_ON_A_BRANCH =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessNonInterruptingHandlerTest.boundaryOnABranch.bpmn20.xml";
  protected static final String BOUNDARY_AND_A_SEPARATE_CHILD =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessNonInterruptingHandlerTest.boundaryAndASeparateChild.bpmn20.xml";

  // ---------------------------------------------------------------- the event sub process

  /** The case CIB7-1967 measured to fail: the handler's task completed while the child still runs. */
  @Deployment(resources = EVENT_SUB_PROCESS)
  @Test
  public void aHandlerBesideARunningChildCanEndFirst() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("nonInterruptingEventSubProcess");
    String scope = scopeExecutionId(pi);
    activate(scope, "taskA");

    runtimeService.correlateMessage("notice");

    assertThat(activityIdOf(scope)).as("the scope execution must stay on the scope").isEqualTo("adHoc");
    ActivityInstanceAssert.assertThat(runtimeService.getActivityInstance(pi.getId())).hasStructure(
        describeActivityInstanceTree(pi.getProcessDefinitionId())
          .beginScope("adHoc")
            .activity("taskA")
            .beginScope("onNotice")
              .activity("handled")
        .done());
    ExecutionAssert.assertThat(ExecutionTree.forExecution(pi.getId(), processEngine)).matches(
        describeExecutionTree(null).scope()
          .child("adHoc").scope().id(scope)
            .child("taskA").concurrent().noScope().up()
            .child(null).concurrent().noScope()
              .child("handled").scope().up().up()
            .child(null).scope().eventScope().up()
        .done());

    complete("handled");
    assertThat(task("taskA")).as("the child is still running").isNotNull();
    assertThat(task("after")).as("so the scope is still open").isNull();

    complete("taskA");
    assertThat(task("after")).as("the scope completes once nothing runs").isNotNull();
  }

  @Deployment(resources = EVENT_SUB_PROCESS)
  @Test
  public void aRunningHandlerKeepsTheScopeOpenWhenTheChildEndsFirst() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("nonInterruptingEventSubProcess");
    activate(scopeExecutionId(pi), "taskA");
    runtimeService.correlateMessage("notice");

    complete("taskA");
    assertThat(task("after")).as("the handler is still running").isNull();

    complete("handled");
    assertThat(task("after")).as("the scope completes once the handler has ended").isNotNull();
  }

  /**
   * A handler in a scope that has nothing running: the scope keeps its activity, and the handler's
   * end does not complete it, because a handler is not an activation.
   */
  @Deployment(resources = EVENT_SUB_PROCESS)
  @Test
  public void aHandlerInAWaitingScopeLeavesItWaiting() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("nonInterruptingEventSubProcess");
    String scope = scopeExecutionId(pi);

    runtimeService.correlateMessage("notice");

    assertThat(activityIdOf(scope)).as("the scope execution must stay on the scope").isEqualTo("adHoc");
    ActivityInstanceAssert.assertThat(runtimeService.getActivityInstance(pi.getId())).hasStructure(
        describeActivityInstanceTree(pi.getProcessDefinitionId())
          .beginScope("adHoc")
            .beginScope("onNotice")
              .activity("handled")
        .done());

    complete("handled");
    assertThat(task("after")).as("nothing was activated, so the scope waits").isNull();
    activate(scope, "taskA");
    complete("taskA");
    assertThat(task("after")).isNotNull();
  }

  /** An event handler is not a performance, as the interrupting one is not (CIB7-1892). */
  @Deployment(resources = EVENT_SUB_PROCESS)
  @Test
  public void aHandlerIsNotGathered() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("nonInterruptingEventSubProcess");
    activate(scopeExecutionId(pi), "taskA");
    runtimeService.correlateMessage("notice");

    complete("handled");
    complete("taskA");

    assertThat(runtimeService.getVariable(pi.getId(), "results")).isEqualTo(Collections.singletonList("taskA"));
  }

  @Deployment(resources = WITH_CONDITION)
  @Test
  public void aHandlerCanSatisfyTheCondition() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("nonInterruptingEventSubProcessWithCondition");
    activate(scopeExecutionId(pi), "taskA");
    runtimeService.correlateMessage("notice");

    taskService.complete(task("handled").getId(), Collections.<String, Object>singletonMap("stop", true));

    assertThat(task("after")).as("the condition holds once the handler has ended").isNotNull();
    assertThat(task("taskA")).as("and what was still running is cancelled").isNull();
  }

  @Deployment(resources = WAITING)
  @Test
  public void withoutCancellingTheScopeWaitsForARunningHandler() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("nonInterruptingEventSubProcessWaiting");
    activate(scopeExecutionId(pi), "taskA");
    runtimeService.correlateMessage("notice");

    taskService.complete(task("taskA").getId(), Collections.<String, Object>singletonMap("stop", true));
    assertThat(task("after")).as("the condition holds, but the handler is still running").isNull();

    complete("handled");
    assertThat(task("after")).as("the scope leaves when the handler has ended").isNotNull();
  }

  @Deployment(resources = EVENT_SUB_PROCESS)
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_ACTIVITY)
  @Test
  public void completingTheScopeCancelsARunningHandler() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("nonInterruptingEventSubProcess");
    String scope = scopeExecutionId(pi);
    activate(scope, "taskA");
    runtimeService.correlateMessage("notice");

    runtimeService.completeAdHocSubProcess(scope);

    assertThat(task("after")).isNotNull();
    assertThat(historic(pi, "handled").isCanceled()).as("the handler").isTrue();
    assertThat(historic(pi, "taskA").isCanceled()).as("the child").isTrue();
    for (HistoricActivityInstance instance : historyService.createHistoricActivityInstanceQuery()
        .processInstanceId(pi.getId()).unfinished().list()) {
      assertThat(instance.getActivityId()).as("nothing inside the scope is left unfinished").isEqualTo("after");
    }
  }

  @Deployment(resources = EVENT_SUB_PROCESS)
  @Test
  public void theEventSubProcessCannotBeActivated() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("nonInterruptingEventSubProcess");
    String scope = scopeExecutionId(pi);

    assertThatThrownBy(() -> runtimeService.activateAdHocSubProcessActivities(scope, Arrays.asList("onNotice")))
        .isInstanceOf(BadUserRequestException.class);
  }

  /** Every activity the handler ran belongs to the scope's activity instance in the history. */
  @Deployment(resources = EVENT_SUB_PROCESS)
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_ACTIVITY)
  @Test
  public void theHistoryPutsTheHandlerUnderTheScope() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("nonInterruptingEventSubProcess");
    activate(scopeExecutionId(pi), "taskA");
    runtimeService.correlateMessage("notice");
    complete("handled");
    complete("taskA");

    String scopeInstance = historic(pi, "adHoc").getId();
    assertThat(historic(pi, "taskA").getParentActivityInstanceId()).isEqualTo(scopeInstance);
    assertThat(historic(pi, "onNotice").getParentActivityInstanceId()).isEqualTo(scopeInstance);
    for (HistoricActivityInstance instance : historyService.createHistoricActivityInstanceQuery()
        .processInstanceId(pi.getId()).list()) {
      if (!"after".equals(instance.getActivityId())) {
        assertThat(instance.getEndTime()).as(instance.getActivityId()).isNotNull();
      }
    }
  }

  /** An interruption outranks a running handler as it outranks a running child (CIB7-2074). */
  @Deployment(resources = INTERRUPTED)
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_ACTIVITY)
  @Test
  public void anInterruptingHandlerCancelsARunningOne() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("interruptedHandler");
    activate(scopeExecutionId(pi), "taskA");
    runtimeService.correlateMessage("notice");

    runtimeService.correlateMessage("stop");

    assertThat(task("after")).as("the interruption ends the scope").isNotNull();
    assertThat(historic(pi, "handled").isCanceled()).as("the running handler").isTrue();
    assertThat(historic(pi, "taskA").isCanceled()).as("the running child").isTrue();
  }

  // ---------------------------------------------------------------- the boundary event

  @Deployment(resources = BOUNDARY_EVENT)
  @Test
  public void aBoundaryPathBesideItsActivityCanEndFirst() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("nonInterruptingBoundaryEvent");
    String scope = scopeExecutionId(pi);
    activate(scope, "work");

    runtimeService.correlateMessage("ping");

    assertThat(activityIdOf(scope)).isEqualTo("adHoc");
    ActivityInstanceAssert.assertThat(runtimeService.getActivityInstance(pi.getId())).hasStructure(
        describeActivityInstanceTree(pi.getProcessDefinitionId())
          .beginScope("adHoc")
            .activity("work")
            .activity("sideTask")
        .done());

    complete("sideTask");
    assertThat(task("after")).as("the activity is still running").isNull();
    complete("work");
    assertThat(task("after")).isNotNull();
    assertThat(runtimeService.getVariable(pi.getId(), "results"))
        .as("the side path is a branch of the activity's performance, and is gathered like one")
        .isEqualTo(Arrays.asList("sideTask", "work"));
  }

  @Deployment(resources = BOUNDARY_EVENT)
  @Test
  public void aRunningBoundaryPathKeepsTheScopeOpenWhenItsActivityEndsFirst() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("nonInterruptingBoundaryEvent");
    activate(scopeExecutionId(pi), "work");
    runtimeService.correlateMessage("ping");

    complete("work");
    assertThat(task("after")).as("the side path is still running").isNull();
    complete("sideTask");
    assertThat(task("after")).isNotNull();
  }

  /** The side path and the path of its activity meet at a join: one activation, two paths. */
  @Deployment(resources = BOUNDARY_PATH_MEETS_ITS_ACTIVITY)
  @Test
  public void aBoundaryPathMeetsThePathOfItsActivityAtAJoin() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("boundaryPathMeetsItsActivity");
    activate(scopeExecutionId(pi), "work");
    runtimeService.correlateMessage("ping");

    complete("work");
    assertThat(task("joined")).as("the side path has not arrived yet").isNull();
    complete("sideTask");
    assertThat(task("joined")).as("both paths of the activation have arrived").isNotNull();

    complete("joined");
    assertThat(task("after")).isNotNull();
  }

  /**
   * The side path meets a branch of a fork on its activity's path: still one activation, so the join
   * fires once both have arrived.
   */
  @Deployment(resources = BOUNDARY_PATH_MEETS_FORK)
  @Test
  public void aBoundaryPathMeetsABranchOfItsActivitysForkAtAJoin() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("boundaryPathMeetsFork");
    activate(scopeExecutionId(pi), "work");
    runtimeService.correlateMessage("ping");

    complete("work");
    complete("left");
    assertThat(task("joined")).as("the side path has not arrived yet").isNull();
    complete("sideTask");
    assertThat(task("joined")).as("the side path and the branch belong to one activation").isNotNull();

    complete("joined");
    complete("right");
    assertThat(task("after")).isNotNull();
  }

  /**
   * A boundary event on a branch of a fork, whose side path goes straight into the join where the
   * other branch already waits: the path carries the activation from the moment it is created.
   */
  @Deployment(resources = BOUNDARY_ON_A_BRANCH)
  @Test
  public void aBoundaryPathThatGoesStraightToAJoinMeetsTheOtherBranchThere() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("boundaryOnABranch");
    activate(scopeExecutionId(pi), "work");
    complete("work");
    complete("right");
    assertThat(task("joined")).isNull();

    runtimeService.correlateMessage("ping");
    assertThat(task("joined")).as("the side path of left belongs to the activation of work").isNotNull();

    complete("joined");
    complete("left");
    assertThat(task("after")).isNotNull();
  }

  /**
   * The start of a side path counts as a fork of its activity, so the activity's activation carries
   * a label from then on, and its path no longer meets a child activated on its own -- as after any
   * fork (CIB7-2264). Without the event, the two join.
   */
  @Deployment(resources = BOUNDARY_AND_A_SEPARATE_CHILD)
  @Test
  public void aChildWhoseBoundaryEventFiredNoLongerMeetsAChildActivatedOnItsOwn() {
    ProcessInstance quiet = runtimeService.startProcessInstanceByKey("boundaryAndASeparateChild");
    activate(scopeExecutionId(quiet), "x");
    activate(scopeExecutionId(quiet), "y");
    complete(quiet, "x");
    complete(quiet, "y");
    assertThat(taskService.createTaskQuery().processInstanceId(quiet.getId()).taskDefinitionKey("z").count())
        .as("without the event the two children join").isEqualTo(1);

    ProcessInstance pinged = runtimeService.startProcessInstanceByKey("boundaryAndASeparateChild");
    activate(scopeExecutionId(pinged), "x");
    activate(scopeExecutionId(pinged), "y");
    runtimeService.createMessageCorrelation("ping").processInstanceId(pinged.getId()).correlate();
    complete(pinged, "x");
    complete(pinged, "y");
    assertThat(taskService.createTaskQuery().processInstanceId(pinged.getId()).taskDefinitionKey("z").count())
        .as("x carries the label of its activation, y none").isZero();
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

  protected void complete(ProcessInstance pi, String key) {
    taskService.complete(taskService.createTaskQuery().processInstanceId(pi.getId()).taskDefinitionKey(key)
        .singleResult().getId());
  }

  protected HistoricActivityInstance historic(ProcessInstance pi, String activityId) {
    return historyService.createHistoricActivityInstanceQuery().processInstanceId(pi.getId())
        .activityId(activityId).singleResult();
  }
}
