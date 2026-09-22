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


  // ---------------------------------------------------------------- FR-12


  // ---------------------------------------------------------------- FR-14, FR-16, FR-16b

  // Asserts on a historic activity instance for the cancelled child.


  // ---------------------------------------------------------------- FR-15



  // ---------------------------------------------------------------- re-activation after a lull



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
  // ---------------------------------------------------------------- FR-17


  // ---------------------------------------------------------------- async continuation


  // ---------------------------------------------------------------- synchronous child

  // Asserts on a historic variable instance, which exists only at full history. Without this the
  // test fails on a lower-history configuration instead of being skipped.

  // ---------------------------------------------------------------- nested scope child


  // ---------------------------------------------------------------- FR-13, history

  // Asserts on historic activity and process instances, so it needs activity-level history.
  // ---------------------------------------------------------------- internal state leakage

  /**
   * The other half of the tamper: surviving it is not the same as being unaffected by it. One
   * activation genuinely happened, so once nothing is active the scope must leave — even though a
   * child reset the count that records it.
   */
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
  /**
   * "Once the condition holds" is a latch, and it has to be one for completion as well as for
   * activation. With {@code cancelRemainingInstances="false"} the scope stays open while its
   * survivors finish, so the condition is evaluated again when the last one ends — and by then the
   * variable it reads may have changed back. Re-deciding at that point would park the scope forever
   * on a completion that had already been determined.
   */
  // ---------------------------------------------------------------- deletion / cleanup

  // ------------------------------------------------- constructs the parser does not reject










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

  @Test
  public void testTriggeredByEventIsRejected() {
    try {
      deploy("triggeredByEvent.bpmn20.xml");
      fail("triggeredByEvent='true' on an ad hoc sub process must be rejected at deployment");
    } catch (ParseException e) {
      testRule.assertTextPresent("triggeredByEvent='true' is not allowed", e.getMessage());
    }
  }

}
