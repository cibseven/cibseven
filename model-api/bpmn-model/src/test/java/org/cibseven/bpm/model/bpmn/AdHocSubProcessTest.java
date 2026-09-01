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
package org.cibseven.bpm.model.bpmn;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collection;

import org.cibseven.bpm.model.bpmn.instance.AdHocSubProcess;
import org.cibseven.bpm.model.bpmn.instance.BaseElement;
import org.cibseven.bpm.model.bpmn.instance.CompletionCondition;
import org.cibseven.bpm.model.bpmn.instance.SequenceFlow;
import org.cibseven.bpm.model.bpmn.instance.ServiceTask;
import org.cibseven.bpm.model.bpmn.instance.UserTask;
import org.cibseven.bpm.model.bpmn.instance.bpmndi.BpmnShape;
import org.cibseven.bpm.model.bpmn.instance.dc.Bounds;
import org.junit.Test;

/**
 * The adHocSubProcess element and its builder.
 *
 * <p>This is the model API's only coverage of these types, and clirr freezes their signatures
 * permanently once released, so what is asserted here is what we are committing to.
 */
public class AdHocSubProcessTest {

  protected static final String XMLNS =
      "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
      + " xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'"
      + " targetNamespace='http://cibseven.org/test'>";

  protected AdHocSubProcess read(String adHocElement) {
    String xml = "<?xml version='1.0' encoding='UTF-8'?>" + XMLNS
        + "<process id='p' isExecutable='true'>" + adHocElement + "</process></definitions>";
    return (AdHocSubProcess) Bpmn.readModelFromStream(
        new ByteArrayInputStream(xml.getBytes())).getModelElementById("adHoc");
  }

