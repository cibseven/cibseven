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

/**
 * @author Thorben Lindhauer
 *
 */
@ScenarioUnderTest("TwoLevelNestedNonInterruptingEventSubprocessScenario")
@Origin("1.1.0")
public class TwoLevelNestedNonInterruptingEventSubprocessScenarioTest {

  @Rule
  public UpgradeTestRule rule = new UpgradeTestRule();

  @Test
  @ScenarioUnderTest("initLevel1.1")
  void initLevel1CompletionCase1() {
    // given
    Task outerTask = rule.taskQuery().taskDefinitionKey("outerTask").singleResult();
    Task innerTask = rule.taskQuery().taskDefinitionKey("subProcessTask").singleResult();

    // when
    rule.getTaskService().complete(outerTask.getId());
    rule.getTaskService().complete(innerTask.getId());

    // then
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("initLevel1.2")
  void initLevel1CompletionCase2() {
    // given
    Task outerTask = rule.taskQuery().taskDefinitionKey("outerTask").singleResult();
    Task innerTask = rule.taskQuery().taskDefinitionKey("subProcessTask").singleResult();

    // when
    rule.getTaskService().complete(innerTask.getId());
    rule.getTaskService().complete(outerTask.getId());

    // then
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("initLevel1.3")
  void initLevel1CompletionCase3() {
    // given
    Task outerTask = rule.taskQuery().taskDefinitionKey("outerTask").singleResult();
    Task innerTask = rule.taskQuery().taskDefinitionKey("subProcessTask").singleResult();

    // when
    rule.messageCorrelation("InnerEventSubProcessMessage").correlate();

    // then
    assertThat(rule.taskQuery().count()).isEqualTo(3);

    Task innerEventSubprocessTask = rule.taskQuery().taskDefinitionKey("innerEventSubProcessTask").singleResult();
    assertThat(innerEventSubprocessTask).isNotNull();

    // and
    rule.getTaskService().complete(innerTask.getId());
    rule.getTaskService().complete(innerEventSubprocessTask.getId());
    rule.getTaskService().complete(outerTask.getId());

    rule.assertScenarioEnded();
  }

  // TODO: update the expected structure for CIB seven migration and enable the test 
  @Disabled("The structure is not as expected: migration from Camunda 7.2.0 and migration from CIB seven 1.1.0 engine")
  @Test
  @ScenarioUnderTest("initLevel1.4")
  void initLevel1ActivityInstanceTree() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when
    ActivityInstance activityInstance = rule.getRuntimeService().getActivityInstance(instance.getId());

    // then
    assertThat(activityInstance).isNotNull();
    assertThat(activityInstance).hasStructure(
        describeActivityInstanceTree(instance.getProcessDefinitionId())
          .activity("outerTask")
          // eventSubProcess was previously no scope so it misses here
          .beginScope("subProcess")
            .activity("subProcessTask")
        .done());
  }

  @Test
  @ScenarioUnderTest("initLevel1.5")
  void initLevel1Deletion() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when
    rule.getRuntimeService().deleteProcessInstance(instance.getId(), null);

    // then
    rule.assertScenarioEnded();
  }

