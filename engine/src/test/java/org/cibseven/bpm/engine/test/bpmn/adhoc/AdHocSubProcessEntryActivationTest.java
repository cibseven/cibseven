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
package org.cibseven.bpm.engine.test.bpmn.adhoc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.cibseven.bpm.engine.ParseException;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.util.PluggableProcessEngineTest;
import org.junit.Test;

/**
 * Declarative activation on entry — CIB7-1891.
 *
 * <p>Until this existed, entering an ad hoc scope started nothing and every activity had to arrive
 * through the activation API. Other implementations of this element can name the activities to start
 * on entry, and the gap was invisible to the conformance suite because it is a vendor capability the
 * specification does not mention at all.
 *
 * <p>Carried as {@code camunda:property activeActivityIds} rather than a new namespace, per
 * CIB7-1890. Extension properties are read at parse time and never become process variables, so a
 * child of the scope cannot rewrite which activities its own scope starts.
 */
public class AdHocSubProcessEntryActivationTest extends PluggableProcessEngineTest {

  protected Task task(String key) {
    return taskService.createTaskQuery().taskDefinitionKey(key).singleResult();
  }

  @Deployment
  @Test
  public void entryActivationStartsTheNamedActivities() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocEntry");

    assertThat(task("taskA")).as("named on entry").isNotNull();
    assertThat(task("taskB")).as("named on entry").isNotNull();
    assertThat(task("taskC")).as("not named, so not started").isNull();

