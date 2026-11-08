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
package org.cibseven.spin.impl.json.jackson.format;

import static org.cibseven.commons.utils.EnsureUtil.ensureNotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.jayway.jsonpath.spi.json.Jackson3JsonProvider;
import com.jayway.jsonpath.spi.mapper.Jackson3MappingProvider;
import org.cibseven.spin.DataFormats;
import org.cibseven.spin.impl.json.jackson.JacksonJsonLogger;
import org.cibseven.spin.impl.json.jackson.JacksonJsonNode;
import org.cibseven.spin.json.SpinJsonDataFormatException;
import org.cibseven.spin.json.SpinJsonNode;
import org.cibseven.spin.spi.DataFormat;
import org.cibseven.spin.spi.TypeDetector;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Configuration.ConfigurationBuilder;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamWriteConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.ser.std.ToStringSerializer;
import tools.jackson.databind.type.TypeFactory;
import tools.jackson.databind.util.StdDateFormat;

/**
 * Spin data format that can wrap Json content and uses
 * <a href="http://wiki.fasterxml.com/JacksonHome">Jackson</a> as its implementation.
 * Caches an instance of {@link ObjectMapper} according to the advice given in the
 * <a href="http://wiki.fasterxml.com/JacksonBestPracticesPerformance">Jackson documentation</a>.
 *
 *
 * @author Thorben Lindhauer
 * @author Stefan Hentschel
 */
public class JacksonJsonDataFormat implements DataFormat<SpinJsonNode> {

  public static final String DATA_FORMAT_NAME = DataFormats.JSON_DATAFORMAT_NAME;

  /**
   * Maximum nesting depth of JSON the default mapper reads and writes: the limit of Jackson 2
   * (Jackson 3 defaults to 500), so that JSON accepted by CIB seven 2.x is still accepted.
   */
  public static final int MAX_NESTING_DEPTH = 1000;

  /** Maximum length of a JSON string value, the default of Jackson 2 (Jackson 3: 100,000,000). */
  public static final int MAX_STRING_LENGTH = 20_000_000;

  private static final JacksonJsonLogger LOG = JacksonJsonLogger.JSON_TREE_LOGGER;

  /** The Jackson Object Mapper used by this dataformat */
  protected ObjectMapper objectMapper;

  /** The JsonPath configuration */
  protected Configuration jsonPathConfiguration;

  /** The JsonPath configuration this data format created itself; only this one follows {@link #setObjectMapper(ObjectMapper)}. */
  protected Configuration defaultJsonPathConfiguration;

  protected List<TypeDetector> typeDetectors;

  protected JacksonJsonDataFormatReader dataFormatReader;
  protected JacksonJsonDataFormatWriter dataFormatWriter;
  protected JacksonJsonDataFormatMapper dataFormatMapper;

  protected final String name;

  public JacksonJsonDataFormat(String name) {
    this(name, createDefaultObjectMapper());
  }

  public JacksonJsonDataFormat(String name, ObjectMapper objectMapper) {
    this(name, objectMapper, createDefaultJsonPathConfiguration(objectMapper));
    this.defaultJsonPathConfiguration = this.jsonPathConfiguration;
  }

  public JacksonJsonDataFormat(String name, ObjectMapper objectMapper, Configuration jsonPathConfiguration) {
    this.name = name;
    this.objectMapper = objectMapper;
    this.jsonPathConfiguration = jsonPathConfiguration;
    init();
  }


  // initialization /////////////////////////////////////////////

  protected void init() {
    initReader();
    initWriter();
    initMapper();
    initTypeDetectors();
  }

  protected void initMapper() {
    this.dataFormatMapper = new JacksonJsonDataFormatMapper(this);
  }

  protected void initWriter() {
    this.dataFormatWriter = new JacksonJsonDataFormatWriter(this);
  }

  protected void initReader() {
    this.dataFormatReader = new JacksonJsonDataFormatReader(this);
  }


  protected void initTypeDetectors() {
    typeDetectors = new ArrayList<>();
    typeDetectors.add(new ListJacksonJsonTypeDetector());
    typeDetectors.add(new DefaultJsonJacksonTypeDetector());
  }

