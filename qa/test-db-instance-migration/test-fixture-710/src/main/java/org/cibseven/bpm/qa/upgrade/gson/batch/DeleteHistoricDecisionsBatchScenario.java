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
package org.cibseven.bpm.qa.upgrade.gson.batch;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.history.HistoricDecisionInstance;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.variable.VariableMap;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.qa.upgrade.DescribesScenario;
import org.cibseven.bpm.qa.upgrade.ScenarioSetup;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Tassilo Weidner
 */
public class DeleteHistoricDecisionsBatchScenario {

  @Deployment
  public static String deploy() {
    return "org/cibseven/bpm/qa/upgrade/gson/Example.dmn";
  }

  @DescribesScenario("initDeleteHistoricDecisionsBatch")
  public static ScenarioSetup initDeleteHistoricDecisionsBatch() {
    return new ScenarioSetup() {
      public void execute(ProcessEngine engine, String scenarioName) {

        VariableMap variables = Variables.createVariables()
          .putValue("status", "silver")
          .putValue("sum", 723);

        for (int i = 0; i < 10; i++) {
          engine.getDecisionService().evaluateDecisionByKey("decision_710")
            .variables(variables)
            .evaluate();
        }

        List<String> decisionInstanceIds = new ArrayList<>();

        List<HistoricDecisionInstance> decisionInstances = engine.getHistoryService().createHistoricDecisionInstanceQuery().list();
        for (HistoricDecisionInstance decisionInstance : decisionInstances) {
          decisionInstanceIds.add(decisionInstance.getId());
        }

        engine.getHistoryService().deleteHistoricDecisionInstancesAsync(decisionInstanceIds, null);
      }
    };
  }
}