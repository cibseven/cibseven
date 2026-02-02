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

/**
 * @author Thorben Lindhauer
 *
 */
@ScenarioUnderTest("NestedInterruptingErrorEventSubprocessScenario")
@Origin("1.1.0")
public class NestedInterruptingErrorEventSubprocessScenarioTest {

  @Rule
  public UpgradeTestRule rule = new UpgradeTestRule();

  // TODO: update the expected structure for CIB seven migration and enable the test 
  @Disabled("The structure is not as expected: migration from Camunda 7.2.0 and migration from CIB seven 1.1.0 engine")
  @Test
  @ScenarioUnderTest("init.1")
  void initActivityInstanceTree() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when
    ActivityInstance activityInstance = rule.getRuntimeService().getActivityInstance(instance.getId());

    // then
    assertThat(activityInstance).isNotNull();
    assertThat(activityInstance).hasStructure(
        describeActivityInstanceTree(instance.getProcessDefinitionId())
          .beginScope("subProcess")
            .activity("innerTask")
            // eventSubProcess was previously no scope so it misses here
            .activity("innerEventSubProcessTask")
        .done());
  }

  @Test
  @ScenarioUnderTest("init.2")
  void initDeletion() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when
    rule.getRuntimeService().deleteProcessInstance(instance.getId(), null);

    // then
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("init.3")
  void initThrowError() {
    // given
    ProcessInstance instance = rule.processInstance();
    Task innerEventSubProcessTask = rule.taskQuery().taskDefinitionKey("innerEventSubProcessTask").singleResult();

    // when
    rule.getRuntimeService().setVariable(instance.getId(), ThrowBpmnErrorDelegate.ERROR_INDICATOR_VARIABLE, true);
    rule.getTaskService().complete(innerEventSubProcessTask.getId());

    // then
    Task outerEventSubProcessTask = rule.taskQuery().singleResult();
    assertThat(outerEventSubProcessTask).isNotNull();
    assertThat(outerEventSubProcessTask.getTaskDefinitionKey()).isEqualTo("outerEventSubProcessTask");

    // and
    rule.getTaskService().complete(outerEventSubProcessTask.getId());
    rule.assertScenarioEnded();

  }

  @Test
  @ScenarioUnderTest("init.4")
  void initThrowErrorActivityInstanceTree() {
    // given
    ProcessInstance instance = rule.processInstance();
    Task innerEventSubProcessTask = rule.taskQuery().taskDefinitionKey("innerEventSubProcessTask").singleResult();

    // when
    rule.getRuntimeService().setVariable(instance.getId(), ThrowBpmnErrorDelegate.ERROR_INDICATOR_VARIABLE, true);
    rule.getTaskService().complete(innerEventSubProcessTask.getId());
    ActivityInstance activityInstance = rule.getRuntimeService().getActivityInstance(instance.getId());

    // then
    assertThat(activityInstance).isNotNull();
    assertThat(activityInstance).hasStructure(
        describeActivityInstanceTree(instance.getProcessDefinitionId())
        // eventSubProcess was previously no scope so it misses here
          .beginScope("outerEventSubProcess")
            .activity("outerEventSubProcessTask")
        .done());

  }

  @Test
  @ScenarioUnderTest("init.5")
  void initThrowUnhandledException() {
    // given
    ProcessInstance instance = rule.processInstance();
    Task innerEventSubProcessTask = rule.taskQuery().taskDefinitionKey("innerEventSubProcessTask").singleResult();

    // when
    rule.getRuntimeService().setVariable(instance.getId(), ThrowBpmnErrorDelegate.EXCEPTION_INDICATOR_VARIABLE, true);
    rule.getRuntimeService().setVariable(instance.getId(), ThrowBpmnErrorDelegate.EXCEPTION_MESSAGE_VARIABLE, "unhandledException");

    // then
    try {
      rule.getTaskService().complete(innerEventSubProcessTask.getId());
      fail("should throw a ThrowBpmnErrorDelegateException");

    } catch (ThrowBpmnErrorDelegateException e) {
      assertThat(e.getMessage()).isEqualTo("unhandledException");
    }
  }
}