  @ScenarioUnderTest("initLevel1.6")
  public void testInitLevel1ThrowError() {
    // given
    ProcessInstance instance = rule.processInstance();
    rule.messageCorrelation("InnerEventSubProcessMessage").correlate();
    Task innerEventSubprocessTask = rule.taskQuery().taskDefinitionKey("innerEventSubProcessTask").singleResult();

    // when
    rule.getRuntimeService().setVariable(instance.getId(), ThrowBpmnErrorDelegate.ERROR_INDICATOR_VARIABLE, true);
    rule.getTaskService().complete(innerEventSubprocessTask.getId());

    // then
    Task escalatedTask = rule.taskQuery().singleResult();
    assertThat(escalatedTask.getTaskDefinitionKey()).isEqualTo("escalatedTask");
    assertThat(escalatedTask).isNotNull();

    rule.getTaskService().complete(escalatedTask.getId());
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("initLevel1.7")
  void initLevel1ThrowUnhandledException() {
    // given
    ProcessInstance instance = rule.processInstance();
    rule.messageCorrelation("InnerEventSubProcessMessage").correlate();
    Task innerEventSubprocessTask = rule.taskQuery().taskDefinitionKey("innerEventSubProcessTask").singleResult();

    // when
    rule.getRuntimeService().setVariable(instance.getId(), ThrowBpmnErrorDelegate.EXCEPTION_INDICATOR_VARIABLE, true);
    rule.getRuntimeService().setVariable(instance.getId(), ThrowBpmnErrorDelegate.EXCEPTION_MESSAGE_VARIABLE, "unhandledException");

    // then
    try {
      rule.getTaskService().complete(innerEventSubprocessTask.getId());
      fail("should throw a ThrowBpmnErrorDelegateException");

    } catch (ThrowBpmnErrorDelegateException e) {
      assertThat(e.getMessage()).isEqualTo("unhandledException");
    }
  }

  @Test
  @ScenarioUnderTest("initLevel1.initLevel2.1")
  void initLevel1InitLevel2CompletionCase1() {
    // given
    Task outerTask = rule.taskQuery().taskDefinitionKey("outerTask").singleResult();
    Task innerTask = rule.taskQuery().taskDefinitionKey("subProcessTask").singleResult();
    Task innerEventSubprocessTask = rule.taskQuery().taskDefinitionKey("innerEventSubProcessTask").singleResult();

    // when
    rule.getTaskService().complete(outerTask.getId());
    rule.getTaskService().complete(innerTask.getId());
    rule.getTaskService().complete(innerEventSubprocessTask.getId());

    // then
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("initLevel1.initLevel2.2")
  void initLevel1InitLevel2CompletionCase2() {
    // given
    Task outerTask = rule.taskQuery().taskDefinitionKey("outerTask").singleResult();
    Task innerTask = rule.taskQuery().taskDefinitionKey("subProcessTask").singleResult();
    Task innerEventSubprocessTask = rule.taskQuery().taskDefinitionKey("innerEventSubProcessTask").singleResult();

    // when
    rule.getTaskService().complete(innerEventSubprocessTask.getId());
    rule.getTaskService().complete(innerTask.getId());
    rule.getTaskService().complete(outerTask.getId());

    // then
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("initLevel1.initLevel2.3")
  void initLevel1InitLevel2CompletionCase3() {
    // given
    Task outerTask = rule.taskQuery().taskDefinitionKey("outerTask").singleResult();
    Task innerTask = rule.taskQuery().taskDefinitionKey("subProcessTask").singleResult();

    // when (the inner subprocess is triggered another time)
    rule.messageCorrelation("InnerEventSubProcessMessage").correlate();

    // then
    assertThat(rule.taskQuery().count()).isEqualTo(4);

    List<Task> innerEventSubprocessTasks = rule.taskQuery().taskDefinitionKey("innerEventSubProcessTask").list();
    assertThat(innerEventSubprocessTasks).hasSize(2);

    // and
    rule.getTaskService().complete(innerTask.getId());
    rule.getTaskService().complete(innerEventSubprocessTasks.get(0).getId());
    rule.getTaskService().complete(outerTask.getId());
    rule.getTaskService().complete(innerEventSubprocessTasks.get(1).getId());

    rule.assertScenarioEnded();
  }

  // TODO: update the expected structure for CIB seven migration and enable the test 
  @Disabled("The structure is not as expected: migration from Camunda 7.2.0 and migration from CIB seven 1.1.0 engine")
  @Test
  @ScenarioUnderTest("initLevel1.initLevel2.4")
  void initLevel1InitLevel2ActivityInstanceTree() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when
    ActivityInstance activityInstance = rule.getRuntimeService().getActivityInstance(instance.getId());

    // then
    assertThat(activityInstance).isNotNull();
    assertThat(activityInstance).hasStructure(
        describeActivityInstanceTree(instance.getProcessDefinitionId())
          .activity("outerTask")
          // eventSubProcess was previously no scope so it misses here
          .beginScope("subProcess")
            .activity("subProcessTask")
            // eventSubProcess was previously no scope so it misses here
            .activity("innerEventSubProcessTask")
        .done());
  }

  @Test
  @ScenarioUnderTest("initLevel1.initLevel2.5")
  void initLevel1InitLevel2Deletion() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when
    rule.getRuntimeService().deleteProcessInstance(instance.getId(), null);

    // then
    rule.assertScenarioEnded();
  }

  @ScenarioUnderTest("initLevel1.initLevel2.6")
  public void testInitLevel1InitLevel2ThrowError() {
    // given
    ProcessInstance instance = rule.processInstance();
    Task innerEventSubprocessTask = rule.taskQuery().taskDefinitionKey("innerEventSubProcessTask").singleResult();

    // when
    rule.getRuntimeService().setVariable(instance.getId(), ThrowBpmnErrorDelegate.ERROR_INDICATOR_VARIABLE, true);
    rule.getTaskService().complete(innerEventSubprocessTask.getId());

    // then
    Task escalatedTask = rule.taskQuery().singleResult();
    assertThat(escalatedTask.getTaskDefinitionKey()).isEqualTo("escalatedTask");
    assertThat(escalatedTask).isNotNull();

    rule.getTaskService().complete(escalatedTask.getId());
    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("initLevel1.initLevel2.7")
  void initLevel1InitLevel2ThrowUnhandledException() {
    // given
    ProcessInstance instance = rule.processInstance();
    Task innerEventSubprocessTask = rule.taskQuery().taskDefinitionKey("innerEventSubProcessTask").singleResult();

    // when
    rule.getRuntimeService().setVariable(instance.getId(), ThrowBpmnErrorDelegate.EXCEPTION_INDICATOR_VARIABLE, true);
    rule.getRuntimeService().setVariable(instance.getId(), ThrowBpmnErrorDelegate.EXCEPTION_MESSAGE_VARIABLE, "unhandledException");

    // then
    try {
      rule.getTaskService().complete(innerEventSubprocessTask.getId());
      fail("should throw a ThrowBpmnErrorDelegateException");

    } catch (ThrowBpmnErrorDelegateException e) {
      assertThat(e.getMessage()).isEqualTo("unhandledException");
    }
  }
}
