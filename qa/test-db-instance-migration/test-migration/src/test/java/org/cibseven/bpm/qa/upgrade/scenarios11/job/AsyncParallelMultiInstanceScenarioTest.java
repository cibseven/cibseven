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
package org.cibseven.bpm.qa.upgrade.scenarios11.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cibseven.bpm.qa.upgrade.util.ActivityInstanceAssert.assertThat;
import static org.cibseven.bpm.qa.upgrade.util.ActivityInstanceAssert.describeActivityInstanceTree;

import java.util.List;

import org.cibseven.bpm.engine.management.JobDefinition;
import org.cibseven.bpm.engine.runtime.ActivityInstance;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.qa.upgrade.Origin;
import org.cibseven.bpm.qa.upgrade.ScenarioUnderTest;
import org.cibseven.bpm.qa.upgrade.UpgradeTestRule;
import org.junit.Rule;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@ScenarioUnderTest("AsyncParallelMultiInstanceScenario")
@Origin("1.1.0")
public class AsyncParallelMultiInstanceScenarioTest {

  @Rule
  public UpgradeTestRule rule = new UpgradeTestRule();

  @Test
  @ScenarioUnderTest("initAsyncBeforeSubprocess.1")
  void initAsyncBeforeSubprocessCompletion() {
    // given
    Job asyncJob = rule.jobQuery().singleResult();

    // when
    rule.getManagementService().executeJob(asyncJob.getId());

    // then the process can be completed successfully
    List<Task> subProcessTasks = rule.taskQuery().list();
    assertThat(subProcessTasks).hasSize(3);

    for (Task subProcessTask : subProcessTasks) {
      rule.getTaskService().complete(subProcessTask.getId());
    }

    rule.assertScenarioEnded();
  }

  // TODO: update the expected structure for CIB seven migration and enable the test 
  @Disabled("The structure is not as expected: migration from Camunda 7.2.0 and migration from CIB seven 1.1.0 engine")
  @Test
  @ScenarioUnderTest("initAsyncBeforeSubprocess.2")
  void initAsyncBeforeSubprocessActivityInstanceTree() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when
    ActivityInstance activityInstance = rule.getRuntimeService().getActivityInstance(instance.getId());

    // then
    assertThat(activityInstance).isNotNull();
    assertThat(activityInstance).hasStructure(
        describeActivityInstanceTree(instance.getProcessDefinitionId())
          // this is not the multi-instance body because the execution
          // references the inner activity
          .transition("miSubProcess")
        .done());
  }

  @Test
  @ScenarioUnderTest("initAsyncBeforeSubprocess.3")
  void initAsyncBeforeSubprocessDeletion() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when
    rule.getRuntimeService().deleteProcessInstance(instance.getId(), null);

    // then
    rule.assertScenarioEnded();
  }

  /**
   * Note: this test is not really isolated since the job
   * definition is migrated when the process definition is accessed the first time.
   * This might happen already before this test case is executed.
   */
  @Test
  @ScenarioUnderTest("initAsyncBeforeSubprocess.4")
  void initAsyncBeforeSubprocessJobDefinition() {
    // when the process is redeployed into the cache (instantiation should trigger that)
    rule.getRuntimeService().startProcessInstanceByKey("AsyncBeforeParallelMultiInstanceSubprocess");

    // then the old job definition referencing "miSubProcess" has been migrated
    JobDefinition asyncJobDefinition = rule.jobDefinitionQuery().singleResult();
    assertThat(asyncJobDefinition.getActivityId()).isEqualTo("miSubProcess#multiInstanceBody");
  }

  @Test
  @ScenarioUnderTest("initAsyncBeforeTask.1")
  void initAsyncBeforeTaskCompletion() {
    // given
    Job asyncJob = rule.jobQuery().singleResult();

    // when
    rule.getManagementService().executeJob(asyncJob.getId());

    // then the process can be completed successfully
    List<Task> subProcessTasks = rule.taskQuery().list();
    assertThat(subProcessTasks).hasSize(3);

    for (Task subProcessTask : subProcessTasks) {
      rule.getTaskService().complete(subProcessTask.getId());
    }

    rule.assertScenarioEnded();
  }

  // TODO: update the expected structure for CIB seven migration and enable the test 
  @Disabled("The structure is not as expected: migration from Camunda 7.2.0 and migration from CIB seven 1.1.0 engine")
  @Test
  @ScenarioUnderTest("initAsyncBeforeTask.2")
  void initAsyncBeforeTaskActivityInstanceTree() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when
    ActivityInstance activityInstance = rule.getRuntimeService().getActivityInstance(instance.getId());

    // then
    assertThat(activityInstance).isNotNull();
    assertThat(activityInstance).hasStructure(
        describeActivityInstanceTree(instance.getProcessDefinitionId())
          // this is not the multi-instance body because the execution
          // references the inner activity
          .transition("miTask")
        .done());
  }

  @Test
  @ScenarioUnderTest("initAsyncBeforeTask.3")
  void initAsyncBeforeTaskDeletion() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when
    rule.getRuntimeService().deleteProcessInstance(instance.getId(), null);

    // then
    rule.assertScenarioEnded();
  }

  /**
   * Note: this test is not really isolated since the job
   * definition is migrated when the process definition is accessed the first time.
   * This might happen already before this test case is executed.
   */
  @Test
  @ScenarioUnderTest("initAsyncBeforeTask.4")
  void initAsyncBeforeTaskJobDefinition() {
    // when the process is redeployed into the cache (instantiation should trigger that)
    rule.getRuntimeService().startProcessInstanceByKey("AsyncBeforeParallelMultiInstanceTask");

    // then the old job definition referencing "miSubProcess" has been migrated
    JobDefinition asyncJobDefinition = rule.jobDefinitionQuery().singleResult();
    assertThat(asyncJobDefinition.getActivityId()).isEqualTo("miTask#multiInstanceBody");
  }

}
