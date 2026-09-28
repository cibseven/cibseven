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

import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocToolDescriptor;

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

  /** A declared parameter reaches the model with its name, type and description. */
  @Test
  public void aDeclaredParameterIsPartOfTheSchema() {
    AdHocToolDescriptor descriptor = new AdHocToolDescriptor("temperaturErmitteln",
        null, null, Collections.<String>emptyList(), false,
        Arrays.asList(
            new AdHocToolDescriptor.Parameter("stadt", "string", "Die gesuchte Stadt"),
            new AdHocToolDescriptor.Parameter("limit", "integer", "")));

    ToolSpecification specification =
        new AdHocToolProvider(null).specification(descriptor);

    assertThat(parameterNames(specification)).containsExactly("stadt", "limit");
    assertThat(specification.parameters().properties().get("stadt").description())
        .isEqualTo("Die gesuchte Stadt");
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
