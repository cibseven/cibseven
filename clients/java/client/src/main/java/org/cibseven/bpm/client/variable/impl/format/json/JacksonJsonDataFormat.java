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
package org.cibseven.bpm.client.variable.impl.format.json;

import static org.cibseven.commons.utils.EnsureUtil.ensureNotNull;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;

import org.cibseven.bpm.client.impl.ExternalTaskClientLogger;
import org.cibseven.bpm.client.spi.DataFormat;
import org.cibseven.bpm.client.variable.impl.format.TypeDetector;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamWriteConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.AnnotationIntrospector;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.KeyDeserializer;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonPOJOBuilder;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.cfg.MapperConfig;
import tools.jackson.databind.deser.jdk.JDKFromStringDeserializer;
import tools.jackson.databind.introspect.AccessorNamingStrategy;
import tools.jackson.databind.introspect.AnnotatedClass;
import tools.jackson.databind.introspect.DefaultAccessorNamingStrategy;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;
import tools.jackson.databind.util.StdDateFormat;

/**
 * The {@code application/json} data format of object variables, based on Jackson 3.
 * <p>
 * The default {@link ObjectMapper} keeps the JSON of the Jackson 2 based client: same property order, dates as
 * timestamps, enums by name, unknown properties rejected, nesting depth up to 1000 (see
 * {@link #createDefaultObjectMapper()}).
 * <p>
 * Jackson 3 mappers are immutable. To change the configuration, register a
 * {@link org.cibseven.bpm.client.spi.DataFormatConfigurator} and replace the mapper:
 * <pre>
 * dataFormat.setObjectMapper(dataFormat.getObjectMapper().rebuild()
 *     .enable(SerializationFeature.INDENT_OUTPUT)
 *     .build());
 * </pre>
 */
public class JacksonJsonDataFormat implements DataFormat {

  private static final JacksonJsonLogger LOG = ExternalTaskClientLogger.JSON_FORMAT_LOGGER;

  /**
   * Maximum nesting depth of the Jackson 2 based client (Jackson 3 defaults to 500).
   */
  public static final int MAX_NESTING_DEPTH = 1000;

  /** Maximum length of a JSON string value, the default of Jackson 2 (Jackson 3: 100,000,000). */
  public static final int MAX_STRING_LENGTH = 20_000_000;

  protected String name;
  protected ObjectMapper objectMapper;
  protected List<TypeDetector> typeDetectors;

  public JacksonJsonDataFormat(String name) {
    this(name, createDefaultObjectMapper());
  }

  public JacksonJsonDataFormat(String name, ObjectMapper objectMapper) {
    this.name = name;
    this.objectMapper = objectMapper;
    this.typeDetectors = new ArrayList<>();
    this.typeDetectors.add(new ListJacksonJsonTypeDetector());
    this.typeDetectors.add(new DefaultJsonJacksonTypeDetector());
  }

  /**
   * Creates the default mapper: the Jackson 2 defaults of Jackson 3
   * ({@link JsonMapper#builderWithJackson2Defaults()}) and the nesting depth limit of Jackson 2.
   */
  public static JsonMapper createDefaultObjectMapper() {
    JsonFactory jsonFactory = JsonFactory.builderWithJackson2Defaults()
        .streamReadConstraints(StreamReadConstraints.defaults().rebuild()
            .maxNestingDepth(MAX_NESTING_DEPTH)
            .maxStringLength(MAX_STRING_LENGTH)
            .build())
        .streamWriteConstraints(StreamWriteConstraints.defaults().rebuild().maxNestingDepth(MAX_NESTING_DEPTH).build())
        .build();
    return JsonMapper.builder(jsonFactory)
        .configureForJackson2()
        // dates with @JsonFormat(shape = STRING) and no pattern: "+00:00" for a zero offset like Jackson 2, not "Z"
        .defaultDateFormat(new StdDateFormat().withColonInTimeZone(true).withZeroOffsetAsZ(false))
        // defaultDateFormat(...) turns WRITE_DATES_AS_TIMESTAMPS off; other dates stay timestamps like Jackson 2
        .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        // getURL() is "url" and getvalue() a property, like Jackson 2 (see Jackson2AccessorNaming)
        .accessorNaming(new Jackson2AccessorNaming())
        // Jackson 3 writes a getter returning Void as property with value null, Jackson 2 did not
        .disable(MapperFeature.ALLOW_VOID_VALUED_PROPERTIES)
        // a Locale with toString() ("en_US") like Jackson 2, not as language tag ("en-US"); both forms are read back.
        // ToStringSerializer, as in Jackson 2, also keeps type ids and "" as empty value (Locale.ROOT, NON_EMPTY);
        // Jackson2LocaleDeserializer reads "zh_TW_#Hant" back with its script, like Jackson 2
        .addModule(new SimpleModule("cibseven-client-jackson2-compatibility")
            .addSerializer(Locale.class, new ToStringSerializer(Locale.class))
            .addDeserializer(Locale.class, Jackson2LocaleDeserializer.forLocale())
            .addKeyDeserializer(Locale.class, new Jackson2LocaleKeyDeserializer()))
        .build();
  }

