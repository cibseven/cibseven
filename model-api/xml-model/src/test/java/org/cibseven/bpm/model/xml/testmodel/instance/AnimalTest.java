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
import static org.cibseven.bpm.model.xml.testmodel.TestModelConstants.MODEL_NAMESPACE;
import static org.assertj.core.api.Assertions.fail;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import org.cibseven.bpm.model.xml.ModelInstance;
import org.cibseven.bpm.model.xml.ModelValidationException;
import org.cibseven.bpm.model.xml.impl.parser.AbstractModelParser;
import org.cibseven.bpm.model.xml.instance.ModelElementInstanceTest;
import org.cibseven.bpm.model.xml.testmodel.Gender;
import org.cibseven.bpm.model.xml.testmodel.TestModelParser;
import org.cibseven.bpm.model.xml.testmodel.TestModelTest;
import org.cibseven.bpm.model.xml.testmodel.TestModelTest.ParsedModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * @author Sebastian Menski
 */
public class AnimalTest extends TestModelTest {

  private Animal tweety;
  private Animal hedwig;
  private Animal birdo;
  private Animal plucky;
  private Animal fiffy;
  private Animal timmy;
  private Animal daisy;
  private RelationshipDefinition hedwigRelationship;
  private RelationshipDefinition birdoRelationship;
  private RelationshipDefinition pluckyRelationship;
  private RelationshipDefinition fiffyRelationship;
  private RelationshipDefinition timmyRelationship;
  private RelationshipDefinition daisyRelationship;

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

    hedwigRelationship = modelInstance.getModelElementById("tweety-hedwig");
    birdoRelationship = modelInstance.getModelElementById("tweety-birdo");
    pluckyRelationship = modelInstance.getModelElementById("tweety-plucky");
    fiffyRelationship = modelInstance.getModelElementById("tweety-fiffy");

