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
package org.cibseven.connect.plugin.impl.agentic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocToolDescriptor;
import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParse;
import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParseUtil;
import org.cibseven.bpm.engine.impl.util.xml.Element;

/**
 * Builds the tool catalogue of an agentic ad hoc sub process at parse time.
 *
 * <p>One {@link AdHocToolDescriptor} per directly startable child, in document order.
 * Everything a turn needs to describe the child as a tool is read here, once, from the
 * model — and everything that would make the child unusable as a tool refuses the
 * deployment, instead of surfacing turns later as a runtime error the modeller never
 * sees.
 */
public final class AgenticToolCatalogParser {

  /**
   * What a tool may be called. The activity id becomes the function name the model
   * sees, and the OpenAI contract allows no more than these characters. An id outside
   * this alphabet is refused at deployment; renaming an element is cheap there and
   * impossible once instances run.
   */
  protected static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  /** The one tool every agentic scope has besides its activities. */
  public static final String COMPLETE_SCOPE_TOOL = "completeScope";

  /**
   * {@code camunda:property} on a child naming the process variables it produces,
   * comma separated — an override for the child whose writes no declaration
   * describes, or to narrow what the agent is shown.
   */
  public static final String RESULT_VARIABLES_PROPERTY = "adHocResultVariables";

  /**
   * {@code camunda:property} on a child marking it as one the agent may only start
   * while nothing else in the scope is running.
   */
  public static final String BLOCKED_WHILE_OTHERS_RUN_PROPERTY = "adHocBlockedWhileOthersRun";

  private AgenticToolCatalogParser() {
  }

  /**
   * Reads the catalogue from the scope's DOM.
   *
   * @param scopeElement the {@code adHocSubProcess} element as the parser saw it
   * @param startableIds what the engine computed as directly startable — the rule
   *     (no incoming sequence flow from within the scope) is not re-derived here
   */
  public static List<AdHocToolDescriptor> parse(Element scopeElement,
      Collection<String> startableIds) {
    List<AdHocToolDescriptor> catalog = new ArrayList<AdHocToolDescriptor>();
    for (Element child : scopeElement.elements()) {
      String id = child.attribute("id");
      if (id == null || !startableIds.contains(id)) {
        continue;
      }
      if (COMPLETE_SCOPE_TOOL.equals(id)) {
        throw new ProcessEngineException("Ad hoc sub process '" + scopeElement.attribute("id")
            + "': the child '" + id + "' collides with the built-in tool of the same name."
            + " Rename the activity.");
      }
      if (!TOOL_NAME.matcher(id).matches()) {
        throw new ProcessEngineException("Ad hoc sub process '" + scopeElement.attribute("id")
            + "': the id '" + id + "' cannot be a tool name. Tool names may carry letters,"
            + " digits, '_' and '-', at most 64 of them. Rename the activity.");
      }
      Map<String, String> properties = childProperties(child);
      catalog.add(new AdHocToolDescriptor(id, child.attribute("name"),
          firstDocumentation(child), resultVariables(child, properties),
          blockedWhileOthersRun(scopeElement, id, properties),
          parameters(scopeElement, id, properties)));
    }
    return Collections.unmodifiableList(catalog);
  }

  /** The child's {@code camunda:property} entries, never null. */
  protected static Map<String, String> childProperties(Element child) {
    Map<String, String> properties = BpmnParseUtil.parseCamundaExtensionProperties(child);
    return (properties == null) ? Collections.<String, String>emptyMap() : properties;
  }

  /**
   * The first non-empty documentation entry, or {@code null}.
   *
   * <p>BPMN allows several documentation elements. Only the first is used, because
   * this text goes into a prompt and concatenating an unbounded number of them is a
   * size problem rather than added information.
   */
  protected static String firstDocumentation(Element child) {
    for (Element documentation : child.elements("documentation")) {
      String text = documentation.getText();
      if (text != null && !text.trim().isEmpty()) {
        return text.trim();
      }
    }
    return null;
  }