  // interface implementation ///////////////////////////////////

  public String getName() {
    return DATA_FORMAT_NAME;
  }

  public Class<? extends SpinJsonNode> getWrapperType() {
    return JacksonJsonNode.class;
  }

  public SpinJsonNode createWrapperInstance(Object parameter) {
    return new JacksonJsonNode((JsonNode) parameter, this);
  }

  /**
   * Identifies the canonical type of an object heuristically.
   *
   * @return the canonical type identifier of the object's class
   * according to Jackson's type format (see {@link TypeFactory#constructFromCanonical(String)})
   */
  public String getCanonicalTypeName(Object object) {
    ensureNotNull("object", object);

    for (TypeDetector typeDetector : typeDetectors) {
      if (typeDetector.canHandle(object)) {
        return typeDetector.detectType(object);
      }
    }

    throw LOG.unableToDetectCanonicalType(object);
  }

  /**
   * Constructs a {@link JavaType} object based on the parameter, which
   * has to follow Jackson's canonical type string format.
   *
   * @param canonicalString canonical string representation of the type
   * @return the constructed java type
   * @throws SpinJsonDataFormatException if no type can be constructed from the given parameter
   */
  public JavaType constructJavaTypeFromCanonicalString(String canonicalString) {
    try {
      return getObjectMapper().getTypeFactory().constructFromCanonical(canonicalString);
    } catch (IllegalArgumentException e) {
      throw LOG.unableToConstructJavaType(canonicalString, e);
    }
  }

  public void addTypeDetector(TypeDetector typeDetector) {
    typeDetectors.add(0, typeDetector);
  }

  public JacksonJsonDataFormatMapper getMapper() {
    return dataFormatMapper;
  }

  public JacksonJsonDataFormatReader getReader() {
    return dataFormatReader;
  }

  public JacksonJsonDataFormatWriter getWriter() {
    return dataFormatWriter;
  }

  // resources //////////////////////////////////////////////////

  /**
   * Returns a {@link Configuration} object for jayway json path
   * which uses this dataformat's object mapper as {@link JsonProvider}.
   *
   * @return the {@link Configuration} for jsonpath
   */
  public Configuration getJsonPathConfiguration() {
    return jsonPathConfiguration;
  }

  public void setJsonPathConfiguration(Configuration jsonPathConfiguration) {
    this.jsonPathConfiguration = jsonPathConfiguration;
  }

  /**
   * Returns the configured Jackson {@link ObjectMapper} instance.
   * @return the configured object mapper.
   */
  public ObjectMapper getObjectMapper() {
    return objectMapper;
  }

  /**
   * Replaces the {@link ObjectMapper} of this data format.
   *
   * <p>Jackson 3 mappers are immutable, so a {@link org.cibseven.spin.spi.DataFormatConfigurator}
   * customizes the mapper with {@code setObjectMapper(getObjectMapper().rebuild()...build())}.
   * If the JsonPath configuration is still the default one built from the previous mapper,
   * it is rebuilt from the new mapper so that JsonPath queries see the same customizations.
   * A configuration set with {@link #setJsonPathConfiguration(Configuration)} is kept.</p>
   *
   * @param objectMapper the new object mapper
   */
  public void setObjectMapper(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    if (jsonPathConfiguration != null && jsonPathConfiguration == defaultJsonPathConfiguration) {
      this.jsonPathConfiguration = createDefaultJsonPathConfiguration(objectMapper);
      this.defaultJsonPathConfiguration = this.jsonPathConfiguration;
    }
  }

