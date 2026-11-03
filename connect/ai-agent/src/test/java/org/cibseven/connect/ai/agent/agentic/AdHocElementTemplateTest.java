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
package org.cibseven.connect.ai.agent.agentic;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.cibseven.connect.ai.agent.AgentConnectorConstants;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The element template for an agentic scope is the AI agent service task's template on another
 * element: the same connector, the same fields. Only what the loop owns is left out, and the
 * scope's limits are added. The two files are kept apart, so this is what stops them drifting.
 */
public class AdHocElementTemplateTest {

  protected static final String STANDARD = "/element-templates/cibseven-ai-agent.json";
  protected static final String AD_HOC = "/element-templates/cibseven-ai-agent-adhoc.json";

  /**
   * Connector fields that exist only on the ad hoc template, because what they mean has no
   * counterpart on a service task that runs once: {@code followUp} (CIB7-2137) replaces the
   * task on the first turn of a re-entry, which only a looping scope ever has, and the turn's
   * outcome (CIB7-2151) says how a turn ended, which a single run does not have.
   */
  protected static final List<String> AD_HOC_ONLY = Arrays.asList(
      "camunda:inputParameter:followUp", "camunda:outputParameter:${outcome}",
      // the AI agent task gets the same two inputs with CIB7-1843; until then only this template has them
      "camunda:inputParameter:" + AgenticAdHocParseListener.CONTEXT_VARIABLES,
      "camunda:inputParameter:" + AgenticAdHocParseListener.OPTIONAL_CONTEXT_VARIABLES);

  @Test
  public void itIsTheTemplateTheParseListenerLooksFor() throws IOException {
    JsonNode template = read(AD_HOC);

    assertThat(template.get("id").asText()).isEqualTo(AgenticAdHocParseListener.AD_HOC_TEMPLATE_ID);
    assertThat(template.get("name").asText()).isEqualTo(AgenticAdHocParseListener.AD_HOC_TEMPLATE_NAME);
    assertThat(template.get("appliesTo").toString()).isEqualTo("[\"bpmn:AdHocSubProcess\"]");
  }

  @Test
  public void itWritesTheAgentsConnectorAndTheScopesLimitsAndSettingsAsProperties()
      throws IOException {
    JsonNode template = read(AD_HOC);

    JsonNode scope = template.get("scopes").get(0);
    assertThat(scope.get("type").asText()).isEqualTo("camunda:Connector");
    assertThat(fields(scope.get("properties")).get("property:connectorId").get("value").asText())
        .isEqualTo(AgenticTurnRunner.AGENT_CONNECTOR_ID);

    // The agent's limits (CIB7-2148), where its conversation lives and what bounds it across
    // entries (CIB7-2137) and what a limit does (CIB7-2151) -- none of these may be a child's to
    // rewrite, so none is an input parameter of the connector. The process variables it sees are
    // the connector's inputs, as on the AI agent task, read once at deployment (CIB7-2179).
    assertThat(fields(template.get("properties")).keySet()).containsExactlyInAnyOrder(
        "camunda:property:" + AgenticAdHocParseListener.MAX_TURNS,
        "camunda:property:" + AgenticAdHocParseListener.MAX_MODEL_CALLS,
        "camunda:property:" + AgenticAdHocParseListener.MAX_TURNS_TOTAL,
        "camunda:property:" + AgenticAdHocParseListener.CONVERSATION,
        "camunda:property:" + AgenticAdHocParseListener.ON_LIMIT);
  }

