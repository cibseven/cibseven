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
package org.cibseven.bpm.engine.test.api.optimize;

import java.util.Date;
import java.util.List;

import org.cibseven.bpm.engine.AuthorizationService;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.authorization.Authorization;
import org.cibseven.bpm.engine.history.HistoricActivityInstance;
import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.User;
import org.cibseven.bpm.engine.impl.OptimizeService;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.history.event.HistoryEvent;
import org.cibseven.bpm.engine.impl.util.ClockUtil;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;


@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
public class GetRunningHistoricActivityInstancesForOptimizeTest {

  protected static final String VARIABLE_NAME = "aVariableName";
  protected static final String VARIABLE_VALUE = "aVariableValue";

  public ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  protected ProcessEngineTestRule testHelper = new ProcessEngineTestRule(engineRule);

//  @Rule
//  public RuleChain ruleChain = RuleChain.outerRule(engineRule).around(testHelper);

  protected String userId = "test";
  private OptimizeService optimizeService;
  private IdentityService identityService;
  private RuntimeService runtimeService;
  private AuthorizationService authorizationService;
  private TaskService taskService;


  @BeforeEach
  public void init() {
    ProcessEngineConfigurationImpl config =
      engineRule.getProcessEngineConfiguration();
    optimizeService = config.getOptimizeService();
    identityService = engineRule.getIdentityService();
    runtimeService = engineRule.getRuntimeService();
    authorizationService = engineRule.getAuthorizationService();
    taskService = engineRule.getTaskService();

    createUser(userId);
  }

  @AfterEach
  public void cleanUp() {
    for (User user : identityService.createUserQuery().list()) {
      identityService.deleteUser(user.getId());
    }
    for (Group group : identityService.createGroupQuery().list()) {
      identityService.deleteGroup(group.getId());
    }
    for (Authorization authorization : authorizationService.createAuthorizationQuery().list()) {
      authorizationService.deleteAuthorization(authorization.getId());
    }
    ClockUtil.reset();
  }

  @Test
  public void getRunningHistoricActivityInstances() {
    // given
    BpmnModelInstance simpleDefinition = Bpmn.createExecutableProcess("process")
      .startEvent("startEvent")
      .name("start")
      .userTask("userTask")
      .name("task")
      .endEvent("endEvent")
      .name("end")
      .done();
    testHelper.deploy(simpleDefinition);
    runtimeService.startProcessInstanceByKey("process");

    // when
    List<HistoricActivityInstance> runningHistoricActivityInstances =
      optimizeService.getRunningHistoricActivityInstances(pastDate(), null, 10);

    // then
    Assertions.assertEquals(1, runningHistoricActivityInstances.size());
    HistoricActivityInstance activityInstance = runningHistoricActivityInstances.get(0);
    assertThatActivitiesHaveAllImportantInformation(activityInstance);
  }

  @Test
  public void startedAfterParameterWorks() {
    // given
    BpmnModelInstance simpleDefinition = Bpmn.createExecutableProcess("process")
      .startEvent("startEvent")
      .userTask("userTask")
      .endEvent("endEvent")
      .done();
    testHelper.deploy(simpleDefinition);
    Date now = new Date();
    Date nowPlus2Seconds = new Date(now.getTime() + 2000L);
    ClockUtil.setCurrentTime(now);
    engineRule.getRuntimeService().startProcessInstanceByKey("process");

    // when
    ClockUtil.setCurrentTime(nowPlus2Seconds);
    ProcessInstance secondProcessInstance =
      engineRule.getRuntimeService().startProcessInstanceByKey("process");
    List<HistoricActivityInstance> runningHistoricActivityInstances =
      optimizeService.getRunningHistoricActivityInstances(now, null, 10);

    // then
    Assertions.assertEquals(1, runningHistoricActivityInstances.size());
    Assertions.assertEquals(secondProcessInstance.getId(), runningHistoricActivityInstances.get(0).getProcessInstanceId());
  }

  @Test
  public void startedAtParameterWorks() {
    // given
    BpmnModelInstance simpleDefinition = Bpmn.createExecutableProcess("process")
      .startEvent("startEvent")
      .userTask("userTask")
      .endEvent("endEvent")
      .done();
    testHelper.deploy(simpleDefinition);
    Date now = new Date();
    Date nowPlus2Seconds = new Date(now.getTime() + 2000L);
    ClockUtil.setCurrentTime(now);
    ProcessInstance firstProcessInstance =
      engineRule.getRuntimeService().startProcessInstanceByKey("process");

    // when
    ClockUtil.setCurrentTime(nowPlus2Seconds);
    engineRule.getRuntimeService().startProcessInstanceByKey("process");
    List<HistoricActivityInstance> runningHistoricActivityInstances =
      optimizeService.getRunningHistoricActivityInstances(null, now, 10);

    // then
    Assertions.assertEquals(1, runningHistoricActivityInstances.size());
    Assertions.assertEquals(firstProcessInstance.getId(), runningHistoricActivityInstances.get(0).getProcessInstanceId());
  }

