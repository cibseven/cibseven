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
package org.cibseven.bpm.engine.test.api.runtime;

import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario.scenario;
import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationSpec.grant;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collection;

import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.authorization.BatchPermissions;
import org.cibseven.bpm.engine.authorization.Permissions;
import org.cibseven.bpm.engine.authorization.Resources;
import org.cibseven.bpm.engine.batch.Batch;
import org.cibseven.bpm.engine.batch.history.HistoricBatch;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationTestRule;
import org.cibseven.bpm.engine.test.api.runtime.migration.models.ProcessModels;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 *
 * @author Anna Pazola
 *
 */
@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
public class BatchRestartAuthorizationTest {

  protected static final String TEST_REASON = "test reason";

  @RegisterExtension
  @Order(1) protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  @Order(2) protected AuthorizationTestRule authRule = new AuthorizationTestRule(engineRule);
  @RegisterExtension
  @Order(3) protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);
  protected BatchModificationHelper helper = new BatchModificationHelper(engineRule);

  public static Collection<AuthorizationScenario[]> scenarios() {
    return AuthorizationTestRule.asParameters(
      scenario()
        .withoutAuthorizations()
        .failsDueToRequired(
          grant(Resources.BATCH, "*", "userId", Permissions.CREATE),
          grant(Resources.BATCH, "*", "userId", BatchPermissions.CREATE_BATCH_RESTART_PROCESS_INSTANCES)
        ),
      scenario()
        .withAuthorizations(
          grant(Resources.BATCH, "*", "userId", Permissions.CREATE)
        )
        .failsDueToRequired(
          grant(Resources.PROCESS_DEFINITION, "Process", "userId", Permissions.READ_HISTORY)
        ),
      scenario()
        .withAuthorizations(
          grant(Resources.BATCH, "*", "userId", Permissions.CREATE),
          grant(Resources.PROCESS_DEFINITION, "Process", "userId", Permissions.READ_HISTORY)
        )
        .failsDueToRequired(
          grant(Resources.PROCESS_INSTANCE, "*", "userId", Permissions.CREATE)
        ),
      scenario()
        .withAuthorizations(
          grant(Resources.BATCH, "*", "userId", Permissions.CREATE),
          grant(Resources.PROCESS_DEFINITION, "Process", "userId", Permissions.READ_HISTORY, Permissions.CREATE_INSTANCE),
          grant(Resources.PROCESS_INSTANCE, "*", "userId", Permissions.CREATE)
        ),
      scenario()
        .withAuthorizations(
          grant(Resources.BATCH, "*", "userId", BatchPermissions.CREATE_BATCH_RESTART_PROCESS_INSTANCES),
          grant(Resources.PROCESS_DEFINITION, "Process", "userId", Permissions.READ_HISTORY, Permissions.CREATE_INSTANCE),
          grant(Resources.PROCESS_INSTANCE, "*", "userId", Permissions.CREATE)
        )
        .succeeds()
    );
  }

  @AfterEach
  public void tearDown() {
    authRule.deleteUsersAndGroups();
  }

  @AfterEach
  public void cleanBatch() {
    Batch batch = engineRule.getManagementService().createBatchQuery().singleResult();
    if (batch != null) {
      engineRule.getManagementService().deleteBatch(
          batch.getId(), true);
    }

    HistoricBatch historicBatch = engineRule.getHistoryService().createHistoricBatchQuery().singleResult();
    if (historicBatch != null) {
      engineRule.getHistoryService().deleteHistoricBatch(
          historicBatch.getId());
    }
  }

  @AfterEach
  public void removeBatches() {
    helper.removeAllRunningAndHistoricBatches();
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  public void executeBatch(AuthorizationScenario scenario) {
    //given
    ProcessDefinition processDefinition = testRule.deployAndGetDefinition(ProcessModels.TWO_TASKS_PROCESS);

    ProcessInstance processInstance1 = engineRule.getRuntimeService().startProcessInstanceByKey("Process");
    ProcessInstance processInstance2 = engineRule.getRuntimeService().startProcessInstanceByKey("Process");
    engineRule.getRuntimeService().deleteProcessInstance(processInstance1.getId(), TEST_REASON);
    engineRule.getRuntimeService().deleteProcessInstance(processInstance2.getId(), TEST_REASON);

    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("processInstance1", processInstance1.getId())
        .bindResource("restartedProcessInstance", "*")
        .bindResource("processInstance2", processInstance2.getId())
        .bindResource("processDefinition", "Process")
        .bindResource("batchId", "*")
        .start();

    Batch batch = engineRule.getRuntimeService()
        .restartProcessInstances(processDefinition.getId())
        .processInstanceIds(processInstance1.getId(), processInstance2.getId())
        .startAfterActivity("userTask1")
        .executeAsync();

    if (batch != null) {
      Job job = engineRule.getManagementService().createJobQuery().jobDefinitionId(batch.getSeedJobDefinitionId()).singleResult();

      // seed job
      engineRule.getManagementService().executeJob(job.getId());

      for (Job pending : engineRule.getManagementService().createJobQuery().jobDefinitionId(batch.getBatchJobDefinitionId()).list()) {
        engineRule.getManagementService().executeJob(pending.getId());
      }
    }
    // then
    if (authRule.assertScenario(scenario)) {
      assertEquals("userId", batch.getCreateUserId());
    }
  }
}
