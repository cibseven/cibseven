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
package org.cibseven.bpm.engine.test.api.authorization;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;

import org.cibseven.bpm.engine.AuthorizationService;
import org.cibseven.bpm.engine.authorization.Authorization;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.commons.testing.ProcessEngineLoggingRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class AuthorizationLoggingTest {

  protected static final String CONTEXT_LOGGER = "org.cibseven.bpm.engine.context";

  @RegisterExtension
  @Order(1) private ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  @Order(2) private AuthorizationTestRule authRule = new AuthorizationTestRule(engineRule);
  @RegisterExtension
  @Order(4) public ProcessEngineLoggingRule loggingRule = new ProcessEngineLoggingRule()
      .watch(CONTEXT_LOGGER)
      .level(Level.DEBUG);

  @AfterEach
  public void tearDown() {
    engineRule.getProcessEngineConfiguration().setAuthorizationEnabled(false);
    AuthorizationService authorizationService = engineRule.getAuthorizationService();
    for (Authorization authorization : authorizationService.createAuthorizationQuery().list()) {
      authorizationService.deleteAuthorization(authorization.getId());
    }
  }

  @Test
  public void shouldLogOnDebugLevel() {
    // given
    AuthorizationScenario scenario = new AuthorizationScenario().withoutAuthorizations();

    authRule.init(scenario)
        .withUser("userId")
        .start();

    // when
    engineRule.getManagementService().getTelemetryData();

    // then
    String message = "ENGINE-03110 Required admin authenticated group or user or any of the following permissions:";
    List<ILoggingEvent> filteredLog = loggingRule.getFilteredLog(CONTEXT_LOGGER, message);

    assertThat(filteredLog.size()).isEqualTo(1);
    assertThat(filteredLog.get(0).getLevel()).isEqualTo(Level.DEBUG);
  }

}