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

import org.cibseven.bpm.engine.ParseException;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.runtime.ActivityInstance;
import org.cibseven.bpm.engine.runtime.Execution;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.util.PluggableProcessEngineTest;
import org.junit.Test;

/**
 * The driver activity — {@code camunda:property adHocDriverActivity}.
 *
 * <p>The property names the child that is re-activated whenever another child of the scope ends,
 * which is what gives a scope its next turn. The engine knows nothing about what that child does: a
 * script, a human client or an agent are equally valid drivers.
 *
 * <p>The re-activation runs inside {@code concurrentChildExecutionEnded}, that is inside a PVM
 * atomic operation while a child is ending. That method has leaked executions before and has cost
 * the scope its identity in the runtime tree — its own comments record both — so the coalescing and
 * self-activation tests here matter more than the rest and should be read first.
 */
public class AdHocSubProcessDriverTest extends PluggableProcessEngineTest {

  protected static final String DRIVEN =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessDriverTest.driven.bpmn20.xml";

  protected Task task(String key) {
    return taskService.createTaskQuery().taskDefinitionKey(key).singleResult();
  }

  protected long taskCount(String key) {
    return taskService.createTaskQuery().taskDefinitionKey(key).count();
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

  protected int activatedCount(String processInstanceId) {
    Object value = runtimeService.createVariableInstanceQuery()
        .processInstanceIdIn(processInstanceId)
        .variableName("nrOfActivatedInstances")
        .singleResult().getValue();
    return ((Number) value).intValue();
  }

  /**
   * Brings the instance into the state every test below starts from: the driver has had its first
   * turn, started one worker, and gone away again. The scope is then parked with a worker open and
   * no driver present — the only state in which a worker's end can re-activate the driver.
   */
  protected ProcessInstance startAndParkWithWorker(String workerId) {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocDriven");
    assertThat(task("driver")).as("entry activation gave the driver its first turn").isNotNull();

    runtimeService.triggerAdHocActivities(scopeExecutionId(pi.getId()),
        Collections.singletonList(workerId));
    taskService.complete(task("driver").getId());

    assertThat(task("driver")).as("the driver's own end does not bring it back").isNull();
    return pi;
  }

  // ─── the core behaviour ───────────────────────────────────────────────────────

  /** A worker ending while no driver is present is what produces the next turn. */
  @Deployment(resources = DRIVEN)
  @Test
  public void theDriverIsReactivatedWhenAnotherChildEnds() {
    ProcessInstance pi = startAndParkWithWorker("workerA");

    taskService.complete(task("workerA").getId());

    assertThat(task("driver")).as("the worker's end gave the driver a further turn").isNotNull();
    assertThat(activatedCount(pi.getId()))
        .as("driver on entry, one worker, driver again")
        .isEqualTo(3);
  }

  /**
   * A driver that re-activated itself on its own end would run without bound and there would be no
   * way to stop it from the model. The scope parks instead, and only an explicit completion — or the
   * timer boundary event a real model carries — gets it out.
   */
  @Deployment(resources = DRIVEN)
  @Test
  public void theDriverDoesNotReactivateItself() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocDriven");

    taskService.complete(task("driver").getId());

    assertThat(task("driver")).as("no second driver").isNull();
    assertThat(taskService.createTaskQuery().count()).as("and nothing else was started").isZero();
    assertThat(runtimeService.createProcessInstanceQuery()
        .processInstanceId(pi.getId()).singleResult())
        .as("the scope is parked, waiting for a completion request")
        .isNotNull();
  }

  /**
   * Three workers ending in quick succession must produce one further turn, not three. This is where
   * turn coalescing comes from, and it is decided against the execution tree rather than against
   * bookkeeping of the connector's own.
   */
  @Deployment(resources = DRIVEN)
  @Test
  public void severalChildrenEndingProduceOneFurtherTurn() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocDriven");
    String scope = scopeExecutionId(pi.getId());

    runtimeService.triggerAdHocActivities(scope, Arrays.asList("workerA", "workerB", "workerC"));
    taskService.complete(task("driver").getId());
    assertThat(task("driver")).isNull();

    taskService.complete(task("workerA").getId());
    assertThat(taskCount("driver")).as("the first end brings the driver back").isEqualTo(1);

    taskService.complete(task("workerB").getId());
    taskService.complete(task("workerC").getId());

