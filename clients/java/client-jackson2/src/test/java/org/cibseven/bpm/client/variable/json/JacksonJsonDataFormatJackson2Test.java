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
package org.cibseven.bpm.client.variable.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.cibseven.bpm.client.exception.DataFormatException;
import org.cibseven.bpm.client.impl.ExternalTaskClientBuilderImpl;
import org.cibseven.bpm.client.spi.DataFormat;
import org.cibseven.bpm.client.spi.DataFormatConfigurator;
import org.cibseven.bpm.client.variable.impl.format.json.JacksonJsonDataFormat;
import org.cibseven.bpm.client.variable.impl.format.json.JacksonJsonDataFormatProvider;
import org.cibseven.bpm.client.variable.json.fixture.FixtureItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Behaviour of the deprecated Jackson 2 variant that differs from {@code cibseven-external-task-client}
 * (see {@code JacksonJsonDataFormatJackson3Test} there).
 */
public class JacksonJsonDataFormatJackson2Test {

  protected JacksonJsonDataFormat dataFormat;

  @BeforeEach
  public void setUp() {
    dataFormat = (JacksonJsonDataFormat) new JacksonJsonDataFormatProvider().createInstance();
  }

  @Test
  public void shouldUseAPlainJackson2Mapper() {
    assertThat(dataFormat.getObjectMapper()).isInstanceOf(ObjectMapper.class);
    assertThat(dataFormat.getObjectMapper().getClass().getName()).isEqualTo("com.fasterxml.jackson.databind.ObjectMapper");
  }

  @Test
  public void shouldConfigureTheMutableMapperInAConfigurator() {
    // given
    DataFormatConfigurator<JacksonJsonDataFormat> configurator = new DataFormatConfigurator<>() {
      public Class<JacksonJsonDataFormat> getDataFormatClass() {
        return JacksonJsonDataFormat.class;
      }

      public void configure(JacksonJsonDataFormat format) {
        format.getObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
      }
    };
    Map<String, DataFormat> dataFormats = new HashMap<>(Map.of("application/json", dataFormat));

    // when
    new ExternalTaskClientBuilderImpl() {
      {
        applyConfigurator(dataFormats, configurator);
      }
    };

    // then
    assertThat(dataFormat.writeValue(new FixtureItem("A-1", 2)))
        .isEqualTo("{\n  \"sku\" : \"A-1\",\n  \"qty\" : 2\n}");
    assertThat((Object) dataFormat.readValue("{\"sku\":\"A-1\",\"qty\":2,\"extra\":1}", FixtureItem.class.getName()))
        .isEqualTo(new FixtureItem("A-1", 2));
  }

  /**
   * Without the jsr310/jdk8 modules, Jackson 2 cannot write java.time types and Optional (Jackson 3 can).
   */
  @Test
  public void shouldNotWriteJavaTimeAndOptional() {
    assertThatThrownBy(() -> dataFormat.writeValue(LocalDate.of(2026, 11, 7))).isInstanceOf(DataFormatException.class);
    assertThatThrownBy(() -> dataFormat.writeValue(Optional.of("x"))).isInstanceOf(DataFormatException.class);
    assertThat(dataFormat.getCanonicalTypeName(Optional.of("x"))).isEqualTo("java.util.Optional");
  }

}
