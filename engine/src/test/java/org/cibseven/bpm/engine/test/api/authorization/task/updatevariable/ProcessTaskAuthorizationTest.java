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
package org.cibseven.bpm.engine.test.api.authorization.task.updatevariable;

import static org.cibseven.bpm.engine.authorization.Permissions.UPDATE;
import static org.cibseven.bpm.engine.authorization.Permissions.UPDATE_TASK;
import static org.cibseven.bpm.engine.authorization.ProcessDefinitionPermissions.UPDATE_TASK_VARIABLE;
import static org.cibseven.bpm.engine.authorization.Resources.PROCESS_DEFINITION;
import static org.cibseven.bpm.engine.authorization.Resources.TASK;
import static org.cibseven.bpm.engine.authorization.TaskPermissions.UPDATE_VARIABLE;
import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario.scenario;
import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationSpec.grant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.Collection;

import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.history.HistoricVariableInstance;
import org.cibseven.bpm.engine.impl.TaskServiceImpl;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.engine.variable.VariableMap;
import org.cibseven.bpm.engine.variable.Variables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * @author Yana.Vasileva
 *
 */
public class ProcessTaskAuthorizationTest {

  private static final String ONE_TASK_PROCESS = "org/cibseven/bpm/engine/test/api/oneTaskProcess.bpmn20.xml";
  protected static final String userId = "userId";
  protected static final String VARIABLE_NAME = "aVariableName";
  protected static final String VARIABLE_VALUE = "aVariableValue";
  protected static final String PROCESS_KEY = "oneTaskProcess";

  @RegisterExtension
  @Order(1) public ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  @Order(2) public AuthorizationTestRule authRule = new AuthorizationTestRule(engineRule);

  protected ProcessEngineConfigurationImpl processEngineConfiguration;
  protected TaskService taskService;
  protected RuntimeService runtimeService;

  protected boolean ensureSpecificVariablePermission;
  protected String deploumentId;

  public static Collection<AuthorizationScenario[]> scenarios() {
    return AuthorizationTestRule.asParameters(
      scenario()
        .withoutAuthorizations()
        .failsDueToRequired(
          grant(TASK, "taskId", userId, UPDATE),
          grant(PROCESS_DEFINITION, PROCESS_KEY, userId, UPDATE_TASK),
          grant(TASK, "taskId", userId, UPDATE_VARIABLE),
          grant(PROCESS_DEFINITION, PROCESS_KEY, userId, UPDATE_TASK_VARIABLE)),
      scenario()
        .withAuthorizations(
          grant(TASK, "taskId", userId, UPDATE)),
      scenario()
        .withAuthorizations(
          grant(TASK, "*", userId, UPDATE)),
      scenario()
        .withAuthorizations(
          grant(PROCESS_DEFINITION, PROCESS_KEY, userId, UPDATE_TASK)),
      scenario()
        .withAuthorizations(
          grant(PROCESS_DEFINITION, "*", userId, UPDATE_TASK)),
      scenario()
        .withAuthorizations(
          grant(TASK, "taskId", userId, UPDATE_VARIABLE)),
      scenario()
        .withAuthorizations(
          grant(TASK, "*", userId, UPDATE_VARIABLE)),
      scenario()
        .withAuthorizations(
          grant(PROCESS_DEFINITION, PROCESS_KEY, userId, UPDATE_TASK_VARIABLE)),
      scenario()
        .withAuthorizations(
          grant(PROCESS_DEFINITION, "*", userId, UPDATE_TASK_VARIABLE))
        .succeeds()
      );
  }

  @BeforeEach
  public void setUp() {
    processEngineConfiguration = engineRule.getProcessEngineConfiguration();
    taskService = engineRule.getTaskService();
    runtimeService = engineRule.getRuntimeService();

    authRule.createUserAndGroup("userId", "groupId");
    deploumentId = engineRule.getRepositoryService().createDeployment().addClasspathResource(ONE_TASK_PROCESS).deployWithResult().getId();
    ensureSpecificVariablePermission = processEngineConfiguration.isEnforceSpecificVariablePermission();

    // prerequisite of the whole test suite
    processEngineConfiguration.setEnforceSpecificVariablePermission(true);
  }

