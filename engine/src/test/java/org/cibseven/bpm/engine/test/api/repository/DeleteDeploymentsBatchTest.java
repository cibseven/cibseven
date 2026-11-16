/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
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
package org.cibseven.bpm.engine.test.api.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.EntityTypes;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.authorization.BatchPermissions;
import org.cibseven.bpm.engine.batch.Batch;
import org.cibseven.bpm.engine.batch.history.HistoricBatch;
import org.cibseven.bpm.engine.history.UserOperationLogEntry;
import org.cibseven.bpm.engine.impl.batch.DeploymentMapping;
import org.cibseven.bpm.engine.impl.batch.DeploymentMappings;
import org.cibseven.bpm.engine.impl.batch.builder.BatchBuilder;
import org.cibseven.bpm.engine.impl.batch.deletion.DeleteDeploymentBatchConfiguration;
import org.cibseven.bpm.engine.impl.cmd.AcquireJobsCmd;
import org.cibseven.bpm.engine.impl.jobexecutor.AcquiredJobs;
import org.cibseven.bpm.engine.impl.jobexecutor.JobExecutor;
import org.cibseven.bpm.engine.management.JobDefinition;
import org.cibseven.bpm.engine.repository.Deployment;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Tests the asynchronous deletion of deployments via batch (CIB7-1597):
 * {@code RepositoryService#deleteDeploymentsAsync} -> {@code DeleteDeploymentsBatchCmd}
 * -> {@code DeleteDeploymentsJobHandler} -> {@code DeleteDeploymentCmd} per deployment.
 */
public class DeleteDeploymentsBatchTest {

  @RegisterExtension
  @Order(2) protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  @Order(4) protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);


  protected RepositoryService repositoryService;
  protected ManagementService managementService;
  protected RuntimeService runtimeService;
  protected HistoryService historyService;
  protected IdentityService identityService;

  protected int defaultInvocationsPerBatchJob;
  protected int defaultBatchJobsPerSeed;
  protected boolean defaultJobExecutorDeploymentAware;
  protected final List<String> createdDeploymentIds = new ArrayList<>();

  @BeforeEach
  public void setUp() {
    repositoryService = engineRule.getRepositoryService();
    managementService = engineRule.getManagementService();
    runtimeService = engineRule.getRuntimeService();
    historyService = engineRule.getHistoryService();
    identityService = engineRule.getIdentityService();
    defaultInvocationsPerBatchJob = engineRule.getProcessEngineConfiguration().getInvocationsPerBatchJob();
    defaultBatchJobsPerSeed = engineRule.getProcessEngineConfiguration().getBatchJobsPerSeed();
    defaultJobExecutorDeploymentAware = engineRule.getProcessEngineConfiguration().isJobExecutorDeploymentAware();
  }

  @AfterEach
  public void tearDown() {
    identityService.clearAuthentication();
    engineRule.getProcessEngineConfiguration().setInvocationsPerBatchJob(defaultInvocationsPerBatchJob);
    engineRule.getProcessEngineConfiguration().setBatchJobsPerSeed(defaultBatchJobsPerSeed);
    engineRule.getProcessEngineConfiguration().setJobExecutorDeploymentAware(defaultJobExecutorDeploymentAware);

    for (Batch batch : managementService.createBatchQuery().list()) {
      managementService.deleteBatch(batch.getId(), true);
    }
    for (HistoricBatch historicBatch : historyService.createHistoricBatchQuery().list()) {
      historyService.deleteHistoricBatch(historicBatch.getId());
    }
    for (String deploymentId : createdDeploymentIds) {
      if (repositoryService.createDeploymentQuery().deploymentId(deploymentId).count() > 0) {
        repositoryService.deleteDeployment(deploymentId, true);
      }
    }
    createdDeploymentIds.clear();
  }

  // ---------------------------------------------------------------- helpers

  /** Deploys a one-task process; the user task keeps started instances alive for cascade tests. */
  protected String deploy(String processKey, String source) {
    BpmnModelInstance model = Bpmn.createExecutableProcess(processKey)
        .startEvent()
        .userTask("task")
        .endEvent()
        .done();

    Deployment deployment = repositoryService.createDeployment()
        .name(processKey)
        .source(source)
        .addModelInstance(processKey + ".bpmn", model)
        .deploy();

    createdDeploymentIds.add(deployment.getId());
    return deployment.getId();
  }

  /** Deploys one more version of a process that starts by the message "order received". */
  protected String deployMessageStart(String processKey) {
    BpmnModelInstance model = Bpmn.createExecutableProcess(processKey)
        .startEvent()
          .message("order received")
        .userTask("task")
        .endEvent()
        .done();

    Deployment deployment = repositoryService.createDeployment()
        .addModelInstance(processKey + ".bpmn", model)
        .deploy();

    createdDeploymentIds.add(deployment.getId());
    return deployment.getId();
  }

  protected boolean deploymentExists(String deploymentId) {
    return repositoryService.createDeploymentQuery().deploymentId(deploymentId).count() > 0;
  }

  protected void executeBatch(Batch batch) {
    executeSeedJobs(batch);
    // re-query after each round in case jobs create follow-up jobs
    List<Job> executionJobs;
    while (!(executionJobs = managementService.createJobQuery()
        .jobDefinitionId(batch.getBatchJobDefinitionId()).list()).isEmpty()) {
      for (Job job : executionJobs) {
        managementService.executeJob(job.getId());
      }
    }
  }

  /** Runs all seed jobs, creating the execution jobs; each seed run creates up to batchJobsPerSeed of them. */
  protected void executeSeedJobs(Batch batch) {
    Job seedJob = getSeedJob(batch);
    while (seedJob != null) {
      managementService.executeJob(seedJob.getId());
      seedJob = getSeedJob(batch);
    }
  }

  protected Job getSeedJob(Batch batch) {
    return managementService.createJobQuery().jobDefinitionId(batch.getSeedJobDefinitionId()).singleResult();
  }

  /**
   * The jobs the job executor acquires next, as in {@code DeploymentAwareJobExecutorTest}; with
   * a fixed number of jobs so that the result does not depend on maxJobsPerAcquisition.
   */
  protected AcquiredJobs getExecutableJobs() {
    JobExecutor jobExecutor = engineRule.getProcessEngineConfiguration().getJobExecutor();
    return engineRule.getProcessEngineConfiguration().getCommandExecutorTxRequired()
        .execute(new AcquireJobsCmd(jobExecutor, 10));
  }

  protected List<Job> getExecutionJobs(Batch batch) {
    return managementService.createJobQuery().jobDefinitionId(batch.getBatchJobDefinitionId()).list();
  }

  // ---------------------------------------------------------------- creation

  @Test
  public void shouldCreateBatchWithCorrectType() {
    String d1 = deploy("process1", "src");
    String d2 = deploy("process2", "src");

    Batch batch = repositoryService.deleteDeploymentsAsync(Arrays.asList(d1, d2), null, false, false, false);

    assertThat(batch).isNotNull();
    assertThat(batch.getType()).isEqualTo(Batch.TYPE_DEPLOYMENT_DELETION);
    assertThat(batch.getTotalJobs()).isEqualTo(2);
  }

  @Test
  public void shouldCreateHistoricBatch() {
    String d1 = deploy("process1", "src");

    Batch batch = repositoryService.deleteDeploymentsAsync(Collections.singletonList(d1), null, false, false, false);

    HistoricBatch historicBatch = historyService.createHistoricBatchQuery().batchId(batch.getId()).singleResult();
    assertThat(historicBatch).isNotNull();
    assertThat(historicBatch.getType()).isEqualTo(Batch.TYPE_DEPLOYMENT_DELETION);
  }

  @Test
  public void shouldCreateOneExecutionJobPerProcess() {
    engineRule.getProcessEngineConfiguration().setInvocationsPerBatchJob(2);
    String d1 = deploy("process1", "src");
    String d2 = deploy("process2", "src");
    String d3 = deploy("process3", "src");

    Batch batch = repositoryService.deleteDeploymentsAsync(Arrays.asList(d1, d2, d3), null, false, false, false);

    // one job per group of deployments of the same process (CIB7-2038); deployments of
    // different processes are not put into one job, whatever invocationsPerBatchJob says
    assertThat(batch.getTotalJobs()).isEqualTo(3);

    executeSeedJobs(batch);
    assertThat(getExecutionJobs(batch)).hasSize(3);

    executeBatch(batch);
    assertThat(deploymentExists(d1)).isFalse();
    assertThat(deploymentExists(d2)).isFalse();
    assertThat(deploymentExists(d3)).isFalse();
  }

  /**
   * Regression test for CIB7-2038: deleting the latest version of a process moves its start
   * events to the previous version. Versions of one process are therefore deleted by one job,
   * newest first, so that the version that stays keeps its message start.
   */
  @Test
  public void shouldDeleteVersionsOfOneProcessInOneJob() {
    String v1 = deployMessageStart("order");
    String v2 = deployMessageStart("order");
    String v3 = deployMessageStart("order");
    String other = deploy("process1", "src");

    Batch batch = repositoryService.deleteDeploymentsAsync(Arrays.asList(v2, other, v3), null, true, false, false);

    assertThat(batch.getTotalJobs()).isEqualTo(2);
    executeSeedJobs(batch);
    assertThat(getExecutionJobs(batch)).hasSize(2);

    executeBatch(batch);
    assertThat(deploymentExists(v1)).isTrue();
    assertThat(deploymentExists(v2)).isFalse();
    assertThat(deploymentExists(v3)).isFalse();
    assertThat(deploymentExists(other)).isFalse();

    ProcessDefinition first = repositoryService.createProcessDefinitionQuery().deploymentId(v1).singleResult();
    assertThat(runtimeService.createEventSubscriptionQuery().eventName("order received").count()).isEqualTo(1);
    assertThat(runtimeService.createMessageCorrelation("order received").correlateStartMessage().getProcessDefinitionId())
        .isEqualTo(first.getId());
  }

  // ---------------------------------------------------------------- deployment-aware job executor

  /**
   * The batch deletes the deployments it works on, so its seed jobs must not be bound to
   * one of them (CIB7-2038).
   */
  @Test
  public void shouldNotSetDeploymentIdOnSeedJob() {
    // one execution job per seed job run, so that a follow-up seed job is created
    engineRule.getProcessEngineConfiguration().setBatchJobsPerSeed(1);
    String d1 = deploy("process1", "src");
    String d2 = deploy("process2", "src");

    Batch batch = repositoryService.deleteDeploymentsAsync(Arrays.asList(d1, d2), null, false, false, false);

    JobDefinition seedJobDefinition = managementService.createJobDefinitionQuery()
        .jobDefinitionId(batch.getSeedJobDefinitionId()).singleResult();
    assertThat(seedJobDefinition.getDeploymentId()).isNull();
    assertThat(getSeedJob(batch).getDeploymentId()).isNull();

    // the follow-up seed job is created from the same job definition
    managementService.executeJob(getSeedJob(batch).getId());
    assertThat(getSeedJob(batch).getDeploymentId()).isNull();
  }

  /**
   * A deployment-aware job executor only acquires jobs of deployments that are registered on
   * its node. The synchronous deletion works on every node, whether the deployment is
   * registered there or not, and the batch must not be stricter than that: its execution jobs
   * are not bound to the deployment they delete (CIB7-2038).
   */
  @Test
  public void shouldNotSetDeploymentIdOnExecutionJobs() {
    String d1 = deploy("process1", "src");
    String d2 = deploy("process2", "src");

    Batch batch = repositoryService.deleteDeploymentsAsync(Arrays.asList(d1, d2), null, false, false, false);
    executeSeedJobs(batch);

    List<Job> executionJobs = getExecutionJobs(batch);
    assertThat(executionJobs).hasSize(2);
    assertThat(executionJobs)
        .extracting(Job::getDeploymentId)
        .containsOnlyNulls();
  }

  /**
   * Regression test for CIB7-2038: deleting a deployment unregisters it from the job executor.
   * A deployment-aware job executor then no longer acquired the remaining seed jobs, because
   * they were bound to the first deployment of the batch, and the batch never finished.
   */
  @Test
  public void shouldAcquireSeedJobAfterFirstDeploymentIsDeleted() {
    engineRule.getProcessEngineConfiguration().setJobExecutorDeploymentAware(true);
    // one execution job per seed job run, so that the batch needs several seed jobs
    engineRule.getProcessEngineConfiguration().setBatchJobsPerSeed(1);
    String d1 = deploy("process1", "src");
    String d2 = deploy("process2", "src");
    String d3 = deploy("process3", "src");

    Batch batch = repositoryService.deleteDeploymentsAsync(Arrays.asList(d1, d2, d3), null, false, false, false);

    // the first seed job run creates the execution job of the first deployment only
    managementService.executeJob(getSeedJob(batch).getId());
    List<Job> executionJobs = getExecutionJobs(batch);
    assertThat(executionJobs).hasSize(1);

    managementService.executeJob(executionJobs.get(0).getId());
    assertThat(deploymentExists(d1)).isFalse();
    assertThat(managementService.getRegisteredDeployments()).doesNotContain(d1).contains(d2, d3);

    // the seed job for the remaining deployments must still be acquired
    Job nextSeedJob = getSeedJob(batch);
    assertThat(nextSeedJob).isNotNull();
    assertThat(getExecutableJobs().contains(nextSeedJob.getId()))
        .as("seed job is acquired by the deployment-aware job executor")
        .isTrue();
  }

  /**
   * Regression test for CIB7-2038: a deployment that is not registered on the node, for
   * example one created through the REST API before the last restart, must still be deleted
   * by the batch on a deployment-aware engine.
   */
  @Test
  public void shouldAcquireJobsForUnregisteredDeployment() {
    engineRule.getProcessEngineConfiguration().setJobExecutorDeploymentAware(true);
    String registered = deploy("process1", "src");
    String unregistered = deploy("process2", "src");
    managementService.unregisterDeploymentForJobExecutor(unregistered);
    assertThat(managementService.getRegisteredDeployments()).contains(registered).doesNotContain(unregistered);

    Batch batch = repositoryService.deleteDeploymentsAsync(
        Arrays.asList(registered, unregistered), null, false, false, false);

    assertThat(getExecutableJobs().contains(getSeedJob(batch).getId()))
        .as("seed job is acquired by the deployment-aware job executor")
        .isTrue();

    executeSeedJobs(batch);

    // one execution job per deployment, both must be acquired
    List<Job> executionJobs = getExecutionJobs(batch);
    assertThat(executionJobs).hasSize(2);
    AcquiredJobs acquiredJobs = getExecutableJobs();
    for (Job executionJob : executionJobs) {
      assertThat(acquiredJobs.contains(executionJob.getId()))
          .as("execution job is acquired by the deployment-aware job executor")
          .isTrue();
    }

    executeBatch(batch);
    assertThat(deploymentExists(registered)).isFalse();
    assertThat(deploymentExists(unregistered)).isFalse();
  }

  /**
   * Batches created before CIB7-2038 have one deployment mapping per deployment in their
   * stored configuration. They must still be read and executed.
   */
  @Test
  public void shouldExecuteBatchCreatedWithDeploymentMappings() {
    String d1 = deploy("process1", "src");
    String d2 = deploy("process2", "src");
    List<String> ids = new ArrayList<>(Arrays.asList(d1, d2));

    Batch batch = engineRule.getProcessEngineConfiguration().getCommandExecutorTxRequired().execute(commandContext -> {
      DeploymentMappings mappings = new DeploymentMappings();
      ids.forEach(id -> mappings.add(new DeploymentMapping(id, 1)));
      return new BatchBuilder(commandContext)
          .type(Batch.TYPE_DEPLOYMENT_DELETION)
          .config(new DeleteDeploymentBatchConfiguration(ids, mappings, false, false, false))
          .permission(BatchPermissions.CREATE_BATCH_DELETE_DEPLOYMENTS)
          .operationLogHandler((ctx, numDeployments) -> { })
          .build();
    });

    executeSeedJobs(batch);
    assertThat(getExecutionJobs(batch))
        .extracting(Job::getDeploymentId)
        .containsExactlyInAnyOrder(d1, d2);

    executeBatch(batch);
    assertThat(deploymentExists(d1)).isFalse();
    assertThat(deploymentExists(d2)).isFalse();
  }

  // ---------------------------------------------------------------- deletion

  @Test
  public void shouldDeleteDeploymentsByIds() {
    String d1 = deploy("process1", "src");
    String d2 = deploy("process2", "src");

    Batch batch = repositoryService.deleteDeploymentsAsync(Arrays.asList(d1, d2), null, false, false, false);
    executeBatch(batch);

    assertThat(deploymentExists(d1)).isFalse();
    assertThat(deploymentExists(d2)).isFalse();
  }

  @Test
  public void shouldDeleteDeploymentsByQuery() {
    String source = "query-only-source";
    String d1 = deploy("process1", source);
    String d2 = deploy("process2", source);

    Batch batch = repositoryService.deleteDeploymentsAsync(
        null, repositoryService.createDeploymentQuery().deploymentSource(source), false, false, false);
    executeBatch(batch);

    assertThat(deploymentExists(d1)).isFalse();
    assertThat(deploymentExists(d2)).isFalse();
  }

  @Test
  public void shouldMergeIdsAndQueryAndDeduplicate() {
    String source = "merge-source";
    String d1 = deploy("process1", source);
    String d2 = deploy("process2", source);

    // d1 provided both explicitly and via the query -> must be deduplicated
    Batch batch = repositoryService.deleteDeploymentsAsync(
        Collections.singletonList(d1),
        repositoryService.createDeploymentQuery().deploymentSource(source),
        false, false, false);

    assertThat(batch.getTotalJobs()).isEqualTo(2);

    executeBatch(batch);
    assertThat(deploymentExists(d1)).isFalse();
    assertThat(deploymentExists(d2)).isFalse();
  }

  @Test
  public void shouldDeduplicateDuplicateExplicitIds() {
    String d1 = deploy("process1", "src");

    Batch batch = repositoryService.deleteDeploymentsAsync(Arrays.asList(d1, d1), null, false, false, false);

    assertThat(batch.getTotalJobs()).isEqualTo(1);

    executeBatch(batch);
    assertThat(deploymentExists(d1)).isFalse();
  }

  @Test
  public void shouldCascadeDeleteRunningInstances() {
    String d1 = deploy("process1", "src");
    runtimeService.startProcessInstanceByKey("process1");
    assertThat(runtimeService.createProcessInstanceQuery().processDefinitionKey("process1").count()).isEqualTo(1);

    Batch batch = repositoryService.deleteDeploymentsAsync(
        Collections.singletonList(d1), null, true, false, false);
    executeBatch(batch);

    assertThat(deploymentExists(d1)).isFalse();
    assertThat(runtimeService.createProcessInstanceQuery().processDefinitionKey("process1").count()).isZero();
  }

  /** With cascade=true the history of the deleted instances is removed as well, not kept. */
  @Test
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  public void shouldCascadeAlsoDeleteHistory() {
    String d1 = deploy("process1", "src");
    String processDefinitionId = repositoryService.createProcessDefinitionQuery()
        .processDefinitionKey("process1").singleResult().getId();
    runtimeService.startProcessInstanceByKey("process1");

    // history has been recorded for the running instance
    assertThat(historyService.createHistoricProcessInstanceQuery()
        .processDefinitionKey("process1").count()).isEqualTo(1);
    assertThat(historyService.createHistoricActivityInstanceQuery()
        .processDefinitionId(processDefinitionId).count()).isPositive();

    Batch batch = repositoryService.deleteDeploymentsAsync(
        Collections.singletonList(d1), null, true, false, false);
    executeBatch(batch);

    assertThat(historyService.createHistoricProcessInstanceQuery()
        .processDefinitionKey("process1").count()).isZero();
    assertThat(historyService.createHistoricActivityInstanceQuery()
        .processDefinitionId(processDefinitionId).count()).isZero();
  }

  // ---------------------------------------------------------------- edge cases

  @Test
  public void shouldThrowWhenNeitherIdsNorQueryProvided() {
    assertThatThrownBy(() -> repositoryService.deleteDeploymentsAsync(null, null, false, false, false))
        .isInstanceOf(BadUserRequestException.class)
        .hasMessageContaining("deploymentIds");
  }

  @Test
  public void shouldThrowWhenEmptyIdListProvided() {
    assertThatThrownBy(
        () -> repositoryService.deleteDeploymentsAsync(Collections.emptyList(), null, false, false, false))
        .isInstanceOf(BadUserRequestException.class);
  }

  @Test
  public void shouldThrowWhenQueryMatchesNothing() {
    assertThatThrownBy(() -> repositoryService.deleteDeploymentsAsync(
        null, repositoryService.createDeploymentQuery().deploymentSource("does-not-exist"), false, false, false))
        .isInstanceOf(BadUserRequestException.class);
  }

  @Test
  public void shouldFailForNonExistingDeploymentId() {
    String d1 = deploy("process1", "src");
    String d2 = deploy("process2", "src");

    // the invalid id sits between two valid ones on purpose: its position must not matter
    assertThatThrownBy(() -> repositoryService.deleteDeploymentsAsync(
        Arrays.asList(d1, "does-not-exist", d2), null, false, false, false))
        .isInstanceOf(BadUserRequestException.class)
        .hasMessageContaining("does-not-exist");

    // the whole batch is rejected: no batch is created, and no deployment gets deleted -
    // not even the ones that would have been valid on their own
    assertThat(managementService.createBatchQuery().count()).isZero();
    assertThat(deploymentExists(d1)).isTrue();
    assertThat(deploymentExists(d2)).isTrue();
  }

  // ---------------------------------------------------------------- operation log

  @Test
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  public void shouldWriteDeploymentOperationLog() {
    identityService.setAuthenticatedUserId("userId");
    String d1 = deploy("process1", "src");
    String d2 = deploy("process2", "src");

    repositoryService.deleteDeploymentsAsync(Arrays.asList(d1, d2), null, false, false, false);

    UserOperationLogEntry countEntry = historyService.createUserOperationLogQuery()
        .entityType(EntityTypes.DEPLOYMENT)
        .operationType(UserOperationLogEntry.OPERATION_TYPE_DELETE)
        .property("nrOfDeployments")
        .singleResult();
    assertThat(countEntry).isNotNull();
    assertThat(countEntry.getNewValue()).isEqualTo("2");
    assertThat(countEntry.getCategory()).isEqualTo(UserOperationLogEntry.CATEGORY_OPERATOR);

    UserOperationLogEntry asyncEntry = historyService.createUserOperationLogQuery()
        .entityType(EntityTypes.DEPLOYMENT)
        .operationType(UserOperationLogEntry.OPERATION_TYPE_DELETE)
        .property("async")
        .singleResult();
    assertThat(asyncEntry).isNotNull();
    assertThat(asyncEntry.getNewValue()).isEqualTo("true");
  }
}
