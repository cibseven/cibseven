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

import java.util.Collections;
import java.util.List;

import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.batch.Batch;
import org.cibseven.bpm.engine.history.HistoricJobLog;
import org.cibseven.bpm.engine.impl.HistoricJobLogQueryImpl;
import org.cibseven.bpm.engine.impl.Page;
import org.cibseven.bpm.engine.impl.batch.history.HistoricBatchEntity;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.interceptor.Command;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.interceptor.CommandExecutor;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.HistoricProcessInstanceEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.HistoricTaskInstanceEntity;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Order;


/**
 * @author Tassilo Weidner
 */
@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
public class PartitioningTest {

  @RegisterExtension
  @Order(4) protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  @Order(9) protected ProcessEngineTestRule testHelper = new ProcessEngineTestRule(engineRule);

  protected ProcessEngineConfigurationImpl processEngineConfiguration;

  protected RuntimeService runtimeService;
  protected TaskService taskService;
  protected HistoryService historyService;
  protected ManagementService managementService;

  protected CommandExecutor commandExecutor;

  @BeforeEach
  public void init() {
    commandExecutor = engineRule.getProcessEngineConfiguration().getCommandExecutorTxRequired();

    runtimeService = engineRule.getRuntimeService();
    taskService = engineRule.getTaskService();
    historyService = engineRule.getHistoryService();
    managementService = engineRule.getManagementService();
  }

  final protected BpmnModelInstance PROCESS_WITH_USERTASK = Bpmn.createExecutableProcess("process")
    .startEvent()
      .userTask()
    .endEvent().done();

  @Test
  public void shouldUpdateHistoricProcessInstance() {
    // given
    final String processInstanceId = deployAndStartProcess(PROCESS_WITH_USERTASK).getId();

    commandExecutor.execute(new Command<Void>() {
      public Void execute(CommandContext commandContext) {

        HistoricProcessInstanceEntity historicProcessInstanceEntity =
          (HistoricProcessInstanceEntity) historyService.createHistoricProcessInstanceQuery().singleResult();

        commandContext.getDbEntityManager()
          .delete(historicProcessInstanceEntity);

        return null;
      }
    });

    // assume
    assertThat(historyService.createHistoricProcessInstanceQuery().count()).isEqualTo(0L);

    // when
    runtimeService.deleteProcessInstance(processInstanceId, "aDeleteReason");

    // then
    assertThat(runtimeService.createProcessInstanceQuery().singleResult()).isNull();

    // cleanup
    cleanUp(processInstanceId);
  }

  @Test
  public void shouldUpdateHistoricTaskInstance() {
    // given
    deployAndStartProcess(PROCESS_WITH_USERTASK).getId();

    commandExecutor.execute(new Command<Void>() {
      public Void execute(CommandContext commandContext) {

        HistoricTaskInstanceEntity historicTaskInstanceEntity =
          (HistoricTaskInstanceEntity) historyService.createHistoricTaskInstanceQuery().singleResult();

        commandContext.getDbEntityManager()
          .delete(historicTaskInstanceEntity);

        return null;
      }
    });

    // assume
    assertThat(historyService.createHistoricTaskInstanceQuery().singleResult()).isNull();

    // when
    String taskId = taskService.createTaskQuery()
      .singleResult()
      .getId();

    taskService.complete(taskId);

    // then
    assertThat(taskService.createTaskQuery().singleResult()).isNull();
  }

  @Test
  public void shouldUpdateHistoricActivityInstance() {
    // given
    final String processInstanceId = deployAndStartProcess(PROCESS_WITH_USERTASK).getId();

    commandExecutor.execute(new Command<Void>() {
      public Void execute(CommandContext commandContext) {

        commandContext.getHistoricActivityInstanceManager()
          .deleteHistoricActivityInstancesByProcessInstanceIds(Collections.singletonList(processInstanceId));

        return null;
      }
    });

    // assume
    assertThat(historyService.createHistoricActivityInstanceQuery().count()).isEqualTo(0L);

    // when
    String taskId = taskService.createTaskQuery()
      .singleResult()
      .getId();

    taskService.complete(taskId);

    // then
    assertThat(historyService.createHistoricActivityInstanceQuery().count()).isEqualTo(1L);
  }

