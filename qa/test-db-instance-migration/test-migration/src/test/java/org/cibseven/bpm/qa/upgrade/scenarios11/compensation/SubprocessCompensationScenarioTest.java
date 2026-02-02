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
package org.cibseven.bpm.qa.upgrade.scenarios11.compensation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cibseven.bpm.qa.upgrade.util.ActivityInstanceAssert.assertThat;
import static org.cibseven.bpm.qa.upgrade.util.ActivityInstanceAssert.describeActivityInstanceTree;

import org.cibseven.bpm.engine.runtime.ActivityInstance;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.qa.upgrade.Origin;
import org.cibseven.bpm.qa.upgrade.ScenarioUnderTest;
import org.cibseven.bpm.qa.upgrade.UpgradeTestRule;
import org.junit.Rule;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * @author Thorben Lindhauer
 *
 */
@ScenarioUnderTest("SubprocessCompensationScenario")
@Origin("1.1.0")
public class SubprocessCompensationScenarioTest {

  @Rule
  public UpgradeTestRule rule = new UpgradeTestRule();

  @Test
  @ScenarioUnderTest("init.1")
  void initCompletion() {
    // when compensation is thrown
    Task beforeCompensationTask = rule.taskQuery().singleResult();
    rule.getTaskService().complete(beforeCompensationTask.getId());

    // then there is an active compensation handler task
    Task compensationHandlerTask = rule.taskQuery().singleResult();
    assertThat(compensationHandlerTask).isNotNull();
    assertThat(compensationHandlerTask.getTaskDefinitionKey()).isEqualTo("undoTask");

    // and it can be completed such that the process instance ends successfully
    rule.getTaskService().complete(compensationHandlerTask.getId());

    Task afterCompensateTask = rule.taskQuery().singleResult();
    assertThat(afterCompensateTask).isNotNull();
    assertThat(afterCompensateTask.getTaskDefinitionKey()).isEqualTo("afterCompensate");

    rule.getTaskService().complete(afterCompensateTask.getId());

    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("init.2")
  void initDeletion() {
    // when compensation is thrown
    Task beforeCompensationTask = rule.taskQuery().singleResult();
    rule.getTaskService().complete(beforeCompensationTask.getId());

    // then the process instance can be deleted
    rule.getRuntimeService().deleteProcessInstance(rule.processInstance().getId(), "");

    // and the process is ended
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("init.3")
  void initActivityInstanceTree() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when compensation is thrown
    Task beforeCompensationTask = rule.taskQuery().singleResult();
    rule.getTaskService().complete(beforeCompensationTask.getId());

    // then the activity instance tree is meaningful
    ActivityInstance activityInstance = rule.getRuntimeService().getActivityInstance(instance.getId());
    assertThat(activityInstance).isNotNull();
    assertThat(activityInstance).hasStructure(
      describeActivityInstanceTree(instance.getProcessDefinitionId())
        .activity("throwCompensate")
        .beginScope("subProcess")
          .activity("undoTask")
      .done());
  }

  @Test
  @ScenarioUnderTest("init.triggerCompensation.1")
  void initTriggerCompensationCompletion() {
    // given active compensation
    Task compensationHandlerTask = rule.taskQuery().singleResult();

    // then it is possible to complete compensation and the follow-up task
    rule.getTaskService().complete(compensationHandlerTask.getId());

    Task afterCompensateTask = rule.taskQuery().singleResult();
    assertThat(afterCompensateTask).isNotNull();
    assertThat(afterCompensateTask.getTaskDefinitionKey()).isEqualTo("afterCompensate");

    rule.getTaskService().complete(afterCompensateTask.getId());

    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("init.triggerCompensation.2")
  void initTriggerCompensationDeletion() {
    // given active compensation

    // then the process instance can be deleted
    rule.getRuntimeService().deleteProcessInstance(rule.processInstance().getId(), "");

    // and the process is ended
    rule.assertScenarioEnded();
  }

  // TODO: update the expected structure for CIB seven migration and enable the test 
  @Disabled("The structure is not as expected: migration from Camunda 7.2.0 and migration from CIB seven 1.1.0 engine")
  @Test
  @ScenarioUnderTest("init.triggerCompensation.3")
  void initTriggerCompensationActivityInstanceTree() {
    // given active compensation
    ProcessInstance instance = rule.processInstance();

    // then the activity instance tree is meaningful
    ActivityInstance activityInstance = rule.getRuntimeService().getActivityInstance(instance.getId());
    assertThat(activityInstance).isNotNull();
    assertThat(activityInstance).hasStructure(
      describeActivityInstanceTree(instance.getProcessDefinitionId())
        .beginScope("throwCompensate")
        // due to different activity instance id assingment in >= 7.4,
        // the subProcess instance is not represented and undoTask is a child of throwCompensate
//        .beginScope("subProcess")
          .activity("undoTask")
      .done());
  }

  @Test
  @ScenarioUnderTest("init.concurrent.1")
  void initConcurrentCompletion() {
    // when compensation is thrown
    Task beforeCompensationTask = rule.taskQuery().singleResult();
    rule.getTaskService().complete(beforeCompensationTask.getId());

    // then there are two active compensation handler task
    assertThat(rule.taskQuery().count()).isEqualTo(2);
    Task undoTask1 = rule.taskQuery().taskDefinitionKey("undoTask1").singleResult();
    assertThat(undoTask1).isNotNull();

    Task undoTask2 = rule.taskQuery().taskDefinitionKey("undoTask2").singleResult();
    assertThat(undoTask2).isNotNull();

    // and they can be completed such that the process instance ends successfully
    rule.getTaskService().complete(undoTask1.getId());
    rule.getTaskService().complete(undoTask2.getId());

    Task afterCompensateTask = rule.taskQuery().singleResult();
    assertThat(afterCompensateTask).isNotNull();
    assertThat(afterCompensateTask.getTaskDefinitionKey()).isEqualTo("afterCompensate");

    rule.getTaskService().complete(afterCompensateTask.getId());

    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("init.concurrent.2")
  void initConcurrentDeletion() {
    // when compensation is thrown
    Task beforeCompensationTask = rule.taskQuery().singleResult();
    rule.getTaskService().complete(beforeCompensationTask.getId());

    // then the process instance can be deleted
    rule.getRuntimeService().deleteProcessInstance(rule.processInstance().getId(), "");

    // and the process is ended
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("init.concurrent.3")
  void initConcurrentActivityInstanceTree() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when compensation is thrown
    Task beforeCompensationTask = rule.taskQuery().singleResult();
    rule.getTaskService().complete(beforeCompensationTask.getId());

    // then the activity instance tree is meaningful
    ActivityInstance activityInstance = rule.getRuntimeService().getActivityInstance(instance.getId());
    assertThat(activityInstance).isNotNull();
    assertThat(activityInstance).hasStructure(
      describeActivityInstanceTree(instance.getProcessDefinitionId())
        .activity("throwCompensate")
        .beginScope("subProcess")
          .activity("undoTask1")
          .activity("undoTask2")
      .done());
  }

  @Test
  @ScenarioUnderTest("init.concurrent.triggerCompensation.1")
  void initConcurrentTriggerCompensationCompletion() {
    // given active compensation
    Task undoTask1 = rule.taskQuery().taskDefinitionKey("undoTask1").singleResult();
    Task undoTask2 = rule.taskQuery().taskDefinitionKey("undoTask2").singleResult();

    // then it is possible to complete compensation and the follow-up task
    rule.getTaskService().complete(undoTask1.getId());
    rule.getTaskService().complete(undoTask2.getId());

    Task afterCompensateTask = rule.taskQuery().singleResult();
    assertThat(afterCompensateTask).isNotNull();
    assertThat(afterCompensateTask.getTaskDefinitionKey()).isEqualTo("afterCompensate");

    rule.getTaskService().complete(afterCompensateTask.getId());

    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("init.concurrent.triggerCompensation.2")
  void initConcurrentTriggerCompensationDeletion() {
    // given active compensation

    // then the process instance can be deleted
    rule.getRuntimeService().deleteProcessInstance(rule.processInstance().getId(), "");

    // and the process is ended
    rule.assertScenarioEnded();
  }

  // TODO: update the expected structure for CIB seven migration and enable the test 
  @Disabled("The structure is not as expected: migration from Camunda 7.2.0 and migration from CIB seven 1.1.0 engine")
  @Test
  @ScenarioUnderTest("init.concurrent.triggerCompensation.3")
  void initConcurrentTriggerCompensationActivityInstanceTree() {
    // given active compensation
    ProcessInstance instance = rule.processInstance();

    // then the activity instance tree is meaningful
    ActivityInstance activityInstance = rule.getRuntimeService().getActivityInstance(instance.getId());
    assertThat(activityInstance).isNotNull();
    assertThat(activityInstance).hasStructure(
      describeActivityInstanceTree(instance.getProcessDefinitionId())
        .beginScope("throwCompensate")
        // due to different activity instance id assingment in >= 7.4,
        // the subProcess instance is not represented and undoTask is a child of throwCompensate
//        .beginScope("subProcess")
          .activity("undoTask1")
          .activity("undoTask2")
      .done());
  }
}
