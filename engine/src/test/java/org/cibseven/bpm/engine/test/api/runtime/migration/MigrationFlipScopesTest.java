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
package org.cibseven.bpm.engine.test.api.runtime.migration;

import org.cibseven.bpm.engine.migration.MigrationPlanValidationException;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.api.runtime.migration.models.ProcessModels;
import org.cibseven.bpm.engine.test.util.MigrationPlanValidationReportAssert;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;


/**
 * @author Thorben Lindhauer
 *
 */
public class MigrationFlipScopesTest {

  @RegisterExtension
  protected ProcessEngineRule rule = new ProvidedProcessEngineRule();
  @RegisterExtension
  protected MigrationTestRule testHelper = new MigrationTestRule(rule);

  @Test
  public void testCannotFlipAncestorScopes() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deployAndGetDefinition(ProcessModels.DOUBLE_SUBPROCESS_PROCESS);
    ProcessDefinition targetProcessDefinition = testHelper.deployAndGetDefinition(ProcessModels.DOUBLE_SUBPROCESS_PROCESS);

    // when
    try {
      rule.getRuntimeService()
        .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
        .mapActivities("outerSubProcess", "innerSubProcess")
        .mapActivities("innerSubProcess", "outerSubProcess")
        .mapActivities("userTask", "userTask")
        .build();

      Assertions.fail("should not validate");
    } catch (MigrationPlanValidationException e) {
      MigrationPlanValidationReportAssert.assertThat(e.getValidationReport())
        .hasInstructionFailures("innerSubProcess",
          "The closest mapped ancestor 'outerSubProcess' is mapped to scope 'innerSubProcess' which is not an ancestor of target scope 'outerSubProcess'"
        );
    }
  }
}
