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

import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.runtime.ActivityInstance;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;


public class ProcessInstanceModificationVariableTest {

  protected ProvidedProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  protected ProcessEngineTestRule testHelper = new ProcessEngineTestRule(engineRule);

//  @Rule
//  public RuleChain ruleChain = RuleChain.outerRule(engineRule).around(testHelper);

  RuntimeService runtimeService;
  TaskService taskService;

  @BeforeEach
  public void initialize() {
    runtimeService = engineRule.getRuntimeService();
    taskService = engineRule.getTaskService();
  }

  @Test
  public void modifyAProcessInstanceWithLocalVariableCreation() {

    // given a process that sets a local variable when entering the user task
    BpmnModelInstance instance = Bpmn.createExecutableProcess("Process")
      .startEvent()
      .userTask("userTask")
        .camundaTaskListenerClass("create", "org.cibseven.bpm.engine.test.api.runtime.util.CreateLocalVariableEventListener")
      .endEvent()
      .done();

    testHelper.deployAndGetDefinition(instance);
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("Process");

    ActivityInstance updatedTree = runtimeService.getActivityInstance(processInstance.getId());

    // when I start another activity and delete the old one
    runtimeService
      .createProcessInstanceModification(processInstance.getId())
      .startBeforeActivity("userTask")
      .cancelActivityInstance(updatedTree.getActivityInstances("userTask")[0].getId())
      .execute(false, false);

    // then migration was successful and I can finish the process
    Task task = taskService.createTaskQuery().singleResult();
    taskService.complete(task.getId());
    testHelper.assertProcessEnded(processInstance.getId());

  }

}
