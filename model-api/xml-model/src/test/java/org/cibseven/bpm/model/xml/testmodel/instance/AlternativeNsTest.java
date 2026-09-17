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
package org.cibseven.bpm.model.xml.testmodel.instance;


import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.cibseven.bpm.model.xml.ModelInstance;
import org.cibseven.bpm.model.xml.impl.ModelImpl;
import org.cibseven.bpm.model.xml.impl.ModelInstanceImpl;
import org.cibseven.bpm.model.xml.impl.parser.AbstractModelParser;
import org.cibseven.bpm.model.xml.instance.DomElement;
import org.cibseven.bpm.model.xml.instance.ModelElementInstance;
import org.cibseven.bpm.model.xml.testmodel.Gender;
import org.cibseven.bpm.model.xml.testmodel.TestModelConstants;
import org.cibseven.bpm.model.xml.testmodel.TestModelParser;
import org.cibseven.bpm.model.xml.testmodel.TestModelTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * @author Ronny Bräunlich
 */
public class AlternativeNsTest extends TestModelTest {

  private static final String MECHANICAL_NS = "http://camunda.org/mechanical";
  private static final String YET_ANOTHER_NS = "http://camunda.org/yans";

  public static Stream<Arguments> models() {
    return Stream.of(parseModel(AlternativeNsTest.class)).map(Arguments::of);
  }

  public void setUp(ParsedModel parsedModel) {
    initializeTestModelTest(parsedModel);
    ModelImpl modelImpl = (ModelImpl) modelInstance.getModel();
    modelImpl.declareAlternativeNamespace(MECHANICAL_NS, TestModelConstants.NEWER_NAMESPACE);
    modelImpl.declareAlternativeNamespace(YET_ANOTHER_NS, TestModelConstants.NEWER_NAMESPACE);
  }