  protected BpmnModelInstance roundTrip(BpmnModelInstance model) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Bpmn.writeModelToStream(out, model);
    return Bpmn.readModelFromStream(new ByteArrayInputStream(out.toByteArray()));
  }

  // ------------------------------------------------------------------ attribute defaults

  @Test
  public void orderingDefaultsToParallel() {
    // BPMN 2.0.0 Table 10.22 and the normative schema in Table 10.33 both give ordering the
    // default Parallel, so absence is Parallel and not "unspecified".
    assertThat(read("<adHocSubProcess id='adHoc'><userTask id='a'/></adHocSubProcess>").getOrdering())
        .isEqualTo(AdHocOrdering.Parallel);
  }

  @Test
  public void orderingIsReadAndWritten() {
    AdHocSubProcess adHoc = read("<adHocSubProcess id='adHoc' ordering='Sequential'>"
        + "<userTask id='a'/></adHocSubProcess>");
    assertThat(adHoc.getOrdering()).isEqualTo(AdHocOrdering.Sequential);

    adHoc.setOrdering(AdHocOrdering.Parallel);
    assertThat(adHoc.getOrdering()).isEqualTo(AdHocOrdering.Parallel);
  }

  @Test
  public void cancelRemainingInstancesDefaultsToTrue() {
    assertThat(read("<adHocSubProcess id='adHoc'><userTask id='a'/></adHocSubProcess>")
        .isCancelRemainingInstances()).isTrue();
  }

  @Test
  public void cancelRemainingInstancesIsReadAndWritten() {
    AdHocSubProcess adHoc = read("<adHocSubProcess id='adHoc' cancelRemainingInstances='false'>"
        + "<userTask id='a'/></adHocSubProcess>");
    assertThat(adHoc.isCancelRemainingInstances()).isFalse();

    adHoc.setCancelRemainingInstances(true);
    assertThat(adHoc.isCancelRemainingInstances()).isTrue();
  }

  @Test
  public void completionConditionIsReadAndWritten() {
    AdHocSubProcess adHoc = read("<adHocSubProcess id='adHoc'><userTask id='a'/>"
        + "<completionCondition xsi:type='tFormalExpression'>${done}</completionCondition>"
        + "</adHocSubProcess>");
    assertThat(adHoc.getCompletionCondition().getTextContent()).isEqualTo("${done}");
  }

  @Test
  public void noCompletionConditionReadsAsNull() {
    // minOccurs="0" in the normative schema
    assertThat(read("<adHocSubProcess id='adHoc'><userTask id='a'/></adHocSubProcess>")
        .getCompletionCondition()).isNull();
  }

  // ------------------------------------------------------------------ the builder

  @Test
  public void builderCreatesTheElementInTheFlow() {
    BpmnModelInstance model = Bpmn.createProcess("p")
        .startEvent("start")
        .adHocSubProcess("adHoc")
        .endEvent("end")
        .done();

    AdHocSubProcess adHoc = (AdHocSubProcess) model.getModelElementById("adHoc");
    assertThat(adHoc).isNotNull();
    assertThat(adHoc.getIncoming()).hasSize(1);
    assertThat(adHoc.getOutgoing()).hasSize(1);
  }

  @Test
  public void builderSetsTheAdHocAttributes() {
    BpmnModelInstance model = Bpmn.createProcess("p")
        .startEvent()
        .adHocSubProcess("adHoc")
        .ordering(AdHocOrdering.Parallel)
        .cancelRemainingInstances(false)
        .completionCondition("${approved}")
        .endEvent()
        .done();

    AdHocSubProcess adHoc = (AdHocSubProcess) model.getModelElementById("adHoc");
    assertThat(adHoc.getOrdering()).isEqualTo(AdHocOrdering.Parallel);
    assertThat(adHoc.isCancelRemainingInstances()).isFalse();
    assertThat(adHoc.getCompletionCondition().getTextContent()).isEqualTo("${approved}");
  }

  @Test
  public void builderAddsDisconnectedChildren() {
    AdHocSubProcessBuilderHolder holder = new AdHocSubProcessBuilderHolder();
    BpmnModelInstance model = build(holder);

    AdHocSubProcess adHoc = (AdHocSubProcess) model.getModelElementById("adHoc");
    assertThat(adHoc.getChildElementsByType(UserTask.class)).hasSize(1);
    assertThat(adHoc.getChildElementsByType(ServiceTask.class)).hasSize(1);

    // BPMN 2.0.0 section 10.3.5: the children have "no REQUIRED sequence relationships" and are
    // "generally disconnected from each other". child() must not create any flow.
    Collection<SequenceFlow> innerFlows = adHoc.getChildElementsByType(SequenceFlow.class);
    assertThat(innerFlows).as("child() must not connect the activities it adds").isEmpty();

    assertThat(holder.userTask.getName()).isEqualTo("Task A");
  }

  /** Holds the children so the test can assert on them after the chain has moved on. */
  protected static class AdHocSubProcessBuilderHolder {
    UserTask userTask;
    ServiceTask serviceTask;
  }

  protected BpmnModelInstance build(AdHocSubProcessBuilderHolder holder) {
    org.cibseven.bpm.model.bpmn.builder.AdHocSubProcessBuilder adHoc =
        Bpmn.createProcess("p").startEvent().adHocSubProcess("adHoc");

    holder.userTask = adHoc.child(UserTask.class, "taskA");
    holder.userTask.setName("Task A");
    holder.serviceTask = adHoc.child(ServiceTask.class, "taskB");

    return adHoc.endEvent().done();
  }

  @Test
  public void completionConditionSetTwiceReplacesIt() {
    // The schema allows at most one completionCondition, so a second call has to update the
    // expression rather than append a second element and make the model invalid.
    BpmnModelInstance model = Bpmn.createProcess("p")
        .startEvent()
        .adHocSubProcess("adHoc")
        .completionCondition("${first}")
        .completionCondition("${second}")
        .endEvent()
        .done();

    AdHocSubProcess adHoc = (AdHocSubProcess) model.getModelElementById("adHoc");
    assertThat(adHoc.getChildElementsByType(CompletionCondition.class))
        .as("a second completionCondition must replace the first, not be appended")
        .hasSize(1);
    assertThat(adHoc.getCompletionCondition().getTextContent()).isEqualTo("${second}");
  }

  @Test
  public void childrenArePlacedInsideTheScopeAndDoNotOverlap() {
    // createBpmnShape sets a size but leaves the position at the origin, so an unplaced child sits
    // at (0,0), outside the scope, stacked on every other child. A model that cannot be opened in a
    // modeller is not much use.
    AdHocSubProcessBuilderHolder holder = new AdHocSubProcessBuilderHolder();
    BpmnModelInstance model = build(holder);

    AdHocSubProcess adHoc = (AdHocSubProcess) model.getModelElementById("adHoc");
    Bounds scope = shapeOf(model, adHoc).getBounds();
    Bounds a = shapeOf(model, holder.userTask).getBounds();
    Bounds b = shapeOf(model, holder.serviceTask).getBounds();

    for (Bounds child : new Bounds[] { a, b }) {
      assertThat(child.getX()).as("child must start inside the scope").isGreaterThan(scope.getX());
      assertThat(child.getY()).as("child must start inside the scope").isGreaterThan(scope.getY());
      assertThat(child.getX() + child.getWidth())
          .as("child must end inside the scope").isLessThanOrEqualTo(scope.getX() + scope.getWidth());
      assertThat(child.getY() + child.getHeight())
          .as("child must end inside the scope").isLessThanOrEqualTo(scope.getY() + scope.getHeight());
    }

    assertThat(a.getX() + a.getWidth())
        .as("the two children must not overlap")
        .isLessThanOrEqualTo(b.getX());
  }

  protected BpmnShape shapeOf(BpmnModelInstance model, BaseElement element) {
    for (BpmnShape shape : model.getModelElementsByType(BpmnShape.class)) {
      if (element.equals(shape.getBpmnElement())) {
        return shape;
      }
    }
    throw new AssertionError("no BPMNShape for " + element.getId());
  }

  @Test
  public void builtModelSurvivesARoundTrip() {
    BpmnModelInstance model = Bpmn.createProcess("p")
        .startEvent()
        .adHocSubProcess("adHoc")
        .ordering(AdHocOrdering.Sequential)
        .cancelRemainingInstances(false)
        .completionCondition("${approved}")
        .endEvent()
        .done();

    AdHocSubProcess adHoc = (AdHocSubProcess) roundTrip(model).getModelElementById("adHoc");
    assertThat(adHoc.getOrdering()).isEqualTo(AdHocOrdering.Sequential);
    assertThat(adHoc.isCancelRemainingInstances()).isFalse();
    assertThat(adHoc.getCompletionCondition().getTextContent()).isEqualTo("${approved}");
  }

  @Test
  public void completionConditionIsSerialisedLast() {
    // The normative schema extends tSubProcess, so the extension particle follows every flow
    // element. A completionCondition written before them fails XSD validation with
    // cvc-complex-type.2.4.d, which is easy to produce by hand and has cost time before.
    AdHocSubProcessBuilderHolder holder = new AdHocSubProcessBuilderHolder();
    org.cibseven.bpm.model.bpmn.builder.AdHocSubProcessBuilder adHoc =
        Bpmn.createProcess("p").startEvent().adHocSubProcess("adHoc");
    holder.userTask = adHoc.child(UserTask.class, "taskA");
    BpmnModelInstance model = adHoc.completionCondition("${done}").endEvent().done();

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Bpmn.writeModelToStream(out, model);
    String xml = new String(out.toByteArray());

    int task = xml.indexOf("taskA");
    int condition = xml.indexOf("completionCondition");
    assertThat(task).isGreaterThan(-1);
    assertThat(condition)
        .as("completionCondition must be serialised after the flow elements")
        .isGreaterThan(task);
  }

  @Test
  public void isASubProcess() {
    // tAdHocSubProcess extends tSubProcess, and code that walks sub processes must see it.
    AdHocSubProcess adHoc = read("<adHocSubProcess id='adHoc'><userTask id='a'/></adHocSubProcess>");
    assertThat(adHoc).isInstanceOf(org.cibseven.bpm.model.bpmn.instance.SubProcess.class);
    assertThat(adHoc.triggeredByEvent()).isFalse();
  }

  @Test
  public void unknownOrderingIsRejected() {
    try {
      read("<adHocSubProcess id='adHoc' ordering='Whenever'><userTask id='a'/></adHocSubProcess>")
          .getOrdering();
      org.junit.Assert.fail("an ordering outside the enum must not be accepted");
    } catch (RuntimeException e) {
      // the enum is Parallel or Sequential, case-sensitive
    }
  }

  @Test
  public void completionConditionTypeIsAnExpression() {
    AdHocSubProcess adHoc = read("<adHocSubProcess id='adHoc'><userTask id='a'/>"
        + "<completionCondition>${done}</completionCondition></adHocSubProcess>");
    CompletionCondition condition = adHoc.getCompletionCondition();
    assertThat(condition).isInstanceOf(org.cibseven.bpm.model.bpmn.instance.Expression.class);
  }

}
