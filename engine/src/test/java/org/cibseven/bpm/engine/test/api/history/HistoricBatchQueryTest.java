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
package org.cibseven.bpm.engine.test.api.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cibseven.bpm.engine.test.api.runtime.TestOrderingUtil.historicBatchByEndTime;
import static org.cibseven.bpm.engine.test.api.runtime.TestOrderingUtil.historicBatchById;
import static org.cibseven.bpm.engine.test.api.runtime.TestOrderingUtil.historicBatchByStartTime;
import static org.cibseven.bpm.engine.test.api.runtime.TestOrderingUtil.inverted;
import static org.cibseven.bpm.engine.test.api.runtime.TestOrderingUtil.verifySorting;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.batch.Batch;
import org.cibseven.bpm.engine.batch.history.HistoricBatch;
import org.cibseven.bpm.engine.exception.NotValidException;
import org.cibseven.bpm.engine.exception.NullValueException;
import org.cibseven.bpm.engine.impl.util.ClockUtil;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.api.runtime.migration.MigrationTestRule;
import org.cibseven.bpm.engine.test.api.runtime.migration.batch.BatchMigrationHelper;
import org.cibseven.bpm.engine.test.util.ClockTestUtil;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;


@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
public class HistoricBatchQueryTest {

  protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  protected MigrationTestRule migrationRule = new MigrationTestRule(engineRule);
  protected BatchMigrationHelper helper = new BatchMigrationHelper(engineRule, migrationRule);

//  @Rule
//  public RuleChain ruleChain = RuleChain.outerRule(engineRule).around(migrationRule);

  protected RuntimeService runtimeService;
  protected ManagementService managementService;
  protected HistoryService historyService;

  @BeforeEach
  public void initServices() {
    runtimeService = engineRule.getRuntimeService();
    managementService = engineRule.getManagementService();
    historyService = engineRule.getHistoryService();
  }

  @AfterEach
  public void removeBatches() {
    helper.removeAllRunningAndHistoricBatches();
  }

  @AfterEach
  public void resetClock() {
    ClockUtil.reset();
  }

  @Test
  public void testBatchQuery() {
    // given
    Batch batch1 = helper.migrateProcessInstancesAsync(1);
    Batch batch2 = helper.migrateProcessInstancesAsync(1);

    // when
    List<HistoricBatch> list = historyService.createHistoricBatchQuery().list();

    // then
    assertEquals(2, list.size());

    List<String> batchIds = new ArrayList<String>();
    for (HistoricBatch resultBatch : list) {
      batchIds.add(resultBatch.getId());
    }

    Assertions.assertTrue(batchIds.contains(batch1.getId()));
    Assertions.assertTrue(batchIds.contains(batch2.getId()));
  }

  @Test
  public void testBatchQueryResult() {
    Date startDate = new Date(10000L);
    Date endDate = new Date(40000L);

    // given
    ClockUtil.setCurrentTime(startDate);
    Batch batch = helper.migrateProcessInstancesAsync(1);
    helper.completeSeedJobs(batch);
    helper.executeJobs(batch);

    ClockUtil.setCurrentTime(endDate);
    helper.executeMonitorJob(batch);

    // when
    HistoricBatch resultBatch = historyService.createHistoricBatchQuery().singleResult();

    // then
    Assertions.assertNotNull(resultBatch);

    assertEquals(batch.getId(), resultBatch.getId());
    assertEquals(batch.getBatchJobDefinitionId(), resultBatch.getBatchJobDefinitionId());
    assertEquals(batch.getMonitorJobDefinitionId(), resultBatch.getMonitorJobDefinitionId());
    assertEquals(batch.getSeedJobDefinitionId(), resultBatch.getSeedJobDefinitionId());
    assertEquals(batch.getTenantId(), resultBatch.getTenantId());
    assertEquals(batch.getType(), resultBatch.getType());
    assertEquals(batch.getBatchJobsPerSeed(), resultBatch.getBatchJobsPerSeed());
    assertEquals(batch.getInvocationsPerBatchJob(), resultBatch.getInvocationsPerBatchJob());
    assertEquals(batch.getTotalJobs(), resultBatch.getTotalJobs());
    assertEquals(startDate, resultBatch.getStartTime());
    assertEquals(endDate, resultBatch.getEndTime());
  }

  @Test
  public void testBatchQueryById() {
    // given
    Batch batch1 = helper.migrateProcessInstancesAsync(1);
    helper.migrateProcessInstancesAsync(1);

    // when
    HistoricBatch resultBatch = historyService.createHistoricBatchQuery().batchId(batch1.getId()).singleResult();

    // then
    Assertions.assertNotNull(resultBatch);
    assertEquals(batch1.getId(), resultBatch.getId());
  }

  @Test
  public void testBatchQueryByIdNull() {
    try {
      historyService.createHistoricBatchQuery().batchId(null).singleResult();
      Assertions.fail("exception expected");
    }
    catch (NullValueException e) {
      assertThat(e.getMessage()).contains("Batch id is null");
    }
  }

  @Test
  public void testBatchQueryByType() {
    // given
    Batch batch1 = helper.migrateProcessInstancesAsync(1);
    helper.migrateProcessInstancesAsync(1);

    // when
    long count = historyService.createHistoricBatchQuery().type(batch1.getType()).count();

    // then
    assertEquals(2, count);
  }

