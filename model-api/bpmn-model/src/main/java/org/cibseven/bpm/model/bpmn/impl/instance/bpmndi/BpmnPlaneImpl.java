/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
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
package org.cibseven.bpm.model.bpmn.impl.instance.bpmndi;

import org.cibseven.bpm.model.bpmn.impl.instance.di.PlaneImpl;
import org.cibseven.bpm.model.bpmn.instance.BaseElement;
import org.cibseven.bpm.model.bpmn.instance.bpmndi.BpmnPlane;
import org.cibseven.bpm.model.bpmn.instance.di.Plane;
import org.cibseven.bpm.model.xml.ModelBuilder;
import org.cibseven.bpm.model.xml.impl.instance.ModelTypeInstanceContext;
import org.cibseven.bpm.model.xml.type.ModelElementTypeBuilder;
import org.cibseven.bpm.model.xml.type.reference.AttributeReference;

import static org.cibseven.bpm.model.bpmn.impl.BpmnModelConstants.BPMNDI_ATTRIBUTE_BPMN_ELEMENT;
import static org.cibseven.bpm.model.bpmn.impl.BpmnModelConstants.BPMNDI_ELEMENT_BPMN_PLANE;
import static org.cibseven.bpm.model.bpmn.impl.BpmnModelConstants.BPMNDI_NS;
import static org.cibseven.bpm.model.xml.type.ModelElementTypeBuilder.ModelTypeInstanceProvider;

/**
 * The BPMNDI BPMNPlane element
 *
 * @author Sebastian Menski
 */
public class BpmnPlaneImpl extends PlaneImpl implements BpmnPlane {

  protected static AttributeReference<BaseElement> bpmnElementAttribute;

  public static void registerType(ModelBuilder modelBuilder) {
    ModelElementTypeBuilder typeBuilder = modelBuilder.defineType(BpmnPlane.class, BPMNDI_ELEMENT_BPMN_PLANE)
      .namespaceUri(BPMNDI_NS)
      .extendsType(Plane.class)
      .instanceProvider(new ModelTypeInstanceProvider<BpmnPlane>() {
        public BpmnPlane newInstance(ModelTypeInstanceContext instanceContext) {
          return new BpmnPlaneImpl(instanceContext);
        }
      });

    bpmnElementAttribute = typeBuilder.stringAttribute(BPMNDI_ATTRIBUTE_BPMN_ELEMENT)
      .qNameAttributeReference(BaseElement.class)
      .build();

    typeBuilder.build();
  }

  public BpmnPlaneImpl(ModelTypeInstanceContext instanceContext) {
    super(instanceContext);
  }

  public BaseElement getBpmnElement() {
    return bpmnElementAttribute.getReferenceTargetElement(this);
  }

  public void setBpmnElement(BaseElement bpmnElement) {
    bpmnElementAttribute.setReferenceTargetElement(this, bpmnElement);
  }
}
