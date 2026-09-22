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

import java.util.Collections;
import java.util.List;

import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParse;
import org.cibseven.bpm.engine.runtime.Execution;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.util.PluggableProcessEngineTest;
import org.junit.jupiter.api.Test;

/**
 * CIB7-1892. Gathering each performance's result into a collection.
 *
 * <p>The specification allows an activity to be performed more than once, and an ad hoc child is
 * deliberately not a variable scope, so its output mapping lands above the scope where a completion
 * condition can read it. The two together mean the second performance overwrites the first, silently.
 * A scalar variable has one slot; repeated performance needs more than one.
 *
 * <p>So the scope may name a variable to gather into and an expression saying what to gather, and
 * each completed child appends one entry. The scalar keeps behaving exactly as before -- this is
 * additive, and a model that does not ask for it is unaffected.
 */
public class AdHocSubProcessOutputAggregationTest extends PluggableProcessEngineTest {

  protected static final String GATHER =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessOutputAggregationTest.gather.bpmn20.xml";
  protected static final String EVENT_SUB_PROCESS =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessOutputAggregationTest.eventSubProcess.bpmn20.xml";
  protected static final String SELECTIVE =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessOutputAggregationTest.selective.bpmn20.xml";
  protected static final String ATTRIBUTION =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessOutputAggregationTest.attribution.bpmn20.xml";
  protected static final String CONDITION =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessOutputAggregationTest.condition.bpmn20.xml";

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

  /** One performance of {@code activityId}, carrying its own input, completed if it waits. */
  protected void perform(ProcessInstance pi, String activityId, String value) {
    runtimeService.activateAdHocSubProcessActivities(scopeExecutionId(pi.getId()),
        Collections.singletonList(activityId),
        Collections.singletonMap(activityId, Collections.<String, Object>singletonMap("in", value)));
    Task task = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
    if (task != null) {
      taskService.complete(task.getId());
    }
  }

  @SuppressWarnings("unchecked")
  protected List<Object> gathered(ProcessInstance pi) {
    return (List<Object>) runtimeService.getVariable(pi.getId(), "results");
  }

  // ---------------------------------------------------------------- the defect it answers

  /**
   * The whole of CIB7-1892: two performances of one child, and both results survive.
   */
  @Deployment(resources = GATHER)
  @Test
  public void everyPerformanceOfTheSameChildIsGathered() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocGather");

    perform(pi, "tool", "A");
    perform(pi, "tool", "B");

