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
package org.cibseven.bpm.engine.test.api.queries;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.history.HistoricVariableInstance;
import org.cibseven.bpm.engine.impl.HistoricVariableInstanceQueryImpl;
import org.cibseven.bpm.engine.impl.persistence.StrongUuidGenerator;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.util.ProcessEngineBootstrapRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Order;

import org.junit.jupiter.api.Test;


@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
public class QueryByIdAfterTest {

  protected ProvidedProcessEngineRule engineRule = new ProvidedProcessEngineRule(bootstrapRule);
  @Order(9) protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  @RegisterExtension
  @Order(3) public static ProcessEngineBootstrapRule bootstrapRule = new ProcessEngineBootstrapRule(config -> config.setIdGenerator(new StrongUuidGenerator()));

  private HistoryService historyService;
  private RuntimeService runtimeService;

  @BeforeEach
  public void init() {
    this.historyService = engineRule.getProcessEngine().getHistoryService();
    this.runtimeService = engineRule.getRuntimeService();
  }

  @Test
  @Deployment(resources = { "org/cibseven/bpm/engine/test/history/HistoricVariableInstanceTest.testSimple.bpmn20.xml" })
  public void shouldVariableInstanceApiReturnOnlyAfterGivenId() {
    // given
    startProcessInstancesByKey("myProc", 10);

    // when querying by idAfter then only expected results are returned
    HistoricVariableInstanceQueryImpl historicVariableInstanceQuery = (HistoricVariableInstanceQueryImpl) historyService.createHistoricVariableInstanceQuery();
    List<HistoricVariableInstance> historicVariableInstances = historicVariableInstanceQuery.orderByVariableId().asc().list();
    String firstId = historicVariableInstances.get(0).getId();
    String middleId = historicVariableInstances.get(9).getId();
    String lastId = historicVariableInstances.get(historicVariableInstances.size() - 1).getId();
    assertEquals(20, historicVariableInstances.size());
    assertEquals(19, historicVariableInstanceQuery.idAfter(firstId).list().size());
    assertEquals(0, historicVariableInstanceQuery.idAfter(lastId).list().size());

    List<HistoricVariableInstance> secondHalf = historicVariableInstanceQuery.idAfter(middleId).list();
    assertEquals(10, secondHalf.size());
    assertTrue(secondHalf.stream().allMatch(variable -> isIdGreaterThan(variable.getId(), middleId)));
  }

  private void startProcessInstancesByKey(String key, int numberOfInstances) {
    for (int i = 0; i < numberOfInstances; i++) {
      Map<String, Object> variables = Collections.singletonMap("message", "exception" + i);

      runtimeService.startProcessInstanceByKey(key, i + "", variables);
    }
    testRule.executeAvailableJobs();
  }

  /**
   * Compares two ids
   * @param id1
   * @param id2
   * @return true if id1 is greater than id2, false otherwise
   */
  private static boolean isIdGreaterThan(String id1, String id2) {
    return id1.compareTo(id2) > 0;
  }

}
