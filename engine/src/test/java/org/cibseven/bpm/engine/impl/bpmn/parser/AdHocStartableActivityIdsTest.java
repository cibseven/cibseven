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
package org.cibseven.bpm.engine.impl.bpmn.parser;

import static org.assertj.core.api.Assertions.assertThat;

import org.cibseven.bpm.engine.impl.util.xml.Element;
import org.cibseven.bpm.engine.impl.util.xml.Parse;
import org.cibseven.bpm.engine.impl.util.xml.Parser;
import org.junit.Test;

/**
 * The startable-child rule, exercised directly against the parse step.
 *
 * <p>In this package on purpose. Two parts of the rule cannot be reached through a deployment: a
 * sequence flow between ad hoc children is rejected at parse time, so no model containing one can
 * deploy, and that is precisely the input the flow-target clause exists to handle. Testing only
 * through deployments would leave live code with no coverage at all.
 */
public class AdHocStartableActivityIdsTest {

  /**
   * Parses the XML only. Deliberately not BpmnParse, whose execute() runs the whole semantic parse
   * and needs a configured process engine; all this needs is the element tree.
   */
  protected Element adHocElement(String adHocXml) {
    String xml = "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + " targetNamespace='http://cibseven.org/test'>"
        + "<process id='p' isExecutable='true'>" + adHocXml + "</process></definitions>";
    return new XmlOnlyParser().createParse().sourceString(xml).execute().getRootElement()
        .element("process").element("adHocSubProcess");
  }

  protected static class XmlOnlyParser extends Parser {
    @Override
    public Parse createParse() {
      return new XmlOnlyParse(this);
    }
  }

  protected static class XmlOnlyParse extends Parse {
    public XmlOnlyParse(Parser parser) {
      super(parser);
    }
  }

  @Test
  public void anActivityWithNoIncomingInnerFlowIsStartable() {
    assertThat(BpmnParse.startableActivityIds(adHocElement(
        "<adHocSubProcess id='adHoc'><userTask id='a'/><serviceTask id='b'/></adHocSubProcess>")))
        .containsExactly("a", "b");
  }

  /**
   * The clause that no deployment can reach: 'second' is the target of a flow from 'first', so it is
   * reached rather than started.
   */
  @Test
  public void aTargetOfAnInnerFlowIsNotStartable() {
    assertThat(BpmnParse.startableActivityIds(adHocElement(
        "<adHocSubProcess id='adHoc'>"
        + "<userTask id='first'/>"
        + "<sequenceFlow id='inner' sourceRef='first' targetRef='second'/>"
        + "<userTask id='second'/>"
        + "</adHocSubProcess>")))
        .as("a flow target is reached from its predecessor, so it cannot also be a starting point")
        .containsExactly("first");
  }

  @Test
  public void aChainOfInnerFlowsLeavesOnlyTheHeadStartable() {
    assertThat(BpmnParse.startableActivityIds(adHocElement(
        "<adHocSubProcess id='adHoc'>"
        + "<userTask id='a'/><userTask id='b'/><userTask id='c'/>"
        + "<sequenceFlow id='f1' sourceRef='a' targetRef='b'/>"
        + "<sequenceFlow id='f2' sourceRef='b' targetRef='c'/>"
        + "</adHocSubProcess>")))
        .containsExactly("a");
  }

  /**
   * Document order, which the property's contract promises. This is the assertion that catches
   * grouping the result by activity type: with a serviceTask declared first, any implementation that
   * iterates the known activity tags in an outer loop returns it second.
   */
  @Test
  public void theOrderIsDocumentOrderAndNotTypeOrder() {
    assertThat(BpmnParse.startableActivityIds(adHocElement(
        "<adHocSubProcess id='adHoc'>"
        + "<serviceTask id='first'/>"
        + "<userTask id='second'/>"
        + "<subProcess id='third'/>"
        + "<receiveTask id='fourth'/>"
        + "</adHocSubProcess>")))
        .containsExactly("first", "second", "third", "fourth");
  }

  @Test
  public void nonActivitiesAreNotStartable() {
    assertThat(BpmnParse.startableActivityIds(adHocElement(
        "<adHocSubProcess id='adHoc'>"
        + "<userTask id='a'/>"
        + "<exclusiveGateway id='gw'/>"
        + "<intermediateCatchEvent id='catch'/>"
        + "<dataObject id='data'/>"
        + "</adHocSubProcess>")))
        .as("a gateway and an intermediate event are flow nodes but not Activities")
        .containsExactly("a");
  }

  @Test
  public void activitiesWithTheirOwnActivationMechanismAreNotStartable() {
    assertThat(BpmnParse.startableActivityIds(adHocElement(
        "<adHocSubProcess id='adHoc'>"
        + "<userTask id='a'/>"
        + "<serviceTask id='compensation' isForCompensation='true'/>"
        + "<subProcess id='eventSubProcess' triggeredByEvent='true'/>"
        + "</adHocSubProcess>")))
        .as("compensation is thrown at one, a start event fires the other")
        .containsExactly("a");
  }

  @Test
  public void anEmptyScopeHasNothingStartable() {
    assertThat(BpmnParse.startableActivityIds(adHocElement("<adHocSubProcess id='adHoc'/>")))
        .isEmpty();
  }

}
