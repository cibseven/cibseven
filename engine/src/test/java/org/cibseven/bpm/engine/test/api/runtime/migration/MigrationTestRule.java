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

import static org.cibseven.bpm.engine.test.util.ActivityInstanceAssert.assertThat;
import static org.cibseven.bpm.engine.test.util.ExecutionAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.impl.cmmn.entity.runtime.CaseExecutionEntity;
import org.cibseven.bpm.engine.impl.jobexecutor.TimerCatchIntermediateEventJobHandler;
import org.cibseven.bpm.engine.impl.jobexecutor.TimerExecuteNestedActivityJobHandler;
import org.cibseven.bpm.engine.impl.jobexecutor.TimerStartEventSubprocessJobHandler;
import org.cibseven.bpm.engine.impl.jobexecutor.TimerTaskListenerJobHandler;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.JobEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.TimerEntity;
import org.cibseven.bpm.engine.management.JobDefinition;
import org.cibseven.bpm.engine.migration.MigrationPlan;
import org.cibseven.bpm.engine.runtime.ActivityInstance;
import org.cibseven.bpm.engine.runtime.EventSubscription;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.runtime.VariableInstance;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.util.ActivityInstanceAssert.ActivityInstanceAssertThatClause;
import org.cibseven.bpm.engine.test.util.ExecutionAssert;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.junit.jupiter.api.Assertions;

/**
 * @author Thorben Lindhauer
 *
 */
public class MigrationTestRule extends ProcessEngineTestRule {

  public ProcessInstanceSnapshot snapshotBeforeMigration;
  public ProcessInstanceSnapshot snapshotAfterMigration;

  public MigrationTestRule(ProcessEngineRule processEngineRule) {
    super(processEngineRule);
  }

  public String getSingleExecutionIdForActivity(ActivityInstance activityInstance, String activityId) {
    ActivityInstance singleInstance = getSingleActivityInstance(activityInstance, activityId);

    String[] executionIds = singleInstance.getExecutionIds();
    if (executionIds.length == 1) {
      return executionIds[0];
    }
    else {
      throw new RuntimeException("There is more than one execution assigned to activity instance " + singleInstance.getId());
    }
  }