  /**
   * Creates the default mapper of this data format: the Jackson 2 defaults of Jackson 3
   * ({@link JsonMapper#builderWithJackson2Defaults()}: dates as timestamps, no alphabetical property order,
   * enums by name, null for primitives accepted, unknown properties rejected, "/" not escaped, ...) and the
   * nesting depth limit of Jackson 2 ({@link #MAX_NESTING_DEPTH}) for reading and writing. JSON written by
   * CIB seven 2.x on Jackson 2 is read and written unchanged.
   *
   * @return a new default object mapper
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
        // defaultDateFormat(...) turns WRITE_DATES_AS_TIMESTAMPS off; Spin writes other dates as timestamps like Jackson 2
        .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        // a Locale with toString() ("en_US") like Jackson 2, not as language tag ("en-US"); both forms are read back.
        // ToStringSerializer, as in Jackson 2, also keeps type ids and "" as empty value (Locale.ROOT, NON_EMPTY)
        .addModule(new SimpleModule("cibseven-spin-jackson2-compatibility")
            .addSerializer(Locale.class, new ToStringSerializer(Locale.class)))
        .build();
  }

  /**
   * Builds the JsonPath {@link Configuration} this data format uses by default:
   * JSON and mapping provider backed by the given object mapper.
   *
   * @param objectMapper the object mapper of the data format
   * @return the default JsonPath configuration
   */
  protected static Configuration createDefaultJsonPathConfiguration(ObjectMapper objectMapper) {
    try {
      return new ConfigurationBuilder()
          .jsonProvider(new Jackson3JsonProvider(objectMapper))
          .mappingProvider(new Jackson3MappingProvider(objectMapper))
          .build();
    } catch (NoClassDefFoundError e) {
      // json-path before 3.0.0 has no Jackson 3 providers, e.g. the version Spring Boot manages
      throw LOG.jsonPathWithoutJackson3Support(e);
    }
  }


  // helper functions //////////////////////////////////////////

  @SuppressWarnings("unchecked")
  public JsonNode createJsonNode(Object parameter) {
    if(parameter instanceof SpinJsonNode) {
      return (JsonNode) ((SpinJsonNode) parameter).unwrap();

    } else if(parameter instanceof String) {
      return createJsonNode((String) parameter);

    } else if(parameter instanceof Integer) {
      return createJsonNode((Integer) parameter);

    } else if(parameter instanceof Boolean) {
      return createJsonNode((Boolean) parameter);

    } else if(parameter instanceof Float) {
      return createJsonNode((Float) parameter);

    } else if(parameter instanceof Long) {
      return createJsonNode((Long) parameter);

    } else if(parameter instanceof Number) {
      return createJsonNode(((Number) parameter).doubleValue());

    } else if(parameter instanceof List) {
      return createJsonNode((List<Object>) parameter);

    } else if(parameter instanceof Map) {
      return createJsonNode((Map<String, Object>) parameter);

    } else if (parameter == null) {
      return createNullJsonNode();
    } else {
      throw LOG.unableToCreateNode(parameter.getClass().getSimpleName());
    }
  }

  public JsonNode createJsonNode(String parameter) {
    return objectMapper.getNodeFactory().textNode(parameter);
  }

  public JsonNode createJsonNode(Integer parameter) {
    return objectMapper.getNodeFactory().numberNode(parameter);
  }

  public JsonNode createJsonNode(Float parameter) {
    return objectMapper.getNodeFactory().numberNode(parameter);
  }

  public JsonNode createJsonNode(Double parameter) {
    return objectMapper.getNodeFactory().numberNode(parameter);
  }

  public JsonNode createJsonNode(Long parameter) {
    return objectMapper.getNodeFactory().numberNode(parameter);
  }

  public JsonNode createJsonNode(Boolean parameter) {
    return objectMapper.getNodeFactory().booleanNode(parameter);
  }

  public JsonNode createJsonNode(List<Object> parameter) {
    if (parameter != null) {
      ArrayNode node = objectMapper.getNodeFactory().arrayNode();
      for(Object entry : parameter) {
        node.add(createJsonNode(entry));
      }
      return node;
    }
    else {
      return createNullJsonNode();
    }

  }

  public JsonNode createJsonNode(Map<String, Object> parameter) {
    if (parameter != null) {
      ObjectNode node = objectMapper.getNodeFactory().objectNode();
      for (Map.Entry<String, Object> entry : parameter.entrySet()) {
        node.set(entry.getKey(), createJsonNode(entry.getValue()));
      }
      return node;
    }
    else {
      return createNullJsonNode();
    }
  }

  public JsonNode createNullJsonNode() {
    return objectMapper.getNodeFactory().nullNode();
  }
}
