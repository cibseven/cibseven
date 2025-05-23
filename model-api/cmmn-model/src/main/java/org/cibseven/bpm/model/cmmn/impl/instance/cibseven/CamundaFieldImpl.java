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
package org.cibseven.bpm.model.cmmn.impl.instance.cibseven;

import static org.cibseven.bpm.model.cmmn.impl.CmmnModelConstants.CAMUNDA_ATTRIBUTE_EXPRESSION;
import static org.cibseven.bpm.model.cmmn.impl.CmmnModelConstants.CAMUNDA_ATTRIBUTE_NAME;
import static org.cibseven.bpm.model.cmmn.impl.CmmnModelConstants.CAMUNDA_ATTRIBUTE_STRING_VALUE;
import static org.cibseven.bpm.model.cmmn.impl.CmmnModelConstants.CAMUNDA_ELEMENT_FIELD;
import static org.cibseven.bpm.model.cmmn.impl.CmmnModelConstants.CAMUNDA_NS;

import org.cibseven.bpm.model.cmmn.impl.instance.CmmnModelElementInstanceImpl;
import org.cibseven.bpm.model.cmmn.instance.cibseven.CamundaExpression;
import org.cibseven.bpm.model.cmmn.instance.cibseven.CamundaField;
import org.cibseven.bpm.model.cmmn.instance.cibseven.CamundaString;
import org.cibseven.bpm.model.xml.ModelBuilder;
import org.cibseven.bpm.model.xml.impl.instance.ModelTypeInstanceContext;
import org.cibseven.bpm.model.xml.type.ModelElementTypeBuilder;
import org.cibseven.bpm.model.xml.type.ModelElementTypeBuilder.ModelTypeInstanceProvider;
import org.cibseven.bpm.model.xml.type.attribute.Attribute;
import org.cibseven.bpm.model.xml.type.child.ChildElement;
import org.cibseven.bpm.model.xml.type.child.SequenceBuilder;

/**
 * @author Roman Smirnov
 *
 */
public class CamundaFieldImpl extends CmmnModelElementInstanceImpl implements CamundaField {

  protected static Attribute<String> camundaNameAttribute;
  protected static Attribute<String> camundaExpressionAttribute;
  protected static Attribute<String> camundaStringValueAttribute;
  protected static ChildElement<CamundaExpression> camundaExpressionChild;
  protected static ChildElement<CamundaString> camundaStringChild;

  public static void registerType(ModelBuilder modelBuilder) {
    ModelElementTypeBuilder typeBuilder = modelBuilder.defineType(CamundaField.class, CAMUNDA_ELEMENT_FIELD)
      .namespaceUri(CAMUNDA_NS)
      .instanceProvider(new ModelTypeInstanceProvider<CamundaField>() {
        public CamundaField newInstance(ModelTypeInstanceContext instanceContext) {
          return new CamundaFieldImpl(instanceContext);
        }
      });

    camundaNameAttribute = typeBuilder.stringAttribute(CAMUNDA_ATTRIBUTE_NAME)
      .namespace(CAMUNDA_NS)
      .build();

    camundaExpressionAttribute = typeBuilder.stringAttribute(CAMUNDA_ATTRIBUTE_EXPRESSION)
      .namespace(CAMUNDA_NS)
      .build();

    camundaStringValueAttribute = typeBuilder.stringAttribute(CAMUNDA_ATTRIBUTE_STRING_VALUE)
      .namespace(CAMUNDA_NS)
      .build();

    SequenceBuilder sequenceBuilder = typeBuilder.sequence();

    camundaExpressionChild = sequenceBuilder.element(CamundaExpression.class)
      .build();

    camundaStringChild = sequenceBuilder.element(CamundaString.class)
      .build();

    typeBuilder.build();
  }

  public CamundaFieldImpl(ModelTypeInstanceContext instanceContext) {
    super(instanceContext);
  }

  public String getCamundaName() {
    return camundaNameAttribute.getValue(this);
  }

  public void setCamundaName(String camundaName) {
    camundaNameAttribute.setValue(this, camundaName);
  }

  public String getCamundaExpression() {
    return camundaExpressionAttribute.getValue(this);
  }

  public void setCamundaExpression(String camundaExpression) {
    camundaExpressionAttribute.setValue(this, camundaExpression);
  }

  public String getCamundaStringValue() {
    return camundaStringValueAttribute.getValue(this);
  }

  public void setCamundaStringValue(String camundaStringValue) {
    camundaStringValueAttribute.setValue(this, camundaStringValue);
  }

  public CamundaString getCamundaString() {
    return camundaStringChild.getChild(this);
  }

  public void setCamundaString(CamundaString camundaString) {
    camundaStringChild.setChild(this, camundaString);
  }

  public CamundaExpression getCamundaExpressionChild() {
    return camundaExpressionChild.getChild(this);
  }

  public void setCamundaExpressionChild(CamundaExpression camundaExpression) {
    camundaExpressionChild.setChild(this, camundaExpression);
  }

}
