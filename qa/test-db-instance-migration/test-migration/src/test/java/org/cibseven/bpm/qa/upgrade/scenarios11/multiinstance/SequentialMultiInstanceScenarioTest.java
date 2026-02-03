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

@ScenarioUnderTest("SequentialMultiInstanceSubprocessScenario")
@Origin("1.1.0")
public class SequentialMultiInstanceScenarioTest {

  @Rule
  public UpgradeTestRule rule = new UpgradeTestRule();

  @Test
  @ScenarioUnderTest("init.1")
  void initCompletionCase1() {
    // given
    Task subProcessTask = rule.taskQuery().taskDefinitionKey("subProcessTask").singleResult();

    // when the first instance and the other two instances are completed
    rule.getTaskService().complete(subProcessTask.getId());

    for (int i = 0; i < 2; i++) {
      subProcessTask = rule.taskQuery().taskDefinitionKey("subProcessTask").singleResult();
      assertThat(subProcessTask).isNotNull();
      rule.getTaskService().complete(subProcessTask.getId());
    }

    // then
    rule.assertScenarioEnded();
  }

  // TODO: update the expected structure for CIB seven migration and enable the test 
  @Disabled("The structure is not as expected: migration from Camunda 7.2.0 and migration from CIB seven 1.1.0 engine")
  @Test
  @ScenarioUnderTest("init.2")
  void initActivityInstanceTree() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when
    ActivityInstance activityInstance = rule.getRuntimeService().getActivityInstance(instance.getId());

    // then
    assertThat(activityInstance).isNotNull();
    assertThat(activityInstance).hasStructure(
      describeActivityInstanceTree(instance.getProcessDefinitionId())
        .beginMiBody("miSubProcess")
          // the subprocess itself misses because it was no scope in 7.2
          .activity("subProcessTask")
      .done());
  }

  @Test
  @ScenarioUnderTest("init.3")
  void initDeletion() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when
    rule.getRuntimeService().deleteProcessInstance(instance.getId(), null);

    // then
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("init.4")
  void initThrowError() {
    // given
    ProcessInstance instance = rule.processInstance();
    Task miSubprocessTask = rule.taskQuery().taskDefinitionKey("subProcessTask").singleResult();

    // when
    rule.getRuntimeService().setVariable(instance.getId(), ThrowBpmnErrorDelegate.ERROR_INDICATOR_VARIABLE, true);
    rule.getTaskService().complete(miSubprocessTask.getId());

    // then
    Task escalatedTask = rule.taskQuery().singleResult();
    assertThat(escalatedTask.getTaskDefinitionKey()).isEqualTo("escalatedTask");
    assertThat(escalatedTask).isNotNull();

    rule.getTaskService().complete(escalatedTask.getId());
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("init.5")
  void initUnhandledException() {
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

  @Test
  @ScenarioUnderTest("initNonInterruptingBoundaryEvent.1")
  void initNonInterruptingBoundaryEventCompletionCase1() {
    // given
    Task subProcessTask = rule.taskQuery().taskDefinitionKey("subProcessTask").singleResult();
    Task afterBoundaryTask = rule.taskQuery().taskDefinitionKey("afterBoundaryTask").singleResult();

    // when the first instance and the other two instances are completed
    rule.getTaskService().complete(subProcessTask.getId());

    for (int i = 0; i < 2; i++) {
      subProcessTask = rule.taskQuery().taskDefinitionKey("subProcessTask").singleResult();
      assertThat(subProcessTask).isNotNull();
      rule.getTaskService().complete(subProcessTask.getId());
    }

    rule.getTaskService().complete(afterBoundaryTask.getId());

    // then
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("initNonInterruptingBoundaryEvent.2")
  void initNonInterruptingBoundaryEventCompletionCase2() {
    // given
    Task subProcessTask = rule.taskQuery().taskDefinitionKey("subProcessTask").singleResult();
    Task afterBoundaryTask = rule.taskQuery().taskDefinitionKey("afterBoundaryTask").singleResult();

    // when the first instance and the other two instances are completed
    rule.getTaskService().complete(afterBoundaryTask.getId());

    rule.getTaskService().complete(subProcessTask.getId());
    for (int i = 0; i < 2; i++) {
      subProcessTask = rule.taskQuery().taskDefinitionKey("subProcessTask").singleResult();
      assertThat(subProcessTask).isNotNull();
      rule.getTaskService().complete(subProcessTask.getId());
    }


    // then
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("initNonInterruptingBoundaryEvent.3")
  void initNonInterruptingBoundaryEventCompletionCase3() {
    // given
    Task subProcessTask = rule.taskQuery().taskDefinitionKey("subProcessTask").singleResult();
    Task afterBoundaryTask = rule.taskQuery().taskDefinitionKey("afterBoundaryTask").singleResult();

    // when the first instance and the other two instances are completed
    rule.getTaskService().complete(subProcessTask.getId());

    rule.getTaskService().complete(afterBoundaryTask.getId());

    for (int i = 0; i < 2; i++) {
      subProcessTask = rule.taskQuery().taskDefinitionKey("subProcessTask").singleResult();
      assertThat(subProcessTask).isNotNull();
      rule.getTaskService().complete(subProcessTask.getId());
    }


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
        .beginMiBody("miSubProcess")
          // the subprocess itself misses because it was no scope in 7.2
          .activity("subProcessTask")
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
    Task miSubprocessTask = rule.taskQuery().taskDefinitionKey("subProcessTask").singleResult();
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
