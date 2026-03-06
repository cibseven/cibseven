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
package org.cibseven.spin.plugin.variables;

import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.junit5.ProcessEngineExtension;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.ObjectValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * @author Thorben Lindhauer
 *
 */
@ExtendWith(ProcessEngineExtension.class)
public class FallbackSerializationTest {

  public ProcessEngine processEngine;
  public RuntimeService runtimeService;

  protected static final String ONE_TASK_PROCESS = "org/cibseven/spin/plugin/oneTaskProcess.bpmn20.xml";

  @Deployment(resources = ONE_TASK_PROCESS)
  @Test
  public void testSerializationOfUnknownFormat() {
    // given
    ProcessInstance instance = runtimeService.startProcessInstanceByKey("oneTaskProcess");

    // when
    ObjectValue objectValue = Variables.serializedObjectValue("foo")
      .serializationDataFormat("application/foo")
      .objectTypeName("org.cibseven.Foo")
      .create();

    runtimeService.setVariable(instance.getId(), "var", objectValue);

    // then
    try {
      runtimeService.getVariable(instance.getId(), "var");
      fail();
    } catch (ProcessEngineException e) {
      assertTextPresent("Fallback serializer cannot handle deserialized objects", e.getMessage());
    }

    ObjectValue returnedValue = runtimeService.getVariableTyped(instance.getId(), "var", false);
    assertFalse(returnedValue.isDeserialized());
    assertEquals("application/foo", returnedValue.getSerializationDataFormat());
    assertEquals("foo", returnedValue.getValueSerialized());
    assertEquals("org.cibseven.Foo", returnedValue.getObjectTypeName());

  }
}
