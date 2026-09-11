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
package org.cibseven.bpm.model.bpmn.util;

import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.xml.Model;
import org.cibseven.bpm.model.xml.ModelInstance;
import org.cibseven.bpm.model.xml.instance.ModelElementInstance;
import org.cibseven.bpm.model.xml.test.GetModelElementTypeRule;
import org.cibseven.bpm.model.xml.type.ModelElementType;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.BeforeAllCallback;

/**
 * JUnit 5 extension to provide ModelElementType for BPMN model tests.
 */
public class GetBpmnModelElementTypeRule implements GetModelElementTypeRule, BeforeAllCallback {

  private ModelInstance modelInstance;
  private Model model;
  private ModelElementType modelElementType;

  @Override
  @SuppressWarnings("unchecked")
  public void beforeAll(ExtensionContext context) {
    String className = context.getRequiredTestClass().getName();
    className = className.replaceAll("Test", "");
    Class<? extends ModelElementInstance> instanceClass;
    try {
      instanceClass = (Class<? extends ModelElementInstance>) Class.forName(className);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
    modelInstance = Bpmn.createEmptyModel();
    model = modelInstance.getModel();
    modelElementType = model.getType(instanceClass);
  }

  public ModelInstance getModelInstance() {
    return modelInstance;
  }

  public Model getModel() {
    return model;
  }

  public ModelElementType getModelElementType() {
    return modelElementType;
  }
}