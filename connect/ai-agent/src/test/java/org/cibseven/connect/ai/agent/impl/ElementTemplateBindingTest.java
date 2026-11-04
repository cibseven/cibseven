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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.cibseven.connect.ai.agent.AgentConnector;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The element template is what writes the input parameters into a modeled
 * BPMN, and nothing else reads it: a typo in a binding name would silently
 * drop the declared context or documents of every modeled agent task.
 */
public class ElementTemplateBindingTest {

  private static List<JsonNode> properties(String group) throws IOException {
    try (InputStream in = ElementTemplateBindingTest.class
        .getResourceAsStream("/element-templates/cibseven-ai-agent.json")) {
      assertThat(in).as("element template on the classpath").isNotNull();
      JsonNode template = new ObjectMapper().readTree(in);
      List<JsonNode> result = new ArrayList<>();
      template.path("properties").forEach(p -> addIfInGroup(result, p, group));
      template.path("scopes").forEach(scope ->
          scope.path("properties").forEach(p -> addIfInGroup(result, p, group)));
      return result;
    }
  }

  private static void addIfInGroup(List<JsonNode> result, JsonNode property, String group) {
    if (group.equals(property.path("group").asText())) {
      result.add(property);
    }
  }

  private static List<String> bindingNames(List<JsonNode> properties) {
    List<String> names = new ArrayList<>();
    for (JsonNode property : properties) {
      assertThat(property.path("binding").path("type").asText())
          .isEqualTo("camunda:inputParameter");
      names.add(property.path("binding").path("name").asText());
    }
    return names;
  }

  @Test
  public void shouldBindTheContextGroupToTheConnectorsParameterNames() throws IOException {
    assertThat(bindingNames(properties("context"))).containsExactlyInAnyOrder(
        AgentConnector.PARAM_NAME_CONTEXT_VARIABLES,
        AgentConnector.PARAM_NAME_OPTIONAL_CONTEXT_VARIABLES);
  }

  @Test
  public void shouldBindTheDocumentsGroupToTheConnectorsParameterNames() throws IOException {
    assertThat(bindingNames(properties("documents"))).containsExactlyInAnyOrder(
        AgentConnector.PARAM_NAME_DOCUMENTS,
        AgentConnector.PARAM_NAME_DOCUMENT_MIME_TYPES,
        AgentConnector.PARAM_NAME_DOCUMENT_DETAIL_LEVEL);
  }

  /** Every dropdown choice must be a level the connector accepts. */
  @Test
  public void shouldOfferOnlyDetailLevelsTheConnectorAccepts() throws IOException {
    List<String> values = new ArrayList<>();
    for (JsonNode property : properties("documents")) {
      if (AgentConnector.PARAM_NAME_DOCUMENT_DETAIL_LEVEL
          .equals(property.path("binding").path("name").asText())) {
        property.path("choices").forEach(choice -> values.add(choice.path("value").asText()));
      }
    }
    assertThat(values).isNotEmpty();
    for (String value : values) {
      // Throws for anything the connector would refuse at run time.
      DocumentContentResolver.parseDetailLevel(value);
    }
  }
}
