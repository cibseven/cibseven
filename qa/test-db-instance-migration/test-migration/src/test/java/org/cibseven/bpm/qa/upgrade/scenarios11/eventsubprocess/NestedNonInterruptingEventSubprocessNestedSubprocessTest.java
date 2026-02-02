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
package org.cibseven.bpm.qa.upgrade.scenarios11.eventsubprocess;

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

@ScenarioUnderTest("NestedNonInterruptingEventSubprocessNestedSubprocessScenario")
@Origin("1.1.0")
public class NestedNonInterruptingEventSubprocessNestedSubprocessTest {

  @Rule
  public UpgradeTestRule rule = new UpgradeTestRule();

  @Test
  @ScenarioUnderTest("init.1")
  void initCompletionCase1() {
    // given
    Task outerSubProcessTask = rule.taskQuery().taskDefinitionKey("outerSubProcessTask").singleResult();
    Task eventSubprocessTask = rule.taskQuery().taskDefinitionKey("eventSubProcessTask").singleResult();

    // when
    rule.getTaskService().complete(outerSubProcessTask.getId());
    rule.getTaskService().complete(eventSubprocessTask.getId());

    // then
    Task innerSubprocessTask = rule.taskQuery().singleResult();
    assertThat(innerSubprocessTask).isNotNull();
    rule.getTaskService().complete(innerSubprocessTask.getId());

    // and
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("init.2")
  void initCompletionCase2() {
    // given
    Task outerSubProcessTask = rule.taskQuery().taskDefinitionKey("outerSubProcessTask").singleResult();
    Task eventSubprocessTask = rule.taskQuery().taskDefinitionKey("eventSubProcessTask").singleResult();

    // when
    rule.getTaskService().complete(eventSubprocessTask.getId());
    rule.getTaskService().complete(outerSubProcessTask.getId());

    // then
    Task innerSubprocessTask = rule.taskQuery().singleResult();
    assertThat(innerSubprocessTask).isNotNull();
    rule.getTaskService().complete(innerSubprocessTask.getId());

    // and
    rule.assertScenarioEnded();
  }

  // TODO: update the expected structure for CIB seven migration and enable the test 
  @Disabled("The structure is not as expected: migration from Camunda 7.2.0 and migration from CIB seven 1.1.0 engine")
  @Test
  @ScenarioUnderTest("init.3")
  void initActivityInstanceTree() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when
    ActivityInstance activityInstance = rule.getRuntimeService().getActivityInstance(instance.getId());

    // then
    assertThat(activityInstance).isNotNull();
    assertThat(activityInstance).hasStructure(
        describeActivityInstanceTree(instance.getProcessDefinitionId())
          .beginScope("outerSubProcess")
            .activity("outerSubProcessTask")
            // eventSubProcess was previously no scope so it misses here
            .activity("eventSubProcessTask")
        .done());
  }

  @Test
  @ScenarioUnderTest("init.4")
  void initDeletion() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when
    rule.getRuntimeService().deleteProcessInstance(instance.getId(), null);

