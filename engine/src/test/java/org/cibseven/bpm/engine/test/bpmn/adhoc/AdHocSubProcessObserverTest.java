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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.cibseven.bpm.engine.impl.RepositoryServiceImpl;
import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocSubProcessActivityBehavior;
import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocSubProcessObserver;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.cibseven.bpm.engine.impl.pvm.delegate.ActivityExecution;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.util.PluggableProcessEngineTest;
import org.junit.jupiter.api.Test;

/**
 * What an {@link AdHocSubProcessObserver} is told: every direct child of the scope that ends, once
 * per performance and after its result is gathered, and every one removed without ending -- by the
 * scope's behaviour itself, so neither a transaction boundary after the child nor
 * {@code skipCustomListeners} hides it, and nothing that runs inside a child reaches it.
 */
public class AdHocSubProcessObserverTest extends PluggableProcessEngineTest {

  protected static final String PROCESS = "<?xml version='1.0' encoding='UTF-8'?>"
      + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
      + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
      + "             xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'"
      + "             targetNamespace='http://cibseven.org/adhoc-observer'>"
      + "  <process id='observed' isExecutable='true' camunda:historyTimeToLive='P1D'>"
      + "    <startEvent id='start' />"
      + "    <sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
      + "    <adHocSubProcess id='adHoc'>"
      + "      <extensionElements><camunda:properties>"
      + "        <camunda:property name='outputCollection' value='results' />"
      + "        <camunda:property name='outputElement' value='${execution.currentActivityId}' />"
      + "      </camunda:properties></extensionElements>"
      + "      <userTask id='task' />"
      + "      <userTask id='other' />"
      + "      <serviceTask id='async' camunda:expression='${1}' camunda:asyncAfter='true' />"
      + "      <userTask id='mi'>"
      + "        <multiInstanceLoopCharacteristics isSequential='false'>"
      + "          <loopCardinality>3</loopCardinality>"
      + "        </multiInstanceLoopCharacteristics>"
      + "      </userTask>"
      + "      <adHocSubProcess id='nested'><userTask id='inner' /></adHocSubProcess>"
      + "      <completionCondition xsi:type='tFormalExpression'>${false}</completionCondition>"
      + "    </adHocSubProcess>"
      + "    <sequenceFlow id='f2' sourceRef='adHoc' targetRef='end' />"
      + "    <endEvent id='end' />"
      + "  </process>"
      + "</definitions>";

  @Test
  public void aChildIsObservedOncePerPerformanceAfterItsResultIsGathered() {
    Recorder recorder = start();
    activate("task");
    activate("task");

    for (Task task : taskService.createTaskQuery().taskDefinitionKey("task").list()) {
      taskService.complete(task.getId());
    }

    assertThat(recorder.events).containsExactly("ended task", "ended task");
    assertThat(recorder.gatheredWhenTold)
        .as("the collection already holds the performance being reported").containsExactly(1, 2);
  }

  /** A child is whatever the scope started; what runs inside it is that child's business. */
  @Test
  public void aMultiInstanceChildAndANestedScopeAreObservedOnceEachAndNothingInsideThem() {
    Recorder recorder = start();
    activate("mi");
    activate("nested");
    runtimeService.createAdHocSubProcessActivation(scopeOf("nested")).startActivity("inner").execute();

    List<Task> instances = taskService.createTaskQuery().taskDefinitionKey("mi").list();
    assertThat(instances).hasSize(3);
    taskService.complete(instances.get(0).getId());
    taskService.complete(instances.get(1).getId());
    assertThat(recorder.events).as("the body is still running").isEmpty();
    taskService.complete(instances.get(2).getId());
    taskService.complete(taskService.createTaskQuery().taskDefinitionKey("inner").singleResult().getId());

    assertThat(recorder.events).hasSize(2);
    assertThat(recorder.events.get(0)).startsWith("ended mi");
    assertThat(recorder.events.get(1)).isEqualTo("ended nested");
  }

  @Test
  public void aChildCancelledByAModificationIsObservedAsRemovedWithoutCustomListeners() {
    Recorder recorder = start();
    activate("task");
    activate("other");
    String taskInstance = runtimeService.getActivityInstance(processInstance.getId())
        .getActivityInstances("task")[0].getId();

    runtimeService.createProcessInstanceModification(processInstance.getId())
        .cancelActivityInstance(taskInstance).execute(true, false);

    assertThat(recorder.events).containsExactly("removed task");
  }

  /** What the scope cancels on its way out goes with the scope; no child is reported for it. */
  @Test
  public void childrenCancelledAsTheScopeCompletesAreNotObserved() {
    Recorder recorder = start();
    activate("task");
    activate("other");

    runtimeService.completeAdHocSubProcess(scopeOf("adHoc"));

    testRule.assertProcessEnded(processInstance.getId());
    assertThat(recorder.events).isEmpty();
  }

