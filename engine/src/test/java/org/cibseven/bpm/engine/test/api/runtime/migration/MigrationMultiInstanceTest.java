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

import static org.cibseven.bpm.engine.test.util.ActivityInstanceAssert.describeActivityInstanceTree;
import static org.cibseven.bpm.engine.test.util.ExecutionAssert.describeExecutionTree;
import static org.cibseven.bpm.engine.test.util.MigrationPlanValidationReportAssert.assertReport;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParse;
import org.cibseven.bpm.engine.migration.MigrationPlan;
import org.cibseven.bpm.engine.migration.MigrationPlanValidationException;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.runtime.ActivityInstance;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.api.runtime.migration.models.MultiInstanceProcessModels;
import org.cibseven.bpm.engine.test.api.runtime.migration.MigrationTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;


/**
 * @author Thorben Lindhauer
 *
 */
public class MigrationMultiInstanceTest {

  public static final String NUMBER_OF_INSTANCES = "nrOfInstances";
  public static final String NUMBER_OF_ACTIVE_INSTANCES = "nrOfActiveInstances";
  public static final String NUMBER_OF_COMPLETED_INSTANCES = "nrOfCompletedInstances";
  public static final String LOOP_COUNTER = "loopCounter";

  @RegisterExtension
  protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  protected MigrationTestRule testRule = new MigrationTestRule(engineRule);


  @Test
  public void testMigrateParallelMultiInstanceTask() {
    // given
    ProcessDefinition sourceProcessDefinition = testRule.deployAndGetDefinition(MultiInstanceProcessModels.PAR_MI_ONE_TASK_PROCESS);
    ProcessDefinition targetProcessDefinition = testRule.deployAndGetDefinition(MultiInstanceProcessModels.PAR_MI_ONE_TASK_PROCESS);

    MigrationPlan migrationPlan = engineRule.getRuntimeService()
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities(miBodyOf("userTask"), miBodyOf("userTask"))
      .mapActivities("userTask", "userTask")
      .build();

    // when
    testRule.createProcessInstanceAndMigrate(migrationPlan);

    // then
    testRule.assertExecutionTreeAfterMigration()
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(testRule.snapshotBeforeMigration.getProcessInstanceId())
          .child(null).scope().id(testRule.getSingleExecutionIdForActivityBeforeMigration(miBodyOf("userTask")))
            .child("userTask").concurrent().noScope().up()
            .child("userTask").concurrent().noScope().up()
            .child("userTask").concurrent().noScope().up()
          .done());

    ActivityInstance[] userTaskInstances = testRule.snapshotBeforeMigration.getActivityTree().getActivityInstances("userTask");

    testRule.assertActivityTreeAfterMigration().hasStructure(
        describeActivityInstanceTree(targetProcessDefinition.getId())
          .beginMiBody("userTask", testRule.getSingleActivityInstanceBeforeMigration(miBodyOf("userTask")).getId())
            .activity("userTask", userTaskInstances[0].getId())
            .activity("userTask", userTaskInstances[1].getId())
            .activity("userTask", userTaskInstances[2].getId())
        .done());

    List<Task> migratedTasks = testRule.snapshotAfterMigration.getTasks();
    Assertions.assertEquals(3, migratedTasks.size());
    for (Task migratedTask : migratedTasks) {
      assertEquals(targetProcessDefinition.getId(), migratedTask.getProcessDefinitionId());
    }

