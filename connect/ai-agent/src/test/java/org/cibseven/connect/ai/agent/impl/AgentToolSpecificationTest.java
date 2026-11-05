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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.cibseven.connect.ai.agent.agentic.AdHocToolDescriptor;

import org.junit.jupiter.api.Test;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;

/**
 * What the language model is actually told about the tools.
 *
 * <p>Every other test of these classes calls the methods directly from Java, so
 * the parameter <em>names</em> never matter — and that is how a real defect got
 * all the way into a running distribution: the module compiled without
 * {@code -parameters}, reflection reported the arguments as {@code arg0} and
 * {@code arg1}, and the specification handed to the model named them that way.
 * A caller sending the obvious {@code activityId} then bound it to {@code null},
 * and the engine refused with "Cannot start [null]" — a message that reads like
 * a model error rather than a build setting.
 *
 * <p>This test asserts the specification instead of the method, which is the
 * only place that mistake is visible.
 */
public class AgentToolSpecificationTest {

  private static ToolSpecification specification(Object tool, String name) {
    List<String> offered = new ArrayList<>();
    for (ToolSpecification candidate : ToolSpecifications.toolSpecificationsFrom(tool)) {
      offered.add(candidate.name());
      if (name.equals(candidate.name())) {
        return candidate;
      }
    }
    throw new AssertionError("no tool '" + name + "' among " + offered);
  }

  private static List<String> parameterNames(ToolSpecification specification) {
    if (specification.parameters() == null) {
      return new ArrayList<>();
    }
    return new ArrayList<>(specification.parameters().properties().keySet());
  }

  /**
   * An activity of an agentic scope is offered under its own id, described by its
   * label and documentation — the activity IS the tool, so a wrong name is not
   * expressible for the model.
   */
  @Test
  public void anActivityIsItsOwnTool() {
    AdHocToolDescriptor descriptor = new AdHocToolDescriptor("rechnungPruefen",
        "Rechnung pruefen", "Prueft die eingegangene Rechnung.",
        Collections.<String>emptyList(), false,
        Collections.<AdHocToolDescriptor.Parameter>emptyList());

    ToolSpecification specification =
        new AdHocToolProvider(null).specification(descriptor);

    assertThat(specification.name()).isEqualTo("rechnungPruefen");
    assertThat(specification.description())
        .contains("Rechnung pruefen")
        .contains("Prueft die eingegangene Rechnung.");
    assertThat(parameterNames(specification)).isEmpty();
  }

  /** The description says which process data the tool reads and writes (CIB7-2169). */
  @Test
  public void theDescriptionSaysWhatTheToolReadsAndWrites() {
    AdHocToolDescriptor descriptor = new AdHocToolDescriptor("bonitaetPruefen",
        "Bonitaet pruefen", "Prueft die Bonitaet eines Kunden.",
        Arrays.asList("kundeId"), Arrays.asList("einstufung", "bonitaet"), false,
        Collections.<AdHocToolDescriptor.Parameter>emptyList());

    String description = new AdHocToolProvider(null).specification(descriptor).description();

    assertThat(description)
        .contains("Prueft die Bonitaet eines Kunden. Process data it reads: kundeId."
            + " Process data it writes: einstufung, bonitaet.")
        .contains(" | Starts this activity");
  }

  /** A tool that reads and writes nothing the model declares keeps the description it had. */
  @Test
  public void aToolThatReadsAndWritesNothingKeepsItsDescription() {
    AdHocToolDescriptor descriptor = new AdHocToolDescriptor("rechnungPruefen",
        "Rechnung pruefen", "Prueft die eingegangene Rechnung.",
        Collections.<String>emptyList(), false,
        Collections.<AdHocToolDescriptor.Parameter>emptyList());

    assertThat(new AdHocToolProvider(null).specification(descriptor).description())
        .startsWith("Rechnung pruefen: Prueft die eingegangene Rechnung. | Starts this activity")
        .doesNotContain("Process data");
  }

