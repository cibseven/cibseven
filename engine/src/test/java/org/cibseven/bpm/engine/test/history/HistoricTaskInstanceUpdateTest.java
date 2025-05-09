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

import static org.junit.Assert.assertEquals;

import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.history.HistoricTaskInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.util.PluggableProcessEngineTest;
import org.junit.Test;


/**
 * @author Frederik Heremans
 */
@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_ACTIVITY)
public class HistoricTaskInstanceUpdateTest extends PluggableProcessEngineTest {


  @Deployment
  @Test
  public void testHistoricTaskInstanceUpdate() {
    runtimeService.startProcessInstanceByKey("HistoricTaskInstanceTest").getId();

    Task task = taskService.createTaskQuery().singleResult();

    // Update and save the task's fields before it is finished
    task.setPriority(12345);
    task.setDescription("Updated description");
    task.setName("Updated name");
    task.setAssignee("gonzo");
    taskService.saveTask(task);

    taskService.complete(task.getId());
    assertEquals(1, historyService.createHistoricTaskInstanceQuery().count());

    HistoricTaskInstance historicTaskInstance = historyService.createHistoricTaskInstanceQuery().singleResult();
    assertEquals("Updated name", historicTaskInstance.getName());
    assertEquals("Updated description", historicTaskInstance.getDescription());
    assertEquals("gonzo", historicTaskInstance.getAssignee());
    assertEquals("task", historicTaskInstance.getTaskDefinitionKey());
  }
}