    // and it is possible to successfully complete the migrated instance
    for (Task migratedTask : migratedTasks) {
      engineRule.getTaskService().complete(migratedTask.getId());
    }
    testRule.assertProcessEnded(testRule.snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testMigrateParallelMultiInstanceTasksVariables() {
    // given
    ProcessDefinition sourceProcessDefinition = testRule.deployAndGetDefinition(MultiInstanceProcessModels.PAR_MI_ONE_TASK_PROCESS);
    ProcessDefinition targetProcessDefinition = testRule.deployAndGetDefinition(MultiInstanceProcessModels.PAR_MI_ONE_TASK_PROCESS);

    MigrationPlan migrationPlan = engineRule.getRuntimeService()
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities(miBodyOf("userTask"), miBodyOf("userTask"))
      .mapActivities("userTask", "userTask")
      .build();

    ProcessInstance processInstance = engineRule.getRuntimeService()
      .startProcessInstanceById(migrationPlan.getSourceProcessDefinitionId());

    List<Task> tasksBeforeMigration = engineRule.getTaskService().createTaskQuery().list();
    Map<String, Integer> loopCounterDistribution = new HashMap<String, Integer>();
    for (Task task : tasksBeforeMigration) {
      Integer loopCounter = (Integer) engineRule.getTaskService().getVariable(task.getId(), LOOP_COUNTER);
      loopCounterDistribution.put(task.getId(), loopCounter);
    }

    // when
    testRule.migrateProcessInstance(migrationPlan, processInstance);

    // then
    List<Task> tasks = testRule.snapshotAfterMigration.getTasks();
    Task firstTask = tasks.get(0);
    Assertions.assertEquals(3, engineRule.getTaskService().getVariable(firstTask.getId(), NUMBER_OF_INSTANCES));
    Assertions.assertEquals(3, engineRule.getTaskService().getVariable(firstTask.getId(), NUMBER_OF_ACTIVE_INSTANCES));
    Assertions.assertEquals(0, engineRule.getTaskService().getVariable(firstTask.getId(), NUMBER_OF_COMPLETED_INSTANCES));

    for (Task task : tasks) {
      Integer loopCounter = (Integer) engineRule.getTaskService().getVariable(task.getId(), LOOP_COUNTER);
      Assertions.assertNotNull(loopCounter);
      Assertions.assertEquals(loopCounterDistribution.get(task.getId()), loopCounter);
    }
  }

  @Test
  public void testMigrateParallelMultiInstancePartiallyComplete() {
    // given
    ProcessDefinition sourceProcessDefinition = testRule.deployAndGetDefinition(MultiInstanceProcessModels.PAR_MI_ONE_TASK_PROCESS);
    ProcessDefinition targetProcessDefinition = testRule.deployAndGetDefinition(MultiInstanceProcessModels.PAR_MI_ONE_TASK_PROCESS);

    MigrationPlan migrationPlan = engineRule.getRuntimeService()
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities(miBodyOf("userTask"), miBodyOf("userTask"))
      .mapActivities("userTask", "userTask")
      .build();

    // when
    ProcessInstance processInstance =
        engineRule.getRuntimeService().startProcessInstanceById(sourceProcessDefinition.getId());

    testRule.completeAnyTask("userTask");
    testRule.migrateProcessInstance(migrationPlan, processInstance);

    // then
    testRule.assertExecutionTreeAfterMigration()
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(testRule.snapshotBeforeMigration.getProcessInstanceId())
          .child(null).scope().id(testRule.getSingleExecutionIdForActivityBeforeMigration(miBodyOf("userTask")))
            .child("userTask").concurrent().noScope().up()
            .child("userTask").concurrent().noScope().up()
            .child("userTask").concurrent().noScope().up()
          .done());

    ActivityInstance[] userTaskInstances = testRule.snapshotBeforeMigration.getActivityTree().getActivityInstances("userTask");

    testRule.assertActivityTreeAfterMigration().hasStructure(
        describeActivityInstanceTree(targetProcessDefinition.getId())
          .beginMiBody("userTask", testRule.getSingleActivityInstanceBeforeMigration(miBodyOf("userTask")).getId())
            .activity("userTask", userTaskInstances[0].getId())
            .activity("userTask", userTaskInstances[1].getId())
            .transition("userTask") // bug CAM-5609
        .done());

    List<Task> migratedTasks = testRule.snapshotAfterMigration.getTasks();
    Assertions.assertEquals(2, migratedTasks.size());
    for (Task migratedTask : migratedTasks) {
      assertEquals(targetProcessDefinition.getId(), migratedTask.getProcessDefinitionId());
    }

    // and it is possible to successfully complete the migrated instance
    for (Task migratedTask : migratedTasks) {
      engineRule.getTaskService().complete(migratedTask.getId());
    }
    testRule.assertProcessEnded(testRule.snapshotBeforeMigration.getProcessInstanceId());
  }


  @Test
  public void testMigrateParallelMiBodyRemoveSubprocess() {
    // given
    ProcessDefinition sourceProcessDefinition = testRule.deployAndGetDefinition(MultiInstanceProcessModels.PAR_MI_SUBPROCESS_PROCESS);
    ProcessDefinition targetProcessDefinition = testRule.deployAndGetDefinition(MultiInstanceProcessModels.PAR_MI_ONE_TASK_PROCESS);

    try {
      engineRule.getRuntimeService()
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities(miBodyOf("subProcess"), miBodyOf("userTask"))
      .mapActivities("userTask", "userTask")
      .build();
      fail("Should not succeed");
    }
    catch (MigrationPlanValidationException e) {
      assertReport(e.getValidationReport())
        .hasInstructionFailures(miBodyOf("subProcess"),
          "Cannot remove the inner activity of a multi-instance body when the body is mapped"
        );
    }
  }


