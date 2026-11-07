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
package org.cibseven.spin.impl.json.jackson.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.cibseven.spin.impl.json.jackson.JacksonJsonNode;
import org.cibseven.spin.json.SpinJsonDataFormatException;
import org.cibseven.spin.json.SpinJsonException;
import org.cibseven.spin.json.SpinJsonNode;
import org.cibseven.spin.json.mapping.EmptyBean;
import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.Jackson3JsonProvider;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

public class JacksonJsonDataFormatTest {

  @Test
  public void shouldRebuildDefaultJsonPathConfigurationWithNewObjectMapper() {
    // given
    JacksonJsonDataFormat format = new JacksonJsonDataFormat("test");
    ObjectMapper newMapper = format.getObjectMapper().rebuild().build();

    // when
    format.setObjectMapper(newMapper);

    // then
    assertThat(format.getObjectMapper()).isSameAs(newMapper);
    assertThat(format.getJsonPathConfiguration().jsonProvider()).isInstanceOf(Jackson3JsonProvider.class);
    assertThat(((Jackson3JsonProvider) format.getJsonPathConfiguration().jsonProvider()).getObjectMapper())
        .isSameAs(newMapper);
  }

  @Test
  public void shouldKeepCustomJsonPathConfigurationOnNewObjectMapper() {
    // given
    JacksonJsonDataFormat format = new JacksonJsonDataFormat("test");
    Configuration customConfiguration = format.getJsonPathConfiguration().addOptions(Option.SUPPRESS_EXCEPTIONS);
    format.setJsonPathConfiguration(customConfiguration);

    // when
    format.setObjectMapper(JsonMapper.builder().build());

    // then
    assertThat(format.getJsonPathConfiguration()).isSameAs(customConfiguration);
  }

  @Test
  public void shouldKeepJsonPathConfigurationOfOtherObjectMapper() {
    // given
    ObjectMapper otherMapper = JsonMapper.builder().build();
    Configuration otherConfiguration = Configuration.builder()
        .jsonProvider(new Jackson3JsonProvider(otherMapper))
        .build();
    JacksonJsonDataFormat format = new JacksonJsonDataFormat("test", JsonMapper.builder().build(), otherConfiguration);

    // when
    format.setObjectMapper(JsonMapper.builder().build());

    // then
    assertThat(format.getJsonPathConfiguration()).isSameAs(otherConfiguration);
  }

  @Test
  public void shouldUseNewObjectMapperInJsonPathQueries() {
    // given
    JacksonJsonDataFormat format = new JacksonJsonDataFormat("test");
    SpinJsonNode json = format.createWrapperInstance(format.getObjectMapper().readTree("{\"count\": 1}"));
    assertThat(json.jsonPath("$.count").numberValue()).isInstanceOf(Integer.class);

    // when: a configurator replaces the immutable mapper
    format.setObjectMapper(format.getObjectMapper().rebuild()
        .enable(DeserializationFeature.USE_LONG_FOR_INTS)
        .build());

    // then: JsonPath parses with the new mapper
    assertThat(json.jsonPath("$.count").numberValue()).isInstanceOf(Long.class);
  }

  @Test
  public void shouldWrapSerializationFailureInSpinException() {
    // given: Jackson 2 defaults fail on empty beans
    JacksonJsonDataFormat format = new JacksonJsonDataFormat("test");

    // when / then
    assertThatThrownBy(() -> format.getMapper().mapJavaToInternal(new EmptyBean()))
        .isInstanceOf(SpinJsonDataFormatException.class)
        .hasMessageContaining("SPIN/JACKSON-JSON-01009")
        .hasCauseInstanceOf(JacksonException.class);
  }

  @Test
  public void shouldNameJsonNullAsNullInIndexOfError() {
    // given
    JacksonJsonDataFormat format = new JacksonJsonDataFormat("test");
    JacksonJsonNode array = (JacksonJsonNode) format.createWrapperInstance(
        JsonNodeFactory.instance.arrayNode().add("a"));
    SpinJsonNode jsonNull = format.createWrapperInstance(JsonNodeFactory.instance.nullNode());

    // when / then: same message text as with Jackson 2's JsonNode.asText()
    assertThatThrownBy(() -> array.indexOf(jsonNull))
        .isInstanceOf(SpinJsonException.class)
        .hasMessageContaining("'null'");
    assertThatThrownBy(() -> array.lastIndexOf("b"))
        .isInstanceOf(SpinJsonException.class)
        .hasMessageContaining("'b'");
  }

}
