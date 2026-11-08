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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

import com.fasterxml.jackson.annotation.JsonTypeInfo;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.introspect.DefaultAccessorNamingStrategy;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.jdk.JDKStringLikeSerializer;
import tools.jackson.databind.util.StdDateFormat;

/**
 * Behaviour of the Jackson 3 based data format that is not shared with the deprecated Jackson 2 variant
 * ({@code cibseven-external-task-client-jackson2}).
 */
public class JacksonJsonDataFormatJackson3Test {

  protected JacksonJsonDataFormat dataFormat;

  @BeforeEach
  public void setUp() {
    dataFormat = (JacksonJsonDataFormat) new JacksonJsonDataFormatProvider().createInstance();
  }

  @Test
  public void shouldReplaceTheImmutableMapperInAConfigurator() {
    // given
    DataFormatConfigurator<JacksonJsonDataFormat> configurator = new DataFormatConfigurator<>() {
      public Class<JacksonJsonDataFormat> getDataFormatClass() {
        return JacksonJsonDataFormat.class;
      }

      public void configure(JacksonJsonDataFormat format) {
        format.setObjectMapper(format.getObjectMapper().rebuild()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build());
      }
    };
    Map<String, DataFormat> dataFormats = new HashMap<>(Map.of("application/json", dataFormat));

    // when
    new ExternalTaskClientBuilderImpl() {
      {
        applyConfigurator(dataFormats, configurator);
      }
    };

    // then the new mapper is used for writing, reading and type resolution
    assertThat(dataFormat.writeValue(new FixtureItem("A-1", 2)))
        .isEqualTo("{\n  \"sku\" : \"A-1\",\n  \"qty\" : 2\n}");
    assertThat((Object) dataFormat.readValue("{\"sku\":\"A-1\",\"qty\":2,\"extra\":1}", FixtureItem.class.getName()))
        .isEqualTo(new FixtureItem("A-1", 2));
    assertThat((Object) dataFormat.readValue("[{\"sku\":\"A-1\",\"qty\":2,\"extra\":1}]",
        "java.util.ArrayList<" + FixtureItem.class.getName() + ">"))
        .isEqualTo(List.of(new FixtureItem("A-1", 2)));
  }

  @Test
  public void shouldKeepTheNestingDepthOfJackson2() {
    // given
    String depth700 = "[".repeat(700) + "]".repeat(700);
    ObjectMapper plainJackson3 = JsonMapper.builderWithJackson2Defaults().build();

    // then Jackson 3 alone stops at depth 500, the client's mapper accepts what Jackson 2 accepted (1000)
    assertThatThrownBy(() -> plainJackson3.readValue(depth700, List.class))
        .hasMessageContaining("exceeds the maximum allowed (500");
    assertThat((Object) dataFormat.readValue(depth700, "java.util.ArrayList")).isNotNull();
    assertThat(JacksonJsonDataFormat.MAX_NESTING_DEPTH).isEqualTo(1000);
  }

  /**
   * Jackson 3 supports java.time and Optional out of the box. The Jackson 2 based client failed to write them
   * (no jsr310/jdk8 module registered), so there are no such stored variables to stay compatible with.
   */
  @Test
  public void shouldWriteJavaTimeAndOptional() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("localDate", LocalDate.of(2026, 11, 7));
    values.put("localDateTime", LocalDateTime.of(2026, 11, 7, 12, 30, 15));
    values.put("instant", Instant.parse("2026-11-07T11:30:15.123Z"));
    values.put("duration", Duration.ofSeconds(90));
    values.put("optional", Optional.of("x"));
    values.put("emptyOptional", Optional.empty());