  @AfterEach
  public void tearDown() {
    ModelImpl modelImpl = (ModelImpl) modelInstance.getModel();
    modelImpl.undeclareAlternativeNamespace(MECHANICAL_NS);
    modelImpl.undeclareAlternativeNamespace(YET_ANOTHER_NS);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void getUniqueChildElementByNameNsForAlternativeNs(ParsedModel parsedModel) {
	setUp(parsedModel);
    ModelElementInstance hedwig = modelInstance.getModelElementById("hedwig");
    assertThat(hedwig).isNotNull();
    ModelElementInstance childElementByNameNs = hedwig.getUniqueChildElementByNameNs(TestModelConstants.NEWER_NAMESPACE, "wings");
    assertThat(childElementByNameNs).isNotNull();
    assertThat(childElementByNameNs.getTextContent()).isEqualTo("wusch");
  }

  @ParameterizedTest
  @MethodSource("models")
  public void getUniqueChildElementByNameNsForSecondAlternativeNs(ParsedModel parsedModel) {
    setUp(parsedModel);
    // given
    ModelElementInstance donald = modelInstance.getModelElementById("donald");

    // when
    ModelElementInstance childElementByNameNs = donald.getUniqueChildElementByNameNs(TestModelConstants.NEWER_NAMESPACE, "wings");

    // then
    assertThat(childElementByNameNs).isNotNull();
    assertThat(childElementByNameNs.getTextContent()).isEqualTo("flappy");
  }

  @ParameterizedTest
  @MethodSource("models")
  public void getChildElementsByTypeForAlternativeNs(ParsedModel parsedModel) {
    setUp(parsedModel);
    ModelElementInstance birdo = modelInstance.getModelElementById("birdo");
    assertThat(birdo).isNotNull();
    Collection<Wings> elements = birdo.getChildElementsByType(Wings.class);
    assertThat(elements.size()).isEqualTo(1);
    assertThat(elements.iterator().next().getTextContent()).isEqualTo("zisch");
  }

  @ParameterizedTest
  @MethodSource("models")
  public void getChildElementsByTypeForSecondAlternativeNs(ParsedModel parsedModel) {
    setUp(parsedModel);
    // given
    ModelElementInstance donald = modelInstance.getModelElementById("donald");

    // when
    Collection<Wings> elements = donald.getChildElementsByType(Wings.class);

    // then
    assertThat(elements.size()).isEqualTo(1);
    assertThat(elements.iterator().next().getTextContent()).isEqualTo("flappy");
  }

  @ParameterizedTest
  @MethodSource("models")
  public void getAttributeValueNsForAlternativeNs(ParsedModel parsedModel) {
    setUp(parsedModel);
    Bird plucky = modelInstance.getModelElementById("plucky");
    assertThat(plucky).isNotNull();
    Boolean extendedWings = plucky.canHazExtendedWings();
    assertThat(extendedWings).isFalse();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void getAttributeValueNsForSecondAlternativeNs(ParsedModel parsedModel) {
    setUp(parsedModel);
    // given
    Bird donald = modelInstance.getModelElementById("donald");

    // when
    Boolean extendedWings = donald.canHazExtendedWings();

    // then
    assertThat(extendedWings).isTrue();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void modifyingAttributeWithAlternativeNamespaceKeepsAlternativeNamespace(ParsedModel parsedModel){
    setUp(parsedModel);
    Bird plucky = modelInstance.getModelElementById("plucky");
    assertThat(plucky).isNotNull();
    //validate old value
    Boolean extendedWings = plucky.canHazExtendedWings();
    assertThat(extendedWings).isEqualTo(false);
    //change it
    plucky.setCanHazExtendedWings(true);
    String attributeValueNs = plucky.getAttributeValueNs(MECHANICAL_NS, "canHazExtendedWings");
    assertThat(attributeValueNs).isEqualTo("true");
  }

  @ParameterizedTest
  @MethodSource("models")
  public void modifyingAttributeWithSecondAlternativeNamespaceKeepsSecondAlternativeNamespace(ParsedModel parsedModel){
    setUp(parsedModel);
    // given
    Bird donald = modelInstance.getModelElementById("donald");

    // when
    donald.setCanHazExtendedWings(false);

    // then
    String attributeValueNs = donald.getAttributeValueNs(YET_ANOTHER_NS, "canHazExtendedWings");
    assertThat(attributeValueNs).isEqualTo("false");
  }

  @ParameterizedTest
  @MethodSource("models")
  public void modifyingAttributeWithNewNamespaceKeepsNewNamespace(ParsedModel parsedModel){
    setUp(parsedModel);
    Bird bird = createBird(modelInstance, "waldo", Gender.Male);
    bird.setCanHazExtendedWings(true);
    String attributeValueNs = bird.getAttributeValueNs(TestModelConstants.NEWER_NAMESPACE, "canHazExtendedWings");
    assertThat(attributeValueNs).isEqualTo("true");
  }

  @ParameterizedTest
  @MethodSource("models")
  public void modifyingElementWithAlternativeNamespaceKeepsAlternativeNamespace(ParsedModel parsedModel){
    setUp(parsedModel);
    Bird birdo = modelInstance.getModelElementById("birdo");
    assertThat(birdo).isNotNull();
    Wings wings = birdo.getWings();
    assertThat(wings).isNotNull();
    wings.setTextContent("kawusch");

    List<DomElement> childElementsByNameNs = birdo.getDomElement().getChildElementsByNameNs(MECHANICAL_NS, "wings");
    assertThat(childElementsByNameNs.size()).isEqualTo(1);
    assertThat(childElementsByNameNs.get(0).getTextContent()).isEqualTo("kawusch");
  }

  @ParameterizedTest
  @MethodSource("models")
  public void modifyingElementWithSecondAlternativeNamespaceKeepsSecondAlternativeNamespace(ParsedModel parsedModel){
    setUp(parsedModel);
    // given
    Bird donald = modelInstance.getModelElementById("donald");
    Wings wings = donald.getWings();

    // when
    wings.setTextContent("kawusch");

    // then
    List<DomElement> childElementsByNameNs = donald.getDomElement().getChildElementsByNameNs(YET_ANOTHER_NS, "wings");
    assertThat(childElementsByNameNs.size()).isEqualTo(1);
    assertThat(childElementsByNameNs.get(0).getTextContent()).isEqualTo("kawusch");
  }

  @ParameterizedTest
  @MethodSource("models")
  public void modifyingElementWithNewNamespaceKeepsNewNamespace(ParsedModel parsedModel){
    setUp(parsedModel);
    Bird bird = createBird(modelInstance, "waldo", Gender.Male);
    bird.setWings(modelInstance.newInstance(Wings.class));

    List<DomElement> childElementsByNameNs = bird.getDomElement().getChildElementsByNameNs(TestModelConstants.NEWER_NAMESPACE, "wings");
    assertThat(childElementsByNameNs.size()).isEqualTo(1);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void useExistingNamespace(ParsedModel parsedModel) {
    setUp(parsedModel);
    assertThatThereIsNoNewerNamespaceUrl();

    Bird plucky = modelInstance.getModelElementById("plucky");
    plucky.setAttributeValueNs(MECHANICAL_NS, "canHazExtendedWings", "true");

    Bird donald = modelInstance.getModelElementById("donald");
    donald.setAttributeValueNs(YET_ANOTHER_NS, "canHazExtendedWings", "false");
    assertThatThereIsNoNewerNamespaceUrl();

    assertTrue(plucky.canHazExtendedWings());
    assertThatThereIsNoNewerNamespaceUrl();
  }

  protected void assertThatThereIsNoNewerNamespaceUrl() {
    Node rootElement = modelInstance.getDocument().getDomSource().getNode().getFirstChild();
    NamedNodeMap attributes = rootElement.getAttributes();
    for (int i = 0; i < attributes.getLength(); i++) {
      Node item = attributes.item(i);
      String nodeValue = item.getNodeValue();
      assertNotEquals(TestModelConstants.NEWER_NAMESPACE, nodeValue, "Found newer namespace url which shouldn't exist");
    }
  }

}
