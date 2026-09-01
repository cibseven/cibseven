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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.history.UserOperationLogEntry;
import org.cibseven.bpm.engine.runtime.Execution;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.util.PluggableProcessEngineTest;
import org.junit.Test;

/**
 * The ad hoc activation API on RuntimeService.
 *
 * <p>An ad hoc sub process starts nothing on entry, so without this API the only way to start a
 * child is process instance modification, which is a general-purpose engine operation rather than a
 * product surface. These tests pin the surface itself: what it returns, what it refuses, and that a
 * refusal starts nothing.
 */
public class AdHocSubProcessActivationApiTest extends PluggableProcessEngineTest {

  protected static final String RESOURCE =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessActivationApiTest.scope.bpmn20.xml";

  protected String scopeExecutionId(String processInstanceId) {
    for (Execution execution : runtimeService.createExecutionQuery()
        .processInstanceId(processInstanceId).list()) {
      if ("adHoc".equals(((org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity) execution)
          .getActivityId())) {
        return execution.getId();
      }
    }
    throw new AssertionError("no execution sitting on the ad hoc scope");
  }

  protected Task task(String key) {
    return taskService.createTaskQuery().taskDefinitionKey(key).singleResult();
  }

  // ---------------------------------------------------------------- starting children

  @Deployment(resources = RESOURCE)
  @Test
  public void startsAChildAndReturnsItsActivityInstanceId() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocActivationApi");

    List<String> ids = runtimeService.triggerAdHocActivities(
        scopeExecutionId(pi.getId()), Collections.singletonList("taskA"));