  /**
   * The process variables the agent sees (CIB7-2179): the connector's input, as on the AI agent
   * task, in the group with the agent's task; read once at deployment, so no child can widen what
   * its agent is sent. The modeler refuses an empty
   * name before the deployment would.
   */
  @Test
  public void theProcessVariablesAreAnInputThatRefusesAnEmptyName() throws IOException {
    JsonNode field = fields(read(AD_HOC).get("scopes").get(0).get("properties"))
        .get("camunda:inputParameter:" + AgenticAdHocParseListener.CONTEXT_VARIABLES);

    assertThat(text(field, "group")).isEqualTo("input");
    assertThat(text(field, "type")).isEqualTo("String");
    assertThat(field.has("value")).as("none by default").isFalse();
    // As the modeler applies it: JavaScript's RegExp.test, which finds a match anywhere and whose \s
    // is Unicode. Each name can be matched one way only, so a value refused while it is typed, after a
    // comma, costs linear time there; an ambiguous form such as [^,]*X[^,]* is exponential in the
    // number of names and freezes the field.
    Pattern pattern = Pattern.compile(field.get("constraints").get("pattern").get("value").asText(),
        Pattern.UNICODE_CHARACTER_CLASS);
    for (String accepted : Arrays.asList("", " ", "kunde", "kunde, prioritaet",
        " kunde ,prioritaet ", "kunde.name, prioritaet", "kunde, prioritaet")) {
      assertThat(pattern.matcher(accepted).find()).as("'" + accepted + "'").isTrue();
    }
    // A space inside a name is a comma left out, and ${…} or braces an expression or a list: the
    // panel says so before the deployment would (the review pass of 2026-11-02, findings 5 and 7).
    for (String refused : Arrays.asList("kunde,,prioritaet", "kunde,", ",kunde",
        "kunde, , prioritaet", "kunde, ", "kunde name, prioritaet", "${kunde}", "kunde, {a}")) {
      assertThat(pattern.matcher(refused).find()).as("'" + refused + "'").isFalse();
    }
  }

  /**
   * Every process variable the agent sees is required unless the field beside it names it
   * (CIB7-2179); the field says so, with its limits, and that a secret has no place there.
   */
  @Test
  public void theOptionalProcessVariablesAreAnInputBesideThem() throws IOException {
    Map<String, JsonNode> fields = fields(read(AD_HOC).get("scopes").get(0).get("properties"));
    JsonNode seen =
        fields.get("camunda:inputParameter:" + AgenticAdHocParseListener.CONTEXT_VARIABLES);
    JsonNode optional =
        fields.get("camunda:inputParameter:" + AgenticAdHocParseListener.OPTIONAL_CONTEXT_VARIABLES);

    assertThat(optional).as("a field of its own").isNotNull();
    assertThat(text(seen, "label")).isEqualTo("Context variables");
    assertThat(text(optional, "label")).isEqualTo("Optional context variables");
    assertThat(text(optional, "group")).isEqualTo("input");
    assertThat(text(optional, "type")).isEqualTo("String");
    assertThat(optional.has("value")).as("none by default").isFalse();
    assertThat(optional.get("constraints")).as("names as the field it narrows takes them")
        .isEqualTo(seen.get("constraints"));
    assertThat(text(optional, "description")).contains("Empty: every one is required.")
        .contains("a file, bytes or a Java object is shown by its type only");
    // What a process developer asked of the text (the review pass of 2026-11-02): a name, not a
    // path; when to use it instead of ${name}; what fails, and what the operator then sees, null
    // among it; what the agent never sees; where the value is, since the chat log keeps a marker.
    assertThat(text(seen, "description"))
        .contains("the name of a variable, not a path into it")
        .contains("instead of ${name} in Message")
        .contains("with the state of this sub process")
        .contains("up to 4000 characters each and about 20000 in all")
        .contains("Each one is required unless Optional context variables names it")
        .contains("a file, bytes or a Java object, which the agent never sees")
        .contains("after the job's retries, the sub process gets an incident naming it")
        .contains("if it is not set or null")
        .contains("the value is in the variable's history")
        .contains("Never list tokens or passwords: the values go to the model's and the"
            + " embedding's provider");
    JsonNode message = fields(read(AD_HOC).get("scopes").get(0).get("properties"))
        .get("camunda:inputParameter:message");
    assertThat(text(message, "description")).as("the task points at the field for data")
        .contains("list the variable under Context variables");
  }

  @Test
  public void itLeavesOutWhatTheLoopOwns() throws IOException {
    Map<String, JsonNode> connector = fields(read(AD_HOC).get("scopes").get(0).get("properties"));

    for (String loopOwned : AgenticAdHocParseListener.LOOP_PARAMETERS) {
      assertThat(connector).doesNotContainKey("camunda:inputParameter:" + loopOwned);
    }
    assertThat(connector).doesNotContainKey("camunda:outputParameter:${memoryId}");
  }