  @Test
  public void startedAfterAndStartedAtParameterWorks() {
    // given
    BpmnModelInstance simpleDefinition = Bpmn.createExecutableProcess("process")
      .startEvent("startEvent")
      .userTask("userTask")
      .endEvent("endEvent")
      .done();
    testHelper.deploy(simpleDefinition);
    Date now = new Date();
    Date nowPlus2Seconds = new Date(now.getTime() + 2000L);
    ClockUtil.setCurrentTime(now);
    engineRule.getRuntimeService().startProcessInstanceByKey("process");

    // when
    ClockUtil.setCurrentTime(nowPlus2Seconds);
    engineRule.getRuntimeService().startProcessInstanceByKey("process");
    List<HistoricActivityInstance> runningHistoricActivityInstances =
      optimizeService.getRunningHistoricActivityInstances(now, now, 10);

    // then
    Assertions.assertEquals(0, runningHistoricActivityInstances.size());
  }

  @Test
  public void maxResultsParameterWorks() {
    // given
    BpmnModelInstance simpleDefinition = Bpmn.createExecutableProcess("process")
      .startEvent("startEvent")
      .userTask("userTask")
      .endEvent("endEvent")
      .done();
    testHelper.deploy(simpleDefinition);
    engineRule.getRuntimeService().startProcessInstanceByKey("process");
    engineRule.getRuntimeService().startProcessInstanceByKey("process");
    engineRule.getRuntimeService().startProcessInstanceByKey("process");
    engineRule.getRuntimeService().startProcessInstanceByKey("process");

    // when
    List<HistoricActivityInstance> runningHistoricActivityInstances =
      optimizeService.getRunningHistoricActivityInstances(pastDate(), null, 3);

    // then
    Assertions.assertEquals(3, runningHistoricActivityInstances.size());
  }

  @Test
  public void resultIsSortedByStartTime() {
    // given
    BpmnModelInstance simpleDefinition = Bpmn.createExecutableProcess("process")
      .startEvent("startEvent")
      .userTask("userTask")
      .endEvent("endEvent")
      .done();
    testHelper.deploy(simpleDefinition);
    ClockUtil.setCurrentTime(new Date());
    ProcessInstance firstProcessInstance =
      engineRule.getRuntimeService().startProcessInstanceByKey("process");
    shiftTimeByOneMinute();
    ProcessInstance secondProcessInstance =
      engineRule.getRuntimeService().startProcessInstanceByKey("process");
    shiftTimeByOneMinute();
    ProcessInstance thirdProcessInstance =
      engineRule.getRuntimeService().startProcessInstanceByKey("process");

    // when
    List<HistoricActivityInstance> runningHistoricActivityInstances =
      optimizeService.getRunningHistoricActivityInstances(pastDate(), null, 4);

    // then
    Assertions.assertEquals(3, runningHistoricActivityInstances.size());
    Assertions.assertEquals(firstProcessInstance.getId(), runningHistoricActivityInstances.get(0).getProcessInstanceId());
    Assertions.assertEquals(secondProcessInstance.getId(), runningHistoricActivityInstances.get(1).getProcessInstanceId());
    Assertions.assertEquals(thirdProcessInstance.getId(), runningHistoricActivityInstances.get(2).getProcessInstanceId());
  }

  public void shiftTimeByOneMinute() {
    Long oneMinute = 1000L * 60L;
    Date shiftedTimeByOneMinute = new Date(ClockUtil.getCurrentTime().getTime() + oneMinute);
    ClockUtil.setCurrentTime(shiftedTimeByOneMinute);
  }

  @Test
  public void fetchOnlyRunningActivities() {
    // given
    BpmnModelInstance simpleDefinition = Bpmn.createExecutableProcess("process")
      .startEvent("startEvent")
      .userTask("userTask")
      .endEvent()
      .done();
    testHelper.deploy(simpleDefinition);
    engineRule.getRuntimeService().startProcessInstanceByKey("process");

    // when
    List<HistoricActivityInstance> runningHistoricActivityInstances =
      optimizeService.getRunningHistoricActivityInstances(pastDate(), null, 10);

    // then
    Assertions.assertEquals(1, runningHistoricActivityInstances.size());
    Assertions.assertEquals("userTask", runningHistoricActivityInstances.get(0).getActivityId());

    // when
    completeAllUserTasks();
    runningHistoricActivityInstances =
      optimizeService.getRunningHistoricActivityInstances(pastDate(), null, 10);

    // then
    Assertions.assertEquals(0, runningHistoricActivityInstances.size());
  }

  private Date pastDate() {
    return new Date(2L);
  }

  // test fetches only completed, even if there are still running activities

  private void completeAllUserTasks() {
    List<Task> list = taskService.createTaskQuery().list();
    for (Task task : list) {
      taskService.claim(task.getId(), userId);
      taskService.complete(task.getId());
    }
  }

  protected void createUser(String userId) {
    User user = identityService.newUser(userId);
    identityService.saveUser(user);
  }

  private void assertThatActivitiesHaveAllImportantInformation(HistoricActivityInstance activityInstance) {
    Assertions.assertNotNull(activityInstance);
    Assertions.assertEquals("task", activityInstance.getActivityName());
    Assertions.assertEquals("userTask", activityInstance.getActivityType());
    Assertions.assertNotNull(activityInstance.getStartTime());
    Assertions.assertNull(activityInstance.getEndTime());
    Assertions.assertEquals("process", activityInstance.getProcessDefinitionKey());
    Assertions.assertNotNull(activityInstance.getProcessDefinitionId());
    Assertions.assertNotNull(((HistoryEvent) activityInstance).getSequenceCounter());
  }

}