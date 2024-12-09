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
package org.cibseven.bpm.model.bpmn.impl.instance;

import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.cibseven.bpm.model.bpmn.builder.ExclusiveGatewayBuilder;
import org.cibseven.bpm.model.bpmn.instance.ExclusiveGateway;
import org.cibseven.bpm.model.bpmn.instance.Gateway;
import org.cibseven.bpm.model.bpmn.instance.SequenceFlow;
import org.cibseven.bpm.model.xml.ModelBuilder;
import org.cibseven.bpm.model.xml.impl.instance.ModelTypeInstanceContext;
import org.cibseven.bpm.model.xml.type.ModelElementTypeBuilder;
import org.cibseven.bpm.model.xml.type.reference.AttributeReference;

import static org.cibseven.bpm.model.bpmn.impl.BpmnModelConstants.*;
import static org.cibseven.bpm.model.xml.type.ModelElementTypeBuilder.ModelTypeInstanceProvider;

/**
 * The BPMN exclusiveGateway element
 *
 * @author Sebastian Menski
 */
public class ExclusiveGatewayImpl extends GatewayImpl implements ExclusiveGateway {

  protected static AttributeReference<SequenceFlow> defaultAttribute;

  public static void registerType(ModelBuilder modelBuilder) {
    ModelElementTypeBuilder typeBuilder = modelBuilder.defineType(ExclusiveGateway.class, BPMN_ELEMENT_EXCLUSIVE_GATEWAY)
      .namespaceUri(BPMN20_NS)
      .extendsType(Gateway.class)
      .instanceProvider(new ModelTypeInstanceProvider<ExclusiveGateway>() {
        public ExclusiveGateway newInstance(ModelTypeInstanceContext instanceContext) {
          return new ExclusiveGatewayImpl(instanceContext);
        }
      });

    defaultAttribute = typeBuilder.stringAttribute(BPMN_ATTRIBUTE_DEFAULT)
      .idAttributeReference(SequenceFlow.class)
      .build();

    typeBuilder.build();
  }

  public ExclusiveGatewayImpl(ModelTypeInstanceContext context) {
    super(context);
  }

  @Override
  public ExclusiveGatewayBuilder builder() {
    return new ExclusiveGatewayBuilder((BpmnModelInstance) modelInstance, this);
  }

  public SequenceFlow getDefault() {
    return defaultAttribute.getReferenceTargetElement(this);
  }

  public void setDefault(SequenceFlow defaultFlow) {
    defaultAttribute.setReferenceTargetElement(this, defaultFlow);
  }
}
