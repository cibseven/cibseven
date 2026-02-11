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
package org.cibseven.bpm.engine.test.api.authorization.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario.scenario;
import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationSpec.grant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import org.cibseven.bpm.engine.EntityTypes;
import org.cibseven.bpm.engine.authorization.BatchPermissions;
import org.cibseven.bpm.engine.authorization.Permissions;
import org.cibseven.bpm.engine.authorization.Resources;
import org.cibseven.bpm.engine.batch.Batch;
import org.cibseven.bpm.engine.batch.history.HistoricBatch;
import org.cibseven.bpm.engine.runtime.ProcessInstanceQuery;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationTestRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;

/**
 * @author Askar Akhmerov
 */
public class DeleteProcessInstancesBatchAuthorizationTest extends AbstractBatchAuthorizationTest {

  protected static final long BATCH_OPERATIONS = 3L;

  @RegisterExtension
  public AuthorizationTestRule authRule = new AuthorizationTestRule(engineRule);
  @RegisterExtension
  public ProcessEngineTestRule testHelper = new org.cibseven.bpm.engine.test.util.ProcessEngineTestRule(engineRule);

  public AuthorizationScenario scenario;

  public static Collection<AuthorizationScenario[]> scenarios() {
    return AuthorizationTestRule.asParameters(
        scenario()
            .withAuthorizations(
                grant(Resources.BATCH, "*", "userId", Permissions.CREATE),
                grant(Resources.PROCESS_INSTANCE, "processInstance1", "userId", Permissions.READ, Permissions.DELETE),
                grant(Resources.PROCESS_INSTANCE, "processInstance2", "userId", Permissions.READ)
            )
            .failsDueToRequired(
                grant(Resources.PROCESS_INSTANCE, "processInstance2", "userId", Permissions.DELETE),
                grant(Resources.PROCESS_DEFINITION, "Process_2", "userId", Permissions.DELETE_INSTANCE)
            ),
        scenario()
            .withAuthorizations(
                grant(Resources.BATCH, "*", "userId", Permissions.CREATE),
                grant(Resources.PROCESS_INSTANCE, "processInstance1", "userId", Permissions.ALL),
                grant(Resources.PROCESS_INSTANCE, "processInstance2", "userId", Permissions.ALL)
            ).succeeds(),
        scenario()
            .withAuthorizations(
                grant(Resources.BATCH, "*", "userId", BatchPermissions.CREATE_BATCH_DELETE_RUNNING_PROCESS_INSTANCES),
                grant(Resources.PROCESS_INSTANCE, "processInstance1", "userId", Permissions.ALL),
                grant(Resources.PROCESS_INSTANCE, "processInstance2", "userId", Permissions.ALL)
            ).succeeds(),
        scenario()
            .withAuthorizations(
                grant(Resources.BATCH, "*", "userId", Permissions.CREATE),
                grant(Resources.PROCESS_DEFINITION, "Process_2", "userId", Permissions.READ_INSTANCE, Permissions.DELETE_INSTANCE),
                grant(Resources.PROCESS_DEFINITION, "Process_1", "userId", Permissions.READ_INSTANCE, Permissions.DELETE_INSTANCE)
            ).succeeds()
    );
  }

  @Test
  public void testWithTwoInvocationsProcessInstancesList() {
    engineRule.getProcessEngineConfiguration().setInvocationsPerBatchJob(2);
    setupAndExecuteProcessInstancesListTest();

    // then
    assertScenario();
  }

  @Test
  public void testProcessInstancesList() {
    setupAndExecuteProcessInstancesListTest();
    // then
    assertScenario();
  }

  @Test
  public void testWithQuery() {
    //given
    ProcessInstanceQuery processInstanceQuery = runtimeService.createProcessInstanceQuery()
        .processInstanceIds(new HashSet<String>(Arrays.asList(processInstance.getId(), processInstance2.getId())));

    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("processInstance1", processInstance.getId())
        .bindResource("processInstance2", processInstance2.getId())
        .bindResource("Process_2", sourceDefinition2.getKey())
        .start();

    // when

    batch = runtimeService.deleteProcessInstancesAsync(null,
        processInstanceQuery, TEST_REASON);
    executeSeedAndBatchJobs();

    // then
    if (authRule.assertScenario(scenario)) {
      if (testHelper.isHistoryLevelFull()) {
        assertThat(engineRule.getHistoryService().createUserOperationLogQuery().entityType(EntityTypes.PROCESS_INSTANCE).count()).isEqualTo(BATCH_OPERATIONS);
      }
    }
  }

  protected void setupAndExecuteProcessInstancesListTest() {
    //given
    List<String> processInstanceIds = Arrays.asList(processInstance.getId(), processInstance2.getId());
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("processInstance1", processInstance.getId())
        .bindResource("processInstance2", processInstance2.getId())
        .bindResource("Process_2", sourceDefinition2.getKey())
        .bindResource("Process_1", sourceDefinition.getKey())
        .start();

    // when
    batch = runtimeService.deleteProcessInstancesAsync(
        processInstanceIds, null, TEST_REASON);

    executeSeedAndBatchJobs();
  }

  protected void assertScenario() {
    if (authRule.assertScenario(getScenario())) {
      Batch batch = engineRule.getManagementService().createBatchQuery().singleResult();
      assertEquals("userId", batch.getCreateUserId());

      if (testHelper.isHistoryLevelFull()) {
        assertThat(engineRule.getHistoryService().createUserOperationLogQuery().entityType(EntityTypes.PROCESS_INSTANCE).count()).isEqualTo(BATCH_OPERATIONS);
        HistoricBatch historicBatch = engineRule.getHistoryService().createHistoricBatchQuery().list().get(0);
        assertEquals("userId", historicBatch.getCreateUserId());
      }

      if (authRule.scenarioSucceeded()) {
        assertThat(runtimeService.createProcessInstanceQuery().count()).isEqualTo(0L);
      }
    }
  }

  @Override
  public AuthorizationScenario getScenario() {
    return scenario;
  }
}