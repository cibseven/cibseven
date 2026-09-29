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
package org.cibseven.bpm.engine.test.cmmn.handler;

import org.cibseven.bpm.engine.impl.cmmn.handler.StageItemHandler;
import org.cibseven.bpm.engine.impl.cmmn.model.CmmnActivity;
import org.cibseven.bpm.engine.test.cmmn.handler.specification.AbstractExecutionListenerSpec;
import org.cibseven.bpm.model.cmmn.instance.DiscretionaryItem;
import org.cibseven.bpm.model.cmmn.instance.PlanningTable;
import org.cibseven.bpm.model.cmmn.instance.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * @author Thorben Lindhauer
 *
 */
public class StageDiscretionaryItemExecutionListenerHandlerTest extends CmmnElementHandlerTest {

  public static Iterable<Object[]> data() {
    return ExecutionListenerCases.TASK_OR_STAGE_CASES;
  }

  protected Stage stage;
  protected PlanningTable planningTable;
  protected DiscretionaryItem discretionaryItem;
  protected StageItemHandler handler = new StageItemHandler();

  @BeforeEach
  public void setUp() {
    stage = createElement(casePlanModel, "aStage", Stage.class);
    planningTable = createElement(casePlanModel, "aPlanningTable", PlanningTable.class);
    discretionaryItem = createElement(planningTable, "DI_aStage", DiscretionaryItem.class);
    discretionaryItem.setDefinition(stage);
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testCaseExecutionListener(AbstractExecutionListenerSpec testSpecification) {
    // given:
    testSpecification.addListenerToElement(modelInstance, stage);

    // when
    CmmnActivity activity = handler.handleElement(discretionaryItem, context);

    // then
    testSpecification.verify(activity);
  }

}