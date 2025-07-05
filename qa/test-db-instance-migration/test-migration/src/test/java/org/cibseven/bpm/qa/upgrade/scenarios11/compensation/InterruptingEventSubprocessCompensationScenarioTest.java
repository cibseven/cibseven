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

import static org.cibseven.bpm.qa.upgrade.util.ActivityInstanceAssert.assertThat;
import static org.cibseven.bpm.qa.upgrade.util.ActivityInstanceAssert.describeActivityInstanceTree;

import org.cibseven.bpm.engine.runtime.ActivityInstance;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.qa.upgrade.Origin;
import org.cibseven.bpm.qa.upgrade.ScenarioUnderTest;
import org.cibseven.bpm.qa.upgrade.UpgradeTestRule;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.Ignore;

/**
 * @author Thorben Lindhauer
 *
 */
@ScenarioUnderTest("InterruptingEventSubprocessCompensationScenario")
@Origin("1.1.0")
public class InterruptingEventSubprocessCompensationScenarioTest {

  @Rule
  public UpgradeTestRule rule = new UpgradeTestRule();

  @Test
  @ScenarioUnderTest("init.1")
  public void testInitCompletion() {
    // when compensation is thrown
    Task beforeCompensationTask = rule.taskQuery().singleResult();
    rule.getTaskService().complete(beforeCompensationTask.getId());

    // then there is an active compensation handler task
    Task compensationHandlerTask = rule.taskQuery().singleResult();
    Assert.assertNotNull(compensationHandlerTask);
    Assert.assertEquals("undoTask", compensationHandlerTask.getTaskDefinitionKey());

    // and it can be completed such that the process instance ends successfully
    rule.getTaskService().complete(compensationHandlerTask.getId());

    Task afterCompensateTask = rule.taskQuery().singleResult();
    Assert.assertNotNull(afterCompensateTask);
    Assert.assertEquals("afterCompensate", afterCompensateTask.getTaskDefinitionKey());

    rule.getTaskService().complete(afterCompensateTask.getId());

    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("init.2")
  public void testInitDeletion() {
    // when compensation is thrown
    Task beforeCompensationTask = rule.taskQuery().singleResult();
    rule.getTaskService().complete(beforeCompensationTask.getId());

    // then the process instance can be deleted
    rule.getRuntimeService().deleteProcessInstance(rule.processInstance().getId(), "");

    // and the process is ended
    rule.assertScenarioEnded();
  }

  // TODO: update the expected structure for CIB seven migration and enable the test 
  @Ignore("The structure is not as expected: migration from Camunda 7.2.0 and migration from CIB seven 1.1.0 engine")
  @Test
  @ScenarioUnderTest("init.3")
  public void testInitActivityInstanceTree() {
    // given
    ProcessInstance instance = rule.processInstance();

    // when compensation is thrown
    Task beforeCompensationTask = rule.taskQuery().singleResult();
    rule.getTaskService().complete(beforeCompensationTask.getId());

    // then the activity instance tree is meaningful
    ActivityInstance activityInstance = rule.getRuntimeService().getActivityInstance(instance.getId());
    Assert.assertNotNull(activityInstance);
    assertThat(activityInstance).hasStructure(
      describeActivityInstanceTree(instance.getProcessDefinitionId())
        .beginScope("subProcess")
          // - undoTask is a child of throwCompensate because the activity execution mapping
          //   assumes that the compensation-throwing scope execution is the scope execution for the
          //   event subprocess (cf PvmExecutionImpl#getFlowScopeExecution)
          .beginScope("throwCompensate")
            .activity("undoTask")
        .done());
  }

  @Test
  @ScenarioUnderTest("init.triggerCompensation.1")
  public void testInitTriggerCompensationCompletion() {
    // given active compensation
    Task compensationHandlerTask = rule.taskQuery().singleResult();

    // then it is possible to complete compensation and the follow-up task
    rule.getTaskService().complete(compensationHandlerTask.getId());

    Task afterCompensateTask = rule.taskQuery().singleResult();
    Assert.assertNotNull(afterCompensateTask);
    Assert.assertEquals("afterCompensate", afterCompensateTask.getTaskDefinitionKey());

    rule.getTaskService().complete(afterCompensateTask.getId());

    rule.assertScenarioEnded();
  }

  @Test
  @ScenarioUnderTest("init.triggerCompensation.2")
  public void testInitTriggerCompensationDeletion() {
    // given active compensation

    // then the process instance can be deleted
    rule.getRuntimeService().deleteProcessInstance(rule.processInstance().getId(), "");

    // and the process is ended
    rule.assertScenarioEnded();
  }

  // TODO: update the expected structure for CIB seven migration and enable the test 
  @Ignore("The structure is not as expected: migration from Camunda 7.2.0 and migration from CIB seven 1.1.0 engine")
  @Test
  @ScenarioUnderTest("init.triggerCompensation.3")
  public void testInitTriggerCompensationActivityInstanceTree() {
    // given active compensation
    ProcessInstance instance = rule.processInstance();

    // then the activity instance tree is meaningful
    ActivityInstance activityInstance = rule.getRuntimeService().getActivityInstance(instance.getId());
    Assert.assertNotNull(activityInstance);
    assertThat(activityInstance).hasStructure(
      describeActivityInstanceTree(instance.getProcessDefinitionId())
        .beginScope("subProcess")
          .activity("throwCompensate")
          .activity("undoTask")
        .done());
  }
}
