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

import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.cibseven.spin.impl.json.jackson.format.JacksonJsonDataFormat;
import org.cibseven.spin.spi.DataFormatConfigurator;

/**
 * Registers the Jackson 2 {@link ParameterNamesModule} with the deprecated Jackson 2 variant of Spin
 * ({@code cibseven-spin-dataformat-json-jackson2}). Jackson 3, used by
 * {@code cibseven-spin-dataformat-json-jackson}, supports this out of the box.
 *
 * @deprecated together with the Jackson 2 variant of Spin, removed in CIB seven 3.1.0
 */
@Deprecated(since = "3.0.0", forRemoval = true)
public class CamundaJacksonFormatConfiguratorParameterNames implements DataFormatConfigurator<JacksonJsonDataFormat> {

  @Override
  public Class<JacksonJsonDataFormat> getDataFormatClass() {
    return JacksonJsonDataFormat.class;
  }

  @Override
  public void configure(JacksonJsonDataFormat dataFormat) {
    final ParameterNamesModule parameterNamesModule = new ParameterNamesModule();

    Jackson2SpinObjectMapper.of(dataFormat).registerModule(parameterNamesModule);
  }
}