  /**
   * Property names of getters and setters like Jackson 2, which Jackson 3 no longer offers: leading upper-case letters
   * are lower-cased ({@code getURL()} is {@code "url"}, not {@code "URL"}), and accessors whose name continues with a
   * lower-case letter, a digit or {@code "_"} ({@code getvalue()}, {@code get1st()}, {@code get_links()}) are
   * properties as well. Records keep the naming of Jackson 3, which equals the one of Jackson 2.
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

    protected static class LegacyNaming extends DefaultAccessorNamingStrategy {

      protected LegacyNaming(MapperConfig<?> config, AnnotatedClass forClass, String mutatorPrefix,
          String getterPrefix, String isGetterPrefix) {
        super(config, forClass, mutatorPrefix, getterPrefix, isGetterPrefix, null);
      }

      /** Jackson 2's {@code legacyManglePropertyName}: lower-cases all leading upper-case letters. */
      @Override
      protected String stdManglePropertyName(String basename, int offset) {
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

  public String getName() {
    return name;
  }

  public ObjectMapper getObjectMapper() {
    return objectMapper;
  }

  /**
   * Replaces the mapper, for example with {@code getObjectMapper().rebuild()...build()}.
   */
  public void setObjectMapper(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public boolean canMap(Object parameter) {
    return parameter != null;
  }

  public String writeValue(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    }
    catch (JacksonException e) {
      throw LOG.unableToWriteValue(value, e);
    }
  }

  @SuppressWarnings("unchecked")
  public <T> T readValue(String value, String typeIdentifier) {
    try {
      Class<?> cls = Class.forName(typeIdentifier);
      return (T) readValue(value, cls);
    }
    catch (ClassNotFoundException e) {
      JavaType javaType = constructJavaTypeFromCanonicalString(typeIdentifier);
      return readValue(value, javaType);
    }
  }

  public <T> T readValue(String value, Class<T> cls) {
    try {
      return objectMapper.readValue(value, cls);
    }
    catch (JacksonException e) {
      throw LOG.unableToReadValue(value, e);
    }
  }

  protected <C> C readValue(String value, JavaType type) {
    try {
      return objectMapper.readValue(value, type);
    }
    catch (JacksonException e) {
      throw LOG.unableToReadValue(value, e);
    }
  }

  public JavaType constructJavaTypeFromCanonicalString(String canonicalString) {
    try {
      return objectMapper.getTypeFactory().constructFromCanonical(canonicalString);
    }
    catch (IllegalArgumentException e) {
      throw LOG.unableToConstructJavaType(canonicalString, e);
    }
  }

  public String getCanonicalTypeName(Object value) {
    ensureNotNull("value", value);

    for (TypeDetector typeDetector : typeDetectors) {
      if (typeDetector.canHandle(value)) {
        return typeDetector.detectType(value);
      }
    }

    throw LOG.unableToDetectCanonicalType(value);
  }

}
