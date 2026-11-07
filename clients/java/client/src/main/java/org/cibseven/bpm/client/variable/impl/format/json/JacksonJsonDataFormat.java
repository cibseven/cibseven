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

import java.util.ArrayList;
import java.util.List;

import org.cibseven.bpm.client.impl.ExternalTaskClientLogger;
import org.cibseven.bpm.client.spi.DataFormat;
import org.cibseven.bpm.client.variable.impl.format.TypeDetector;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamWriteConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_NESTING_DEPTH).build())
        .streamWriteConstraints(StreamWriteConstraints.builder().maxNestingDepth(MAX_NESTING_DEPTH).build())
        .build();
    return JsonMapper.builder(jsonFactory)
        .configureForJackson2()
        .build();
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
