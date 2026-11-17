/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.cibseven.connect.ai.agent.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.ObjectValue;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** How {@link AiOutputMarker} hands the marker to the engine (CIB7-2819). */
public class AiOutputMarkerTest {

  private static final String JSON = Variables.SerializationDataFormats.JSON.getName();

  private static Map<String, Object> marker() {
    Map<String, Object> marker = new LinkedHashMap<String, Object>();
    marker.put("aiGenerated", Boolean.TRUE);
    marker.put("runId", "run-1");
    marker.put("model", "a \"quoted\" model\\ with\nlines, Umlaute ä and 😀");
    marker.put("responseId", null);
    marker.put("generatedAt", "2026-11-17T11:00:00Z");
    return marker;
  }

  @Test
  public void anyOtherDefaultFormatLeavesTheMarkerToTheEngine() {
    Map<String, Object> marker = marker();

    assertThat(AiOutputMarker.storable(marker, null)).isSameAs(marker);
    assertThat(AiOutputMarker.storable(marker,
        Variables.SerializationDataFormats.JAVA.getName())).isSameAs(marker);
    assertThat(AiOutputMarker.storable(marker,
        Variables.SerializationDataFormats.XML.getName())).isSameAs(marker);
  }

  @Test
  public void withJsonTheMarkerIsJsonUnderItsOwnType() throws Exception {
    Object storable = AiOutputMarker.storable(marker(), JSON);

    assertThat(storable).isInstanceOf(ObjectValue.class);
    ObjectValue value = (ObjectValue) storable;
    assertThat(value.isDeserialized()).isFalse();
    assertThat(value.getSerializationDataFormat()).isEqualTo(JSON);
    assertThat(value.getObjectTypeName()).isEqualTo(AiOutputMarker.JSON_TYPE_NAME);
    assertThat(value.getValueSerialized()).startsWith("{\"aiGenerated\":true,");
    Map<String, Object> read = new ObjectMapper().readValue(value.getValueSerialized(),
        new TypeReference<LinkedHashMap<String, Object>>() { });
    assertThat(read).as("every value, in its order").containsExactlyEntriesOf(marker());
  }

  @Test
  public void whatIsNoMapStaysAsItIs() {
    assertThat(AiOutputMarker.storable(null, JSON)).isNull();
    assertThat(AiOutputMarker.storable("text", JSON)).isEqualTo("text");
  }
}