    // The scope is still an ad hoc scope: the API can add more on top of the entry list.
    runtimeService.createProcessInstanceModification(pi.getId())
        .startBeforeActivity("taskC").execute();
    assertThat(task("taskC")).as("entry activation and the API compose").isNotNull();
  }

  @Deployment
  @Test
  public void entryActivationFromAnExpression() {
    Map<String, Object> vars = new HashMap<String, Object>();
    vars.put("starters", Arrays.asList("taskB"));
    runtimeService.startProcessInstanceByKey("adHocEntryExpr", vars);

    // A collection from process data, not a literal — which is the reason this is an expression and
    // not a static attribute.
    assertThat(task("taskB")).isNotNull();
    assertThat(task("taskA")).isNull();
  }

  /**
   * A literal list is checked when the model deploys, not when it runs. Neither reference does this —
   * both find a bad id only at runtime, per instance.
   */
  @Test
  public void entryActivationRejectsAnUnstartableId() {
    try {
      testRule.deploy("org/cibseven/bpm/engine/test/bpmn/adhoc/"
          + "AdHocSubProcessEntryActivationTest.entryActivationRejectsAnUnstartableId.bpmn20.xml");
      fail("a literal entry list naming an unstartable activity must be rejected at deployment");
    } catch (ParseException e) {
      testRule.assertTextPresent("activeActivityIds names [nosuch]", e.getMessage());
      testRule.assertTextPresent("not directly startable here", e.getMessage());
    }
  }

  /**
   * Two synchronous children named on entry: the scope enters, runs both, finds nothing active with
   * something activated, and leaves — all within the start call. Correct under the no-condition
   * completion rule, and worth pinning because it is the one shape where entry activation makes a
   * scope invisible at runtime.
   */
  @Deployment
  @Test
  public void entryActivationCompletesImmediatelyWithSynchronousChildren() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocEntrySync");

    assertThat(task("afterAdHoc")).as("the scope entered, ran both, and left").isNotNull();
    taskService.complete(task("afterAdHoc").getId());
    testRule.assertProcessEnded(pi.getId());
  }

  /**
   * A completion condition already true on entry starts nothing, and does not throw.
   *
   * <p>The refusal CIB7-1850 added throws, and throwing from scope entry would fail the process start
   * outright — a model whose condition happens to be true on entry would be undeployable in practice.
   * Starting nothing leaves the scope waiting, which is what CIB7-1851 decided such a scope does.
   */
  @Deployment
  @Test
  public void entryActivationSkippedWhenTheConditionAlreadyHolds() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocEntryTrue");

    assertThat(task("taskA")).as("nothing started while the condition already holds").isNull();
    assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).count())
        .as("and the start did not fail: the scope is waiting")
        .isEqualTo(1);

    // Recoverable exactly as CIB7-1851 describes.
    runtimeService.completeAdHocSubProcess(
        runtimeService.createExecutionQuery().processInstanceId(pi.getId())
            .activityId("adHoc").singleResult().getId());
    testRule.assertProcessEnded(pi.getId());
  }

  /**
   * Another implementation of this element spells the same extension property
   * `activeTasksCollection`, and reads it from `camunda:property` exactly as we do — so a model
   * written against that spelling runs here unchanged.
   *
   * <p>Our own name is preferred and documented, because the specification calls the children
   * Activities and a Task is only one kind of Activity: a child may be a sub-process, which
   * "Tasks" would misdescribe. The alias exists for portability, not as an endorsement of the name.
   */
  @Deployment
  @Test
  public void theReferenceSpellingIsAcceptedAsAnAlias() {
    runtimeService.startProcessInstanceByKey("adHocEntryAlias");

    assertThat(task("taskA")).isNotNull();
    assertThat(task("taskB")).isNotNull();
    assertThat(task("taskC")).isNull();
  }

  /**
   * A multi-instance child is started through its generated body, not through the nested activity.
   *
   * <p>Loop characteristics make the parser wrap the activity, so a recursive lookup finds the nested
   * one and executing that bypasses the body that owns the loop — the cardinality would be ignored
   * and one instance would run instead of two. Only direct children are considered and the body is
   * resolved by name, the same resolution the activation API makes.
   */
  @Deployment
  @Test
  public void aMultiInstanceChildIsStartedThroughItsBody() {
    runtimeService.startProcessInstanceByKey("adHocEntryMi");

    assertThat(taskService.createTaskQuery().taskDefinitionKey("looped").count())
        .as("the loop ran, so its cardinality was honoured")
        .isEqualTo(2);
  }

  /**
   * An earlier child in the entry list can run synchronously and satisfy the completion condition,
   * which ends the scope and cancels the children created but not yet started.
   *
   * <p>Starting one of those afterwards fails on flush with an OptimisticLockingException, which
   * reads as a transient concurrency problem and is not — it is deterministic. The same hole existed
   * in the activation API and is fixed there too, in the same commit.
   */
  @Deployment
  @Test
  public void entryActivationStopsWhenAnEarlierChildEndsTheScope() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocBatchCond");

    // The scope entered, ran 'sync', the condition fired, and it left — without trying to start the
    // task it had already cancelled.
    testRule.assertProcessEnded(pi.getId());
    assertThat(task("taskA")).isNull();
  }

  /**
   * The same hole through the activation API, which had it before entry activation existed and which
   * this commit fixes there too.
   *
   * <p>Requesting a synchronous child and a user task in one batch: the synchronous one runs during
   * the call, satisfies the completion condition, and the scope leaves — cancelling the user task it
   * had created but not yet started. Before the fix the loop then started that cancelled execution
   * and the call failed on flush with {@code OptimisticLockingException: Execution of 'INSERT
   * TaskEntity' failed}, which reads as transient and is not.
   */
  @Deployment
  @Test
  public void theActivationApiAlsoStopsWhenAnEarlierChildEndsTheScope() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocApiBatchCond");
    String scope = runtimeService.createExecutionQuery()
        .processInstanceId(pi.getId()).activityId("adHoc").singleResult().getId();

    java.util.List<String> ids =
        runtimeService.triggerAdHocActivities(scope, Arrays.asList("sync", "taskA"));

    // Contract: an entry is null exactly where the activity was not started.
    assertThat(ids).hasSize(2);
    assertThat(ids.get(0)).as("the synchronous child ran and has an id").isNotNull();
    assertThat(ids.get(1)).as("the second was cancelled before it could start").isNull();

    testRule.assertProcessEnded(pi.getId());
    assertThat(task("taskA")).isNull();
  }

  /** A model with no entry property behaves exactly as before: entering starts nothing. */
  @Deployment(resources =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessScenarioTest.testTwoConcurrentChildren.bpmn20.xml")
  @Test
  public void withoutThePropertyEnteringStartsNothing() {
    runtimeService.startProcessInstanceByKey("adHocConcurrent");
    assertThat(taskService.createTaskQuery().count()).isZero();
  }

  /** An expression resolving to nothing starts nothing, rather than failing. */
  @Deployment(resources =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessEntryActivationTest.entryActivationFromAnExpression.bpmn20.xml")
  @Test
  public void anEmptyEntryListStartsNothing() {
    Map<String, Object> vars = new HashMap<String, Object>();
    vars.put("starters", Arrays.asList());
    runtimeService.startProcessInstanceByKey("adHocEntryExpr", vars);
    assertThat(taskService.createTaskQuery().count()).isZero();
  }

  /** An expression naming an unstartable activity can only be caught when it is evaluated. */
  @Deployment(resources =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessEntryActivationTest.entryActivationFromAnExpression.bpmn20.xml")
  @Test
  public void anExpressionNamingAnUnstartableIdFailsAtRuntime() {
    Map<String, Object> vars = new HashMap<String, Object>();
    vars.put("starters", Arrays.asList("nosuch"));
    try {
      runtimeService.startProcessInstanceByKey("adHocEntryExpr", vars);
      fail("an expression naming an unstartable activity must fail");
    } catch (ProcessEngineException e) {
      testRule.assertTextPresent("activeActivityIds names [nosuch]", e.getMessage());
    }
  }

}
