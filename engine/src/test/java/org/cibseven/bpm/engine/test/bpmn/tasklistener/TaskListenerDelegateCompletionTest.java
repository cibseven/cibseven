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
package org.cibseven.bpm.engine.test.bpmn.tasklistener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.TimeUnit;

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.delegate.TaskListener;
import org.cibseven.bpm.engine.impl.util.ClockUtil;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.task.TaskQuery;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationTestRule;
import org.cibseven.bpm.engine.test.util.AuthorizationRuleTripleExtension;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;


/**
 * @author Askar Akhmerov
 */
@ExtendWith(AuthorizationRuleTripleExtension.class)
public class TaskListenerDelegateCompletionTest {

  protected static final String COMPLETE_LISTENER = "org.cibseven.bpm.engine.test.bpmn.tasklistener.util.CompletingTaskListener";
  protected static final String TASK_LISTENER_PROCESS = "taskListenerProcess";
  protected static final String ACTIVITY_ID = "UT";

  protected ProcessEngineRule engineRule;
  protected AuthorizationTestRule authRule;
  protected ProcessEngineTestRule testRule;

//  @Rule
//  public RuleChain ruleChain = RuleChain.outerRule(engineRule).around(authRule).around(testRule);

  protected RuntimeService runtimeService;
  protected TaskService taskService;

  @BeforeEach
  public void setUp() {
    taskService = engineRule.getTaskService();
    runtimeService = engineRule.getRuntimeService();
  }

  @AfterEach
  public void cleanUp() {
    if (runtimeService.createProcessInstanceQuery().count() > 0) {
      runtimeService.deleteProcessInstance(runtimeService.createProcessInstanceQuery().singleResult().getId(),null,true);
    }
  }


  protected static BpmnModelInstance setupProcess(String eventName) {
    return Bpmn.createExecutableProcess(TASK_LISTENER_PROCESS)
        .startEvent()
          .userTask(ACTIVITY_ID)
          .camundaTaskListenerClass(eventName,COMPLETE_LISTENER)
        .endEvent()
        .done();
  }

  @Test
  public void testCompletionIsPossibleOnCreation () {
    //given
    createProcessWithListener(TaskListener.EVENTNAME_CREATE);

    //when
    runtimeService.startProcessInstanceByKey(TASK_LISTENER_PROCESS);

    //then
    Task task = taskService.createTaskQuery().singleResult();
    assertThat(task).isNull();
  }

  @Test
  public void testCompletionIsPossibleOnAssignment () {
    //given
    createProcessWithListener(TaskListener.EVENTNAME_ASSIGNMENT);

    //when
    runtimeService.startProcessInstanceByKey(TASK_LISTENER_PROCESS);
    Task task = taskService.createTaskQuery().singleResult();
    taskService.setAssignee(task.getId(),"test assignee");

    //then
    task = taskService.createTaskQuery().singleResult();
    assertThat(task).isNull();
  }

  @Test
  public void testCompletionIsPossibleAfterAssignmentUpdate () {
    //given
    createProcessWithListener(TaskListener.EVENTNAME_UPDATE);

    //when
    runtimeService.startProcessInstanceByKey(TASK_LISTENER_PROCESS);
    Task task = taskService.createTaskQuery().singleResult();
    taskService.setAssignee(task.getId(),"test assignee");

    //then
    task = taskService.createTaskQuery().singleResult();
    assertThat(task).isNull();
  }

  @Test
  public void testCompletionIsPossibleAfterPropertyUpdate () {
    //given
    createProcessWithListener(TaskListener.EVENTNAME_UPDATE);

    //when
    runtimeService.startProcessInstanceByKey(TASK_LISTENER_PROCESS);
    Task task = taskService.createTaskQuery().singleResult();
    taskService.setOwner(task.getId(),"ownerId");

    //then
    task = taskService.createTaskQuery().singleResult();
    assertThat(task).isNull();
  }

  @Test
  @Deployment
  public void testCompletionIsPossibleOnTimeout() {
    TaskQuery taskQuery = taskService.createTaskQuery();

    // given
    runtimeService.startProcessInstanceByKey("process");

    // assume
    assertThat(taskQuery.count()).isEqualTo(1L);

    // when
    ClockUtil.offset(TimeUnit.MINUTES.toMillis(70L));
    testRule.waitForJobExecutorToProcessAllJobs(5000L);

    // then
    assertThat(taskQuery.count()).isEqualTo(0L);
  }

  @Test
  public void testCompletionIsNotPossibleOnComplete () {
    //given
    createProcessWithListener(TaskListener.EVENTNAME_COMPLETE);

    runtimeService.startProcessInstanceByKey(TASK_LISTENER_PROCESS);
    Task task = taskService.createTaskQuery().singleResult();

    // when/then
    assertThatThrownBy(() -> taskService.complete(task.getId()))
      .isInstanceOf(ProcessEngineException.class)
      .hasMessageContaining("invalid task state");
  }

  @Test
  public void testCompletionIsNotPossibleOnDelete () {

    //given
    createProcessWithListener(TaskListener.EVENTNAME_DELETE);
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey(TASK_LISTENER_PROCESS);

    // when/then
    assertThatThrownBy(() -> runtimeService.deleteProcessInstance(processInstance.getId(),"test reason"))
      .isInstanceOf(ProcessEngineException.class)
      .hasMessageContaining("invalid task state");
  }

  protected void createProcessWithListener(String eventName) {
    BpmnModelInstance bpmnModelInstance = setupProcess(eventName);
    testRule.deploy(bpmnModelInstance);
  }

}
