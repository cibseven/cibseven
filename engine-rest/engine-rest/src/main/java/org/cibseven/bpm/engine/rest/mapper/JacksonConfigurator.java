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
import java.util.Properties;

import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.ContextResolver;
import javax.ws.rs.ext.Provider;

import org.cibseven.bpm.engine.impl.ProcessEngineLogger;
import org.cibseven.bpm.engine.impl.util.EngineUtilLogger;
import org.cibseven.bpm.engine.rest.hal.Hal;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Provider
@Produces({MediaType.APPLICATION_JSON, Hal.APPLICATION_HAL_JSON})
public class JacksonConfigurator implements ContextResolver<ObjectMapper> {

  protected static final EngineUtilLogger LOG = ProcessEngineLogger.UTIL_LOGGER;

  public static final String DEFAULT_DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSZ";
  public static String dateFormatString = DEFAULT_DATE_FORMAT;

  private static final String PROPERTIES_FILE = "jackson.properties";
  private static final String LENGTH_PROPERTY = "jackson.maxStringLength";
  private static int maxStringLength = StreamReadConstraints.DEFAULT_MAX_STRING_LEN;

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

  public static ObjectMapper configureObjectMapper(ObjectMapper mapper) {
    SimpleDateFormat dateFormat = new SimpleDateFormat(dateFormatString);
    mapper.setDateFormat(dateFormat);
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    
    mapper.registerModule(new JavaTimeModule());

    // setStreamReadConstraints was added in Jackson 2.15.0
    // Check Jackson version before using the feature
    configureStreamReadConstraints(mapper);

    return mapper;
  }

  /**
   * Configures StreamReadConstraints on the ObjectMapper if the Jackson version supports it.
   * StreamReadConstraints was added in Jackson 2.15.0.
   */
  private static void configureStreamReadConstraints(ObjectMapper mapper) {
    try {
      // Try to use StreamReadConstraints - available in Jackson 2.15.0+
      StreamReadConstraints streamReadConstraints = StreamReadConstraints.builder()
              .maxStringLength(maxStringLength)
              .build();
      mapper.getFactory().setStreamReadConstraints(streamReadConstraints);
    } catch (NoSuchMethodError e) {
      // setStreamReadConstraints method not available in Jackson < 2.15.0
      // This can happen when WildFly's older Jackson modules are used
      // Application will continue without this security constraint
    }
  }

  @Override
  public ObjectMapper getContext(Class<?> clazz) {
    return configureObjectMapper(new ObjectMapper());
  }

  public static void setDateFormatString(String dateFormatString) {
    JacksonConfigurator.dateFormatString = dateFormatString;
  }

}
