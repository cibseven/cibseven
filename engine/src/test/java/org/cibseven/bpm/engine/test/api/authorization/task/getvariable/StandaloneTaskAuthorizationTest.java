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
package org.cibseven.bpm.engine.test.api.authorization.task.getvariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Arrays;
import java.util.Map;

import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.util.AuthorizationRuleExtension;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.engine.variable.VariableMap;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.TypedValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.runners.Parameterized.Parameter;

/**
 * @author Yana.Vasileva
 *
 */
@ExtendWith(AuthorizationRuleExtension.class)
public abstract class StandaloneTaskAuthorizationTest {

  public ProcessEngineRule engineRule;
  public AuthorizationTestRule authRule;

//  @Rule
//  public RuleChain chain = RuleChain.outerRule(engineRule).around(authRule);

  @Parameter
  public AuthorizationScenario scenario;

  protected ProcessEngineConfigurationImpl processEngineConfiguration;
  protected TaskService taskService;
  protected RuntimeService runtimeService;

  public static final String userId = "userId";
  public String taskId = "myTask";
  public static final String VARIABLE_NAME = "aVariableName";
  public static final String VARIABLE_VALUE = "aVariableValue";
  public static final String PROCESS_KEY = "oneTaskProcess";
  protected boolean ensureSpecificVariablePermission;

  @BeforeEach
  public void setUp() {
    processEngineConfiguration = engineRule.getProcessEngineConfiguration();
    taskService = engineRule.getTaskService();
    runtimeService = engineRule.getRuntimeService();

    authRule.createUserAndGroup("userId", "groupId");
  }

  @AfterEach
  public void tearDown() {
    authRule.deleteUsersAndGroups();
    taskService.deleteTask(taskId, true);
  }

  @Test
  public void testGetVariable() {
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
    }
  }

  @Test
  public void testGetVariableLocal() {
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
    }
  }

  @Test
  public void testGetVariableTyped() {
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
    }
  }

  @Test
  public void testGetVariableLocalTyped() {
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
    }
  }

  @Test
  public void testGetVariables() {
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
    }
  }

  @Test
  public void testGetVariablesLocal() {
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
    }
  }

  @Test
  public void testGetVariablesTyped() {
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
    }
  }

  @Test
  public void testGetVariablesLocalTyped() {
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
    }
  }

  @Test
  public void testGetVariablesByName() {
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
    }
  }

  @Test
  public void testGetVariablesLocalByName() {
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
    }
  }

  @Test
  public void testGetVariablesTypedByName() {
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
    }
  }

  @Test
  public void testGetVariablesLocalTypedByName() {
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
    }
  }

  protected void createTask(final String taskId) {
    Task task = taskService.newTask(taskId);
    taskService.saveTask(task);
  }

  protected VariableMap getVariables() {
    return Variables.createVariables().putValue(ProcessTaskAuthorizationTest.VARIABLE_NAME, ProcessTaskAuthorizationTest.VARIABLE_VALUE);
  }

  protected void verifyGetVariables(Map<String, Object> variables) {
    assertNotNull(variables);
    assertFalse(variables.isEmpty());
    assertEquals(1, variables.size());

    assertEquals(ProcessTaskAuthorizationTest.VARIABLE_VALUE, variables.get(ProcessTaskAuthorizationTest.VARIABLE_NAME));
  }

}