  /**
   * A child whose inner flow meets the completion condition completes the scope there: its path is
   * gathered, but it never ends as a child, so no observer is told.
   */
  @Test
  public void aPathThatMeetsTheCompletionConditionIsGatheredButNotObserved() {
    Recorder recorder = start(process(""
        + "      <extensionElements><camunda:properties>"
        + "        <camunda:property name='outputCollection' value='results' />"
        + "        <camunda:property name='outputElement' value='${execution.currentActivityId}' />"
        + "      </camunda:properties></extensionElements>"
        + "      <serviceTask id='first' camunda:expression='${true}' camunda:resultVariable='done' />"
        + "      <sequenceFlow id='i1' sourceRef='first' targetRef='second' />"
        + "      <userTask id='second' />"
        + "      <completionCondition xsi:type='tFormalExpression'>"
        + "${execution.hasVariable('done')}</completionCondition>", ""));

    activate("first");

    testRule.assertProcessEnded(processInstance.getId());
    assertThat(recorder.events).isEmpty();
    assertThat((Collection<?>) historyService.createHistoricVariableInstanceQuery()
        .processInstanceId(processInstance.getId()).variableName("results").singleResult()
        .getValue()).as("the path was gathered").hasSize(1);
  }

  /**
   * A compensation handler of a child, thrown after the scope has left, runs on the event scope
   * execution that keeps the scope's compensation; it is no child of a running scope, and no
   * observer is told, also when the handler waits and ends later.
   */
  @Test
  public void aCompensationHandlerAfterTheScopeLeftIsNotObserved() {
    Recorder recorder = start(process(""
        + "      <serviceTask id='book' camunda:expression='${1}' />"
        + "      <boundaryEvent id='bookCompensation' attachedToRef='book'>"
        + "        <compensateEventDefinition /></boundaryEvent>"
        + "      <userTask id='undo' isForCompensation='true' />"
        + "      <association id='a1' associationDirection='One' sourceRef='bookCompensation'"
        + "        targetRef='undo' />",
        "    <intermediateThrowEvent id='compensate'><compensateEventDefinition />"
        + "</intermediateThrowEvent>"));

    activate("book");
    Task undo = taskService.createTaskQuery().taskDefinitionKey("undo").singleResult();
    taskService.complete(undo.getId());

    testRule.assertProcessEnded(processInstance.getId());
    assertThat(recorder.events).containsExactly("ended book");
  }

  /**
   * What a removed child's execution still holds when the observer is told: its id and its
   * activity; it has ended, has left the scope's children, and its variables are gone.
   */
  @Test
  public void aRemovedChildKeepsItsIdAndActivityButNotItsState() {
    start();
    final List<String> seen = new ArrayList<String>();
    ProcessDefinitionEntity definition = (ProcessDefinitionEntity) ((RepositoryServiceImpl)
        repositoryService).getDeployedProcessDefinition(processInstance.getProcessDefinitionId());
    ((AdHocSubProcessActivityBehavior) definition.findActivity("adHoc").getActivityBehavior())
        .addObserver(new AdHocSubProcessObserver() {
          @Override
          public void childEnded(ActivityExecution scope, ActivityExecution ended) {
          }

          @Override
          public void childRemoved(ActivityExecution scope, ActivityExecution removed) {
            ExecutionEntity child = (ExecutionEntity) removed;
            seen.add(child.getActivityId() + ", id " + (child.getId() != null)
                + ", ended " + child.isEnded()
                + ", among the scope's children " + ((ExecutionEntity) scope).getExecutions()
                    .contains(child)
                + ", variables " + child.getVariablesLocal().keySet());
          }
        });
    runtimeService.createAdHocSubProcessActivation(scopeOf("adHoc")).startActivity("task")
        .setVariable("note", "local").execute();
    activate("other");
    String taskInstance = runtimeService.getActivityInstance(processInstance.getId())
        .getActivityInstances("task")[0].getId();

    runtimeService.createProcessInstanceModification(processInstance.getId())
        .cancelActivityInstance(taskInstance).execute();

    assertThat(seen).containsExactly(
        "task, id true, ended true, among the scope's children false, variables []");
  }

  /** A modification that cancels the scope's only child cancels the scope with it: nothing is told. */
  @Test
  public void cancellingTheOnlyChildCancelsTheScopeAndIsNotObserved() {
    Recorder recorder = start();
    activate("task");
    String taskInstance = runtimeService.getActivityInstance(processInstance.getId())
        .getActivityInstances("task")[0].getId();

    runtimeService.createProcessInstanceModification(processInstance.getId())
        .cancelActivityInstance(taskInstance).execute(true, false);

    testRule.assertProcessEnded(processInstance.getId());
    assertThat(recorder.events).isEmpty();
  }

