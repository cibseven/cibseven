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
package org.cibseven.bpm.engine.test.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;

import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.exception.NotFoundException;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.EventSubscriptionEntity;
import org.cibseven.bpm.engine.repository.Deployment;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.runtime.EventSubscription;
import org.cibseven.bpm.engine.runtime.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Concurrent deletion of process definitions and deployments is serialized by the
 * deployment lock (CIB7-2714): the second transaction waits for the first one to
 * commit and then works on the committed state. The deletion lock is also acquired
 * on H2 (see {@code DbSqlSession#lockIncludingH2}).
 */
public class DeleteProcessDefinitionTest extends ConcurrencyTestCase {

  protected static final String MESSAGE_START_RESOURCE = "org/cibseven/bpm/engine/test/api/repository/processWithNewInvoiceMessage.bpmn20.xml";
  protected static final String TIMER_START_RESOURCE = "org/cibseven/bpm/engine/test/bpmn/event/timer/StartTimerEventTest.testTimeCycle.bpmn20.xml";
  protected static final String SIGNAL_START_RESOURCE = "org/cibseven/bpm/engine/test/api/repository/processWithStartSignalEvent.bpmn20.xml";
  protected static final String TENANT_ONE = "tenant1";
  protected static final String CONDITIONAL_START_RESOURCE = "org/cibseven/bpm/engine/test/api/repository/processWithConditionalStartEvent.bpmn20.xml";

  protected ThreadControl thread1;
  protected ThreadControl thread2;
  protected ControllableDeleteCommand command1;
  protected ControllableDeleteCommand command2;
  protected boolean deploymentLockUsed;

  @BeforeEach
  public void rememberDeploymentLockUsed() {
    deploymentLockUsed = processEngineConfiguration.isDeploymentLockUsed();
  }

  @AfterEach
  public void tearDown() {
    processEngineConfiguration.setDeploymentLockUsed(deploymentLockUsed);
    repositoryService.createDeploymentQuery().list().forEach(deployment -> repositoryService.deleteDeployment(deployment.getId(), true));
    processEngineConfiguration.getDeploymentCache().purgeCache();
  }

  @Test
  public void testDeleteAllVersionsOfMessageStartProcess() throws InterruptedException {
    // given
    List<ProcessDefinition> processDefinitions = deployVersions(MESSAGE_START_RESOURCE, 2);

    // when
    deleteProcessDefinitionsSerialized(processDefinitions.get(0).getId(), processDefinitions.get(1).getId());

    // then no orphan subscription is left
    assertNoException();
    assertThat(repositoryService.createProcessDefinitionQuery().count()).isZero();
    assertThat(runtimeService.createEventSubscriptionQuery().count()).isZero();

    // and a new deployment gets exactly one subscription
    repositoryService.createDeployment().addClasspathResource(MESSAGE_START_RESOURCE).deploy();
    assertThat(repositoryService.createProcessDefinitionQuery().count()).isEqualTo(1);
    assertThat(runtimeService.createEventSubscriptionQuery().count()).isEqualTo(1);
  }

  @Test
  public void testDeleteAllVersionsOfTimerStartProcess() throws InterruptedException {
    // given
    List<ProcessDefinition> processDefinitions = deployVersions(TIMER_START_RESOURCE, 2);

    // when
    deleteProcessDefinitionsSerialized(processDefinitions.get(0).getId(), processDefinitions.get(1).getId());

    // then no orphan timer job is left
    assertNoException();
    assertThat(repositoryService.createProcessDefinitionQuery().count()).isZero();
    assertThat(managementService.createJobQuery().count()).isZero();

    // and a new deployment gets exactly one timer job
    repositoryService.createDeployment().addClasspathResource(TIMER_START_RESOURCE).deploy();
    assertThat(repositoryService.createProcessDefinitionQuery().count()).isEqualTo(1);
    assertThat(managementService.createJobQuery().count()).isEqualTo(1);
  }

  @Test
  public void testDeleteAllVersionsOfSignalStartProcess() throws InterruptedException {
    // given
    List<ProcessDefinition> processDefinitions = deployVersions(SIGNAL_START_RESOURCE, 2);

    // when
    deleteProcessDefinitionsSerialized(processDefinitions.get(0).getId(), processDefinitions.get(1).getId());

    // then
    assertNoException();
    assertThat(repositoryService.createProcessDefinitionQuery().count()).isZero();
    assertThat(runtimeService.createEventSubscriptionQuery().count()).isZero();
  }

  @Test
  public void testDeleteAllVersionsOfConditionalStartProcess() throws InterruptedException {
    // given
    List<ProcessDefinition> processDefinitions = deployVersions(CONDITIONAL_START_RESOURCE, 2);

    // when
    deleteProcessDefinitionsSerialized(processDefinitions.get(0).getId(), processDefinitions.get(1).getId());

    // then
    assertNoException();
    assertThat(repositoryService.createProcessDefinitionQuery().count()).isZero();
    assertThat(runtimeService.createEventSubscriptionQuery().count()).isZero();
  }

  @Test
  public void testDeleteTwoLatestVersionsOfMessageStartProcess() throws InterruptedException {
    // given
    List<ProcessDefinition> processDefinitions = deployVersions(MESSAGE_START_RESOURCE, 3);
    String firstVersionId = processDefinitions.get(0).getId();

    // when
    deleteProcessDefinitionsSerialized(processDefinitions.get(1).getId(), processDefinitions.get(2).getId());

    // then version 1 is left with exactly one subscription pointing to version 1
    assertNoException();
    assertThat(repositoryService.createProcessDefinitionQuery().singleResult().getId()).isEqualTo(firstVersionId);
    assertSingleSubscriptionOn(firstVersionId);

    // and a new deployment moves the subscription to the new version
    repositoryService.createDeployment().addClasspathResource(MESSAGE_START_RESOURCE).deploy();
    assertThat(repositoryService.createProcessDefinitionQuery().count()).isEqualTo(2);
    assertThat(runtimeService.createEventSubscriptionQuery().count()).isEqualTo(1);
  }

  @Test
  public void testDeleteTwoLatestVersionsOfTimerStartProcess() throws InterruptedException {
    // given
    List<ProcessDefinition> processDefinitions = deployVersions(TIMER_START_RESOURCE, 3);
    String firstVersionId = processDefinitions.get(0).getId();

    // when
    deleteProcessDefinitionsSerialized(processDefinitions.get(1).getId(), processDefinitions.get(2).getId());

    // then version 1 is left with exactly one timer job of version 1
    assertNoException();
    assertThat(repositoryService.createProcessDefinitionQuery().singleResult().getId()).isEqualTo(firstVersionId);
    Job job = managementService.createJobQuery().singleResult();
    assertThat(job).isNotNull();
    assertThat(job.getProcessDefinitionId()).isEqualTo(firstVersionId);
  }

  @Test
  public void testDeleteSameProcessDefinitionTwice() throws InterruptedException {
    // given
    List<ProcessDefinition> processDefinitions = deployVersions(MESSAGE_START_RESOURCE, 2);
    String firstVersionId = processDefinitions.get(0).getId();
    String secondVersionId = processDefinitions.get(1).getId();

    // when
    deleteProcessDefinitionsSerialized(secondVersionId, secondVersionId);

    // then the first transaction wins and the second one does not find the definition anymore
    assertThat(thread1.getException()).isNull();
    assertThat(thread2.getException()).isNull();
    assertThat(command1.failure).isNull();
    assertThat(command2.failure).isInstanceOf(NotFoundException.class);
    assertThat(repositoryService.createProcessDefinitionQuery().singleResult().getId()).isEqualTo(firstVersionId);
    assertSingleSubscriptionOn(firstVersionId);
  }

  @Test
  public void testDeleteLatestVersionByIdAndAllVersionsByKey() throws InterruptedException {
    // given
    List<ProcessDefinition> processDefinitions = deployVersions(MESSAGE_START_RESOURCE, 3);

    // when thread 1 deletes the latest version by id and thread 2 deletes all versions by key
    runSerialized(
        new ControllableDeleteProcessDefinitionCommand(processDefinitions.get(2).getId()),
        new ControllableDeleteProcessDefinitionsByKeyCommand(processDefinitions.get(0).getKey()));

    // then thread 2 only sees the remaining versions and no subscription is left
    assertNoException();
    assertThat(repositoryService.createProcessDefinitionQuery().count()).isZero();
    assertThat(runtimeService.createEventSubscriptionQuery().count()).isZero();
  }

  @Test
  public void testDeleteSameTenantDeploymentTwice() throws InterruptedException {
    // given a deployment of a tenant, deleted twice with an active tenant check
    String deploymentId = repositoryService.createDeployment()
        .tenantId(TENANT_ONE)
        .addClasspathResource(MESSAGE_START_RESOURCE)
        .deploy()
        .getId();
    assertThat(runtimeService.createEventSubscriptionQuery().count()).isEqualTo(1);

    // when (runSerialized asserts that thread 2 waits for the lock)
    runSerialized(
        new ControllableDeleteTenantDeploymentCommand(deploymentId, TENANT_ONE),
        new ControllableDeleteTenantDeploymentCommand(deploymentId, TENANT_ONE));

    // then thread 1 deletes the deployment; thread 2 finds nothing left to delete,
    // which DeleteDeploymentCmd treats as a no-op (no exception)
    assertNoException();
    assertThat(repositoryService.createDeploymentQuery().count()).isZero();
    assertThat(repositoryService.createProcessDefinitionQuery().count()).isZero();
    assertThat(runtimeService.createEventSubscriptionQuery().count()).isZero();
  }

  @Test
  public void testDeleteDeploymentsCascadeWithRunningInstances() throws InterruptedException {
    // given three versions in their own deployments, each with a running instance waiting in a user task
    List<ProcessDefinition> processDefinitions = deployVersions(MESSAGE_START_RESOURCE, 3);
    for (ProcessDefinition processDefinition : processDefinitions) {
      runtimeService.startProcessInstanceById(processDefinition.getId());
    }
    assertThat(taskService.createTaskQuery().count()).isEqualTo(3);
    String firstVersionId = processDefinitions.get(0).getId();

    // when
    deleteDeploymentsSerialized(processDefinitions.get(1).getDeploymentId(), processDefinitions.get(2).getDeploymentId());

    // then neither transaction fails and version 1 is left with its instance and one subscription
    assertNoException();
    assertThat(repositoryService.createDeploymentQuery().list())
        .extracting(Deployment::getId)
        .containsExactly(processDefinitions.get(0).getDeploymentId());
    assertThat(repositoryService.createProcessDefinitionQuery().singleResult().getId()).isEqualTo(firstVersionId);
    assertThat(runtimeService.createProcessInstanceQuery().singleResult().getProcessDefinitionId()).isEqualTo(firstVersionId);
    assertThat(taskService.createTaskQuery().singleResult().getProcessDefinitionId()).isEqualTo(firstVersionId);
    assertSingleSubscriptionOn(firstVersionId);
  }

  // Orphan cleanup in BpmnDeployer: without the deployment lock, interleaved deletions
  // still leave orphans (unchanged behavior), which the next deployment cleans up.

  @Test
  public void testDeploymentOfProcessDefinitionWithOrphanMessageEvent() throws InterruptedException {
    // given
    List<ProcessDefinition> processDefinitions = deployVersions(MESSAGE_START_RESOURCE, 2);
    deleteProcessDefinitionsInterleavedWithoutLock(processDefinitions.get(0).getId(), processDefinitions.get(1).getId());

    assertNoException();
    assertThat(repositoryService.createProcessDefinitionQuery().list()).isEmpty();
    assertThat(runtimeService.createEventSubscriptionQuery().count()).isEqualTo(1);

    // when
    repositoryService.createDeployment().addClasspathResource(MESSAGE_START_RESOURCE).deploy();

    // then
    ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery().singleResult();
    assertSingleSubscriptionOn(processDefinition.getId());
  }

  @Test
  public void testDeploymentOfProcessDefinitionWithOrphanJob() throws InterruptedException {
    // given
    List<ProcessDefinition> processDefinitions = deployVersions(TIMER_START_RESOURCE, 2);
    deleteProcessDefinitionsInterleavedWithoutLock(processDefinitions.get(0).getId(), processDefinitions.get(1).getId());

    assertNoException();
    assertThat(repositoryService.createProcessDefinitionQuery().list()).isEmpty();
    assertThat(managementService.createJobQuery().count()).isEqualTo(1);

    // when
    repositoryService.createDeployment().addClasspathResource(TIMER_START_RESOURCE).deploy();

    // then
    assertThat(repositoryService.createProcessDefinitionQuery().count()).isEqualTo(1);
    assertThat(managementService.createJobQuery().count()).isEqualTo(1);
  }

  @Test
  public void testDeploymentOfProcessDefinitionWithOrphanSignalEvent() throws InterruptedException {
    // given
    List<ProcessDefinition> processDefinitions = deployVersions(SIGNAL_START_RESOURCE, 2);
    deleteProcessDefinitionsInterleavedWithoutLock(processDefinitions.get(0).getId(), processDefinitions.get(1).getId());

    assertNoException();
    assertThat(repositoryService.createProcessDefinitionQuery().list()).isEmpty();
    assertThat(runtimeService.createEventSubscriptionQuery().count()).isEqualTo(1);

    // when
    repositoryService.createDeployment().addClasspathResource(SIGNAL_START_RESOURCE).deploy();

    // then
    assertThat(repositoryService.createProcessDefinitionQuery().count()).isEqualTo(1);
    assertThat(runtimeService.createEventSubscriptionQuery().count()).isEqualTo(1);
  }

  @Test
  public void testDeploymentOfProcessDefinitionWithOrphanEventAndPreviousVersion() throws InterruptedException {
    // given
    List<ProcessDefinition> processDefinitions = deployVersions(MESSAGE_START_RESOURCE, 3);
    deleteProcessDefinitionsInterleavedWithoutLock(processDefinitions.get(1).getId(), processDefinitions.get(2).getId());

    assertNoException();
    assertThat(repositoryService.createProcessDefinitionQuery().count()).isEqualTo(1);
    assertThat(runtimeService.createEventSubscriptionQuery().count()).isEqualTo(1);

    // when
    repositoryService.createDeployment().addClasspathResource(MESSAGE_START_RESOURCE).deploy();

    // then
    assertThat(repositoryService.createProcessDefinitionQuery().count()).isEqualTo(2);
    assertThat(runtimeService.createEventSubscriptionQuery().count()).isEqualTo(1);
  }

  @Test
  public void testDeploymentOfProcessDefinitionWithOrphanConditionalEvent() throws InterruptedException {
    // given
    List<ProcessDefinition> processDefinitions = deployVersions(CONDITIONAL_START_RESOURCE, 2);
    deleteProcessDefinitionsInterleavedWithoutLock(processDefinitions.get(0).getId(), processDefinitions.get(1).getId());

    assertNoException();
    assertThat(repositoryService.createProcessDefinitionQuery().list()).isEmpty();
    assertThat(runtimeService.createEventSubscriptionQuery().count()).isEqualTo(1);

    // when
    repositoryService.createDeployment().addClasspathResource(CONDITIONAL_START_RESOURCE).deploy();

    // then
    ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery().singleResult();
    assertSingleSubscriptionOn(processDefinition.getId());
  }

  /**
   * Original interleaved choreography with the deployment lock disabled: both
   * deletions run before either commits, so neither waits for the other.
   */
  protected void deleteProcessDefinitionsInterleavedWithoutLock(String id1, String id2) throws InterruptedException {
    processEngineConfiguration.setDeploymentLockUsed(false);
    command1 = new ControllableDeleteProcessDefinitionCommand(id1);
    command2 = new ControllableDeleteProcessDefinitionCommand(id2);

    try {
      thread1 = executeControllableCommand(command1);
      thread1.reportInterrupts();
      thread1.waitForSync();

      thread2 = executeControllableCommand(command2);
      thread2.reportInterrupts();
      thread2.waitForSync();

      // delete the first definition without committing
      thread1.makeContinue();
      thread1.waitForSync();

      // delete the second definition without committing (not blocked, no lock);
      // bounded wait, so that a regression that always locks fails instead of hanging
      // on databases without a lock timeout (waitForSync resets syncAvailable, so poll it)
      thread2.makeContinue();
      long deadline = System.currentTimeMillis() + 10000;
      while (!thread2.syncAvailable && thread2.getException() == null && System.currentTimeMillis() < deadline) {
        Thread.sleep(50);
      }
      assertThat(thread2.syncAvailable)
          .as("without the deployment lock thread 2 must not block")
          .isTrue();
      thread2.waitForSync();

      // commit both deletions
      thread1.waitUntilDone();
      thread2.waitUntilDone();
    } finally {
      finishQuietly(thread1);
      finishQuietly(thread2);
    }
  }

  protected void assertNoException() {
    assertThat(thread1.getException()).isNull();
    assertThat(thread2.getException()).isNull();
    assertThat(command1.failure).isNull();
    assertThat(command2.failure).isNull();
  }

  protected void assertSingleSubscriptionOn(String processDefinitionId) {
    EventSubscription subscription = runtimeService.createEventSubscriptionQuery().singleResult();
    assertThat(subscription).isNotNull();
    assertThat(((EventSubscriptionEntity) subscription).getConfiguration()).isEqualTo(processDefinitionId);
  }

  /**
   * Catches the failure of the deletion instead of propagating it, so that the
   * thread always reaches its second sync point and the choreography stays the same.
   */
  protected abstract static class ControllableDeleteCommand extends ControllableCommand<Void> {

    protected volatile RuntimeException failure;

    @Override
    public Void execute(CommandContext commandContext) {
      monitor.sync(); // thread will block here until makeContinue() is called from main thread
      try {
        delete(commandContext);
      } catch (RuntimeException e) {
        failure = e;
      }
      monitor.sync(); // thread will block here until waitUntilDone() is called form main thread
      return null;
    }

    protected abstract void delete(CommandContext commandContext);
  }

  protected static class ControllableDeleteProcessDefinitionCommand extends ControllableDeleteCommand {

    protected String processDefinitionId;

    public ControllableDeleteProcessDefinitionCommand(String processDefinitionId) {
      this.processDefinitionId = processDefinitionId;
    }

    @Override
    protected void delete(CommandContext commandContext) {
      commandContext.getProcessEngineConfiguration().getRepositoryService().deleteProcessDefinition(processDefinitionId);
    }
  }

  protected static class ControllableDeleteDeploymentCommand extends ControllableDeleteCommand {

    protected String deploymentId;

    public ControllableDeleteDeploymentCommand(String deploymentId) {
      this.deploymentId = deploymentId;
    }

    @Override
    protected void delete(CommandContext commandContext) {
      commandContext.getProcessEngineConfiguration().getRepositoryService().deleteDeployment(deploymentId, true);
    }
  }

  /**
   * Deletes a deployment with an authenticated tenant, so that the tenant check of
   * {@code DeleteDeploymentCmd} is active. The authentication is thread-local and is
   * therefore set and cleared in the worker thread.
   */
  protected static class ControllableDeleteTenantDeploymentCommand extends ControllableDeleteDeploymentCommand {

    protected String tenantId;

    public ControllableDeleteTenantDeploymentCommand(String deploymentId, String tenantId) {
      super(deploymentId);
      this.tenantId = tenantId;
    }

    @Override
    protected void delete(CommandContext commandContext) {
      IdentityService identityService = commandContext.getProcessEngineConfiguration().getIdentityService();
      identityService.setAuthentication("user", null, Collections.singletonList(tenantId));
      try {
        super.delete(commandContext);
      } finally {
        identityService.clearAuthentication();
      }
    }
  }

  protected static class ControllableDeleteProcessDefinitionsByKeyCommand extends ControllableDeleteCommand {

    protected String processDefinitionKey;

    public ControllableDeleteProcessDefinitionsByKeyCommand(String processDefinitionKey) {
      this.processDefinitionKey = processDefinitionKey;
    }

    @Override
    protected void delete(CommandContext commandContext) {
      commandContext.getProcessEngineConfiguration().getRepositoryService()
          .deleteProcessDefinitions().byKey(processDefinitionKey).delete();
    }
  }

  protected void deleteProcessDefinitionsSerialized(String id1, String id2) throws InterruptedException {
    runSerialized(new ControllableDeleteProcessDefinitionCommand(id1), new ControllableDeleteProcessDefinitionCommand(id2));
  }

  protected void deleteDeploymentsSerialized(String id1, String id2) throws InterruptedException {
    runSerialized(new ControllableDeleteDeploymentCommand(id1), new ControllableDeleteDeploymentCommand(id2));
  }

  protected void runSerialized(ControllableDeleteCommand command1, ControllableDeleteCommand command2) throws InterruptedException {
    this.command1 = command1;
    this.command2 = command2;

    try {
      // STEP 1: bring both threads to a point where they have started a new transaction
      thread1 = executeControllableCommand(command1);
      thread1.reportInterrupts();
      thread1.waitForSync();

      thread2 = executeControllableCommand(command2);
      thread2.reportInterrupts();
      thread2.waitForSync();

      // STEP 2: thread 1 deletes without committing and keeps holding the deployment lock
      thread1.makeContinue();
      thread1.waitForSync();

      // STEP 3: thread 2 attempts to acquire the deployment lock and blocks
      thread2.makeContinue();
      Thread.sleep(2000);
      assertThat(thread2.syncAvailable)
          .as("thread 2 must wait for the deployment lock held by thread 1")
          .isFalse();

      // STEP 4: thread 1 commits and releases the lock
      thread1.waitUntilDone();

      // STEP 5: thread 2 deletes on the committed state and commits
      thread2.waitForSync();
      thread2.waitUntilDone();
    } finally {
      // on failure, let both threads run to the end so that no lock is left for tearDown
      finishQuietly(thread1);
      finishQuietly(thread2);
    }
  }

  protected List<ProcessDefinition> deployVersions(String resource, int count) {
    for (int i = 0; i < count; i++) {
      repositoryService.createDeployment().addClasspathResource(resource).deploy();
    }
    List<ProcessDefinition> processDefinitions = repositoryService.createProcessDefinitionQuery()
        .orderByProcessDefinitionVersion().asc()
        .list();
    assertThat(processDefinitions).hasSize(count);
    return processDefinitions;
  }
}
