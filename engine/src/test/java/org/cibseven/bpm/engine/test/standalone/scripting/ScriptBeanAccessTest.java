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
package org.cibseven.bpm.engine.test.standalone.scripting;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.util.ProcessEngineBootstrapClassExtension;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * @author Tom Baeyens
 */
public class ScriptBeanAccessTest {

  @RegisterExtension
  public static ProcessEngineBootstrapClassExtension processEngineBootstrapClassExtension = ProcessEngineBootstrapClassExtension.builder()
    .setConfigurationResource("org/cibseven/bpm/engine/test/standalone/scripting/camunda.cfg.xml")
    .build();

  public ProvidedProcessEngineRule engineRule = null;

  protected RuntimeService runtimeService;

  @BeforeEach
  public void setUp() {
    runtimeService = engineRule.getRuntimeService();
  }

  @Deployment
  @Test
  public void testConfigurationBeanAccess() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("ScriptBeanAccess");
    assertEquals("myValue", runtimeService.getVariable(pi.getId(), "myVariable"));
  }

}
