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
package org.cibseven.bpm.qa.upgrade.scenarios11.multiinstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cibseven.bpm.qa.upgrade.util.ActivityInstanceAssert.assertThat;
import static org.cibseven.bpm.qa.upgrade.util.ActivityInstanceAssert.describeActivityInstanceTree;

import org.cibseven.bpm.engine.migration.MigrationPlan;
import org.cibseven.bpm.engine.runtime.ActivityInstance;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.qa.upgrade.Origin;
import org.cibseven.bpm.qa.upgrade.ScenarioUnderTest;
import org.cibseven.bpm.qa.upgrade.UpgradeTestRule;
import org.junit.Rule;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@ScenarioUnderTest("MultiInstanceReceiveTaskScenario")
@Origin("1.1.0")
public class MultiInstanceReceiveTaskScenarioTest {

  @Rule
  public UpgradeTestRule rule = new UpgradeTestRule();

  @Test
  @ScenarioUnderTest("initParallel.1")
  void initParallelCompletion() {
    // when the receive task messages are correlated
    rule.messageCorrelation("Message").correlateAll();

    // then
    rule.assertScenarioEnded();
  }

  // TODO: update the expected structure for CIB seven migration and enable the test 
  @Disabled("The structure is not as expected: migration from Camunda 7.2.0 and migration from CIB seven 1.1.0 engine")
  @Test
  @ScenarioUnderTest("initParallel.2")
  void initParallelActivityInstanceTree() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when
    ActivityInstance activityInstance = rule.getRuntimeService().getActivityInstance(instance.getId());

    // then
    assertThat(activityInstance).isNotNull();
    assertThat(activityInstance).hasStructure(
      describeActivityInstanceTree(instance.getProcessDefinitionId())
        // no mi body due to missing execution
        .activity("miReceiveTask")
        .activity("miReceiveTask")
        .activity("miReceiveTask")
      .done());
  }

  @Test
  @ScenarioUnderTest("initParallel.3")
  void initParallelDeletion() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when
    rule.getRuntimeService().deleteProcessInstance(instance.getId(), null);

    // then
    rule.assertScenarioEnded();
  }

  @Test
  @Disabled("CAM-6408")
  @ScenarioUnderTest("initParallel.4")
  void initParallelMigration() {
    // given
    ProcessInstance instance = rule.processInstance();
    MigrationPlan migrationPlan = rule.getRuntimeService()
      .createMigrationPlan(instance.getProcessDefinitionId(), instance.getProcessDefinitionId())
      .mapEqualActivities()
      .build();

    // when
    rule.getRuntimeService().newMigration(migrationPlan)
      .processInstanceIds(instance.getId())
      .execute();

    // then the receive task messages can be correlated
    rule.messageCorrelation("Message").correlateAll();
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("initSequential.1")
  void initSequentialCompletion() {
    // when the receive task messages are correlated
    for (int i = 0; i < 3; i++) {
      rule.messageCorrelation("Message").correlate();
    }

    // then
    rule.assertScenarioEnded();
  }

  // TODO: update the expected structure for CIB seven migration and enable the test 
  @Disabled("The structure is not as expected: migration from Camunda 7.2.0 and migration from CIB seven 1.1.0 engine")
  @Test
  @ScenarioUnderTest("initSequential.2")
  void initSequentialActivityInstanceTree() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when
    ActivityInstance activityInstance = rule.getRuntimeService().getActivityInstance(instance.getId());

    // then
    assertThat(activityInstance).isNotNull();
    assertThat(activityInstance).hasStructure(
      describeActivityInstanceTree(instance.getProcessDefinitionId())
        .activity("miReceiveTask")
      .done());
  }

  @Test
  @ScenarioUnderTest("initSequential.3")
  void initSequentialDeletion() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when
    rule.getRuntimeService().deleteProcessInstance(instance.getId(), null);

    // then
    rule.assertScenarioEnded();
  }

  @Test
  @Disabled("CAM-6408")
  @ScenarioUnderTest("initSequential.4")
  void initSequentialMigration() {
    // given
    ProcessInstance instance = rule.processInstance();
    MigrationPlan migrationPlan = rule.getRuntimeService()
      .createMigrationPlan(instance.getProcessDefinitionId(), instance.getProcessDefinitionId())
      .mapEqualActivities()
      .build();

    // when
    rule.getRuntimeService().newMigration(migrationPlan)
      .processInstanceIds(instance.getId())
      .execute();

    // then the receive task messages can be correlated
    for (int i = 0; i < 3; i++) {
      rule.messageCorrelation("Message").correlate();
    }

    rule.assertScenarioEnded();
  }

}
