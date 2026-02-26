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
package org.cibseven.bpm.engine.test.api.authorization.history;

import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario.scenario;
import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationSpec.grant;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.authorization.Permissions;
import org.cibseven.bpm.engine.authorization.Resources;
import org.cibseven.bpm.engine.history.HistoricProcessInstance;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationTestRule;
import org.cibseven.bpm.engine.test.api.runtime.migration.models.ProcessModels;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * @author Askar Akhmerov
 */
@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_ACTIVITY)
public class DeleteHistoricProcessInstancesAuthorizationTest {

  protected static final String PROCESS_KEY = "oneTaskProcess";

  @RegisterExtension
  @Order(1) protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  @Order(2) protected AuthorizationTestRule authRule = new AuthorizationTestRule(engineRule);
  @RegisterExtension
  @Order(3) protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  protected ProcessInstance processInstance;
  protected ProcessInstance processInstance2;

  protected HistoricProcessInstance historicProcessInstance;
  protected HistoricProcessInstance historicProcessInstance2;

  protected RuntimeService runtimeService;
  protected HistoryService historyService;
  protected ManagementService managementService;

  @BeforeEach
  public void setUp() {
    authRule.createUserAndGroup("userId", "groupId");
    runtimeService = engineRule.getRuntimeService();
    managementService = engineRule.getManagementService();
    historyService = engineRule.getHistoryService();

    deployAndCompleteProcesses();
  }

  public void deployAndCompleteProcesses() {
    ProcessDefinition sourceDefinition = testRule.deployAndGetDefinition(ProcessModels.ONE_TASK_PROCESS);
    processInstance = engineRule.getRuntimeService().startProcessInstanceById(sourceDefinition.getId());
    processInstance2 = engineRule.getRuntimeService().startProcessInstanceById(sourceDefinition.getId());

    List<String> processInstanceIds = Arrays.asList(
        new String[]{processInstance.getId(), processInstance2.getId()});
    runtimeService.deleteProcessInstances(processInstanceIds, null, false, false);

    historicProcessInstance = historyService.createHistoricProcessInstanceQuery()
        .processInstanceId(processInstance.getId()).singleResult();

    historicProcessInstance2 = historyService.createHistoricProcessInstanceQuery()
        .processInstanceId(processInstance2.getId()).singleResult();
  }

  @AfterEach
  public void tearDown() {
    authRule.deleteUsersAndGroups();
  }

  public static List<AuthorizationScenario> scenarios() {
    return Arrays.asList(
      scenario()
        .withAuthorizations(
          grant(Resources.PROCESS_DEFINITION, "Process", "userId", Permissions.READ_HISTORY)
        )
        .failsDueToRequired(
          grant(Resources.PROCESS_DEFINITION, "Process", "userId", Permissions.DELETE_HISTORY)
        ),
      scenario()
        .withAuthorizations(
          grant(Resources.PROCESS_DEFINITION, "Process", "userId", Permissions.READ_HISTORY, Permissions.DELETE_HISTORY)
        )
        .succeeds()
    );
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void testProcessInstancesList(AuthorizationScenario scenario) {
    //given
    List<String> processInstanceIds = Arrays.asList(historicProcessInstance.getId(), historicProcessInstance2.getId());
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("processInstance1", processInstance.getId())
        .bindResource("processInstance2", processInstance2.getId())
        .start();

    // when
    historyService.deleteHistoricProcessInstances(processInstanceIds);

    // then
    if (authRule.assertScenario(scenario)) {
      assertThat(historyService.createHistoricProcessInstanceQuery().count()).isEqualTo(0L);
    }
  }
}