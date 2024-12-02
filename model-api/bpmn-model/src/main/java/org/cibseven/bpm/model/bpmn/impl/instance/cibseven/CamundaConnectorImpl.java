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
package org.cibseven.bpm.model.bpmn.impl.instance.cibseven;

import static org.cibseven.bpm.model.bpmn.impl.BpmnModelConstants.CAMUNDA_ELEMENT_CONNECTOR;
import static org.cibseven.bpm.model.bpmn.impl.BpmnModelConstants.CAMUNDA_NS;

import org.cibseven.bpm.model.bpmn.impl.instance.BpmnModelElementInstanceImpl;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaConnector;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaConnectorId;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaInputOutput;
import org.cibseven.bpm.model.xml.ModelBuilder;
import org.cibseven.bpm.model.xml.impl.instance.ModelTypeInstanceContext;
import org.cibseven.bpm.model.xml.type.ModelElementTypeBuilder;
import org.cibseven.bpm.model.xml.type.ModelElementTypeBuilder.ModelTypeInstanceProvider;
import org.cibseven.bpm.model.xml.type.child.ChildElement;
import org.cibseven.bpm.model.xml.type.child.SequenceBuilder;

/**
 * The BPMN connector camunda extension element
 *
 * @author Sebastian Menski
 */
public class CamundaConnectorImpl extends BpmnModelElementInstanceImpl implements CamundaConnector {

  protected static ChildElement<CamundaConnectorId> camundaConnectorIdChild;
  protected static ChildElement<CamundaInputOutput> camundaInputOutputChild;

  public static void registerType(ModelBuilder modelBuilder) {
    ModelElementTypeBuilder typeBuilder = modelBuilder.defineType(CamundaConnector.class, CAMUNDA_ELEMENT_CONNECTOR)
      .namespaceUri(CAMUNDA_NS)
      .instanceProvider(new ModelTypeInstanceProvider<CamundaConnector>() {
        public CamundaConnector newInstance(ModelTypeInstanceContext instanceContext) {
          return new CamundaConnectorImpl(instanceContext);
        }
      });

    SequenceBuilder sequenceBuilder = typeBuilder.sequence();

    camundaConnectorIdChild = sequenceBuilder.element(CamundaConnectorId.class)
      .required()
      .build();

    camundaInputOutputChild = sequenceBuilder.element(CamundaInputOutput.class)
      .build();

    typeBuilder.build();
  }

  public CamundaConnectorImpl(ModelTypeInstanceContext instanceContext) {
    super(instanceContext);
  }

  public CamundaConnectorId getCamundaConnectorId() {
    return camundaConnectorIdChild.getChild(this);
  }

  public void setCamundaConnectorId(CamundaConnectorId camundaConnectorId) {
    camundaConnectorIdChild.setChild(this, camundaConnectorId);
  }

  public CamundaInputOutput getCamundaInputOutput() {
    return camundaInputOutputChild.getChild(this);
  }

  public void setCamundaInputOutput(CamundaInputOutput camundaInputOutput) {
    camundaInputOutputChild.setChild(this, camundaInputOutput);
  }

}
