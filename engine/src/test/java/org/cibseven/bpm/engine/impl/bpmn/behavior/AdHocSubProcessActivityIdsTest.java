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
package org.cibseven.bpm.engine.impl.bpmn.behavior;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

/**
 * The list of activities to activate of an ad hoc sub process is read through JsonUtil.fromJson (CIB7-2634):
 * as before with Gson's lenient parsing.
 */
public class AdHocSubProcessActivityIdsTest {

  @Test
  public void shouldReadJsonArrayOfIds() {
    assertThat(ids(AdHocSubProcessActivityBehavior.jsonArrayOfIds("[\"a\", \"b\"]", "scope"))).containsExactly("a", "b");
  }

  @Test
  public void shouldReadJsonArrayOfIdsLeniently() {
    assertThat(ids(AdHocSubProcessActivityBehavior.jsonArrayOfIds("['a', 'b']", "scope"))).containsExactly("a", "b");
  }

  protected static List<String> ids(JsonArray array) {
    return array.asList().stream().map(JsonElement::getAsString).toList();
  }
}
