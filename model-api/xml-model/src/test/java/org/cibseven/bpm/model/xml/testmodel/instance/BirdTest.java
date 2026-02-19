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
import org.cibseven.bpm.model.xml.impl.util.StringUtil;
import org.cibseven.bpm.model.xml.testmodel.Gender;
import org.cibseven.bpm.model.xml.testmodel.TestModelParser;
import org.cibseven.bpm.model.xml.testmodel.TestModelTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cibseven.bpm.model.xml.testmodel.TestModelConstants.MODEL_NAMESPACE;

/**
 * @author Sebastian Menski
 */
public class BirdTest extends TestModelTest {

  private Bird tweety;
  private Bird hedwig;
  private Bird timmy;
  private Egg egg1;
  private Egg egg2;
  private Egg egg3;

  public void setUp(ParsedModel parsedModel) {
    initializeTestModelTest(parsedModel);
    this.modelInstance = (ModelInstance) parsedModel.modelInstance;
    tweety = modelInstance.getModelElementById("tweety");
    hedwig = modelInstance.getModelElementById("hedwig");
    timmy = modelInstance.getModelElementById("timmy");
    egg1 = modelInstance.getModelElementById("egg1");
    egg2 = modelInstance.getModelElementById("egg2");
    egg3 = modelInstance.getModelElementById("egg3");
  }

  public static Stream<Arguments> models() {
    return Stream.of(createModel(), parseModel(BirdTest.class)).map(Arguments::of);
  }

