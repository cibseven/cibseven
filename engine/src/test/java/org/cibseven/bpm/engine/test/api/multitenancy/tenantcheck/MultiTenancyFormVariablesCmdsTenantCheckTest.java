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
package org.cibseven.bpm.engine.test.api.multitenancy.tenantcheck;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.engine.variable.Variables;
import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Order;


/**
 *
 * @author Deivarayan Azhagappan
 *
 */

public class MultiTenancyFormVariablesCmdsTenantCheckTest {

  protected static final String TENANT_ONE = "tenant1";

  protected static final String PROCESS_DEFINITION_KEY = "testProcess";

  protected static final String VARIABLE_1 = "testVariable1";
  protected static final String VARIABLE_2 = "testVariable2";

  protected static final String VARIABLE_VALUE_1 = "test1";
  protected static final String VARIABLE_VALUE_2 = "test2";
  @RegisterExtension
  @Order(4) protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  @Order(9) protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  protected ProcessInstance instance;

  protected static final String START_FORM_RESOURCE = "org/cibseven/bpm/engine/test/api/form/FormServiceTest.startFormFields.bpmn20.xml";

  @BeforeEach
  public void init() {

    // deploy tenants
    testRule.deployForTenant(TENANT_ONE, START_FORM_RESOURCE);
    instance = engineRule.getRuntimeService()
    .startProcessInstanceByKey(PROCESS_DEFINITION_KEY, Variables.createVariables().putValue(VARIABLE_1, VARIABLE_VALUE_1)
         .putValue(VARIABLE_2, VARIABLE_VALUE_2));
  }

  // start form variables
  @Test
  public void testGetStartFormVariablesWithAuthenticatedTenant() {

    engineRule.getIdentityService().setAuthentication("aUserId", null, Arrays.asList(TENANT_ONE));

    assertEquals(4, engineRule.getFormService().getStartFormVariables(instance.getProcessDefinitionId()).size());

  }

  @Test
  public void testGetStartFormVariablesWithNoAuthenticatedTenant() {

    engineRule.getIdentityService().setAuthentication("aUserId", null);

    // when/then
    assertThatThrownBy(() -> engineRule.getFormService().getStartFormVariables(instance.getProcessDefinitionId()))
      .isInstanceOf(ProcessEngineException.class)
      .hasMessageContaining("Cannot get the process definition '"
          + instance.getProcessDefinitionId() +"' because it belongs to no authenticated tenant.");

  }

  @Test
  public void testGetStartFormVariablesWithDisabledTenantCheck() {

    engineRule.getIdentityService().setAuthentication("aUserId", null);
    engineRule.getProcessEngineConfiguration().setTenantCheckEnabled(false);

    assertEquals(4, engineRule.getFormService().getStartFormVariables(instance.getProcessDefinitionId()).size());

  }

  @Test
  public void testGetTaskFormVariablesWithAuthenticatedTenant() {

    engineRule.getIdentityService().setAuthentication("aUserId", null, Arrays.asList(TENANT_ONE));

    Task task = engineRule.getTaskService().createTaskQuery().singleResult();

    assertEquals(2, engineRule.getFormService().getTaskFormVariables(task.getId()).size());

  }

  @Test
  public void testGetTaskFormVariablesWithNoAuthenticatedTenant() {

    Task task = engineRule.getTaskService().createTaskQuery().singleResult();

    engineRule.getIdentityService().setAuthentication("aUserId", null);

    // when/then
    assertThatThrownBy(() -> engineRule.getFormService().getTaskFormVariables(task.getId()))
      .isInstanceOf(ProcessEngineException.class)
      .hasMessageContaining("Cannot read the task '"
          + task.getId() +"' because it belongs to no authenticated tenant.");

  }

  @Test
  public void testGetTaskFormVariablesWithDisabledTenantCheck() {

    Task task = engineRule.getTaskService().createTaskQuery().singleResult();

    engineRule.getIdentityService().setAuthentication("aUserId", null);
    engineRule.getProcessEngineConfiguration().setTenantCheckEnabled(false);

    assertEquals(2, engineRule.getFormService().getTaskFormVariables(task.getId()).size());

  }
}
