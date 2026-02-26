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
package org.cibseven.bpm.engine.test.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

import org.apache.commons.lang3.time.DateUtils;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.history.HistoricProcessInstance;
import org.cibseven.bpm.engine.impl.interceptor.Command;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.cibseven.bpm.engine.impl.util.ClockUtil;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * @author Svetlana Dorokhova
 */
@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
public class HistoricProcessInstanceManagerProcessInstancesForCleanupTest {

  protected static final String ONE_TASK_PROCESS = "oneTaskProcess";
  protected static final String TWO_TASKS_PROCESS = "twoTasksProcess";

  @RegisterExtension
  protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  private HistoryService historyService;
  private RuntimeService runtimeService;

  @BeforeEach
  public void init() {
    runtimeService = engineRule.getRuntimeService();
    historyService = engineRule.getHistoryService();
  }


  public static Collection<Object[]> scenarios() {
    return Arrays.asList(new Object[][] {
        { 3, 5, 3, 7, 4, 50, 3 },
        //not enough time has passed
        { 3, 5, 3, 7, 2, 50, 0 },
        //all historic process instances are old enough to be cleaned up
        { 3, 5, 3, 7, 6, 50, 10 },
        //batchSize will reduce the result
        { 3, 5, 3, 7, 6, 4, 4 }
    });
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  @Deployment(resources = { "org/cibseven/bpm/engine/test/api/oneTaskProcess.bpmn20.xml", "org/cibseven/bpm/engine/test/api/twoTasksProcess.bpmn20.xml" })
  public void testFindHistoricProcessInstanceIdsForCleanup(int processDefiniotion1TTL, int processDefiniotion2TTL, 
    int processInstancesOfProcess1Count, int processInstancesOfProcess2Count, 
    int daysPassedAfterProcessEnd, int batchSize, int resultCount) {

    engineRule.getProcessEngineConfiguration().getCommandExecutorTxRequired().execute(new Command<Object>() {
      @Override
      public Object execute(CommandContext commandContext) {

        //given
        //set different TTL for two process definition
        updateTimeToLive(commandContext, ONE_TASK_PROCESS, processDefiniotion1TTL);
        updateTimeToLive(commandContext, TWO_TASKS_PROCESS, processDefiniotion2TTL);
        return null;
      }
    });
    //start processes
    List<String> ids = prepareHistoricProcesses(ONE_TASK_PROCESS, processInstancesOfProcess1Count);
    ids.addAll(prepareHistoricProcesses(TWO_TASKS_PROCESS, processInstancesOfProcess2Count));

    runtimeService.deleteProcessInstances(ids, null, true, true);

    //some days passed
    ClockUtil.setCurrentTime(DateUtils.addDays(new Date(), daysPassedAfterProcessEnd));

    engineRule.getProcessEngineConfiguration().getCommandExecutorTxRequired().execute(new Command<Object>() {
      @Override
      public Object execute(CommandContext commandContext) {
        //when
        List<String> historicProcessInstanceIdsForCleanup = commandContext.getHistoricProcessInstanceManager().findHistoricProcessInstanceIdsForCleanup(
            batchSize, 0, 60);

        //then
        assertEquals(resultCount, historicProcessInstanceIdsForCleanup.size());

        if (resultCount > 0) {

          List<HistoricProcessInstance> historicProcessInstances = historyService.createHistoricProcessInstanceQuery()
              .processInstanceIds(new HashSet<String>(historicProcessInstanceIdsForCleanup)).list();

          for (HistoricProcessInstance historicProcessInstance : historicProcessInstances) {
            assertNotNull(historicProcessInstance.getEndTime());
            List<ProcessDefinition> processDefinitions = engineRule.getRepositoryService().createProcessDefinitionQuery()
                .processDefinitionId(historicProcessInstance.getProcessDefinitionId()).list();
            assertEquals(1, processDefinitions.size());
            ProcessDefinitionEntity processDefinition = (ProcessDefinitionEntity) processDefinitions.get(0);
            assertTrue(historicProcessInstance.getEndTime().before(DateUtils.addDays(ClockUtil.getCurrentTime(), processDefinition.getHistoryTimeToLive())));
          }
        }

        return null;
      }
    });

  }

  private void updateTimeToLive(CommandContext commandContext, String businessKey, int timeToLive) {
    List<ProcessDefinition> processDefinitions = engineRule.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey(businessKey).list();
    assertEquals(1, processDefinitions.size());
    ProcessDefinitionEntity processDefinition1 = (ProcessDefinitionEntity) processDefinitions.get(0);
    processDefinition1.setHistoryTimeToLive(timeToLive);
    commandContext.getDbEntityManager().merge(processDefinition1);
  }

  private List<String> prepareHistoricProcesses(String businessKey, Integer processInstanceCount) {
    List<String> processInstanceIds = new ArrayList<String>();

    for (int i = 0; i < processInstanceCount; i++) {
      ProcessInstance processInstance = runtimeService.startProcessInstanceByKey(businessKey);
      processInstanceIds.add(processInstance.getId());
    }

    return processInstanceIds;
  }

}
