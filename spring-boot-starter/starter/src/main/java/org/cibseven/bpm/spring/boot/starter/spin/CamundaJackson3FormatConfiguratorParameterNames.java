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
package org.cibseven.bpm.spring.boot.starter.spin;

import org.cibseven.spin.impl.json.jackson.format.JacksonJsonDataFormat;
import org.cibseven.spin.spi.DataFormatConfigurator;

import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.ConstructorDetector;

/**
 * Lets Spin JSON on Jackson 3 ({@code cibseven-spin-dataformat-json-jackson}) use the parameter names of
 * constructors, as {@link CamundaJacksonFormatConfiguratorParameterNames} did with Jackson 2's
 * {@code ParameterNamesModule}. Jackson 3 has parameter name support built in, but Spin's default mapper
 * is built with {@code builderWithJackson2Defaults()}, which turns {@link MapperFeature#DETECT_PARAMETER_NAMES}
 * off. Without it, a class whose only constructor takes all properties and is not annotated could no
 * longer be read from a Spin JSON variable in a Spring Boot application (compiled with {@code -parameters}).
 * <p>
 * As with Jackson 2, a class that also has a constructor without parameters (the shape of Lombok's
 * {@code @Data @NoArgsConstructor @AllArgsConstructor}) is still read through that constructor and its setters,
 * and written in the order of its fields: Jackson 3 would otherwise use the unannotated constructor that takes
 * the properties (databind#5318), so properties missing in stored JSON would become {@code null} instead of
 * keeping their default values. Jackson 2's rule is kept: that constructor is used next to one without
 * parameters only when the constructor detector prefers properties-based creators
 * ({@link ConstructorDetector#USE_PROPERTIES_BASED}).
 * <p>
 * The constructor detector configured so far is kept: Spin applies an application's configurators registered
 * in {@code META-INF/services} before this one. An application that wants Jackson 3's rule registers its
 * configurator in {@code spring.factories} (applied after this one) or defines a bean named
 * {@code spinDataFormatConfiguratorJackson3ParameterNames}.
 */
public class CamundaJackson3FormatConfiguratorParameterNames implements DataFormatConfigurator<JacksonJsonDataFormat> {

  @Override
  public Class<JacksonJsonDataFormat> getDataFormatClass() {
    return JacksonJsonDataFormat.class;
  }

  @Override
  public void configure(JacksonJsonDataFormat dataFormat) {
    ObjectMapper objectMapper = dataFormat.getObjectMapper();
    ConstructorDetector constructorDetector = objectMapper.deserializationConfig().getConstructorDetector();
    dataFormat.setObjectMapper(objectMapper.rebuild()
        .enable(MapperFeature.DETECT_PARAMETER_NAMES)
        .constructorDetector(constructorDetector.withAllowImplicitWithDefaultConstructor(
            constructorDetector.singleArgCreatorDefaultsToProperties()))
        .build());
  }
}