  public String getSingleExecutionIdForActivityBeforeMigration(String activityId) {
    return getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), activityId);
  }

  public String getSingleExecutionIdForActivityAfterMigration(String activityId) {
    return getSingleExecutionIdForActivity(snapshotAfterMigration.getActivityTree(), activityId);
  }

  public ActivityInstance getSingleActivityInstance(ActivityInstance tree, String activityId) {
    ActivityInstance[] activityInstances = tree.getActivityInstances(activityId);
    if (activityInstances.length == 1) {
      return activityInstances[0];
    }
    else {
      throw new RuntimeException("There is not exactly one activity instance for activity " + activityId);
    }
  }

  public ActivityInstance getSingleActivityInstanceBeforeMigration(String activityId) {
    return getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), activityId);
  }

  public ActivityInstance getSingleActivityInstanceAfterMigration(String activityId) {
    return getSingleActivityInstance(snapshotAfterMigration.getActivityTree(), activityId);
  }

  public ProcessInstanceSnapshot takeFullProcessInstanceSnapshot(ProcessInstance processInstance) {
    return takeProcessInstanceSnapshot(processInstance).full();
  }

  public ProcessInstanceSnapshotBuilder takeProcessInstanceSnapshot(ProcessInstance processInstance) {
    return new ProcessInstanceSnapshotBuilder(processInstance, processEngine);
  }

  public ProcessInstance createProcessInstanceAndMigrate(MigrationPlan migrationPlan) {
    ProcessInstance processInstance = processEngine.getRuntimeService()
      .startProcessInstanceById(migrationPlan.getSourceProcessDefinitionId());

    migrateProcessInstance(migrationPlan, processInstance);
    return processInstance;
  }

  public ProcessInstance createProcessInstanceAndMigrate(MigrationPlan migrationPlan, Map<String, Object> variables) {
    ProcessInstance processInstance = processEngine.getRuntimeService()
        .startProcessInstanceById(migrationPlan.getSourceProcessDefinitionId(), variables);

    migrateProcessInstance(migrationPlan, processInstance);
    return processInstance;
  }

  public void migrateProcessInstance(MigrationPlan migrationPlan, ProcessInstance processInstance) {
    snapshotBeforeMigration = takeFullProcessInstanceSnapshot(processInstance);

    RuntimeService runtimeService = processEngine.getRuntimeService();

    runtimeService
      .newMigration(migrationPlan).processInstanceIds(Collections.singletonList(snapshotBeforeMigration.getProcessInstanceId())).execute();

    // fetch updated process instance
    processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(processInstance.getId()).singleResult();

    snapshotAfterMigration = takeFullProcessInstanceSnapshot(processInstance);
  }

  public void triggerTimer() {
    Job job = assertTimerJobExists(snapshotAfterMigration);
    processEngine.getManagementService().executeJob(job.getId());
  }

  public ExecutionAssert assertExecutionTreeAfterMigration() {
    return assertThat(snapshotAfterMigration.getExecutionTree());
  }

  public ActivityInstanceAssertThatClause assertActivityTreeAfterMigration() {
    return assertThat(snapshotAfterMigration.getActivityTree());
  }

  public void assertEventSubscriptionsMigrated(String activityIdBefore, String activityIdAfter, String eventName) {
    List<EventSubscription> eventSubscriptionsBefore = snapshotBeforeMigration.getEventSubscriptionsForActivityIdAndEventName(activityIdAfter, eventName);

    for (EventSubscription eventSubscription : eventSubscriptionsBefore) {
      assertEventSubscriptionMigrated(eventSubscription, activityIdAfter, eventName);
    }
  }

  protected void assertEventSubscriptionMigrated(EventSubscription eventSubscriptionBefore, String activityIdAfter, String eventName) {
    EventSubscription eventSubscriptionAfter = snapshotAfterMigration.getEventSubscriptionById(eventSubscriptionBefore.getId());
    assertNotNull(eventSubscriptionAfter, "Expected that an event subscription with id '" + eventSubscriptionBefore.getId() + "' "
        + "exists after migration");

    assertEquals(eventSubscriptionBefore.getEventType(), eventSubscriptionAfter.getEventType());
    assertEquals(activityIdAfter, eventSubscriptionAfter.getActivityId());
    assertEquals(eventName, eventSubscriptionAfter.getEventName());
  }


  public void assertEventSubscriptionMigrated(String activityIdBefore, String activityIdAfter, String eventName) {
    EventSubscription eventSubscriptionBefore = snapshotBeforeMigration.getEventSubscriptionForActivityIdAndEventName(activityIdBefore, eventName);
    assertNotNull(eventSubscriptionBefore, "Expected that an event subscription for activity '" + activityIdBefore + "' exists before migration");

    assertEventSubscriptionMigrated(eventSubscriptionBefore, activityIdAfter, eventName);
  }

  public void assertEventSubscriptionMigrated(String activityIdBefore, String eventNameBefore, String activityIdAfter, String eventNameAfter) {
    EventSubscription eventSubscriptionBefore = snapshotBeforeMigration.getEventSubscriptionForActivityIdAndEventName(activityIdBefore, eventNameBefore);
    assertNotNull(eventSubscriptionBefore, "Expected that an event subscription for activity '" + activityIdBefore + "' exists before migration");

    assertEventSubscriptionMigrated(eventSubscriptionBefore, activityIdAfter, eventNameAfter);
  }

  public void assertEventSubscriptionRemoved(String activityId, String eventName) {
    EventSubscription eventSubscriptionBefore = snapshotBeforeMigration.getEventSubscriptionForActivityIdAndEventName(activityId, eventName);
    assertNotNull(eventSubscriptionBefore, "Expected an event subscription for activity '" + activityId + "' before the migration");

    for (EventSubscription eventSubscription : snapshotAfterMigration.getEventSubscriptions()) {
      if (eventSubscriptionBefore.getId().equals(eventSubscription.getId())) {
        fail("Expected event subscription '" + eventSubscriptionBefore.getId() + "' to be removed after migration");
      }
    }
  }

  public void assertEventSubscriptionCreated(String activityId, String eventName) {
    EventSubscription eventSubscriptionAfter = snapshotAfterMigration.getEventSubscriptionForActivityIdAndEventName(activityId, eventName);
    assertNotNull(eventSubscriptionAfter, "Expected an event subscription for activity '" + activityId + "' after the migration");

    for (EventSubscription eventSubscription : snapshotBeforeMigration.getEventSubscriptions()) {
      if (eventSubscriptionAfter.getId().equals(eventSubscription.getId())) {
        fail("Expected event subscription '" + eventSubscriptionAfter.getId() + "' to be created after migration");
      }
    }
  }

  public void assertTimerJob(Job job) {
    assertEquals("Expected job to be a timer job", TimerEntity.TYPE, ((JobEntity) job).getType());
  }

  public Job assertTimerJobExists(ProcessInstanceSnapshot snapshot) {
    List<Job> jobs = snapshot.getJobs();
    assertEquals(1, jobs.size());
    Job job = jobs.get(0);
    assertTimerJob(job);
    return job;
  }

  public void assertJobCreated(String activityId, String handlerType) {
    JobDefinition jobDefinitionAfter = snapshotAfterMigration.getJobDefinitionForActivityIdAndType(activityId, handlerType);
    assertNotNull(jobDefinitionAfter, "Expected that a job definition for activity '" + activityId + "' exists after migration");

    Job jobAfter = snapshotAfterMigration.getJobForDefinitionId(jobDefinitionAfter.getId());
    assertNotNull(jobAfter, "Expected that a job for activity '" + activityId + "' exists after migration");
    assertTimerJob(jobAfter);
    assertEquals(jobDefinitionAfter.getProcessDefinitionId(), jobAfter.getProcessDefinitionId());
    assertEquals(jobDefinitionAfter.getProcessDefinitionKey(), jobAfter.getProcessDefinitionKey());

    for (Job job : snapshotBeforeMigration.getJobs()) {
      if (jobAfter.getId().equals(job.getId())) {
        fail("Expected job '" + jobAfter.getId() + "' to be created first after migration");
      }
    }
  }

  public void assertJobsCreated(String activityId, String handlerType, int countJobs) {
    List<JobDefinition> jobDefinitionsAfter = snapshotAfterMigration.getJobDefinitionsForActivityIdAndType(activityId, handlerType);
    assertEquals(
        countJobs, jobDefinitionsAfter.size(),
        "Expected that " + countJobs + "job definitions for activity '" + activityId + "' exist after migration, but found " + jobDefinitionsAfter.size());

    for (JobDefinition jobDefinitionAfter : jobDefinitionsAfter) {
      Job jobAfter = snapshotAfterMigration.getJobForDefinitionId(jobDefinitionAfter.getId());
      assertNotNull(jobAfter, "Expected that a job for activity '" + activityId + "' exists after migration");
      assertTimerJob(jobAfter);
      assertEquals(jobDefinitionAfter.getProcessDefinitionId(), jobAfter.getProcessDefinitionId());
      assertEquals(jobDefinitionAfter.getProcessDefinitionKey(), jobAfter.getProcessDefinitionKey());

      for (Job job : snapshotBeforeMigration.getJobs()) {
        if (jobAfter.getId().equals(job.getId())) {
          fail("Expected job '" + jobAfter.getId() + "' to be created first after migration");
        }
      }
    }
  }

  public void assertJobRemoved(String activityId, String handlerType) {
    JobDefinition jobDefinitionBefore = snapshotBeforeMigration.getJobDefinitionForActivityIdAndType(activityId, handlerType);
    assertNotNull(jobDefinitionBefore, "Expected that a job definition for activity '" + activityId + "' exists before migration");

    Job jobBefore = snapshotBeforeMigration.getJobForDefinitionId(jobDefinitionBefore.getId());
    assertNotNull(jobBefore, "Expected that a job for activity '" + activityId + "' exists before migration");
    assertTimerJob(jobBefore);

    for (Job job : snapshotAfterMigration.getJobs()) {
      if (jobBefore.getId().equals(job.getId())) {
        fail("Expected job '" + jobBefore.getId() + "' to be removed after migration");
      }
    }
  }

  public void assertJobMigrated(String activityIdBefore, String activityIdAfter, String handlerType) {
    assertJobMigrated(activityIdBefore, activityIdAfter, handlerType, null);
  }

  public void assertJobMigrated(String activityIdBefore, String activityIdAfter, String handlerType, Date dueDateAfter) {
    JobDefinition jobDefinitionBefore = snapshotBeforeMigration.getJobDefinitionForActivityIdAndType(activityIdBefore, handlerType);
    assertNotNull(jobDefinitionBefore, "Expected that a job definition for activity '" + activityIdBefore + "' exists before migration");

    Job jobBefore = snapshotBeforeMigration.getJobForDefinitionId(jobDefinitionBefore.getId());
    assertNotNull(jobBefore, "Expected that a timer job for activity '" + activityIdBefore + "' exists before migration");

    assertJobMigrated(jobBefore, activityIdAfter, dueDateAfter == null ? jobBefore.getDuedate() : dueDateAfter);
  }

  public void assertJobMigrated(Job jobBefore, String activityIdAfter) {
    assertJobMigrated(jobBefore, activityIdAfter, jobBefore.getDuedate());
  }

  public void assertJobMigrated(Job jobBefore, String activityIdAfter, Date dueDateAfter) {

    Job jobAfter = snapshotAfterMigration.getJobById(jobBefore.getId());
    assertNotNull(jobAfter, "Expected that a job with id '" + jobBefore.getId() + "' exists after migration");

    JobDefinition jobDefinitionAfter = snapshotAfterMigration.getJobDefinitionForActivityIdAndType(activityIdAfter, ((JobEntity) jobBefore).getJobHandlerType());
    assertNotNull(jobDefinitionAfter, "Expected that a job definition for activity '" + activityIdAfter + "' exists after migration");

    assertEquals(jobBefore.getId(), jobAfter.getId());
    assertEquals("Expected that job is assigned to job definition '" + jobDefinitionAfter.getId() + "' after migration",
        jobDefinitionAfter.getId(), jobAfter.getJobDefinitionId());
    assertEquals("Expected that job is assigned to deployment '" + snapshotAfterMigration.getDeploymentId() + "' after migration",
        snapshotAfterMigration.getDeploymentId(), jobAfter.getDeploymentId());
    assertEquals(dueDateAfter, jobAfter.getDuedate());
    assertEquals(((JobEntity) jobBefore).getType(), ((JobEntity) jobAfter).getType());
    assertEquals(jobBefore.getPriority(), jobAfter.getPriority());
    assertEquals(jobDefinitionAfter.getProcessDefinitionId(), jobAfter.getProcessDefinitionId());
    assertEquals(jobDefinitionAfter.getProcessDefinitionKey(), jobAfter.getProcessDefinitionKey());
  }

  public void assertBoundaryTimerJobCreated(String activityId) {
    assertJobCreated(activityId, TimerExecuteNestedActivityJobHandler.TYPE);
  }

  public void assertBoundaryTimerJobRemoved(String activityId) {
    assertJobRemoved(activityId, TimerExecuteNestedActivityJobHandler.TYPE);
  }

  public void assertBoundaryTimerJobMigrated(String activityIdBefore, String activityIdAfter) {
    assertJobMigrated(activityIdBefore, activityIdAfter, TimerExecuteNestedActivityJobHandler.TYPE);
  }

  public void assertIntermediateTimerJobCreated(String activityId) {
    assertJobCreated(activityId, TimerCatchIntermediateEventJobHandler.TYPE);
  }

  public void assertIntermediateTimerJobRemoved(String activityId) {
    assertJobRemoved(activityId, TimerCatchIntermediateEventJobHandler.TYPE);
  }

  public void assertIntermediateTimerJobMigrated(String activityIdBefore, String activityIdAfter) {
    assertJobMigrated(activityIdBefore, activityIdAfter, TimerCatchIntermediateEventJobHandler.TYPE);
  }

  public void assertEventSubProcessTimerJobCreated(String activityId) {
    assertJobCreated(activityId, TimerStartEventSubprocessJobHandler.TYPE);
  }

  public void assertEventSubProcessTimerJobRemoved(String activityId) {
    assertJobRemoved(activityId, TimerStartEventSubprocessJobHandler.TYPE);
  }

  public void assertTaskListenerTimerJobCreated(String activityId) {
    assertJobCreated(activityId, TimerTaskListenerJobHandler.TYPE);
  }

  public void assertTaskListenerTimerJobsCreated(String activityId, int countJobs) {
    assertJobsCreated(activityId, TimerTaskListenerJobHandler.TYPE, countJobs);
  }

  public void assertTaskListenerTimerJobRemoved(String activityId) {
    assertJobRemoved(activityId, TimerTaskListenerJobHandler.TYPE);
  }

  public void assertTaskListenerTimerJobMigrated(String activityIdBefore, String activityIdAfter) {
    assertJobMigrated(activityIdBefore, activityIdAfter, TimerTaskListenerJobHandler.TYPE);
  }

  public void assertTaskListenerTimerJobMigrated(String activityIdBefore, String activityIdAfter, Date dueDateAfter) {
    assertJobMigrated(activityIdBefore, activityIdAfter, TimerTaskListenerJobHandler.TYPE, dueDateAfter);
  }

  public void assertVariableMigratedToExecution(VariableInstance variableBefore, String executionId) {
    assertVariableMigratedToExecution(variableBefore, executionId, variableBefore.getActivityInstanceId());
  }

  public void assertVariableMigratedToExecution(VariableInstance variableBefore, String executionId, String activityInstanceId) {
    VariableInstance variableAfter = snapshotAfterMigration.getVariable(variableBefore.getId());

    Assertions.assertNotNull(variableAfter, "Variable with id " + variableBefore.getId() + " does not exist");

    Assertions.assertEquals(activityInstanceId, variableAfter.getActivityInstanceId());
    Assertions.assertEquals(variableBefore.getCaseExecutionId(), variableAfter.getCaseExecutionId());
    Assertions.assertEquals(variableBefore.getCaseInstanceId(), variableAfter.getCaseInstanceId());
    Assertions.assertEquals(variableBefore.getErrorMessage(), variableAfter.getErrorMessage());
    Assertions.assertEquals(executionId, variableAfter.getExecutionId());
    Assertions.assertEquals(variableBefore.getId(), variableAfter.getId());
    Assertions.assertEquals(variableBefore.getName(), variableAfter.getName());
    Assertions.assertEquals(variableBefore.getProcessInstanceId(), variableAfter.getProcessInstanceId());
    Assertions.assertEquals(variableBefore.getTaskId(), variableAfter.getTaskId());
    Assertions.assertEquals(variableBefore.getTenantId(), variableAfter.getTenantId());
    Assertions.assertEquals(variableBefore.getTypeName(), variableAfter.getTypeName());
    Assertions.assertEquals(variableBefore.getValue(), variableAfter.getValue());
  }

  public void assertSuperExecutionOfCaseInstance(String caseInstanceId, String expectedSuperExecutionId) {
    CaseExecutionEntity calledInstance = (CaseExecutionEntity) processEngine.getCaseService()
        .createCaseInstanceQuery()
        .caseInstanceId(caseInstanceId)
        .singleResult();

    Assertions.assertEquals(expectedSuperExecutionId, calledInstance.getSuperExecutionId());
  }

  public void assertSuperExecutionOfProcessInstance(String processInstance, String expectedSuperExecutionId) {
    ExecutionEntity calledInstance = (ExecutionEntity) processEngine.getRuntimeService()
        .createProcessInstanceQuery()
        .processInstanceId(processInstance)
        .singleResult();

    Assertions.assertEquals(expectedSuperExecutionId, calledInstance.getSuperExecutionId());
  }

}
