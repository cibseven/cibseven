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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.interceptor.Command;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.JobEntity;
import org.cibseven.bpm.engine.impl.util.ClockUtil;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.util.ProcessEngineBootstrapRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 *
 * @author Anna Pazola
 *
 */
@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
public class HistoryCleanupBatchWindowForEveryDayTest {

  protected String defaultStartTime;
  protected String defaultEndTime;
  protected int defaultBatchSize;

  @RegisterExtension
  @Order(3) public static ProcessEngineBootstrapRule bootstrapRule = new ProcessEngineBootstrapRule(configuration -> {
    configuration.setHistoryCleanupBatchSize(20);
    configuration.setHistoryCleanupBatchThreshold(10);
    configuration.setDefaultNumberOfRetries(5);
  });

  @RegisterExtension
  protected ProvidedProcessEngineRule engineRule = new ProvidedProcessEngineRule(bootstrapRule);
  @RegisterExtension
  @Order(9) protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);


  private HistoryService historyService;
  private ManagementService managementService;
  private ProcessEngineConfigurationImpl processEngineConfiguration;

  private static SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");


  public static Collection<Object[]> scenarios() throws ParseException {
    return Arrays.asList(new Object[][] {
        // inside the batch window on the same day
        { "22:00", "23:00", sdf.parse("2017-09-06T22:00:00"), sdf.parse("2017-09-06T23:00:00"), sdf.parse("2017-09-06T22:15:00")},
        // inside the batch window on the next day
        { "23:00", "01:00", sdf.parse("2017-09-06T23:00:00"), sdf.parse("2017-09-07T01:00:00"), sdf.parse("2017-09-07T00:15:00")},
        // batch window 24h
        { "00:00", "00:00", sdf.parse("2017-09-06T00:00:00"), sdf.parse("2017-09-07T00:00:00"), sdf.parse("2017-09-06T15:00:00")},
        // batch window 24h
        { "00:00", "00:00", sdf.parse("2017-09-06T00:00:00"), sdf.parse("2017-09-07T00:00:00"), sdf.parse("2017-09-06T00:00:00")},
        // before the batch window on the same day
        { "22:00", "23:00", sdf.parse("2017-09-06T22:00:00"), sdf.parse("2017-09-06T23:00:00"), sdf.parse("2017-09-06T21:15:00")},
        // after the batch window on the same day
        { "22:00", "23:00", sdf.parse("2017-09-07T22:00:00"), sdf.parse("2017-09-07T23:00:00"), sdf.parse("2017-09-06T23:15:00")},
        // after the batch window on the next day
        { "22:00", "23:00", sdf.parse("2017-09-07T22:00:00"), sdf.parse("2017-09-07T23:00:00"), sdf.parse("2017-09-07T00:15:00")} });
  }

  @BeforeEach
  public void init() {
    historyService = engineRule.getHistoryService();
    processEngineConfiguration = engineRule.getProcessEngineConfiguration();
    managementService = engineRule.getManagementService();

    defaultStartTime = processEngineConfiguration.getHistoryCleanupBatchWindowStartTime();
    defaultEndTime = processEngineConfiguration.getHistoryCleanupBatchWindowEndTime();
    defaultBatchSize = processEngineConfiguration.getHistoryCleanupBatchSize();
  }

  @AfterEach
  public void clearDatabase() {
    //reset configuration changes
    processEngineConfiguration.setHistoryCleanupBatchWindowStartTime(defaultStartTime);
    processEngineConfiguration.setHistoryCleanupBatchWindowEndTime(defaultEndTime);
    processEngineConfiguration.setHistoryCleanupBatchSize(defaultBatchSize);

    processEngineConfiguration.getCommandExecutorTxRequired().execute(new Command<Void>() {
      public Void execute(CommandContext commandContext) {

        List<Job> jobs = managementService.createJobQuery().list();
        if (jobs.size() > 0) {
          assertEquals(1, jobs.size());
          String jobId = jobs.get(0).getId();
          commandContext.getJobManager().deleteJob((JobEntity) jobs.get(0));
          commandContext.getHistoricJobLogManager().deleteHistoricJobLogByJobId(jobId);
        }

        return null;
      }
    });
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testScheduleJobForBatchWindow(String startTime, String endTime, Date startDateForCheck, Date endDateForCheck, Date currentDate) throws ParseException {
    ClockUtil.setCurrentTime(currentDate);

    processEngineConfiguration.setHistoryCleanupBatchWindowStartTime(startTime);
    processEngineConfiguration.setHistoryCleanupBatchWindowEndTime(endTime);

    processEngineConfiguration.initHistoryCleanup();

    Job job = historyService.cleanUpHistoryAsync();

    assertFalse(startDateForCheck.after(job.getDuedate())); // job due date is not before start date
    assertTrue(endDateForCheck.after(job.getDuedate()));
  }
}
