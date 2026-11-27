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
package org.cibseven.bpm.engine.test.bpmn.adhoc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParse;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.util.IoUtil;
import org.cibseven.bpm.engine.impl.util.JsonUtil;
import org.cibseven.bpm.engine.runtime.Execution;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.util.PluggableProcessEngineTest;
import org.junit.jupiter.api.Test;

/**
 * The element template of the plain ad hoc sub process (CIB7-2939). The Modeler finds it in the
 * engine jar through {@code classpath*:element-templates/*.json}. It gives the three extension
 * properties the parser reads named fields, so a modeller no longer types their names.
 */
public class AdHocElementTemplateTest extends PluggableProcessEngineTest {

  protected static final String TEMPLATE = "/element-templates/cibseven-adhoc.json";

  protected static JsonObject template() throws IOException {
    try (InputStream in = AdHocElementTemplateTest.class.getResourceAsStream(TEMPLATE)) {
      assertThat(in).as("the template ships in the engine jar at " + TEMPLATE).isNotNull();
      return JsonUtil.asObject(new String(IoUtil.readInputStream(in, TEMPLATE), StandardCharsets.UTF_8));
    }
  }

  protected static Map<String, JsonObject> propertiesByBinding(JsonObject template) {
    Map<String, JsonObject> byName = new HashMap<>();
    for (JsonElement property : template.getAsJsonArray("properties")) {
      JsonObject binding = property.getAsJsonObject().getAsJsonObject("binding");
      assertThat(binding.get("type").getAsString()).as("every field is an extension property")
          .isEqualTo("camunda:property");
      byName.put(binding.get("name").getAsString(), property.getAsJsonObject());
    }
    return byName;
  }

  @Test
  public void itIsAnAdHocTemplateNamedLikeTheOthers() throws IOException {
    JsonObject template = template();

    assertThat(template.get("id").getAsString()).isEqualTo("org.cibseven.bpm.AdHocSubProcess");
    // the bundled templates are named "CIB seven - <Name>"; the Modeler groups them by the prefix
    assertThat(template.get("name").getAsString()).isEqualTo("CIB seven - Ad Hoc Sub Process");
    assertThat(template.get("appliesTo").toString()).isEqualTo("[\"bpmn:AdHocSubProcess\"]");
    // the model of theModelAsTheTemplateWritesItRuns carries this id and version
    assertThat(template.get("version").getAsInt()).isEqualTo(1);
  }

  /**
   * A template cannot set the completion condition, a child element of the ad hoc sub process.
   * The Modeler's own groups stay visible so that "Completion" can still be edited.
   */
  @Test
  public void itKeepsTheModelersOwnGroupsSoTheCompletionConditionStaysEditable() throws IOException {
    assertThat(template().get("entriesVisible").getAsBoolean()).isTrue();
  }

  @Test
  public void itsFieldsAreTheExtensionPropertiesTheParserReads() throws IOException {
    assertThat(propertiesByBinding(template()).keySet()).containsExactlyInAnyOrder(
        BpmnParse.AD_HOC_ENTRY_ACTIVITIES_PROPERTY,
        BpmnParse.AD_HOC_OUTPUT_COLLECTION_PROPERTY,
        BpmnParse.AD_HOC_OUTPUT_ELEMENT_PROPERTY);
  }

  /**
   * As the Modeler applies it: JavaScript's RegExp.test. The field takes the forms the parser takes
   * (a list, a JSON array, an expression, nothing) and refuses a list with an empty or a spaced name,
   * which the parser would skip or reject.
   */
  @Test
  public void theActivitiesFieldAcceptsWhatTheParserAccepts() throws IOException {
    JsonObject field = propertiesByBinding(template()).get(BpmnParse.AD_HOC_ENTRY_ACTIVITIES_PROPERTY);
    Pattern pattern = Pattern.compile(
        field.getAsJsonObject("constraints").getAsJsonObject("pattern").get("value").getAsString(),
        Pattern.UNICODE_CHARACTER_CLASS);

    List<String> accepted = Arrays.asList("", " ", "callCustomer", "callCustomer,checkLogs",
        " callCustomer , checkLogs ", "[\"callCustomer\",\"checkLogs\"]", "[]", "${firstSteps}", "#{firstSteps}",
        "${firstStep},checkLogs");
    for (String value : accepted) {
      assertThat(pattern.matcher(value).find()).as("'" + value + "'").isTrue();
    }
    List<String> refused = Arrays.asList("callCustomer,", ",checkLogs", "call customer",
        "callCustomer,,checkLogs", "[1]", "[\"call customer\"]");
    for (String value : refused) {
      assertThat(pattern.matcher(value).find()).as("'" + value + "'").isFalse();
    }
  }

  /**
   * The model as the template writes it deploys and runs: entry, gathering only what the result expression
   * selects, and a completion condition that leaves the scope while another activity is still active.
   */
  @Deployment
  @Test
  public void theModelAsTheTemplateWritesItRuns() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("adHocTemplate");
    assertThat(taskKeys(pi)).containsExactlyInAnyOrder("callCustomer", "checkLogs");

    // the value 'callCustomer' leaves in 'finding' is not what the expression gathers
    taskService.complete(task(pi, "callCustomer").getId(), Collections.singletonMap("finding", "cable fault"));
    runtimeService.activateAdHocSubProcessActivities(scopeExecutionId(pi), Collections.singletonList("orderSparePart"));

    Map<String, Object> logs = new HashMap<>();
    logs.put("finding", "link down");
    logs.put("resolved", true);
    taskService.complete(task(pi, "checkLogs").getId(), logs);

    assertThat(runtimeService.getVariable(pi.getId(), "findings")).isEqualTo(Arrays.asList("link down"));
    assertThat(taskKeys(pi)).as("the completion condition left the scope and cancelled orderSparePart")
        .containsExactly("closeIncident");
  }

  protected String scopeExecutionId(ProcessInstance pi) {
    for (Execution execution : runtimeService.createExecutionQuery().processInstanceId(pi.getId()).list()) {
      if ("handleIncident".equals(((ExecutionEntity) execution).getActivityId())) {
        return execution.getId();
      }
    }
    throw new AssertionError("no execution sitting on the ad hoc scope");
  }

  protected List<String> taskKeys(ProcessInstance pi) {
    return taskService.createTaskQuery().processInstanceId(pi.getId()).list().stream()
        .map(Task::getTaskDefinitionKey).collect(Collectors.toList());
  }

  protected Task task(ProcessInstance pi, String key) {
    return taskService.createTaskQuery().processInstanceId(pi.getId()).taskDefinitionKey(key).singleResult();
  }
}
