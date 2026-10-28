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
package org.cibseven.bpm.engine.test.api.cfg;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Function;

import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.ProcessEngineImpl;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The webclient tables (MOD_, CHAT_, NOTIF_) are the optional webext schema component. These tests
 * cover the whole chain the configuration endpoint depends on: the switch decides whether the
 * component is created, the create script writes webclient.extensions.version into ACT_GE_PROPERTY, and
 * BootstrapEngineCommand reads it back into the configuration.
 *
 * <p>No table is created when the component is off, so the version is then absent - that is how the
 * webclient tells it cannot use those features.
 */
public class WebclientExtensionsCfgTest {

  protected ProcessEngineImpl engine;

  @AfterEach
  public void closeEngine() {
    if (engine != null) {
      engine.close();
      engine = null;
    }
  }

  @Test
  public void shouldNotCreateTheWebclientTablesByDefault() {
    engine = buildEngine("webext-default", configuration -> configuration);

    assertThat(webextTablePresent()).isFalse();
    assertThat(webclientExtensionsVersion()).isNull();
  }

  @Test
  public void shouldCreateTheWebclientTablesWhenSwitchedOn() {
    engine = buildEngine("webext-on", configuration -> {
      configuration.setWebclientExtensionsEnabled(true);
      return configuration;
    });

    assertThat(webextTablePresent()).isTrue();
    assertThat(webclientExtensionsVersion())
        .as("the create script writes the release it belongs to")
        .matches("\\d+\\.\\d+\\.\\d+");
  }

  @Test
  public void shouldLeaveTheWebclientTablesOutWhenSwitchedOff() {
    engine = buildEngine("webext-off", configuration -> {
      configuration.setWebclientExtensionsEnabled(false);
      return configuration;
    });

    assertThat(webextTablePresent()).isFalse();
    assertThat(webclientExtensionsVersion()).isNull();
  }

  /** modelerEnabled is deprecated but still has to drive the same component. */
  @Test
  public void shouldHonourTheDeprecatedModelerEnabled() {
    engine = buildEngine("modeler-on", configuration -> {
      configuration.setModelerEnabled(true);
      return configuration;
    });

    assertThat(configuration().isWebclientExtensionsEnabled()).isTrue();
    assertThat(webextTablePresent()).isTrue();
    assertThat(webclientExtensionsVersion()).isNotNull();
  }

  /** Both names address one field, so neither can report something the other contradicts. */
  @Test
  public void shouldReportBothSwitchNamesConsistently() {
    engine = buildEngine("webext-aliases", configuration -> {
      configuration.setWebclientExtensionsEnabled(true);
      return configuration;
    });

    assertThat(configuration().isModelerEnabled()).isTrue();

    configuration().setModelerEnabled(false);
    assertThat(configuration().isWebclientExtensionsEnabled()).isFalse();
  }

  protected ProcessEngineImpl buildEngine(String name,
      Function<StandaloneInMemProcessEngineConfiguration, StandaloneInMemProcessEngineConfiguration> customize) {
    StandaloneInMemProcessEngineConfiguration configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration
      .setProcessEngineName(name + "-engine")
      .setJdbcUrl("jdbc:h2:mem:" + name)
      .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_CREATE_DROP)
      .setJobExecutorActivate(false);
    return (ProcessEngineImpl) customize.apply(configuration).buildProcessEngine();
  }

  protected ProcessEngineConfigurationImpl configuration() {
    return engine.getProcessEngineConfiguration();
  }

  /** Reads the database rather than the switch, so a misnamed script resource shows up here. */
  protected boolean webextTablePresent() {
    return configuration().getCommandExecutorTxRequired()
        .execute((CommandContext commandContext) -> commandContext.getDbSqlSession().isWebextTablePresent());
  }

  protected String webclientExtensionsVersion() {
    return configuration().getWebclientExtensionsVersion();
  }
}
