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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.cibseven.bpm.client.exception.DataFormatException;
import org.cibseven.bpm.client.impl.ExternalTaskClientBuilderImpl;
import org.cibseven.bpm.client.spi.DataFormat;
import org.cibseven.bpm.client.spi.DataFormatConfigurator;
import org.cibseven.bpm.client.variable.impl.format.json.JacksonJsonDataFormat;
import org.cibseven.bpm.client.variable.impl.format.json.JacksonJsonDataFormatProvider;
import org.cibseven.bpm.client.variable.json.Jackson2CompatibilityTest.Located;
import org.cibseven.bpm.client.variable.json.Jackson2CompatibilityTest.Plain;
import org.cibseven.bpm.client.variable.json.Jackson2CompatibilityTest.Spot;
import org.cibseven.bpm.client.variable.json.fixture.FixtureItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.annotation.JsonValue;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.DeserializationConfig;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.deser.ValueDeserializerModifier;
import tools.jackson.databind.deser.bean.BeanDeserializerBase;
import tools.jackson.databind.deser.std.DelegatingDeserializer;
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

  @Test
  public void shouldWriteYearAndSqlDateLikeJackson2WhenDatesAreText() {
    // given: dates as text, as a configurator may set; Jackson 2 with JavaTimeModule wrote "2020" and toString()
    dataFormat.setObjectMapper(dataFormat.getObjectMapper().rebuild().disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build());
    java.util.TimeZone previous = java.util.TimeZone.getDefault();
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"));
    try {
      // when / then: Jackson 3 alone writes 2020 and "1970-01-01T00:00:00.000+00:00"
      assertThat(dataFormat.writeValue(java.time.Year.of(2020))).isEqualTo("\"2020\"");
      assertThat(dataFormat.writeValue(new java.sql.Date(0))).isEqualTo("\"1969-12-31\"");
    } finally {
      java.util.TimeZone.setDefault(previous);
    }
  }

  @Test
  public void shouldRejectUnknownPropertyWhereTheUnwrappedTypeHasItsOwnDeserializer() {
    // the property is read under its own name, as in Jackson 2
    Coded coded = dataFormat.readValue("{\"name\":\"n\",\"code\":\"c\"}", Coded.class);
    assertThat(coded.code.value()).isEqualTo("c");
    assertThat(dataFormat.writeValue(coded)).isEqualTo("{\"name\":\"n\",\"code\":\"c\"}");
    // and an unknown property fails, as in Jackson 2
    assertThatThrownBy(() -> dataFormat.readValue("{\"name\":\"n\",\"code\":\"c\",\"extra\":1}", Coded.class))
        .hasStackTraceContaining("UnrecognizedPropertyException").hasStackTraceContaining("\"extra\"");

    // also when a module registers the deserializer
    dataFormat.setObjectMapper(dataFormat.getObjectMapper().rebuild().addModule(tokenModule()).build());
    assertThat(dataFormat.readValue("{\"name\":\"n\",\"token\":\"t\"}", Tokened.class).token.value()).isEqualTo("t");
    assertThatThrownBy(() -> dataFormat.readValue("{\"name\":\"n\",\"token\":\"t\",\"extra\":1}", Tokened.class))
        .hasStackTraceContaining("UnrecognizedPropertyException").hasStackTraceContaining("\"extra\"");
  }

  @Test
  public void shouldReadUnwrappedClassThatAnotherThreadUsedWhileJacksonResolvedIt() throws Exception {
    // given a new mapper: thread 1 reads Race first and is held while Jackson resolves Race, after RaceHolder
    SlowDeserializer.entered = new CountDownLatch(1);
    SlowDeserializer.release = new CountDownLatch(1);
    try {
      CompletableFuture<Race> first = CompletableFuture.supplyAsync(() ->
          dataFormat.readValue("{\"name\":\"r\"}", Race.class));
      assertThat(SlowDeserializer.entered.await(10, TimeUnit.SECONDS)).isTrue();
      // when thread 2 reads RaceHolder meanwhile
      assertThat(dataFormat.readValue("{\"id\":\"h\",\"race\":{}}", RaceHolder.class).id).isEqualTo("h");
      // thread 1 is still held: thread 2 read while Jackson was resolving Race
      assertThat(first).isNotDone();
      SlowDeserializer.release.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS).name).isEqualTo("r");
    } finally {
      SlowDeserializer.entered = null;
      SlowDeserializer.release.countDown();
    }

    // then RaceHolder reads the unwrapped class as Jackson 2 does, also afterwards
    RaceHolder holder = dataFormat.readValue(
        "{\"id\":\"h\",\"race\":{\"name\":\"r\",\"street\":\"Main 1\",\"city\":\"Bonn\",\"label\":\"Main 1, Bonn\"}}",
        RaceHolder.class);
    assertThat(holder.race.spot.street).isEqualTo("Main 1");
  }

  @Test
  public void shouldReadUnwrappedClassWhenAModuleWrapsItsDeserializer() {
    // given a module added later, whose modifier runs first and wraps every bean deserializer
    dataFormat.setObjectMapper(dataFormat.getObjectMapper().rebuild().addModule(wrappingModule()).build());
    Wrapping.READS.set(0);

    // then the unwrapped class is read back as in Jackson 2, through the module's wrapper
    Located located = dataFormat.readValue(
        "{\"name\":\"Shop\",\"street\":\"Main 1\",\"city\":\"Bonn\",\"label\":\"Main 1, Bonn\"}",
        Located.class);
    assertThat(located.spot.street).isEqualTo("Main 1");
    assertThat(Wrapping.READS.get()).isPositive();
    // and a class without @JsonUnwrapped still rejects unknown properties
    assertThatThrownBy(() -> dataFormat.readValue("{\"name\":\"n\",\"extra\":1}", Plain.class))
        .hasStackTraceContaining("UnrecognizedPropertyException").hasStackTraceContaining("\"extra\"");
  }

  /** A type with a deserializer of its own: Jackson cannot unwrap it, so {@code @JsonUnwrapped} has no effect. */
  @JsonDeserialize(using = CodeDeserializer.class)
  public static class Code {
    private final String value;

    public Code(String value) {
      this.value = value;
    }

    @JsonValue
    public String value() {
      return value;
    }
  }

  public static class CodeDeserializer extends ValueDeserializer<Code> {
    @Override
    public Code deserialize(JsonParser p, DeserializationContext ctxt) {
      return new Code(p.getString());
    }
  }

  public static class Coded {
    public String name;
    @JsonUnwrapped
    public Code code;
  }

  /** The same with the deserializer registered by a module. */
  public static class Token {
    private final String value;

    public Token(String value) {
      this.value = value;
    }

    @JsonValue
    public String value() {
      return value;
    }
  }

  public static class Tokened {
    public String name;
    @JsonUnwrapped
    public Token token;
  }

  protected static SimpleModule tokenModule() {
    return new SimpleModule("token").addDeserializer(Token.class, new ValueDeserializer<Token>() {
      @Override
      public Token deserialize(JsonParser p, DeserializationContext ctxt) {
        return new Token(p.getString());
      }
    });
  }

  /** Resolving Race, Jackson takes RaceHolder first, which it keeps at once, and then Slow, which holds thread 1. */
  public static class Race {
    public String name;
    @JsonUnwrapped
    public Spot spot;
    public RaceHolder holder;
    public Slow slow;
  }

  public static class RaceHolder {
    public String id;
    public Race race;
  }

  @JsonDeserialize(using = SlowDeserializer.class)
  public static class Slow {
  }

  /** Holds the thread that creates it while {@link #entered} is set, until {@link #release}. */
  public static class SlowDeserializer extends ValueDeserializer<Slow> {
    static volatile CountDownLatch entered;
    static volatile CountDownLatch release;

    public SlowDeserializer() {
      CountDownLatch latch = entered;
      if (latch != null) {
        latch.countDown();
        try {
          release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }

    @Override
    public Slow deserialize(JsonParser p, DeserializationContext ctxt) {
      p.skipChildren();
      return new Slow();
    }
  }

  /** Wraps every bean deserializer, as a module may do to log or check what is read. */
  public static class Wrapping extends DelegatingDeserializer {
    static final AtomicInteger READS = new AtomicInteger();

    public Wrapping(ValueDeserializer<?> delegatee) {
      super(delegatee);
    }

    @Override
    protected ValueDeserializer<?> newDelegatingInstance(ValueDeserializer<?> delegatee) {
      return new Wrapping(delegatee);
    }

    @Override
    public Object deserialize(JsonParser p, DeserializationContext ctxt) {
      READS.incrementAndGet();
      return super.deserialize(p, ctxt);
    }
  }

  protected static SimpleModule wrappingModule() {
    SimpleModule module = new SimpleModule("wrapping");
    module.setDeserializerModifier(new ValueDeserializerModifier() {
      @Override
      public ValueDeserializer<?> modifyDeserializer(DeserializationConfig config, BeanDescription.Supplier beanDescRef,
          ValueDeserializer<?> deserializer) {
        return deserializer instanceof BeanDeserializerBase ? new Wrapping(deserializer) : deserializer;
      }
    });
    return module;
  }
}
