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
package org.cibseven.connect.plugin.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.junit.jupiter.api.Test;

/**
 * A connector that needs engine hooks declares a {@code ProcessEnginePlugin}, and this
 * plugin carries it.
 *
 * <p>The reason it exists: a connector is found through
 * {@code ServiceLoader<ConnectorProvider>} and cannot register a parse listener or a job
 * handler itself. Without this, the only way to give a connector hooks was to build them
 * into this generic plugin — which is where the agentic ad hoc machinery sat until it
 * moved to the AI agent connector, and why that connector's plugin has to be found here.
 *
 * <p>The declaration under test is {@code src/test/resources/META-INF/services}, so the
 * lookup runs against a real service file rather than an injected list.
 */
public class ConnectorPluginDiscoveryTest {

  /** What a connector's plugin would do, recorded rather than performed. */
  public static class Recorded extends AbstractProcessEnginePlugin {

    public static final List<String> PHASES = new ArrayList<String>();

    @Override
    public void preInit(ProcessEngineConfigurationImpl configuration) {
      PHASES.add("preInit");
    }

    @Override
    public void postInit(ProcessEngineConfigurationImpl configuration) {
      PHASES.add("postInit");
    }

    @Override
    public void postProcessEngineBuild(ProcessEngine processEngine) {
      PHASES.add("postProcessEngineBuild");
    }
  }

  @Test
  public void aDeclaredPluginIsCarriedThroughEveryPhase() {
    Recorded.PHASES.clear();
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setJdbcUrl("jdbc:h2:mem:connector-plugin-discovery;DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    configuration.setEnforceHistoryTimeToLive(false);
    configuration.setProcessEnginePlugins(
        Collections.singletonList(new ConnectProcessEnginePlugin()));

    ProcessEngine engine = configuration.buildProcessEngine();
    try {
      assertThat(Recorded.PHASES)
          .as("all three lifecycle phases reach the connector's plugin")
          .containsExactly("preInit", "postInit", "postProcessEngineBuild");
    } finally {
      engine.close();
    }
  }

  /**
   * This plugin is itself a {@code ProcessEnginePlugin}. If a distribution ever declares
   * it as a service too, the lookup must not hand it back to itself: the engine would
   * recurse on start until the stack ran out.
   */
  @Test
  public void thePluginDoesNotFindItself() {
    List<?> found = new ConnectProcessEnginePlugin()
        .loadConnectorPlugins(getClass().getClassLoader());

    assertThat(found).noneMatch(plugin -> plugin instanceof ConnectProcessEnginePlugin);
    assertThat(found).as("the declared test plugin is found").hasAtLeastOneElementOfType(
        Recorded.class);
  }
}
