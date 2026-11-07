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
package org.cibseven.bpm.spring.boot.starter.spin;

import org.cibseven.spin.impl.json.jackson.format.JacksonJsonDataFormat;
import org.cibseven.spin.spi.DataFormatConfigurator;

import tools.jackson.databind.MapperFeature;

/**
 * Lets Spin JSON on Jackson 3 ({@code cibseven-spin-dataformat-json-jackson}) use the parameter names of
 * constructors, as {@link CamundaJacksonFormatConfiguratorParameterNames} did with Jackson 2's
 * {@code ParameterNamesModule}. Jackson 3 has parameter name support built in, but Spin's default mapper
 * is built with {@code builderWithJackson2Defaults()}, which turns {@link MapperFeature#DETECT_PARAMETER_NAMES}
 * off. Without it, a class whose only constructor takes all properties and is not annotated could no
 * longer be read from a Spin JSON variable in a Spring Boot application (compiled with {@code -parameters}).
 */
public class CamundaJackson3FormatConfiguratorParameterNames implements DataFormatConfigurator<JacksonJsonDataFormat> {

  @Override
  public Class<JacksonJsonDataFormat> getDataFormatClass() {
    return JacksonJsonDataFormat.class;
  }

  @Override
  public void configure(JacksonJsonDataFormat dataFormat) {
    dataFormat.setObjectMapper(dataFormat.getObjectMapper().rebuild()
        .enable(MapperFeature.DETECT_PARAMETER_NAMES)
        .build());
  }
}
