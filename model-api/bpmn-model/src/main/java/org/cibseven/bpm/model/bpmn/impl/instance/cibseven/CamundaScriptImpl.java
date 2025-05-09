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

import org.cibseven.bpm.model.bpmn.impl.instance.BpmnModelElementInstanceImpl;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaScript;
import org.cibseven.bpm.model.xml.ModelBuilder;
import org.cibseven.bpm.model.xml.impl.instance.ModelTypeInstanceContext;
import org.cibseven.bpm.model.xml.type.ModelElementTypeBuilder;
import org.cibseven.bpm.model.xml.type.ModelElementTypeBuilder.ModelTypeInstanceProvider;
import org.cibseven.bpm.model.xml.type.attribute.Attribute;

import static org.cibseven.bpm.model.bpmn.impl.BpmnModelConstants.CAMUNDA_ATTRIBUTE_RESOURCE;
import static org.cibseven.bpm.model.bpmn.impl.BpmnModelConstants.CAMUNDA_ATTRIBUTE_SCRIPT_FORMAT;
import static org.cibseven.bpm.model.bpmn.impl.BpmnModelConstants.CAMUNDA_ELEMENT_SCRIPT;
import static org.cibseven.bpm.model.bpmn.impl.BpmnModelConstants.CAMUNDA_NS;

/**
 * The BPMN script camunda extension element
 *
 * @author Sebastian Menski
 */
public class CamundaScriptImpl extends BpmnModelElementInstanceImpl implements CamundaScript {

  protected static Attribute<String> camundaScriptFormatAttribute;
  protected static Attribute<String> camundaResourceAttribute;

  public static void registerType(ModelBuilder modelBuilder) {
    ModelElementTypeBuilder typeBuilder = modelBuilder.defineType(CamundaScript.class, CAMUNDA_ELEMENT_SCRIPT)
      .namespaceUri(CAMUNDA_NS)
      .instanceProvider(new ModelTypeInstanceProvider<CamundaScript>() {
        public CamundaScript newInstance(ModelTypeInstanceContext instanceContext) {
          return new CamundaScriptImpl(instanceContext);
        }
      });

    camundaScriptFormatAttribute = typeBuilder.stringAttribute(CAMUNDA_ATTRIBUTE_SCRIPT_FORMAT)
      .required()
      .build();

    camundaResourceAttribute = typeBuilder.stringAttribute(CAMUNDA_ATTRIBUTE_RESOURCE)
      .build();

    typeBuilder.build();
  }

  public CamundaScriptImpl(ModelTypeInstanceContext instanceContext) {
    super(instanceContext);
  }

  public String getCamundaScriptFormat() {
    return camundaScriptFormatAttribute.getValue(this);
  }

  public void setCamundaScriptFormat(String camundaScriptFormat) {
    camundaScriptFormatAttribute.setValue(this, camundaScriptFormat);
  }

  public String getCamundaResource() {
    return camundaResourceAttribute.getValue(this);
  }

  public void setCamundaResource(String camundaResource) {
    camundaResourceAttribute.setValue(this, camundaResource);
  }
}