  @AfterEach
  public void tearDown() {
    authRule.deleteUsersAndGroups();
    processEngineConfiguration.setEnforceSpecificVariablePermission(ensureSpecificVariablePermission);
    engineRule.getRepositoryService().deleteDeployment(deploumentId, true);
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testSetVariable(AuthorizationScenario scenario) {
    // given
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    String taskId = taskService.createTaskQuery().singleResult().getId();

    // when
    authRule
      .init(scenario)
      .withUser("userId")
      .bindResource("taskId", taskId)
      .start();

    taskService.setVariable(taskId, VARIABLE_NAME, VARIABLE_VALUE);

    // then
    if (authRule.assertScenario(scenario)) {
      verifySetVariables();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testSetVariableLocal(AuthorizationScenario scenario) {
    // given
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    String taskId = taskService.createTaskQuery().singleResult().getId();

    // when
    authRule
      .init(scenario)
      .withUser("userId")
      .bindResource("taskId", taskId)
      .start();

    taskService.setVariableLocal(taskId, VARIABLE_NAME, VARIABLE_VALUE);

    // then
    if (authRule.assertScenario(scenario)) {
      verifySetVariables();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testSetVariables(AuthorizationScenario scenario) {
    // given
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    String taskId = taskService.createTaskQuery().singleResult().getId();

    // when
    authRule
      .init(scenario)
      .withUser("userId")
      .bindResource("taskId", taskId)
      .start();

    taskService.setVariables(taskId, getVariables());

    // then
    if (authRule.assertScenario(scenario)) {
      verifySetVariables();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testSetVariablesLocal(AuthorizationScenario scenario) {
    // given
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    String taskId = taskService.createTaskQuery().singleResult().getId();

    // when
    authRule
      .init(scenario)
      .withUser("userId")
      .bindResource("taskId", taskId)
      .start();

    taskService.setVariablesLocal(taskId, getVariables());

    // then
    if (authRule.assertScenario(scenario)) {
      verifySetVariables();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  public void testRemoveVariable(AuthorizationScenario scenario) {
    // given
    runtimeService.startProcessInstanceByKey(PROCESS_KEY, getVariables());
    String taskId = taskService.createTaskQuery().singleResult().getId();

    // when
    authRule
      .init(scenario)
      .withUser("userId")
      .bindResource("taskId", taskId)
      .start();

    taskService.removeVariable(taskId, VARIABLE_NAME);

    // then
    if (authRule.assertScenario(scenario)) {
      verifyRemoveVariables();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  public void testRemoveVariableLocal(AuthorizationScenario scenario) {
    // given
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    String taskId = taskService.createTaskQuery().singleResult().getId();

    taskService.setVariableLocal(taskId, VARIABLE_NAME, VARIABLE_VALUE);

    // when
    authRule
      .init(scenario)
      .withUser("userId")
      .bindResource("taskId", taskId)
      .start();

    taskService.removeVariableLocal(taskId, VARIABLE_NAME);

    // then
    if (authRule.assertScenario(scenario)) {
      verifyRemoveVariables();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  public void testRemoveVariables(AuthorizationScenario scenario) {
    // given
    runtimeService.startProcessInstanceByKey(PROCESS_KEY, getVariables());
    String taskId = taskService.createTaskQuery().singleResult().getId();

    // when
    authRule
      .init(scenario)
      .withUser("userId")
      .bindResource("taskId", taskId)
      .start();

    taskService.removeVariables(taskId, Arrays.asList(VARIABLE_NAME));

    // then
    if (authRule.assertScenario(scenario)) {
      verifyRemoveVariables();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  public void testRemoveVariablesLocal(AuthorizationScenario scenario) {
    // given
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    String taskId = taskService.createTaskQuery().singleResult().getId();

    taskService.setVariableLocal(taskId, VARIABLE_NAME, VARIABLE_VALUE);

    // when
    authRule
      .init(scenario)
      .withUser("userId")
      .bindResource("taskId", taskId)
      .start();

    taskService.removeVariablesLocal(taskId, Arrays.asList(VARIABLE_NAME));

    // then
    if (authRule.assertScenario(scenario)) {
      verifyRemoveVariables();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  public void testUpdateVariablesAdd(AuthorizationScenario scenario) {
    // given
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    String taskId = taskService.createTaskQuery().singleResult().getId();

    // when
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("taskId", taskId)
        .start();

    ((TaskServiceImpl) taskService).updateVariables(taskId, getVariables(), null);

    // then
    if (authRule.assertScenario(scenario)) {
      verifySetVariables();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  public void testUpdateVariablesRemove(AuthorizationScenario scenario) {
    // given
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    String taskId = taskService.createTaskQuery().singleResult().getId();
    taskService.setVariable(taskId, VARIABLE_NAME, VARIABLE_VALUE);

    // when
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("taskId", taskId)
        .start();

    ((TaskServiceImpl) taskService).updateVariables(taskId, null, Arrays.asList(VARIABLE_NAME));

    // then
    if (authRule.assertScenario(scenario)) {
      verifyRemoveVariables();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  public void testUpdateVariablesAddRemove(AuthorizationScenario scenario) {
    // given
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    String taskId = taskService.createTaskQuery().singleResult().getId();

    // when
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("taskId", taskId)
        .start();

    ((TaskServiceImpl) taskService).updateVariables(taskId, getVariables(), Arrays.asList(VARIABLE_NAME));

    // then
    if (authRule.assertScenario(scenario)) {
      verifyRemoveVariables();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  public void testUpdateVariablesLocalAdd(AuthorizationScenario scenario) {
    // given
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    String taskId = taskService.createTaskQuery().singleResult().getId();

    // when
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("taskId", taskId)
        .start();

    ((TaskServiceImpl) taskService).updateVariablesLocal(taskId, getVariables(), null);

    // then
    if (authRule.assertScenario(scenario)) {
      verifySetVariables();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  public void testUpdateVariablesLocalRemove(AuthorizationScenario scenario) {
    // given
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    String taskId = taskService.createTaskQuery().singleResult().getId();
    taskService.setVariableLocal(taskId, VARIABLE_NAME, VARIABLE_VALUE);

    // when
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("taskId", taskId)
        .start();

    ((TaskServiceImpl) taskService).updateVariablesLocal(taskId, null, Arrays.asList(VARIABLE_NAME));

    // then
    if (authRule.assertScenario(scenario)) {
      verifyRemoveVariables();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  public void testUpdateVariablesLocalAddRemove(AuthorizationScenario scenario) {
    // given
    runtimeService.startProcessInstanceByKey(PROCESS_KEY);
    String taskId = taskService.createTaskQuery().singleResult().getId();

    // when
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("taskId", taskId)
        .start();

    ((TaskServiceImpl) taskService).updateVariablesLocal(taskId, getVariables(), Arrays.asList(VARIABLE_NAME));

    // then
    if (authRule.assertScenario(scenario)) {
      verifyRemoveVariables();
    }
  }

  protected void verifySetVariables() {
    verifyVariableInstanceCount(1);
    assertNotNull(runtimeService.createVariableInstanceQuery().singleResult());
  }

  protected void verifyRemoveVariables() {
    verifyVariableInstanceCount(0);
    assertNull(runtimeService.createVariableInstanceQuery().singleResult());
    HistoricVariableInstance deletedVariable = engineRule.getHistoryService().createHistoricVariableInstanceQuery().includeDeleted().singleResult();
    Assertions.assertEquals("DELETED", deletedVariable.getState());
  }

  protected void verifyVariableInstanceCount(int count) {
    assertEquals(count, runtimeService.createVariableInstanceQuery().list().size());
    assertEquals(count, runtimeService.createVariableInstanceQuery().count());
  }

  protected VariableMap getVariables() {
    return Variables.createVariables().putValue(VARIABLE_NAME, VARIABLE_VALUE);
  }

}