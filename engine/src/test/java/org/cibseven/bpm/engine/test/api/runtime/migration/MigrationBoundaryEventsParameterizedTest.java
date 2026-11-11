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

import static org.cibseven.bpm.engine.test.api.runtime.migration.ModifiableBpmnModelInstance.modify;

import java.util.Arrays;
import java.util.Collection;

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
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * @author Christopher Zell <christopher.zell@camunda.com>
 */
public class MigrationBoundaryEventsParameterizedTest {

  public static final String AFTER_BOUNDARY_TASK = "afterBoundary";
  public static final String MESSAGE_NAME = "Message";
  public static final String SIGNAL_NAME = "Signal";
  public static final String TIMER_DATE = "2016-02-11T12:13:14Z";
  public static final String NEW_TIMER_DATE = "2018-02-11T12:13:14Z";
  protected static final String BOUNDARY_ID = "boundary";
  protected static final String MIGRATE_MESSAGE_BOUNDARY_EVENT = "MigrateMessageBoundaryEvent";
  protected static final String MIGRATE_SIGNAL_BOUNDARY_EVENT = "MigrateSignalBoundaryEvent";
  protected static final String MIGRATE_TIMER_BOUNDARY_EVENT = "MigrateTimerBoundaryEvent";
  protected static final String MIGRATE_CONDITIONAL_BOUNDARY_EVENT = "MigrateConditionalBoundaryEvent";
  protected static final String USER_TASK_ID = "userTask";
  protected static final String NEW_BOUNDARY_ID = "newBoundary";
  public static final String USER_TASK_1_ID = "userTask1";
  public static final String USER_TASK_2_ID = "userTask2";
  public static final String SUB_PROCESS_ID = "subProcess";

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

  // tests ////////////////////////////////////////////////////////////////////////////////////////////////////////////

