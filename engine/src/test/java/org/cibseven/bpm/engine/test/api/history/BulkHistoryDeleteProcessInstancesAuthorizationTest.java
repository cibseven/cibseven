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
package org.cibseven.bpm.engine.test.api.history;

import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario.scenario;
import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationSpec.grant;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.authorization.Permissions;
import org.cibseven.bpm.engine.authorization.Resources;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationTestRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.engine.variable.VariableMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * @author Svetlana Dorokhova
 */
@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
public class BulkHistoryDeleteProcessInstancesAuthorizationTest {

  protected static final String ONE_TASK_PROCESS = "oneTaskProcess";

  @RegisterExtension
  @Order(1) protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  @Order(2) protected AuthorizationTestRule authRule = new AuthorizationTestRule(engineRule);
  @RegisterExtension
  @Order(3) protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  private HistoryService historyService;
  private RuntimeService runtimeService;

  @BeforeEach
  public void init() {
    runtimeService = engineRule.getRuntimeService();
    historyService = engineRule.getHistoryService();

    authRule.createUserAndGroup("demo", "groupId");
  }


  public static Collection<AuthorizationScenario[]> scenarios() {
    return AuthorizationTestRule.asParameters(
        scenario()
            .failsDueToRequired(
                grant(Resources.PROCESS_DEFINITION, "processDefinition", "demo", Permissions.DELETE_HISTORY)
            )
                ,
        scenario()
            .withAuthorizations(
                grant(Resources.PROCESS_DEFINITION, "processDefinition", "demo", Permissions.DELETE_HISTORY)
            )
            .succeeds(),
        scenario()
            .withAuthorizations(
                grant(Resources.PROCESS_DEFINITION, "*", "demo", Permissions.DELETE_HISTORY)
            )
            .succeeds()
    );
  }

  @AfterEach
  public void tearDown() {
    authRule.deleteUsersAndGroups();
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @Deployment(resources = {
      "org/cibseven/bpm/engine/test/api/oneTaskProcess.bpmn20.xml"})
  public void testCleanupHistory(AuthorizationScenario scenario) {
    //given
    final List<String> ids = prepareHistoricProcesses();
    runtimeService.deleteProcessInstances(ids, null, true, true);

    // when
    authRule
        .init(scenario)
        .bindResource("processDefinition", "oneTaskProcess")
        .withUser("demo")
        .start();

    //when
    historyService.deleteHistoricProcessInstancesBulk(ids);

    if (authRule.assertScenario(scenario)) {
      assertEquals(0, historyService.createHistoricProcessInstanceQuery().processDefinitionKey(ONE_TASK_PROCESS).count());
    }

  }

  private List<String> prepareHistoricProcesses() {
    return prepareHistoricProcesses(ONE_TASK_PROCESS);
  }

  private List<String> prepareHistoricProcesses(String businessKey) {
    return prepareHistoricProcesses(businessKey, null);
  }

  private List<String> prepareHistoricProcesses(String businessKey, VariableMap variables) {
    List<String> processInstanceIds = new ArrayList<String>();

    for (int i = 0; i < 5; i++) {
      ProcessInstance processInstance = runtimeService.startProcessInstanceByKey(businessKey, variables);
      processInstanceIds.add(processInstance.getId());
    }

    return processInstanceIds;
  }

}
