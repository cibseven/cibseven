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
package org.cibseven.bpm.qa.rolling.update.authorization;

import java.util.Arrays;
import java.util.List;
import org.cibseven.bpm.engine.FormService;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.history.HistoricActivityInstance;
import org.cibseven.bpm.engine.history.HistoricProcessInstance;
import org.cibseven.bpm.engine.history.HistoricTaskInstance;
import org.cibseven.bpm.engine.repository.Deployment;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.runtime.Execution;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.qa.rolling.update.AbstractRollingUpdateTestCase;
import org.cibseven.bpm.qa.upgrade.ScenarioUnderTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
/**
 *
 * @author Christopher Zell <christopher.zell@camunda.com>
 */
@ScenarioUnderTest("AuthorizationScenario")
public class AuthorizationTest extends AbstractRollingUpdateTestCase {

  public static final String PROCESS_DEF_KEY = "oneTaskProcess";
  protected static final String USER_ID = "user";
  protected static final String GROUP_ID = "group";

  protected IdentityService identityService;
  protected RepositoryService repositoryService;
  protected RuntimeService runtimeService;
  protected TaskService taskService;
  protected HistoryService historyService;
  protected FormService formService;

  @BeforeEach
  public void setUp() {
    identityService = rule.getIdentityService();
    repositoryService = rule.getRepositoryService();
    runtimeService = rule.getRuntimeService();
    taskService = rule.getTaskService();
    historyService = rule.getHistoryService();
    formService = rule.getFormService();

    identityService.clearAuthentication();
    identityService.setAuthentication(USER_ID + rule.getBuisnessKey(), Arrays.asList(GROUP_ID + rule.getBuisnessKey()));
  }

  @AfterEach
  public void cleanUp() {
    identityService.clearAuthentication();
  }

  @Test
  @ScenarioUnderTest("startProcessInstance.1")
  public void testAuthorization() {
    //test access process related
    testGetDeployment();
    testGetProcessDefinition();
    testGetProcessInstance();
    testGetExecution();
    testGetTask();

    //test access historic
    testGetHistoricProcessInstance();
    testGetHistoricActivityInstance();
    testGetHistoricTaskInstance();

    //test process modification
    testSetVariable();
    testSubmitStartForm();
    testStartProcessInstance();
    testCompleteTaskInstance();
    testSubmitTaskForm();
  }


  public void testGetDeployment() {
    List<Deployment> deployments = repositoryService.createDeploymentQuery().list();
    assertThat(deployments.isEmpty()).isFalse();
  }

  public void testGetProcessDefinition() {
    List<ProcessDefinition> definitions = repositoryService
        .createProcessDefinitionQuery()
        .processDefinitionKey(PROCESS_DEF_KEY)
        .list();
    assertThat(definitions.isEmpty()).isFalse();
  }

  public void testGetProcessInstance() {
    List<ProcessInstance> instances = runtimeService
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(rule.getBuisnessKey())
        .processDefinitionKey(PROCESS_DEF_KEY)
        .list();
    assertThat(instances.isEmpty()).isFalse();
  }

  public void testGetExecution() {
    List<Execution> executions = runtimeService
        .createExecutionQuery()
        .processInstanceBusinessKey(rule.getBuisnessKey())
        .processDefinitionKey(PROCESS_DEF_KEY)
        .list();
    assertThat(executions.isEmpty()).isFalse();
  }

  public void testGetTask() {
    List<Task> tasks = taskService
        .createTaskQuery()
        .processInstanceBusinessKey(rule.getBuisnessKey())
        .processDefinitionKey(PROCESS_DEF_KEY)
        .list();
    assertThat(tasks.isEmpty()).isFalse();
  }

  public void testGetHistoricProcessInstance() {
    List<HistoricProcessInstance> instances= historyService
        .createHistoricProcessInstanceQuery()
        .processInstanceBusinessKey(rule.getBuisnessKey())
        .processDefinitionKey(PROCESS_DEF_KEY)
        .list();
    assertThat(instances.isEmpty()).isFalse();
  }

  public void testGetHistoricActivityInstance() {
    List<HistoricActivityInstance> instances= historyService
        .createHistoricActivityInstanceQuery()
        .list();
    assertThat(instances.isEmpty()).isFalse();
  }

  public void testGetHistoricTaskInstance() {
    List<HistoricTaskInstance> instances= historyService
        .createHistoricTaskInstanceQuery()
        .processDefinitionKey(PROCESS_DEF_KEY)
        .list();
    assertThat(instances.isEmpty()).isFalse();
  }

  public void testStartProcessInstance() {
    ProcessInstance instance = runtimeService.startProcessInstanceByKey(PROCESS_DEF_KEY, rule.getBuisnessKey());
    assertThat(instance).isNotNull();
  }

  public void testSubmitStartForm() {
    ProcessInstance instance = formService.submitStartForm(rule.processInstance().getProcessDefinitionId(), rule.getBuisnessKey(), null);
    assertThat(instance).isNotNull();
  }

  public void testCompleteTaskInstance() {
    String taskId = taskService
        .createTaskQuery()
        .processDefinitionKey(PROCESS_DEF_KEY)
        .processInstanceBusinessKey(rule.getBuisnessKey())
        .listPage(0, 1)
        .get(0)
        .getId();
    taskService.complete(taskId);
  }

  public void testSubmitTaskForm() {
    String taskId = taskService
        .createTaskQuery()
        .processDefinitionKey(PROCESS_DEF_KEY)
        .processInstanceBusinessKey(rule.getBuisnessKey())
        .listPage(0, 1)
        .get(0)
        .getId();
    formService.submitTaskForm(taskId, null);
  }

  public void testSetVariable() {
    String processInstanceId = runtimeService
        .createProcessInstanceQuery()
        .processDefinitionKey(PROCESS_DEF_KEY)
        .listPage(0, 1)
        .get(0)
        .getId();
    runtimeService.setVariable(processInstanceId, "abc", "def");
  }
}