  @Test
  public void anEmptyMemoryWindowUsesTheContextualDefault() throws IOException {
    JsonNode window = fields(read(AD_HOC).get("scopes").get(0).get("properties"))
        .get("camunda:inputParameter:chatMemoryMaxMessages");

    assertThat(window.has("value")).isFalse();
    assertThat(text(window, "description"))
        .contains("Default 20")
        .contains("about the last six calls")
        .contains("With 'Continue across entries' an empty value means at least 53")
        .contains("2 * the effective Max model calls per turn + 3 is rejected")
        // the user sees the dropdown's labels, not the values it writes
        .doesNotContain("perEntry")
        .doesNotContain("perActivity");
  }

  /**
   * The Conversation dropdown sends the reader to the field that bounds what is remembered, by
   * the label the modeler shows; what that field holds when left empty is said there, once.
   */
  @Test
  public void theConversationTextNamesTheWindowField() throws IOException {
    JsonNode template = read(AD_HOC);
    JsonNode conversation = fields(template.get("properties"))
        .get("camunda:property:" + AgenticAdHocParseListener.CONVERSATION);
    JsonNode window = fields(template.get("scopes").get(0).get("properties"))
        .get("camunda:inputParameter:chatMemoryMaxMessages");

    assertThat(text(conversation, "description")).contains(text(window, "label"));
  }

  /**
   * With 'Throw a BPMN error' a deployment is refused unless boundary error events catch every
   * limit the scope can reach, each by its code. The On limit text is where the modeller looks for
   * those codes.
   */
  @Test
  public void theOnLimitTextNamesEveryLimitCode() throws IOException {
    JsonNode onLimit = fields(read(AD_HOC).get("properties"))
        .get("camunda:property:" + AgenticAdHocParseListener.ON_LIMIT);

    assertThat(text(onLimit, "description")).contains(AgenticOutcome.LIMITS);
  }

  /**
   * The modeller's instruction is added to the agent's built-in prompt (CIB7-2235) only when the
   * two are combined; without a value the connector replaces it. So the template presets append.
   */
  @Test
  public void theTemplatePresetsAppend() throws IOException {
    JsonNode mode = fields(read(AD_HOC).get("scopes").get(0).get("properties"))
        .get("camunda:inputParameter:instructionMode");

    assertThat(text(mode, "value")).isEqualTo(AgentConnectorConstants.INSTRUCTION_MODE_APPEND);
  }

  /**
   * The texts are read in the modeler by whoever models the process. A ticket key or a release
   * phase tells them nothing there, and it stays behind once the ticket is closed. Every text of
   * the template is read, not only the fields': a group's label, a placeholder and the message of
   * a check are shown as well.
   */
  @Test
  public void noTextNamesATicketOrAReleasePhase() throws IOException {
    List<String> naming = new ArrayList<String>();
    findInTexts(read(AD_HOC), "", Pattern.compile("CIB7-\\d+|\\bMVP\\b"), naming);
    assertThat(naming).as("texts naming a ticket or a release phase").isEmpty();
  }

