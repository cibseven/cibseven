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
package org.cibseven.bpm.model.xml.type.attribute;

import org.cibseven.bpm.model.xml.ModelInstance;
import org.cibseven.bpm.model.xml.impl.type.attribute.AttributeImpl;
import org.cibseven.bpm.model.xml.testmodel.Gender;
import org.cibseven.bpm.model.xml.testmodel.TestModelParser;
import org.cibseven.bpm.model.xml.testmodel.TestModelTest;
import org.cibseven.bpm.model.xml.testmodel.instance.Animal;
import org.cibseven.bpm.model.xml.testmodel.instance.Animals;
import org.cibseven.bpm.model.xml.testmodel.instance.Bird;
import org.cibseven.bpm.model.xml.type.ModelElementType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.util.stream.Stream;

import static org.cibseven.bpm.model.xml.test.assertions.ModelAssertions.assertThat;

/**
 * @author Sebastian Menski
 */
public class AttributeTest extends TestModelTest {

  private Bird tweety;
  private Attribute<String> idAttribute;
  private Attribute<String> nameAttribute;
  private Attribute<String> fatherAttribute;

  public static Stream<Arguments> models() {
    return Stream.of(createModel(), parseModel(AttributeTest.class)).map(Arguments::of);
  }

  public static ParsedModel createModel() {
    TestModelParser modelParser = new TestModelParser();
    ModelInstance modelInstance = modelParser.getEmptyModel();

    Animals animals = modelInstance.newInstance(Animals.class);
    modelInstance.setDocumentElement(animals);

    createBird(modelInstance, "tweety", Gender.Female);

    return new ParsedModel("model", modelInstance, modelParser);
  }

  @SuppressWarnings("unchecked")
  public void setUp(ParsedModel parsedModel) {
    initializeTestModelTest(parsedModel);
    this.modelInstance = (ModelInstance) parsedModel.modelInstance;
    tweety = modelInstance.getModelElementById("tweety");
    ModelElementType animalType = modelInstance.getModel().getType(Animal.class);
    idAttribute = (Attribute<String>) animalType.getAttribute("id");
    nameAttribute = (Attribute<String>) animalType.getAttribute("name");
    fatherAttribute = (Attribute<String>) animalType.getAttribute("father");
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testOwningElementType(ParsedModel parsedModel) {
    setUp(parsedModel);
    ModelElementType animalType = modelInstance.getModel().getType(Animal.class);
    assertThat(idAttribute).hasOwningElementType(animalType);
    assertThat(nameAttribute).hasOwningElementType(animalType);
    assertThat(fatherAttribute).hasOwningElementType(animalType);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSetAttributeValue(ParsedModel parsedModel) {
    setUp(parsedModel);
    String identifier = "new-" + tweety.getId();
    idAttribute.setValue(tweety, identifier);
    assertThat(idAttribute).hasValue(tweety, identifier);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSetAttributeValueWithoutUpdateReference(ParsedModel parsedModel) {
    setUp(parsedModel);
    String identifier = "new-" + tweety.getId();
    idAttribute.setValue(tweety, identifier, false);
    assertThat(idAttribute).hasValue(tweety, identifier);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSetDefaultValue(ParsedModel parsedModel) {
    setUp(parsedModel);
    String defaultName = "default-name";
    assertThat(tweety.getName()).isNull();
    assertThat(nameAttribute).hasNoDefaultValue();
    ((AttributeImpl<String>) nameAttribute).setDefaultValue(defaultName);
    assertThat(nameAttribute).hasDefaultValue(defaultName);
    assertThat(tweety.getName()).isEqualTo(defaultName);
    tweety.setName("not-" + defaultName);
    assertThat(tweety.getName()).isNotEqualTo(defaultName);
    tweety.removeAttribute("name");
    assertThat(tweety.getName()).isEqualTo(defaultName);
    ((AttributeImpl<String>) nameAttribute).setDefaultValue(null);
    assertThat(nameAttribute).hasNoDefaultValue();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testRequired(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.removeAttribute("name");
    assertThat(nameAttribute).isOptional();
    ((AttributeImpl<String>) nameAttribute).setRequired(true);
    assertThat(nameAttribute).isRequired();
    ((AttributeImpl<String>) nameAttribute).setRequired(false);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSetNamespaceUri(ParsedModel parsedModel) {
    setUp(parsedModel);
    String testNamespace = "http://camunda.org/test";
    ((AttributeImpl<String>) idAttribute).setNamespaceUri(testNamespace);
    assertThat(idAttribute).hasNamespaceUri(testNamespace);
    ((AttributeImpl<String>) idAttribute).setNamespaceUri(null);
    assertThat(idAttribute).hasNoNamespaceUri();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testIdAttribute(ParsedModel parsedModel) {
    setUp(parsedModel);
    assertThat(idAttribute).isIdAttribute();
    assertThat(nameAttribute).isNotIdAttribute();
    assertThat(fatherAttribute).isNotIdAttribute();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testAttributeName(ParsedModel parsedModel) {
    setUp(parsedModel);
    assertThat(idAttribute).hasAttributeName("id");
    assertThat(nameAttribute).hasAttributeName("name");
    assertThat(fatherAttribute).hasAttributeName("father");
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testRemoveAttribute(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.setName("test");
    assertThat(tweety.getName()).isNotNull();
    assertThat(nameAttribute).hasValue(tweety);
    ((AttributeImpl<String>) nameAttribute).removeAttribute(tweety);
    assertThat(tweety.getName()).isNull();
    assertThat(nameAttribute).hasNoValue(tweety);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testIncomingReferences(ParsedModel parsedModel) {
    setUp(parsedModel);
    assertThat(idAttribute).hasIncomingReferences();
    assertThat(nameAttribute).hasNoIncomingReferences();
    assertThat(fatherAttribute).hasNoIncomingReferences();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testOutgoingReferences(ParsedModel parsedModel) {
    setUp(parsedModel);
    assertThat(idAttribute).hasNoOutgoingReferences();
    assertThat(nameAttribute).hasNoOutgoingReferences();
    assertThat(fatherAttribute).hasOutgoingReferences();
  }

}