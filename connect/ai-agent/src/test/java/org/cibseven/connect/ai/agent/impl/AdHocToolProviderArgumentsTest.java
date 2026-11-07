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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import org.cibseven.connect.ai.agent.agentic.AdHocToolDescriptor;

import org.junit.jupiter.api.Test;

/**
 * {@link AdHocToolProvider#arguments} directly, without an engine: what the connector makes of a
 * call's arguments before any activity starts.
 */
public class AdHocToolProviderArgumentsTest {

  private static final AdHocToolDescriptor SUCHE = new AdHocToolDescriptor("suche", null, null,
      Collections.<String>emptyList(), false,
      Arrays.asList(
          new AdHocToolDescriptor.Parameter("query", "string", "", false),
          new AdHocToolDescriptor.Parameter("limit", "integer", "", true)));

  private static Map<String, Object> arguments(String json) {
    return new AdHocToolProvider(null).arguments(SUCHE, json);
  }

  /**
   * An optional parameter may simply be left out: the call proceeds, and the parameter is not
   * among the values, so {@code toolCall.<name>} reads null in the activity's mapping.
   */
  @Test
  public void anOmittedOptionalParameterIsLeftOut() {
    Map<String, Object> values = arguments("{\"limit\":3}");

    assertThat(values).containsEntry("limit", 3L);
    assertThat(values).doesNotContainKey("query");
  }

  /** An optional parameter passed as an explicit {@code null} is handed on as null. */
  @Test
  public void anExplicitNullOptionalParameterIsHandedOnAsNull() {
    Map<String, Object> values = arguments("{\"query\":null,\"limit\":3}");

    assertThat(values).containsEntry("limit", 3L);
    assertThat(values).containsKey("query");
    assertThat(values.get("query")).isNull();
  }

  /** A required parameter that is simply absent is refused before any activity starts. */
  @Test
  public void aMissingRequiredParameterIsRefused() {
    assertThatThrownBy(() -> arguments("{\"query\":\"Rom\"}"))
        .isInstanceOf(AdHocToolRefusedException.class)
        .hasMessageContaining("is missing required parameter 'limit'")
        .hasMessageContaining("Call again with it included.");
  }

  /** A required parameter passed as {@code null} is refused the same way as an absent one. */
  @Test
  public void aNullRequiredParameterIsRefusedLikeAMissingOne() {
    assertThatThrownBy(() -> arguments("{\"query\":\"Rom\",\"limit\":null}"))
        .isInstanceOf(AdHocToolRefusedException.class)
        .hasMessageContaining("is missing required parameter 'limit'");
  }

  /**
   * Several missing required parameters are named together, in one refusal, not one at a time.
   * Listed in the descriptor's own parameter order here — the real parser (TreeMap, sorted by
   * name) would deliver them alphabetically, but that order is this descriptor's to set, not
   * something {@code missingRequiredParameters} imposes.
   */
  @Test
  public void severalMissingRequiredParametersAreNamedTogether() {
    AdHocToolDescriptor twoRequired = new AdHocToolDescriptor("buchen", null, null,
        Collections.<String>emptyList(), false,
        Arrays.asList(
            new AdHocToolDescriptor.Parameter("betrag", "integer", "", true),
            new AdHocToolDescriptor.Parameter("konto", "string", "", true)));

    assertThatThrownBy(() -> new AdHocToolProvider(null).arguments(twoRequired, "{}"))
        .isInstanceOf(AdHocToolRefusedException.class)
        .hasMessageContaining("is missing required parameters 'betrag', 'konto'")
        .hasMessageContaining("Call again with them included.");
  }

  /** A call that supplies every required parameter is not refused. */
  @Test
  public void aCallWithAllRequiredParametersIsNotRefused() {
    Map<String, Object> values = arguments("{\"query\":\"Rom\",\"limit\":3}");

    assertThat(values).containsEntry("query", "Rom").containsEntry("limit", 3L);
  }

  /** An undeclared name is refused, as before this ticket — required-ness does not change this. */
  @Test
  public void anUndeclaredArgumentIsStillRefused() {
    assertThatThrownBy(() -> arguments("{\"query\":\"Rom\",\"limit\":3,\"farbe\":\"rot\"}"))
        .isInstanceOf(AdHocToolRefusedException.class)
        .hasMessageContaining("has no parameter 'farbe'");
  }

  /**
   * An undeclared argument and a missing required parameter are refused together, in one
   * message, so the model fixes the whole call in its next attempt.
   */
  @Test
  public void anUndeclaredArgumentAndAMissingRequiredParameterAreRefusedTogether() {
    assertThatThrownBy(() -> arguments("{\"query\":\"Rom\",\"farbe\":\"rot\"}"))
        .isInstanceOf(AdHocToolRefusedException.class)
        .hasMessageContaining("has no parameter 'farbe'")
        .hasMessageContaining("is missing required parameter 'limit'");
  }