  @Test
  public void shouldUpdateHistoricIncident() {
    // given
    final String processInstanceId = deployAndStartProcess(PROCESS_WITH_USERTASK).getId();

    ExecutionEntity execution = (ExecutionEntity) runtimeService.createExecutionQuery().singleResult();

    String incidentId = engineRule.getRuntimeService()
      .createIncident("foo", execution.getId(), execution.getActivityId(), "bar").getId();

    commandExecutor.execute(new Command<Void>() {
      public Void execute(CommandContext commandContext) {

        commandContext.getHistoricIncidentManager()
          .deleteHistoricIncidentsByProcessInstanceIds(Collections.singletonList(processInstanceId));

        return null;
      }
    });

    // assume
    assertThat(historyService.createHistoricIncidentQuery().count()).isEqualTo(0L);
    assertThat(runtimeService.createIncidentQuery().count()).isEqualTo(1L);

    // when
    runtimeService.resolveIncident(incidentId);

    // then
    assertThat(runtimeService.createIncidentQuery().count()).isEqualTo(0L);
    assertThat(historyService.createHistoricIncidentQuery().count()).isEqualTo(0L);
  }

  @Test
  public void shouldUpdateHistoricBatch() {
    // given
    String processInstanceId = deployAndStartProcess(PROCESS_WITH_USERTASK).getId();

    final Batch batch = runtimeService.deleteProcessInstancesAsync(Collections.singletonList(processInstanceId), "aDeleteReason");

    // assume
    assertThat(historyService.createHistoricBatchQuery().count()).isEqualTo(1L);

    commandExecutor.execute(new Command<Void>() {
      public Void execute(CommandContext commandContext) {

        HistoricBatchEntity historicBatchEntity = (HistoricBatchEntity) historyService.createHistoricBatchQuery()
          .singleResult();

        commandContext.getDbEntityManager()
          .delete(historicBatchEntity);

        return null;
      }
    });

    // assume
    assertThat(historyService.createHistoricBatchQuery().count()).isEqualTo(0L);

    // when
    String seedJobDefinitionId = batch.getSeedJobDefinitionId();
    Job seedJob = managementService.createJobQuery().jobDefinitionId(seedJobDefinitionId).singleResult();
    managementService.executeJob(seedJob.getId());

    String batchJobDefinitionId = batch.getBatchJobDefinitionId();
    List<Job> batchJobs = managementService.createJobQuery().jobDefinitionId(batchJobDefinitionId).list();
    for (Job batchJob : batchJobs) {
      managementService.executeJob(batchJob.getId());
    }

    List<Job> monitorJobs = managementService.createJobQuery().jobDefinitionId(batch.getMonitorJobDefinitionId()).list();
    for (Job monitorJob : monitorJobs) {
      managementService.executeJob(monitorJob.getId());
    }

    // then
    assertThat(runtimeService.createProcessInstanceQuery().count()).isEqualTo(0L);
    assertThat(managementService.createBatchQuery().count()).isEqualTo(0L);

    // cleanup
    cleanUp(processInstanceId);
  }

  protected ProcessInstance deployAndStartProcess(BpmnModelInstance bpmnModelInstance) {
    testHelper.deploy(bpmnModelInstance);

    String processDefinitionKey = bpmnModelInstance.getDefinitions().getRootElements().iterator().next().getId();
    return runtimeService.startProcessInstanceByKey(processDefinitionKey);
  }

  protected void cleanUp(final String processInstanceId) {
    commandExecutor.execute(new Command<Void>() {
      public Void execute(CommandContext commandContext) {

        commandContext.getHistoricActivityInstanceManager()
          .deleteHistoricActivityInstancesByProcessInstanceIds(Collections.singletonList(processInstanceId));

        commandContext.getHistoricTaskInstanceManager()
          .deleteHistoricTaskInstancesByProcessInstanceIds(Collections.singletonList(processInstanceId), true);

        List<HistoricJobLog> historicJobLogs = commandContext.getHistoricJobLogManager()
          .findHistoricJobLogsByQueryCriteria(new HistoricJobLogQueryImpl(), new Page(0, 100));

        for (HistoricJobLog historicJobLog : historicJobLogs) {
          commandContext.getHistoricJobLogManager().deleteHistoricJobLogByJobId(historicJobLog.getJobId());
        }

        return null;
      }
    });
  }

}
