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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.repository.Deployment;
import org.cibseven.bpm.engine.runtime.ActivityInstance;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.util.PluggableProcessEngineTest;
import org.cibseven.bpm.model.bpmn.AdHocOrdering;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.cibseven.bpm.model.bpmn.instance.AdHocSubProcess;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Investigative probes for the step-by-step review. Each test prints what the engine actually does and
 * asserts the behaviour the specification or the PRD requires, so a failure names a gap.
 *
 * <p>Not the feature's suite — see AdHocSubProcessScenarioTest for that.
 *
 * <p>Some tests here are {@code @Ignore}d. Each asserts behaviour the specification or the
 * requirements call for and the engine does not implement yet, and each names the sub-task that turns
 * it green; that sub-task's fix removes its {@code @Ignore}. Do not weaken an assertion to make one
 * pass, and do not delete one as unsupported: either documents the defect instead of catching it.
 */
public class AdHocSubProcessReviewProbeTest extends PluggableProcessEngineTest {

  protected static final String SPEC = "org/cibseven/bpm/engine/test/bpmn/adhoc/spec/";

  protected List<String> toClean = new ArrayList<String>();

  @After
  public void cleanUp() {
    for (String id : toClean) {
      repositoryService.deleteDeployment(id, true);
    }
    toClean.clear();
  }

  protected void activate(String pi, String... ids) {
    org.cibseven.bpm.engine.runtime.ProcessInstanceModificationBuilder b =
        runtimeService.createProcessInstanceModification(pi);
    for (String id : ids) {
      b.startBeforeActivity(id);
    }
    b.execute();
  }

  protected Task task(String key) {
    return taskService.createTaskQuery().taskDefinitionKey(key).singleResult();
  }

  protected long running(String pi) {
    return runtimeService.createProcessInstanceQuery().processInstanceId(pi).count();
  }

  protected String deploySpec(String resource) {
    Deployment d = repositoryService.createDeployment().addClasspathResource(SPEC + resource).deploy();
    toClean.add(d.getId());
    return d.getId();
  }

  protected Map<String, Object> vars(String k, Object v) {
    Map<String, Object> m = new HashMap<String, Object>();
    m.put(k, v);
    return m;
  }

  // ============================================================ STEP 4

  /** D1: after a synchronous child ends, does the scope still describe itself? */
  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void probeD1Mechanism() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("probeD1");

    activate(pi.getId(), "taskA");
    ActivityInstance before = runtimeService.getActivityInstance(pi.getId()).getChildActivityInstances()[0];
    System.out.println("[S4] after activating taskA  : activityId=" + before.getActivityId()
        + " type=" + before.getActivityType());

    activate(pi.getId(), "sync");
    ActivityInstance after = runtimeService.getActivityInstance(pi.getId()).getChildActivityInstances()[0];
    System.out.println("[S4] after sync ran and ended: activityId=" + after.getActivityId()
        + " type=" + after.getActivityType());
    System.out.println("[S4] executions now          : "
        + runtimeService.createExecutionQuery().processInstanceId(pi.getId()).count());

