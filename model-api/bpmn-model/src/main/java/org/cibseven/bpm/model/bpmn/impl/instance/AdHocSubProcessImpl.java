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
package org.cibseven.bpm.model.bpmn.impl.instance;

import static org.cibseven.bpm.model.bpmn.impl.BpmnModelConstants.BPMN20_NS;
import static org.cibseven.bpm.model.bpmn.impl.BpmnModelConstants.BPMN_ATTRIBUTE_CANCEL_REMAINING_INSTANCES;
import static org.cibseven.bpm.model.bpmn.impl.BpmnModelConstants.BPMN_ATTRIBUTE_ORDERING;
import static org.cibseven.bpm.model.bpmn.impl.BpmnModelConstants.BPMN_ELEMENT_AD_HOC_SUB_PROCESS;

import org.cibseven.bpm.model.bpmn.AdHocOrdering;
import org.cibseven.bpm.model.bpmn.instance.AdHocSubProcess;
import org.cibseven.bpm.model.bpmn.instance.CompletionCondition;
import org.cibseven.bpm.model.bpmn.instance.SubProcess;
import org.cibseven.bpm.model.xml.ModelBuilder;
import org.cibseven.bpm.model.xml.impl.instance.ModelTypeInstanceContext;
import org.cibseven.bpm.model.xml.type.ModelElementTypeBuilder;
import org.cibseven.bpm.model.xml.type.ModelElementTypeBuilder.ModelTypeInstanceProvider;
import org.cibseven.bpm.model.xml.type.attribute.Attribute;
import org.cibseven.bpm.model.xml.type.child.ChildElement;
import org.cibseven.bpm.model.xml.type.child.SequenceBuilder;

/**
 * The BPMN 2.0 adHocSubProcess element.
 */
public class AdHocSubProcessImpl extends SubProcessImpl implements AdHocSubProcess {

  protected static Attribute<AdHocOrdering> orderingAttribute;
  protected static Attribute<Boolean> cancelRemainingInstancesAttribute;
  protected static ChildElement<CompletionCondition> completionConditionChild;

  public static void registerType(ModelBuilder modelBuilder) {
    ModelElementTypeBuilder typeBuilder = modelBuilder.defineType(AdHocSubProcess.class, BPMN_ELEMENT_AD_HOC_SUB_PROCESS)
      .namespaceUri(BPMN20_NS)
      .extendsType(SubProcess.class)
      .instanceProvider(new ModelTypeInstanceProvider<AdHocSubProcess>() {
        public AdHocSubProcess newInstance(ModelTypeInstanceContext instanceContext) {
          return new AdHocSubProcessImpl(instanceContext);
        }
      });

    // BPMN 2.0.0 Table 10.22 and the normative schema in Table 10.33 both give 'ordering' the
    // default Parallel, so an absent attribute means Parallel and not "unspecified". Declared the
    // same way as cancelRemainingInstances below, so the default lives in one place (AD-7).
    orderingAttribute = typeBuilder.namedEnumAttribute(BPMN_ATTRIBUTE_ORDERING, AdHocOrdering.class)
      .defaultValue(AdHocOrdering.Parallel)
      .build();

    // The XSD declares default="true". A Java boolean primitive would default to false,
    // so this default is declared once, here, and the parser reads it from the model (AD-7).
    cancelRemainingInstancesAttribute = typeBuilder.booleanAttribute(BPMN_ATTRIBUTE_CANCEL_REMAINING_INSTANCES)
      .defaultValue(true)
      .build();

    SequenceBuilder sequenceBuilder = typeBuilder.sequence();

    completionConditionChild = sequenceBuilder.element(CompletionCondition.class)
      .minOccurs(0)
      .maxOccurs(1)
      .build();

    typeBuilder.build();
  }

  public AdHocSubProcessImpl(ModelTypeInstanceContext context) {
    super(context);
  }

  @Override
  public AdHocOrdering getOrdering() {
    return orderingAttribute.getValue(this);
  }

  @Override
  public void setOrdering(AdHocOrdering ordering) {
    orderingAttribute.setValue(this, ordering);
  }

  @Override
  public boolean isCancelRemainingInstances() {
    return cancelRemainingInstancesAttribute.getValue(this);
  }

  @Override
  public void setCancelRemainingInstances(boolean cancelRemainingInstances) {
    cancelRemainingInstancesAttribute.setValue(this, cancelRemainingInstances);
  }

  @Override
  public CompletionCondition getCompletionCondition() {
    return completionConditionChild.getChild(this);
  }

  @Override
  public void setCompletionCondition(CompletionCondition completionCondition) {
    completionConditionChild.setChild(this, completionCondition);
  }

}