  /**
   * The process variables the child declares as its result.
   *
   * <p>Derived rather than demanded: the model usually already says it. The names of
   * {@code camunda:outputParameter} entries, {@code camunda:resultVariable} on the
   * task, and the ids of {@code camunda:formField} entries all name exactly the
   * variables the activity is declared to write. {@link #RESULT_VARIABLES_PROPERTY}
   * overrides all three — for a delegate calling {@code setVariable} directly, or to
   * narrow what the agent is shown.
   */
  protected static List<String> resultVariables(Element child, Map<String, String> properties) {
    String override = properties.get(RESULT_VARIABLES_PROPERTY);
    Set<String> names = new LinkedHashSet<String>();
    if (override != null) {
      for (String part : override.split(",")) {
        addIfPresent(names, part);
      }
      return new ArrayList<String>(names);
    }

    Element inputOutput = BpmnParseUtil.findCamundaExtensionElement(child, "inputOutput");
    if (inputOutput != null) {
      for (Element parameter : inputOutput.elementsNS(
          BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS, "outputParameter")) {
        addIfPresent(names, parameter.attribute("name"));
      }
    }
    addIfPresent(names, child.attributeNS(BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS, "resultVariable"));
    addIfPresent(names,
        child.attributeNS(BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS, "resultVariableName"));
    Element formData = BpmnParseUtil.findCamundaExtensionElement(child, "formData");
    if (formData != null) {
      for (Element field : formData.elementsNS(BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS, "formField")) {
        addIfPresent(names, field.attribute("id"));
      }
    }
    return new ArrayList<String>(names);
  }

  /**
   * Whether the child carries {@link #BLOCKED_WHILE_OTHERS_RUN_PROPERTY}.
   *
   * <p>A value that is neither {@code true} nor {@code false} refuses the deployment.
   * The runtime catalogue this replaces could only warn, because it read the property
   * long after deployment; here the typo is caught where fixing it is cheap, and an
   * activity meant to be guarded is never silently left unguarded.
   */
  protected static boolean blockedWhileOthersRun(Element scopeElement, String childId,
      Map<String, String> properties) {
    String raw = properties.get(BLOCKED_WHILE_OTHERS_RUN_PROPERTY);
    if (raw == null || raw.trim().isEmpty()) {
      return false;
    }
    String value = raw.trim();
    if ("true".equalsIgnoreCase(value)) {
      return true;
    }
    if ("false".equalsIgnoreCase(value)) {
      return false;
    }
    throw new ProcessEngineException("Ad hoc sub process '" + scopeElement.attribute("id")
        + "', child '" + childId + "': " + BLOCKED_WHILE_OTHERS_RUN_PROPERTY + " is '" + value
        + "', but must be 'true' or 'false'.");
  }

  /**
   * The declared input parameters, from {@code adHocToolParameter.<name>} properties
   * with a {@code <type>|<description>} value.
   *
   * <p>Sorted by name rather than map order: {@code camunda:property} entries reach
   * this code as a {@link java.util.HashMap}, and a tool schema whose field order
   * changes between deployments of the same model would be noise in every diff of a
   * prompt log.
   */
  protected static List<AdHocToolDescriptor.Parameter> parameters(Element scopeElement,
      String childId, Map<String, String> properties) {
    Map<String, AdHocToolDescriptor.Parameter> byName =
        new TreeMap<String, AdHocToolDescriptor.Parameter>();
    for (Map.Entry<String, String> property : properties.entrySet()) {
      String key = property.getKey();
      if (key == null || !key.startsWith(AdHocToolDescriptor.TOOL_PARAMETER_PREFIX)) {
        continue;
      }
      String name = key.substring(AdHocToolDescriptor.TOOL_PARAMETER_PREFIX.length()).trim();
      if (!TOOL_NAME.matcher(name).matches()) {
        throw new ProcessEngineException("Ad hoc sub process '" + scopeElement.attribute("id")
            + "', child '" + childId + "': '" + key + "' does not name a parameter."
            + " Expected " + AdHocToolDescriptor.TOOL_PARAMETER_PREFIX
            + "<name> with letters, digits, '_' or '-'.");
      }
      String raw = (property.getValue() == null) ? "" : property.getValue().trim();
      int separator = raw.indexOf('|');
      String type = ((separator < 0) ? raw : raw.substring(0, separator)).trim().toLowerCase();
      String description = (separator < 0) ? "" : raw.substring(separator + 1).trim();
      if (!AdHocToolDescriptor.PARAMETER_TYPES.contains(type)) {
        throw new ProcessEngineException("Ad hoc sub process '" + scopeElement.attribute("id")
            + "', child '" + childId + "', parameter '" + name + "': the type '" + type
            + "' is not supported. Use one of " + AdHocToolDescriptor.PARAMETER_TYPES
            + ", as '<type>|<description>'.");
      }
      byName.put(name, new AdHocToolDescriptor.Parameter(name, type, description));
    }
    return new ArrayList<AdHocToolDescriptor.Parameter>(byName.values());
  }

  protected static void addIfPresent(Set<String> names, String candidate) {
    if (candidate != null && !candidate.trim().isEmpty()) {
      names.add(candidate.trim());
    }
  }
}
