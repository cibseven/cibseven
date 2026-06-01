/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.cibseven.bpm.engine.test.history;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.runtime.Incident;
import org.cibseven.bpm.engine.history.HistoricActivityStatistics;
import org.cibseven.bpm.engine.impl.HistoricActivityStatisticsPostQueryImpl;
import org.cibseven.bpm.engine.impl.util.ClockUtil;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_AUDIT)
public class HistoricActivityStatisticsPostQueryOrTest {

  private static final String PROCESS_KEY = "orProcess";
  private static final String PROCESS_NAME = "OR Process";
  private static final String USER_TASK_ID = "testQuerySuspensionStateTask";

  @Rule
  public ProcessEngineRule processEngineRule = new ProvidedProcessEngineRule();

  protected HistoryService historyService;
  protected RuntimeService runtimeService;
  protected RepositoryService repositoryService;

  protected List<String> deploymentIds = new ArrayList<>();

  @Before
  public void init() {
    historyService = processEngineRule.getHistoryService();
    runtimeService = processEngineRule.getRuntimeService();
    repositoryService = processEngineRule.getRepositoryService();
  }

  @After
  public void tearDown() {
    ClockUtil.reset();
    for (String deploymentId : deploymentIds) {
      repositoryService.deleteDeployment(deploymentId, true);
    }
  }

  @Test
  public void shouldReturnStatsWithEmptyOrQuery() {
    // given
    String processDefinitionId = deployOneTaskProcess(PROCESS_KEY, PROCESS_NAME);
    startProcess(PROCESS_KEY, "bk-1", null);
    startProcess(PROCESS_KEY, "bk-2", null);

    // when
    HistoricActivityStatisticsPostQueryImpl query = createOrQuery(processDefinitionId, orQuery -> {
      // empty OR block
    });

    // then
    assertEquals(2L, getInstances(query));
  }

  @Test
  public void shouldReturnStatsByProcessInstanceIdOrProcessInstanceIds() {
    // given
    String processDefinitionId = deployOneTaskProcess(PROCESS_KEY, PROCESS_NAME);
    ProcessInstance processInstance1 = startProcess(PROCESS_KEY, null, null);
    ProcessInstance processInstance2 = startProcess(PROCESS_KEY, null, null);
    ProcessInstance processInstance3 = startProcess(PROCESS_KEY, null, null);

    // when
    HistoricActivityStatisticsPostQueryImpl query = createOrQuery(processDefinitionId, orQuery -> {
      orQuery.processInstanceId(processInstance1.getId());
      orQuery.processInstanceIds(new HashSet<>(List.of(processInstance2.getId(), processInstance3.getId())));
    });

    // then
    assertEquals(3L, getInstances(query));
  }

  @Test
  public void shouldReturnStatsByProcessInstanceIdNotInOrProcessInstanceId() {
    // given
    String processDefinitionId = deployOneTaskProcess(PROCESS_KEY, PROCESS_NAME);
    ProcessInstance processInstance1 = startProcess(PROCESS_KEY, null, null);
    ProcessInstance processInstance2 = startProcess(PROCESS_KEY, null, null);
    ProcessInstance processInstance3 = startProcess(PROCESS_KEY, null, null);

    // when
    HistoricActivityStatisticsPostQueryImpl query = createOrQuery(processDefinitionId, orQuery -> {
      orQuery.processInstanceIdNotIn(processInstance1.getId());
      orQuery.processInstanceId(processInstance1.getId());
    });

    // then
    assertEquals(3L, getInstances(query));
    assertEquals(3L, historyService.createHistoricProcessInstanceQuery()
      .processInstanceIds(new HashSet<>(List.of(processInstance1.getId(), processInstance2.getId(), processInstance3.getId())))
      .count());
  }

  @Test
  public void shouldReturnStatsByStartedBeforeOrStartedAfter() {
    // given
    String processDefinitionId = deployOneTaskProcess(PROCESS_KEY, PROCESS_NAME);
    Date firstStart = new Date(1716112800000L); // 2024-05-19T10:00:00Z
    Date secondStart = new Date(1716116400000L); // 2024-05-19T11:00:00Z

    ClockUtil.setCurrentTime(firstStart);
    startProcess(PROCESS_KEY, null, null);

    ClockUtil.setCurrentTime(secondStart);
    startProcess(PROCESS_KEY, null, null);

    // when
    HistoricActivityStatisticsPostQueryImpl query = createOrQuery(processDefinitionId, orQuery -> orQuery
      .startedBefore(firstStart)
      .startedAfter(secondStart));

    // then
    assertEquals(2L, getInstances(query));
  }


  @Test
  public void shouldReturnBusinessKeylike() {
    // given
    String processDefinitionId = deployOneTaskProcess(PROCESS_KEY, PROCESS_NAME);
    startProcess(PROCESS_KEY, "bk-1", null);
    startProcess(PROCESS_KEY, "bk-2", null);
    startProcess(PROCESS_KEY, "other", null);

    // when
    HistoricActivityStatisticsPostQueryImpl query = createOrQuery(processDefinitionId, orQuery -> orQuery
      .businessKeyLike("%bk-%"));

    // then
    assertEquals(2L, getInstances(query));
  }

