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
package org.cibseven.bpm.spring.boot.starter.webapp;

import org.cibseven.bpm.spring.boot.starter.CamundaBpmAutoConfiguration;
import org.cibseven.bpm.spring.boot.starter.property.CamundaBpmProperties;
import org.cibseven.bpm.spring.boot.starter.property.WebappProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

public class CamundaBpmLegacyWebappAutoConfigurationIntegrationTest {

  private String bpmDisabled = CamundaBpmProperties.PREFIX + ".enabled=false";

  private String webappDisabled = WebappProperty.PREFIX + ".enabled=false";

  private String webclientDisabled = "cibseven.webclient.enabled=false";

  private WebApplicationContextRunner contextRunner;

  @BeforeEach
  public void setUp() {
    AutoConfigurations autoConfigurationsUnderTest = AutoConfigurations.of(CamundaBpmAutoConfiguration.class,
        CamundaBpmWebappAutoConfiguration.class, CamundaBpmLegacyWebappAutoConfiguration.class);
    AutoConfigurations additionalAutoConfigurations = AutoConfigurations.of(DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class);
    contextRunner = new WebApplicationContextRunner().withConfiguration(autoConfigurationsUnderTest).withConfiguration(additionalAutoConfigurations);
  }

  @Test
  public void test_legacyWebappOnClasspath_shouldInitLegacyWebapps() {
    contextRunner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).hasSingleBean(CamundaBpmWebappAutoConfiguration.class);
      assertThat(context).hasSingleBean(CamundaBpmWebappInitializer.class);
      assertThat(context).hasSingleBean(FaviconResourceResolver.class);
    });
  }

  @Test
  public void test_legacyWebappOnClasspath_and_webappIsDisabled_shouldNotInitWebapp() {
    contextRunner.withPropertyValues(webappDisabled).run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).doesNotHaveBean(CamundaBpmWebappAutoConfiguration.class);
      assertThat(context).doesNotHaveBean(CamundaBpmWebappInitializer.class);
    });
  }

  @Test
  public void test_legacyClassesAreAbsent_shouldNotInitLegacyWebapps() {
    contextRunner
      .withClassLoader(new FilteredClassLoader("org.cibseven.bpm.cockpit"))
      .run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context).hasSingleBean(CamundaBpmWebappAutoConfiguration.class);
        assertThat(context).doesNotHaveBean(CamundaBpmWebappInitializer.class);
        assertThat(context).doesNotHaveBean("resourceLoaderDependingInitHook");
        assertThat(context).doesNotHaveBean(FaviconResourceResolver.class);
      });
  }

  @Test
  public void test_legacyResourcesAreAbsent_shouldNotInitLegacyWebapps() {
    contextRunner
      .withClassLoader(new FilteredClassLoader(new ClassPathResource("META-INF/resources/webjars/camunda/securityFilterRules.json")))
      .run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context).hasSingleBean(CamundaBpmWebappAutoConfiguration.class);
        assertThat(context).doesNotHaveBean(CamundaBpmWebappInitializer.class);
        assertThat(context).doesNotHaveBean("resourceLoaderDependingInitHook");
        assertThat(context).doesNotHaveBean(FaviconResourceResolver.class);
      });
  }

  @Test
  public void test_legacyWebappOnClasspath_and_bpmIsDisabled_shouldNotInitWebapp() {
    contextRunner
    .withPropertyValues(bpmDisabled)
    .withPropertyValues(webclientDisabled)
    .run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).doesNotHaveBean(CamundaBpmWebappAutoConfiguration.class);
      assertThat(context).doesNotHaveBean(CamundaBpmWebappInitializer.class);
    });
  }
}
