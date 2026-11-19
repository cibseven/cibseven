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
package org.cibseven.bpm.spring.boot.starter.spin;

import static org.assertj.core.api.Assertions.assertThat;

import org.cibseven.spin.impl.json.jackson.format.JacksonJsonDataFormat;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.ConstructorDetector;
import tools.jackson.databind.cfg.ConstructorDetector.SingleArgConstructor;

public class CamundaJackson3FormatConfiguratorParameterNamesTest {

  private final CamundaJackson3FormatConfiguratorParameterNames configurator =
      new CamundaJackson3FormatConfiguratorParameterNames();

  @Test
  public void shouldUseParameterNamesButNotNextToDefaultConstructor() {
    JacksonJsonDataFormat dataFormat = new JacksonJsonDataFormat("application/json");

    configurator.configure(dataFormat);

    ObjectMapper objectMapper = dataFormat.getObjectMapper();
    assertThat(objectMapper.deserializationConfig().isEnabled(MapperFeature.DETECT_PARAMETER_NAMES)).isTrue();
    // as with Jackson 2: a class with a constructor without parameters is read through it and its setters
    assertThat(objectMapper.deserializationConfig().getConstructorDetector().allowImplicitWithDefaultConstructor())
      .isFalse();
  }

  @Test
  public void shouldKeepConstructorDetectorConfiguredBefore() {
    // given an application's own configurator (META-INF/services), which Spin applies before the starter's
    JacksonJsonDataFormat dataFormat = new JacksonJsonDataFormat("application/json");
    dataFormat.setObjectMapper(dataFormat.getObjectMapper().rebuild()
        .constructorDetector(ConstructorDetector.USE_DELEGATING.withRequireAnnotation(true))
        .build());

    // when
    configurator.configure(dataFormat);

    // then
    ConstructorDetector constructorDetector = dataFormat.getObjectMapper().deserializationConfig()
        .getConstructorDetector();
    assertThat(constructorDetector.singleArgMode()).isEqualTo(SingleArgConstructor.DELEGATING);
    assertThat(constructorDetector.requireCtorAnnotation()).isTrue();
    assertThat(constructorDetector.allowImplicitWithDefaultConstructor()).isFalse();
  }

  @Test
  public void shouldUseImplicitConstructorNextToDefaultConstructorWhenPropertiesBasedPreferred() {
    // as with Jackson 2, where ConstructorDetector.USE_PROPERTIES_BASED also used such a constructor
    JacksonJsonDataFormat dataFormat = new JacksonJsonDataFormat("application/json");
    dataFormat.setObjectMapper(dataFormat.getObjectMapper().rebuild()
        .constructorDetector(ConstructorDetector.USE_PROPERTIES_BASED)
        .build());

    configurator.configure(dataFormat);

    ConstructorDetector constructorDetector = dataFormat.getObjectMapper().deserializationConfig()
        .getConstructorDetector();
    assertThat(constructorDetector.singleArgMode()).isEqualTo(SingleArgConstructor.PROPERTIES);
    assertThat(constructorDetector.allowImplicitWithDefaultConstructor()).isTrue();
  }
}