  public static ParsedModel createModel() {
    TestModelParser modelParser = new TestModelParser();
    ModelInstance modelInstance = modelParser.getEmptyModel();

    Animals animals = modelInstance.newInstance(Animals.class);
    modelInstance.setDocumentElement(animals);

    // add a tns namespace prefix for QName testing
    animals.getDomElement().registerNamespace("tns", MODEL_NAMESPACE);

    Bird tweety = createBird(modelInstance, "tweety", Gender.Female);
    Bird hedwig = createBird(modelInstance, "hedwig", Gender.Female);
    Bird timmy = createBird(modelInstance, "timmy", Gender.Female);
    Egg egg1 = createEgg(modelInstance, "egg1");
    egg1.setMother(tweety);
    Collection<Animal> guards = egg1.getGuardians();
    guards.add(hedwig);
    guards.add(timmy);
    Egg egg2 = createEgg(modelInstance, "egg2");
    egg2.setMother(tweety);
    guards = egg2.getGuardians();
    guards.add(hedwig);
    guards.add(timmy);
    Egg egg3 = createEgg(modelInstance, "egg3");
    guards = egg3.getGuardians();
    guards.add(timmy);

    tweety.setSpouse(hedwig);
    tweety.getEggs().add(egg1);
    tweety.getEggs().add(egg2);
    tweety.getEggs().add(egg3);

    Collection<Egg> guardedEggs = hedwig.getGuardedEggs();
    guardedEggs.add(egg1);
    guardedEggs.add(egg2);

    GuardEgg guardEgg = modelInstance.newInstance(GuardEgg.class);
    guardEgg.setTextContent(egg1.getId() + " " + egg2.getId());
    timmy.getGuardedEggRefs().add(guardEgg);
    timmy.getGuardedEggs().add(egg3);

    return new ParsedModel("created", modelInstance, modelParser);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testAddEggsByHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    assertThat(tweety.getEggs())
      .isNotEmpty()
      .hasSize(3)
      .containsOnly(egg1, egg2, egg3);

    Egg egg4 = createEgg(modelInstance, "egg4");
    tweety.getEggs().add(egg4);
    Egg egg5 = createEgg(modelInstance, "egg5");
    tweety.getEggs().add(egg5);

    assertThat(tweety.getEggs())
      .hasSize(5)
      .containsOnly(egg1, egg2, egg3, egg4, egg5);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateEggsByIdByHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    egg1.setId("new-" + egg1.getId());
    egg2.setId("new-" + egg2.getId());
    assertThat(tweety.getEggs())
      .hasSize(3)
      .containsOnly(egg1, egg2, egg3);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateEggsByIdByAttributeName(ParsedModel parsedModel) {
    setUp(parsedModel);
    egg1.setAttributeValue("id", "new-" + egg1.getId(), true);
    egg2.setAttributeValue("id", "new-" + egg2.getId(), true);
    assertThat(tweety.getEggs())
      .hasSize(3)
      .containsOnly(egg1, egg2, egg3);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateEggsByReplaceElements(ParsedModel parsedModel) {
    setUp(parsedModel);
    Egg egg4 = createEgg(modelInstance, "egg4");
    Egg egg5 = createEgg(modelInstance, "egg5");
    egg1.replaceWithElement(egg4);
    egg2.replaceWithElement(egg5);
    assertThat(tweety.getEggs())
      .hasSize(3)
      .containsOnly(egg3, egg4, egg5);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateEggsByRemoveElement(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.getEggs().remove(egg1);
    assertThat(tweety.getEggs())
      .hasSize(2)
      .containsOnly(egg2, egg3);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testClearEggs(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.getEggs().clear();
    assertThat(tweety.getEggs())
      .isEmpty();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSetSpouseRefByHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.setSpouse(timmy);
    assertThat(tweety.getSpouse()).isEqualTo(timmy);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateSpouseByIdHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    hedwig.setId("new-" + hedwig.getId());
    assertThat(tweety.getSpouse()).isEqualTo(hedwig);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateSpouseByIdByAttributeName(ParsedModel parsedModel) {
    setUp(parsedModel);
    hedwig.setAttributeValue("id", "new-" + hedwig.getId(), true);
    assertThat(tweety.getSpouse()).isEqualTo(hedwig);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateSpouseByReplaceElement(ParsedModel parsedModel) {
    setUp(parsedModel);
    hedwig.replaceWithElement(timmy);
    assertThat(tweety.getSpouse()).isEqualTo(timmy);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateSpouseByRemoveElement(ParsedModel parsedModel) {
    setUp(parsedModel);
    Animals animals = (Animals) modelInstance.getDocumentElement();
    animals.getAnimals().remove(hedwig);
    assertThat(tweety.getSpouse()).isNull();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testClearSpouse(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.removeSpouse();
    assertThat(tweety.getSpouse()).isNull();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSetSpouseRefsByHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    SpouseRef spouseRef = modelInstance.newInstance(SpouseRef.class);
    spouseRef.setTextContent(timmy.getId());
    tweety.getSpouseRef().replaceWithElement(spouseRef);
    assertThat(tweety.getSpouse()).isEqualTo(timmy);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSpouseRefsByTextContent(ParsedModel parsedModel) {
    setUp(parsedModel);
    SpouseRef spouseRef = tweety.getSpouseRef();
    assertThat(spouseRef.getTextContent()).isEqualTo(hedwig.getId());
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateSpouseRefsByTextContent(ParsedModel parsedModel) {
    setUp(parsedModel);
    SpouseRef spouseRef = tweety.getSpouseRef();
    spouseRef.setTextContent(timmy.getId());
    assertThat(tweety.getSpouse()).isEqualTo(timmy);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateSpouseRefsByTextContentWithNamespace(ParsedModel parsedModel) {
    setUp(parsedModel);
    SpouseRef spouseRef = tweety.getSpouseRef();
    spouseRef.setTextContent("tns:" + timmy.getId());
    assertThat(tweety.getSpouse()).isEqualTo(timmy);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testGetMother(ParsedModel parsedModel) {
    setUp(parsedModel);
    Animal mother = egg1.getMother();
    assertThat(mother).isEqualTo(tweety);

    mother = egg2.getMother();
    assertThat(mother).isEqualTo(tweety);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSetMotherRefByHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    egg1.setMother(timmy);
    assertThat(egg1.getMother()).isEqualTo(timmy);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateMotherByIdHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.setId("new-" + tweety.getId());
    assertThat(egg1.getMother()).isEqualTo(tweety);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateMotherByIdByAttributeName(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.setAttributeValue("id", "new-" + tweety.getId(), true);
    assertThat(egg1.getMother()).isEqualTo(tweety);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateMotherByReplaceElement(ParsedModel parsedModel) {
    setUp(parsedModel);
    tweety.replaceWithElement(timmy);
    assertThat(egg1.getMother()).isEqualTo(timmy);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateMotherByRemoveElement(ParsedModel parsedModel) {
    setUp(parsedModel);
    egg1.setMother(hedwig);
    Animals animals = (Animals) modelInstance.getDocumentElement();
    animals.getAnimals().remove(hedwig);
    assertThat(egg1.getMother()).isNull();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testClearMother(ParsedModel parsedModel) {
    setUp(parsedModel);
    egg1.removeMother();
    assertThat(egg1.getMother()).isNull();
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testSetMotherRefsByHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    Mother mother = modelInstance.newInstance(Mother.class);
    mother.setHref("#" + timmy.getId());
    egg1.getMotherRef().replaceWithElement(mother);
    assertThat(egg1.getMother()).isEqualTo(timmy);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testMotherRefsByTextContent(ParsedModel parsedModel) {
    setUp(parsedModel);
    Mother mother = egg1.getMotherRef();
    assertThat(mother.getHref()).isEqualTo("#" + tweety.getId());
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateMotherRefsByTextContent(ParsedModel parsedModel) {
    setUp(parsedModel);
    Mother mother = egg1.getMotherRef();
    mother.setHref("#" + timmy.getId());
    assertThat(egg1.getMother()).isEqualTo(timmy);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testGetGuards(ParsedModel parsedModel) {
    setUp(parsedModel);
    Collection<Animal> guards = egg1.getGuardians();
    assertThat(guards).isNotEmpty().hasSize(2);
    assertThat(guards).contains(hedwig, timmy);

    guards = egg2.getGuardians();
    assertThat(guards).isNotEmpty().hasSize(2);
    assertThat(guards).contains(hedwig, timmy);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testAddGuardianRefsByHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    assertThat(egg1.getGuardianRefs())
      .isNotEmpty()
      .hasSize(2);

    Guardian tweetyGuardian = modelInstance.newInstance(Guardian.class);
    tweetyGuardian.setHref("#" + tweety.getId());
    egg1.getGuardianRefs().add(tweetyGuardian);

    assertThat(egg1.getGuardianRefs())
      .isNotEmpty()
      .hasSize(3)
      .contains(tweetyGuardian);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testGuardianRefsByTextContent(ParsedModel parsedModel) {
    setUp(parsedModel);
    Collection<Guardian> guardianRefs = egg1.getGuardianRefs();
    Collection<String> hrefs = new ArrayList<String>();
    for (Guardian guardianRef : guardianRefs) {
      String href = guardianRef.getHref();
      assertThat(href).isNotEmpty();
      hrefs.add(href);
    }
    assertThat(hrefs)
      .isNotEmpty()
      .hasSize(2)
      .containsOnly("#" + hedwig.getId(), "#" + timmy.getId());
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateGuardianRefsByTextContent(ParsedModel parsedModel) {
    setUp(parsedModel);
    List<Guardian> guardianRefs = new ArrayList<Guardian>(egg1.getGuardianRefs());

    guardianRefs.get(0).setHref("#" + tweety.getId());

    assertThat(egg1.getGuardians())
      .hasSize(2)
      .containsOnly(tweety, timmy);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateGuardianRefsByRemoveElements(ParsedModel parsedModel) {
    setUp(parsedModel);
    List<Guardian> guardianRefs = new ArrayList<Guardian>(egg1.getGuardianRefs());
    egg1.getGuardianRefs().remove(guardianRefs.get(1));
    assertThat(egg1.getGuardians())
      .hasSize(1)
      .containsOnly(hedwig);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testClearGuardianRefs(ParsedModel parsedModel) {
    setUp(parsedModel);
    egg1.getGuardianRefs().clear();
    assertThat(egg1.getGuardianRefs()).isEmpty();

    // should not affect animals collection
    Animals animals = (Animals) modelInstance.getDocumentElement();
    assertThat(animals.getAnimals())
      .isNotEmpty()
      .hasSize(3);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testGetGuardedEggs(ParsedModel parsedModel) {
    setUp(parsedModel);
    Collection<Egg> guardedEggs = hedwig.getGuardedEggs();
    assertThat(guardedEggs)
      .isNotEmpty()
      .hasSize(2)
      .contains(egg1, egg2);

    guardedEggs = timmy.getGuardedEggs();
    assertThat(guardedEggs)
      .isNotEmpty()
      .hasSize(3)
      .contains(egg1, egg2);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testAddGuardedEggRefsByHelper(ParsedModel parsedModel) {
    setUp(parsedModel);
    assertThat(hedwig.getGuardedEggRefs())
      .isNotEmpty()
      .hasSize(2);

    GuardEgg egg3GuardedEgg = modelInstance.newInstance(GuardEgg.class);
    egg3GuardedEgg.setTextContent(egg3.getId());
    hedwig.getGuardedEggRefs().add(egg3GuardedEgg);

    assertThat(hedwig.getGuardedEggRefs())
      .isNotEmpty()
      .hasSize(3)
      .contains(egg3GuardedEgg);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testGuardedEggRefsByTextContent(ParsedModel parsedModel) {
    setUp(parsedModel);
    Collection<GuardEgg> guardianRefs = timmy.getGuardedEggRefs();
    Collection<String> textContents = new ArrayList<String>();
    for (GuardEgg guardianRef : guardianRefs) {
      String textContent = guardianRef.getTextContent();
      assertThat(textContent).isNotEmpty();
      textContents.addAll(StringUtil.splitListBySeparator(textContent, " "));
    }
    assertThat(textContents)
      .isNotEmpty()
      .hasSize(3)
      .containsOnly(egg1.getId(), egg2.getId(), egg3.getId());
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateGuardedEggRefsByTextContent(ParsedModel parsedModel) {
    setUp(parsedModel);
    List<GuardEgg> guardianRefs = new ArrayList<GuardEgg>(hedwig.getGuardedEggRefs());

    guardianRefs.get(0).setTextContent(egg1.getId() + " " + egg3.getId());

    assertThat(hedwig.getGuardedEggs())
      .hasSize(3)
      .containsOnly(egg1, egg2, egg3);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testUpdateGuardedEggRefsByRemoveElements(ParsedModel parsedModel) {
    setUp(parsedModel);
    List<GuardEgg> guardianRefs = new ArrayList<GuardEgg>(timmy.getGuardedEggRefs());
    timmy.getGuardedEggRefs().remove(guardianRefs.get(0));
    assertThat(timmy.getGuardedEggs())
      .hasSize(1)
      .containsOnly(egg3);
  }

  @ParameterizedTest
  @MethodSource("models")
  public void testClearGuardedEggRefs(ParsedModel parsedModel) {
    setUp(parsedModel);
    timmy.getGuardedEggRefs().clear();
    assertThat(timmy.getGuardedEggRefs()).isEmpty();

    // should not affect animals collection
    Animals animals = (Animals) modelInstance.getDocumentElement();
    assertThat(animals.getAnimals())
      .isNotEmpty()
      .hasSize(3);
  }

}