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

import org.cibseven.bpm.model.xml.ModelInstance;
import org.cibseven.bpm.model.xml.impl.parser.AbstractModelParser;
import org.cibseven.bpm.model.xml.instance.ModelElementInstanceTest;
import org.cibseven.bpm.model.xml.testmodel.Gender;
import org.cibseven.bpm.model.xml.testmodel.TestModelParser;
import org.cibseven.bpm.model.xml.testmodel.TestModelTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cibseven.bpm.model.xml.testmodel.TestModelConstants.MODEL_NAMESPACE;

/**
 * @author Sebastian Menski
 */
public class FlyingAnimalTest extends TestModelTest {

  private FlyingAnimal tweety;
  private FlyingAnimal hedwig;
  private FlyingAnimal birdo;
  private FlyingAnimal plucky;
  private FlyingAnimal fiffy;
  private FlyingAnimal timmy;
  private FlyingAnimal daisy;

  public void setUp(ParsedModel parsedModel) {
    initializeTestModelTest(parsedModel);
    this.modelInstance = (ModelInstance) parsedModel.modelInstance;
    tweety = modelInstance.getModelElementById("tweety");
    hedwig = modelInstance.getModelElementById("hedwig");
    birdo = modelInstance.getModelElementById("birdo");
    plucky = modelInstance.getModelElementById("plucky");
    fiffy = modelInstance.getModelElementById("fiffy");
    timmy = modelInstance.getModelElementById("timmy");
    daisy = modelInstance.getModelElementById("daisy");
  }

  public static Stream<Arguments> models() {
    return Stream.of(createModel(), parseModel(FlyingAnimalTest.class)).map(Arguments::of);
  }

