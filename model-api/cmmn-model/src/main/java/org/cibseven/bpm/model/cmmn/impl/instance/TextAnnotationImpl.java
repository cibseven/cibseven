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
package org.cibseven.bpm.model.cmmn.impl.instance;

import static org.cibseven.bpm.model.cmmn.impl.CmmnModelConstants.CMMN11_NS;
import static org.cibseven.bpm.model.cmmn.impl.CmmnModelConstants.CMMN_ATTRIBUTE_TEXT_FORMAT;
import static org.cibseven.bpm.model.cmmn.impl.CmmnModelConstants.CMMN_ELEMENT_TEXT_ANNOTATION;

import org.cibseven.bpm.model.cmmn.instance.Artifact;
import org.cibseven.bpm.model.cmmn.instance.Text;
import org.cibseven.bpm.model.cmmn.instance.TextAnnotation;
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
public class TextAnnotationImpl extends ArtifactImpl implements TextAnnotation {

  protected static Attribute<String> textFormatAttribute;
  protected static ChildElement<Text> textChild;

  public TextAnnotationImpl(ModelTypeInstanceContext instanceContext) {
    super(instanceContext);
  }

  public String getTextFormat() {
    return textFormatAttribute.getValue(this);
  }

  public void setTextFormat(String textFormat) {
    textFormatAttribute.setValue(this, textFormat);
  }

  public Text getText() {
    return textChild.getChild(this);
  }

  public void setText(Text text) {
    textChild.setChild(this, text);
  }

  public static void registerType(ModelBuilder modelBuilder) {
    ModelElementTypeBuilder typeBuilder = modelBuilder.defineType(TextAnnotation.class, CMMN_ELEMENT_TEXT_ANNOTATION)
      .namespaceUri(CMMN11_NS)
      .extendsType(Artifact.class)
      .instanceProvider(new ModelTypeInstanceProvider<TextAnnotation>() {
        public TextAnnotation newInstance(ModelTypeInstanceContext instanceContext) {
          return new TextAnnotationImpl(instanceContext);
        }
      });

    textFormatAttribute = typeBuilder.stringAttribute(CMMN_ATTRIBUTE_TEXT_FORMAT)
      .defaultValue("text/plain")
      .build();

    SequenceBuilder sequenceBuilder = typeBuilder.sequence();

    textChild = sequenceBuilder.element(Text.class)
      .build();

    typeBuilder.build();
  }

}