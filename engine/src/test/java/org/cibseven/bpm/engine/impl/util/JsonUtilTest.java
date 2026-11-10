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
package org.cibseven.bpm.engine.impl.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.cibseven.commons.testing.ProcessEngineLoggingRule;
import org.cibseven.commons.testing.WatchLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

/**
 * Gson 2.12+ reads JSON nested at most 255 levels deep by default; the engine reads up to
 * {@link JsonUtil#MAX_JSON_NESTING_DEPTH} levels, like the REST API (CIB7-2634).
 */
public class JsonUtilTest {

  @RegisterExtension
  public ProcessEngineLoggingRule loggingRule = new ProcessEngineLoggingRule();

  @Test
  public void shouldReadObjectNestedUpToMaxDepth() {
    assertThat(depth(JsonUtil.asObject(nestedObject(700)))).isEqualTo(700);
    assertThat(depth(JsonUtil.asObject(nestedObject(JsonUtil.MAX_JSON_NESTING_DEPTH)))).isEqualTo(1000);
  }

  @Test
  public void shouldReadMapNestedUpToMaxDepth() {
    Map<String, Object> map = JsonUtil.asMap(JsonUtil.asObject(nestedObject(700)));

    int depth = 0;
    Object value = map;
    while (value instanceof Map) {
      value = ((Map<?, ?>) value).get("a");
      depth++;
    }
    assertThat(depth).isEqualTo(700);
    assertThat(value).isEqualTo(1);
  }

  @Test
  @WatchLogger(loggerNames = {"org.cibseven.bpm.engine.util"}, level = "WARN")
  public void shouldReadEmptyObjectAndWarnWhenNestedDeeperThanMaxDepth() {
    // when
    JsonObject jsonObject = JsonUtil.asObject(nestedObject(1100));

    // then
    assertThat(jsonObject.size()).isZero();
    assertThat(loggingRule.getFilteredLog("ENGINE-09049 Could not read JSON nested deeper than 1000 levels"))
        .hasSize(1);
  }

  @Test
  @WatchLogger(loggerNames = {"org.cibseven.bpm.engine.util"}, level = "WARN")
  public void shouldNotWarnForOtherMalformedJson() {
    // when
    JsonObject jsonObject = JsonUtil.asObject("{\"a\":");

    // then
    assertThat(jsonObject.size()).isZero();
    assertThat(loggingRule.getFilteredLog("ENGINE-09049")).isEmpty();
  }

  @Test
  public void shouldReadArrayNestedUpToMaxDepth() {
    JsonArray array = JsonUtil.fromJson(nestedArray(700), JsonArray.class);

    assertThat(depth(array)).isEqualTo(700);
  }

  @Test
  public void shouldRejectArrayNestedDeeperThanMaxDepth() {
    assertThatThrownBy(() -> JsonUtil.fromJson(nestedArray(1100), JsonArray.class))
        .isInstanceOf(JsonSyntaxException.class)
        .hasMessageContaining("Nesting limit 1000 reached");
  }

  @Test
  public void shouldReadJsonNestedExactlyMaxDepth() {
    JsonArray array = JsonUtil.fromJson(nestedArray(JsonUtil.MAX_JSON_NESTING_DEPTH), JsonArray.class);

    assertThat(depth(array)).isEqualTo(JsonUtil.MAX_JSON_NESTING_DEPTH);
  }

  @Test
  public void shouldRejectJsonNestedOneLevelDeeperThanMaxDepth() {
    assertThatThrownBy(() -> JsonUtil.fromJson(nestedArray(JsonUtil.MAX_JSON_NESTING_DEPTH + 1), JsonArray.class))
        .isInstanceOf(JsonSyntaxException.class)
        .hasMessageContaining("Nesting limit 1000 reached");
  }

  @Test
  public void shouldReadLikeGson() {
    // lenient
    assertThat(JsonUtil.fromJson("{a:1, 'b':'x'}", JsonObject.class).toString()).isEqualTo("{\"a\":1,\"b\":\"x\"}");
    // empty input
    assertThat(JsonUtil.fromJson((String) null, JsonObject.class)).isNull();
    assertThat(JsonUtil.fromJson("", JsonObject.class)).isNull();
    assertThat(JsonUtil.fromJson("  ", JsonObject.class)).isNull();
    // the whole input must be JSON
    assertThatThrownBy(() -> JsonUtil.fromJson("{\"a\":1} x", JsonObject.class))
        .isInstanceOf(JsonSyntaxException.class);
    assertThatThrownBy(() -> JsonUtil.fromJson("{\"a\":1} {\"a\":2}", JsonObject.class))
        .isInstanceOf(JsonSyntaxException.class);
    // the requested type
    assertThatThrownBy(() -> JsonUtil.fromJson("[1]", JsonObject.class))
        .isInstanceOf(JsonSyntaxException.class);
  }

  protected static String nestedObject(int depth) {
    StringBuilder json = new StringBuilder();
    for (int i = 0; i < depth; i++) {
      json.append("{\"a\":");
    }
    json.append('1');
    for (int i = 0; i < depth; i++) {
      json.append('}');
    }
    return json.toString();
  }

  protected static String nestedArray(int depth) {
    StringBuilder json = new StringBuilder();
    for (int i = 0; i < depth; i++) {
      json.append('[');
    }
    json.append("\"x\"");
    for (int i = 0; i < depth; i++) {
      json.append(']');
    }
    return json.toString();
  }

  protected static int depth(JsonElement element) {
    int depth = 0;
    while (element != null && (element.isJsonObject() || element.isJsonArray())) {
      element = element.isJsonObject() ? element.getAsJsonObject().get("a") : element.getAsJsonArray().get(0);
      depth++;
    }
    return depth;
  }
}
