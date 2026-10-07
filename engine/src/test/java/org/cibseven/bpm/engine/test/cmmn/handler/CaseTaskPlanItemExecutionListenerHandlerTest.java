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

import org.cibseven.bpm.engine.impl.cmmn.handler.CaseTaskItemHandler;
import org.cibseven.bpm.engine.impl.cmmn.model.CmmnActivity;
import org.cibseven.bpm.engine.test.cmmn.handler.specification.AbstractExecutionListenerSpec;
import org.cibseven.bpm.model.cmmn.instance.CaseTask;
import org.cibseven.bpm.model.cmmn.instance.PlanItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * @author Thorben Lindhauer
 *
 */
public class CaseTaskPlanItemExecutionListenerHandlerTest extends CmmnElementHandlerTest {

  public static Iterable<Object[]> data() {
    return ExecutionListenerCases.TASK_OR_STAGE_CASES;
  }

  protected CaseTask caseTask;
  protected PlanItem planItem;
  protected CaseTaskItemHandler handler = new CaseTaskItemHandler();

  @BeforeEach
  public void setUp() {
    caseTask = createElement(casePlanModel, "aCaseTask", CaseTask.class);

    planItem = createElement(casePlanModel, "PI_aCaseTask", PlanItem.class);
    planItem.setDefinition(caseTask);
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testCaseExecutionListener(AbstractExecutionListenerSpec testSpecification) {
    // given:
    testSpecification.addListenerToElement(modelInstance, caseTask);

    // when
    CmmnActivity activity = handler.handleElement(planItem, context);

    // then
    testSpecification.verify(activity);
  }
}