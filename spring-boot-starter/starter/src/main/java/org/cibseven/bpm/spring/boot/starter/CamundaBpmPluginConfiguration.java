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
package org.cibseven.bpm.spring.boot.starter;

import org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.cibseven.bpm.spring.boot.starter.plugin.ApplicationContextClassloaderSwitchPlugin;
import org.cibseven.bpm.spring.boot.starter.spin.CamundaJackson3FormatConfiguratorParameterNames;
import org.cibseven.bpm.spring.boot.starter.spin.CamundaJacksonFormatConfiguratorJSR310;
import org.cibseven.bpm.spring.boot.starter.spin.CamundaJacksonFormatConfiguratorJdk8;
import org.cibseven.bpm.spring.boot.starter.spin.CamundaJacksonFormatConfiguratorParameterNames;
import org.cibseven.bpm.spring.boot.starter.spin.SpringBootSpinProcessEnginePlugin;
import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.cibseven.spin.plugin.impl.SpinProcessEnginePlugin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.devtools.restart.ConditionalOnInitializedRestarter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@SuppressWarnings("removal") // the Jackson 2 configurators of the deprecated Jackson 2 variant of Spin
public class CamundaBpmPluginConfiguration {

  /**
   * Marker class that only the deprecated Jackson 2 variant of Spin JSON has
   * ({@code cibseven-spin-dataformat-json-jackson2}, {@code cibseven-spin-dataformat-all-jackson2}).
   */
  static final String SPIN_JACKSON2_VARIANT = "org.cibseven.spin.impl.json.jackson.Jackson2Variant";

  /*
     The Jackson 2 modules for Java 8 types (JSR310, Jdk8, ParameterNames) are registered only with the
     deprecated Jackson 2 variant of Spin (`cibseven-spin-dataformat-json-jackson2`). Jackson 3, used by
     `cibseven-spin-dataformat-json-jackson` and `cibseven-spin-dataformat-all`, supports java.time
     and Optional out of the box; parameter names are built in too, but Spin's Jackson 2 defaults turn
     them off, so the starter turns them on again (as the ParameterNames module did with Jackson 2), and keeps
     Jackson 2's choice of constructor for classes that also have one without parameters.
     `cibseven-spin-dataformat-all-jackson2` comes with a
     shaded Jackson ObjectMapper (prefixed with `spinjar`), which breaks auto-configuration for the
     Jackson Java 8 modules.
  */

  @ConditionalOnClass(name = {"org.cibseven.spin.impl.json.jackson.format.JacksonJsonDataFormat",
      "tools.jackson.databind.ObjectMapper"})
  @ConditionalOnMissingClass({SPIN_JACKSON2_VARIANT, "spinjar.tools.jackson.databind.ObjectMapper"})
  @Configuration
  static class SpinDataFormatConfigurationJackson3ParameterNames {

    @Bean
    @ConditionalOnMissingBean(name = "spinDataFormatConfiguratorJackson3ParameterNames")
    public static CamundaJackson3FormatConfiguratorParameterNames spinDataFormatConfiguratorJackson3ParameterNames() {
      return new CamundaJackson3FormatConfiguratorParameterNames();
    }

  }

  @ConditionalOnClass(name = {SPIN_JACKSON2_VARIANT, "com.fasterxml.jackson.datatype.jsr310.JavaTimeModule"})
  @ConditionalOnMissingClass("spinjar.com.fasterxml.jackson.databind.ObjectMapper")
  @Configuration
  static class SpinDataFormatConfigurationJSR310 {

    @Bean
    @ConditionalOnMissingBean(name = "spinDataFormatConfiguratorJSR310")
    public static CamundaJacksonFormatConfiguratorJSR310 spinDataFormatConfiguratorJSR310() {
      return new CamundaJacksonFormatConfiguratorJSR310();
    }

  }

  @ConditionalOnClass(name = {SPIN_JACKSON2_VARIANT, "com.fasterxml.jackson.module.paramnames.ParameterNamesModule"})
  @ConditionalOnMissingClass("spinjar.com.fasterxml.jackson.databind.ObjectMapper")
  @Configuration
  static class SpinDataFormatConfigurationParameterNames {

    @Bean
    @ConditionalOnMissingBean(name = "spinDataFormatConfiguratorParameterNames")
    public static CamundaJacksonFormatConfiguratorParameterNames spinDataFormatConfiguratorParameterNames() {
      return new CamundaJacksonFormatConfiguratorParameterNames();
    }

  }

  @ConditionalOnClass(name = {SPIN_JACKSON2_VARIANT, "com.fasterxml.jackson.datatype.jdk8.Jdk8Module"})
  @ConditionalOnMissingClass("spinjar.com.fasterxml.jackson.databind.ObjectMapper")
  @Configuration
  static class SpinDataFormatConfigurationJdk8 {

    @Bean
    @ConditionalOnMissingBean(name = "spinDataFormatConfiguratorJdk8")
    public static CamundaJacksonFormatConfiguratorJdk8 spinDataFormatConfiguratorJdk8() {
      return new CamundaJacksonFormatConfiguratorJdk8();
    }

  }

  @ConditionalOnClass(SpinProcessEnginePlugin.class)
  @Configuration
  static class SpinConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "spinProcessEnginePlugin")
    public static ProcessEnginePlugin spinProcessEnginePlugin() {
      return new SpringBootSpinProcessEnginePlugin();
    }

  }

  @ConditionalOnClass(ConnectProcessEnginePlugin.class)
  @Configuration
  static class ConnectConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "connectProcessEnginePlugin")
    public static ProcessEnginePlugin connectProcessEnginePlugin() {
      return new ConnectProcessEnginePlugin();
    }
  }


  /*
    Provide option to apply application context classloader switch when Spring
    Spring Developer tools are enabled
    For more details: https://jira.camunda.com/browse/CAM-9043
   */
  @ConditionalOnInitializedRestarter
  @Configuration
  static class InitializedRestarterConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "applicationContextClassloaderSwitchPlugin")
    public ApplicationContextClassloaderSwitchPlugin applicationContextClassloaderSwitchPlugin() {
      return new ApplicationContextClassloaderSwitchPlugin();
    }
  }


}