  /** Many names are cut, so one tool cannot fill the prompt with them. */
  @Test
  public void aLongListOfNamesIsCut() {
    List<String> many = new ArrayList<String>();
    for (int i = 1; i <= 14; i++) {
      many.add("wert" + i);
    }
    AdHocToolDescriptor descriptor = new AdHocToolDescriptor("sammeln", null, null,
        many, Collections.<String>emptyList(), false,
        Collections.<AdHocToolDescriptor.Parameter>emptyList());

    assertThat(new AdHocToolProvider(null).specification(descriptor).description())
        .contains("Process data it reads: wert1, wert2, wert3, wert4, wert5, wert6, wert7, wert8,"
            + " wert9, wert10 and 4 more.")
        .doesNotContain("wert11");
  }

  /**
   * A declared parameter reaches the model with its name, type and description, here two
   * declared required.
   */
  @Test
  public void aDeclaredParameterIsPartOfTheSchema() {
    AdHocToolDescriptor descriptor = new AdHocToolDescriptor("temperaturErmitteln",
        null, null, Collections.<String>emptyList(), false,
        Arrays.asList(
            new AdHocToolDescriptor.Parameter("stadt", "string", "Die gesuchte Stadt", true),
            new AdHocToolDescriptor.Parameter("limit", "integer", "", true)));

    ToolSpecification specification =
        new AdHocToolProvider(null).specification(descriptor);

    assertThat(parameterNames(specification)).containsExactly("stadt", "limit");
    assertThat(specification.parameters().properties().get("stadt").description())
        .isEqualTo("Die gesuchte Stadt");
  }

  /** An optional parameter is part of the schema's properties but not its required list. */
  @Test
  public void anOptionalParameterIsAbsentFromRequired() {
    AdHocToolDescriptor descriptor = new AdHocToolDescriptor("temperaturErmitteln",
        null, null, Collections.<String>emptyList(), false,
        Arrays.asList(new AdHocToolDescriptor.Parameter("stadt", "string", "", false)));

    ToolSpecification specification =
        new AdHocToolProvider(null).specification(descriptor);

    assertThat(specification.parameters().properties())
        .as("still offered as a parameter, just not a required one").containsKey("stadt");
    assertThat(specification.parameters().required())
        .as("the schema never claims any property is required").isEmpty();
  }

  /**
   * A tool with several parameters, all of them optional, still offers every one of them and
   * claims none as required. The schema is built without calling {@code required(...)} at all
   * in that case, so what this pins is the result a model is offered, not which builder calls
   * produced it.
   */
  @Test
  public void aToolWithOnlyOptionalParametersHasAnEmptyRequiredList() {
    AdHocToolDescriptor descriptor = new AdHocToolDescriptor("suche",
        null, null, Collections.<String>emptyList(), false,
        Arrays.asList(
            new AdHocToolDescriptor.Parameter("query", "string", "", false),
            new AdHocToolDescriptor.Parameter("limit", "integer", "", false)));

    ToolSpecification specification =
        new AdHocToolProvider(null).specification(descriptor);

    assertThat(parameterNames(specification)).containsExactly("query", "limit");
    assertThat(specification.parameters().required()).isEmpty();
  }

  /** A required parameter is named in the schema's required list. */
  @Test
  public void aRequiredParameterIsPartOfRequired() {
    AdHocToolDescriptor descriptor = new AdHocToolDescriptor("suche",
        null, null, Collections.<String>emptyList(), false,
        Arrays.asList(
            new AdHocToolDescriptor.Parameter("query", "string", "", false),
            new AdHocToolDescriptor.Parameter("limit", "integer", "", true)));

    ToolSpecification specification =
        new AdHocToolProvider(null).specification(descriptor);

    assertThat(specification.parameters().required()).containsExactly("limit");
  }

  /**
   * The same setting governs the tool that existed before this ticket, and with
   * five parameters — two of them numeric — it has more to lose from positional
   * names than the ad hoc tool does.
   */
  @Test
  public void theProcessStarterToolDeclaresItsParametersByName() {
    ToolSpecification specification =
        specification(new ProcessStarterTool(), "runProcessByKey");

    assertThat(parameterNames(specification)).containsExactly(
        "key", "variables", "outputPrefix", "maxRetries", "pollIntervalMillis");
  }

}
