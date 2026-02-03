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
import static org.assertj.core.api.Assertions.fail;
import static org.cibseven.bpm.qa.upgrade.util.ActivityInstanceAssert.assertThat;
import static org.cibseven.bpm.qa.upgrade.util.ActivityInstanceAssert.describeActivityInstanceTree;

import java.util.List;

import org.cibseven.bpm.engine.runtime.ActivityInstance;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.qa.upgrade.Origin;
import org.cibseven.bpm.qa.upgrade.ScenarioUnderTest;
import org.cibseven.bpm.qa.upgrade.UpgradeTestRule;
import org.cibseven.bpm.qa.upgrade.util.ThrowBpmnErrorDelegate;
import org.cibseven.bpm.qa.upgrade.util.ThrowBpmnErrorDelegate.ThrowBpmnErrorDelegateException;
import org.junit.Rule;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@ScenarioUnderTest("ParallelMultiInstanceSubprocessScenario")
@Origin("1.1.0")
public class ParallelMultiInstanceScenarioTest {

  @Rule
  public UpgradeTestRule rule = new UpgradeTestRule();

  @Test
  @ScenarioUnderTest("initNonInterruptingBoundaryEvent.1")
  void initNonInterruptingBoundaryEventCompletionCase1() {
    // given
    List<Task> subProcessTasks = rule.taskQuery().taskDefinitionKey("subProcessTask").list();
    Task afterBoundaryTask = rule.taskQuery().taskDefinitionKey("afterBoundaryTask").singleResult();

    // when all instances are completed
    for (Task subProcessTask : subProcessTasks) {
      rule.getTaskService().complete(subProcessTask.getId());
    }

    // and
    rule.getTaskService().complete(afterBoundaryTask.getId());

    // then
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("initNonInterruptingBoundaryEvent.2")
  void initNonInterruptingBoundaryEventCompletionCase2() {
    // given
    List<Task> subProcessTasks = rule.taskQuery().taskDefinitionKey("subProcessTask").list();
    Task afterBoundaryTask = rule.taskQuery().taskDefinitionKey("afterBoundaryTask").singleResult();

    // when
    rule.getTaskService().complete(afterBoundaryTask.getId());

    for (Task subProcessTask : subProcessTasks) {
      rule.getTaskService().complete(subProcessTask.getId());
    }

    // then
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("initNonInterruptingBoundaryEvent.3")
  void initNonInterruptingBoundaryEventCompletionCase3() {
    // given
    List<Task> subProcessTasks = rule.taskQuery().taskDefinitionKey("subProcessTask").list();
    Task afterBoundaryTask = rule.taskQuery().taskDefinitionKey("afterBoundaryTask").singleResult();

    // when the first instance and the other two instances are completed
    rule.getTaskService().complete(subProcessTasks.get(0).getId());

    rule.getTaskService().complete(afterBoundaryTask.getId());
    rule.getTaskService().complete(subProcessTasks.get(1).getId());
    rule.getTaskService().complete(subProcessTasks.get(2).getId());

    // then
    rule.assertScenarioEnded();
  }

  // TODO: update the expected structure for CIB seven migration and enable the test 
  @Disabled("The structure is not as expected: migration from Camunda 7.2.0 and migration from CIB seven 1.1.0 engine")
  @Test
  @ScenarioUnderTest("initNonInterruptingBoundaryEvent.4")
  void initNonInterruptingBoundaryEventActivityInstanceTree() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when
    ActivityInstance activityInstance = rule.getRuntimeService().getActivityInstance(instance.getId());

    // then
    assertThat(activityInstance).isNotNull();
    assertThat(activityInstance).hasStructure(
        describeActivityInstanceTree(instance.getProcessDefinitionId())
          .activity("afterBoundaryTask")
          .beginScope("miSubProcess")
            .activity("subProcessTask")
          .endScope()
          .beginScope("miSubProcess")
            .activity("subProcessTask")
          .endScope()
          .beginScope("miSubProcess")
            .activity("subProcessTask")
          .endScope()
        .done());
  }

  @Test
  @ScenarioUnderTest("initNonInterruptingBoundaryEvent.5")
  void initNonInterruptingBoundaryEventDeletion() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when
    rule.getRuntimeService().deleteProcessInstance(instance.getId(), null);

    // then
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("initNonInterruptingBoundaryEvent.6")
  void initNonInterruptingBoundaryEventThrowError() {
    // given
    ProcessInstance instance = rule.processInstance();
    Task miSubprocessTask = rule.taskQuery().taskDefinitionKey("subProcessTask").list().get(0);
    Task afterBoundaryTask = rule.taskQuery().taskDefinitionKey("afterBoundaryTask").singleResult();

    // when
    rule.getRuntimeService().setVariable(instance.getId(), ThrowBpmnErrorDelegate.ERROR_INDICATOR_VARIABLE, true);
    rule.getTaskService().complete(miSubprocessTask.getId());

    // then
    assertThat(rule.taskQuery().count()).isEqualTo(2);

    Task escalatedTask = rule.taskQuery().taskDefinitionKey("escalatedTask").singleResult();
    assertThat(escalatedTask).isNotNull();

    // and
    rule.getTaskService().complete(escalatedTask.getId());
    rule.getTaskService().complete(afterBoundaryTask.getId());
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("initNonInterruptingBoundaryEvent.7")
  void initNonInterruptingBoundaryEventThrowUnhandledException() {
    // given
    ProcessInstance instance = rule.processInstance();
    Task miSubprocessTask = rule.taskQuery().taskDefinitionKey("subProcessTask").list().get(0);

    // when
    rule.getRuntimeService().setVariable(instance.getId(), ThrowBpmnErrorDelegate.EXCEPTION_INDICATOR_VARIABLE, true);
    rule.getRuntimeService().setVariable(instance.getId(), ThrowBpmnErrorDelegate.EXCEPTION_MESSAGE_VARIABLE, "unhandledException");

    // then
    try {
      rule.getTaskService().complete(miSubprocessTask.getId());
      fail("should throw a ThrowBpmnErrorDelegateException");

    } catch (ThrowBpmnErrorDelegateException e) {
      assertThat(e.getMessage()).isEqualTo("unhandledException");
    }
  }

}
