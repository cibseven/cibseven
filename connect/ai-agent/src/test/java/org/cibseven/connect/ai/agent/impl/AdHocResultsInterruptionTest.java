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
package org.cibseven.connect.ai.agent.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Whether an interruption sat on the tool itself, which decides what a waiting result says about a
 * start again (GH-610): the decision of the note itself, so the two never disagree.
 */
class AdHocResultsInterruptionTest {

  private static Map<String, Object> at(String attachedTo) {
    Map<String, Object> one = new HashMap<>();
    one.put("boundaryEvent", "fehler");
    one.put("eventType", "error");
    if (attachedTo != null) {
      one.put("attachedTo", attachedTo);
    }
    return one;
  }

  private static AdHocResults.Interruption describe(String toolId, List<?> interrupted) {
    return AdHocResults.describeInterruptions(toolId, interrupted,
        new LinkedHashMap<String, Object>());
  }

  @Test
  void onTheToolItselfItDidNotComplete() {
    AdHocResults.Interruption interruption = describe("buchen", Arrays.asList(at("buchen")));

    assertThat(interruption.onTheTool).isTrue();
    assertThat(interruption.note).startsWith("'buchen' did not complete");
  }

  @Test
  void furtherAlongItsFlowItRan() {
    AdHocResults.Interruption interruption = describe("anfang", Arrays.asList(at("buchen")));

    assertThat(interruption.onTheTool).isFalse();
    assertThat(interruption.note).startsWith("'anfang' ran;");
  }

  @Test
  void oneOfSeveralOnTheToolIsEnough() {
    AdHocResults.Interruption interruption =
        describe("anfang", Arrays.asList(at("buchen"), at("anfang")));

    assertThat(interruption.onTheTool).isTrue();
    assertThat(interruption.note).startsWith("'anfang' did not complete");
  }

  /** An entry that says nothing of where it sat counts as on the tool, in the note as in the flag. */
  @Test
  void anEntryWithoutAnyKnownFieldAgreesWithItsNote() {
    Map<String, Object> unknown = new HashMap<>();
    unknown.put("somethingElse", "x");

    AdHocResults.Interruption interruption = describe("anfang", Arrays.asList(unknown));

    assertThat(interruption.onTheTool).isTrue();
    assertThat(interruption.note).startsWith("'anfang' did not complete");
  }

  @Test
  void noInterruptionsDescribeNothing() {
    assertThat(describe("anfang", Collections.emptyList())).isNull();
    assertThat(describe("anfang", Arrays.asList(Collections.emptyMap()))).isNull();
  }

  @Test
  void theInterruptionsAreShownToTheModel() {
    Map<String, Object> into = new LinkedHashMap<>();

    AdHocResults.describeInterruptions("anfang", Arrays.asList(at("buchen")), into);

    assertThat(into).containsKey("interruptedBy");
    assertThat(into.get("interruptedBy").toString()).contains("attachedTo=buchen");
  }
}
