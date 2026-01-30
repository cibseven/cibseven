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
package org.cibseven.bpm.engine.test.assertions.bpmn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.cibseven.bpm.engine.test.assertions.bpmn.BpmnAwareTests.withVariables;

import java.util.*;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class ProcessEngineTestsWithVariablesTest {

  static Stream<org.junit.jupiter.params.provider.Arguments> data() {
    return Stream.of(
      org.junit.jupiter.params.provider.Arguments.of(Arrays.asList("key1"), Arrays.asList(1), mapOf("key1", 1)),
      org.junit.jupiter.params.provider.Arguments.of(Arrays.asList("key1", "key2"), Arrays.asList(1, 2), mapOf("key1", 1, "key2", 2)),
      org.junit.jupiter.params.provider.Arguments.of(Arrays.asList("key2"), Arrays.asList(2), mapOf("key2", 2)),
      org.junit.jupiter.params.provider.Arguments.of(Arrays.asList("key1", "key2", "key3"), Arrays.asList(1, 2, 3), mapOf("key1", 1, "key2", 2, "key3", 3))
    );
  }

  private static Map<String, Object> mapOf(Object... kv) {
    Map<String, Object> map = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      map.put((String) kv[i], kv[i + 1]);
    }
    return map;
  }

  @ParameterizedTest
  @MethodSource("data")
  void testWithVariables(List<Object> keys, List<Object> values, Map<String, Object> expectedMap) {
    Map<String, Object> returnedMap = returnedMap(keys, values);
    assertThat(returnedMap).isEqualTo(expectedMap);
  }

  @Test
  void testWithVariables_NoStringKeys() {
    List<Object> keys = new ArrayList<>(Arrays.asList("key1", "key2", "key3"));
    List<Object> values = new ArrayList<>(Arrays.asList(1, 2, 3));
    keys.set(keys.size() - 1, values.get(values.size() - 1));
    try {
      returnedMap(keys, values);
    } catch (Throwable t) {
      assertThat(t).isInstanceOfAny(ClassCastException.class, IllegalArgumentException.class, AssertionError.class);
      return;
    }
    fail("IllegalArgumentException or AssertionError expected!");
  }

  @Test
  void testWithVariables_NullKeys() {
    List<Object> keys = new ArrayList<>(Arrays.asList("key1", "key2", "key3"));
    List<Object> values = new ArrayList<>(Arrays.asList(1, 2, 3));
    keys.set(keys.size() - 1, null);
    try {
      returnedMap(keys, values);
    } catch (Throwable t) {
      assertThat(t).isInstanceOfAny(IllegalArgumentException.class, AssertionError.class);
      return;
    }
    fail("IllegalArgumentException expected!");
  }

  @Test
  void testWithVariables_NullValues() {
    List<Object> keys = new ArrayList<>(Arrays.asList("key1", "key2", "key3"));
    List<Object> values = new ArrayList<>(Arrays.asList(1, 2, 3));
    int idx = values.size();
    while (idx > 0)
      values.set(--idx, null);
    Map<String, Object> returnedMap = returnedMap(keys, values);
    assertThat(returnedMap.keySet()).isEqualTo(new HashSet<>(keys));
    assertThat(returnedMap.values()).containsOnly((Object) null);
  }

  private static Map<String, Object> returnedMap(List<Object> keys, List<Object> values) {
    Map<String, Object> returnedMap;
    if (keys.size() > 2)
      returnedMap = withVariables((String) keys.get(0), values.get(0), keys.get(1), values.get(1), keys.get(2), values.get(2));
    else if (keys.size() > 1)
      returnedMap = withVariables((String) keys.get(0), values.get(0), keys.get(1), values.get(1));
    else
      returnedMap = withVariables((String) keys.get(0), values.get(0));
    return returnedMap;
  }
}