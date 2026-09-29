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
package org.cibseven.bpm.engine.test.api.authorization;

import static org.cibseven.bpm.engine.authorization.Resources.TASK;
import static org.cibseven.bpm.engine.authorization.TaskPermissions.READ_VARIABLE;
import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario.scenario;
import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationSpec.grant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

import org.cibseven.bpm.engine.AuthorizationService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.authorization.Authorization;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.engine.variable.VariableMap;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.TypedValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * @author Yana.Vasileva
 *
 */
public class StandaloneTaskGetVariableAuthorizationTest {

  @RegisterExtension
  @Order(1) public ProcessEngineRule engineRule = new ProvidedProcessEngineRule();

  @RegisterExtension
  @Order(2) public AuthorizationTestRule authRule = new AuthorizationTestRule(engineRule);

  protected ProcessEngineConfigurationImpl processEngineConfiguration;
  protected TaskService taskService;
  protected RuntimeService runtimeService;

  protected static final String userId = "userId";
  protected String taskId = "myTask";
  protected static final String VARIABLE_NAME = "aVariableName";
  protected static final String VARIABLE_VALUE = "aVariableValue";
  protected static final String PROCESS_KEY = "oneTaskProcess";
  protected boolean ensureSpecificVariablePermission;

  public static Collection<AuthorizationScenario[]> scenarios() {
    return AuthorizationTestRule.asParameters(
      scenario()
        .withoutAuthorizations()
        .failsDueToRequired(
          grant(TASK, "taskId", userId, READ_VARIABLE)),
      scenario()
        .withAuthorizations(
          grant(TASK, "taskId", userId, READ_VARIABLE)),
      scenario()
        .withAuthorizations(
          grant(TASK, "*", userId, READ_VARIABLE))
        .succeeds()
      );
  }

  @BeforeEach
  public void setUp() {
    processEngineConfiguration = engineRule.getProcessEngineConfiguration();
    taskService = engineRule.getTaskService();
    runtimeService = engineRule.getRuntimeService();

    authRule.createUserAndGroup("userId", "groupId");
    ensureSpecificVariablePermission = processEngineConfiguration.isEnforceSpecificVariablePermission();
    // prerequisite of the whole test suite
    processEngineConfiguration.setEnforceSpecificVariablePermission(true);
  }

