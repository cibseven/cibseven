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
package org.cibseven.spin.impl.json.jackson.format;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Behaviour of the deprecated Jackson 2 module that differs from cibseven-spin-dataformat-json-jackson
 * (see JacksonJsonDataFormatTest there).
 */
public class JacksonJsonDataFormatJackson2Test {

  /** A property whose class comes from the JSON. */
  public static class ClassIdValue {
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
    public Object value;
  }

  @Test
  public void shouldReadClassIdsForAnyPropertyType() {
    // Jackson 2 reads the class of a property of type Object from the JSON, like Spin 2.2 (Jackson 3 does not)
    JacksonJsonDataFormat format = new JacksonJsonDataFormat("test");
    Object node = format.getReader().readInput(new StringReader("{\"value\":[\"java.util.Locale\",\"en_US\"]}"));

    ClassIdValue value = format.getMapper().mapInternalToJava(node, ClassIdValue.class);

    assertThat(value.value).isEqualTo(Locale.US);
  }
}
