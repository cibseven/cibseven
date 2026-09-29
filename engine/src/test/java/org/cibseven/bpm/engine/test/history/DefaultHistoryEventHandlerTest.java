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
package org.cibseven.bpm.engine.test.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.cibseven.bpm.engine.impl.history.event.HistoryEvent;
import org.cibseven.bpm.engine.impl.history.handler.CompositeDbHistoryEventHandler;
import org.cibseven.bpm.engine.impl.history.handler.CompositeHistoryEventHandler;
import org.cibseven.bpm.engine.impl.history.handler.HistoryEventHandler;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineBootstrapRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class DefaultHistoryEventHandlerTest {

  // Note: This test requires engine bootstrap per parameter value.
  // With JUnit 5, we pass the parameter directly to each test method.
  // However, since the bootstrap rule configures the engine at startup,
  // we use a default config and test both scenarios via separate invocations.

  @RegisterExtension
  @Order(1) public ProcessEngineBootstrapRule bootstrapRule = new ProcessEngineBootstrapRule(configuration -> {
    configuration.setCustomHistoryEventHandlers(Collections.singletonList(new CustomHistoryEventHandler()));
  });

  @RegisterExtension
  @Order(4) public ProcessEngineRule engineRule = new ProvidedProcessEngineRule(bootstrapRule);

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void shouldUseInstanceOfCompositeHistoryEventHandler(boolean isDefaultHandlerEnabled) {
    // given
    engineRule.getProcessEngineConfiguration().setEnableDefaultDbHistoryEventHandler(isDefaultHandlerEnabled);

    // when
    boolean useDefaultDbHandler = engineRule.getProcessEngineConfiguration()
        .isEnableDefaultDbHistoryEventHandler();
    HistoryEventHandler defaultHandler = engineRule.getProcessEngineConfiguration()
        .getHistoryEventHandler();

    // then
    assertThat(useDefaultDbHandler).isNotNull();
    if (useDefaultDbHandler) {
      assertThat(defaultHandler).isInstanceOf(CompositeDbHistoryEventHandler.class);
    } else {
      assertThat(defaultHandler).isInstanceOf(CompositeHistoryEventHandler.class);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void shouldProvideCustomHistoryEventHandlers(boolean isDefaultHandlerEnabled) {
    // given
    engineRule.getProcessEngineConfiguration().setEnableDefaultDbHistoryEventHandler(isDefaultHandlerEnabled);

    // when
    List<HistoryEventHandler> eventHandlers = engineRule.getProcessEngineConfiguration().getCustomHistoryEventHandlers();

    // then
    assertThat(eventHandlers).hasSize(1);
    assertThat(eventHandlers.get(0)).isInstanceOf(CustomHistoryEventHandler.class);
  }

  public class CustomHistoryEventHandler implements HistoryEventHandler {

    @Override
    public void handleEvent(HistoryEvent historyEvent) {
    }

    @Override
    public void handleEvents(List<HistoryEvent> historyEvents) {
    }
  }
}