  @Test
  public void testMigrateParallelMiBodyAddSubprocess() {
    // given
    ProcessDefinition sourceProcessDefinition = testRule.deployAndGetDefinition(MultiInstanceProcessModels.PAR_MI_ONE_TASK_PROCESS);
    ProcessDefinition targetProcessDefinition = testRule.deployAndGetDefinition(MultiInstanceProcessModels.PAR_MI_SUBPROCESS_PROCESS);

    try {
      engineRule.getRuntimeService()
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities(miBodyOf("userTask"), miBodyOf("subProcess"))
      .mapActivities("userTask", "userTask")
      .build();
      fail("Should not succeed");
    }
    catch (MigrationPlanValidationException e) {
      assertReport(e.getValidationReport())
        .hasInstructionFailures(miBodyOf("userTask"),
          "Must map the inner activity of a multi-instance body when the body is mapped"
        );
    }
  }

  @Test
  public void testMigrateSequentialMultiInstanceTask() {
    // given
    ProcessDefinition sourceProcessDefinition = testRule.deployAndGetDefinition(MultiInstanceProcessModels.SEQ_MI_ONE_TASK_PROCESS);
    ProcessDefinition targetProcessDefinition = testRule.deployAndGetDefinition(MultiInstanceProcessModels.SEQ_MI_ONE_TASK_PROCESS);

    MigrationPlan migrationPlan = engineRule.getRuntimeService()
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities(miBodyOf("userTask"), miBodyOf("userTask"))
      .mapActivities("userTask", "userTask")
      .build();

    // when
    testRule.createProcessInstanceAndMigrate(migrationPlan);

    // then
    testRule.assertExecutionTreeAfterMigration()
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(testRule.snapshotBeforeMigration.getProcessInstanceId())
          .child("userTask").scope().id(testRule.getSingleExecutionIdForActivityBeforeMigration(miBodyOf("userTask")))
          .done());

    testRule.assertActivityTreeAfterMigration().hasStructure(
        describeActivityInstanceTree(targetProcessDefinition.getId())
          .beginMiBody("userTask", testRule.getSingleActivityInstanceBeforeMigration(miBodyOf("userTask")).getId())
            .activity("userTask", testRule.getSingleActivityInstanceBeforeMigration("userTask").getId())
        .done());

    Task migratedTask = testRule.snapshotAfterMigration.getTaskForKey("userTask");
    Assertions.assertNotNull(migratedTask);
    assertEquals(targetProcessDefinition.getId(), migratedTask.getProcessDefinitionId());

    // and it is possible to successfully complete the migrated instance
    testRule.completeTask("userTask");
    testRule.completeTask("userTask");
    testRule.completeTask("userTask");
    testRule.assertProcessEnded(testRule.snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testMigrateSequentialMultiInstanceTasksVariables() {
    // given
    ProcessDefinition sourceProcessDefinition = testRule.deployAndGetDefinition(MultiInstanceProcessModels.SEQ_MI_ONE_TASK_PROCESS);
    ProcessDefinition targetProcessDefinition = testRule.deployAndGetDefinition(MultiInstanceProcessModels.SEQ_MI_ONE_TASK_PROCESS);

    MigrationPlan migrationPlan = engineRule.getRuntimeService()
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities(miBodyOf("userTask"), miBodyOf("userTask"))
      .mapActivities("userTask", "userTask")
      .build();

    // when
    testRule.createProcessInstanceAndMigrate(migrationPlan);

    // then
    Task task = testRule.snapshotAfterMigration.getTaskForKey("userTask");
    Assertions.assertEquals(3, engineRule.getTaskService().getVariable(task.getId(), NUMBER_OF_INSTANCES));
    Assertions.assertEquals(1, engineRule.getTaskService().getVariable(task.getId(), NUMBER_OF_ACTIVE_INSTANCES));
    Assertions.assertEquals(0, engineRule.getTaskService().getVariable(task.getId(), NUMBER_OF_COMPLETED_INSTANCES));
    Assertions.assertEquals(0, engineRule.getTaskService().getVariable(task.getId(), NUMBER_OF_COMPLETED_INSTANCES));
  }

  @Test
  public void testMigrateSequentialMultiInstancePartiallyComplete() {
    // given
    ProcessDefinition sourceProcessDefinition = testRule.deployAndGetDefinition(MultiInstanceProcessModels.SEQ_MI_ONE_TASK_PROCESS);
    ProcessDefinition targetProcessDefinition = testRule.deployAndGetDefinition(MultiInstanceProcessModels.SEQ_MI_ONE_TASK_PROCESS);

    MigrationPlan migrationPlan = engineRule.getRuntimeService()
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities(miBodyOf("userTask"), miBodyOf("userTask"))
      .mapActivities("userTask", "userTask")
      .build();

    // when
    ProcessInstance processInstance =
        engineRule.getRuntimeService().startProcessInstanceById(sourceProcessDefinition.getId());

    testRule.completeAnyTask("userTask");
    testRule.migrateProcessInstance(migrationPlan, processInstance);

    // then
    testRule.assertExecutionTreeAfterMigration()
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(testRule.snapshotBeforeMigration.getProcessInstanceId())
          .child("userTask").scope().id(testRule.getSingleExecutionIdForActivityBeforeMigration(miBodyOf("userTask")))
          .done());

    testRule.assertActivityTreeAfterMigration().hasStructure(
        describeActivityInstanceTree(targetProcessDefinition.getId())
          .beginMiBody("userTask", testRule.getSingleActivityInstanceBeforeMigration(miBodyOf("userTask")).getId())
            .activity("userTask", testRule.getSingleActivityInstanceBeforeMigration("userTask").getId())
        .done());

    // and it is possible to successfully complete the migrated instance
    testRule.completeTask("userTask");
    testRule.completeTask("userTask");
    testRule.assertProcessEnded(testRule.snapshotBeforeMigration.getProcessInstanceId());
  }