    assertThat(dataFormat.writeValue(values)).isEqualTo("{\"localDate\":[2026,11,7],"
        + "\"localDateTime\":[2026,11,7,12,30,15],\"instant\":1794051015.123000000,\"duration\":90.000000000,"
        + "\"optional\":\"x\",\"emptyOptional\":null}");
    assertThat(dataFormat.getCanonicalTypeName(Optional.of("x"))).isEqualTo("java.util.Optional<java.lang.Object>");
    assertThat(dataFormat.getCanonicalTypeName(LocalDate.of(2026, 11, 7))).isEqualTo("java.time.LocalDate");
    assertThat((Object) dataFormat.readValue("[2026,11,7]", "java.time.LocalDate")).isEqualTo(LocalDate.of(2026, 11, 7));
    assertThat((Object) dataFormat.readValue("\"x\"", "java.util.Optional<java.lang.Object>")).isEqualTo(Optional.of("x"));
  }

  @Test
  public void shouldWrapJacksonExceptions() {
    assertThatThrownBy(() -> dataFormat.readValue("{", FixtureItem.class.getName()))
        .isInstanceOf(DataFormatException.class)
        .hasCauseInstanceOf(tools.jackson.core.JacksonException.class);
    assertThatThrownBy(() -> dataFormat.writeValue(new Object()))
        .isInstanceOf(DataFormatException.class)
        .hasCauseInstanceOf(tools.jackson.core.JacksonException.class);
  }

  /** A property whose class comes from the JSON. */
  public static class ClassIdValue {
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
    public Object value;
  }

  @Test
  public void shouldNotReadClassIdsForTooGenericPropertyTypes() {
    // Jackson 3's default PolymorphicTypeValidator, kept on purpose: no class from the JSON for a property of type
    // Object, Serializable, Comparable, ... (Jackson 2 read it, see JacksonJsonDataFormatJackson2Test)
    assertThatThrownBy(() -> dataFormat.readValue("{\"value\":[\"java.util.Locale\",\"en_US\"]}", ClassIdValue.class))
        .isInstanceOf(DataFormatException.class)
        .hasStackTraceContaining("PolymorphicTypeValidator");
  }

  @Test
  public void shouldReadClassIdsAllowedByAPolymorphicTypeValidator() {
    // given: a configurator allows the classes of the application
    dataFormat.setObjectMapper(dataFormat.getObjectMapper().rebuild()
        .polymorphicTypeValidator(BasicPolymorphicTypeValidator.builder().allowIfSubType("java.util.").build())
        .build());

    // when
    ClassIdValue value = dataFormat.readValue("{\"value\":[\"java.util.Locale\",\"en_US\"]}", ClassIdValue.class);

    // then
    assertThat(value.value).isEqualTo(Locale.US);
  }

  @Test
  public void shouldSwitchToJackson3StandardsInAConfigurator() {
    // given: the switches of the update guide, as a DataFormatConfigurator would apply them
    dataFormat.setObjectMapper(dataFormat.getObjectMapper().rebuild()
        .accessorNaming(new DefaultAccessorNamingStrategy.Provider())
        .enable(MapperFeature.ALLOW_VOID_VALUED_PROPERTIES)
        .addModule(new SimpleModule("jackson3-standards").addSerializer(JDKStringLikeSerializer.find(Locale.class)))
        .defaultDateFormat(new StdDateFormat())
        .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build());

    // when / then: Jackson 3's own output
    assertThat(dataFormat.readValue(dataFormat.writeValue(new Jackson2CompatibilityTest.Accessors()), Map.class))
        .containsOnlyKeys("x", "a1", "URL", "URLValue", "XCoordinate", "OK", "IBAN");
    assertThat(dataFormat.writeValue(new Jackson2CompatibilityTest.VoidGetter())).isEqualTo("{\"value\":1,\"nothing\":null}");
    assertThat(dataFormat.writeValue(Locale.US)).isEqualTo("\"en-US\"");
    assertThat(dataFormat.writeValue(new Jackson2CompatibilityTest.FormattedDates()))
        .isEqualTo("{\"date\":\"1970-01-01T00:00:00.000Z\",\"calendar\":\"1970-01-01T00:00:00.000Z\"}");
  }

  @Test
  public void shouldSwitchCompletelyToJackson3WithANewMapper() {
    // given: a complete switch to Jackson 3's defaults; the application adds its own modules to this mapper
    dataFormat.setObjectMapper(JsonMapper.builder().build());

    // when / then: Jackson 3's own output, also for dates that are not annotated
    assertThat(dataFormat.readValue(dataFormat.writeValue(new Jackson2CompatibilityTest.Accessors()), Map.class))
        .containsOnlyKeys("x", "a1", "URL", "URLValue", "xCoordinate", "OK", "IBAN"); // FIX_FIELD_NAME_UPPER_CASE_PREFIX is on
    assertThat(dataFormat.writeValue(new Jackson2CompatibilityTest.VoidGetter())).isEqualTo("{\"nothing\":null,\"value\":1}"); // sorted: SORT_PROPERTIES_ALPHABETICALLY is on
    assertThat(dataFormat.writeValue(Locale.US)).isEqualTo("\"en-US\"");
    assertThat(dataFormat.writeValue(new java.util.Date(0))).isEqualTo("\"1970-01-01T00:00:00.000Z\"");
  }
}