  @AfterEach
  public void tearDown() {
    authRule.deleteUsersAndGroups();
    taskService.deleteTask(taskId, true);
    processEngineConfiguration.setEnforceSpecificVariablePermission(ensureSpecificVariablePermission);
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testGetVariable(AuthorizationScenario scenario) {
    // given
    createTask(taskId);

    taskService.setVariables(taskId, getVariables());

    // when
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("taskId", taskId)
        .start();

    Object variable = taskService.getVariable(taskId, VARIABLE_NAME);

    // then
    if (authRule.assertScenario(scenario)) {
      assertEquals(VARIABLE_VALUE, variable);
      deleteAuthorizations();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testGetVariableLocal(AuthorizationScenario scenario) {
    // given
    createTask(taskId);

    taskService.setVariablesLocal(taskId, getVariables());

    // when
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("taskId", taskId)
        .start();

    Object variable = taskService.getVariableLocal(taskId, VARIABLE_NAME);

    // then
    if (authRule.assertScenario(scenario)) {
      assertEquals(VARIABLE_VALUE, variable);
      deleteAuthorizations();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testGetVariableTyped(AuthorizationScenario scenario) {
    // given
    createTask(taskId);

    taskService.setVariables(taskId, getVariables());

    // when
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("taskId", taskId)
        .start();

    TypedValue typedValue = taskService.getVariableTyped(taskId, VARIABLE_NAME);

    // then
    if (authRule.assertScenario(scenario)) {
      assertNotNull(typedValue);
      assertEquals(VARIABLE_VALUE, typedValue.getValue());
      deleteAuthorizations();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testGetVariableLocalTyped(AuthorizationScenario scenario) {
    // given
    createTask(taskId);

    taskService.setVariablesLocal(taskId, getVariables());

    // when
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("taskId", taskId)
        .start();

    TypedValue typedValue = taskService.getVariableLocalTyped(taskId, VARIABLE_NAME);

    // then
    if (authRule.assertScenario(scenario)) {
      assertNotNull(typedValue);
      assertEquals(VARIABLE_VALUE, typedValue.getValue());
      deleteAuthorizations();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testGetVariables(AuthorizationScenario scenario) {
    // given
    createTask(taskId);

    taskService.setVariables(taskId, getVariables());

    // when
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("taskId", taskId)
        .start();

    Map<String, Object> variables = taskService.getVariables(taskId);

    // then
    if (authRule.assertScenario(scenario)) {
      verifyGetVariables(variables);

      deleteAuthorizations();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testGetVariablesLocal(AuthorizationScenario scenario) {
    // given
    createTask(taskId);

    taskService.setVariablesLocal(taskId, getVariables());

    // when
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("taskId", taskId)
        .start();

    Map<String, Object> variables = taskService.getVariablesLocal(taskId);

    // then
    if (authRule.assertScenario(scenario)) {
      verifyGetVariables(variables);

      deleteAuthorizations();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testGetVariablesTyped(AuthorizationScenario scenario) {
    createTask(taskId);

    taskService.setVariables(taskId, getVariables());

    // when
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("taskId", taskId)
        .start();

    VariableMap variables = taskService.getVariablesTyped(taskId);

    // then
    if (authRule.assertScenario(scenario)) {
      verifyGetVariables(variables);

      deleteAuthorizations();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testGetVariablesLocalTyped(AuthorizationScenario scenario) {
    createTask(taskId);

    taskService.setVariablesLocal(taskId, getVariables());

    // when
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("taskId", taskId)
        .start();

    VariableMap variables = taskService.getVariablesLocalTyped(taskId);

    // then
    if (authRule.assertScenario(scenario)) {
      verifyGetVariables(variables);

      deleteAuthorizations();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testGetVariablesByName(AuthorizationScenario scenario) {
    // given
    createTask(taskId);

    taskService.setVariables(taskId, getVariables());

    // when
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("taskId", taskId)
        .start();

    Map<String, Object> variables = taskService.getVariables(taskId, Arrays.asList(VARIABLE_NAME));

    // then
    if (authRule.assertScenario(scenario)) {
      verifyGetVariables(variables);

      deleteAuthorizations();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testGetVariablesLocalByName(AuthorizationScenario scenario) {
    // given
    createTask(taskId);

    taskService.setVariablesLocal(taskId, getVariables());

    // when
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("taskId", taskId)
        .start();

    Map<String, Object> variables = taskService.getVariablesLocal(taskId, Arrays.asList(VARIABLE_NAME));

    // then
    if (authRule.assertScenario(scenario)) {
      verifyGetVariables(variables);

      deleteAuthorizations();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testGetVariablesTypedByName(AuthorizationScenario scenario) {
    createTask(taskId);

    taskService.setVariables(taskId, getVariables());

    // when
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("taskId", taskId)
        .start();

    VariableMap variables = taskService.getVariablesTyped(taskId, Arrays.asList(VARIABLE_NAME), false);

    // then
    if (authRule.assertScenario(scenario)) {
      verifyGetVariables(variables);

      deleteAuthorizations();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testGetVariablesLocalTypedByName(AuthorizationScenario scenario) {
    createTask(taskId);

    taskService.setVariablesLocal(taskId, getVariables());

    // when
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("taskId", taskId)
        .start();

    VariableMap variables = taskService.getVariablesLocalTyped(taskId, Arrays.asList(VARIABLE_NAME), false);

    // then
    if (authRule.assertScenario(scenario)) {
      verifyGetVariables(variables);

      deleteAuthorizations();
    }
  }

  protected void createTask(final String taskId) {
    Task task = taskService.newTask(taskId);
    taskService.saveTask(task);
  }

  protected VariableMap getVariables() {
    return Variables.createVariables().putValue(VARIABLE_NAME, VARIABLE_VALUE);
  }

  protected void deleteAuthorizations() {
    AuthorizationService authorizationService = engineRule.getAuthorizationService();
    for (Authorization authorization : authorizationService.createAuthorizationQuery().list()) {
      authorizationService.deleteAuthorization(authorization.getId());
    }
  }

  protected void verifyGetVariables(Map<String, Object> variables) {
    assertNotNull(variables);
    assertFalse(variables.isEmpty());
    assertEquals(1, variables.size());
    assertEquals(VARIABLE_VALUE, variables.get(VARIABLE_NAME));
  }

}
