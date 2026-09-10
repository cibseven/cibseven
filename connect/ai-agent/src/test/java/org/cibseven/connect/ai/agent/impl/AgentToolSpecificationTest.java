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
import java.util.List;

import org.junit.Test;

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

  @Test
  public void theAdHocToolsAreOfferedUnderTheirOwnNames() {
    List<String> names = new ArrayList<>();
    for (ToolSpecification specification :
        ToolSpecifications.toolSpecificationsFrom(new AdHocSubProcessTool())) {
      names.add(specification.name());
    }

    assertThat(names).containsExactlyInAnyOrder(
        "listAvailableActivities", "startActivity", "completeScope");
  }

  /**
   * The one that broke. Without real names the model is offered {@code arg0} and
   * {@code arg1}, and anything it sends under a meaningful name silently becomes
   * null.
   */
  @Test
  public void startActivityDeclaresItsParametersByName() {
    ToolSpecification specification =
        specification(new AdHocSubProcessTool(), "startActivity");

    assertThat(parameterNames(specification))
        .as("the model is told these parameter names")
        .containsExactly("activityId", "variables");
    assertThat(parameterNames(specification))
        .as("reflection fell back to positional names, so -parameters is missing")
        .doesNotContain("arg0", "arg1");
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

  /** Descriptions come from {@code @P} and are independent of the names. */
  @Test
  public void everyParameterCarriesItsDescription() {
    ToolSpecification specification =
        specification(new AdHocSubProcessTool(), "startActivity");

    assertThat(specification.parameters().properties().get("activityId").description())
        .contains("listAvailableActivities");
    assertThat(specification.parameters().properties().get("variables").description())
        .contains("empty object");
  }

  /** A tool without parameters has nothing to lose, and must stay that way. */
  @Test
  public void theParameterlessToolsDeclareNoParameters() {
    assertThat(parameterNames(specification(new AdHocSubProcessTool(), "completeScope")))
        .isEmpty();
    assertThat(parameterNames(
        specification(new AdHocSubProcessTool(), "listAvailableActivities")))
        .isEmpty();
  }
}
