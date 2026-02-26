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
package org.cibseven.bpm.engine.test.api.history.removaltime.cleanup;

import static org.apache.commons.lang3.time.DateUtils.addDays;
import static org.apache.commons.lang3.time.DateUtils.addSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.cibseven.bpm.engine.impl.jobexecutor.historycleanup.HistoryCleanupJobHandlerConfiguration.START_DELAY;

import java.util.Date;

import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.impl.util.ClockUtil;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineBootstrapRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;

import org.junit.jupiter.api.Test;

/**
 * @author Tassilo Weidner
 */
public class HistoryCleanupSchedulerCommentsTest extends AbstractHistoryCleanupSchedulerTest {

  @RegisterExtension
  @Order (1) public static ProcessEngineBootstrapRule bootstrapRule =
      new ProcessEngineBootstrapRule(AbstractHistoryCleanupSchedulerTest::configure);
  @RegisterExtension
  @Order (2) protected ProcessEngineRule engineRule = new ProcessEngineRule();
  @RegisterExtension
  @Order (3) protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);


  protected RuntimeService runtimeService;
  protected TaskService taskService;

  @BeforeEach
  public void init() {
    engineConfiguration = engineRule.getProcessEngineConfiguration();
    initEngineConfiguration(engineConfiguration);

    historyService = engineRule.getHistoryService();
    managementService = engineRule.getManagementService();

    runtimeService = engineRule.getRuntimeService();
    taskService = engineRule.getTaskService();
  }

  protected final String PROCESS_KEY = "process";
  protected final BpmnModelInstance PROCESS = Bpmn.createExecutableProcess(PROCESS_KEY)
    .camundaHistoryTimeToLive(5)
    .startEvent()
      .userTask("userTask").name("userTask")
    .endEvent().done();

  @Test
  public void shouldScheduleToNow() {
    // given
    testRule.deploy(PROCESS);

    runtimeService.startProcessInstanceByKey(PROCESS_KEY);

    String processInstanceId = runtimeService.createProcessInstanceQuery()
      .activityIdIn("userTask")
      .singleResult()
      .getId();

    ClockUtil.setCurrentTime(END_DATE);

    for (int i = 0; i < 5; i++) {
      taskService.createComment(null, processInstanceId, "aMessage");
    }

    String taskId = taskService.createTaskQuery().singleResult().getId();
    taskService.complete(taskId);

    engineConfiguration.setHistoryCleanupBatchSize(5);
    engineConfiguration.initHistoryCleanup();

    Date removalTime = addDays(END_DATE, 5);
    ClockUtil.setCurrentTime(addDays(END_DATE, 5));

    // when
    runHistoryCleanup();

    Job job = historyService.findHistoryCleanupJobs().get(0);

    // then
    assertThat(job.getDuedate()).isEqualTo(removalTime);
  }

  @Test
  public void shouldScheduleToLater() {
    // given
    testRule.deploy(PROCESS);

    runtimeService.startProcessInstanceByKey(PROCESS_KEY);

    String processInstanceId = runtimeService.createProcessInstanceQuery()
      .activityIdIn("userTask")
      .singleResult()
      .getId();

    ClockUtil.setCurrentTime(END_DATE);

    for (int i = 0; i < 5; i++) {
      taskService.createComment(null, processInstanceId, "aMessage");
    }

    String taskId = taskService.createTaskQuery().singleResult().getId();
    taskService.complete(taskId);

    engineConfiguration.setHistoryCleanupBatchSize(6);
    engineConfiguration.initHistoryCleanup();

    Date removalTime = addDays(END_DATE, 5);
    ClockUtil.setCurrentTime(removalTime);

    // when
    runHistoryCleanup();

    Job job = historyService.findHistoryCleanupJobs().get(0);

    // then
    assertThat(job.getDuedate()).isEqualTo(addSeconds(removalTime, START_DELAY));
  }

}
