/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
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
package org.cibseven.connect.ai.agent.agentic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The engine says at start when it lacks the function {@code toolParameter} (CIB7-2136), which
 * the connect plugin registers: an older connect plugin beside this connector would otherwise
 * show up only as a bare parse error at deployment.
 */
public class AgenticAdHocProcessEnginePluginTest {

  /** This plugin without the connect plugin's registration, as beside an older connect plugin. */
  @Test
  public void anEngineWithoutTheFunctionSaysSoAtStart() {
    ListAppender<ILoggingEvent> log = errorsDuring(
        new AgenticAdHocProcessEnginePlugin(), "agentic-plugin-without-function");

    assertThat(log.list).anySatisfy(event -> {
      assertThat(event.getLevel()).isEqualTo(Level.ERROR);
      assertThat(event.getFormattedMessage())
          .contains("'toolParameter' is not registered")
          .contains("ConnectProcessEnginePlugin");
    });
  }

  /**
   * Through the connect plugin, as in every distribution, the function is there. The plugin ran,
   * found by the connect plugin: without it nothing would check, and nothing would be said.
   */
  @Test
  public void anEngineThroughTheConnectPluginSaysNothing() {
    StandaloneInMemProcessEngineConfiguration configuration =
        configuration(new ConnectProcessEnginePlugin(), "agentic-plugin-through-connect");
    ListAppender<ILoggingEvent> log = errorsDuring(configuration);

    assertThat(configuration.getCustomPostBPMNParseListeners())
        .as("its parse listener, added before the engine's init")
        .hasAtLeastOneElementOfType(AgenticAdHocParseListener.class);
    assertThat(configuration.getFailedJobCommandFactory())
        .as("its retry of a failed turn, set after the engine's init")
        .isInstanceOf(AgenticTurnFailedJobCommandFactory.class);
    assertThat(log.list).noneMatch(event -> event.getLevel() == Level.ERROR);
  }

  /** What this plugin logs while an engine with the given plugin is built. */
  protected static ListAppender<ILoggingEvent> errorsDuring(ProcessEnginePlugin plugin,
      String name) {
    return errorsDuring(configuration(plugin, name));
  }

  protected static StandaloneInMemProcessEngineConfiguration configuration(
      ProcessEnginePlugin plugin, String name) {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName(name);
    configuration.setJdbcUrl("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    configuration.setProcessEnginePlugins(Collections.singletonList(plugin));
    return configuration;
  }

  /** What this plugin logs while an engine of the configuration is built. */
  protected static ListAppender<ILoggingEvent> errorsDuring(
      StandaloneInMemProcessEngineConfiguration configuration) {
    Logger logger = (Logger) LoggerFactory.getLogger(AgenticAdHocProcessEnginePlugin.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    ProcessEngine engine = null;
    try {
      engine = configuration.buildProcessEngine();
    } finally {
      logger.detachAppender(appender);
      if (engine != null) {
        engine.close();
      }
    }
    return appender;
  }
}
