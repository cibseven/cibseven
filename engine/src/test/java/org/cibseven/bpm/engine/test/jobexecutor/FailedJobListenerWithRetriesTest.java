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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.time.DateUtils;
import org.cibseven.bpm.engine.OptimisticLockingException;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cmd.DefaultJobRetryCmd;
import org.cibseven.bpm.engine.impl.interceptor.Command;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.jobexecutor.DefaultFailedJobCommandFactory;
import org.cibseven.bpm.engine.impl.persistence.entity.JobEntity;
import org.cibseven.bpm.engine.impl.util.ClockUtil;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.test.util.ProcessEngineBootstrapRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class FailedJobListenerWithRetriesTest {

  @RegisterExtension
  public static ProcessEngineBootstrapRule bootstrapRule = new ProcessEngineBootstrapRule();

  @RegisterExtension
  protected ProvidedProcessEngineRule engineRule = new ProvidedProcessEngineRule(bootstrapRule);
  @RegisterExtension
  protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  protected ProcessEngineConfigurationImpl processEngineConfiguration;
  protected RuntimeService runtimeService;
  public int failedRetriesNumber;
  
  @BeforeEach
  public void init() {
    processEngineConfiguration = engineRule.getProcessEngineConfiguration();
    processEngineConfiguration.setFailedJobCommandFactory(new OLEFailedJobCommandFactory());
    processEngineConfiguration.setFailedJobListenerMaxRetries(5);
    runtimeService = engineRule.getRuntimeService();
  }

  public static Collection<Object[]> scenarios() {
    return Arrays.asList(new Object[][] {
        { 4, 0, false },
        //all retries are depleted without success -> the job is still locked
        { 5, 1, true }
    });
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @org.cibseven.bpm.engine.test.Deployment(resources = {"org/cibseven/bpm/engine/test/api/mgmt/IncidentTest.testShouldCreateOneIncident.bpmn"})
  public void testFailedJobListenerRetries(int failedRetriesNumber, int jobRetries, boolean jobLocked) {
    this.failedRetriesNumber = failedRetriesNumber;
    //given
    runtimeService.startProcessInstanceByKey("failingProcess");

    //when the job is run several times till the incident creation
    Job job = getJob();
    while (job.getRetries() > 0 && ((JobEntity)job).getLockOwner() == null ) {
      try {
        lockTheJob(job.getId());
        engineRule.getManagementService().executeJob(job.getId());
      } catch (Exception ex) {
      }
      job = getJob();
    }

    //then
    JobEntity jobFinalState = (JobEntity)engineRule.getManagementService().createJobQuery().jobId(job.getId()).list().get(0);
    assertEquals(jobRetries, jobFinalState.getRetries());
    if (jobLocked) {
      assertNotNull(jobFinalState.getLockOwner());
      assertNotNull(jobFinalState.getLockExpirationTime());
    } else {
      assertNull(jobFinalState.getLockOwner());
      assertNull(jobFinalState.getLockExpirationTime());
    }
  }

  void lockTheJob(final String jobId) {
    engineRule.getProcessEngineConfiguration().getCommandExecutorTxRequiresNew().execute(new Command<Object>() {
      @Override
      public Object execute(CommandContext commandContext) {
        final JobEntity job = commandContext.getJobManager().findJobById(jobId);
        job.setLockOwner("someLockOwner");
        job.setLockExpirationTime(DateUtils.addHours(ClockUtil.getCurrentTime(), 1));
        return null;
      }
    });
  }

  private Job getJob() {
    List<Job> jobs = engineRule.getManagementService().createJobQuery().list();
    assertEquals(1, jobs.size());
    return jobs.get(0);
  }

  public class OLEFailedJobCommandFactory extends DefaultFailedJobCommandFactory {

    private Map<String, OLEFoxJobRetryCmd> oleFoxJobRetryCmds = new HashMap<>();

    public Command<Object> getCommand(String jobId, Throwable exception) {
      return getOleFoxJobRetryCmds(jobId, exception);
    }

    public OLEFoxJobRetryCmd getOleFoxJobRetryCmds(String jobId, Throwable exception) {
      if (!oleFoxJobRetryCmds.containsKey(jobId)) {
        oleFoxJobRetryCmds.put(jobId, new OLEFoxJobRetryCmd(jobId, exception));
      }
      return oleFoxJobRetryCmds.get(jobId);
    }
  }

  public class OLEFoxJobRetryCmd extends DefaultJobRetryCmd {

    private int countRuns = 0;

    public OLEFoxJobRetryCmd(String jobId, Throwable exception) {
      super(jobId, exception);
    }

    @Override
    public Object execute(CommandContext commandContext) {
      Job job = getJob();
      //on last attempt the incident will be created, we imitate OLE
      if (job.getRetries() == 1) {
        countRuns++;
        if (countRuns <= failedRetriesNumber) {
          super.execute(commandContext);
          throw new OptimisticLockingException("OLE");
        }
      }
      return super.execute(commandContext);
    }
  }
}