  @Test
  public void shouldReturnStatsByActiveOrSuspendedState() {
    // given
    String processDefinitionId = deployOneTaskProcess(PROCESS_KEY, PROCESS_NAME);
    startProcess(PROCESS_KEY, null, null);
    startProcess(PROCESS_KEY, null, null);
    ProcessInstance suspendedProcessInstance = startProcess(PROCESS_KEY, null, null);
    runtimeService.suspendProcessInstanceById(suspendedProcessInstance.getId());

    // when
    HistoricActivityStatisticsPostQueryImpl query = createOrQuery(processDefinitionId, orQuery -> orQuery
      .active()
      .suspended());

    // then
    assertEquals(3L, getInstances(query));
  }

  @Test
  public void shouldReturnStatsByActivityIdOrProcessInstanceId() {
    // given
    String processDefinitionId = deployOneTaskProcess(PROCESS_KEY, PROCESS_NAME);
    ProcessInstance processInstance1 = startProcess(PROCESS_KEY, null, null);
    ProcessInstance processInstance2 = startProcess(PROCESS_KEY, null, null);

    // when
    HistoricActivityStatisticsPostQueryImpl query = createOrQuery(processDefinitionId, orQuery -> orQuery
      .activeActivityIdIn(USER_TASK_ID)
      .processInstanceId(processInstance2.getId()));

    // then
    assertEquals(2L, getInstances(query));
    assertEquals(2L, historyService.createHistoricProcessInstanceQuery()
      .processInstanceIds(new HashSet<>(List.of(processInstance1.getId(), processInstance2.getId())))
      .count());
  }

  @Test
  public void shouldReturnStatsByFinishedOrUnfinished() {
    // given
    String processDefinitionId = deployOneTaskProcess(PROCESS_KEY, PROCESS_NAME);
    ProcessInstance finished = startProcess(PROCESS_KEY, null, null);
    ProcessInstance unfinished = startProcess(PROCESS_KEY, null, null);

    Task taskToFinish = processEngineRule.getTaskService().createTaskQuery()
      .processInstanceId(finished.getId())
      .singleResult();
    processEngineRule.getTaskService().complete(taskToFinish.getId());

    // when
    HistoricActivityStatisticsPostQueryImpl query = createOrQuery(processDefinitionId, orQuery -> orQuery
      .finished()
      .unfinished());
    query.includeFinished();

    // then
    assertEquals(1L, sumInstances(query));
    assertEquals(4L, sumFinished(query));
    assertEquals(1L, historyService.createHistoricProcessInstanceQuery().processInstanceId(unfinished.getId()).active().count());
  }

  @Test
  public void shouldReturnStatsWithIncludeFinishedForOrSelection() {
    // given
    String processDefinitionId = deployOneTaskProcess(PROCESS_KEY, PROCESS_NAME);
    ProcessInstance finished = startProcess(PROCESS_KEY, "finished", null);
    startProcess(PROCESS_KEY, "running", null);

    Task taskToFinish = processEngineRule.getTaskService().createTaskQuery()
      .processInstanceId(finished.getId())
      .singleResult();
    processEngineRule.getTaskService().complete(taskToFinish.getId());

    // when
    HistoricActivityStatisticsPostQueryImpl query = createOrQuery(processDefinitionId, orQuery -> orQuery
      .processInstanceId(finished.getId())
      .businessKeyLike("%running%"));
    query.includeFinished();

    // then
    assertEquals(1L, sumInstances(query));
    assertEquals(4L, sumFinished(query));
  }

  @Test
  public void shouldReturnStatsWithIncludeCanceledForOrSelection() {
    // given
    String processDefinitionId = deployOneTaskProcess(PROCESS_KEY, PROCESS_NAME);
    ProcessInstance canceled = startProcess(PROCESS_KEY, "canceled", null);
    startProcess(PROCESS_KEY, "running", null);
    runtimeService.deleteProcessInstance(canceled.getId(), "test");

    // when
    HistoricActivityStatisticsPostQueryImpl query = createOrQuery(processDefinitionId, orQuery -> orQuery
      .processInstanceId(canceled.getId())
      .businessKeyLike("%running%"));
    query.includeCanceled();

    // then
    assertEquals(1L, sumInstances(query));
    assertEquals(1L, sumCanceled(query));
  }

  @Test
  public void shouldReturnNoStatsWhenNoOrBranchMatches() {
    // given
    String processDefinitionId = deployOneTaskProcess(PROCESS_KEY, PROCESS_NAME);
    startProcess(PROCESS_KEY, null, null);
    startProcess(PROCESS_KEY, null, null);

    // when
    HistoricActivityStatisticsPostQueryImpl query =
      (HistoricActivityStatisticsPostQueryImpl) historyService.createHistoricActivityStatisticsPostQuery(processDefinitionId);
    query.processInstanceId("not-existing-base");

    HistoricActivityStatisticsPostQueryImpl orQuery = new HistoricActivityStatisticsPostQueryImpl(processDefinitionId);
    orQuery.setIsOrQueryActive();
    orQuery.processInstanceId("not-existing-or");
    query.addOrQuery(orQuery);

    // then
    assertEquals(0L, getInstances(query));
    assertEquals(0L, query.count());
  }