  /** An interrupting boundary event takes the scope away with its children: nothing is told. */
  @Test
  public void childrenOfAnInterruptedScopeAreNotObserved() {
    Recorder recorder = start(process(""
        + "      <userTask id='task' /><userTask id='other' />"
        + "      <completionCondition xsi:type='tFormalExpression'>${false}</completionCondition>", "")
        .replace("<endEvent id='end' />", "<endEvent id='end' />"
            + "<boundaryEvent id='stop' attachedToRef='adHoc'>"
            + "<messageEventDefinition messageRef='msgStop' /></boundaryEvent>"
            + "<sequenceFlow id='fs' sourceRef='stop' targetRef='stopped' /><endEvent id='stopped' />")
        .replace("</process>", "</process><message id='msgStop' name='stop' />"));
    activate("task");
    activate("other");

    runtimeService.correlateMessage("stop");

    testRule.assertProcessEnded(processInstance.getId());
    assertThat(recorder.events).isEmpty();
  }

  /** Deleting the process instance cancels the scope and its children: nothing is told. */
  @Test
  public void childrenOfADeletedInstanceAreNotObserved() {
    Recorder recorder = start();
    activate("task");
    activate("other");

    runtimeService.deleteProcessInstance(processInstance.getId(), "no longer needed");

    testRule.assertProcessEnded(processInstance.getId());
    assertThat(recorder.events).isEmpty();
  }

  /** The child ends where its continuation runs, behind the transaction boundary after it. */
  @Test
  public void anAsyncAfterChildIsObservedOnceItsContinuationHasRun() {
    Recorder recorder = start();
    activate("async");
    assertThat(recorder.events).as("the activity ran, the child has not ended yet").isEmpty();

    Job continuation = managementService.createJobQuery().processInstanceId(processInstance.getId())
        .singleResult();
    managementService.executeJob(continuation.getId());

    assertThat(recorder.events).containsExactly("ended async");
  }

  // --- helpers

  protected ProcessInstance processInstance;

  /** Deploys the process, attaches a recorder to the scope's behaviour, and starts an instance. */
  protected Recorder start() {
    return start(PROCESS);
  }

  /** The same for another process with the key 'observed' and an ad hoc scope 'adHoc'. */
  protected Recorder start(String process) {
    testRule.deploy(repositoryService.createDeployment().addString("observed.bpmn20.xml", process));
    processInstance = runtimeService.startProcessInstanceByKey("observed");
    ProcessDefinitionEntity definition = (ProcessDefinitionEntity) ((RepositoryServiceImpl)
        repositoryService).getDeployedProcessDefinition(processInstance.getProcessDefinitionId());
    Recorder recorder = new Recorder();
    ((AdHocSubProcessActivityBehavior) definition.findActivity("adHoc").getActivityBehavior())
        .addObserver(recorder);
    return recorder;
  }

  protected void activate(String activityId) {
    runtimeService.createAdHocSubProcessActivation(scopeOf("adHoc")).startActivity(activityId)
        .execute();
  }

  protected String scopeOf(String activityId) {
    return runtimeService.createExecutionQuery().processInstanceId(processInstance.getId())
        .activityId(activityId).list().get(0).getId();
  }

  /**
   * A process 'observed' whose ad hoc scope 'adHoc' holds {@code inside}, with {@code after}
   * between the scope and the end.
   */
  protected static String process(String inside, String after) {
    String next = after.isEmpty() ? "end" : "afterScope";
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + "             xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'"
        + "             targetNamespace='http://cibseven.org/adhoc-observer'>"
        + "  <process id='observed' isExecutable='true' camunda:historyTimeToLive='P1D'>"
        + "    <startEvent id='start' />"
        + "    <sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + "    <adHocSubProcess id='adHoc'>" + inside + "</adHocSubProcess>"
        + "    <sequenceFlow id='f2' sourceRef='adHoc' targetRef='" + next + "' />"
        + (after.isEmpty() ? "" : after.replaceFirst("id='[^']*'", "id='afterScope'")
            + "    <sequenceFlow id='f3' sourceRef='afterScope' targetRef='end' />")
        + "    <endEvent id='end' />"
        + "  </process>"
        + "</definitions>";
  }

  /** What the observer was told, in order, and how much had been gathered at the time. */
  public static class Recorder implements AdHocSubProcessObserver {
    final List<String> events = new ArrayList<String>();
    final List<Integer> gatheredWhenTold = new ArrayList<Integer>();

    @Override
    public void childEnded(ActivityExecution scopeExecution, ActivityExecution endedExecution) {
      events.add("ended " + endedExecution.getCurrentActivityId());
      Object results = scopeExecution.getVariable("results");
      gatheredWhenTold.add(results instanceof Collection ? ((Collection<?>) results).size() : 0);
    }

    @Override
    public void childRemoved(ActivityExecution scopeExecution, ActivityExecution removedExecution) {
      events.add("removed " + removedExecution.getCurrentActivityId());
    }
  }
}