    assertThat(after.getActivityId())
        .as("the ad hoc scope must keep describing itself after a child ends")
        .isEqualTo("adHoc");
  }

  // ============================================================ STEP 5

  /** D6a: a completion condition already true on entry. */
  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void probeConditionNotEvaluatedOnEntry() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("probeAlwaysTrue");
    System.out.println("[S5] condition ${true}, running instances after entry = " + running(pi.getId()));
    // DECIDED (CIB7-1851): no evaluation on entry. Evaluating on entry moves an
    // unresolvable-condition failure all the way to process start -- measured on another
    // implementation that does evaluate there. The scope waits instead; a performer ends it with
    // completeAdHocSubProcess. Disclosed in the release notes.
    assertThat(running(pi.getId()))
        .as("the condition is not evaluated on entry, so the scope waits even when it already holds")
        .isEqualTo(1);
  }

  /** D6b: is the condition watched, or only polled when a child ends? */
  @org.cibseven.bpm.engine.test.Deployment(resources =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessReviewProbeTest.probeD1Mechanism.bpmn20.xml")
  @Test
  public void probeConditionNotWatchedOnExternalVariableSet() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("probeD1");
    activate(pi.getId(), "taskA");

    // Satisfy the condition from outside the scope, with a child still active.
    runtimeService.setVariable(pi.getId(), "enough", true);

    System.out.println("[S5] condition satisfied externally; tasks still active = "
        + taskService.createTaskQuery().processInstanceId(pi.getId()).count()
        + ", running = " + running(pi.getId()));

    // DECIDED (CIB7-1851): the condition is polled when a child ends, never watched. Registering a
    // variable listener on every ad hoc scope was judged not worth its cost now that the case is
    // recoverable through completeAdHocSubProcess. Disclosed in the release notes.
    assertThat(taskService.createTaskQuery().processInstanceId(pi.getId()).count())
        .as("an externally satisfied condition is not noticed, so the active child keeps running")
        .isEqualTo(1);
  }

  // ============================================================ STEP 6

  /**
   * A known trap: cancelling survivors that carry io-mappings.
   * Interrupt alone leaves variables pointing at a scope execution being deleted.
   */
  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void probeCancelRemainingWithIoMappedChildren() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("probeIoCancel");
    activate(pi.getId(), "survivor", "trigger");
    assertThat(taskService.createTaskQuery().processInstanceId(pi.getId()).count()).isEqualTo(2);

    try {
      taskService.complete(task("trigger").getId(), vars("enough", true));
      System.out.println("[S6] io-mapped cancellation OK; running = " + running(pi.getId())
          + ", tasks = " + taskService.createTaskQuery().processInstanceId(pi.getId()).count());
    } catch (ProcessEngineException e) {
      System.out.println("[S6] io-mapped cancellation FAILED: " + e.getClass().getSimpleName()
          + ": " + e.getMessage());
      throw e;
    }

    assertThat(running(pi.getId()))
        .as("the scope must leave after cancelling io-mapped survivors")
        .isZero();
  }

  /** FR-15 / N25: with cancelRemainingInstances=false, may further children still be activated? */
  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void probeCancelFalseFurtherActivation() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("probeCancelFalse");
    activate(pi.getId(), "slow");
    runtimeService.setVariable(pi.getId(), "enough", true);

    boolean activated;
    try {
      activate(pi.getId(), "extra");
      activated = task("extra") != null;
    } catch (ProcessEngineException e) {
      activated = false;
      System.out.println("[S6] further activation refused: " + e.getMessage());
    }
    System.out.println("[S6] cancelRemainingInstances=false, condition true -> further activation "
        + (activated ? "ALLOWED" : "refused"));

    // FR-15 holds even here, where no child has ended and nothing recorded the condition: activation
    // evaluates it on the spot, purely to refuse. That is not a completion point -- the scope is not
    // ended by this evaluation -- so the polled semantics settled in CIB7-1851 are unaffected.
    assertThat(activated)
        .as("FR-15: once the condition holds, the scope must start no further activities")
        .isFalse();
  }

  /**
   * A child writes camunda:outputParameter and the completion condition reads it. The output has to
   * reach the ad hoc scope execution, or a condition written the way the specification describes
   * completion can never become true.
   */
  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void probeChildOutputReachesTheCondition() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("probeChildOutput");
    activate(pi.getId(), "taskA");
    taskService.complete(task("taskA").getId());

    System.out.println("[S24] running=" + running(pi.getId())
        + " approved visible on the instance=" + runtimeService.createVariableInstanceQuery()
            .processInstanceIdIn(pi.getId()).variableName("approved").count());

    assertThat(running(pi.getId()))
        .as("a completion condition reading a child's output parameter must be able to fire")
        .isZero();
  }

  // ============================================================ STEP 7

  /** Spec p.181: Gateway MAY be used inside an ad hoc sub-process. */
  @Ignore("CIB7-1882 - BPMN 2.0.0 section 10.3.5 permits a Gateway inside the scope, but we reject the inner sequence flows a gateway needs. CIB7-1852 decided to defer that capability rather than decline it, so supporting the flows is tracked in CIB7-1882 and turns this green with it")
  @Test
  public void probeGatewayInsideScopeDeploys() {
    deploySpec("gatewayInside.bpmn20.xml");
    System.out.println("[S7] gateway inside scope: DEPLOYED");
    assertThat(repositoryService.createProcessDefinitionQuery()
        .processDefinitionKey("specGateway").count()).isEqualTo(1);
  }

  /** Spec p.181: Intermediate Event MAY be used inside. */
  @Test
  public void probeIntermediateEventInsideScopeDeploys() {
    deploySpec("intermediateEventInside.bpmn20.xml");
    System.out.println("[S7] intermediate catch event inside scope: DEPLOYED");
    assertThat(repositoryService.createProcessDefinitionQuery()
        .processDefinitionKey("specIntermediate").count()).isEqualTo(1);
  }

  /** Spec p.181: Data Object MAY be used inside. */
  @Test
  public void probeDataObjectInsideScopeDeploys() {
    deploySpec("dataObjectInside.bpmn20.xml");
    System.out.println("[S7] data object inside scope: DEPLOYED");
    assertThat(repositoryService.createProcessDefinitionQuery()
        .processDefinitionKey("specDataObject").count()).isEqualTo(1);
  }

  /** Spec p.181: "The list of BPMN elements that MUST be used in an Ad-Hoc Sub-Process: Activity." */
  @Test
  public void probeChildlessScopeIsRejected() {
    try {
      deploySpec("childlessScope.bpmn20.xml");
      fail("spec p.181 — Activity MUST be used in an ad hoc sub-process, so this must be rejected");
    } catch (ProcessEngineException e) {
      System.out.println("[S7] childless scope rejected: " + e.getMessage());
      // Asserting the diagnostic, not merely that something was thrown. Accepting any
      // ProcessEngineException would let this probe pass on an unrelated parse error and report
      // the childless rule as covered when it is not.
      assertThat(e.getMessage())
          .as("must be rejected for having no activity, not for some other reason")
          .contains("at least one activity is required");
    }
  }

  // ============================================================ STEP 8

  /** Spec Table 10.22 / 10.33: ordering defaults to Parallel. */
  @Test
  public void probeOrderingDefaultsToParallel() throws Exception {
    String xml = "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + " targetNamespace='http://cibseven.org/probe'>"
        + "<process id='p' isExecutable='true'>"
        + "<adHocSubProcess id='adHoc'><userTask id='a'/></adHocSubProcess>"
        + "</process></definitions>";
    AdHocSubProcess adHoc = (AdHocSubProcess) Bpmn
        .readModelFromStream(new ByteArrayInputStream(xml.getBytes("UTF-8")))
        .getModelElementById("adHoc");

    System.out.println("[S8] ordering absent -> getOrdering() = " + adHoc.getOrdering());

    assertThat(adHoc.getOrdering())
        .as("spec Table 10.33: ordering has default=\"Parallel\"")
        .isEqualTo(AdHocOrdering.Parallel);
  }

  /** Round-trip must preserve an explicit Sequential, whatever the engine does with it. */
  @Test
  public void probeSequentialSurvivesRoundTrip() throws Exception {
    String xml = "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + " targetNamespace='http://cibseven.org/probe'>"
        + "<process id='p' isExecutable='true'>"
        + "<adHocSubProcess id='adHoc' ordering='Sequential'><userTask id='a'/></adHocSubProcess>"
        + "</process></definitions>";
    BpmnModelInstance parsed = Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes("UTF-8")));
    assertThat(((AdHocSubProcess) parsed.getModelElementById("adHoc")).getOrdering())
        .as("reading an explicit Sequential")
        .isEqualTo(AdHocOrdering.Sequential);

    // Write it back out and read that, which is what "survives a round trip" has to mean. Reading
    // once would let a serializer that drops the attribute pass, and an attribute silently lost on
    // write is exactly the regression this guards.
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Bpmn.writeModelToStream(out, parsed);
    AdHocSubProcess reread = (AdHocSubProcess) Bpmn
        .readModelFromStream(new ByteArrayInputStream(out.toByteArray()))
        .getModelElementById("adHoc");

    System.out.println("[S8] ordering='Sequential' survived a write and re-read -> " + reread.getOrdering());
    assertThat(reread.getOrdering())
        .as("an explicit Sequential must survive being written out and read back")
        .isEqualTo(AdHocOrdering.Sequential);
  }

  /**
   * The other default in Table 10.33, guarded on its own so it cannot regress unnoticed while the
   * ordering default is still being settled. This one already works.
   */
  @Test
  public void probeCancelRemainingInstancesDefaultsToTrue() throws Exception {
    String xml = "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + " targetNamespace='http://cibseven.org/probe'>"
        + "<process id='p' isExecutable='true'>"
        + "<adHocSubProcess id='adHoc'><userTask id='a'/></adHocSubProcess>"
        + "</process></definitions>";
    AdHocSubProcess adHoc = (AdHocSubProcess) Bpmn
        .readModelFromStream(new ByteArrayInputStream(xml.getBytes("UTF-8")))
        .getModelElementById("adHoc");

    assertThat(adHoc.isCancelRemainingInstances())
        .as("spec Table 10.33: cancelRemainingInstances has default=\"true\"")
        .isTrue();
  }

}
