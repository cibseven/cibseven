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
package org.cibseven.bpm.engine.test.history;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections.map.HashedMap;
import org.apache.commons.lang3.time.DateUtils;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.batch.Batch;
import org.cibseven.bpm.engine.batch.history.HistoricBatch;
import org.cibseven.bpm.engine.impl.interceptor.Command;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.util.ClockUtil;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.api.runtime.migration.MigrationTestRule;
import org.cibseven.bpm.engine.test.api.runtime.migration.batch.BatchMigrationHelper;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
public class HistoricBatchManagerBatchesForCleanupTest {

  @RegisterExtension
  public ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  public MigrationTestRule migrationRule = new MigrationTestRule(engineRule);
  public BatchMigrationHelper helper = new BatchMigrationHelper(engineRule, migrationRule);


  protected HistoryService historyService;

  @BeforeEach
  public void init() {
    historyService = engineRule.getHistoryService();
  }

  @AfterEach
  public void clearDatabase() {
    helper.removeAllRunningAndHistoricBatches();
  }


  public static Collection<Object[]> scenarios() {
    return Arrays.asList(new Object[][] {
        // all historic batches are old enough to be cleaned up
        { 5, -11, -6, -7, 50, 2 },
        // one batch should be cleaned up
        { 5, -11, -3, -7, 50, 1 },
        // not enough time has passed
        { 5, -11, -3, -4, 50, 0 },
        // batchSize will reduce the result
        { 5, -11, -6, -7, 1, 1 } });
  }

  @SuppressWarnings("unchecked")
  @ParameterizedTest
  @MethodSource("scenarios")
  public void testFindHistoricBatchIdsForCleanup(int historicBatchHistoryTTL, int daysInThePast, int batch1EndTime,
    int batch2EndTime, int batchSize, int resultCount) {
    // given
    String batchType = prepareHistoricBatches(2, daysInThePast, batch1EndTime, batch2EndTime);
    final Map<String, Integer> batchOperationsMap = new HashedMap();
    batchOperationsMap.put(batchType, historicBatchHistoryTTL);


    engineRule.getProcessEngineConfiguration().getCommandExecutorTxRequired().execute(new Command<Object>() {
      @Override
      public Object execute(CommandContext commandContext) {
        // when
        List<String> historicBatchIdsForCleanup = commandContext.getHistoricBatchManager().findHistoricBatchIdsForCleanup(batchSize, batchOperationsMap, 0, 59);

        // then
        assertEquals(resultCount, historicBatchIdsForCleanup.size());

        if (resultCount > 0) {

          List<HistoricBatch> historicBatches = historyService.createHistoricBatchQuery().list();

          for (HistoricBatch historicBatch : historicBatches) {
            historicBatch.getEndTime().before(DateUtils.addDays(ClockUtil.getCurrentTime(), historicBatchHistoryTTL));
          }
        }

        return null;
      }
    });
  }

  private String prepareHistoricBatches(int batchesCount, int daysInThePast, int batch1EndTime, int batch2EndTime) {
    Date startDate = ClockUtil.getCurrentTime();
    ClockUtil.setCurrentTime(DateUtils.addDays(startDate, daysInThePast));

    List<Batch> list = new ArrayList<Batch>();
    for (int i = 0; i < batchesCount; i++) {
      list.add(helper.migrateProcessInstancesAsync(1));
    }

    Batch batch1 = list.get(0);
    String batchType = batch1.getType();
    helper.completeSeedJobs(batch1);
    helper.executeJobs(batch1);
    ClockUtil.setCurrentTime(DateUtils.addDays(startDate, batch1EndTime));
    helper.executeMonitorJob(batch1);

    Batch batch2 = list.get(1);
    helper.completeSeedJobs(batch2);
    helper.executeJobs(batch2);
    ClockUtil.setCurrentTime(DateUtils.addDays(startDate, batch2EndTime));
    helper.executeMonitorJob(batch2);

    ClockUtil.setCurrentTime(new Date());

    return batchType;
  }
}