    assertThat(task("taskA")).as("the child must be started").isNotNull();
    assertThat(ids).as("one id for one activity").hasSize(1);
    assertThat(ids.get(0)).as("the id must not be null, that was the whole point").isNotNull();
    assertThat(runtimeService.getActivityInstance(pi.getId())
        .getChildActivityInstances()[0].getChildActivityInstances()[0].getId())
        .as("the returned id must be the activity instance actually created")
        .isEqualTo(ids.get(0));
  }

  @Deployment(resources = RESOURCE)
  @Test
  public void startsSeveralChildrenAndReturnsIdsInTheOrderGiven() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocActivationApi");

    List<String> ids = runtimeService.triggerAdHocActivities(
        scopeExecutionId(pi.getId()), Arrays.asList("taskB", "taskA"));

    assertThat(ids).hasSize(2);
    assertThat(taskService.createTaskQuery().processInstanceId(pi.getId()).count()).isEqualTo(2);
    assertThat(ids.get(0)).as("first id belongs to taskB, the first requested").isNotNull();
    assertThat(ids).doesNotHaveDuplicates();
  }

  /**
   * The specification allows an activity to be performed more than once, which is why the return is
   * a list rather than a map keyed by activity id.
   */
  @Deployment(resources = RESOURCE)
  @Test
  public void theSameChildCanBeStartedTwiceInOneCall() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocActivationApi");

    List<String> ids = runtimeService.triggerAdHocActivities(
        scopeExecutionId(pi.getId()), Arrays.asList("taskA", "taskA"));

    assertThat(ids).as("one id per performance").hasSize(2);
    assertThat(ids).as("two distinct performances").doesNotHaveDuplicates();
    assertThat(taskService.createTaskQuery().taskDefinitionKey("taskA").count()).isEqualTo(2);
  }

  /**
   * A synchronous child has already finished by the time the call returns, so its id cannot be read
   * off the execution afterwards: {@code leaveActivityInstance} has replaced it with the enclosing
   * instance's. The id is captured as the instance is entered instead, so it is the child's own.
   *
   * <p>This is the case that caught the first implementation returning the scope's id dressed up as
   * the child's, so it is asserted against what history recorded rather than merely against non-null.
   */
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_ACTIVITY)
  @Deployment(resources = RESOURCE)
  @Test
  public void returnsTheChildsOwnIdForASynchronousChild() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocActivationApi");
    String scope = scopeExecutionId(pi.getId());
    String scopeActivityInstanceId = runtimeService.getActivityInstance(pi.getId())
        .getChildActivityInstances()[0].getId();

    List<String> ids = runtimeService.triggerAdHocActivities(scope, Collections.singletonList("sync"));

    System.out.println("[API-SYNC] returned=" + ids.get(0) + " scope=" + scopeActivityInstanceId);

    assertThat(ids).hasSize(1);
    assertThat(ids.get(0))
        .as("the scope's own activity instance id would be the wrong answer")
        .isNotEqualTo(scopeActivityInstanceId);
    assertThat(ids.get(0))
        .as("it must be the id history recorded for that activity")
        .isEqualTo(historyService.createHistoricActivityInstanceQuery()
            .processInstanceId(pi.getId()).activityId("sync").singleResult().getId());
  }

  /**
   * The same, for a synchronous child that is also a scope because it carries an io mapping. A scope
   * activity is entered by a freshly created execution rather than by the one the command made, so
   * this is the case that needs the capture to travel with the new execution.
   */
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_ACTIVITY)
  @Deployment(resources = RESOURCE)
  @Test
  public void returnsTheChildsOwnIdForASynchronousScopeChild() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocActivationApi");

    List<String> ids = runtimeService.triggerAdHocActivities(
        scopeExecutionId(pi.getId()), Collections.singletonList("syncWithIoMapping"));

    assertThat(ids.get(0))
        .as("a scope child's instance is entered by a different execution, so the capture must travel")
        .isEqualTo(historyService.createHistoricActivityInstanceQuery()
            .processInstanceId(pi.getId()).activityId("syncWithIoMapping").singleResult().getId());
  }

  /**
   * A mixed batch: a synchronous child first, then a waiting one. The synchronous child leaves
   * nothing active behind it, so if children are started one at a time the scope completes after the
   * first and the second is started from an execution that has already ended.
   */
  @Deployment(resources = RESOURCE)
  @Test
  public void aMixedBatchStartsEveryChild() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocActivationApi");

    List<String> ids = runtimeService.triggerAdHocActivities(
        scopeExecutionId(pi.getId()), Arrays.asList("sync", "taskA"));

    assertThat(ids).as("one id per requested activity").hasSize(2);
    assertThat(task("taskA"))
        .as("the waiting child must be started even though the synchronous one ran first")
        .isNotNull();
  }

  /**
   * An activity with loop characteristics is wrapped by the parser in a generated multi-instance
   * body, and the body is the direct child of the scope. Starting it by the id the model uses has to
   * find that body, or a legal model is unstartable.
   */
  @Deployment(resources = RESOURCE)
  @Test
  public void startsAMultiInstanceChild() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocActivationApi");

    List<String> ids = runtimeService.triggerAdHocActivities(
        scopeExecutionId(pi.getId()), Collections.singletonList("multi"));

    assertThat(ids).hasSize(1);
    assertThat(taskService.createTaskQuery().taskDefinitionKey("multi").count())
        .as("loop cardinality of two means two tasks")
        .isEqualTo(2);
  }

  // ---------------------------------------------------------------- per-activation variables

  /**
   * Variables go on the started child, not on the scope keyed by activity id. The runtime variable
   * table is unique on scope and name, so the latter would make a second performance overwrite the
   * first.
   */
  @Deployment(resources = RESOURCE)
  @Test
  public void variablesAreLocalToTheStartedChild() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocActivationApi");
    Map<String, Map<String, Object>> perActivity = new HashMap<>();
    perActivity.put("taskA", Collections.<String, Object>singletonMap("assignedTo", "alice"));

    runtimeService.triggerAdHocActivities(
        scopeExecutionId(pi.getId()), Collections.singletonList("taskA"), perActivity);

    assertThat(runtimeService.getVariable(pi.getId(), "assignedTo"))
        .as("a per-activation variable must not leak to the process instance scope")
        .isNull();
    assertThat(taskService.getVariable(task("taskA").getId(), "assignedTo"))
        .as("it must be visible to the child it was given for")
        .isEqualTo("alice");
  }

  // ---------------------------------------------------------------- what it refuses

  @Deployment(resources = RESOURCE)
  @Test
  public void refusesAnActivityThatIsNotDirectlyStartable() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocActivationApi");
    try {
      runtimeService.triggerAdHocActivities(
          scopeExecutionId(pi.getId()), Collections.singletonList("waitForMsg"));
      fail("an intermediate catch event is not an activity, so it must not be startable");
    } catch (BadUserRequestException e) {
      assertThat(e.getMessage()).contains("waitForMsg");
      assertThat(e.getMessage()).as("the message should say what IS startable")
          .contains("taskA");
    }
  }

  @Deployment(resources = RESOURCE)
  @Test
  public void refusesAnUnknownActivity() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocActivationApi");
    try {
      runtimeService.triggerAdHocActivities(
          scopeExecutionId(pi.getId()), Collections.singletonList("noSuchThing"));
      fail("an unknown activity id must be refused");
    } catch (BadUserRequestException e) {
      assertThat(e.getMessage()).contains("noSuchThing");
    }
  }

  /** All or nothing: one bad id in a batch must start none of the others. */
  @Deployment(resources = RESOURCE)
  @Test
  public void aBatchWithOneBadIdStartsNothing() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocActivationApi");
    try {
      runtimeService.triggerAdHocActivities(
          scopeExecutionId(pi.getId()), Arrays.asList("taskA", "noSuchThing"));
      fail("the batch must be refused");
    } catch (BadUserRequestException e) {
      // expected
    }
    assertThat(taskService.createTaskQuery().processInstanceId(pi.getId()).count())
        .as("taskA must not have been started despite being valid")
        .isZero();
  }

  @Deployment(resources = RESOURCE)
  @Test
  public void refusesAnExecutionThatIsNotAnAdHocScope() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocActivationApi");
    try {
      runtimeService.triggerAdHocActivities(pi.getId(), Collections.singletonList("taskA"));
      fail("the process instance execution is not the ad hoc scope execution");
    } catch (BadUserRequestException e) {
      assertThat(e.getMessage()).contains("not an ad hoc sub process");
    }
  }

  @Test
  public void refusesMissingArguments() {
    try {
      runtimeService.triggerAdHocActivities(null, Collections.singletonList("taskA"));
      fail("executionId is required");
    } catch (BadUserRequestException e) {
      // expected
    }
    try {
      runtimeService.triggerAdHocActivities("someId", Collections.<String>emptyList());
      fail("activityIds must not be empty");
    } catch (BadUserRequestException e) {
      // expected
    }
  }

  // ---------------------------------------------------------------- completing the scope

  @Deployment(resources = RESOURCE)
  @Test
  public void completesTheScopeAndCancelsWhatIsStillRunning() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocActivationApi");
    String scope = scopeExecutionId(pi.getId());
    runtimeService.triggerAdHocActivities(scope, Arrays.asList("taskA", "taskB"));
    assertThat(taskService.createTaskQuery().processInstanceId(pi.getId()).count()).isEqualTo(2);

    runtimeService.completeAdHocSubProcess(scope);

    assertThat(taskService.createTaskQuery().processInstanceId(pi.getId()).count())
        .as("running children must be cancelled")
        .isZero();
    testRule.assertProcessEnded(pi.getId());
  }

  /** A scope with nothing running must still be completable, which is the case that strands today. */
  @Deployment(resources = RESOURCE)
  @Test
  public void completesAnIdleScope() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocActivationApi");

    runtimeService.completeAdHocSubProcess(scopeExecutionId(pi.getId()));

    testRule.assertProcessEnded(pi.getId());
  }

  @Deployment(resources = RESOURCE)
  @Test
  public void completesWithVariables() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocActivationApi");

    runtimeService.completeAdHocSubProcess(scopeExecutionId(pi.getId()),
        Collections.<String, Object>singletonMap("outcome", "done"));

    assertThat(historyService.createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("outcome").count())
        .as("variables set on completion must be recorded")
        .isEqualTo(1);
  }

  @Deployment(resources = RESOURCE)
  @Test
  public void refusesCompletingSomethingThatIsNotAnAdHocScope() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocActivationApi");
    try {
      runtimeService.completeAdHocSubProcess(pi.getId());
      fail("the process instance execution is not the ad hoc scope execution");
    } catch (BadUserRequestException e) {
      assertThat(e.getMessage()).contains("not an ad hoc sub process");
    }
  }

  // ---------------------------------------------------------------- audit

  /**
   * A call that starts arbitrary activities inside a running instance needs an audit record. The
   * reference implementation writes none.
   */
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  @Deployment(resources = RESOURCE)
  @Test
  public void writesAUserOperationLogEntry() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocActivationApi");
    identityService.setAuthenticatedUserId("alice");
    try {
      runtimeService.triggerAdHocActivities(
          scopeExecutionId(pi.getId()), Collections.singletonList("taskA"));
    } finally {
      identityService.clearAuthentication();
    }

    // One row per recorded property, sharing an operation id, so the property is what to count.
    assertThat(historyService.createUserOperationLogQuery()
        .processInstanceId(pi.getId()).userId("alice")
        .operationType(UserOperationLogEntry.OPERATION_TYPE_MODIFY_PROCESS_INSTANCE)
        .property("activityIds").count())
        .as("logged as a modification, not as Activate, which means unsuspending an instance")
        .isEqualTo(1);
    assertThat(historyService.createUserOperationLogQuery()
        .processInstanceId(pi.getId())
        .operationType(UserOperationLogEntry.OPERATION_TYPE_ACTIVATE).count())
        .as("an audit consumer must not read ad hoc activation as an unsuspension")
        .isZero();
  }

  /**
   * The completion entry has to name the ad hoc scope. Completing leaves the scope synchronously, so
   * an entry written afterwards would record whatever the execution moved on to.
   */
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  @Deployment(resources = RESOURCE)
  @Test
  public void completionIsAuditedAgainstTheAdHocScope() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocActivationApi");
    String scope = scopeExecutionId(pi.getId());
    identityService.setAuthenticatedUserId("alice");
    try {
      runtimeService.completeAdHocSubProcess(scope);
    } finally {
      identityService.clearAuthentication();
    }

    List<org.cibseven.bpm.engine.history.UserOperationLogEntry> entries =
        historyService.createUserOperationLogQuery()
            .processInstanceId(pi.getId()).userId("alice").property("adHocActivityId").list();
    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).getNewValue())
        .as("must name the scope that was completed, not what the execution went on to")
        .isEqualTo("adHoc");
  }

}
