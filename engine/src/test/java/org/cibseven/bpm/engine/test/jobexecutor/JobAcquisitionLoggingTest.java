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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.commons.testing.ProcessEngineLoggingRule;
import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

public class JobAcquisitionLoggingTest {

  @RegisterExtension
  protected ProvidedProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  @Order(9) public ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);
  @RegisterExtension
  @Order(7) public ProcessEngineLoggingRule loggingRule = new ProcessEngineLoggingRule().watch(
      "org.cibseven.bpm.engine.jobexecutor", Level.DEBUG);

  protected RuntimeService runtimeService;
  protected ProcessEngineConfigurationImpl processEngineConfiguration;

  @BeforeEach
  public void init() {
    runtimeService = engineRule.getRuntimeService();
    processEngineConfiguration = engineRule.getProcessEngineConfiguration();
  }

  @Test
  @Deployment(resources = { "org/cibseven/bpm/engine/test/jobexecutor/simpleAsyncProcess.bpmn20.xml" })
  public void shouldLogJobsAttemptingToAcquire() {
    // Given three jobs
    for (int i = 0; i < 3; i++) {
      runtimeService.startProcessInstanceByKey("simpleAsyncProcess");
    }

    // When executing the jobs
    processEngineConfiguration.getJobExecutor().start();
    testRule.waitForJobExecutorToProcessAllJobs();
    processEngineConfiguration.getJobExecutor().shutdown();

    // Look for log where it states that "acquiring [set value of MaxJobPerAcquisition] jobs"
    List<ILoggingEvent> filteredLogList = loggingRule.getFilteredLog(
        "Attempting to acquire " + processEngineConfiguration.getJobExecutor().getMaxJobsPerAcquisition()
            + " jobs for the process engine '" + processEngineConfiguration.getProcessEngineName() + "'");

    // asserting for a minimum occurrence as acquisition cycle should have started
    assertThat(filteredLogList.size()).isGreaterThanOrEqualTo(1);
  }

  @Test
  @Deployment(resources = { "org/cibseven/bpm/engine/test/jobexecutor/simpleAsyncProcess.bpmn20.xml" })
  public void shouldLogFailedAcquisitionLocks() {
    // Given three jobs
    for (int i = 0; i < 3; i++) {
      runtimeService.startProcessInstanceByKey("simpleAsyncProcess");
    }

    // when executing the jobs
    processEngineConfiguration.getJobExecutor().start();
    testRule.waitForJobExecutorToProcessAllJobs();
    processEngineConfiguration.getJobExecutor().shutdown();

    // Look for acquisition lock failures in logs. The logs should appear irrelevant of lock failure count of zero or
    // more.
    List<ILoggingEvent> filteredLogList = loggingRule.getFilteredLog(
        "Jobs failed to Lock during Acquisition of jobs for the process engine '"
            + processEngineConfiguration.getProcessEngineName() + "' : ");

    // Then observe the log appearing minimum 1 time, considering minimum 1 acquisition cycle
    assertThat(filteredLogList.size()).isGreaterThanOrEqualTo(1);
  }
}