    // then
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("init.5")
  void initThrowError() {
    // given
    ProcessInstance instance = rule.processInstance();
    Task eventSubProcessTask = rule.taskQuery().taskDefinitionKey("eventSubProcessTask").singleResult();

    // when
    rule.getTaskService().complete(eventSubProcessTask.getId());

    // and
    rule.getRuntimeService().setVariable(instance.getId(), ThrowBpmnErrorDelegate.ERROR_INDICATOR_VARIABLE, true);
    Task innerSubProcessTask = rule.taskQuery().taskDefinitionKey("innerSubProcessTask").singleResult();
    assertThat(innerSubProcessTask).isNotNull();
    rule.getTaskService().complete(innerSubProcessTask.getId());

    // then
    Task afterErrorTask = rule.taskQuery().singleResult();
    assertThat(afterErrorTask).isNotNull();
    assertThat(afterErrorTask.getTaskDefinitionKey()).isEqualTo("escalatedTask");

    // and
    rule.getTaskService().complete(afterErrorTask.getId());
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("init.6")
  void initThrowUnhandledException() {
    // given
    ProcessInstance instance = rule.processInstance();
    Task eventSubProcessTask = rule.taskQuery().taskDefinitionKey("eventSubProcessTask").singleResult();

    // when
    rule.getTaskService().complete(eventSubProcessTask.getId());

    // and
    rule.getRuntimeService().setVariable(instance.getId(), ThrowBpmnErrorDelegate.EXCEPTION_INDICATOR_VARIABLE, true);
    rule.getRuntimeService().setVariable(instance.getId(), ThrowBpmnErrorDelegate.EXCEPTION_MESSAGE_VARIABLE, "unhandledException");
    Task innerSubProcessTask = rule.taskQuery().taskDefinitionKey("innerSubProcessTask").singleResult();
    assertThat(innerSubProcessTask).isNotNull();

    // then
    try {
      rule.getTaskService().complete(innerSubProcessTask.getId());
      fail("should throw a ThrowBpmnErrorDelegateException");

    } catch (ThrowBpmnErrorDelegateException e) {
      assertThat(e.getMessage()).isEqualTo("unhandledException");
    }
  }

  @Test
  @ScenarioUnderTest("init.innerSubProcess.1")
  void initInnerSubProcessCompletionCase1() {
    // given
    Task outerSubProcessTask = rule.taskQuery().taskDefinitionKey("outerSubProcessTask").singleResult();
    Task innerSubProcessTask = rule.taskQuery().taskDefinitionKey("innerSubProcessTask").singleResult();

    // when
    rule.getTaskService().complete(outerSubProcessTask.getId());
    rule.getTaskService().complete(innerSubProcessTask.getId());

    // then
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("init.innerSubProcess.2")
  void initInnerSubProcessCompletionCase2() {
    // given
    Task outerSubProcessTask = rule.taskQuery().taskDefinitionKey("outerSubProcessTask").singleResult();
    Task innerSubProcessTask = rule.taskQuery().taskDefinitionKey("innerSubProcessTask").singleResult();

    // when
    rule.getTaskService().complete(innerSubProcessTask.getId());
    rule.getTaskService().complete(outerSubProcessTask.getId());

    // then
    rule.assertScenarioEnded();
  }

  // TODO: update the expected structure for CIB seven migration and enable the test 
  @Disabled("The structure is not as expected: migration from Camunda 7.2.0 and migration from CIB seven 1.1.0 engine")
  @Test
  @ScenarioUnderTest("init.innerSubProcess.3")
  void initInnerSubProcessActivityInstanceTree() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when
    ActivityInstance activityInstance = rule.getRuntimeService().getActivityInstance(instance.getId());

    // then
    assertThat(activityInstance).isNotNull();
    assertThat(activityInstance).hasStructure(
        describeActivityInstanceTree(instance.getProcessDefinitionId())
          .beginScope("outerSubProcess")
            .activity("outerSubProcessTask")
            // eventSubProcess was previously no scope so it misses here
            .beginScope("innerSubProcess")
              .activity("innerSubProcessTask")
        .done());
  }

  @Test
  @ScenarioUnderTest("init.innerSubProcess.4")
  void initInnerSubProcessDeletion() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when
    rule.getRuntimeService().deleteProcessInstance(instance.getId(), null);

    // then
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("init.innerSubProcess.5")
  void initInnerSubProcessThrowError() {
    // given
    ProcessInstance instance = rule.processInstance();
    Task innerSubProcessTask = rule.taskQuery().taskDefinitionKey("innerSubProcessTask").singleResult();

    // when
    rule.getRuntimeService().setVariable(instance.getId(), ThrowBpmnErrorDelegate.ERROR_INDICATOR_VARIABLE, true);
    rule.getTaskService().complete(innerSubProcessTask.getId());

    // then
    Task afterErrorTask = rule.taskQuery().singleResult();
    assertThat(afterErrorTask).isNotNull();
    assertThat(afterErrorTask.getTaskDefinitionKey()).isEqualTo("escalatedTask");

    // and
    rule.getTaskService().complete(afterErrorTask.getId());
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("init.innerSubProcess.6")
  void initInnerSubProcessThrowUnhandledException() {
    // given
    ProcessInstance instance = rule.processInstance();
    Task innerSubProcessTask = rule.taskQuery().taskDefinitionKey("innerSubProcessTask").singleResult();

    // when
    rule.getRuntimeService().setVariable(instance.getId(), ThrowBpmnErrorDelegate.EXCEPTION_INDICATOR_VARIABLE, true);
    rule.getRuntimeService().setVariable(instance.getId(), ThrowBpmnErrorDelegate.EXCEPTION_MESSAGE_VARIABLE, "unhandledException");

    // then
    try {
      rule.getTaskService().complete(innerSubProcessTask.getId());
      fail("should throw a ThrowBpmnErrorDelegateException");

    } catch (ThrowBpmnErrorDelegateException e) {
      assertThat(e.getMessage()).isEqualTo("unhandledException");
    }
  }

}
