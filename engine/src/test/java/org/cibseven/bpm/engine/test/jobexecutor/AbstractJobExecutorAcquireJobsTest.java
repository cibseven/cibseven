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
package org.cibseven.bpm.engine.test.jobexecutor;

import java.util.List;

import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.impl.Page;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.interceptor.Command;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.AcquirableJobEntity;
import org.cibseven.bpm.engine.impl.util.ClockUtil;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.util.ClockTestUtil;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

public abstract class AbstractJobExecutorAcquireJobsTest {

  public ProcessEngineRule rule = new ProvidedProcessEngineRule();

  protected ManagementService managementService;
  protected RuntimeService runtimeService;

  protected ProcessEngineConfigurationImpl configuration;

  private boolean jobExecutorAcquireByDueDate;
  private boolean jobExecutorAcquireByPriority;
  private boolean jobExecutorPreferTimerJobs;
  private boolean jobEnsureDueDateSet;
  private Long jobExecutorPriorityRangeMin;
  private Long jobExecutorPriorityRangeMax;

  @BeforeEach
  public void initServices() {
    runtimeService = rule.getRuntimeService();
    managementService = rule.getManagementService();
    configuration = (ProcessEngineConfigurationImpl) rule.getProcessEngine().getProcessEngineConfiguration();
    jobExecutorAcquireByDueDate = configuration.isJobExecutorAcquireByDueDate();
    jobExecutorAcquireByPriority = configuration.isJobExecutorAcquireByPriority();
    jobExecutorPreferTimerJobs = configuration.isJobExecutorPreferTimerJobs();
    jobEnsureDueDateSet = configuration.isEnsureJobDueDateNotNull();
    jobExecutorPriorityRangeMin = configuration.getJobExecutorPriorityRangeMin();
    jobExecutorPriorityRangeMax = configuration.getJobExecutorPriorityRangeMax();
    ClockTestUtil.setClockToDateWithoutMilliseconds();
  }

  @AfterEach
  public void restoreProcessEngineConfiguration() {
    configuration.setJobExecutorAcquireByDueDate(jobExecutorAcquireByDueDate);
    configuration.setJobExecutorAcquireByPriority(jobExecutorAcquireByPriority);
    configuration.setJobExecutorPreferTimerJobs(jobExecutorPreferTimerJobs);
    configuration.setEnsureJobDueDateNotNull(jobEnsureDueDateSet);
    configuration.setJobExecutorPriorityRangeMin(jobExecutorPriorityRangeMin);
    configuration.setJobExecutorPriorityRangeMax(jobExecutorPriorityRangeMax);
    ClockUtil.reset();
  }

  protected List<AcquirableJobEntity> findAcquirableJobs() {
    return configuration.getCommandExecutorTxRequired().execute(new Command<List<AcquirableJobEntity>>() {

      @Override
      public List<AcquirableJobEntity> execute(CommandContext commandContext) {
        return commandContext
          .getJobManager()
          .findNextJobsToExecute(new Page(0, 100));
      }
    });
  }

  protected String startProcess(String processDefinitionKey, String activity) {
    return runtimeService
      .createProcessInstanceByKey(processDefinitionKey)
      .startBeforeActivity(activity)
      .execute().getId();
  }

  protected void startProcess(String processDefinitionKey, String activity, int times) {
    for (int i = 0; i < times; i++) {
      startProcess(processDefinitionKey, activity);
    }
  }

  protected Job findJobById(String id) {
    return managementService.createJobQuery().jobId(id).singleResult();
  }

}