  /** Adds where {@code pattern} is found in any text under {@code node}, and what it found. */
  protected static void findInTexts(JsonNode node, String path, Pattern pattern,
      List<String> found) {
    if (node.isTextual()) {
      Matcher match = pattern.matcher(node.asText());
      if (match.find()) {
        found.add(path + " -> " + match.group());
      }
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        findInTexts(node.get(i), path + "/" + i, pattern, found);
      }
    } else {
      for (Map.Entry<String, JsonNode> field : node.properties()) {
        findInTexts(field.getValue(), path + "/" + field.getKey(), pattern, found);
      }
    }
  }

  /**
   * A dropdown's text names its choices as the modeler shows them, not by a word of its own: the
   * reader looks for the quoted name in the list.
   */
  @Test
  public void aDropdownsTextQuotesOnlyTheChoicesItShows() throws IOException {
    JsonNode template = read(AD_HOC);
    List<JsonNode> all = new ArrayList<JsonNode>();
    template.get("properties").forEach(all::add);
    template.get("scopes").get(0).get("properties").forEach(all::add);

    List<String> unknown = new ArrayList<String>();
    for (JsonNode property : all) {
      if (!"Dropdown".equals(text(property, "type"))) {
        continue;
      }
      Set<String> shown = new HashSet<String>();
      for (JsonNode choice : property.path("choices")) {
        shown.add(text(choice, "name"));
      }
      Matcher quoted = Pattern.compile("'([^']+)'").matcher(text(property, "description"));
      while (quoted.find()) {
        if (!shown.contains(quoted.group(1))) {
          unknown.add(text(property, "label") + " -> '" + quoted.group(1) + "'");
        }
      }
    }
    assertThat(unknown).as("quoted names that are no choice of their dropdown").isEmpty();
  }

  /** Same label, type and binding as the service task's field; defaults and texts may differ. */
  @Test
  public void everyConnectorFieldIsTheServiceTasksField() throws IOException {
    Map<String, JsonNode> standard = fields(read(STANDARD).get("scopes").get(0).get("properties"));
    Map<String, JsonNode> adHoc = fields(read(AD_HOC).get("scopes").get(0).get("properties"));

    List<String> differing = new ArrayList<String>();
    for (Map.Entry<String, JsonNode> field : adHoc.entrySet()) {
      if (AD_HOC_ONLY.contains(field.getKey())) {
        continue;
      }
      JsonNode same = standard.get(field.getKey());
      if (same == null
          || !text(same, "label").equals(text(field.getValue(), "label"))
          || !text(same, "type").equals(text(field.getValue(), "type"))
          || !same.get("binding").equals(field.getValue().get("binding"))) {
        differing.add(field.getKey());
      }
    }
    assertThat(differing).as("fields of the ad hoc template that the service task's lacks or"
        + " declares otherwise").isEmpty();
  }

  /**
   * A condition names another field of the same template by its id. One that names a field the
   * template does not have is never met: the modeler then hides its field for good and never
   * writes it. That is how the chat memory window went missing once useChatMemory was left out.
   */
  @Test
  public void everyConditionNamesAFieldTheTemplateHas() throws IOException {
    for (String resource : new String[] { STANDARD, AD_HOC }) {
      List<JsonNode> all = new ArrayList<JsonNode>();
      JsonNode template = read(resource);
      template.get("properties").forEach(all::add);
      for (JsonNode scope : template.path("scopes")) {
        scope.get("properties").forEach(all::add);
      }
      Set<String> ids = new HashSet<String>();
      for (JsonNode property : all) {
        if (property.has("id")) {
          ids.add(property.get("id").asText());
        }
      }

      List<String> dangling = new ArrayList<String>();
      for (JsonNode property : all) {
        JsonNode condition = property.path("condition");
        List<JsonNode> simple = new ArrayList<JsonNode>();
        if (condition.has("allMatch")) {
          condition.get("allMatch").forEach(simple::add);
        } else if (condition.has("property")) {
          simple.add(condition);
        }
        for (JsonNode one : simple) {
          if (!ids.contains(one.get("property").asText())) {
            dangling.add(text(property, "label") + " -> " + one.get("property").asText());
          }
        }
      }
      assertThat(dangling).as(resource + ": conditions naming no field of the template").isEmpty();
    }
  }

  /** The fields of a template or scope, keyed by what they write. */
  protected static Map<String, JsonNode> fields(JsonNode properties) {
    Map<String, JsonNode> byBinding = new LinkedHashMap<String, JsonNode>();
    for (JsonNode property : properties) {
      JsonNode binding = property.get("binding");
      String target = binding.has("name") ? binding.get("name").asText()
          : binding.get("source").asText();
      byBinding.put(binding.get("type").asText() + ":" + target, property);
    }
    return byBinding;
  }

  protected static String text(JsonNode node, String field) {
    return node.has(field) ? node.get(field).asText() : "";
  }

  protected static JsonNode read(String resource) throws IOException {
    try (InputStream in = AdHocElementTemplateTest.class.getResourceAsStream(resource)) {
      return new ObjectMapper().readTree(in);
    }
  }
}