  @ParameterizedTest
  @MethodSource("data")
  public void testMigrateBoundaryEventOnUserTask(BpmnEventFactory eventFactory) {
    // given
    BpmnModelInstance sourceProcess = ProcessModels.ONE_TASK_PROCESS.clone();
    MigratingBpmnEventTrigger eventTrigger = eventFactory.addBoundaryEvent(
        rule.getProcessEngine(),
        sourceProcess,
        USER_TASK_ID,
        BOUNDARY_ID);
    ModifiableBpmnModelInstance.wrap(sourceProcess).flowNodeBuilder(BOUNDARY_ID)
      .userTask(AFTER_BOUNDARY_TASK)
      .endEvent()
      .done();

    BpmnModelInstance targetProcess = modify(sourceProcess).changeElementId(BOUNDARY_ID, NEW_BOUNDARY_ID);

    ProcessDefinition sourceProcessDefinition = testHelper.deployAndGetDefinition(sourceProcess);
    ProcessDefinition targetProcessDefinition = testHelper.deployAndGetDefinition(targetProcess);

    MigrationPlan migrationPlan = rule.getRuntimeService().createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
        .mapActivities(USER_TASK_ID, USER_TASK_ID)
        .mapActivities(BOUNDARY_ID, NEW_BOUNDARY_ID).updateEventTrigger()
        .build();

    // when
    testHelper.createProcessInstanceAndMigrate(migrationPlan);

    // then
    eventTrigger.assertEventTriggerMigrated(testHelper, NEW_BOUNDARY_ID);

    // and it is possible to successfully complete the migrated instance
    testHelper.completeTask(USER_TASK_ID);
    testHelper.assertProcessEnded(testHelper.snapshotBeforeMigration.getProcessInstanceId());
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testMigrateBoundaryEventOnUserTaskAndTriggerEvent(BpmnEventFactory eventFactory) {
    // given
    BpmnModelInstance sourceProcess = ProcessModels.ONE_TASK_PROCESS.clone();
    MigratingBpmnEventTrigger eventTrigger = eventFactory.addBoundaryEvent(
        rule.getProcessEngine(),
        sourceProcess,
        USER_TASK_ID,
        BOUNDARY_ID);
    ModifiableBpmnModelInstance.wrap(sourceProcess).flowNodeBuilder(BOUNDARY_ID)
      .userTask(AFTER_BOUNDARY_TASK)
      .endEvent()
      .done();

    BpmnModelInstance targetProcess = modify(sourceProcess).changeElementId(BOUNDARY_ID, NEW_BOUNDARY_ID);

    ProcessDefinition sourceProcessDefinition = testHelper.deployAndGetDefinition(sourceProcess);
    ProcessDefinition targetProcessDefinition = testHelper.deployAndGetDefinition(targetProcess);

    MigrationPlan migrationPlan = rule.getRuntimeService().createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
        .mapActivities(USER_TASK_ID, USER_TASK_ID)
        .mapActivities(BOUNDARY_ID, NEW_BOUNDARY_ID).updateEventTrigger()
        .build();

    // when
    ProcessInstance processInstance = testHelper.createProcessInstanceAndMigrate(migrationPlan);

    // then it is possible to trigger boundary event and successfully complete the migrated instance
    eventTrigger.inContextOf(NEW_BOUNDARY_ID).trigger(processInstance.getId());
    testHelper.completeTask(AFTER_BOUNDARY_TASK);
    testHelper.assertProcessEnded(testHelper.snapshotBeforeMigration.getProcessInstanceId());
  }


  @ParameterizedTest
  @MethodSource("data")
  public void testMigrateBoundaryEventOnConcurrentUserTask(BpmnEventFactory eventFactory) {
    // given
    BpmnModelInstance sourceProcess = ProcessModels.PARALLEL_GATEWAY_PROCESS.clone();
    MigratingBpmnEventTrigger eventTrigger = eventFactory.addBoundaryEvent(
        rule.getProcessEngine(),
        sourceProcess,
        USER_TASK_1_ID,
        BOUNDARY_ID);
    ModifiableBpmnModelInstance.wrap(sourceProcess).flowNodeBuilder(BOUNDARY_ID)
      .userTask(AFTER_BOUNDARY_TASK)
      .endEvent()
      .done();

    BpmnModelInstance targetProcess = modify(sourceProcess)
        .changeElementId("boundary", "newBoundary");

    ProcessDefinition sourceProcessDefinition = testHelper.deployAndGetDefinition(sourceProcess);
    ProcessDefinition targetProcessDefinition = testHelper.deployAndGetDefinition(targetProcess);


    MigrationPlan migrationPlan = rule.getRuntimeService().createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
        .mapActivities(USER_TASK_1_ID, USER_TASK_1_ID)
        .mapActivities(USER_TASK_2_ID, USER_TASK_2_ID)
        .mapActivities(BOUNDARY_ID, NEW_BOUNDARY_ID).updateEventTrigger()
        .build();

    // when
    testHelper.createProcessInstanceAndMigrate(migrationPlan);

    // then
    eventTrigger.assertEventTriggerMigrated(testHelper, NEW_BOUNDARY_ID);

    // and it is possible to successfully complete the migrated instance
    testHelper.completeTask(USER_TASK_1_ID);
    testHelper.completeTask(USER_TASK_2_ID);
    testHelper.assertProcessEnded(testHelper.snapshotBeforeMigration.getProcessInstanceId());
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testMigrateBoundaryEventOnConcurrentUserTaskAndTriggerEvent(BpmnEventFactory eventFactory) {
    // given
    BpmnModelInstance sourceProcess = ProcessModels.PARALLEL_GATEWAY_PROCESS.clone();
    MigratingBpmnEventTrigger eventTrigger = eventFactory.addBoundaryEvent(
        rule.getProcessEngine(),
        sourceProcess,
        USER_TASK_1_ID,
        BOUNDARY_ID);
    ModifiableBpmnModelInstance.wrap(sourceProcess).flowNodeBuilder(BOUNDARY_ID)
      .userTask(AFTER_BOUNDARY_TASK)
      .endEvent()
      .done();

    BpmnModelInstance targetProcess = modify(sourceProcess)
        .changeElementId("boundary", "newBoundary");

    ProcessDefinition sourceProcessDefinition = testHelper.deployAndGetDefinition(sourceProcess);
    ProcessDefinition targetProcessDefinition = testHelper.deployAndGetDefinition(targetProcess);


    MigrationPlan migrationPlan = rule.getRuntimeService().createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
        .mapActivities(USER_TASK_1_ID, USER_TASK_1_ID)
        .mapActivities(USER_TASK_2_ID, USER_TASK_2_ID)
        .mapActivities(BOUNDARY_ID, NEW_BOUNDARY_ID).updateEventTrigger()
        .build();

    // when
    ProcessInstance processInstance = testHelper.createProcessInstanceAndMigrate(migrationPlan);

    // then it is possible to trigger the event and successfully complete the migrated instance
    eventTrigger.inContextOf(NEW_BOUNDARY_ID).trigger(processInstance.getId());
    testHelper.completeTask(AFTER_BOUNDARY_TASK);
    testHelper.completeTask(USER_TASK_2_ID);
    testHelper.assertProcessEnded(testHelper.snapshotBeforeMigration.getProcessInstanceId());
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testMigrateBoundaryEventOnConcurrentScopeUserTask(BpmnEventFactory eventFactory) {
    // given
    BpmnModelInstance sourceProcess = ProcessModels.PARALLEL_SCOPE_TASKS.clone();
    MigratingBpmnEventTrigger eventTrigger = eventFactory.addBoundaryEvent(
        rule.getProcessEngine(),
        sourceProcess,
        USER_TASK_1_ID,
        BOUNDARY_ID);
    ModifiableBpmnModelInstance.wrap(sourceProcess).flowNodeBuilder(BOUNDARY_ID)
      .userTask(AFTER_BOUNDARY_TASK)
      .endEvent()
      .done();

    BpmnModelInstance targetProcess = modify(sourceProcess)
        .changeElementId("boundary", "newBoundary");

    ProcessDefinition sourceProcessDefinition = testHelper.deployAndGetDefinition(sourceProcess);
    ProcessDefinition targetProcessDefinition = testHelper.deployAndGetDefinition(targetProcess);


    MigrationPlan migrationPlan = rule.getRuntimeService().createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
        .mapActivities(USER_TASK_1_ID, USER_TASK_1_ID)
        .mapActivities(USER_TASK_2_ID, USER_TASK_2_ID)
        .mapActivities(BOUNDARY_ID, NEW_BOUNDARY_ID).updateEventTrigger()
        .build();

    // when
    testHelper.createProcessInstanceAndMigrate(migrationPlan);

    // then
    eventTrigger.assertEventTriggerMigrated(testHelper, NEW_BOUNDARY_ID);

    // and it is possible to successfully complete the migrated instance
    testHelper.completeTask(USER_TASK_1_ID);
    testHelper.completeTask(USER_TASK_2_ID);
    testHelper.assertProcessEnded(testHelper.snapshotBeforeMigration.getProcessInstanceId());
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testMigrateBoundaryEventOnConcurrentScopeUserTaskAndTriggerEvent(BpmnEventFactory eventFactory) {
    // given
    BpmnModelInstance sourceProcess = ProcessModels.PARALLEL_SCOPE_TASKS.clone();
    MigratingBpmnEventTrigger eventTrigger = eventFactory.addBoundaryEvent(
        rule.getProcessEngine(),
        sourceProcess,
        USER_TASK_1_ID,
        BOUNDARY_ID);
    ModifiableBpmnModelInstance.wrap(sourceProcess).flowNodeBuilder(BOUNDARY_ID)
      .userTask(AFTER_BOUNDARY_TASK)
      .endEvent()
      .done();

    BpmnModelInstance targetProcess = modify(sourceProcess)
        .changeElementId("boundary", "newBoundary");

    ProcessDefinition sourceProcessDefinition = testHelper.deployAndGetDefinition(sourceProcess);
    ProcessDefinition targetProcessDefinition = testHelper.deployAndGetDefinition(targetProcess);


    MigrationPlan migrationPlan = rule.getRuntimeService().createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
        .mapActivities(USER_TASK_1_ID, USER_TASK_1_ID)
        .mapActivities(USER_TASK_2_ID, USER_TASK_2_ID)
        .mapActivities(BOUNDARY_ID, NEW_BOUNDARY_ID).updateEventTrigger()
        .build();

    // when
    ProcessInstance processInstance = testHelper.createProcessInstanceAndMigrate(migrationPlan);

    // then it is possible to trigger the event and successfully complete the migrated instance
    eventTrigger.inContextOf(NEW_BOUNDARY_ID).trigger(processInstance.getId());
    testHelper.completeTask(AFTER_BOUNDARY_TASK);
    testHelper.completeTask(USER_TASK_2_ID);
    testHelper.assertProcessEnded(testHelper.snapshotBeforeMigration.getProcessInstanceId());
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testMigrateBoundaryEventToSubProcess(BpmnEventFactory eventFactory) {
    // given
    BpmnModelInstance sourceProcess = ProcessModels.SUBPROCESS_PROCESS.clone();
    MigratingBpmnEventTrigger eventTrigger = eventFactory.addBoundaryEvent(
        rule.getProcessEngine(),
        sourceProcess,
        SUB_PROCESS_ID,
        BOUNDARY_ID);
    ModifiableBpmnModelInstance.wrap(sourceProcess).flowNodeBuilder(BOUNDARY_ID)
      .userTask(AFTER_BOUNDARY_TASK)
      .endEvent()
      .done();

    BpmnModelInstance targetProcess = modify(sourceProcess).changeElementId(BOUNDARY_ID, NEW_BOUNDARY_ID);

    ProcessDefinition sourceProcessDefinition = testHelper.deployAndGetDefinition(sourceProcess);
    ProcessDefinition targetProcessDefinition = testHelper.deployAndGetDefinition(targetProcess);

    MigrationPlan migrationPlan = rule.getRuntimeService().createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
        .mapActivities(SUB_PROCESS_ID, SUB_PROCESS_ID)
        .mapActivities(USER_TASK_ID, USER_TASK_ID)
        .mapActivities(BOUNDARY_ID, NEW_BOUNDARY_ID).updateEventTrigger()
        .build();

    // when
    testHelper.createProcessInstanceAndMigrate(migrationPlan);

    // then
    eventTrigger.assertEventTriggerMigrated(testHelper, NEW_BOUNDARY_ID);

    // and it is possible to successfully complete the migrated instance
    testHelper.completeTask(USER_TASK_ID);
    testHelper.assertProcessEnded(testHelper.snapshotBeforeMigration.getProcessInstanceId());
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testMigrateBoundaryEventToSubProcessAndTriggerEvent(BpmnEventFactory eventFactory) {
    // given
    BpmnModelInstance sourceProcess = ProcessModels.SUBPROCESS_PROCESS.clone();
    MigratingBpmnEventTrigger eventTrigger = eventFactory.addBoundaryEvent(
        rule.getProcessEngine(),
        sourceProcess,
        SUB_PROCESS_ID,
        BOUNDARY_ID);
    ModifiableBpmnModelInstance.wrap(sourceProcess).flowNodeBuilder(BOUNDARY_ID)
      .userTask(AFTER_BOUNDARY_TASK)
      .endEvent()
      .done();

    BpmnModelInstance targetProcess = modify(sourceProcess).changeElementId(BOUNDARY_ID, NEW_BOUNDARY_ID);

    ProcessDefinition sourceProcessDefinition = testHelper.deployAndGetDefinition(sourceProcess);
    ProcessDefinition targetProcessDefinition = testHelper.deployAndGetDefinition(targetProcess);

    MigrationPlan migrationPlan = rule.getRuntimeService().createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
        .mapActivities(SUB_PROCESS_ID, SUB_PROCESS_ID)
        .mapActivities(USER_TASK_ID, USER_TASK_ID)
        .mapActivities(BOUNDARY_ID, NEW_BOUNDARY_ID).updateEventTrigger()
        .build();

    // when
    ProcessInstance processInstance = testHelper.createProcessInstanceAndMigrate(migrationPlan);

    // then it is possible to trigger the event and successfully complete the migrated instance
    eventTrigger.inContextOf(NEW_BOUNDARY_ID).trigger(processInstance.getId());
    testHelper.completeTask(AFTER_BOUNDARY_TASK);
    testHelper.assertProcessEnded(testHelper.snapshotBeforeMigration.getProcessInstanceId());
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testMigrateBoundaryEventToSubProcessWithScopeUserTask(BpmnEventFactory eventFactory) {
    // given
    BpmnModelInstance sourceProcess = ProcessModels.SCOPE_TASK_SUBPROCESS_PROCESS.clone();
    MigratingBpmnEventTrigger eventTrigger = eventFactory.addBoundaryEvent(
        rule.getProcessEngine(),
        sourceProcess,
        SUB_PROCESS_ID,
        BOUNDARY_ID);
    ModifiableBpmnModelInstance.wrap(sourceProcess).flowNodeBuilder(BOUNDARY_ID)
      .userTask(AFTER_BOUNDARY_TASK)
      .endEvent()
      .done();

    BpmnModelInstance targetProcess = modify(sourceProcess).changeElementId(BOUNDARY_ID, NEW_BOUNDARY_ID);

    ProcessDefinition sourceProcessDefinition = testHelper.deployAndGetDefinition(sourceProcess);
    ProcessDefinition targetProcessDefinition = testHelper.deployAndGetDefinition(targetProcess);

    MigrationPlan migrationPlan = rule.getRuntimeService().createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
        .mapActivities(SUB_PROCESS_ID, SUB_PROCESS_ID)
        .mapActivities(USER_TASK_ID, USER_TASK_ID)
        .mapActivities(BOUNDARY_ID, NEW_BOUNDARY_ID).updateEventTrigger()
        .build();

    // when
    testHelper.createProcessInstanceAndMigrate(migrationPlan);

    // then
    eventTrigger.assertEventTriggerMigrated(testHelper, NEW_BOUNDARY_ID);

    // and it is possible to successfully complete the migrated instance
    testHelper.completeTask(USER_TASK_ID);
    testHelper.assertProcessEnded(testHelper.snapshotBeforeMigration.getProcessInstanceId());
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testMigrateBoundaryEventToSubProcessWithScopeUserTaskAndTriggerEvent(BpmnEventFactory eventFactory) {
    // given
    BpmnModelInstance sourceProcess = ProcessModels.SCOPE_TASK_SUBPROCESS_PROCESS.clone();
    MigratingBpmnEventTrigger eventTrigger = eventFactory.addBoundaryEvent(
        rule.getProcessEngine(),
        sourceProcess,
        SUB_PROCESS_ID,
        BOUNDARY_ID);
    ModifiableBpmnModelInstance.wrap(sourceProcess).flowNodeBuilder(BOUNDARY_ID)
      .userTask(AFTER_BOUNDARY_TASK)
      .endEvent()
      .done();

    BpmnModelInstance targetProcess = modify(sourceProcess).changeElementId(BOUNDARY_ID, NEW_BOUNDARY_ID);

    ProcessDefinition sourceProcessDefinition = testHelper.deployAndGetDefinition(sourceProcess);
    ProcessDefinition targetProcessDefinition = testHelper.deployAndGetDefinition(targetProcess);

    MigrationPlan migrationPlan = rule.getRuntimeService().createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
        .mapActivities(SUB_PROCESS_ID, SUB_PROCESS_ID)
        .mapActivities(USER_TASK_ID, USER_TASK_ID)
        .mapActivities(BOUNDARY_ID, NEW_BOUNDARY_ID).updateEventTrigger()
        .build();

    // when
    ProcessInstance processInstance = testHelper.createProcessInstanceAndMigrate(migrationPlan);

    // then it is possible to trigger the event and successfully complete the migrated instance
    eventTrigger.inContextOf(NEW_BOUNDARY_ID).trigger(processInstance.getId());
    testHelper.completeTask(AFTER_BOUNDARY_TASK);
    testHelper.assertProcessEnded(testHelper.snapshotBeforeMigration.getProcessInstanceId());
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testMigrateBoundaryEventToParallelSubProcess(BpmnEventFactory eventFactory) {
    // given
    BpmnModelInstance sourceProcess = ProcessModels.PARALLEL_SUBPROCESS_PROCESS.clone();
    MigratingBpmnEventTrigger eventTrigger = eventFactory.addBoundaryEvent(
        rule.getProcessEngine(),
        sourceProcess,
        "subProcess1",
        BOUNDARY_ID);
    ModifiableBpmnModelInstance.wrap(sourceProcess).flowNodeBuilder(BOUNDARY_ID)
      .userTask(AFTER_BOUNDARY_TASK)
      .endEvent()
      .done();

    BpmnModelInstance targetProcess = modify(sourceProcess).changeElementId(BOUNDARY_ID, NEW_BOUNDARY_ID);

    ProcessDefinition sourceProcessDefinition = testHelper.deployAndGetDefinition(sourceProcess);
    ProcessDefinition targetProcessDefinition = testHelper.deployAndGetDefinition(targetProcess);

    MigrationPlan migrationPlan = rule.getRuntimeService().createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
        .mapActivities("subProcess1", "subProcess1")
        .mapActivities(USER_TASK_1_ID, USER_TASK_1_ID)
        .mapActivities("subProcess2", "subProcess2")
        .mapActivities(USER_TASK_2_ID, USER_TASK_2_ID)
        .mapActivities(BOUNDARY_ID, NEW_BOUNDARY_ID).updateEventTrigger()
        .build();

    // when
    testHelper.createProcessInstanceAndMigrate(migrationPlan);

    // then
    eventTrigger.assertEventTriggerMigrated(testHelper, NEW_BOUNDARY_ID);

    // and it is possible to successfully complete the migrated instance
    testHelper.completeTask(USER_TASK_1_ID);
    testHelper.completeTask(USER_TASK_2_ID);
    testHelper.assertProcessEnded(testHelper.snapshotBeforeMigration.getProcessInstanceId());
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testMigrateBoundaryEventToParallelSubProcessAndTriggerEvent(BpmnEventFactory eventFactory) {
    // given
    BpmnModelInstance sourceProcess = ProcessModels.PARALLEL_SUBPROCESS_PROCESS.clone();
    MigratingBpmnEventTrigger eventTrigger = eventFactory.addBoundaryEvent(
        rule.getProcessEngine(),
        sourceProcess,
        "subProcess1",
        BOUNDARY_ID);
    ModifiableBpmnModelInstance.wrap(sourceProcess).flowNodeBuilder(BOUNDARY_ID)
      .userTask(AFTER_BOUNDARY_TASK)
      .endEvent()
      .done();

    BpmnModelInstance targetProcess = modify(sourceProcess).changeElementId(BOUNDARY_ID, NEW_BOUNDARY_ID);

    ProcessDefinition sourceProcessDefinition = testHelper.deployAndGetDefinition(sourceProcess);
    ProcessDefinition targetProcessDefinition = testHelper.deployAndGetDefinition(targetProcess);

    MigrationPlan migrationPlan = rule.getRuntimeService().createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
        .mapActivities("subProcess1", "subProcess1")
        .mapActivities(USER_TASK_1_ID, USER_TASK_1_ID)
        .mapActivities("subProcess2", "subProcess2")
        .mapActivities(USER_TASK_2_ID, USER_TASK_2_ID)
        .mapActivities(BOUNDARY_ID, NEW_BOUNDARY_ID).updateEventTrigger()
        .build();

    // when
    ProcessInstance processInstance = testHelper.createProcessInstanceAndMigrate(migrationPlan);

    // then it is possible to trigger the event and successfully complete the migrated instance
    eventTrigger.inContextOf(NEW_BOUNDARY_ID).trigger(processInstance.getId());
    testHelper.completeTask(AFTER_BOUNDARY_TASK);
    testHelper.completeTask(USER_TASK_2_ID);
    testHelper.assertProcessEnded(testHelper.snapshotBeforeMigration.getProcessInstanceId());
  }

}
