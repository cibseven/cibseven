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
package org.cibseven.bpm.engine.test.api.authorization.batch;

import static org.cibseven.bpm.engine.authorization.Authorization.ANY;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.cibseven.bpm.engine.authorization.Permissions;
import org.cibseven.bpm.engine.authorization.Resources;
import org.cibseven.bpm.engine.batch.Batch;
import org.cibseven.bpm.engine.batch.BatchStatistics;
import org.cibseven.bpm.engine.migration.MigrationPlan;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationTestBaseRule;
import org.cibseven.bpm.engine.test.api.runtime.migration.models.ProcessModels;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * @author Thorben Lindhauer
 *
 */
public class BatchStatisticsQueryAuthorizationTest {

  @RegisterExtension
  @Order(4) public ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  @Order(7) public AuthorizationTestBaseRule authRule = new AuthorizationTestBaseRule(engineRule);
  @RegisterExtension
  @Order(9) public ProcessEngineTestRule testHelper = new ProcessEngineTestRule(engineRule);

  protected MigrationPlan migrationPlan;
  protected Batch batch1;
  protected Batch batch2;
  protected Batch batch3;

  @BeforeEach
  public void setUp() {
    authRule.createUserAndGroup("user", "group");
  }

  @BeforeEach
  public void deployProcessesAndCreateMigrationPlan() {
    ProcessInstance pi = createMigrationPlan();

    batch1 = engineRule.getRuntimeService()
      .newMigration(migrationPlan)
      .processInstanceIds(Arrays.asList(pi.getId()))
      .executeAsync();

    Job seedJob = engineRule.getManagementService().createJobQuery().singleResult();
    engineRule.getManagementService().executeJob(seedJob.getId());

    batch2 = engineRule.getRuntimeService()
        .newMigration(migrationPlan)
        .processInstanceIds(Arrays.asList(pi.getId()))
        .executeAsync();
  }

  @AfterEach
  public void tearDown() {
    authRule.deleteUsersAndGroups();
  }

  @AfterEach
  public void deleteBatches() {
    engineRule.getManagementService().deleteBatch(batch1.getId(), true);
    engineRule.getManagementService().deleteBatch(batch2.getId(), true);
    if (batch3 != null) {
      engineRule.getManagementService().deleteBatch(batch3.getId(), true);
    }
  }

  @Test
  public void testQueryList() {
    // given
    authRule.createGrantAuthorization(Resources.BATCH, batch1.getId(), "user", Permissions.READ);

    // when
    authRule.enableAuthorization("user");
    List<BatchStatistics> batches = engineRule.getManagementService().createBatchStatisticsQuery().list();
    authRule.disableAuthorization();

    // then
    assertEquals(1, batches.size());
    assertEquals(batch1.getId(), batches.get(0).getId());

    // and the visibility of jobs is not restricted
    assertEquals(1, batches.get(0).getJobsCreated());
    assertEquals(1, batches.get(0).getRemainingJobs());
    assertEquals(1, batches.get(0).getTotalJobs());
  }

  @Test
  public void testQueryCount() {
    // given
    authRule.createGrantAuthorization(Resources.BATCH, batch1.getId(), "user", Permissions.READ);

    // when
    authRule.enableAuthorization("user");
    long count = engineRule.getManagementService().createBatchStatisticsQuery().count();
    authRule.disableAuthorization();

    // then
    assertEquals(1, count);
  }

  @Test
  public void testQueryNoAuthorizations() {
    // when
    authRule.enableAuthorization("user");
    long count = engineRule.getManagementService().createBatchStatisticsQuery().count();
    authRule.disableAuthorization();

    // then
    assertEquals(0, count);
  }

  @Test
  public void testQueryListAccessAll() {
    // given
    authRule.createGrantAuthorization(Resources.BATCH, ANY, "user", Permissions.READ);

    // when
    authRule.enableAuthorization("user");
    List<BatchStatistics> batches = engineRule.getManagementService().createBatchStatisticsQuery().list();
    authRule.disableAuthorization();

    // then
    assertEquals(2, batches.size());
  }

  @Test
  public void testQueryListMultiple() {
    // given
    authRule.createGrantAuthorization(Resources.BATCH, ANY, "user", Permissions.READ);
    authRule.createGrantAuthorization(Resources.BATCH, batch1.getId(), "user", Permissions.READ);

    // when
    authRule.enableAuthorization("user");
    List<BatchStatistics> batches = engineRule.getManagementService().createBatchStatisticsQuery().list();
    authRule.disableAuthorization();

    // then
    assertEquals(2, batches.size());
  }

  @Test
  public void testBatchStatisticsAndCreateUserId() {
    // given
    ProcessInstance pi = createMigrationPlan();

    // when
    authRule.createGrantAuthorization(Resources.BATCH, ANY, "userId", Permissions.CREATE);
    authRule.createGrantAuthorization(Resources.PROCESS_DEFINITION, ANY, "userId", Permissions.MIGRATE_INSTANCE);

    authRule.enableAuthorization("userId");
    batch3 = engineRule.getRuntimeService()
      .newMigration(migrationPlan)
      .processInstanceIds(Arrays.asList(pi.getId()))
      .executeAsync();
    authRule.disableAuthorization();

    // then
    BatchStatistics batchStatistics = engineRule.getManagementService().createBatchStatisticsQuery().batchId(batch3.getId()).singleResult();
    assertEquals("userId", batchStatistics.getCreateUserId());
  }

  @Test
  public void shouldNotFindStatisticsWithRevokedReadPermissionOnBatch() {
    // given
    authRule.createGrantAuthorization(Resources.BATCH, ANY, ANY, Permissions.READ);
    authRule.createRevokeAuthorization(Resources.BATCH, ANY, "user", Permissions.READ);

    // when
    authRule.enableAuthorization("user");
    List<BatchStatistics> batches = engineRule.getManagementService().createBatchStatisticsQuery().list();
    authRule.disableAuthorization();

    // then
    assertEquals(0, batches.size());
  }

  protected ProcessInstance createMigrationPlan() {
    ProcessDefinition sourceDefinition = testHelper.deployAndGetDefinition(ProcessModels.ONE_TASK_PROCESS);
    ProcessDefinition targetDefinition = testHelper.deployAndGetDefinition(ProcessModels.ONE_TASK_PROCESS);

    migrationPlan = engineRule.getRuntimeService().createMigrationPlan(sourceDefinition.getId(), targetDefinition.getId())
        .mapEqualActivities()
        .build();

    ProcessInstance pi = engineRule.getRuntimeService().startProcessInstanceById(sourceDefinition.getId());
    return pi;
  }
}