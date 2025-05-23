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
package org.cibseven.bpm.engine.test.api.authorization.batch.creation.removaltime;

import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario.scenario;
import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationSpec.grant;

import java.util.Collection;
import java.util.Date;
import java.util.List;

import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.authorization.BatchPermissions;
import org.cibseven.bpm.engine.authorization.Permissions;
import org.cibseven.bpm.engine.authorization.Resources;
import org.cibseven.bpm.engine.history.HistoricDecisionInstanceQuery;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.api.authorization.batch.creation.BatchCreationAuthorizationTest;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationTestRule;
import org.cibseven.bpm.engine.variable.Variables;
import org.junit.Test;
import org.junit.runners.Parameterized;

/**
 * @author Tassilo Weidner
 */
public class SetRemovalTimeForHistoricDecisionInstancesBatchAuthorizationTest extends BatchCreationAuthorizationTest {

  @Parameterized.Parameters(name = "Scenario {index}")
  public static Collection<AuthorizationScenario[]> scenarios() {
    return AuthorizationTestRule.asParameters(
        scenario()
            .withAuthorizations(
              grant(Resources.DECISION_DEFINITION, "dish-decision", "userId", Permissions.READ_HISTORY)
            )
            .failsDueToRequired(
                grant(Resources.BATCH, "batchId", "userId", Permissions.CREATE),
                grant(Resources.BATCH, "batchId", "userId", BatchPermissions.CREATE_BATCH_SET_REMOVAL_TIME)
            ),
        scenario()
            .withAuthorizations(
                grant(Resources.DECISION_DEFINITION, "dish-decision", "userId", Permissions.READ_HISTORY),
                grant(Resources.BATCH, "batchId", "userId", Permissions.CREATE)
            ),
        scenario()
            .withAuthorizations(
                grant(Resources.DECISION_DEFINITION, "dish-decision", "userId", Permissions.READ_HISTORY),
                grant(Resources.BATCH, "batchId", "userId", BatchPermissions.CREATE_BATCH_SET_REMOVAL_TIME)
            ).succeeds()
    );
  }

  @Test
  @Deployment(resources = {
    "org/cibseven/bpm/engine/test/dmn/deployment/drdDish.dmn11.xml"
  })
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  public void shouldAuthorizeSetRemovalTimeForHistoricDecisionInstancesBatch() {
    // given
    setupHistory();

    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("batchId", "*")
        .start();

    HistoricDecisionInstanceQuery query = historyService.createHistoricDecisionInstanceQuery();

    // when
    historyService.setRemovalTimeToHistoricDecisionInstances()
      .absoluteRemovalTime(new Date())
      .byQuery(query)
      .executeAsync();

    // then
    authRule.assertScenario(scenario);
  }

  protected List<String> setupHistory() {
    engineRule.getDecisionService()
      .evaluateDecisionTableByKey("dish-decision", Variables.createVariables()
        .putValue("temperature", 32)
        .putValue("dayType", "Weekend"));

    return null;
  }

}