  @Test
  public void shouldReturnStatsByIncidentTypeOrIncidentStatus() {
    // given
    String processDefinitionId = deployOneTaskProcess(PROCESS_KEY, PROCESS_NAME);
    startProcess(PROCESS_KEY, null, Map.of("x", "y"));
    startProcess(PROCESS_KEY, null, Map.of("x", "z"));

    // create incidents on both instances via a failing expression job
    for (String processInstanceId : List.of(
        runtimeService.createProcessInstanceQuery().list().get(0).getId(),
        runtimeService.createProcessInstanceQuery().list().get(1).getId())) {
      runtimeService.setVariable(processInstanceId, "failing", null);
    }

    // directly create an incident on one execution to keep the OR-incident-path deterministic
    ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().list().get(0);
    runtimeService.createIncident("failedExternalTask", processInstance.getId(), "cfg", "incident message");

    // when
    HistoricActivityStatisticsPostQueryImpl query = createOrQuery(processDefinitionId, orQuery -> orQuery
      .incidentType("failedExternalTask")
      .incidentStatus("open"));
    query.withIncidents();

    // then
    assertEquals(2L, getInstances(query));
    List<Incident> incidents = runtimeService.createIncidentQuery().list();
    assertEquals(1, incidents.size());
  }

  @Test
  public void shouldReturnStatsByBusinessKeyOrProcessInstanceIds() {
    // given
    String processDefinitionId = deployOneTaskProcess(PROCESS_KEY, PROCESS_NAME);
    ProcessInstance p1 = startProcess(PROCESS_KEY, "bk-1", null);
    ProcessInstance p2 = startProcess(PROCESS_KEY, "bk-2", null);
    ProcessInstance p3 = startProcess(PROCESS_KEY, "other", null);
    ProcessInstance p4 = startProcess(PROCESS_KEY, "otherother", null);

    // when
    HistoricActivityStatisticsPostQueryImpl query = createOrQuery(processDefinitionId, orQuery -> {
      orQuery.businessKeyLike("%bk-%");
      orQuery.processInstanceId(p3.getId());
    });

    // then
    assertEquals(3L, getInstances(query));
  }

  private String deployOneTaskProcess(String processKey, String processName) {
    BpmnModelInstance process = Bpmn.createExecutableProcess(processKey)
      .name(processName)
      .startEvent()
      .userTask(USER_TASK_ID)
      .endEvent()
      .done();

    String deploymentId = repositoryService.createDeployment()
      .addModelInstance(processKey + ".bpmn", process)
      .deploy()
      .getId();

    deploymentIds.add(deploymentId);

    ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
      .deploymentId(deploymentId)
      .singleResult();

    return definition.getId();
  }

  private ProcessInstance startProcess(String processKey, String businessKey, Map<String, Object> variables) {
    if (businessKey != null && variables != null) {
      return runtimeService.startProcessInstanceByKey(processKey, businessKey, variables);
    }

    if (businessKey != null) {
      return runtimeService.startProcessInstanceByKey(processKey, businessKey);
    }

    if (variables != null) {
      return runtimeService.startProcessInstanceByKey(processKey, variables);
    }

    return runtimeService.startProcessInstanceByKey(processKey);
  }

  private HistoricActivityStatisticsPostQueryImpl createOrQuery(String processDefinitionId,
      Consumer<HistoricActivityStatisticsPostQueryImpl> orQueryConfigurer) {

    HistoricActivityStatisticsPostQueryImpl query =
      (HistoricActivityStatisticsPostQueryImpl) historyService.createHistoricActivityStatisticsPostQuery(processDefinitionId);

    HistoricActivityStatisticsPostQueryImpl orQuery = new HistoricActivityStatisticsPostQueryImpl(processDefinitionId);
    orQuery.setIsOrQueryActive();
    orQueryConfigurer.accept(orQuery);

    query.addOrQuery(orQuery);

    return query;
  }

  private long getInstances(HistoricActivityStatisticsPostQueryImpl query) {
    List<HistoricActivityStatistics> statistics = query.list();
    if (statistics.isEmpty()) {
      return 0L;
    }

    // The one-task model yields a single active activity row with aggregated instance count.
    return statistics.get(0).getInstances();
  }

  private long sumInstances(HistoricActivityStatisticsPostQueryImpl query) {
    return query.list().stream().mapToLong(HistoricActivityStatistics::getInstances).sum();
  }

  private long sumFinished(HistoricActivityStatisticsPostQueryImpl query) {
    return query.list().stream().mapToLong(HistoricActivityStatistics::getFinished).sum();
  }

  private long sumCanceled(HistoricActivityStatisticsPostQueryImpl query) {
    return query.list().stream().mapToLong(HistoricActivityStatistics::getCanceled).sum();
  }
}