  public static ParsedModel createModel() {
    TestModelParser modelParser = new TestModelParser();
    ModelInstance modelInstance = modelParser.getEmptyModel();

    Animals animals = modelInstance.newInstance(Animals.class);
    modelInstance.setDocumentElement(animals);

    // add a tns namespace prefix for QName testing
    animals.getDomElement().registerNamespace("tns", MODEL_NAMESPACE);

    FlyingAnimal tweety = TestModelTest.createBird(modelInstance, "tweety", Gender.Female);
    FlyingAnimal hedwig = TestModelTest.createBird(modelInstance, "hedwig", Gender.Male);
    FlyingAnimal birdo = TestModelTest.createBird(modelInstance, "birdo", Gender.Female);
    FlyingAnimal plucky = TestModelTest.createBird(modelInstance, "plucky", Gender.Unknown);
    FlyingAnimal fiffy = TestModelTest.createBird(modelInstance, "fiffy", Gender.Female);
    TestModelTest.createBird(modelInstance, "timmy", Gender.Male);
    TestModelTest.createBird(modelInstance, "daisy", Gender.Female);

    tweety.setFlightInstructor(hedwig);

    tweety.getFlightPartnerRefs().add(hedwig);
    tweety.getFlightPartnerRefs().add(birdo);
    tweety.getFlightPartnerRefs().add(plucky);
    tweety.getFlightPartnerRefs().add(fiffy);


    return new ParsedModel("created", modelInstance, modelParser);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSetWingspanAttributeByHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    double wingspan = 2.123;
    tweety.setWingspan(wingspan);
    assertThat(tweety.getWingspan()).isEqualTo(wingspan);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSetWingspanAttributeByAttributeName(ParsedModel parsedModel) {
    setUp(parsedModel);
    Double wingspan = 2.123;
    tweety.setAttributeValue("wingspan", wingspan.toString(), false);
    assertThat(tweety.getWingspan()).isEqualTo(wingspan);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testRemoveWingspanAttribute(ParsedModel parsedModel) {
    setUp(parsedModel);
    double wingspan = 2.123;
    tweety.setWingspan(wingspan);
    assertThat(tweety.getWingspan()).isEqualTo(wingspan);
    tweety.removeAttribute("wingspan");
    assertThat(tweety.getWingspan()).isNull();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSetFlightInstructorByHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.setFlightInstructor(timmy);
    assertThat(tweety.getFlightInstructor()).isEqualTo(timmy);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateFlightInstructorByIdHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    hedwig.setId("new-" + hedwig.getId());
    assertThat(tweety.getFlightInstructor()).isEqualTo(hedwig);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateFlightInstructorByIdAttributeName(ParsedModel parsedModel) {
    setUp(parsedModel);
    hedwig.setAttributeValue("id", "new-" + hedwig.getId(), true);
    assertThat(tweety.getFlightInstructor()).isEqualTo(hedwig);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateFlightInstructorByReplaceElement(ParsedModel parsedModel) {
    setUp(parsedModel);
    hedwig.replaceWithElement(timmy);
    assertThat(tweety.getFlightInstructor()).isEqualTo(timmy);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateFlightInstructorByRemoveElement(ParsedModel parsedModel) {
    setUp(parsedModel);
    Animals animals = (Animals) modelInstance.getDocumentElement();
    animals.getAnimals().remove(hedwig);
    assertThat(tweety.getFlightInstructor()).isNull();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testClearFlightInstructor(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.removeFlightInstructor();
    assertThat(tweety.getFlightInstructor()).isNull();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testAddFlightPartnerRefsByHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    assertThat(tweety.getFlightPartnerRefs())
      .isNotEmpty()
      .hasSize(4)
      .containsOnly(hedwig, birdo, plucky, fiffy);
    tweety.getFlightPartnerRefs().add(timmy);
    tweety.getFlightPartnerRefs().add(daisy);
    assertThat(tweety.getFlightPartnerRefs())
      .isNotEmpty()
      .hasSize(6)
      .containsOnly(hedwig, birdo, plucky, fiffy, timmy, daisy);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateFlightPartnerRefsByIdByHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    hedwig.setId("new-" + hedwig.getId());
    plucky.setId("new-" + plucky.getId());
    assertThat(tweety.getFlightPartnerRefs())
      .hasSize(4)
      .containsOnly(hedwig, birdo, plucky, fiffy);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateFlightPartnerRefsByIdByAttributeName(ParsedModel parsedModel) {
    setUp(parsedModel);
    birdo.setAttributeValue("id", "new-" + birdo.getId(), true);
    fiffy.setAttributeValue("id", "new-" + fiffy.getId(), true);
    assertThat(tweety.getFlightPartnerRefs())
      .hasSize(4)
      .containsOnly(hedwig, birdo, plucky, fiffy);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateFlightPartnerRefsByReplaceElements(ParsedModel parsedModel) {
    setUp(parsedModel);
    hedwig.replaceWithElement(timmy);
    plucky.replaceWithElement(daisy);
    assertThat(tweety.getFlightPartnerRefs())
      .hasSize(4)
      .containsOnly(birdo, fiffy, timmy ,daisy);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateFlightPartnerRefsByRemoveElements(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.getFlightPartnerRefs().remove(birdo);
    tweety.getFlightPartnerRefs().remove(fiffy);
    assertThat(tweety.getFlightPartnerRefs())
      .hasSize(2)
      .containsOnly(hedwig, plucky);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testClearFlightPartnerRefs(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.getFlightPartnerRefs().clear();
    assertThat(tweety.getFlightPartnerRefs()).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testAddFlightPartnerRefElementsByHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    assertThat(tweety.getFlightPartnerRefElements())
      .isNotEmpty()
      .hasSize(4);
    FlightPartnerRef timmyFlightPartnerRef = modelInstance.newInstance(FlightPartnerRef.class);
    timmyFlightPartnerRef.setTextContent(timmy.getId());
    tweety.getFlightPartnerRefElements().add(timmyFlightPartnerRef);
    FlightPartnerRef daisyFlightPartnerRef = modelInstance.newInstance(FlightPartnerRef.class);
    daisyFlightPartnerRef.setTextContent(daisy.getId());
    tweety.getFlightPartnerRefElements().add(daisyFlightPartnerRef);
    assertThat(tweety.getFlightPartnerRefElements())
      .isNotEmpty()
      .hasSize(6)
      .contains(timmyFlightPartnerRef, daisyFlightPartnerRef);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testFlightPartnerRefElementsByTextContent(ParsedModel parsedModel) {
    setUp(parsedModel);
    Collection<FlightPartnerRef> flightPartnerRefElements = tweety.getFlightPartnerRefElements();
    Collection<String> textContents = new ArrayList<String>();
    for (FlightPartnerRef flightPartnerRefElement : flightPartnerRefElements) {
      String textContent = flightPartnerRefElement.getTextContent();
      assertThat(textContent).isNotEmpty();
      textContents.add(textContent);
    }
    assertThat(textContents)
      .isNotEmpty()
      .hasSize(4)
      .containsOnly(hedwig.getId(), birdo.getId(), plucky.getId(), fiffy.getId());
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateFlightPartnerRefElementsByTextContent(ParsedModel parsedModel) {
    setUp(parsedModel);
    List<FlightPartnerRef> flightPartnerRefs = new ArrayList<FlightPartnerRef>(tweety.getFlightPartnerRefElements());
    flightPartnerRefs.get(0).setTextContent(timmy.getId());
    flightPartnerRefs.get(2).setTextContent(daisy.getId());
    assertThat(tweety.getFlightPartnerRefs())
      .hasSize(4)
      .containsOnly(birdo, fiffy, timmy, daisy);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateFlightPartnerRefElementsByRemoveElements(ParsedModel parsedModel) {
    setUp(parsedModel);
    List<FlightPartnerRef> flightPartnerRefs = new ArrayList<FlightPartnerRef>(tweety.getFlightPartnerRefElements());
    tweety.getFlightPartnerRefElements().remove(flightPartnerRefs.get(1));
    tweety.getFlightPartnerRefElements().remove(flightPartnerRefs.get(3));
    assertThat(tweety.getFlightPartnerRefs())
      .hasSize(2)
      .containsOnly(hedwig, plucky);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testClearFlightPartnerRefElements(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.getFlightPartnerRefElements().clear();
    assertThat(tweety.getFlightPartnerRefElements()).isEmpty();
    // should not affect animals collection
    Animals animals = (Animals) modelInstance.getDocumentElement();
    assertThat(animals.getAnimals())
      .isNotEmpty()
      .hasSize(7);
  }

}