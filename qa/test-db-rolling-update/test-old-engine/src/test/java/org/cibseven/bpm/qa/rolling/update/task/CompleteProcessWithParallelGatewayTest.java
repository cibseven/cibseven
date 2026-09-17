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
package org.cibseven.bpm.qa.rolling.update.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import org.cibseven.bpm.engine.history.HistoricProcessInstance;
import org.cibseven.bpm.engine.history.HistoricTaskInstanceQuery;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.qa.rolling.update.AbstractRollingUpdateTestCase;
import org.cibseven.bpm.qa.upgrade.ScenarioUnderTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * This test ensures that the old engine can complete an
 * existing process with parallel gateway and user task on the new schema.
 *
 * @author Christopher Zell <christopher.zell@camunda.com>
 */
@ScenarioUnderTest("ProcessWithParallelGatewayScenario")
public class CompleteProcessWithParallelGatewayTest extends AbstractRollingUpdateTestCase {

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("data")
  @ScenarioUnderTest("init.none.1")
  public void testCompleteProcessWithParallelGateway(String tag) {
    init(tag);
    //given an already started process instance with two user tasks
    ProcessInstance oldInstance = rule.processInstance();
    assertNotNull(oldInstance);

    List<Task> tasks = rule.taskQuery().list();
    assertEquals(2, tasks.size());

    //when completing the user tasks
    for (Task task : tasks) {
      rule.getTaskService().complete(task.getId());
    }

    //then there exists no more tasks
    //and the process instance is also completed
    assertEquals(0, rule.taskQuery().count());
    rule.assertScenarioEnded();
  }


  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("data")
  @ScenarioUnderTest("init.complete.one.1")
  public void testCompleteProcessWithParallelGatewayAndSingleUserTask(String tag) {
    init(tag);
    //given an already started process instance
    ProcessInstance oldInstance = rule.processInstance();
    assertNotNull(oldInstance);

    //with one completed user task
    HistoricTaskInstanceQuery historicTaskQuery = rule.getHistoryService()
            .createHistoricTaskInstanceQuery()
            .processInstanceId(oldInstance.getId())
            .finished();
    assertEquals(1, historicTaskQuery.count());

    //and one waiting
    Task task = rule.taskQuery().singleResult();
    assertNotNull(task);

    //when completing the user task
    rule.getTaskService().complete(task.getId());

    //then there exists no more tasks
    assertEquals(0, rule.taskQuery().count());
    //and two historic tasks
    assertEquals(2, historicTaskQuery.count());
    //and the process instance is also completed
    rule.assertScenarioEnded();
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("data")
  @ScenarioUnderTest("init.complete.two.1")
  public void testQueryHistoricProcessWithParallelGateway(String tag) {
    init(tag);
    //given an already finished process instance with parallel gateway and two user tasks
    HistoricProcessInstance historicProcessInstance = rule.historicProcessInstance();

    //when query history
    HistoricTaskInstanceQuery historicTaskQuery = rule.getHistoryService()
            .createHistoricTaskInstanceQuery()
            .processInstanceId(historicProcessInstance.getId());

    //then two historic user tasks are returned
    assertEquals(2, historicTaskQuery.count());
  }

}
