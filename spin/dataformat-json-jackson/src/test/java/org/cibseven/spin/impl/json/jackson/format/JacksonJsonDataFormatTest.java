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

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.cibseven.spin.impl.json.jackson.JacksonJsonNode;
import org.cibseven.spin.json.SpinJsonDataFormatException;
import org.cibseven.spin.json.SpinJsonException;
import org.cibseven.spin.json.SpinJsonNode;
import org.cibseven.spin.json.mapping.EmptyBean;
import org.cibseven.spin.json.golden.Jackson2CompatibilityTest.Accessors;
import org.cibseven.spin.json.golden.Jackson2CompatibilityTest.FormattedDates;
import org.cibseven.spin.json.golden.Jackson2CompatibilityTest.VoidGetter;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.Jackson3JsonProvider;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.introspect.DefaultAccessorNamingStrategy;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.ser.jdk.JDKStringLikeSerializer;
import tools.jackson.databind.util.StdDateFormat;

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
  public void shouldKeepUserJsonPathConfigurationThatLooksLikeTheDefault() {
    // given: a configuration of the user with the same provider types and the same mapper as the default one
    JacksonJsonDataFormat format = new JacksonJsonDataFormat("test");
    ObjectMapper mapper = format.getObjectMapper();
    Configuration userConfiguration = Configuration.builder()
        .jsonProvider(new Jackson3JsonProvider(mapper))
        .mappingProvider(new com.jayway.jsonpath.spi.mapper.Jackson3MappingProvider(mapper))
        .build();
    format.setJsonPathConfiguration(userConfiguration);

    // when
    format.setObjectMapper(mapper.rebuild().build());

    // then: only the configuration the data format created itself follows the new mapper
    assertThat(format.getJsonPathConfiguration()).isSameAs(userConfiguration);
  }

  @Test
  public void shouldRebuildDefaultJsonPathConfigurationOnEveryNewObjectMapper() {
    // given
    JacksonJsonDataFormat format = new JacksonJsonDataFormat("test");
    format.setObjectMapper(format.getObjectMapper().rebuild().build());
    ObjectMapper secondMapper = format.getObjectMapper().rebuild().build();

    // when
    format.setObjectMapper(secondMapper);

    // then
    assertThat(((Jackson3JsonProvider) format.getJsonPathConfiguration().jsonProvider()).getObjectMapper())
        .isSameAs(secondMapper);
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
  public void shouldReadAndWriteJsonNestedDeeperThanJackson3Default() {
    // given: 700 levels, above Jackson 3's default limit of 500, below Jackson 2's limit of 1000
    String depth700 = "[".repeat(700) + "1" + "]".repeat(700);
    JacksonJsonDataFormat format = new JacksonJsonDataFormat("test");
    assertThatThrownBy(() -> JsonMapper.builder().build().readTree(depth700))
        .hasMessageContaining("exceeds the maximum allowed (500");

    // when: read, write, map to Java and back
    Object node = format.getReader().readInput(new StringReader(depth700));
    StringWriter written = new StringWriter();
    format.getWriter().writeToWriter(written, node);
    Object javaValue = format.getMapper().mapInternalToJava(node, List.class);
    Object nodeAgain = format.getMapper().mapJavaToInternal(javaValue);

    // then
    assertThat(written.toString()).isEqualTo(depth700);
    assertThat(nodeAgain).isEqualTo(node);
    assertThat(JacksonJsonDataFormat.MAX_NESTING_DEPTH).isEqualTo(1000);
  }

  @Test
  public void shouldWriteDeepJsonInToStringAndJsonPath() {
    // given: 700 levels; Jackson 3's JsonNode.toString() stops at 500
    String depth700 = "[".repeat(700) + "1" + "]".repeat(700);
    JacksonJsonDataFormat format = new JacksonJsonDataFormat("test");
    SpinJsonNode json = format.createWrapperInstance(format.getReader().readInput(new StringReader(depth700)));

    // when / then: SpinJsonNode#toString() (scripts, JsonPath queries) writes with the configured mapper
    assertThat(json.toString()).isEqualTo(depth700);
    assertThat(json.jsonPath("$").element().toString()).isEqualTo(depth700);
  }

  @Test
  public void shouldPreviewDeepJsonInDeserializationError() {
    // given
    String depth700 = "[".repeat(700) + "1" + "]".repeat(700);
    JacksonJsonDataFormat format = new JacksonJsonDataFormat("test");
    Object node = format.getReader().readInput(new StringReader(depth700));

    // when / then: the error names the input instead of failing on Jackson 3's JsonNode.toString()
    assertThatThrownBy(() -> format.getMapper().mapInternalToJava(node, Integer.class))
        .isInstanceOf(SpinJsonException.class)
        .hasMessageContaining("SPIN/JACKSON-JSON-01006 Cannot deserialize '[[[[[[[[[[...'");
  }

  @Test
  public void shouldPreviewShortJsonInDeserializationError() {
    // given: JSON shorter than the 10 characters of the preview
    JacksonJsonDataFormat format = new JacksonJsonDataFormat("test");
    Object node = format.getReader().readInput(new StringReader("[\"abc\"]"));

    // when / then
    assertThatThrownBy(() -> format.getMapper().mapInternalToJava(node, Integer.class))
        .isInstanceOf(SpinJsonException.class)
        .hasMessageContaining("Cannot deserialize '[\"abc\"]...'");
  }

  @Test
  public void shouldRejectJsonNestedDeeperThanJackson2Limit() {
    String depth1001 = "[".repeat(1001) + "]".repeat(1001);
    JacksonJsonDataFormat format = new JacksonJsonDataFormat("test");

    assertThatThrownBy(() -> format.getReader().readInput(new StringReader(depth1001)))
        .isInstanceOf(SpinJsonDataFormatException.class)
        .hasStackTraceContaining("exceeds the maximum allowed (1000");
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

  /** A property whose class comes from the JSON. */
  public static class ClassIdValue {
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
    public Object value;
  }

  @Test
  public void shouldNotReadClassIdsForTooGenericPropertyTypes() {
    // Jackson 3's default PolymorphicTypeValidator, kept on purpose: no class from the JSON for a property of type
    // Object, Serializable, Comparable, ... (Jackson 2 read it, see the -jackson2 module)
    JacksonJsonDataFormat format = new JacksonJsonDataFormat("test");
    Object node = format.getReader().readInput(new StringReader("{\"value\":[\"java.util.Locale\",\"en_US\"]}"));

    assertThatThrownBy(() -> format.getMapper().mapInternalToJava(node, ClassIdValue.class))
        .isInstanceOf(SpinJsonException.class)
        .hasStackTraceContaining("PolymorphicTypeValidator");
  }

  @Test
  public void shouldReadClassIdsAllowedByAPolymorphicTypeValidator() {
    // given: a configurator allows the classes of the application
    JacksonJsonDataFormat format = new JacksonJsonDataFormat("test");
    format.setObjectMapper(format.getObjectMapper().rebuild()
        .polymorphicTypeValidator(BasicPolymorphicTypeValidator.builder().allowIfSubType("java.util.").build())
        .build());
    Object node = format.getReader().readInput(new StringReader("{\"value\":[\"java.util.Locale\",\"en_US\"]}"));

    // when
    ClassIdValue value = format.getMapper().mapInternalToJava(node, ClassIdValue.class);

    // then
    assertThat(value.value).isEqualTo(Locale.US);
  }

  @Test
  public void shouldSwitchToJackson3StandardsInAConfigurator() {
    // given: the switches of the update guide, as a DataFormatConfigurator would apply them
    JacksonJsonDataFormat format = new JacksonJsonDataFormat("test");
    format.setObjectMapper(format.getObjectMapper().rebuild()
        .accessorNaming(new DefaultAccessorNamingStrategy.Provider())
        .enable(MapperFeature.ALLOW_VOID_VALUED_PROPERTIES)
        .addModule(new SimpleModule("jackson3-standards").addSerializer(JDKStringLikeSerializer.find(Locale.class)))
        .defaultDateFormat(new StdDateFormat())
        .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build());

    // when / then: Jackson 3's own output
    assertThat(format.getMapper().mapInternalToJava(format.getMapper().mapJavaToInternal(new Accessors()), Map.class))
        .containsOnlyKeys("x", "a1", "URL", "URLValue", "XCoordinate", "OK", "IBAN");
    assertThat(write(format, new VoidGetter())).isEqualTo("{\"value\":1,\"nothing\":null}");
    assertThat(write(format, Locale.US)).isEqualTo("\"en-US\"");
    assertThat(write(format, new FormattedDates()))
        .isEqualTo("{\"date\":\"1970-01-01T00:00:00.000Z\",\"calendar\":\"1970-01-01T00:00:00.000Z\"}");
  }

  @Test
  public void shouldSwitchCompletelyToJackson3WithANewMapper() {
    // given: a complete switch to Jackson 3's defaults; the application adds its own modules to this mapper
    JacksonJsonDataFormat format = new JacksonJsonDataFormat("test");
    format.setObjectMapper(JsonMapper.builder().build());

    // when / then: Jackson 3's own output, also for dates that are not annotated
    assertThat(format.getMapper().mapInternalToJava(format.getMapper().mapJavaToInternal(new Accessors()), Map.class))
        .containsOnlyKeys("x", "a1", "URL", "URLValue", "xCoordinate", "OK", "IBAN"); // FIX_FIELD_NAME_UPPER_CASE_PREFIX is on
    assertThat(write(format, new VoidGetter())).isEqualTo("{\"nothing\":null,\"value\":1}"); // sorted: SORT_PROPERTIES_ALPHABETICALLY is on
    assertThat(write(format, Locale.US)).isEqualTo("\"en-US\"");
    assertThat(write(format, new java.util.Date(0))).isEqualTo("\"1970-01-01T00:00:00.000Z\"");
  }

  @Test
  public void shouldWriteYearAndSqlDateLikeJackson2WhenDatesAreText() {
    // given: dates as text, as a configurator may set; Jackson 2 with JavaTimeModule wrote "2020" and toString()
    JacksonJsonDataFormat format = new JacksonJsonDataFormat("test");
    format.setObjectMapper(format.getObjectMapper().rebuild().disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build());
    java.util.TimeZone previous = java.util.TimeZone.getDefault();
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"));
    try {
      // when / then: Jackson 3 alone writes 2020 and "1970-01-01T00:00:00.000+00:00"
      assertThat(write(format, java.time.Year.of(2020))).isEqualTo("\"2020\"");
      assertThat(write(format, new java.sql.Date(0))).isEqualTo("\"1969-12-31\"");
    } finally {
      java.util.TimeZone.setDefault(previous);
    }
  }

  protected static String write(JacksonJsonDataFormat format, Object value) {
    StringWriter writer = new StringWriter();
    format.getWriter().writeToWriter(writer, format.getMapper().mapJavaToInternal(value));
    return writer.toString();
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
