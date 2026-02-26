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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.impl.interceptor.Command;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.util.ClockUtil;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineBootstrapRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.RegisterExtension;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class MetricsManagerForCleanupTest {

  private static final BpmnModelInstance PROCESS = Bpmn.createExecutableProcess("process")
      .startEvent("start")
      .userTask("userTask1")
      .sequenceFlowId("seq")
      .userTask("userTask2")
      .endEvent("end")
      .done();

  @RegisterExtension
  public static ProcessEngineBootstrapRule bootstrapRule = new ProcessEngineBootstrapRule(configuration ->
      configuration.setTaskMetricsEnabled(true));

  @RegisterExtension
  protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);


  protected ManagementService managementService;
  protected RuntimeService runtimeService;
  protected TaskService taskService;

  @BeforeEach
  public void init() {
    runtimeService = engineRule.getRuntimeService();
    managementService = engineRule.getManagementService();
    taskService = engineRule.getTaskService();
  }

  @AfterEach
  public void clearDatabase() {
    testRule.deleteHistoryCleanupJobs();
    managementService.deleteTaskMetrics(null);
  }

  public static Collection<Object[]> scenarios() {
    return Arrays.asList(new Object[][] {
        // all historic batches are old enough to be cleaned up
        { 5, -6, -7, 50, 2 },
        // one batch should be cleaned up
        { 5, -3, -7, 50, 1 },
        // not enough time has passed
        { 5, -3, -4, 50, 0 },
        // batchSize will reduce the result
        { 5, -6, -7, 1, 1 } });
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testFindHistoricBatchIdsForCleanup(int taskMetricHistoryTTL, int metric1DaysInThePast, 
    int metric2DaysInThePast, int batchSize, int resultCount) {
    // given
    prepareTaskMetrics(metric1DaysInThePast, metric2DaysInThePast);

    engineRule.getProcessEngineConfiguration().getCommandExecutorTxRequired().execute(new Command<Object>() {
      @Override
      public Object execute(CommandContext commandContext) {
        // when
        List<String> taskMetricIdsForCleanup = commandContext.getMeterLogManager()
            .findTaskMetricsForCleanup(batchSize, taskMetricHistoryTTL, 0, 59);

        // then
        assertThat(taskMetricIdsForCleanup.size()).isEqualTo(resultCount);

        return null;
      }
    });
  }

  private void prepareTaskMetrics(int metric1DaysInThePast, int metric2DaysInThePast) {
    testRule.deploy(PROCESS);
    runtimeService.startProcessInstanceByKey("process");

    String taskId = taskService.createTaskQuery().singleResult().getId();

    ClockUtil.offset(TimeUnit.DAYS.toMillis(metric1DaysInThePast));
    taskService.setAssignee(taskId, "kermit");

    ClockUtil.offset(TimeUnit.DAYS.toMillis(metric2DaysInThePast));
    taskService.setAssignee(taskId, "gonzo");

    ClockUtil.reset();
  }
}