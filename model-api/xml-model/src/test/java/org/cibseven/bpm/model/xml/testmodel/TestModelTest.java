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
package org.cibseven.bpm.model.xml.testmodel;

import org.cibseven.bpm.model.xml.ModelInstance;
import org.cibseven.bpm.model.xml.impl.ModelInstanceImpl;
import org.cibseven.bpm.model.xml.impl.parser.AbstractModelParser;
import org.cibseven.bpm.model.xml.testmodel.instance.*;
import org.junit.jupiter.api.AfterEach;

import java.io.InputStream;

/**
 * @author Sebastian Menski
 */
public abstract class TestModelTest {

  protected String testName;
//  protected ModelInstance testModelInstance;
  protected AbstractModelParser modelParser;
  protected ModelInstance modelInstance;

  //TODO: remove duplicate code
  public void initializeTestModelTest(ParsedModel parsedModel) {//String testName, ModelInstance testModelInstance, AbstractModelParser modelParser) {
    this.testName = parsedModel.name;
    this.modelInstance = parsedModel.modelInstance;
    this.modelParser = parsedModel.modelParser;
  }

  public static class ParsedModel {
   public String name;
   public TestModelParser modelParser;
   public ModelInstance modelInstance;
   public ParsedModel(String name, ModelInstance modelInstance, TestModelParser modelParser) {
     this.name = name;
     this.modelInstance = modelInstance;
     this.modelParser = modelParser;
     }
  }
  
  public static ParsedModel parseModel(Class<?> test) {
    TestModelParser modelParser = new TestModelParser();
    String testXml = test.getSimpleName() + ".xml";
    InputStream testXmlAsStream = test.getResourceAsStream(testXml);
    ModelInstance modelInstance = modelParser.parseModelFromStream(testXmlAsStream);
    return new ParsedModel(testXml, modelInstance, modelParser);
  }

  public static Bird createBird(ModelInstance modelInstance, String id, Gender gender) {
    Bird bird = modelInstance.newInstance(Bird.class, id);
    bird.setGender(gender);
    Animals animals = (Animals) modelInstance.getDocumentElement();
    animals.getAnimals().add(bird);
    return bird;
  }

  protected static RelationshipDefinition createRelationshipDefinition(ModelInstance modelInstance, Animal animalInRelationshipWith, Class<? extends RelationshipDefinition> relationshipDefinitionClass) {
    RelationshipDefinition relationshipDefinition = modelInstance.newInstance(relationshipDefinitionClass, "relationship-" + animalInRelationshipWith.getId());
    relationshipDefinition.setAnimal(animalInRelationshipWith);
    return relationshipDefinition;
  }

  public static void addRelationshipDefinition(Animal animalWithRelationship, RelationshipDefinition relationshipDefinition) {
    Animal animalInRelationshipWith = relationshipDefinition.getAnimal();
    relationshipDefinition.setId(animalWithRelationship.getId() + "-" + animalInRelationshipWith.getId());
    animalWithRelationship.getRelationshipDefinitions().add(relationshipDefinition);
  }

  public static Egg createEgg(ModelInstance modelInstance, String id) {
    Egg egg = modelInstance.newInstance(Egg.class, id);
    return egg;
  }

  @AfterEach
  public void validateModel() {
    modelParser.validateModel(modelInstance.getDocument());
  }
}