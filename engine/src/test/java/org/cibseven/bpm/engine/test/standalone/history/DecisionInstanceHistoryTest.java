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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.List;

import org.cibseven.bpm.dmn.engine.impl.DefaultDmnEngineConfiguration;
import org.cibseven.bpm.engine.DecisionService;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.history.event.HistoryEventTypes;
import org.cibseven.bpm.engine.repository.DecisionDefinition;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.util.ProcessEngineBootstrapRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.engine.test.util.ResetDmnConfigUtil;
import org.cibseven.bpm.engine.variable.VariableMap;
import org.cibseven.bpm.engine.variable.Variables;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

public class DecisionInstanceHistoryTest {

  public static final String DECISION_SINGLE_OUTPUT_DMN = "org/cibseven/bpm/engine/test/history/HistoricDecisionInstanceTest.decisionSingleOutput.dmn11.xml";

  @ClassRule
  public static ProcessEngineBootstrapRule bootstrapRule = new ProcessEngineBootstrapRule(
      "org/cibseven/bpm/engine/test/standalone/history/decisionInstanceHistory.camunda.cfg.xml");
  @Rule
  public ProvidedProcessEngineRule engineRule = new ProvidedProcessEngineRule(bootstrapRule);

  protected ProcessEngineConfigurationImpl processEngineConfiguration;
  protected RepositoryService repositoryService;
  protected DecisionService decisionService;

  @Before
  public void setUp() throws Exception {
    processEngineConfiguration = engineRule.getProcessEngineConfiguration();
    repositoryService = engineRule.getRepositoryService();
    decisionService = engineRule.getDecisionService();

    DefaultDmnEngineConfiguration dmnEngineConfiguration =
        processEngineConfiguration.getDmnEngineConfiguration();

    ResetDmnConfigUtil.reset(dmnEngineConfiguration)
        .enableFeelLegacyBehavior(true)
        .init();
  }

  @After
  public void tearDown() throws Exception {
    DefaultDmnEngineConfiguration dmnEngineConfiguration =
        processEngineConfiguration.getDmnEngineConfiguration();

    ResetDmnConfigUtil.reset(dmnEngineConfiguration)
        .enableFeelLegacyBehavior(false)
        .init();
  }

  @Deployment(resources = DECISION_SINGLE_OUTPUT_DMN)
  @Test
  public void testDecisionDefinitionPassedToHistoryLevel() {
    RecordHistoryLevel historyLevel = (RecordHistoryLevel) processEngineConfiguration.getHistoryLevel();
    DecisionDefinition decisionDefinition = repositoryService.createDecisionDefinitionQuery().decisionDefinitionKey("testDecision").singleResult();

    VariableMap variables = Variables.createVariables().putValue("input1", true);
    decisionService.evaluateDecisionTableByKey("testDecision", variables);

    List<RecordHistoryLevel.ProducedHistoryEvent> producedHistoryEvents = historyLevel.getProducedHistoryEvents();
    assertEquals(1, producedHistoryEvents.size());

    RecordHistoryLevel.ProducedHistoryEvent producedHistoryEvent = producedHistoryEvents.get(0);
    assertEquals(HistoryEventTypes.DMN_DECISION_EVALUATE, producedHistoryEvent.eventType);

    DecisionDefinition entity = (DecisionDefinition) producedHistoryEvent.entity;
    assertNotNull(entity);
    assertEquals(decisionDefinition.getId(), entity.getId());
  }

}