  /** A required parameter passed with the wrong type is refused for its type, not as missing. */
  @Test
  public void aWronglyTypedRequiredParameterIsNotAlsoMissing() {
    assertThatThrownBy(() -> arguments("{\"query\":\"Rom\",\"limit\":\"drei\"}"))
        .isInstanceOf(AdHocToolRefusedException.class)
        .hasMessageContaining("parameter 'limit' must be a integer")
        .hasMessageNotContaining("missing");
  }

  /**
   * A wrong type does not stop the check of the rest: with an undeclared argument and a missing
   * required parameter in the same call, all three are refused together, in one message.
   */
  @Test
  public void aWrongTypeIsRefusedTogetherWithTheOtherMistakes() {
    assertThatThrownBy(() -> arguments("{\"query\":5,\"farbe\":\"rot\"}"))
        .isInstanceOf(AdHocToolRefusedException.class)
        .hasMessageContaining("parameter 'query' must be a string")
        .hasMessageContaining("has no parameter 'farbe'")
        .hasMessageContaining("is missing required parameter 'limit'");
  }

  /** As {@link #SUCHE}, with 'query' the whole value of one of the tool's own inputs. */
  private static final AdHocToolDescriptor SUCHE_STORED = new AdHocToolDescriptor("suche", null,
      null, Collections.<String>emptyList(), false, Arrays.asList(
          new AdHocToolDescriptor.Parameter("query", "string", "", false, true),
          new AdHocToolDescriptor.Parameter("limit", "integer", "", true)));

  private static Map<String, Object> storedArguments(String json) {
    return new AdHocToolProvider(null).arguments(SUCHE_STORED, json);
  }

  /** A text a String variable holds passes whole: 4000 characters, the column outside Oracle. */
  @Test
  public void aTextAsLongAsAStringVariableHoldsPasses() {
    Map<String, Object> values =
        storedArguments("{\"query\":\"" + text(4000) + "\",\"limit\":3}");

    assertThat((String) values.get("query")).hasSize(4000);
  }

  /**
   * A longer one, for a parameter the tool stores whole, is refused before the activity starts,
   * with its length and the limit, so the model shortens it. Let through, the input would store
   * it as a String variable, and the step would fail at the flush on every attempt, the model
   * asked and paid each time. It is refused as a mistake of its own, not also as a missing one.
   */
  @Test
  public void aTextLongerThanAStringVariableHoldsIsRefused() {
    assertThatThrownBy(() -> storedArguments("{\"query\":\"" + text(4001) + "\",\"limit\":3}"))
        .isInstanceOf(AdHocToolRefusedException.class)
        .hasMessageContaining("'suche' parameter 'query' is 4001 characters long")
        .hasMessageContaining("at most 4000 characters")
        .hasMessageNotContaining("missing required");
  }

  /**
   * A parameter the tool does not store whole -- in a connector's input, a list, a map, a text
   * with more around it -- is handed on at any length: no String variable holds it as it is.
   */
  @Test
  public void aLongTextOfAParameterNotStoredWholeIsHandedOn() {
    Map<String, Object> values = arguments("{\"query\":\"" + text(4001) + "\",\"limit\":3}");

    assertThat((String) values.get("query")).hasSize(4001);
  }

  /** On Oracle the column holds 2000 characters, and the limit the model is told says so. */
  @Test
  public void onOracleATextOver2000CharactersIsRefused() {
    AdHocToolProvider onOracle = new AdHocToolProvider(null) {
      @Override
      protected String databaseType() {
        return "oracle";
      }
    };

    assertThat((String) onOracle.arguments(SUCHE_STORED,
        "{\"query\":\"" + text(2000) + "\",\"limit\":3}").get("query")).hasSize(2000);
    assertThatThrownBy(() -> onOracle.arguments(SUCHE_STORED,
        "{\"query\":\"" + text(2001) + "\",\"limit\":3}"))
        .isInstanceOf(AdHocToolRefusedException.class)
        .hasMessageContaining("is 2001 characters long")
        .hasMessageContaining("at most 2000 characters");
  }

  /** On DB2 the column counts bytes too: the model is told both, umlauts reach the bytes first. */
  @Test
  public void onDb2ATextOver4000BytesIsRefusedWithItsBytes() {
    AdHocToolProvider onDb2 = new AdHocToolProvider(null) {
      @Override
      protected String databaseType() {
        return "db2";
      }
    };
    StringBuilder umlauts = new StringBuilder();
    for (int i = 0; i < 2001; i++) {
      umlauts.append('\u00e4');
    }

    assertThatThrownBy(() -> onDb2.arguments(SUCHE_STORED,
        "{\"query\":\"" + umlauts + "\",\"limit\":3}"))
        .isInstanceOf(AdHocToolRefusedException.class)
        .hasMessageContaining("is 2001 characters, 4002 bytes, long")
        .hasMessageContaining("at most 4000 characters and 4000 bytes");
  }

  private static String text(int length) {
    StringBuilder text = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      text.append('a');
    }
    return text.toString();
  }
}
