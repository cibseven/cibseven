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
package org.cibseven.bpm.engine.test.api.mgmt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.impl.interceptor.Command;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.JobEntity;
import org.cibseven.bpm.engine.impl.util.ClockUtil;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.api.runtime.util.ChangeVariablesDelegate;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;


/**
 * @author Tassilo Weidner
 */
public class JobEntityTest {

  protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

//  @Rule
//  public RuleChain ruleChain = RuleChain.outerRule(engineRule).around(testRule);

  protected List<String> jobIds = new ArrayList<>();

  protected HistoryService historyService;
  protected ManagementService managementService;
  protected RuntimeService runtimeService;

  protected static final Date CREATE_DATE = new Date(1363607000000L);

  protected String activityIdLoggingProperty;

  @BeforeEach
  public void setUp() {
    historyService = engineRule.getHistoryService();
    managementService = engineRule.getManagementService();
    runtimeService = engineRule.getRuntimeService();

    jobIds = new ArrayList<>();

    activityIdLoggingProperty = engineRule.getProcessEngineConfiguration().getLoggingContextActivityId();
  }

  @BeforeEach
  public void setClock() {
    ClockUtil.setCurrentTime(CREATE_DATE);
  }

  @AfterEach
  public void resetClock() {
    ClockUtil.reset();
  }

  @AfterEach
  public void cleanup() {
    for (String jobId : jobIds) {
      managementService.deleteJob(jobId);
    }

    if (!testRule.isHistoryLevelNone()) {
      cleanupJobLog();
    }

    engineRule.getProcessEngineConfiguration().setLoggingContextActivityId(activityIdLoggingProperty);
  }

  @Test
  public void shouldCheckCreateTimeOnMessage() {
    // given
    testRule.deploy(Bpmn.createExecutableProcess("process")
        .camundaHistoryTimeToLive(180)
        .startEvent()
        .camundaAsyncBefore()
        .endEvent()
        .done());

    runtimeService.startProcessInstanceByKey("process");

    // when
    Job messageJob = managementService.createJobQuery().singleResult();

    // then
    assertThat(messageJob.getCreateTime()).isEqualTo(CREATE_DATE);
    assertThat(messageJob.getClass().getSimpleName()).isEqualTo("MessageEntity");

    // cleanup
    jobIds.add(messageJob.getId());
  }

  @Test
  public void shouldCheckCreateTimeOnTimer() {
    // given
    testRule.deploy(Bpmn.createExecutableProcess("process")
        .camundaHistoryTimeToLive(180)
        .startEvent()
        .timerWithDuration("PT5S")
        .endEvent()
        .done());

    runtimeService.startProcessInstanceByKey("process");

    // when
    Job timerJob = managementService.createJobQuery().singleResult();

    // then
    assertThat(timerJob.getCreateTime()).isEqualTo(CREATE_DATE);
    assertThat(timerJob.getClass().getSimpleName()).isEqualTo("TimerEntity");

    // cleanup
    jobIds.add(timerJob.getId());
  }

  @Test
  public void shouldCheckCreateTimeOnEverLivingJob() {
    // given
    historyService.cleanUpHistoryAsync(true);

    // when
    Job everLivingJob = managementService.createJobQuery().singleResult();

    // then
    assertThat(everLivingJob.getCreateTime()).isEqualTo(CREATE_DATE);
    assertThat(everLivingJob.getClass().getSimpleName()).isEqualTo("EverLivingJobEntity");

    // cleanup
    jobIds.add(everLivingJob.getId());
  }

  @Test
  public void shouldShowFailedActivityIdPropertyForFailingAsyncTask() {
    // given
    testRule.deploy(Bpmn.createExecutableProcess("process")
        .camundaHistoryTimeToLive(180)
        .startEvent()
        .serviceTask("theTask")
        .camundaAsyncBefore()
        .camundaClass(FailingDelegate.class)
        .endEvent()
        .done());

    runtimeService.startProcessInstanceByKey("process", Variables.createVariables().putValue("fail", true));
    JobEntity job = (JobEntity) managementService.createJobQuery().singleResult();

    // when
    try {
      managementService.executeJob(job.getId());
      fail("Exception expected");
    } catch (Exception e) {
      // exception expected
    }

    // then
    job = (JobEntity) managementService.createJobQuery().jobId(job.getId()).singleResult();
    assertThat(job.getFailedActivityId()).isEqualTo("theTask");
  }

  @Test
  public void shouldShowFailedActivityIdIfActivityIdLoggingIsDisabled() {
    // given
    engineRule.getProcessEngineConfiguration().setLoggingContextActivityId(null);

    testRule.deploy(Bpmn.createExecutableProcess("process")
        .camundaHistoryTimeToLive(180)
        .startEvent()
        .serviceTask("theTask")
        .camundaAsyncBefore()
        .camundaClass(FailingDelegate.class)
        .endEvent()
        .done());

    runtimeService.startProcessInstanceByKey("process", Variables.createVariables().putValue("fail", true));
    JobEntity job = (JobEntity) managementService.createJobQuery().singleResult();

    // when
    try {
      managementService.executeJob(job.getId());
      fail("Exception expected");
    } catch (Exception e) {
      // exception expected
    }

    // then
    job = (JobEntity) managementService.createJobQuery().jobId(job.getId()).singleResult();
    assertThat(job.getFailedActivityId()).isEqualTo("theTask");
  }

  @Test
  public void shouldShowFailedActivityIdPropertyForAsyncTaskWithFailingFollowUp() {
    // given
    testRule.deploy(Bpmn.createExecutableProcess("process")
        .camundaHistoryTimeToLive(180)
        .startEvent()
        .serviceTask("theTask")
        .camundaAsyncBefore()
        .camundaClass(ChangeVariablesDelegate.class)
        .serviceTask("theTask2")
        .camundaClass(ChangeVariablesDelegate.class)
        .serviceTask("theTask3")
        .camundaClass(FailingDelegate.class)
        .endEvent()
        .done());

    runtimeService.startProcessInstanceByKey("process", Variables.createVariables().putValue("fail", true));
    JobEntity job = (JobEntity) managementService.createJobQuery().singleResult();

    // when
    try {
      managementService.executeJob(job.getId());
      fail("Exception expected");
    } catch (Exception e) {
      // exception expected
    }

    // then
    job = (JobEntity) managementService.createJobQuery().jobId(job.getId()).singleResult();
    assertThat(job.getFailedActivityId()).isEqualTo("theTask3");
  }

  // helper ////////////////////////////////////////////////////////////////////////////////////////////////////////////

  protected void cleanupJobLog() {
    engineRule.getProcessEngineConfiguration().getCommandExecutorTxRequired()
      .execute(new Command<Void>() {
        public Void execute(CommandContext commandContext) {
          for (String jobId : jobIds) {
            commandContext.getHistoricJobLogManager()
              .deleteHistoricJobLogByJobId(jobId);
          }

          return null;
        }
      });
  }

}