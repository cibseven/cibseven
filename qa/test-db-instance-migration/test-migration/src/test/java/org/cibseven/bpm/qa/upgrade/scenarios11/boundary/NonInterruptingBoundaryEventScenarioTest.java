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
package org.cibseven.bpm.qa.upgrade.scenarios11.boundary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cibseven.bpm.qa.upgrade.util.ActivityInstanceAssert.assertThat;
import static org.cibseven.bpm.qa.upgrade.util.ActivityInstanceAssert.describeActivityInstanceTree;

import java.util.List;

import org.cibseven.bpm.engine.runtime.ActivityInstance;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.qa.upgrade.Origin;
import org.cibseven.bpm.qa.upgrade.ScenarioUnderTest;
import org.cibseven.bpm.qa.upgrade.UpgradeTestRule;
import org.junit.Rule;
import org.junit.jupiter.api.Test;

@ScenarioUnderTest("NonInterruptingBoundaryEventScenario")
@Origin("1.1.0")
public class NonInterruptingBoundaryEventScenarioTest {

  @Rule
  public UpgradeTestRule rule = new UpgradeTestRule();

  @Test
  @ScenarioUnderTest("initTimer.1")
  void initTimerCompletionCase1() {
    // given
    Task afterBoundaryTask = rule.taskQuery().taskDefinitionKey("afterBoundaryTask").singleResult();

    // when
    rule.messageCorrelation("ReceiveTaskMessage").correlate();
    rule.getTaskService().complete(afterBoundaryTask.getId());

    // then
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("initTimer.2")
  void initTimerCompletionCase2() {
    // given
    Task afterBoundaryTask = rule.taskQuery().taskDefinitionKey("afterBoundaryTask").singleResult();

    // when
    rule.getTaskService().complete(afterBoundaryTask.getId());
    rule.messageCorrelation("ReceiveTaskMessage").correlate();

    // then
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("initTimer.3")
  void initTimerActivityInstanceTree() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when
    ActivityInstance activityInstance = rule.getRuntimeService().getActivityInstance(instance.getId());

    // then
    assertThat(activityInstance).isNotNull();
    assertThat(activityInstance).hasStructure(
        describeActivityInstanceTree(instance.getProcessDefinitionId())
          .activity("afterBoundaryTask")
          .activity("outerTask")
        .done());
  }

  @Test
  @ScenarioUnderTest("initTimer.4")
  void initTimerDeletion() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when
    rule.getRuntimeService().deleteProcessInstance(instance.getId(), null);

    // then
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("initTimer.5")
  void initTimerTriggerBoundary() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when the boundary event is triggered another 2 times
    for (int i = 0; i < 2; i++) {
      Job job = rule.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).singleResult();
      rule.getManagementService().executeJob(job.getId());
    }

    // and the tasks are completed
    List<Task> afterBoundaryTasks = rule.taskQuery().list();
    assertThat(afterBoundaryTasks).hasSize(3);

    for (Task afterBoundaryTask : afterBoundaryTasks) {
      rule.getTaskService().complete(afterBoundaryTask.getId());
    }

    rule.messageCorrelation("ReceiveTaskMessage").correlate();

    // then
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("initMessage.1")
  void initMessageCompletionCase1() {
    // given
    Task afterBoundaryTask = rule.taskQuery().taskDefinitionKey("afterBoundaryTask").singleResult();

    // when
    rule.messageCorrelation("ReceiveTaskMessage").correlate();
    rule.getTaskService().complete(afterBoundaryTask.getId());

    // then
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("initMessage.2")
  void initMessageCompletionCase2() {
    // given
    Task afterBoundaryTask = rule.taskQuery().taskDefinitionKey("afterBoundaryTask").singleResult();

    // when
    rule.getTaskService().complete(afterBoundaryTask.getId());
    rule.messageCorrelation("ReceiveTaskMessage").correlate();

    // then
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("initMessage.3")
  void initMessageCompletionCase3() {
    // given
    Task existingAfterBoundaryTask = rule.taskQuery().taskDefinitionKey("afterBoundaryTask").singleResult();

    // when
    rule.messageCorrelation("BoundaryEventMessage").correlate();
    List<Task> afterBoundaryTasks = rule.taskQuery().taskDefinitionKey("afterBoundaryTask").list();

    assertThat(afterBoundaryTasks).hasSize(2);

    Task newAfterBoundaryTask = afterBoundaryTasks.get(0);
    if (newAfterBoundaryTask.getId().equals(existingAfterBoundaryTask.getId())) {
      newAfterBoundaryTask = afterBoundaryTasks.get(1);
    }

    rule.getTaskService().complete(existingAfterBoundaryTask.getId());
    rule.getTaskService().complete(newAfterBoundaryTask.getId());
    rule.messageCorrelation("ReceiveTaskMessage").correlate();

    // then
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("initMessage.4")
  void initMessageActivityInstanceTree() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when
    ActivityInstance activityInstance = rule.getRuntimeService().getActivityInstance(instance.getId());

    // then
    assertThat(activityInstance).isNotNull();
    assertThat(activityInstance).hasStructure(
        describeActivityInstanceTree(instance.getProcessDefinitionId())
          .activity("afterBoundaryTask")
          .activity("outerTask")
        .done());
  }

  @Test
  @ScenarioUnderTest("initMessage.5")
  void initMessageDeletion() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when
    rule.getRuntimeService().deleteProcessInstance(instance.getId(), null);

    // then
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("initMessage.6")
  void initMessageTriggerBoundary() {
    // when the boundary event is triggered another 2 times
    for (int i = 0; i < 2; i++) {
      rule.messageCorrelation("BoundaryEventMessage").correlate();
    }

    // and the tasks are completed
    List<Task> afterBoundaryTasks = rule.taskQuery().list();
    assertThat(afterBoundaryTasks).hasSize(3);

    for (Task afterBoundaryTask : afterBoundaryTasks) {
      rule.getTaskService().complete(afterBoundaryTask.getId());
    }

    rule.messageCorrelation("ReceiveTaskMessage").correlate();

    // then
    rule.assertScenarioEnded();
  }

}
