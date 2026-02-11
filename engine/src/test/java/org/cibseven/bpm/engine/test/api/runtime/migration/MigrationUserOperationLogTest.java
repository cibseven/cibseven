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
package org.cibseven.bpm.engine.test.api.runtime.migration;

import static org.cibseven.bpm.engine.test.api.runtime.migration.ModifiableBpmnModelInstance.modify;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.history.UserOperationLogEntry;
import org.cibseven.bpm.engine.migration.MigrationPlan;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.api.runtime.migration.models.ProcessModels;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.Test;


/**
 * @author Thorben Lindhauer
 *
 */
@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
public class MigrationUserOperationLogTest {

  public static final String USER_ID = "userId";

  protected ProcessEngineRule rule = new ProvidedProcessEngineRule();
  protected MigrationTestRule testHelper = new MigrationTestRule(rule);

//  @Rule
//  public RuleChain ruleChain = RuleChain.outerRule(rule).around(testHelper);

  @Test
  public void testLogCreation() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deployAndGetDefinition(ProcessModels.ONE_TASK_PROCESS);
    ProcessDefinition targetProcessDefinition = testHelper.deployAndGetDefinition(
        modify(ProcessModels.ONE_TASK_PROCESS).changeElementId(ProcessModels.PROCESS_KEY, "new" + ProcessModels.PROCESS_KEY));

    MigrationPlan migrationPlan = rule.getRuntimeService().createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapEqualActivities()
      .build();

    ProcessInstance processInstance = rule.getRuntimeService().startProcessInstanceById(sourceProcessDefinition.getId());

    // when
    rule.getIdentityService().setAuthenticatedUserId(USER_ID);
    rule.getRuntimeService()
      .newMigration(migrationPlan)
      .processInstanceIds(Arrays.asList(processInstance.getId()))
      .execute();
    rule.getIdentityService().clearAuthentication();

    // then
    List<UserOperationLogEntry> opLogEntries = rule.getHistoryService().createUserOperationLogQuery().list();
    Assertions.assertEquals(3, opLogEntries.size());

    Map<String, UserOperationLogEntry> entries = asMap(opLogEntries);

    UserOperationLogEntry procDefEntry = entries.get("processDefinitionId");
    Assertions.assertNotNull(procDefEntry);
    Assertions.assertEquals("ProcessInstance", procDefEntry.getEntityType());
    Assertions.assertEquals("Migrate", procDefEntry.getOperationType());
    Assertions.assertEquals(sourceProcessDefinition.getId(), procDefEntry.getProcessDefinitionId());
    Assertions.assertEquals(sourceProcessDefinition.getKey(), procDefEntry.getProcessDefinitionKey());
    Assertions.assertNull(procDefEntry.getProcessInstanceId());
    Assertions.assertEquals(sourceProcessDefinition.getId(), procDefEntry.getOrgValue());
    Assertions.assertEquals(targetProcessDefinition.getId(), procDefEntry.getNewValue());
    Assertions.assertEquals(UserOperationLogEntry.CATEGORY_OPERATOR, procDefEntry.getCategory());

    UserOperationLogEntry asyncEntry = entries.get("async");
    Assertions.assertNotNull(asyncEntry);
    Assertions.assertEquals("ProcessInstance", asyncEntry.getEntityType());
    Assertions.assertEquals("Migrate", asyncEntry.getOperationType());
    Assertions.assertEquals(sourceProcessDefinition.getId(), asyncEntry.getProcessDefinitionId());
    Assertions.assertEquals(sourceProcessDefinition.getKey(), asyncEntry.getProcessDefinitionKey());
    Assertions.assertNull(asyncEntry.getProcessInstanceId());
    Assertions.assertNull(asyncEntry.getOrgValue());
    Assertions.assertEquals("false", asyncEntry.getNewValue());
    Assertions.assertEquals(UserOperationLogEntry.CATEGORY_OPERATOR, asyncEntry.getCategory());

    UserOperationLogEntry numInstanceEntry = entries.get("nrOfInstances");
    Assertions.assertNotNull(numInstanceEntry);
    Assertions.assertEquals("ProcessInstance", numInstanceEntry.getEntityType());
    Assertions.assertEquals("Migrate", numInstanceEntry.getOperationType());
    Assertions.assertEquals(sourceProcessDefinition.getId(), numInstanceEntry.getProcessDefinitionId());
    Assertions.assertEquals(sourceProcessDefinition.getKey(), numInstanceEntry.getProcessDefinitionKey());
    Assertions.assertNull(numInstanceEntry.getProcessInstanceId());
    Assertions.assertNull(numInstanceEntry.getOrgValue());
    Assertions.assertEquals("1", numInstanceEntry.getNewValue());
    Assertions.assertEquals(UserOperationLogEntry.CATEGORY_OPERATOR, numInstanceEntry.getCategory());

    Assertions.assertEquals(procDefEntry.getOperationId(), asyncEntry.getOperationId());
    Assertions.assertEquals(asyncEntry.getOperationId(), numInstanceEntry.getOperationId());
  }

  protected Map<String, UserOperationLogEntry> asMap(List<UserOperationLogEntry> logEntries) {
    Map<String, UserOperationLogEntry> map = new HashMap<String, UserOperationLogEntry>();

    for (UserOperationLogEntry entry : logEntries) {

      UserOperationLogEntry previousValue = map.put(entry.getProperty(), entry);
      if (previousValue != null) {
        Assertions.fail("expected only entry for every property");
      }
    }

    return map;
  }
}
