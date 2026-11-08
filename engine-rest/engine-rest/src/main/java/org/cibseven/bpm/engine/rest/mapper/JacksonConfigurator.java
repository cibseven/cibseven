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
import java.text.SimpleDateFormat;
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

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamWriteConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.introspect.DefaultAccessorNamingStrategy;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

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
        // Jackson 3 ignores accessors like get_links() or getvalue(), Jackson 2 did not
        .accessorNaming(new DefaultAccessorNamingStrategy.Provider().withFirstCharAcceptance(true, true))
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        // the date format uses the JVM default time zone (no explicit mapper time zone)
        .defaultDateFormat(new SimpleDateFormat(dateFormat))
        // Jackson 3 writes a Locale as language tag ("en-US"), Jackson 2 with toString() ("en_US"),
        // e.g. in deserialized object variable values. ToStringSerializer, as in Jackson 2, also keeps
        // type ids and "" as empty value (Locale.ROOT, NON_EMPTY)
        .addModule(new SimpleModule("cibseven-rest-jackson2-compatibility")
            .addSerializer(Locale.class, new ToStringSerializer(Locale.class)));
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
