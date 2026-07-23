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
import org.cibseven.bpm.engine.batch.Batch;
import org.cibseven.bpm.engine.batch.history.HistoricBatch;
import org.cibseven.bpm.engine.history.UserOperationLogEntry;
import org.cibseven.bpm.engine.repository.Deployment;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

/**
 * Tests the asynchronous deletion of deployments via batch (CIB7-1597):
 * {@code RepositoryService#deleteDeploymentsAsync} -> {@code DeleteDeploymentsBatchCmd}
 * -> {@code DeleteDeploymentsJobHandler} -> {@code DeleteDeploymentCmd} per deployment.
 */
public class DeleteDeploymentsBatchTest {

  protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  @Rule
  public RuleChain ruleChain = RuleChain.outerRule(engineRule).around(testRule);

  protected RepositoryService repositoryService;
  protected ManagementService managementService;
  protected RuntimeService runtimeService;
  protected HistoryService historyService;
  protected IdentityService identityService;

  protected int defaultInvocationsPerBatchJob;
  protected final List<String> createdDeploymentIds = new ArrayList<>();

  @Before
  public void setUp() {
    repositoryService = engineRule.getRepositoryService();
    managementService = engineRule.getManagementService();
    runtimeService = engineRule.getRuntimeService();
    historyService = engineRule.getHistoryService();
    identityService = engineRule.getIdentityService();
    defaultInvocationsPerBatchJob = engineRule.getProcessEngineConfiguration().getInvocationsPerBatchJob();
  }

  @After
  public void tearDown() {
    identityService.clearAuthentication();
    engineRule.getProcessEngineConfiguration().setInvocationsPerBatchJob(defaultInvocationsPerBatchJob);

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

  /** Runs all seed jobs, creating the execution jobs; each seed run handles one deployment mapping. */
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
  public void shouldCreateOneExecutionJobPerDeployment() {
    engineRule.getProcessEngineConfiguration().setInvocationsPerBatchJob(2);
    String d1 = deploy("process1", "src");
    String d2 = deploy("process2", "src");
    String d3 = deploy("process3", "src");

    Batch batch = repositoryService.deleteDeploymentsAsync(Arrays.asList(d1, d2, d3), null, false, false, false);

    // estimate at creation time: ceil(3 / 2) = 2
    assertThat(batch.getTotalJobs()).isEqualTo(2);

    executeSeedJobs(batch);

    // the per-deployment id mappings put every deployment into its own execution job,
    // so invocationsPerBatchJob cannot group deployments here
    List<Job> executionJobs = managementService.createJobQuery()
        .jobDefinitionId(batch.getBatchJobDefinitionId()).list();
    assertThat(executionJobs).hasSize(3);
  }

  @Test
  public void shouldSetDeploymentIdOnExecutionJobs() {
    String d1 = deploy("process1", "src");
    String d2 = deploy("process2", "src");

    Batch batch = repositoryService.deleteDeploymentsAsync(Arrays.asList(d1, d2), null, false, false, false);
    executeSeedJobs(batch);

    // a deployment-aware job executor must only acquire these jobs on nodes
    // that have the respective deployment registered
    List<Job> executionJobs = managementService.createJobQuery()
        .jobDefinitionId(batch.getBatchJobDefinitionId()).list();
    assertThat(executionJobs)
        .extracting(Job::getDeploymentId)
        .containsExactlyInAnyOrder(d1, d2);
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
