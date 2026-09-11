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
package org.cibseven.bpm.engine.test.api.runtime.migration;

import java.util.Arrays;
import java.util.Collection;
import java.util.Date;

import org.cibseven.bpm.engine.impl.util.ClockUtil;
import org.cibseven.bpm.engine.migration.MigrationPlan;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.api.runtime.migration.models.ProcessModels;
import org.cibseven.bpm.engine.test.api.runtime.migration.util.BpmnEventFactory;
import org.cibseven.bpm.engine.test.api.runtime.migration.util.ConditionalEventFactory;
import org.cibseven.bpm.engine.test.api.runtime.migration.util.MessageEventFactory;
import org.cibseven.bpm.engine.test.api.runtime.migration.util.MigratingBpmnEventTrigger;
import org.cibseven.bpm.engine.test.api.runtime.migration.util.SignalEventFactory;
import org.cibseven.bpm.engine.test.api.runtime.migration.util.TimerEventFactory;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * @author Christopher Zell <christopher.zell@camunda.com>
 */
public class MigrateEventSubProcessAndTriggerTest {

  public static Collection<Object[]> data() {
      return Arrays.asList(new Object[][] {
               new Object[]{ new TimerEventFactory() },
               new Object[]{ new MessageEventFactory() },
               new Object[]{ new SignalEventFactory() },
               new Object[]{ new ConditionalEventFactory() }
         });
  }

  @RegisterExtension
  protected ProcessEngineRule rule = new ProvidedProcessEngineRule();
  @RegisterExtension
  protected MigrationTestRule testHelper = new MigrationTestRule(rule);

  @BeforeEach
  public void setUp() {
    ClockUtil.setCurrentTime(new Date()); // lock time so that timer job is effectively not updated
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testMigrateEventSubprocessSignalTrigger(BpmnEventFactory eventFactory) {
    BpmnModelInstance processModel = ProcessModels.ONE_TASK_PROCESS.clone();
    MigratingBpmnEventTrigger eventTrigger = eventFactory.addEventSubProcess(
        rule.getProcessEngine(),
        processModel,
        ProcessModels.PROCESS_KEY,
        "eventSubProcess",
        "eventSubProcessStart");
    ModifiableBpmnModelInstance.wrap(processModel).startEventBuilder("eventSubProcessStart")
        .userTask("eventSubProcessTask")
        .endEvent()
        .done();

    ProcessDefinition sourceProcessDefinition = testHelper.deployAndGetDefinition(processModel);
    ProcessDefinition targetProcessDefinition = testHelper.deployAndGetDefinition(processModel);

    ProcessInstance processInstance = rule.getRuntimeService().startProcessInstanceById(sourceProcessDefinition.getId());

    MigrationPlan migrationPlan = rule.getRuntimeService().createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
        .mapActivities("userTask", "userTask")
        .mapActivities("eventSubProcessStart", "eventSubProcessStart").updateEventTrigger()
        .build();

    // when
    testHelper.migrateProcessInstance(migrationPlan, processInstance);

    // then
    eventTrigger.assertEventTriggerMigrated(testHelper, "eventSubProcessStart");

    // and it is possible to trigger the event subprocess
    eventTrigger.trigger(processInstance.getId());
    Assertions.assertEquals(1, rule.getTaskService().createTaskQuery().count());

    // and complete the process instance
    testHelper.completeTask("eventSubProcessTask");
    testHelper.assertProcessEnded(processInstance.getId());
  }
}
