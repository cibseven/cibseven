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
package org.cibseven.bpm.engine.test.api.variables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Arrays;
import java.util.List;

import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.history.HistoricVariableInstance;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.runtime.VariableInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.engine.variable.VariableMap;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Order;

public class ModifyVariableInSameTransactionTest {
  @RegisterExtension
  @Order(4) public ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  @Order(9) public ProcessEngineTestRule testHelper = new ProcessEngineTestRule(engineRule);

  @Test
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_AUDIT)
  public void testDeleteAndInsertTheSameVariableByteArray() {
    BpmnModelInstance bpmnModel =
        Bpmn.createExecutableProcess("serviceTaskProcess")
        .startEvent()
        .userTask("userTask")
        .serviceTask("service")
          .camundaClass(DeleteAndInsertVariableDelegate.class)
        .userTask("userTask1")
        .endEvent()
        .done();
    ProcessDefinition processDefinition = testHelper.deployAndGetDefinition(bpmnModel);
    VariableMap variables = Variables.createVariables().putValue("listVar", Arrays.asList(new int[] { 1, 2, 3 }));
    ProcessInstance instance = engineRule.getRuntimeService().startProcessInstanceById(processDefinition.getId(), variables);

    Task task = engineRule.getTaskService().createTaskQuery().singleResult();
    engineRule.getTaskService().complete(task.getId());

    VariableInstance variable = engineRule.getRuntimeService().createVariableInstanceQuery().processInstanceIdIn(instance.getId()).variableName("listVar").singleResult();
    assertNotNull(variable);
    assertEquals("stringValue", variable.getValue());
    HistoricVariableInstance historicVariable = engineRule.getHistoryService().createHistoricVariableInstanceQuery().singleResult();
    assertEquals(variable.getName(), historicVariable.getName());
    assertEquals(HistoricVariableInstance.STATE_CREATED, historicVariable.getState());
  }

  @Test
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_AUDIT)
  public void testDeleteAndInsertTheSameVariable() {
    BpmnModelInstance bpmnModel =
        Bpmn.createExecutableProcess("serviceTaskProcess")
        .startEvent()
        .userTask("userTask")
        .serviceTask("service")
          .camundaClass(DeleteAndInsertVariableDelegate.class)
        .userTask("userTask1")
        .endEvent()
        .done();
    ProcessDefinition processDefinition = testHelper.deployAndGetDefinition(bpmnModel);
    VariableMap variables = Variables.createVariables().putValue("foo", "firstValue");
    ProcessInstance instance = engineRule.getRuntimeService().startProcessInstanceById(processDefinition.getId(), variables);

    Task task = engineRule.getTaskService().createTaskQuery().singleResult();
    engineRule.getTaskService().complete(task.getId());

    VariableInstance variable = engineRule.getRuntimeService().createVariableInstanceQuery().processInstanceIdIn(instance.getId()).variableName("foo").singleResult();
    assertNotNull(variable);
    assertEquals("secondValue", variable.getValue());
    HistoricVariableInstance historicVariable = engineRule.getHistoryService().createHistoricVariableInstanceQuery().singleResult();
    assertEquals(variable.getName(), historicVariable.getName());
    assertEquals(HistoricVariableInstance.STATE_CREATED, historicVariable.getState());
  }

  @Test
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_AUDIT)
  public void testInsertDeleteInsertTheSameVariable() {
    BpmnModelInstance bpmnModel =
        Bpmn.createExecutableProcess("serviceTaskProcess")
        .startEvent()
        .userTask("userTask")
        .serviceTask("service")
          .camundaClass(InsertDeleteInsertVariableDelegate.class)
        .userTask("userTask1")
        .endEvent()
        .done();
    ProcessDefinition processDefinition = testHelper.deployAndGetDefinition(bpmnModel);
    VariableMap variables = Variables.createVariables().putValue("listVar", Arrays.asList(new int[] { 1, 2, 3 }));
    ProcessInstance instance = engineRule.getRuntimeService().startProcessInstanceById(processDefinition.getId(), variables);

    Task task = engineRule.getTaskService().createTaskQuery().singleResult();
    engineRule.getTaskService().complete(task.getId());

    VariableInstance variable = engineRule.getRuntimeService().createVariableInstanceQuery().processInstanceIdIn(instance.getId()).variableName("foo")
        .singleResult();
    assertNotNull(variable);
    assertEquals("bar", variable.getValue());
    List<HistoricVariableInstance> historyVariables = engineRule.getHistoryService().createHistoricVariableInstanceQuery().list();
    for (HistoricVariableInstance historicVariable : historyVariables) {
      if (variable.getName().equals(historicVariable.getName())) {
        assertEquals(HistoricVariableInstance.STATE_CREATED, historicVariable.getState());
        break;
      }
    }
  }
}