  @Test
  public void testBatchQueryByNonExistingType() {
    // given
    helper.migrateProcessInstancesAsync(1);

    // when
    long count = historyService.createHistoricBatchQuery().type("foo").count();

    // then
    assertEquals(0, count);
  }

  @Test
  public void testBatchByState() {
    // given
    Batch batch1 = helper.migrateProcessInstancesAsync(1);
    Batch batch2 = helper.migrateProcessInstancesAsync(1);

    helper.completeBatch(batch1);

    // when
    HistoricBatch historicBatch = historyService.createHistoricBatchQuery()
      .completed(true)
      .singleResult();

    // then
    assertEquals(batch1.getId(), historicBatch.getId());

    // when
    historicBatch = historyService.createHistoricBatchQuery()
      .completed(false)
      .singleResult();

    // then
    assertEquals(batch2.getId(), historicBatch.getId());
  }

  @Test
  public void testBatchQueryByTypeNull() {
    try {
      historyService.createHistoricBatchQuery().type(null).singleResult();
      Assertions.fail("exception expected");
    }
    catch (NullValueException e) {
      assertThat(e.getMessage()).contains("Type is null");
    }
  }

  @Test
  public void testBatchQueryCount() {
    // given
    helper.migrateProcessInstancesAsync(1);
    helper.migrateProcessInstancesAsync(1);

    // when
    long count = historyService.createHistoricBatchQuery().count();

    // then
    assertEquals(2, count);
  }

  @Test
  public void testBatchQueryOrderByIdAsc() {
    // given
    helper.migrateProcessInstancesAsync(1);
    helper.migrateProcessInstancesAsync(1);

    // when
    List<HistoricBatch> orderedBatches = historyService.createHistoricBatchQuery().orderById().asc().list();

    // then
    verifySorting(orderedBatches, historicBatchById());
  }

  @Test
  public void testBatchQueryOrderByIdDec() {
    // given
    helper.migrateProcessInstancesAsync(1);
    helper.migrateProcessInstancesAsync(1);

    // when
    List<HistoricBatch> orderedBatches = historyService.createHistoricBatchQuery().orderById().desc().list();

    // then
    verifySorting(orderedBatches, inverted(historicBatchById()));
  }

  @Test
  public void testBatchQueryOrderByStartTimeAsc() {
    // given
    ClockTestUtil.setClockToDateWithoutMilliseconds();
    helper.migrateProcessInstancesAsync(1);
    ClockTestUtil.incrementClock(1000);
    helper.migrateProcessInstancesAsync(1);

    // when
    List<HistoricBatch> orderedBatches = historyService.createHistoricBatchQuery().orderByStartTime().asc().list();

    // then
    verifySorting(orderedBatches, historicBatchByStartTime());
  }

  @Test
  public void testBatchQueryOrderByStartTimeDec() {
    // given
    ClockTestUtil.setClockToDateWithoutMilliseconds();
    helper.migrateProcessInstancesAsync(1);
    ClockTestUtil.incrementClock(1000);
    helper.migrateProcessInstancesAsync(1);

    // when
    List<HistoricBatch> orderedBatches = historyService.createHistoricBatchQuery().orderByStartTime().desc().list();

    // then
    verifySorting(orderedBatches, inverted(historicBatchByStartTime()));
  }

  @Test
  public void testBatchQueryOrderByEndTimeAsc() {
    // given
    ClockTestUtil.setClockToDateWithoutMilliseconds();
    Batch batch1 = helper.migrateProcessInstancesAsync(1);
    helper.completeBatch(batch1);

    ClockTestUtil.incrementClock(1000);
    Batch batch2 = helper.migrateProcessInstancesAsync(1);
    helper.completeBatch(batch2);

    // when
    List<HistoricBatch> orderedBatches = historyService.createHistoricBatchQuery().orderByEndTime().asc().list();

    // then
    verifySorting(orderedBatches, historicBatchByEndTime());
  }

  @Test
  public void testBatchQueryOrderByEndTimeDec() {
    // given
    ClockTestUtil.setClockToDateWithoutMilliseconds();
    Batch batch1 = helper.migrateProcessInstancesAsync(1);
    helper.completeBatch(batch1);

    ClockTestUtil.incrementClock(1000);
    Batch batch2 = helper.migrateProcessInstancesAsync(1);
    helper.completeBatch(batch2);

    // when
    List<HistoricBatch> orderedBatches = historyService.createHistoricBatchQuery().orderByEndTime().desc().list();

    // then
    verifySorting(orderedBatches, inverted(historicBatchByEndTime()));
  }

  @Test
  public void testBatchQueryOrderingPropertyWithoutOrder() {
    try {
      historyService.createHistoricBatchQuery().orderById().singleResult();
      Assertions.fail("exception expected");
    }
    catch (NotValidException e) {
      assertThat(e.getMessage()).contains("Invalid query: "
          + "call asc() or desc() after using orderByXX()");
    }
  }

  @Test
  public void testBatchQueryOrderWithoutOrderingProperty() {
    try {
      historyService.createHistoricBatchQuery().asc().singleResult();
      Assertions.fail("exception expected");
    }
    catch (NotValidException e) {
      assertThat(e.getMessage()).contains("You should call any of the orderBy methods "
          + "first before specifying a direction");
    }
  }
}