    assertThat(taskCount("driver"))
        .as("the other two find a driver already present and add none")
        .isEqualTo(1);
    assertThat(activatedCount(pi.getId()))
        .as("driver, three workers, one further driver")
        .isEqualTo(5);
  }

  /** The same rule seen from the other side: a driver that is still open blocks a second one. */
  @Deployment(resources = DRIVEN)
  @Test
  public void aRunningDriverBlocksAFurtherOne() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocDriven");
    String scope = scopeExecutionId(pi.getId());

    runtimeService.triggerAdHocActivities(scope, Collections.singletonList("workerA"));
    taskService.complete(task("workerA").getId());

    assertThat(taskCount("driver")).as("the driver was already there").isEqualTo(1);
    assertThat(activatedCount(pi.getId())).as("no extra activation").isEqualTo(2);
  }

  /**
   * A synchronous child ends while the driver is still running, so it must not produce a turn
   * either. This is the case that made {@code startActivity} report a synchronous result
   * immediately: the agent will never get a turn for it.
   */
  @Deployment(resources = DRIVEN)
  @Test
  public void aSynchronousChildDoesNotReactivateTheRunningDriver() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocDriven");

    runtimeService.triggerAdHocActivities(scopeExecutionId(pi.getId()),
        Collections.singletonList("sync"));

    assertThat(runtimeService.getVariable(pi.getId(), "ran")).as("it ran").isEqualTo(true);
    assertThat(taskCount("driver")).as("and produced no further turn").isEqualTo(1);
    assertThat(activatedCount(pi.getId())).isEqualTo(2);
  }

  /**
   * The runtime activity instance tree must keep reporting the scope. Retaining ended children used
   * to make it report the last child that ran instead, and Cockpit reads that tree — the
   * re-activation must not bring the problem back.
   */
  @Deployment(resources = DRIVEN)
  @Test
  public void theRuntimeTreeStillReportsTheScope() {
    ProcessInstance pi = startAndParkWithWorker("workerA");
    taskService.complete(task("workerA").getId());

    ActivityInstance tree = runtimeService.getActivityInstance(pi.getId());
    ActivityInstance[] children = tree.getChildActivityInstances();

    assertThat(children).hasSize(1);
    assertThat(children[0].getActivityId()).as("the scope, not a child of it").isEqualTo("adHoc");
  }

  // ─── the transaction boundary ─────────────────────────────────────────────────

  /**
   * With {@code asyncBefore} the re-activation must produce a job rather than run the driver inside
   * the transaction that ended the worker. That is what keeps a person completing a task from
   * waiting on the driver's work, and keeps a failure in it from rolling their completion back.
   *
   * <p>Also the test that pins the activation path: over process instance modification
   * {@code isAsync} is hard-coded false, so this only holds through the activation API.
   */
  @Deployment(resources = "org/cibseven/bpm/engine/test/bpmn/adhoc/"
      + "AdHocSubProcessDriverTest.drivenAsync.bpmn20.xml")
  @Test
  public void anAsyncDriverIsReactivatedAsAJob() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocDrivenAsync");

    // Entry activation already produced a job, because the driver is asyncBefore.
    Job entryJob = managementService.createJobQuery().processInstanceId(pi.getId()).singleResult();
    assertThat(entryJob).as("the first turn is a job too").isNotNull();
    assertThat(task("driver")).as("and has not run yet").isNull();
    managementService.executeJob(entryJob.getId());
    assertThat(task("driver")).isNotNull();

    runtimeService.triggerAdHocActivities(scopeExecutionId(pi.getId()),
        Collections.singletonList("workerA"));
    taskService.complete(task("driver").getId());
    taskService.complete(task("workerA").getId());

    Job turnJob = managementService.createJobQuery().processInstanceId(pi.getId()).singleResult();
    assertThat(turnJob).as("the further turn is a job, not work done inline").isNotNull();
    assertThat(task("driver")).as("so the driver has not run yet").isNull();

    managementService.executeJob(turnJob.getId());
    assertThat(task("driver")).as("and runs once the job executor takes it").isNotNull();
  }

  // ─── unchanged without the property ───────────────────────────────────────────

  /** Every model written before this existed. Ending a child re-activates nothing. */
  @Deployment(resources = "org/cibseven/bpm/engine/test/bpmn/adhoc/"
      + "AdHocSubProcessDriverTest.noDriver.bpmn20.xml")
  @Test
  public void withoutADriverNothingIsReactivated() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocNoDriver");
    String scope = scopeExecutionId(pi.getId());

    runtimeService.triggerAdHocActivities(scope, Arrays.asList("driver", "workerA"));
    taskService.complete(task("workerA").getId());
    taskService.complete(task("driver").getId());

    assertThat(taskService.createTaskQuery().count()).as("nothing came back").isZero();
    assertThat(activatedCount(pi.getId())).as("exactly what was asked for").isEqualTo(2);
  }

  // ─── deployment refusals ──────────────────────────────────────────────────────

  /**
   * A driver without parking is refused. The scope would end as soon as the driver's first turn did,
   * so the model expresses an intention it cannot carry out.
   */
  @Test
  public void aDriverWithoutParkingIsRefused() {
    try {
      testRule.deploy("org/cibseven/bpm/engine/test/bpmn/adhoc/"
          + "AdHocSubProcessDriverTest.driverWithoutParking.bpmn20.xml");
      fail("adHocDriverActivity without explicitCompletionOnly must be refused");
    } catch (ParseException e) {
      testRule.assertTextPresent("adHocDriverActivity", e.getMessage());
      testRule.assertTextPresent("explicitCompletionOnly", e.getMessage());
      testRule.assertTextPresent("never a second turn", e.getMessage());
    }
  }

  /** An unstartable id is refused at deployment, because a driver is never an expression. */
  @Test
  public void aDriverNamingAnUnstartableActivityIsRefused() {
    try {
      testRule.deploy("org/cibseven/bpm/engine/test/bpmn/adhoc/"
          + "AdHocSubProcessDriverTest.driverUnstartable.bpmn20.xml");
      fail("a driver naming an activity that is not directly startable must be refused");
    } catch (ParseException e) {
      testRule.assertTextPresent("adHocDriverActivity", e.getMessage());
      testRule.assertTextPresent("nosuch", e.getMessage());
      testRule.assertTextPresent("not directly startable here", e.getMessage());
    }
  }
}
