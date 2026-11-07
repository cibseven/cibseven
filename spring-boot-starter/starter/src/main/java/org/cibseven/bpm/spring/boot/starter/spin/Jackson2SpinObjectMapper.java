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

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Access to the Jackson 2 {@link ObjectMapper} of the deprecated Jackson 2 variant of Spin
 * ({@code cibseven-spin-dataformat-json-jackson2}).
 *
 * <p>The starter is compiled against the Jackson 3 variant, whose {@code JacksonJsonDataFormat#getObjectMapper()}
 * returns a {@code tools.jackson.databind.ObjectMapper}. The Jackson 2 variant has the same class with a
 * {@code getObjectMapper()} that returns a {@code com.fasterxml.jackson.databind.ObjectMapper}, so the
 * method is looked up at runtime instead of being linked at compile time.</p>
 *
 * @deprecated together with the Jackson 2 variant of Spin, removed in CIB seven 3.1.0
 */
@Deprecated(since = "3.0.0", forRemoval = true)
final class Jackson2SpinObjectMapper {

  private Jackson2SpinObjectMapper() {
  }

  static ObjectMapper of(JacksonJsonDataFormat dataFormat) {
    Object objectMapper;
    try {
      objectMapper = dataFormat.getClass().getMethod("getObjectMapper").invoke(dataFormat);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Cannot access the object mapper of " + dataFormat.getClass().getName(), e);
    }
    if (objectMapper instanceof ObjectMapper jackson2ObjectMapper) {
      return jackson2ObjectMapper;
    }
    throw new IllegalStateException("The Spin JSON data format uses " + objectMapper.getClass().getName()
        + ", not a Jackson 2 ObjectMapper: the Jackson 2 configurators of the starter need "
        + "cibseven-spin-dataformat-json-jackson2");
  }
}
