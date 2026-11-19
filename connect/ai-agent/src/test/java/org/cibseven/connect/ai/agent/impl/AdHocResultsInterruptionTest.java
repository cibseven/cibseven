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
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Whether an interruption sat on the tool itself, which decides what a waiting result says about a
 * start again (GH-610): the same rule as the result's own "did not complete".
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

  @Test
  void onTheToolItself() {
    assertThat(AdHocResults.interruptedOnTheTool(Arrays.asList(at("buchen")), "buchen")).isTrue();
  }

  @Test
  void furtherAlongItsFlow() {
    assertThat(AdHocResults.interruptedOnTheTool(Arrays.asList(at("buchen")), "anfang")).isFalse();
  }

  @Test
  void anEntryThatSaysNothingOfWhereCountsAsOnTheTool() {
    assertThat(AdHocResults.interruptedOnTheTool(Arrays.asList(at(null)), "anfang")).isTrue();
  }

  @Test
  void oneOfSeveralOnTheToolIsEnough() {
    assertThat(AdHocResults.interruptedOnTheTool(
        Arrays.asList(at("buchen"), at("anfang")), "anfang")).isTrue();
  }

  @Test
  void emptyEntriesAndNothingAreSkipped() {
    assertThat(AdHocResults.interruptedOnTheTool(
        Arrays.asList(Collections.emptyMap(), at("buchen")), "anfang")).isFalse();
    assertThat(AdHocResults.interruptedOnTheTool(null, "anfang")).isFalse();
    assertThat(AdHocResults.interruptedOnTheTool(Collections.emptyList(), "anfang")).isFalse();
  }

  @Test
  void aSingleEntryNotInAListIsReadToo() {
    assertThat(AdHocResults.interruptedOnTheTool(at("anfang"), "anfang")).isTrue();
  }
}