    timmyRelationship = createRelationshipDefinition(modelInstance, timmy, FriendRelationshipDefinition.class);
    daisyRelationship = createRelationshipDefinition(modelInstance, daisy, ChildRelationshipDefinition.class);
  }

  public static Stream<Arguments> models() {
    return Stream.of(createModel(), parseModel(AnimalTest.class)).map(Arguments::of);
  }

  public static ParsedModel createModel() {
    TestModelParser modelParser = new TestModelParser();
    ModelInstance modelInstance = modelParser.getEmptyModel();

    Animals animals = modelInstance.newInstance(Animals.class);
    modelInstance.setDocumentElement(animals);

    // add a tns namespace prefix for QName testing
    animals.getDomElement().registerNamespace("tns", MODEL_NAMESPACE);

    Animal tweety = createBird(modelInstance, "tweety", Gender.Female);
    Animal hedwig = createBird(modelInstance, "hedwig", Gender.Male);
    Animal birdo = createBird(modelInstance, "birdo", Gender.Female);
    Animal plucky = createBird(modelInstance, "plucky", Gender.Unknown);
    Animal fiffy = createBird(modelInstance, "fiffy", Gender.Female);
    createBird(modelInstance, "timmy", Gender.Male);
    createBird(modelInstance, "daisy", Gender.Female);

    // create and add some relationships
    RelationshipDefinition hedwigRelationship = createRelationshipDefinition(modelInstance, hedwig, ChildRelationshipDefinition.class);
    addRelationshipDefinition(tweety, hedwigRelationship);
    RelationshipDefinition birdoRelationship = createRelationshipDefinition(modelInstance, birdo, ChildRelationshipDefinition.class);
    addRelationshipDefinition(tweety, birdoRelationship);
    RelationshipDefinition pluckyRelationship = createRelationshipDefinition(modelInstance, plucky, FriendRelationshipDefinition.class);
    addRelationshipDefinition(tweety, pluckyRelationship);
    RelationshipDefinition fiffyRelationship = createRelationshipDefinition(modelInstance, fiffy, FriendRelationshipDefinition.class);
    addRelationshipDefinition(tweety, fiffyRelationship);

    tweety.getRelationshipDefinitionRefs().add(hedwigRelationship);
    tweety.getRelationshipDefinitionRefs().add(birdoRelationship);
    tweety.getRelationshipDefinitionRefs().add(pluckyRelationship);
    tweety.getRelationshipDefinitionRefs().add(fiffyRelationship);

    tweety.getBestFriends().add(birdo);
    tweety.getBestFriends().add(plucky);
    return new ParsedModel("created", modelInstance, modelParser);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSetIdAttributeByHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    String newId = "new-" + tweety.getId();
    tweety.setId(newId);
    assertThat(tweety.getId()).isEqualTo(newId);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSetIdAttributeByAttributeName(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.setAttributeValue("id", "duffy", true);
    assertThat(tweety.getId()).isEqualTo("duffy");
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testRemoveIdAttribute(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.removeAttribute("id");
    assertThat(tweety.getId()).isNull();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSetNameAttributeByHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.setName("tweety");
    assertThat(tweety.getName()).isEqualTo("tweety");
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSetNameAttributeByAttributeName(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.setAttributeValue("name", "daisy");
    assertThat(tweety.getName()).isEqualTo("daisy");
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testRemoveNameAttribute(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.removeAttribute("name");
    assertThat(tweety.getName()).isNull();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSetFatherAttributeByHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.setFather(timmy);
    assertThat(tweety.getFather()).isEqualTo(timmy);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSetFatherAttributeByAttributeName(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.setAttributeValue("father", timmy.getId());
    assertThat(tweety.getFather()).isEqualTo(timmy);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSetFatherAttributeByAttributeNameWithNamespace(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.setAttributeValue("father", "tns:hedwig");
    assertThat(tweety.getFather()).isEqualTo(hedwig);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testRemoveFatherAttribute(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.setFather(timmy);
    assertThat(tweety.getFather()).isEqualTo(timmy);
    tweety.removeAttribute("father");
    assertThat(tweety.getFather()).isNull();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testChangeIdAttributeOfFatherReference(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.setFather(timmy);
    assertThat(tweety.getFather()).isEqualTo(timmy);
    timmy.setId("new-" + timmy.getId());
    assertThat(tweety.getFather()).isEqualTo(timmy);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testReplaceFatherReferenceWithNewAnimal(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.setFather(timmy);
    assertThat(tweety.getFather()).isEqualTo(timmy);
    timmy.replaceWithElement(plucky);
    assertThat(tweety.getFather()).isEqualTo(plucky);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSetMotherAttributeByHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.setMother(daisy);
    assertThat(tweety.getMother()).isEqualTo(daisy);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSetMotherAttributeByAttributeName(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.setAttributeValue("mother", fiffy.getId());
    assertThat(tweety.getMother()).isEqualTo(fiffy);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testRemoveMotherAttribute(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.setMother(daisy);
    assertThat(tweety.getMother()).isEqualTo(daisy);
    tweety.removeAttribute("mother");
    assertThat(tweety.getMother()).isNull();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testReplaceMotherReferenceWithNewAnimal(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.setMother(daisy);
    assertThat(tweety.getMother()).isEqualTo(daisy);
    daisy.replaceWithElement(birdo);
    assertThat(tweety.getMother()).isEqualTo(birdo);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testChangeIdAttributeOfMotherReference(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.setMother(daisy);
    assertThat(tweety.getMother()).isEqualTo(daisy);
    daisy.setId("new-" + daisy.getId());
    assertThat(tweety.getMother()).isEqualTo(daisy);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSetIsEndangeredAttributeByHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.setIsEndangered(true);
    assertThat(tweety.isEndangered()).isTrue();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSetIsEndangeredAttributeByAttributeName(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.setAttributeValue("isEndangered", "false");
    assertThat(tweety.isEndangered()).isFalse();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testRemoveIsEndangeredAttribute(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.removeAttribute("isEndangered");
    // default value of isEndangered: false
    assertThat(tweety.isEndangered()).isFalse();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSetGenderAttributeByHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.setGender(Gender.Male);
    assertThat(tweety.getGender()).isEqualTo(Gender.Male);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSetGenderAttributeByAttributeName(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.setAttributeValue("gender", Gender.Unknown.toString());
    assertThat(tweety.getGender()).isEqualTo(Gender.Unknown);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testRemoveGenderAttribute(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.removeAttribute("gender");
    assertThat(tweety.getGender()).isNull();

    // gender is required, so the model is invalid without
    try {
      validateModel();
      fail("The model is invalid cause the gender of an animal is a required attribute.");
    }
    catch (Exception e) {
      assertThat(e).isInstanceOf(ModelValidationException.class);
    }

    // add gender to make model valid
    tweety.setGender(Gender.Female);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSetAgeAttributeByHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.setAge(13);
    assertThat(tweety.getAge()).isEqualTo(13);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSetAgeAttributeByAttributeName(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.setAttributeValue("age", "23");
    assertThat(tweety.getAge()).isEqualTo(23);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testRemoveAgeAttribute(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.removeAttribute("age");
    assertThat(tweety.getAge()).isNull();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testAddRelationshipDefinitionsByHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    assertThat(tweety.getRelationshipDefinitions())
      .isNotEmpty()
      .hasSize(4)
      .containsOnly(hedwigRelationship, birdoRelationship, pluckyRelationship, fiffyRelationship);

    tweety.getRelationshipDefinitions().add(timmyRelationship);
    tweety.getRelationshipDefinitions().add(daisyRelationship);

    assertThat(tweety.getRelationshipDefinitions())
      .hasSize(6)
      .containsOnly(hedwigRelationship, birdoRelationship, pluckyRelationship, fiffyRelationship, timmyRelationship, daisyRelationship);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateRelationshipDefinitionsByIdByHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    hedwigRelationship.setId("new-" + hedwigRelationship.getId());
    pluckyRelationship.setId("new-" + pluckyRelationship.getId());
    assertThat(tweety.getRelationshipDefinitions())
      .hasSize(4)
      .containsOnly(hedwigRelationship, birdoRelationship, pluckyRelationship, fiffyRelationship);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateRelationshipDefinitionsByIdByAttributeName(ParsedModel parsedModel) {
    setUp(parsedModel);
    birdoRelationship.setAttributeValue("id", "new-" + birdoRelationship.getId(), true);
    fiffyRelationship.setAttributeValue("id", "new-" + fiffyRelationship.getId(), true);
    assertThat(tweety.getRelationshipDefinitions())
      .hasSize(4)
      .containsOnly(hedwigRelationship, birdoRelationship, pluckyRelationship, fiffyRelationship);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateRelationshipDefinitionsByReplaceElements(ParsedModel parsedModel) {
    setUp(parsedModel);
    hedwigRelationship.replaceWithElement(timmyRelationship);
    pluckyRelationship.replaceWithElement(daisyRelationship);
    assertThat(tweety.getRelationshipDefinitions())
      .hasSize(4)
      .containsOnly(birdoRelationship, fiffyRelationship, timmyRelationship, daisyRelationship);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateRelationshipDefinitionsByRemoveElements(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.getRelationshipDefinitions().remove(birdoRelationship);
    tweety.getRelationshipDefinitions().remove(fiffyRelationship);
    assertThat(tweety.getRelationshipDefinitions())
      .hasSize(2)
      .containsOnly(hedwigRelationship, pluckyRelationship);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testClearRelationshipDefinitions(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.getRelationshipDefinitions().clear();
    assertThat(tweety.getRelationshipDefinitions()).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testAddRelationsDefinitionRefsByHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    assertThat(tweety.getRelationshipDefinitionRefs())
      .isNotEmpty()
      .hasSize(4)
      .containsOnly(hedwigRelationship, birdoRelationship, pluckyRelationship, fiffyRelationship);

    addRelationshipDefinition(tweety, timmyRelationship);
    addRelationshipDefinition(tweety, daisyRelationship);
    tweety.getRelationshipDefinitionRefs().add(timmyRelationship);
    tweety.getRelationshipDefinitionRefs().add(daisyRelationship);

    assertThat(tweety.getRelationshipDefinitionRefs())
      .isNotEmpty()
      .hasSize(6)
      .containsOnly(hedwigRelationship, birdoRelationship, pluckyRelationship, fiffyRelationship, timmyRelationship, daisyRelationship);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateRelationshipDefinitionRefsByIdByHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    hedwigRelationship.setId("child-relationship");
    pluckyRelationship.setId("friend-relationship");
    assertThat(tweety.getRelationshipDefinitionRefs())
      .hasSize(4)
      .containsOnly(hedwigRelationship, birdoRelationship, pluckyRelationship, fiffyRelationship);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateRelationshipDefinitionRefsByIdByAttributeName(ParsedModel parsedModel) {
    setUp(parsedModel);
    birdoRelationship.setAttributeValue("id", "birdo-relationship", true);
    fiffyRelationship.setAttributeValue("id", "fiffy-relationship", true);
    assertThat(tweety.getRelationshipDefinitionRefs())
      .hasSize(4)
      .containsOnly(hedwigRelationship, birdoRelationship, pluckyRelationship, fiffyRelationship);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateRelationshipDefinitionRefsByReplaceElements(ParsedModel parsedModel) {
    setUp(parsedModel);
    hedwigRelationship.replaceWithElement(timmyRelationship);
    pluckyRelationship.replaceWithElement(daisyRelationship);
    assertThat(tweety.getRelationshipDefinitionRefs())
      .hasSize(4)
      .containsOnly(birdoRelationship, fiffyRelationship, timmyRelationship, daisyRelationship);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateRelationshipDefinitionRefsByRemoveElements(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.getRelationshipDefinitions().remove(birdoRelationship);
    tweety.getRelationshipDefinitions().remove(fiffyRelationship);
    assertThat(tweety.getRelationshipDefinitionRefs())
      .hasSize(2)
      .containsOnly(hedwigRelationship, pluckyRelationship);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateRelationshipDefinitionRefsByRemoveIdAttribute(ParsedModel parsedModel) {
    setUp(parsedModel);
    birdoRelationship.removeAttribute("id");
    pluckyRelationship.removeAttribute("id");
    assertThat(tweety.getRelationshipDefinitionRefs())
      .hasSize(2)
      .containsOnly(hedwigRelationship, fiffyRelationship);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testClearRelationshipDefinitionsRefs(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.getRelationshipDefinitionRefs().clear();
    assertThat(tweety.getRelationshipDefinitionRefs()).isEmpty();
    // should not affect animal relationship definitions
    assertThat(tweety.getRelationshipDefinitions()).hasSize(4);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testClearRelationshipDefinitionRefsByClearRelationshipDefinitions(ParsedModel parsedModel) {
    setUp(parsedModel);
    assertThat(tweety.getRelationshipDefinitionRefs()).isNotEmpty();
    tweety.getRelationshipDefinitions().clear();
    assertThat(tweety.getRelationshipDefinitions()).isEmpty();
    // should affect animal relationship definition refs
    assertThat(tweety.getRelationshipDefinitionRefs()).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testAddRelationshipDefinitionRefElementsByHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    assertThat(tweety.getRelationshipDefinitionRefElements())
      .isNotEmpty()
      .hasSize(4);

    addRelationshipDefinition(tweety, timmyRelationship);
    RelationshipDefinitionRef timmyRelationshipDefinitionRef = modelInstance.newInstance(RelationshipDefinitionRef.class);
    timmyRelationshipDefinitionRef.setTextContent(timmyRelationship.getId());
    tweety.getRelationshipDefinitionRefElements().add(timmyRelationshipDefinitionRef);

    addRelationshipDefinition(tweety, daisyRelationship);
    RelationshipDefinitionRef daisyRelationshipDefinitionRef = modelInstance.newInstance(RelationshipDefinitionRef.class);
    daisyRelationshipDefinitionRef.setTextContent(daisyRelationship.getId());
    tweety.getRelationshipDefinitionRefElements().add(daisyRelationshipDefinitionRef);

    assertThat(tweety.getRelationshipDefinitionRefElements())
      .isNotEmpty()
      .hasSize(6)
      .contains(timmyRelationshipDefinitionRef, daisyRelationshipDefinitionRef);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testRelationshipDefinitionRefElementsByTextContent(ParsedModel parsedModel) {
    setUp(parsedModel);
    Collection<RelationshipDefinitionRef> relationshipDefinitionRefElements = tweety.getRelationshipDefinitionRefElements();
    Collection<String> textContents = new ArrayList<String>();
    for (RelationshipDefinitionRef relationshipDefinitionRef : relationshipDefinitionRefElements) {
      String textContent = relationshipDefinitionRef.getTextContent();
      assertThat(textContent).isNotEmpty();
      textContents.add(textContent);
    }
    assertThat(textContents)
      .isNotEmpty()
      .hasSize(4)
      .containsOnly(hedwigRelationship.getId(), birdoRelationship.getId(), pluckyRelationship.getId(), fiffyRelationship.getId());
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateRelationshipDefinitionRefElementsByTextContent(ParsedModel parsedModel) {
    setUp(parsedModel);
    List<RelationshipDefinitionRef> relationshipDefinitionRefs = new ArrayList<RelationshipDefinitionRef>(tweety.getRelationshipDefinitionRefElements());

    addRelationshipDefinition(tweety, timmyRelationship);
    relationshipDefinitionRefs.get(0).setTextContent(timmyRelationship.getId());

    addRelationshipDefinition(daisy, daisyRelationship);
    relationshipDefinitionRefs.get(2).setTextContent(daisyRelationship.getId());

    assertThat(tweety.getRelationshipDefinitionRefs())
      .hasSize(4)
      .containsOnly(birdoRelationship, fiffyRelationship, timmyRelationship, daisyRelationship);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateRelationshipDefinitionRefElementsByTextContentWithNamespace(ParsedModel parsedModel) {
    setUp(parsedModel);
    List<RelationshipDefinitionRef> relationshipDefinitionRefs = new ArrayList<RelationshipDefinitionRef>(tweety.getRelationshipDefinitionRefElements());

    addRelationshipDefinition(tweety, timmyRelationship);
    relationshipDefinitionRefs.get(0).setTextContent("tns:" + timmyRelationship.getId());

    addRelationshipDefinition(daisy, daisyRelationship);
    relationshipDefinitionRefs.get(2).setTextContent("tns:" + daisyRelationship.getId());

    assertThat(tweety.getRelationshipDefinitionRefs())
      .hasSize(4)
      .containsOnly(birdoRelationship, fiffyRelationship, timmyRelationship, daisyRelationship);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateRelationshipDefinitionRefElementsByRemoveElements(ParsedModel parsedModel) {
    setUp(parsedModel);
    List<RelationshipDefinitionRef> relationshipDefinitionRefs = new ArrayList<RelationshipDefinitionRef>(tweety.getRelationshipDefinitionRefElements());
    tweety.getRelationshipDefinitionRefElements().remove(relationshipDefinitionRefs.get(1));
    tweety.getRelationshipDefinitionRefElements().remove(relationshipDefinitionRefs.get(3));
    assertThat(tweety.getRelationshipDefinitionRefs())
      .hasSize(2)
      .containsOnly(hedwigRelationship, pluckyRelationship);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testClearRelationshipDefinitionRefElements(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.getRelationshipDefinitionRefElements().clear();
    assertThat(tweety.getRelationshipDefinitionRefElements()).isEmpty();
    assertThat(tweety.getRelationshipDefinitionRefs()).isEmpty();
    // should not affect animal relationship definitions
    assertThat(tweety.getRelationshipDefinitions())
      .isNotEmpty()
      .hasSize(4);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testClearRelationshipDefinitionRefElementsByClearRelationshipDefinitionRefs(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.getRelationshipDefinitionRefs().clear();
    assertThat(tweety.getRelationshipDefinitionRefs()).isEmpty();
    assertThat(tweety.getRelationshipDefinitionRefElements()).isEmpty();
    // should not affect animal relationship definitions
    assertThat(tweety.getRelationshipDefinitions())
      .isNotEmpty()
      .hasSize(4);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testClearRelationshipDefinitionRefElementsByClearRelationshipDefinitions(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.getRelationshipDefinitions().clear();
    assertThat(tweety.getRelationshipDefinitionRefs()).isEmpty();
    assertThat(tweety.getRelationshipDefinitionRefElements()).isEmpty();
    // should affect animal relationship definitions
    assertThat(tweety.getRelationshipDefinitions()).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testGetBestFriends(ParsedModel parsedModel) {
    setUp(parsedModel);
    Collection<Animal> bestFriends = tweety.getBestFriends();

    assertThat(bestFriends)
      .isNotEmpty()
      .hasSize(2)
      .containsOnly(birdo, plucky);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testAddBestFriend(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.getBestFriends().add(daisy);

    Collection<Animal> bestFriends = tweety.getBestFriends();

    assertThat(bestFriends)
      .isNotEmpty()
      .hasSize(3)
      .containsOnly(birdo, plucky, daisy);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testRemoveBestFriendRef(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.getBestFriends().remove(plucky);

    Collection<Animal> bestFriends = tweety.getBestFriends();

    assertThat(bestFriends)
      .isNotEmpty()
      .hasSize(1)
      .containsOnly(birdo);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testClearBestFriendRef(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.getBestFriends().clear();

    Collection<Animal> bestFriends = tweety.getBestFriends();

    assertThat(bestFriends)
      .isEmpty();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testClearAndAddBestFriendRef(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.getBestFriends().clear();

    Collection<Animal> bestFriends = tweety.getBestFriends();

    assertThat(bestFriends)
      .isEmpty();

    bestFriends.add(daisy);

    assertThat(bestFriends)
      .hasSize(1)
      .containsOnly(daisy);
  }
}