    assertThat(gathered(pi)).as("one entry per performance, in the order performed")
        .containsExactly("A", "B");
  }

  /**
   * And the scalar keeps doing what it always did. Gathering is additive: it adds a second place for
   * the value, it does not move the value.
   */
  @Deployment(resources = GATHER)
  @Test
  public void theScalarVariableStillHoldsTheLastResult() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocGather");

    perform(pi, "tool", "A");
    perform(pi, "tool", "B");

    assertThat(runtimeService.getVariable(pi.getId(), "result")).isEqualTo("B");
  }

  /**
   * A child that is an embedded sub process ends by a different path in the engine than a plain
   * concurrent child does, and gathering has to happen on both or the results of whole shapes of
   * child go missing.
   */
  @Deployment(resources = GATHER)
  @Test
  public void aChildThatIsASubProcessIsGatheredToo() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocGather");

    perform(pi, "tool", "A");
    perform(pi, "sub", "B");

    assertThat(gathered(pi)).containsExactly("A", "B");
  }

  /**
   * The results outlive the scope. They are written where an ordinary variable written from the
   * scope goes -- above it -- rather than local to the scope execution, which dies with it.
   */
  @Deployment(resources = GATHER)
  @Test
  public void whatWasGatheredSurvivesTheScope() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocGather");

    perform(pi, "tool", "A");
    perform(pi, "tool", "B");
    runtimeService.completeAdHocSubProcess(scopeExecutionId(pi.getId()));

    testRule.assertProcessEnded(pi.getId());
    assertThat(historyService.createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("results").singleResult().getValue())
        .isEqualTo(java.util.Arrays.asList("A", "B"));
  }

  /**
   * Gathering happens before the completion condition is consulted, so the condition can be written
   * against what has been gathered -- which is the natural way to say "enough".
   */
  @Deployment(resources = CONDITION)
  @Test
  public void theCompletionConditionCanReadWhatHasBeenGathered() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocGatherCondition");

    perform(pi, "tool", "A");
    assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).count())
        .as("one result is not enough").isEqualTo(1L);

    perform(pi, "tool", "B");

    testRule.assertProcessEnded(pi.getId());
  }

  /**
   * An event handler is not a performance, and must not be gathered.
   *
   * <p>An interrupting event sub process is the one thing inside the scope that ends through the
   * engine's other child-end callback, so gathering there would have been easy to add and wrong: the
   * element expression would be evaluated against whatever the last real performance left behind,
   * and the same value would be appended twice. The parser already keeps such a sub process out of
   * the startable set for the same reason -- it has its own way in.
   */
  @Deployment(resources = EVENT_SUB_PROCESS)
  @Test
  public void anEventHandlerIsNotAPerformanceAndIsNotGathered() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocGatherEventSub");

    perform(pi, "tool", "A");
    assertThat(gathered(pi)).containsExactly("A");

    // the handler interrupts the scope and then completes; nothing it does is a performance
    runtimeService.correlateMessage("theMessage");
    Task handled = taskService.createTaskQuery().processInstanceId(pi.getId())
        .taskDefinitionKey("handled").singleResult();
    assertThat(handled).as("the handler must have started").isNotNull();
    taskService.complete(handled.getId());

    assertThat(historyService.createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("results").singleResult().getValue())
        .as("still one entry: the handler added nothing")
        .isEqualTo(java.util.Collections.singletonList("A"));
  }

  // ------------------------------------------------- one expression, many children

  /**
   * The expression is evaluated against the child that ended, so it can say which one it is.
   *
   * <p>One expression serves every child of the scope, which is the awkward part of gathering here:
   * an ad hoc child is not a variable scope, so the expression reads variables that every child
   * writes to. Putting the ending child in reach is what makes one expression enough.
   */
  @Deployment(resources = ATTRIBUTION)
  @Test
  public void theExpressionCanTellWhichChildEnded() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocGatherAttribution");

    perform(pi, "tool", null);
    perform(pi, "other", null);

    assertThat(gathered(pi)).as("each entry names its own performance")
        .containsExactly("tool", "other");
  }

  /**
   * A null result adds nothing, which is how a model gathers some children and not others.
   *
   * <p>Without this the feature had a measured flaw: a child that writes nothing still produced an
   * entry, and the entry held whatever the previous performance left. Two results where one was
   * produced is the same class of wrong answer the whole ticket is about.
   */
  @Deployment(resources = SELECTIVE)
  @Test
  public void anExpressionThatYieldsNullContributesNothing() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocGatherSelective");

    perform(pi, "tool", "A");
    perform(pi, "other", null);
    perform(pi, "tool", "B");

    assertThat(gathered(pi)).as("only the child the expression selects")
        .containsExactly("A", "B");
  }

  // ---------------------------------------------------------------- what it refuses

  @Test
  public void aCollectionWithoutAnElementIsRejected() {
    assertRefused("adHocGather-collectionOnly", "outputElement");
  }

  @Test
  public void anElementWithoutACollectionIsRejected() {
    assertRefused("adHocGather-elementOnly", "outputCollection");
  }

  protected void assertRefused(String fixture, String missing) {
    try {
      repositoryService.createDeployment()
          .addClasspathResource("org/cibseven/bpm/engine/test/bpmn/adhoc/invalid/"
              + fixture + ".bpmn20.xml")
          .deploy();
      fail("expected the deployment to be refused");
    } catch (org.cibseven.bpm.engine.ProcessEngineException e) {
      assertThat(e.getMessage()).contains(missing);
      assertThat(e.getMessage()).as("the message must say why one alone is useless")
          .contains("silently gather nothing");
    }
  }

  /** The property names are part of the contract, so they are pinned rather than spelled twice. */
  @Test
  public void thePropertyNamesAreTheOnesTheManualDocuments() {
    assertThat(BpmnParse.AD_HOC_OUTPUT_COLLECTION_PROPERTY).isEqualTo("outputCollection");
    assertThat(BpmnParse.AD_HOC_OUTPUT_ELEMENT_PROPERTY).isEqualTo("outputElement");
  }
}
