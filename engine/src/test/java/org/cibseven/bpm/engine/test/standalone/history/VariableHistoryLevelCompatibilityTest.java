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
package org.cibseven.bpm.engine.test.standalone.history;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.test.util.ProcessEngineBootstrapRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;


/**
 * This test ensures that if a user selects
 * {@link ProcessEngineConfiguration#HISTORY_VARIABLE}, the level is internally
 * mapped to {@link ProcessEngineConfigurationImpl#HISTORYLEVEL_ACTIVITY}.
 *
 * @author Daniel Meyer
 */
public class VariableHistoryLevelCompatibilityTest {

  @RegisterExtension
  @Order(1) public ProcessEngineBootstrapRule bootstrapRule = new ProcessEngineBootstrapRule(
      "org/cibseven/bpm/engine/test/standalone/history/variablehistory.camunda.cfg.xml");
  @RegisterExtension
  @Order(5) public ProvidedProcessEngineRule engineRule = new ProvidedProcessEngineRule(bootstrapRule);

  protected ProcessEngineConfigurationImpl processEngineConfiguration;

  @BeforeEach
  public void setUp() {
    processEngineConfiguration = engineRule.getProcessEngineConfiguration();
  }

  @Test
  public void testCompatibilty() {
    int historyLevel = processEngineConfiguration.getHistoryLevel().getId();
    assertEquals(ProcessEngineConfigurationImpl.HISTORYLEVEL_ACTIVITY, historyLevel);
  }

}
