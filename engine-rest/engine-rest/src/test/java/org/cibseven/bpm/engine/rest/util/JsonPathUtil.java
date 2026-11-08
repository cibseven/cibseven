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
package org.cibseven.bpm.engine.rest.util;

import java.text.SimpleDateFormat;

import org.cibseven.bpm.engine.rest.mapper.JacksonConfigurator;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.restassured.path.json.mapper.factory.DefaultJackson2ObjectMapperFactory;
import io.restassured.path.json.JsonPath;

/**
 * REST-assured parses JSON with Jackson 2 (test scope only); it is configured like the
 * Jackson 3 mapper of the REST API ({@link JacksonConfigurator}).
 */
public final class JsonPathUtil {

  public static JsonPath from(String json) {
    return JsonPath.from(json).using(new DefaultJackson2ObjectMapperFactory() {
      public ObjectMapper create(Class cls, String charset) {
        ObjectMapper mapper = super.create(cls, charset);
        mapper.setDateFormat(new SimpleDateFormat(JacksonConfigurator.dateFormatString));
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        return mapper;
      }
    });
  }

}
