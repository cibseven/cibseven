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
package org.cibseven.bpm.engine.test.api.form;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.form.FormProperty;
import org.cibseven.bpm.engine.form.StartFormData;
import org.cibseven.bpm.engine.form.TaskFormData;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.util.PluggableProcessEngineTest;
import org.junit.Test;

public class FormPropertyDefaultValueTest extends PluggableProcessEngineTest {

  @Deployment
  @Test
  public void testDefaultValue() throws Exception {
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("FormPropertyDefaultValueTest.testDefaultValue");
    Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();

    TaskFormData formData = formService.getTaskFormData(task.getId());
    List<FormProperty> formProperties = formData.getFormProperties();
    assertEquals(4, formProperties.size());

    for (FormProperty prop : formProperties) {
      if ("booleanProperty".equals(prop.getId())) {
        assertEquals("true", prop.getValue());
      } else if ("stringProperty".equals(prop.getId())) {
        assertEquals("someString", prop.getValue());
      } else if ("longProperty".equals(prop.getId())) {
        assertEquals("42", prop.getValue());
      } else if ("longExpressionProperty".equals(prop.getId())) {
        assertEquals("23", prop.getValue());
      } else {
        assertTrue("Invalid form property: " + prop.getId(), false);
      }
    }

    Map<String, String> formDataUpdate = new HashMap<String, String>();
    formDataUpdate.put("longExpressionProperty", "1");
    formDataUpdate.put("booleanProperty", "false");
    formService.submitTaskFormData(task.getId(), formDataUpdate);

    assertEquals(false, runtimeService.getVariable(processInstance.getId(), "booleanProperty"));
    assertEquals("someString", runtimeService.getVariable(processInstance.getId(), "stringProperty"));
    assertEquals(42L, runtimeService.getVariable(processInstance.getId(), "longProperty"));
    assertEquals(1L, runtimeService.getVariable(processInstance.getId(), "longExpressionProperty"));
  }
  
  @Deployment
  @Test
  public void testStartFormDefaultValue() throws Exception {
    String processDefinitionId = repositoryService.createProcessDefinitionQuery()
      .processDefinitionKey("FormPropertyDefaultValueTest.testDefaultValue")
      .latestVersion()
      .singleResult()
      .getId();
    
    StartFormData startForm = formService.getStartFormData(processDefinitionId);
    
    
    List<FormProperty> formProperties = startForm.getFormProperties();
    assertEquals(4, formProperties.size());

    for (FormProperty prop : formProperties) {
      if ("booleanProperty".equals(prop.getId())) {
        assertEquals("true", prop.getValue());
      } else if ("stringProperty".equals(prop.getId())) {
        assertEquals("someString", prop.getValue());
      } else if ("longProperty".equals(prop.getId())) {
        assertEquals("42", prop.getValue());
      } else if ("longExpressionProperty".equals(prop.getId())) {
        assertEquals("23", prop.getValue());
      } else {
        assertTrue("Invalid form property: " + prop.getId(), false);
      }
    }

    // Override 2 properties. The others should pe posted as the default-value
    Map<String, String> formDataUpdate = new HashMap<String, String>();
    formDataUpdate.put("longExpressionProperty", "1");
    formDataUpdate.put("booleanProperty", "false");
    ProcessInstance processInstance = formService.submitStartFormData(processDefinitionId, formDataUpdate);

    assertEquals(false, runtimeService.getVariable(processInstance.getId(), "booleanProperty"));
    assertEquals("someString", runtimeService.getVariable(processInstance.getId(), "stringProperty"));
    assertEquals(42L, runtimeService.getVariable(processInstance.getId(), "longProperty"));
    assertEquals(1L, runtimeService.getVariable(processInstance.getId(), "longExpressionProperty"));
  }
}
