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

import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.qa.rolling.update.AbstractRollingUpdateTestCase;
import org.cibseven.bpm.qa.upgrade.ScenarioUnderTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * This test ensures that the old engine can complete an
 * existing process with user task on the new schema.
 *
 * @author Christopher Zell <christopher.zell@camunda.com>
 */
@ScenarioUnderTest("ProcessWithUserTaskScenario")
public class CompleteProcessWithUserTaskTest extends AbstractRollingUpdateTestCase {

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("data")
  @ScenarioUnderTest("init.1")
  public void testCompleteProcessWithUserTask(String tag) {
    init(tag);
    //given an already started process instance
    ProcessInstance oldInstance = rule.processInstance();
    assertNotNull(oldInstance);

    //which waits on an user task
    TaskService taskService = rule.getTaskService();
    Task userTask = taskService.createTaskQuery().processInstanceId(oldInstance.getId()).singleResult();
    assertNotNull(userTask);

    //when completing the user task
    taskService.complete(userTask.getId());

    //then there exists no more tasks
    //and the process instance is also completed
    assertEquals(0, rule.taskQuery().count());
    rule.assertScenarioEnded();
  }

}