  @Test
  public void testMigrateSequenatialMiBodyRemoveSubprocess() {
    // given
    ProcessDefinition sourceProcessDefinition = testRule.deployAndGetDefinition(MultiInstanceProcessModels.SEQ_MI_SUBPROCESS_PROCESS);
    ProcessDefinition targetProcessDefinition = testRule.deployAndGetDefinition(MultiInstanceProcessModels.SEQ_MI_ONE_TASK_PROCESS);

    try {
      engineRule.getRuntimeService()
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities(miBodyOf("subProcess"), miBodyOf("userTask"))
      .mapActivities("userTask", "userTask")
      .build();
      fail("Should not succeed");
    }
    catch (MigrationPlanValidationException e) {
      assertReport(e.getValidationReport())
        .hasInstructionFailures(miBodyOf("subProcess"),
          "Cannot remove the inner activity of a multi-instance body when the body is mapped"
        );
    }
  }


  @Test
  public void testMigrateSequentialMiBodyAddSubprocess() {
    // given
    ProcessDefinition sourceProcessDefinition = testRule.deployAndGetDefinition(MultiInstanceProcessModels.SEQ_MI_ONE_TASK_PROCESS);
    ProcessDefinition targetProcessDefinition = testRule.deployAndGetDefinition(MultiInstanceProcessModels.SEQ_MI_SUBPROCESS_PROCESS);

    try {
      engineRule.getRuntimeService()
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities(miBodyOf("userTask"), miBodyOf("subProcess"))
      .mapActivities("userTask", "userTask")
      .build();
      fail("Should not succeed");
    }
    catch (MigrationPlanValidationException e) {
      assertReport(e.getValidationReport())
        .hasInstructionFailures(miBodyOf("userTask"),
          "Must map the inner activity of a multi-instance body when the body is mapped"
        );
    }
  }

  @Test
  public void testMigrateParallelToSequential() {
    // given
    ProcessDefinition sourceProcessDefinition = testRule.deployAndGetDefinition(MultiInstanceProcessModels.PAR_MI_ONE_TASK_PROCESS);
    ProcessDefinition targetProcessDefinition = testRule.deployAndGetDefinition(MultiInstanceProcessModels.SEQ_MI_ONE_TASK_PROCESS);

    try {
      engineRule.getRuntimeService()
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities(miBodyOf("userTask"), miBodyOf("userTask"))
      .mapActivities("userTask", "userTask")
      .build();
      fail("Should not succeed");
    }
    catch (MigrationPlanValidationException e) {
      assertReport(e.getValidationReport())
        .hasInstructionFailures(miBodyOf("userTask"),
          "Activities have incompatible types (ParallelMultiInstanceActivityBehavior is not "
          + "compatible with SequentialMultiInstanceActivityBehavior)"
        );
    }
  }

  protected String miBodyOf(String activityId) {
    return activityId + BpmnParse.MULTI_INSTANCE_BODY_ID_SUFFIX;
  }

}
