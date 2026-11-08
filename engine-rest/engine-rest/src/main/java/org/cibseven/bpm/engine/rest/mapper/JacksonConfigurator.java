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
package org.cibseven.bpm.engine.rest.mapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Month;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Properties;
import java.util.TimeZone;

import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.Provider;

import org.cibseven.bpm.engine.impl.ProcessEngineLogger;
import org.cibseven.bpm.engine.impl.util.EngineUtilLogger;
import org.cibseven.bpm.engine.rest.hal.Hal;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamWriteConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.AnnotationIntrospector;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.KeyDeserializer;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonPOJOBuilder;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.cfg.MapperConfig;
import tools.jackson.databind.deser.jdk.JDKFromStringDeserializer;
import tools.jackson.databind.ext.javatime.ser.YearSerializer;
import tools.jackson.databind.ext.sql.JavaSqlDateSerializer;
import tools.jackson.databind.introspect.AccessorNamingStrategy;
import tools.jackson.databind.introspect.AnnotatedClass;
import tools.jackson.databind.introspect.DefaultAccessorNamingStrategy;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.Serializers;
import tools.jackson.databind.ser.jdk.EnumSerializer;
import tools.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * Provides the Jackson {@link ObjectMapper} of the REST API.
 *
 * <p>The mapper keeps the JSON format of the Jackson 2 based REST API: it starts from the
 * Jackson 2 defaults of Jackson 3 ({@link JsonMapper#builderWithJackson2Defaults()}), writes
 * dates as text in {@link #dateFormatString} (in the JVM default time zone) and ignores
 * unknown properties. Beyond those defaults it keeps the Jackson 2 accessor names (e.g.
 * <code>get_links()</code>) and the Jackson 2 limits for nesting depth and string length;
 * the maximum string length can be changed in <code>jackson.properties</code>.
 *
 * <p>Jackson 3 mappers are immutable, so the mapper is built once and shared. It is rebuilt
 * when the date format ({@link #setDateFormatString(String)}) or the JVM default time zone
 * changes. To add modules or features, start from
 * {@link #configureObjectMapper()}<code>.rebuild()</code>.
 *
 * <p>The JAX-RS JSON providers of the REST API ({@link CibsevenJacksonJsonProvider}) and the
 * resources look the mapper up as <code>ContextResolver&lt;ObjectMapper&gt;</code>, so an
 * application can replace this class with its own resolver.
 */
@Provider
@Produces({MediaType.APPLICATION_JSON, Hal.APPLICATION_HAL_JSON})
public class JacksonConfigurator implements ContextResolver<ObjectMapper> {

  protected static final EngineUtilLogger LOG = ProcessEngineLogger.UTIL_LOGGER;

  public static final String DEFAULT_DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSZ";
  public static String dateFormatString = DEFAULT_DATE_FORMAT;

  /** Default of <code>jackson.maxStringLength</code>, the limit of Jackson 2 (Jackson 3: 100,000,000). */
  public static final int DEFAULT_MAX_STRING_LENGTH = 20_000_000;
  /** Maximum nesting depth for reading and writing, the limit of Jackson 2 (Jackson 3: 500). */
  public static final int MAX_NESTING_DEPTH = 1000;

  private static final String PROPERTIES_FILE = "jackson.properties";
  private static final String LENGTH_PROPERTY = "jackson.maxStringLength";
  private static int maxStringLength = DEFAULT_MAX_STRING_LENGTH;

  private static volatile ConfiguredMapper configuredMapper;

  static {
    Properties properties = new Properties();
    try (InputStream inputStream = JacksonConfigurator.class.getClassLoader().getResourceAsStream(PROPERTIES_FILE)) {
      if (inputStream == null) {
        LOG.logMissingPropertiesFile(PROPERTIES_FILE);
      } else {
        properties.load(inputStream);
      }

    } catch (IOException e) {
      LOG.exceptionWhileReadingFile(PROPERTIES_FILE, e);
    }

    maxStringLength = Integer.parseInt(properties.getProperty(LENGTH_PROPERTY, maxStringLength + ""));

  }

  /**
   * @return the mapper of the REST API for the current date format and JVM default time zone
   */
  public static JsonMapper configureObjectMapper() {
    String format = dateFormatString;
    TimeZone timeZone = TimeZone.getDefault();
    ConfiguredMapper current = configuredMapper;
    if (current == null || !current.isFor(format, timeZone)) {
      current = new ConfiguredMapper(format, timeZone, createObjectMapperBuilder(format).build());
      configuredMapper = current;
    }
    return current.mapper;
  }

  /**
   * @return a builder with the settings of the REST API mapper for the given date format
   */
  protected static JsonMapper.Builder createObjectMapperBuilder(String dateFormat) {
    StreamReadConstraints streamReadConstraints = StreamReadConstraints.builder()
        .maxStringLength(maxStringLength)
        .maxNestingDepth(MAX_NESTING_DEPTH)
        .build();
    StreamWriteConstraints streamWriteConstraints = StreamWriteConstraints.builder()
        .maxNestingDepth(MAX_NESTING_DEPTH)
        .build();
    JsonFactory jsonFactory = JsonFactory.builderWithJackson2Defaults()
        .streamReadConstraints(streamReadConstraints)
        .streamWriteConstraints(streamWriteConstraints)
        .build();

    // as JsonMapper.builderWithJackson2Defaults(), but with our stream constraints
    return JsonMapper.builder(jsonFactory)
        .configureForJackson2()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        // the date format uses the JVM default time zone (no explicit mapper time zone)
        .defaultDateFormat(new SimpleDateFormat(dateFormat))
        // getURL() is "url" and get_links() or getvalue() a property, like Jackson 2 (see Jackson2AccessorNaming)
        .accessorNaming(new Jackson2AccessorNaming())
        // Jackson 3 writes a getter returning Void as property with value null, Jackson 2 did not
        .disable(MapperFeature.ALLOW_VOID_VALUED_PROPERTIES)
        // Jackson 3 writes a Locale as language tag ("en-US"), Jackson 2 with toString() ("en_US"),
        // e.g. in deserialized object variable values. ToStringSerializer, as in Jackson 2, also keeps
        // type ids and "" as empty value (Locale.ROOT, NON_EMPTY); Jackson2LocaleDeserializer reads
        // "zh_TW_#Hant" back with its script, like Jackson 2
        // Year, Month and java.sql.Date are written like Jackson 2 too (see Jackson2CompatibilityModule)
        .addModule(new Jackson2CompatibilityModule("cibseven-rest-jackson2-compatibility"));
  }

  /**
   * Property names of getters and setters like Jackson 2, which Jackson 3 no longer offers: leading upper-case letters
   * are lower-cased ({@code getURL()} is {@code "url"}, not {@code "URL"}), and accessors whose name continues with a
   * lower-case letter, a digit or {@code "_"} ({@code getvalue()}, {@code get1st()}, {@code get_links()}) are
   * properties as well. Record components keep their names; additional getters of a record are named like Jackson 2
   * too (Jackson 3 names {@code getURL()} "URL", the name of a component {@code URL}, and drops that component's value).
   */
  public static class Jackson2AccessorNaming extends DefaultAccessorNamingStrategy.Provider {

    private static final long serialVersionUID = 1L;

    public Jackson2AccessorNaming() {
      super("set", JsonPOJOBuilder.DEFAULT_WITH_PREFIX, "get", "is", null);
    }

    @Override
    public AccessorNamingStrategy forPOJO(MapperConfig<?> config, AnnotatedClass targetClass) {
      return new LegacyNaming(config, targetClass, _setterPrefix, _getterPrefix, _isGetterPrefix);
    }

    @Override
    public AccessorNamingStrategy forBuilder(MapperConfig<?> config, AnnotatedClass builderClass,
        BeanDescription valueTypeDesc) {
      AnnotationIntrospector introspector = config.getAnnotationIntrospector();
      String mutatorPrefix = null;
      if (valueTypeDesc != null) {
        mutatorPrefix = introspector.findBuilderPrefix(config, valueTypeDesc.getClassInfo());
      }
      if (mutatorPrefix == null) {
        JsonPOJOBuilder.Value builderConfig = introspector.findPOJOBuilderConfig(config, builderClass);
        mutatorPrefix = (builderConfig != null) ? builderConfig.withPrefix : _withPrefix;
      }
      return new LegacyNaming(config, builderClass, mutatorPrefix, _getterPrefix, _isGetterPrefix);
    }

    @Override
    public AccessorNamingStrategy forRecord(MapperConfig<?> config, AnnotatedClass recordClass) {
      if (config.isEnabled(MapperFeature.INFER_RECORD_GETTERS_FROM_COMPONENTS_ONLY)) {
        return new DefaultAccessorNamingStrategy.RecordNamingStrict(config, recordClass); // components only: no names to build
      }
      return new LegacyRecordNaming(config, recordClass);
    }

    protected static class LegacyNaming extends DefaultAccessorNamingStrategy {

      protected LegacyNaming(MapperConfig<?> config, AnnotatedClass forClass, String mutatorPrefix,
          String getterPrefix, String isGetterPrefix) {
        super(config, forClass, mutatorPrefix, getterPrefix, isGetterPrefix, null);
      }

      @Override
      protected String stdManglePropertyName(String basename, int offset) {
        return legacyManglePropertyName(basename, offset);
      }
    }

    protected static class LegacyRecordNaming extends DefaultAccessorNamingStrategy.RecordNaming {

      protected LegacyRecordNaming(MapperConfig<?> config, AnnotatedClass forClass) {
        super(config, forClass);
      }

      @Override
      protected String stdManglePropertyName(String basename, int offset) {
        return legacyManglePropertyName(basename, offset);
      }
    }

    /** Jackson 2's {@code legacyManglePropertyName}: lower-cases all leading upper-case letters. */
    protected static String legacyManglePropertyName(String basename, int offset) {
      int end = basename.length();
      if (end == offset) {
        return null;
      }
      char c = basename.charAt(offset);
      char d = Character.toLowerCase(c);
      if (c == d) {
        return basename.substring(offset);
      }
      StringBuilder name = new StringBuilder(end - offset);
      name.append(d);
      for (int i = offset + 1; i < end; ++i) {
        c = basename.charAt(i);
        d = Character.toLowerCase(c);
        if (c == d) {
          name.append(basename, i, end);
          break;
        }
        name.append(d);
      }
      return name.toString();
    }
  }

  /**
   * Reads a {@link Locale} like Jackson 2, also the form {@link Locale#toString()} writes for a script or extensions
   * ({@code "zh_TW_#Hant"}, {@code "th_TH_#u-nu-thai"}): Jackson 3 would drop the script and the extensions.
   * All other forms are read by Jackson 3's own deserializer.
   */
  public static class Jackson2LocaleDeserializer extends JDKFromStringDeserializer {

    private static final long serialVersionUID = 1L;

    public Jackson2LocaleDeserializer() {
      super(Locale.class, STD_LOCALE);
    }

    /** This deserializer typed for {@link SimpleModule#addDeserializer}; Jackson's base class is typed {@code Object}. */
    @SuppressWarnings("unchecked")
    public static ValueDeserializer<Locale> forLocale() {
      return (ValueDeserializer<Locale>) (ValueDeserializer<?>) new Jackson2LocaleDeserializer();
    }

    @Override
    public Object _deserialize(String value, DeserializationContext ctxt)
        throws MalformedURLException, UnknownHostException {
      return value.contains(LOCALE_EXTENSION_MARKER) ? localeWithScriptOrExtensions(value) : super._deserialize(value, ctxt);
    }
  }

  /** Reads {@link Locale} map keys like {@link Jackson2LocaleDeserializer}. */
  public static class Jackson2LocaleKeyDeserializer extends KeyDeserializer {

    protected final Jackson2LocaleDeserializer deserializer = new Jackson2LocaleDeserializer();

    @Override
    public Object deserializeKey(String key, DeserializationContext ctxt) {
      try {
        return deserializer._deserialize(key, ctxt);
      } catch (IOException e) { // not thrown for a Locale
        return ctxt.handleWeirdKey(Locale.class, key, "not a valid Locale: %s", e.getMessage());
      }
    }
  }

  protected static final String LOCALE_EXTENSION_MARKER = "_#";

  /** Jackson 2's parsing of {@code language_country_variant_#script_extensions} (FromStringDeserializer, 2.21). */
  @SuppressWarnings("deprecation") // Locale constructors, as in Jackson 2
  protected static Locale localeWithScriptOrExtensions(String value) {
    int ix = firstHyphenOrUnderscore(value);
    String first = value.substring(0, ix);
    value = value.substring(ix + 1);
    ix = firstHyphenOrUnderscore(value);
    if (ix < 0) {
      return new Locale(first, value);
    }
    String second = value.substring(0, ix);
    int extensionMarker = value.indexOf(LOCALE_EXTENSION_MARKER);
    if (extensionMarker < 0) {
      return new Locale(first, second, value.substring(ix + 1));
    }
    String third = "";
    try {
      if (extensionMarker > 0 && extensionMarker > ix) {
        third = value.substring(ix + 1, extensionMarker);
      }
      value = value.substring(extensionMarker + 2);
      int underscore = value.indexOf('_');
      if (underscore < 0) {
        int hyphen = value.indexOf('-');
        if (hyphen < 0) {
          return new Locale.Builder().setLanguage(first).setRegion(second).setVariant(third).setScript(value).build();
        }
        return new Locale.Builder().setLanguage(first).setRegion(second).setVariant(third)
            .setExtension(value.charAt(0), value.substring(hyphen + 1)).build();
      }
      int length = value.length();
      Locale.Builder builder = new Locale.Builder().setLanguage(first).setRegion(second).setVariant(third)
          .setScript(value.substring(0, underscore));
      if ((underscore + 1) < length) {
        builder = builder.setExtension(value.charAt(underscore + 1), value.substring(Math.min(length, underscore + 3)));
      }
      return builder.build();
    } catch (IllformedLocaleException e) {
      return new Locale(first, second, third);
    }
  }

  protected static int firstHyphenOrUnderscore(String value) {
    for (int i = 0; i < value.length(); ++i) {
      char c = value.charAt(i);
      if (c == '_' || c == '-') {
        return i;
      }
    }
    return -1;
  }

  /**
   * The Jackson 2 compatible serializers and deserializers of this mapper. Registered after Jackson 3's own java.time
   * support, so they take precedence.
   */
  public static class Jackson2CompatibilityModule extends SimpleModule {

    private static final long serialVersionUID = 1L;

    public Jackson2CompatibilityModule(String name) {
      super(name);
      addSerializer(Locale.class, new ToStringSerializer(Locale.class));
      addDeserializer(Locale.class, Jackson2LocaleDeserializer.forLocale());
      addKeyDeserializer(Locale.class, new Jackson2LocaleKeyDeserializer());
      addSerializer(Year.class, new Jackson2YearSerializer());
      addSerializer(java.sql.Date.class, new Jackson2SqlDateSerializer());
    }

    @Override
    public void setupModule(SetupContext context) {
      super.setupModule(context);
      context.addSerializers(new Jackson2EnumLikeSerializers());
    }
  }

  /**
   * {@link Month} as enum ({@code "JANUARY"}) like Jackson 2, with all enum settings and {@code @JsonFormat}; Jackson 3
   * writes the month number ({@code 0}).
   */
  public static class Jackson2EnumLikeSerializers extends Serializers.Base {

    @Override
    public ValueSerializer<?> findEnumSerializer(SerializationConfig config, JavaType type,
        BeanDescription.Supplier beanDescRef, JsonFormat.Value formatOverrides) {
      if (!type.hasRawClass(Month.class)) {
        return null;
      }
      JsonFormat.Value format = calculateEffectiveFormat(beanDescRef, Month.class, formatOverrides);
      JsonFormat.Shape shape = format.getShape();
      if (shape == JsonFormat.Shape.POJO || shape == JsonFormat.Shape.OBJECT) {
        return null; // as Jackson's own enum handling: written as bean
      }
      return EnumSerializer.construct(Month.class, config, beanDescRef.get(), format);
    }
  }

  /**
   * {@link Year} like Jackson 2: a number only if dates are written as timestamps, otherwise text ({@code "2020"});
   * Jackson 3 always writes a number unless the property says otherwise.
   */
  public static class Jackson2YearSerializer extends YearSerializer {

    private static final long serialVersionUID = 1L;

    public Jackson2YearSerializer() {
      super(null);
    }

    protected Jackson2YearSerializer(Jackson2YearSerializer base, DateTimeFormatter formatter, Boolean useTimestamp) {
      super(base, formatter, useTimestamp);
    }

    @Override
    protected YearSerializer withFormat(DateTimeFormatter formatter, Boolean useTimestamp, JsonFormat.Shape shape) {
      return new Jackson2YearSerializer(this, formatter, useTimestamp);
    }

    @Override
    protected boolean useTimestampFromGlobalDefaults(SerializationContext ctxt) {
      return ctxt != null && ctxt.isEnabled(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
  }

  /**
   * {@link java.sql.Date} like Jackson 2: written as text without a format on the property, it is the value's own
   * {@code toString()} ({@code "1970-01-01"}); Jackson 3 uses the mapper's date format instead. Timestamps and a
   * format on the property ({@code @JsonFormat}) work as before.
   */
  public static class Jackson2SqlDateSerializer extends JavaSqlDateSerializer {

    private static final long serialVersionUID = 1L;

    public Jackson2SqlDateSerializer() {
      super(null, null);
    }

    protected Jackson2SqlDateSerializer(Boolean useTimestamp, DateFormat customFormat) {
      super(useTimestamp, customFormat);
    }

    @Override
    public Jackson2SqlDateSerializer withFormat(Boolean timestamp, DateFormat customFormat) {
      return new Jackson2SqlDateSerializer(timestamp, customFormat);
    }

    @Override
    public void serialize(java.sql.Date value, JsonGenerator generator, SerializationContext ctxt) {
      if (_asTimestamp(ctxt)) {
        generator.writeNumber(_timestamp(value));
      } else if (_customFormat == null) {
        generator.writeString(value.toString());
      } else {
        _serializeAsString(value, generator, ctxt);
      }
    }
  }

  @Override
  public ObjectMapper getContext(Class<?> clazz) {
    return configureObjectMapper();
  }

  public static void setDateFormatString(String dateFormatString) {
    JacksonConfigurator.dateFormatString = dateFormatString;
  }

  /** The mapper together with the settings it was built for. */
  private static final class ConfiguredMapper {

    final String dateFormat;
    final TimeZone timeZone;
    final JsonMapper mapper;

    ConfiguredMapper(String dateFormat, TimeZone timeZone, JsonMapper mapper) {
      this.dateFormat = dateFormat;
      this.timeZone = timeZone;
      this.mapper = mapper;
    }

    boolean isFor(String dateFormat, TimeZone timeZone) {
      return this.dateFormat.equals(dateFormat) && this.timeZone.equals(timeZone);
    }
  }

}
