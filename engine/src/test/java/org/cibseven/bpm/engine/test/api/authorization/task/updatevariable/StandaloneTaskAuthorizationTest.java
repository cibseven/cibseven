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
import static org.cibseven.bpm.engine.authorization.Resources.TASK;
import static org.cibseven.bpm.engine.authorization.TaskPermissions.UPDATE_VARIABLE;
import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario.scenario;
import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationSpec.grant;
import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationSpec.revoke;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.Collection;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.history.HistoricVariableInstance;
import org.cibseven.bpm.engine.impl.TaskServiceImpl;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.task.Task;
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
public class StandaloneTaskAuthorizationTest {


  @RegisterExtension
  @Order(1) public ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  @Order(2) public AuthorizationTestRule authRule = new AuthorizationTestRule(engineRule);

  protected ProcessEngineConfigurationImpl processEngineConfiguration;
  protected TaskService taskService;
  protected RuntimeService runtimeService;
  protected HistoryService historyService;

  protected static final String userId = "userId";
  protected String taskId = "myTask";
  protected static final String VARIABLE_NAME = "aVariableName";
  protected static final String VARIABLE_VALUE = "aVariableValue";
  protected static final String PROCESS_KEY = "oneTaskProcess";

  public static Collection<AuthorizationScenario[]> scenarios() {
    return AuthorizationTestRule.asParameters(
      scenario()
        .withoutAuthorizations()
        .failsDueToRequired(
          grant(TASK, "taskId", userId, UPDATE),
          grant(TASK, "taskId", userId, UPDATE_VARIABLE)),
      scenario()
        .withAuthorizations(
          grant(TASK, "taskId", userId, UPDATE)),
      scenario()
        .withAuthorizations(
          grant(TASK, "*", userId, UPDATE)),
      scenario()
        .withAuthorizations(
          grant(TASK, "taskId", userId, UPDATE_VARIABLE)),
      scenario()
        .withAuthorizations(
          grant(TASK, "*", userId, UPDATE_VARIABLE))
        .succeeds(),
      scenario()
        .withAuthorizations(
          grant(TASK, "*", "*", UPDATE),
          revoke(TASK, "taskId", userId, UPDATE))
        .failsDueToRequired(
          grant(TASK, "taskId", userId, UPDATE),
          grant(TASK, "taskId", userId, UPDATE_VARIABLE))
      );
  }

  @BeforeEach
  public void setUp() {
    processEngineConfiguration = engineRule.getProcessEngineConfiguration();
    taskService = engineRule.getTaskService();
    runtimeService = engineRule.getRuntimeService();
    historyService = engineRule.getHistoryService();

    authRule.createUserAndGroup("userId", "groupId");
  }

  @AfterEach
  public void tearDown() {
    authRule.deleteUsersAndGroups();
    taskService.deleteTask(taskId, true);
    for (HistoricVariableInstance var : historyService.createHistoricVariableInstanceQuery().includeDeleted().list()) {
      historyService.deleteHistoricVariableInstance(var.getId());
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testSetVariable(AuthorizationScenario scenario) {
    // given
    createTask(taskId);

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
    createTask(taskId);

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
    createTask(taskId);

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
    createTask(taskId);

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
    createTask(taskId);

    taskService.setVariable(taskId, VARIABLE_NAME, VARIABLE_VALUE);

    // when
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("taskId", taskId)
        .start();

    taskService.removeVariable(taskId, VARIABLE_NAME);

    // then
    if (authRule.assertScenario(scenario)) {
      verifyRemoveVariable();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  public void testRemoveVariableLocal(AuthorizationScenario scenario) {
    // given
    createTask(taskId);

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
      verifyRemoveVariable();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  public void testRemoveVariables(AuthorizationScenario scenario) {
    // given
    createTask(taskId);

    taskService.setVariable(taskId, VARIABLE_NAME, VARIABLE_VALUE);

    // when
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("taskId", taskId)
        .start();

    taskService.removeVariables(taskId, Arrays.asList(VARIABLE_NAME));

    // then
    if (authRule.assertScenario(scenario)) {
      verifyRemoveVariable();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  public void testRemoveVariablesLocal(AuthorizationScenario scenario) {
    // given
    createTask(taskId);

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
      verifyRemoveVariable();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  public void testUpdateVariablesAdd(AuthorizationScenario scenario) {
    // given
    createTask(taskId);

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
    createTask(taskId);
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
      verifyRemoveVariable();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  public void testUpdateVariablesAddRemove(AuthorizationScenario scenario) {
    // given
    createTask(taskId);

    // when
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("taskId", taskId)
        .start();

    ((TaskServiceImpl) taskService).updateVariables(taskId, getVariables(), Arrays.asList(VARIABLE_NAME));

    // then
    if (authRule.assertScenario(scenario)) {
      verifyRemoveVariable();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  public void testUpdateVariablesLocalAdd(AuthorizationScenario scenario) {
    // given
    createTask(taskId);

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
    createTask(taskId);
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
      verifyRemoveVariable();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  public void testUpdateVariablesLocalAddRemove(AuthorizationScenario scenario) {
    // given
    createTask(taskId);

    // when
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("taskId", taskId)
        .start();

    ((TaskServiceImpl) taskService).updateVariablesLocal(taskId, getVariables(), Arrays.asList(VARIABLE_NAME));

    // then
    if (authRule.assertScenario(scenario)) {
      verifyRemoveVariable();
    }
  }

  protected void verifySetVariables() {
    verifyVariableInstanceCount(1);
    assertNotNull(runtimeService.createVariableInstanceQuery().singleResult());
  }

  protected void verifyRemoveVariable() {
    verifyVariableInstanceCount(0);
    assertNull(runtimeService.createVariableInstanceQuery().singleResult());
    HistoricVariableInstance deletedVariable = historyService.createHistoricVariableInstanceQuery().includeDeleted().singleResult();
    Assertions.assertEquals("DELETED", deletedVariable.getState());
  }

  protected void verifyVariableInstanceCount(int count) {
    assertEquals(count, runtimeService.createVariableInstanceQuery().list().size());
    assertEquals(count, runtimeService.createVariableInstanceQuery().count());
  }

  protected void createTask(final String taskId) {
    Task task = taskService.newTask(taskId);
    taskService.saveTask(task);
  }

  protected VariableMap getVariables() {
    return Variables.createVariables().putValue(VARIABLE_NAME, VARIABLE_VALUE);
  }

}
