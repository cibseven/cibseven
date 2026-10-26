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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.cibseven.bpm.engine.BpmnParseException;
import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParse;
import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParseUtil;
import org.cibseven.bpm.engine.impl.scripting.engine.JuelScriptEngineFactory;
import org.cibseven.bpm.engine.impl.scripting.engine.ScriptingEngines;
import org.cibseven.bpm.engine.impl.util.ScriptUtil;
import org.cibseven.bpm.engine.impl.util.xml.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final Logger LOG = LoggerFactory.getLogger(AgenticToolCatalogParser.class);

  /**
   * What a tool may be called. The activity id becomes the function name the model
   * sees, and the OpenAI contract allows no more than these characters. An id outside
   * this alphabet is refused at deployment; renaming an element is cheap there and
   * impossible once instances run.
   */
  protected static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  /** A simple expression: one variable, or a path from it, such as ${kunde.adresse.ort}. */
  protected static final Pattern SIMPLE_EXPRESSION = Pattern.compile(
      "^\\$\\{\\s*([A-Za-z_][A-Za-z0-9_]*)(\\s*\\.\\s*[A-Za-z_][A-Za-z0-9_]*)*\\s*\\}$");

  /** What a multi-instance body sets on each instance, or on itself, for its loop. */
  protected static final Set<String> LOOP_VARIABLES = new HashSet<String>(Arrays.asList(
      "loopCounter", "nrOfInstances", "nrOfActiveInstances", "nrOfCompletedInstances"));

  /** Names an expression gives itself rather than reading from the process. */
  protected static final Set<String> EL_NAMES = new HashSet<String>(Arrays.asList(
      "execution", "task", "connector", "caseExecution", "variableScope", "authenticatedUserId",
      "null", "true", "false", "empty", "not", "and", "or", "div", "mod", "eq", "ne", "lt", "gt",
      "le", "ge", "instanceof"));

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

  /**
   * The variable {@code toolCall} itself: not part of a longer name, and no property of something
   * else, as in {@code order.toolCall}.
   */
  protected static final String TOOL_CALL_ITSELF =
      "(?<!\\p{javaJavaIdentifierPart})(?<!\\.\\s{0,16})" + AdHocToolDescriptor.TOOL_CALL_VARIABLE;

  /**
   * A read of one argument in an expression (CIB7-2136): {@code toolCall.<name>}, the name in
   * group 1, or {@code toolCall['<name>']}, the name in group 2 or 3. A method of the map, such
   * as {@code toolCall.containsKey('x')}, reads no argument of its name; a key computed in the
   * brackets cannot be told at deployment. The name is the whole identifier, as EL reads it, so
   * {@code toolCall.betragé} is no read of {@code betrag}.
   */
  protected static final Pattern TOOL_CALL_READ = Pattern.compile(TOOL_CALL_ITSELF
      + "\\s*(?:\\.\\s*(\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*+)(?!\\s*\\()"
      + "|\\[\\s*(?:'([^'\\\\]*)'|\"([^\"\\\\]*)\")\\s*\\])");

  /** Any mention of the argument map, in a text that is no expression: a script, say. */
  protected static final Pattern TOOL_CALL_WORD = Pattern.compile(TOOL_CALL_ITSELF
      + "(?!\\p{javaJavaIdentifierPart})");

  /**
   * Elements whose text never reaches an expression; they may mention the function freely. A
   * delegate field's {@code camunda:string} is among them: the engine hands it on as a fixed value.
   */
  protected static final Set<String> NO_CODE = Collections.unmodifiableSet(new HashSet<String>(
      Arrays.asList("documentation", "textAnnotation", "text", "properties", "string")));

  /**
   * Attributes whose value never reaches an expression: a delegate field's {@code stringValue},
   * which the engine hands on as a fixed value, as the field's {@code camunda:string}.
   */
  protected static final Set<String> NO_CODE_ATTRIBUTES = Collections.singleton("stringValue");

  private AgenticToolCatalogParser() {
  }

  /**
   * One parse of a process, as the agent's checks see it (CIB7-2136, CIB7-2367): which process,
   * and whether it is deployed now or only parsed again, from a deployment made earlier, after a
   * restart, a cache eviction or on another node.
   *
   * <p>The checks refuse a model when it is deployed. A model parsed again that they would
   * refuse was deployed when nothing checked it: while the AI agent was off, or before the check
   * existed. Refused now, it would stop every instance of it, and every command that needs the
   * definition, even the ones that would end them. So then a check only logs what it would refuse,
   * and the model loads without the part it refuses: a scope no agent can run stays the plain ad
   * hoc scope it was deployed as, and a broken tool, parameter or setting is left out. The engine
   * treats its own checks that depend on the configuration the same way, as the history time to
   * live.
   *
   * <p>The rule for a check added after a release: on a parse again it leaves out the part it
   * refuses, a tool, a parameter or a setting, never the agent. "Loads as it was deployed, without
   * the agent" holds only for a model deployed while the agent was off. One deployed with the agent
   * on, before the check existed, ran with its agent, and its instances wait for turns that only
   * the agent gives; without it they would wait for ever.
   */
  public static final class Parsing {

    /** A deployment, with no process named: the checks refuse. */
    public static final Parsing DEPLOYMENT = new Parsing(null, true);

    protected final String processKey;
    protected final boolean newDeployment;
    /** Which definition is parsed again, in words for the log, read only when it logs. */
    protected final Supplier<String> definition;

    public Parsing(String processKey, boolean newDeployment) {
      this(processKey, newDeployment, null);
    }

    /** @param definition which definition is parsed again, in words for the log, or null */
    public Parsing(String processKey, boolean newDeployment, Supplier<String> definition) {
      this.processKey = processKey;
      this.newDeployment = newDeployment;
      this.definition = definition;
    }

    /** Runs the check: it refuses a deployment, and only logs on a parse again. */
    public void check(Runnable check) {
      check(check, "it loads as it is");
    }

    /**
     * Runs the check: it refuses a deployment; on a parse again it logs what it would refuse.
     *
     * @param instead what the model loads with instead, named in the log
     * @return whether the check passed, so the caller leaves out what it refused
     */
    public boolean check(Runnable check, String instead) {
      try {
        check.run();
        return true;
      } catch (BpmnParseException refusal) {
        refuse(refusal, instead);
        return false;
      }
    }

    /**
     * Refuses a deployment with {@code refusal}; on a parse again only logs it, with what the
     * model loads with instead, and returns.
     */
    public void refuse(BpmnParseException refusal, String instead) {
      if (newDeployment) {
        throw refusal;
      }
      LOG.warn("Process '{}'{} is parsed again, not deployed, and a deployment of it would now be"
          + " refused: {} It was deployed when nothing checked this, so {}."
          + " Deploy a corrected version.", processKey,
          (definition == null) ? "" : " (" + definition.get() + ")", refusal.getMessage(),
          instead);
    }
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
    return parse(scopeElement, startableIds, Parsing.DEPLOYMENT);
  }

  /** @param parsing which process the scope is in, and whether it is deployed now */
  public static List<AdHocToolDescriptor> parse(final Element scopeElement,
      final Collection<String> startableIds, Parsing parsing) {
    List<AdHocToolDescriptor> catalog = new ArrayList<AdHocToolDescriptor>();
    final Map<String, List<AdHocToolDescriptor.Parameter>> declared =
        new HashMap<String, List<AdHocToolDescriptor.Parameter>>();
    for (Element child : scopeElement.elements()) {
      String id = child.attribute("id");
      if (id == null || !startableIds.contains(id)) {
        continue;
      }
      // On a parse again a tool these would refuse is left out, so the agent never starts it
      // (CIB7-2367): one whose guard cannot be read would run unguarded, one whose parameter
      // property cannot be read would run without the value the model was never asked for.
      String leftOut = "the tool '" + id + "' is left out of the agent's catalogue";
      if (COMPLETE_SCOPE_TOOL.equals(id)) {
        parsing.refuse(new BpmnParseException("Ad hoc sub process '"
            + scopeElement.attribute("id") + "': the child '" + id + "' collides with the"
            + " built-in tool of the same name. Rename the activity.", child), leftOut);
        continue;
      }
      if (!TOOL_NAME.matcher(id).matches()) {
        parsing.refuse(new BpmnParseException("Ad hoc sub process '"
            + scopeElement.attribute("id") + "': the id '" + id + "' cannot be a tool name. Tool"
            + " names may carry letters, digits, '_' and '-', at most 64 of them. Rename the"
            + " activity.", child), leftOut);
        continue;
      }
      Map<String, String> properties = childProperties(child);
      boolean blockedWhileOthersRun;
      List<AdHocToolDescriptor.Parameter> parameters;
      try {
        blockedWhileOthersRun = blockedWhileOthersRun(scopeElement, child, properties);
        parameters = parameters(scopeElement, child, parsing);
      } catch (BpmnParseException refusal) {
        parsing.refuse(refusal, leftOut);
        continue;
      }
      declared.put(id, parameters);
      catalog.add(new AdHocToolDescriptor(id, child.attribute("name"),
          firstDocumentation(child), readVariables(child),
          resultVariables(parsing, scopeElement, child, properties),
          blockedWhileOthersRun, parameters));
    }
    parsing.check(() -> refuseUnreadCalls(scopeElement, startableIds));
    parsing.check(() -> refuseUndeclaredReadsInFollowers(scopeElement, startableIds, declared));
    return Collections.unmodifiableList(catalog);
  }

  /**
   * Refuses a misspelt read of toolCall further along a tool's chain (CIB7-2136). An activity an
   * inner flow reaches from a tool runs on the tool's performance, where the call's toolCall is
   * visible; so do the flow's condition and the path of a boundary event on a tool or on such an
   * activity. A flow into a throwing link event goes on from the catching one of its name, as the
   * engine links them. A read there has to name a parameter that a tool whose flow reaches it
   * declares in a mapping. No call's arguments arrive anywhere else in the scope, so a read of one
   * there would read null, and is refused as well.
   *
   * @param declared the parameters of each tool, by the tool's id
   */
  protected static void refuseUndeclaredReadsInFollowers(Element scopeElement,
      Collection<String> startableIds, Map<String, List<AdHocToolDescriptor.Parameter>> declared) {
    Map<String, List<String>> next = new HashMap<String, List<String>>();
    Map<String, String> linkThrows = new HashMap<String, String>();
    Map<String, String> linkCatches = new HashMap<String, String>();
    for (Element child : scopeElement.elements()) {
      String id = child.attribute("id");
      String tag = child.getTagName();
      Element link = child.element(BpmnParse.LINK_EVENT_DEFINITION);
      if ("sequenceFlow".equals(tag)) {
        follows(next, child.attribute("sourceRef"), id);
        follows(next, id, child.attribute("targetRef"));
      } else if ("boundaryEvent".equals(tag)) {
        follows(next, child.attribute("attachedToRef"), id);
      } else if (link != null && "intermediateThrowEvent".equals(tag)) {
        linkThrows.put(id, link.attribute("name"));
      } else if (link != null && "intermediateCatchEvent".equals(tag)) {
        linkCatches.put(link.attribute("name"), id);
      }
    }
    for (Map.Entry<String, String> linkThrow : linkThrows.entrySet()) {
      follows(next, linkThrow.getKey(), linkCatches.get(linkThrow.getValue()));
    }
    Map<String, Set<String>> reachedFrom = new HashMap<String, Set<String>>();
    for (String tool : startableIds) {
      Deque<String> todo = new ArrayDeque<String>(followers(next, tool));
      Set<String> seen = new HashSet<String>();
      while (!todo.isEmpty()) {
        String id = todo.pop();
        if (startableIds.contains(id) || !seen.add(id)) {
          continue;
        }
        Set<String> tools = reachedFrom.get(id);
        if (tools == null) {
          tools = new TreeSet<String>();
          reachedFrom.put(id, tools);
        }
        tools.add(tool);
        todo.addAll(followers(next, id));
      }
    }
    for (Element child : scopeElement.elements()) {
      String id = child.attribute("id");
      if (id == null || startableIds.contains(id) || NO_CODE.contains(child.getTagName())) {
        continue;
      }
      Set<String> tools = reachedFrom.get(id);
      // Reached from a tool left out on a parse again (CIB7-2367): its declarations are gone with
      // it, and every read here would be named undeclared only for that.
      if (tools != null && !declared.keySet().containsAll(tools)) {
        continue;
      }
      Map<String, AdHocToolDescriptor.Parameter> byName =
          new HashMap<String, AdHocToolDescriptor.Parameter>();
      String declaredBy = null;
      if (tools != null) {
        for (String tool : tools) {
          List<AdHocToolDescriptor.Parameter> parameters = declared.get(tool);
          for (AdHocToolDescriptor.Parameter parameter : (parameters == null)
              ? Collections.<AdHocToolDescriptor.Parameter>emptyList() : parameters) {
            byName.put(parameter.getName(), parameter);
          }
        }
        declaredBy = quoted(tools)
            + ((tools.size() == 1) ? ", whose flow reaches it," : ", whose flows reach it,");
      }
      refuseUndeclaredReadsIn(scopeElement, child, child, byName, declaredBy);
    }
  }

  protected static void follows(Map<String, List<String>> next, String from, String to) {
    if (from == null || to == null) {
      return;
    }
    List<String> targets = next.get(from);
    if (targets == null) {
      targets = new ArrayList<String>();
      next.put(from, targets);
    }
    targets.add(to);
  }

  protected static List<String> followers(Map<String, List<String>> next, String from) {
    List<String> targets = next.get(from);
    return (targets == null) ? Collections.<String>emptyList() : targets;
  }

  /** The ids in quotes, as a refusal names them: 'a', 'a' or 'b', 'a', 'b' or 'c'. */
  protected static String quoted(Collection<String> ids) {
    StringBuilder text = new StringBuilder();
    int i = 0;
    for (String id : ids) {
      if (i > 0) {
        text.append((i == ids.size() - 1) ? " or " : ", ");
      }
      text.append('\'').append(id).append('\'');
      i++;
    }
    return text.toString();
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
   * <p>Derived rather than demanded: the model usually already says it. A child with output
   * parameters, its own or those of a {@code camunda:connector} on it (an http call, an AI agent
   * task, an agent in a nested ad hoc scope), declares exactly those: its outputs are what it
   * hands out (CIB7-2239). A child without them declares {@code camunda:resultVariable} on the
   * task and the ids of the {@code camunda:formField} entries a person fills in; a read-only
   * field only shows a value and is no result. {@link #RESULT_VARIABLES_PROPERTY} overrides them
   * all — for a delegate calling {@code setVariable} directly, or to narrow what the agent is
   * shown.
   *
   * <p>A result variable or a form field a person fills in that the outputs do not hand out is
   * then not shown to the agent, though the activity still writes it; a warning at deployment
   * names it, so that the modeller hands it out or lists it on purpose.
   */
  protected static List<String> resultVariables(Parsing parsing, Element scopeElement,
      Element child, Map<String, String> properties) {
    String override = properties.get(RESULT_VARIABLES_PROPERTY);
    Set<String> names = new LinkedHashSet<String>();
    if (override != null) {
      for (String part : override.split(",")) {
        addIfPresent(names, part);
      }
      return new ArrayList<String>(names);
    }

    addOutputParameters(names, BpmnParseUtil.findCamundaExtensionElement(child, "inputOutput"));
    Element connector = BpmnParseUtil.findCamundaExtensionElement(child, "connector");
    if (connector != null) {
      addOutputParameters(names,
          connector.elementNS(BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS, "inputOutput"));
    }
    Set<String> written = new LinkedHashSet<String>();
    addIfPresent(written,
        child.attributeNS(BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS, "resultVariable"));
    addIfPresent(written,
        child.attributeNS(BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS, "resultVariableName"));
    Element formData = BpmnParseUtil.findCamundaExtensionElement(child, "formData");
    if (formData != null) {
      for (Element field : formData.elementsNS(BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS, "formField")) {
        if (!readOnly(field)) {
          addIfPresent(written, field.attribute("id"));
        }
      }
    }
    if (names.isEmpty()) {
      return new ArrayList<String>(written);
    }
    written.removeAll(names);
    // Once, when the model is deployed: a parse again, on every start and node, would repeat it.
    if (!written.isEmpty() && parsing.newDeployment) {
      LOG.warn("Process '{}', ad hoc sub process '{}', tool '{}' (line {}): its output parameters"
          + " {} are what the agent is told when it ends, so {} that the tool also writes {} not"
          + " shown to the agent. Hand {} out through an output parameter, or list what the agent"
          + " should see in {}.", parsing.processKey, scopeElement.attribute("id"),
          child.attribute("id"),
          child.getLine(), names, written,
          written.size() == 1 ? "is" : "are", written.size() == 1 ? "it" : "them",
          RESULT_VARIABLES_PROPERTY);
    }
    return new ArrayList<String>(names);
  }

  /**
   * The process variables the child reads, as far as its model says (CIB7-2169): the variable a
   * simple expression of its input mapping names, or the root of a path, also of a
   * {@code camunda:connector} on it; and the sources of a call activity's {@code camunda:in}.
   * What a script, a delegate or a longer expression reads cannot be told from the model and is
   * not listed. Left out are the call's own {@code toolCall}, and the names an expression gives
   * itself, such as {@code execution}. Left out too is a name the activity's own
   * input parameter creates, where the connector's inputs or {@code camunda:in} read it: they read
   * the activity's execution, where that parameter is local. A multi-instance tool reads its
   * collection, not its element variable and loop counters ({@link #multiInstanceReads}).
   */
  protected static List<String> readVariables(Element child) {
    Set<String> names = new LinkedHashSet<String>();
    Element inputOutput = BpmnParseUtil.findCamundaExtensionElement(child, "inputOutput");
    addSimpleInputs(names, inputOutput);
    Set<String> inside = new LinkedHashSet<String>();
    Element connector = BpmnParseUtil.findCamundaExtensionElement(child, "connector");
    if (connector != null) {
      addSimpleInputs(inside,
          connector.elementNS(BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS, "inputOutput"));
    }
    Element extensions = child.element("extensionElements");
    if (extensions != null) {
      for (Element in : extensions.elementsNS(BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS, "in")) {
        addIfPresent(inside, in.attribute("source"));
        addSimple(inside, in.attribute("sourceExpression"));
      }
    }
    inside.removeAll(inputParameterNames(inputOutput));
    names.addAll(inside);
    Element loop = child.element("multiInstanceLoopCharacteristics");
    if (loop != null) {
      names = multiInstanceReads(loop, names);
    }
    // The map the call's arguments arrive in is the call's own, set on its execution by the
    // activation: toolCall.<name> reads what the model passed, not process data (CIB7-2136). A
    // parameter arrives only there, so a read of its name reads the process variable, which is
    // process data the tool reads.
    names.remove(AdHocToolDescriptor.TOOL_CALL_VARIABLE);
    names.removeAll(EL_NAMES);
    return new ArrayList<String>(names);
  }

  /**
   * What a multi-instance tool reads: the collection it runs over, or what its number of
   * instances names, first; then what its mappings read, but not its element variable and the
   * loop's own counters, which each instance has without the process.
   */
  protected static Set<String> multiInstanceReads(Element loop, Set<String> mapped) {
    Set<String> names = new LinkedHashSet<String>();
    String collection = loop.attributeNS(BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS, "collection");
    if (collection != null && collection.contains("{")) {
      addSimple(names, collection);
    } else {
      addIfPresent(names, collection);
    }
    Element cardinality = loop.element("loopCardinality");
    if (cardinality != null) {
      addSimple(names, cardinality.getText());
    }
    Set<String> perInstance = new HashSet<String>(LOOP_VARIABLES);
    addIfPresent(perInstance,
        loop.attributeNS(BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS, "elementVariable"));
    for (String name : mapped) {
      if (!perInstance.contains(name)) {
        names.add(name);
      }
    }
    return names;
  }

  /** The names the input parameters of {@code inputOutput} create, none without one. */
  protected static Set<String> inputParameterNames(Element inputOutput) {
    Set<String> names = new LinkedHashSet<String>();
    if (inputOutput != null) {
      for (Element parameter : inputOutput.elementsNS(
          BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS, "inputParameter")) {
        addIfPresent(names, parameter.attribute("name"));
      }
    }
    return names;
  }

  protected static void addSimpleInputs(Set<String> names, Element inputOutput) {
    if (inputOutput == null) {
      return;
    }
    for (Element parameter : inputOutput.elementsNS(
        BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS, "inputParameter")) {
      if (parameter.elements().isEmpty()) {
        addSimple(names, parameter.getText());
      }
    }
  }

  protected static void addSimple(Set<String> names, String expression) {
    if (expression == null) {
      return;
    }
    Matcher simple = SIMPLE_EXPRESSION.matcher(expression.trim());
    if (simple.matches()) {
      names.add(simple.group(1));
    }
  }

  /** Whether a form field carries the {@code readonly} constraint: it shows, nobody fills it in. */
  protected static boolean readOnly(Element field) {
    Element validation = field.elementNS(BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS, "validation");
    if (validation == null) {
      return false;
    }
    for (Element constraint : validation.elementsNS(
        BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS, "constraint")) {
      if ("readonly".equals(constraint.attribute("name"))) {
        return true;
      }
    }
    return false;
  }

  protected static void addOutputParameters(Set<String> names, Element inputOutput) {
    if (inputOutput == null) {
      return;
    }
    for (Element parameter : inputOutput.elementsNS(
        BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS, "outputParameter")) {
      addIfPresent(names, parameter.attribute("name"));
    }
  }

  /**
   * Whether the child carries {@link #BLOCKED_WHILE_OTHERS_RUN_PROPERTY}.
   *
   * <p>A value that is neither {@code true} nor {@code false} refuses the deployment.
   * The runtime catalogue this replaces could only warn, because it read the property
   * long after deployment; here the typo is caught where fixing it is cheap, and an
   * activity meant to be guarded is never silently left unguarded.
   */
  protected static boolean blockedWhileOthersRun(Element scopeElement, Element child,
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
    throw new BpmnParseException("Ad hoc sub process '" + scopeElement.attribute("id")
        + "', child '" + child.attribute("id") + "': " + BLOCKED_WHILE_OTHERS_RUN_PROPERTY
        + " is '" + value + "', but must be 'true' or 'false'.", child);
  }

  /**
   * The declared input parameters: the {@code toolParameter(toolCall.<name>, ...)} calls in the
   * child's input mappings (CIB7-2136), and nothing else.
   *
   * <p>Sorted by name, as the schema has always been: the same model keeps its schema, and
   * moving an input mapping does not reorder it, so a prompt log's diff shows no noise.
   */
  protected static List<AdHocToolDescriptor.Parameter> parameters(final Element scopeElement,
      final Element child, Parsing parsing) {
    final Map<String, AdHocToolDescriptor.Parameter> byName =
        new TreeMap<String, AdHocToolDescriptor.Parameter>();
    List<String> texts = inputTexts(child);
    final Set<String> typed = new HashSet<String>();
    // On a parse again a declaration this would refuse is left out, with what follows it in the
    // same input; the others stay in the catalogue.
    for (final String text : texts) {
      parsing.check(() -> addMappingDeclarations(scopeElement, child, text, byName, typed));
    }
    for (final String text : texts) {
      parsing.check(() -> refuseUndeclaredReads(scopeElement, child, text, byName));
    }
    parsing.check(() -> refuseUndeclaredReadsInTool(scopeElement, child, child, byName));
    return new ArrayList<AdHocToolDescriptor.Parameter>(byName.values());
  }

  /**
   * Refuses a read of {@code toolCall.<name>} anywhere in the tool that names no parameter it
   * declares in a mapping (CIB7-2136): in its input mappings, where the declarations are, and as
   * well in its output parameters, in the activities inside a sub process tool, in a call
   * activity's mappings and in its conditions. A misspelt name would read null in each of them.
   * Script code is no expression and is not read, but a script in JUEL is, and so are a script's
   * attributes, a dynamic resource among them ({@link #reading}); the tools of an agent inside the
   * tool are that agent's, and the connector inputs of an agent are judged by
   * {@link #refuseInAgentConnector}.
   * A text that calls the function is left to the input mappings' own check above, or, anywhere
   * else, to {@link #refuseUnreadCalls}, which says the call declares nothing there.
   */
  protected static void refuseUndeclaredReadsInTool(Element scopeElement, Element tool,
      Element element, Map<String, AdHocToolDescriptor.Parameter> byName) {
    refuseUndeclaredReadsIn(scopeElement, tool, element, byName, "this tool");
  }

  /**
   * As {@link #refuseUndeclaredReadsInTool}, for the child of the scope that holds the element.
   *
   * @param declaredBy who declares the parameters of {@code byName}, as a refusal names it; null
   *     where no call's arguments arrive
   */
  protected static void refuseUndeclaredReadsIn(Element scopeElement, Element tool,
      Element element, Map<String, AdHocToolDescriptor.Parameter> byName, String declaredBy) {
    String tag = element.getTagName();
    if (NO_CODE.contains(tag)) {
      return;
    }
    for (String key : codeAttributes(element)) {
      String value = element.attribute(key);
      if (!callsInCode(value)) {
        refuseUndeclaredReads(scopeElement, tool, value, byName, declaredBy);
      }
    }
    Reading reading = reading(element);
    String text = textOf(element);
    if ((reading == Reading.EXPRESSION || reading == Reading.JUEL_SCRIPT) && !callsInCode(text)) {
      refuseUndeclaredReads(scopeElement, tool, text, byName, declaredBy);
    }
    boolean agent = isAgent(element);
    for (Element nested : element.elements()) {
      if (isTaskScript(element, nested)) {
        continue;
      }
      if (agent && "extensionElements".equals(nested.getTagName())) {
        for (Element extension : nested.elements()) {
          if ("connector".equals(extension.getTagName())) {
            // An agent anywhere in or along the tool, not only the tool itself: its connector's
            // inputs are read on its own turns, so the placement rule holds for it too.
            refuseInAgentConnectorOf(scopeElement, tool, element, extension);
          } else {
            refuseUndeclaredReadsIn(scopeElement, tool, extension, byName, declaredBy);
          }
        }
        continue;
      }
      if (agent && nested.attribute("id") != null
          && !"multiInstanceLoopCharacteristics".equals(nested.getTagName())) {
        continue;
      }
      refuseUndeclaredReadsIn(scopeElement, tool, nested, byName, declaredBy);
    }
  }

  /**
   * The parameters an input mapping declares with {@code toolParameter(toolCall.<name>,
   * '<description>', '<type>')} (CIB7-2136); the description and the type may be left out, the
   * type then being string. Only a call inside an expression counts, and only that form is read,
   * with literals, so the schema is known at deployment; any other call of the function refuses
   * the deployment, with the reason. A name may be used in several calls: a call without a type
   * takes the type another one gives, two given types must agree, and so must two given
   * descriptions: a call without one takes the one another gives.
   *
   * <p>A parameter is required (CIB7-2135): it is listed in the schema's {@code required} array,
   * and a call that leaves it out is refused before the activity starts. A trailing
   * {@link #OPTIONAL_MARKER} on the type marks it as one the model may leave out. A call without a
   * type takes the marker with the type another call gives; two calls that give a type must agree
   * on the marker too.
   *
   * @param typed the names whose type a call of this tool gave, so far
   */
  protected static void addMappingDeclarations(Element scopeElement, Element child, String text,
      Map<String, AdHocToolDescriptor.Parameter> byName, Set<String> typed) {
    String where = where(scopeElement, child);
    boolean[] code = code(text);
    Set<Integer> declared = new HashSet<Integer>();
    Matcher declaration = AgenticToolParameterFunction.DECLARATION.matcher(text);
    while (declaration.find()) {
      if (!code[declaration.start()]) {
        continue;
      }
      declared.add(declaration.start());
      String name = declaration.group(1);
      String description = AgenticToolParameterFunction.unescape(
          firstPresent(declaration.group(2), declaration.group(3))).trim();
      boolean typeGiven = declaration.group(4) != null || declaration.group(5) != null;
      String declaredType = AgenticToolParameterFunction.unescape(
          firstPresent(declaration.group(4), declaration.group(5))).trim();
      String markedType = declaredType.toLowerCase(Locale.ROOT);
      boolean required = !isMarked(markedType);
      String type = unmarked(markedType);
      if (AdHocToolDescriptor.TOOL_CALL_VARIABLE.equals(name)) {
        throw reservedName(where, child);
      }
      if (!TOOL_NAME.matcher(name).matches()) {
        throw new BpmnParseException(where + ": the parameter name '" + name + "' is longer than"
            + " 64 characters.", child);
      }
      // Every marker is stripped first: 'integer?', or 'integer??', as the only literal is a type
      // in the wrong place as much as 'integer' is, and would otherwise pass as a description.
      String descriptionWord = description.toLowerCase(Locale.ROOT);
      String descriptionType = descriptionWord;
      while (isMarked(descriptionType)) {
        descriptionType = unmarked(descriptionType);
      }
      if (!typeGiven && AdHocToolDescriptor.PARAMETER_TYPES.contains(descriptionType)) {
        throw new BpmnParseException(where + ", parameter '" + name + "': the second argument of "
            + AgenticToolParameterFunction.NAME + " is the description, and '" + description
            + "' reads like a type. To give the type alone, write "
            + AgenticToolParameterFunction.NAME + "(" + AdHocToolDescriptor.TOOL_CALL_VARIABLE
            + "." + name + ", '', '" + marked(descriptionType, !isMarked(descriptionWord))
            + "').", child);
      }
      if (typeGiven && !AdHocToolDescriptor.PARAMETER_TYPES.contains(type)) {
        throw new BpmnParseException(where + ", parameter '" + name + "': the type '"
            + declaredType + "' is not supported. Use one of "
            + AdHocToolDescriptor.PARAMETER_TYPES + "." + typeHint(type), child);
      }
      AdHocToolDescriptor.Parameter known = byName.get(name);
      if (known == null) {
        byName.put(name, new AdHocToolDescriptor.Parameter(name,
            typeGiven ? type : AgenticToolParameterFunction.DEFAULT_TYPE, description, required));
        if (typeGiven) {
          typed.add(name);
        }
        continue;
      }
      // Nothing is recorded until every check passed: on a parse again a refused declaration is
      // only logged, and must leave the name as the declarations before it made it.
      boolean firstType = typeGiven && !typed.contains(name);
      String mergedType = firstType ? type : known.getType();
      boolean mergedRequired = firstType ? required : known.isRequired();
      if (typeGiven && !firstType && !known.getType().equals(type)) {
        throw new BpmnParseException(where + ", parameter '" + name + "' is declared with two"
            + " types, '" + marked(known.getType(), known.isRequired()) + "' and '"
            + marked(type, required) + "'. Give it one type. A call"
            + " without a type takes the type another call gives, and ${"
            + AdHocToolDescriptor.TOOL_CALL_VARIABLE + "." + name + "} reads the value without"
            + " declaring it again.", child);
      }
      if (typeGiven && !firstType && known.isRequired() != required) {
        throw new BpmnParseException(where + ", parameter '" + name + "' is declared required"
            + " in one call and optional in another, as '" + type + "' and '"
            + marked(type, false) + "'. Mark it the same way in every call that gives a type:"
            + " with a trailing '" + OPTIONAL_MARKER + "' for an optional parameter, without one"
            + " for a required parameter.", child);
      }
      if (!description.isEmpty() && !known.getDescription().isEmpty()
          && !description.equals(known.getDescription())) {
        throw new BpmnParseException(where + ", parameter '" + name + "' is declared with two"
            + " descriptions, '" + known.getDescription() + "' and '" + description + "'. Give it"
            + " one description: a call without one takes the description another call gives.",
            child);
      }
      String mergedDescription = known.getDescription().isEmpty()
          ? description : known.getDescription();
      if (firstType) {
        typed.add(name);
      }
      byName.put(name, new AdHocToolDescriptor.Parameter(name, mergedType, mergedDescription,
          mergedRequired));
    }
    Matcher any = AgenticToolParameterFunction.ANY_CALL.matcher(text);
    while (any.find()) {
      if (code[any.start()] && !declared.contains(any.start())) {
        throw new BpmnParseException(where + ": " + diagnose(text, any.start())
            + " Write it as " + AgenticToolParameterFunction.NAME + "("
            + AdHocToolDescriptor.TOOL_CALL_VARIABLE + ".<name>, '<description>', '<type>'):"
            + " it is read at deployment, so the description and the type are literals; both may"
            + " be left out, the type then being string.", child);
      }
    }
  }

  /**
   * Refuses a read of {@code toolCall.<name>} or {@code toolCall['<name>']} in the text that names
   * no parameter the tool declares in a mapping (CIB7-2136): a misspelt name would read null.
   */
  protected static void refuseUndeclaredReads(Element scopeElement, Element child, String text,
      Map<String, AdHocToolDescriptor.Parameter> byName) {
    refuseUndeclaredReads(scopeElement, child, text, byName, "this tool");
  }

  /** @param declaredBy as for {@link #refuseUndeclaredReadsIn} */
  protected static void refuseUndeclaredReads(Element scopeElement, Element child, String text,
      Map<String, AdHocToolDescriptor.Parameter> byName, String declaredBy) {
    if (text == null || text.indexOf(AdHocToolDescriptor.TOOL_CALL_VARIABLE) < 0) {
      return;
    }
    boolean[] code = code(text);
    Matcher read = TOOL_CALL_READ.matcher(text);
    while (read.find()) {
      if (!code[read.start()]) {
        continue;
      }
      String name = firstPresent(read.group(1), firstPresent(read.group(2), read.group(3)));
      if (byName.containsKey(name)) {
        continue;
      }
      String written = (read.group(1) != null) ? "." + name : "['" + name + "']";
      if (declaredBy == null) {
        throw new BpmnParseException(where(scopeElement, child) + ": "
            + AdHocToolDescriptor.TOOL_CALL_VARIABLE + written + " is read, but no tool's flow"
            + " reaches '" + child.attribute("id") + "': no call's arguments arrive there, and it"
            + " would read null.", child);
      }
      throw new BpmnParseException(where(scopeElement, child) + ": "
          + AdHocToolDescriptor.TOOL_CALL_VARIABLE + written + " is read, but no "
          + AgenticToolParameterFunction.NAME + "(" + AdHocToolDescriptor.TOOL_CALL_VARIABLE + "."
          + name + ", ...) of " + declaredBy + " declares it. Declare it, or correct the name.",
          child);
    }
  }

  /**
   * The parameter type for a type word of the engine or of Java, which a modeller writes from
   * habit, as for a form field: the schema knows only {@link AdHocToolDescriptor#PARAMETER_TYPES}.
   */
  protected static String typeHint(String type) {
    if (Arrays.asList("long", "int", "short").contains(type)) {
      return " A whole number is 'integer'; it arrives as a Long.";
    }
    if (Arrays.asList("double", "float", "decimal").contains(type)) {
      return " A number with a fraction is 'number'; it arrives as a Double.";
    }
    return "";
  }

  /**
   * Trailing marker on a parameter's type naming it optional, e.g. {@code 'integer?'}; without
   * it a parameter is required. The declaration syntax lives here and only here: everything
   * downstream reads {@link AdHocToolDescriptor.Parameter#isRequired()} and never sees how it
   * was written.
   */
  protected static final char OPTIONAL_MARKER = '?';

  /** The type as a declaration writes it, with the marker of an optional parameter. */
  protected static String marked(String type, boolean required) {
    return required ? type : type + OPTIONAL_MARKER;
  }

  /** Whether {@code type} ends in {@link #OPTIONAL_MARKER}. */
  protected static boolean isMarked(String type) {
    return type.endsWith(String.valueOf(OPTIONAL_MARKER));
  }

  /** {@code type} without one trailing {@link #OPTIONAL_MARKER} and the blanks before it. */
  protected static String unmarked(String type) {
    return isMarked(type) ? type.substring(0, type.length() - 1).trim() : type;
  }

  /**
   * Why a call of the function does not have the one form the catalogue reads, with the call
   * quoted.
   */
  protected static String diagnose(String text, int start) {
    int open = text.indexOf('(', start);
    List<String> arguments = new ArrayList<String>();
    StringBuilder current = new StringBuilder();
    int depth = 0;
    char quote = 0;
    int end = -1;
    for (int i = open + 1; i < text.length() && end < 0; i++) {
      char c = text.charAt(i);
      current.append(c);
      if (quote != 0) {
        if (c == '\\' && i + 1 < text.length()) {
          current.append(text.charAt(++i));
        } else if (c == quote) {
          quote = 0;
        }
      } else if (c == '\'' || c == '"') {
        quote = c;
      } else if (c == '(' || c == '[') {
        depth++;
      } else if ((c == ')' || c == ']') && depth > 0) {
        depth--;
      } else if (c == ')') {
        current.setLength(current.length() - 1);
        arguments.add(current.toString().trim());
        end = i;
      } else if (c == ',' && depth == 0) {
        current.setLength(current.length() - 1);
        arguments.add(current.toString().trim());
        current.setLength(0);
      }
    }
    String call = (end < 0) ? text.substring(start) : text.substring(start, end + 1);
    call = call.replaceAll("\\s+", " ");
    if (call.length() > 120) {
      call = call.substring(0, 117) + "...";
    }
    String reason = "is not in the form read at deployment.";
    // An unclosed call, or a quote that ends a literal early, never gets here: the engine refuses
    // such an expression with its own syntax error before the catalogue reads it.
    if (end >= 0 && arguments.size() > 3) {
      reason = "has more than three arguments: the value, a description and a type.";
    } else if (end >= 0) {
      String first = arguments.isEmpty() ? "" : arguments.get(0);
      Matcher read = Pattern.compile("(?i)toolcall\\s*\\.\\s*(.*)").matcher(first);
      Matcher bracket = Pattern.compile(
          "toolCall\\s*\\[\\s*(?:'([^']*)'|\"([^\"]*)\")\\s*\\]").matcher(first);
      if (bracket.matches()) {
        reason = "reads the argument in brackets; write it as "
            + AdHocToolDescriptor.TOOL_CALL_VARIABLE + "."
            + firstPresent(bracket.group(1), bracket.group(2)) + ".";
      } else if (!read.matches()) {
        reason = "does not start with " + AdHocToolDescriptor.TOOL_CALL_VARIABLE + ".<name>,"
            + " where the model's arguments arrive.";
      } else if (!first.startsWith(AdHocToolDescriptor.TOOL_CALL_VARIABLE)) {
        reason = "writes " + first.substring(0, 8) + " for " + AdHocToolDescriptor.TOOL_CALL_VARIABLE
            + ".";
      } else if (!read.group(1).matches("[\\p{L}_][\\p{L}\\p{N}_]*(-[\\p{L}\\p{N}_]+)*")
          && read.group(1).matches("[\\p{L}_][\\p{L}\\p{N}_]*\\s*[^\\p{L}\\p{N}_\\s].*")) {
        reason = "passes an expression as the value; pass " + AdHocToolDescriptor.TOOL_CALL_VARIABLE
            + ".<name> alone, and compute with the value in another input.";
      } else if (!read.group(1).matches("[A-Za-z_][A-Za-z0-9_]*")) {
        reason = "names the parameter '" + read.group(1) + "': a parameter name has the letters"
            + " A-Z and a-z, digits and '_' only, no umlauts and no '-'.";
      } else {
        for (int i = 1; i < arguments.size(); i++) {
          String literal = arguments.get(i);
          boolean quoted = literal.length() >= 2
              && (literal.charAt(0) == '\'' || literal.charAt(0) == '"')
              && literal.charAt(literal.length() - 1) == literal.charAt(0);
          if (!quoted) {
            reason = "passes " + literal + " as the " + (i == 1 ? "description" : "type")
                + ", which is no literal in quotes.";
            break;
          }
        }
      }
    }
    return "the call " + call + " " + reason;
  }

  /**
   * Which characters of a text are expression code: inside {@code ${...}} or {@code #{...}},
   * outside string literals. Only there does a call of the function, or a read of
   * {@code toolCall}, mean anything; elsewhere it is plain text.
   */
  protected static boolean[] code(String text) {
    boolean[] code = new boolean[text.length()];
    boolean inExpression = false;
    char quote = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (!inExpression) {
        if ((c == '$' || c == '#') && i + 1 < text.length() && text.charAt(i + 1) == '{'
            && (i == 0 || text.charAt(i - 1) != '\\')) {
          inExpression = true;
          i++;
        }
      } else if (quote != 0) {
        if (c == '\\') {
          i++;
        } else if (c == quote) {
          quote = 0;
        }
      } else if (c == '\'' || c == '"') {
        quote = c;
      } else if (c == '}') {
        inExpression = false;
      } else {
        code[i] = true;
      }
    }
    return code;
  }

  /** Whether the argument map is mentioned in expression code of the text. */
  protected static boolean mentionsToolCallInCode(String text) {
    if (text == null || text.indexOf(AdHocToolDescriptor.TOOL_CALL_VARIABLE) < 0) {
      return false;
    }
    boolean[] code = code(text);
    Matcher word = TOOL_CALL_WORD.matcher(text);
    while (word.find()) {
      if (code[word.start()]) {
        return true;
      }
    }
    return false;
  }

  /** Whether a call of the function appears in expression code of the text. */
  protected static boolean callsInCode(String text) {
    if (text == null || text.indexOf(AgenticToolParameterFunction.NAME) < 0) {
      return false;
    }
    boolean[] code = code(text);
    Matcher any = AgenticToolParameterFunction.ANY_CALL.matcher(text);
    while (any.find()) {
      if (code[any.start()]) {
        return true;
      }
    }
    return false;
  }

  /**
   * The texts the catalogue reads declarations from: the child's input parameters and, unless
   * the child is an agent itself, those of a connector on it, nested ones too. Script code is no
   * expression and is left out; {@link #refuseUnreadCalls} refuses a call there.
   */
  protected static List<String> inputTexts(Element child) {
    List<String> texts = new ArrayList<String>();
    collectInputTexts(BpmnParseUtil.findCamundaExtensionElement(child, "inputOutput"), texts);
    Element connector = BpmnParseUtil.findCamundaExtensionElement(child, "connector");
    if (connector != null && !isAgent(child)) {
      collectInputTexts(connector.elementNS(BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS, "inputOutput"),
          texts);
    }
    return texts;
  }

  protected static void collectInputTexts(Element inputOutput, List<String> texts) {
    if (inputOutput == null) {
      return;
    }
    for (Element parameter : inputOutput.elementsNS(
        BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS, "inputParameter")) {
      collectTexts(parameter, texts);
    }
  }

  protected static void collectTexts(Element element, List<String> texts) {
    if ("script".equals(element.getTagName())) {
      return;
    }
    // The key of a map's entry is an expression too: the engine evaluates it with the value.
    String key = "entry".equals(element.getTagName()) ? element.attribute("key") : null;
    if (key != null && !key.trim().isEmpty()) {
      texts.add(key);
    }
    String text = element.getText();
    if (text != null && !text.trim().isEmpty()) {
      texts.add(text);
    }
    for (Element nested : element.elements()) {
      collectTexts(nested, texts);
    }
  }

  /**
   * Refuses a call of the function the catalogue does not read (CIB7-2136). A tool parameter is
   * declared only in an input parameter of an activity the agent starts, its own or its
   * connector's; a call anywhere else in the scope would declare nothing, the model would never
   * be offered the parameter, and the call would read null at run time. Script code is no
   * expression, so a call there is refused as well. A tool that is itself an agent evaluates its
   * connector's inputs on every turn of its own scope, not when it is started, and the catalogue
   * reads no declaration there: a call or a read of toolCall there is refused, and its own input
   * parameters are the place. The tools of a nested agent are left to that agent's own parse.
   */
  protected static void refuseUnreadCalls(Element scopeElement, Collection<String> startableIds) {
    for (Element child : scopeElement.elements()) {
      // The scope's own extension elements are the outer scope's to judge, when this scope is a
      // tool there: its input parameters declare that tool's parameters.
      if (NO_CODE.contains(child.getTagName()) || "extensionElements".equals(child.getTagName())) {
        continue;
      }
      String id = child.attribute("id");
      if (id != null && startableIds.contains(id)) {
        refuseUnreadCallsInTool(scopeElement, child);
      } else {
        refuseCalls(scopeElement, child, child, false);
      }
    }
  }

  protected static void refuseUnreadCallsInTool(Element scopeElement, Element tool) {
    boolean agent = isAgent(tool);
    refuseCallsInAttributes(scopeElement, tool, tool, true);
    refuseCallsInText(scopeElement, tool, tool, true);
    for (Element part : tool.elements()) {
      String tag = part.getTagName();
      if (isTaskScript(tool, part)) {
        continue;
      }
      if ("extensionElements".equals(tag)) {
        for (Element extension : part.elements()) {
          String extensionTag = extension.getTagName();
          if ("inputOutput".equals(extensionTag)) {
            refuseInMapping(scopeElement, tool, extension, false);
          } else if ("connector".equals(extensionTag)) {
            for (Element connectorPart : extension.elements()) {
              if ("inputOutput".equals(connectorPart.getTagName())) {
                refuseInMapping(scopeElement, tool, connectorPart, agent);
              } else {
                refuseCalls(scopeElement, connectorPart, tool, true);
              }
            }
          } else {
            refuseCalls(scopeElement, extension, tool, true);
          }
        }
      } else if (agent && part.attribute("id") != null
          && !"multiInstanceLoopCharacteristics".equals(tag)) {
        continue;
      } else {
        refuseCalls(scopeElement, part, tool, true);
      }
    }
  }

  protected static void refuseInMapping(Element scopeElement, Element tool, Element inputOutput,
      boolean agentConnector) {
    for (Element parameter : inputOutput.elements()) {
      if (!"inputParameter".equals(parameter.getTagName())) {
        refuseCalls(scopeElement, parameter, tool, true);
      } else if (agentConnector) {
        refuseInAgentConnector(scopeElement, tool, parameter);
      } else {
        refuseInScripts(scopeElement, tool, parameter);
      }
    }
  }

  protected static void refuseInScripts(Element scopeElement, Element tool, Element element) {
    if ("script".equals(element.getTagName())) {
      refuseCallsInAttributes(scopeElement, element, tool, true);
      refuseCallsInText(scopeElement, element, tool, true);
      return;
    }
    for (Element nested : element.elements()) {
      refuseInScripts(scopeElement, tool, nested);
    }
  }

  protected static void refuseInAgentConnector(Element scopeElement, Element tool,
      Element element) {
    refuseInAgentConnector(scopeElement, tool, tool, element);
  }

  /** The inputs of the connector of {@code agent}, an agent in or along {@code tool}. */
  protected static void refuseInAgentConnectorOf(Element scopeElement, Element tool, Element agent,
      Element connector) {
    for (Element part : connector.elements()) {
      if ("inputOutput".equals(part.getTagName())) {
        for (Element parameter : part.elements()) {
          refuseInAgentConnector(scopeElement, tool, agent, parameter);
        }
      }
    }
  }

  /**
   * As {@link #refuseInAgentConnector(Element, Element, Element)}, for the connector of
   * {@code agent}, which is the tool itself or an agent inside or along it; the refusal names the
   * tool as the scope's child and is reported on {@code agent}.
   */
  protected static void refuseInAgentConnector(Element scopeElement, Element tool, Element agent,
      Element element) {
    if (readsToolCall(element)) {
      String id = agent.attribute("id");
      throw new BpmnParseException(where(scopeElement, tool) + ": '" + id + "' is an agent"
          + " itself, and the input parameter '" + inputName(element) + "' of its connector"
          + " reads the tool call. Its connector's inputs are read on every turn of '" + id
          + "', not when it is started, and declare no parameter: the model would never be"
          + " offered one there. Declare the parameter in the input"
          + " parameters of '" + id + "' itself, e.g. request = ${"
          + AgenticToolParameterFunction.NAME + "(" + AdHocToolDescriptor.TOOL_CALL_VARIABLE
          + ".request, 'What to do')}, and use ${request} in its message.", agent);
    }
    for (Element nested : element.elements()) {
      refuseInAgentConnector(scopeElement, tool, agent, nested);
    }
  }

  /**
   * Whether the element itself, its text or an attribute, calls the function or mentions
   * toolCall: in a script anywhere in its text, otherwise in expression code.
   */
  protected static boolean readsToolCall(Element element) {
    String text = element.getText();
    boolean script = "script".equals(element.getTagName());
    boolean reads = text != null && (script
        ? AgenticToolParameterFunction.ANY_CALL.matcher(text).find()
            || TOOL_CALL_WORD.matcher(text).find()
        : callsInCode(text) || mentionsToolCallInCode(text));
    for (String key : element.attributes()) {
      String value = element.attribute(key);
      reads = reads || callsInCode(value) || mentionsToolCallInCode(value);
    }
    return reads;
  }

  /** As {@link #readsToolCall}, for the element or anything below it. */
  protected static boolean readsToolCallBelow(Element element) {
    if (readsToolCall(element)) {
      return true;
    }
    for (Element nested : element.elements()) {
      if (readsToolCallBelow(nested)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Refuses any call of the function in the element and below it: none of it is read.
   *
   * @param child the child of the scope the element belongs to, named in the refusal
   * @param tool whether that child is a tool, one the agent starts directly
   */
  protected static void refuseCalls(Element scopeElement, Element element, Element child,
      boolean tool) {
    String tag = element.getTagName();
    if (NO_CODE.contains(tag)) {
      return;
    }
    refuseCallsInAttributes(scopeElement, element, child, tool);
    refuseCallsInText(scopeElement, element, child, tool);
    boolean agent = isAgent(element);
    for (Element nested : element.elements()) {
      if (isTaskScript(element, nested) || (agent && nested.attribute("id") != null
          && !"multiInstanceLoopCharacteristics".equals(nested.getTagName()))) {
        continue;
      }
      refuseCalls(scopeElement, nested, child, tool);
    }
  }

  /**
   * Refuses a call of the function in the element's text, as the engine reads that text: in an
   * expression it declares nothing here, in a script no script engine knows it. A dynamic script
   * that mentions toolCall at all is refused: its expression computes the script's code, and the
   * model's argument would run as that code.
   */
  protected static void refuseCallsInText(Element scopeElement, Element element, Element child,
      boolean tool) {
    String text = textOf(element);
    if (text == null) {
      return;
    }
    Reading reading = reading(element);
    if (reading == Reading.DYNAMIC_SCRIPT && (mentionsToolCallInCode(text)
        || AgenticToolParameterFunction.ANY_CALL.matcher(text).find())) {
      throw dynamicScript(scopeElement, element, child);
    }
    if (reading == Reading.EXPRESSION) {
      if (callsInCode(text)) {
        throw unreadCall(scopeElement, element, null, child, tool, false);
      }
    } else if (AgenticToolParameterFunction.ANY_CALL.matcher(text).find()) {
      throw unreadCall(scopeElement, element, null, child, tool, true);
    }
  }

  protected static void refuseCallsInAttributes(Element scopeElement, Element element,
      Element child, boolean tool) {
    for (String key : codeAttributes(element)) {
      if (callsInCode(element.attribute(key))) {
        throw unreadCall(scopeElement, element, key, child, tool, false);
      }
    }
  }

  /**
   * Why a call of the function in the element declares nothing, naming the scope's child it
   * belongs to (CIB7-2136).
   */
  protected static BpmnParseException unreadCall(Element scopeElement, Element element,
      String attribute, Element child, boolean tool, boolean script) {
    String where = (child.attribute("id") != null) ? where(scopeElement, child)
        : "Ad hoc sub process '" + scopeElement.attribute("id") + "'";
    if (script) {
      return new BpmnParseException(where + ": " + AgenticToolParameterFunction.NAME + "(...) in"
          + " a script declares nothing, and no script engine knows the function, not even JUEL's."
          + " Declare the parameter in an input parameter that is an expression, e.g. amount = ${"
          + AgenticToolParameterFunction.NAME + "(" + AdHocToolDescriptor.TOOL_CALL_VARIABLE
          + ".amount)}, and read the variable amount in the activity's script or an output"
          + " parameter: a name a script reads from " + AdHocToolDescriptor.TOOL_CALL_VARIABLE
          + " is not checked at deployment, and misspelt it reads null.",
          pointedAt(element, child));
    }
    String place = tool
        ? "an input parameter of '" + child.attribute("id") + "'"
        : "an input parameter of an activity the agent starts";
    return new BpmnParseException(where + ": " + AgenticToolParameterFunction.NAME + "(...) in "
        + describe(element, attribute) + " declares nothing. A tool parameter is declared only in"
        + " an input parameter of an activity the agent starts, its own or its connector's; here"
        + " the model would never be offered it, and it would read null. Declare it in " + place
        + ", and read it here as ${"
        + AdHocToolDescriptor.TOOL_CALL_VARIABLE + ".<name>} or as that input's variable.",
        pointedAt(element, child));
  }

  /** Why a dynamic script must not read the tool call (CIB7-2136). */
  protected static BpmnParseException dynamicScript(Element scopeElement, Element element,
      Element child) {
    String where = (child.attribute("id") != null) ? where(scopeElement, child)
        : "Ad hoc sub process '" + scopeElement.attribute("id") + "'";
    return new BpmnParseException(where + ": " + describe(element, null) + " is a dynamic script:"
        + " its source starts with ${ and is an expression that computes the script's code, and"
        + " it reads " + AdHocToolDescriptor.TOOL_CALL_VARIABLE + ", so the model's argument would"
        + " run as script code. Declare the parameter in an input parameter that is an expression,"
        + " e.g. amount = ${" + AgenticToolParameterFunction.NAME + "("
        + AdHocToolDescriptor.TOOL_CALL_VARIABLE + ".amount)}, and read the variable amount in a"
        + " script that is no expression.", pointedAt(element, child));
  }

  /**
   * Refuses a call of the function that no agentic scope reads (CIB7-2136): anywhere outside an
   * agentic ad hoc sub process, and in the own extension elements of an agentic scope that is no
   * tool of another. The function is registered on every engine, so such a model would deploy, and
   * the call would fail when it runs: nothing sets toolCall there. Inside an agentic scope the
   * catalogue has judged every call already ({@link #refuseUnreadCalls}), so its children are left
   * alone. So is a script here: no script engine knows the function, so a {@code toolParameter(}
   * in a script that no agent starts is someone else's, a function of the script's own, say. The
   * source of a dynamic script is an expression, though, and a call there is refused. So is a call
   * in an attribute of the process itself, as its job priority, which the engine evaluates too.
   */
  public static void refuseCallsOutsideAgents(Element processElement) {
    for (String key : codeAttributes(processElement)) {
      if (callsInCode(processElement.attribute(key))) {
        throw outsideAgents(processElement, processElement, key, processElement);
      }
    }
    for (Element child : processElement.elements()) {
      refuseOutsideAgents(processElement, child, child);
    }
  }

  /** @param owner the nearest element with an id that holds the element, named in the refusal */
  protected static void refuseOutsideAgents(Element processElement, Element element,
      Element owner) {
    String tag = element.getTagName();
    if (NO_CODE.contains(tag)) {
      return;
    }
    Element holder = (element.attribute("id") != null) ? element : owner;
    for (String key : codeAttributes(element)) {
      if (callsInCode(element.attribute(key))) {
        throw outsideAgents(processElement, element, key, holder);
      }
    }
    Reading reading = reading(element);
    if ((reading == Reading.EXPRESSION || reading == Reading.DYNAMIC_SCRIPT)
        && callsInCode(textOf(element))) {
      throw outsideAgents(processElement, element, null, holder);
    }
    boolean agent = isAgent(element);
    for (Element nested : element.elements()) {
      if (isTaskScript(element, nested) || (agent && nested.attribute("id") != null
          && !"multiInstanceLoopCharacteristics".equals(nested.getTagName()))) {
        continue;
      }
      refuseOutsideAgents(processElement, nested, holder);
    }
    if (agent) {
      refuseToolCallInOwnMappings(processElement, element);
    }
  }

  /**
   * Refuses a read of toolCall in the connector and in the own input and output parameters of an
   * agentic scope that is no tool of another agent (CIB7-2136). The connector's inputs are read on
   * every turn of the agent, the scope's inputs when it is entered and its outputs when it ends:
   * no tool call's arguments arrive at any of them, so toolCall there would read null. An agent in
   * or along a tool of another is judged by that agent's catalogue
   * ({@link #refuseInAgentConnector}); a call of the function there is refused before this, as
   * anywhere outside a tool.
   */
  protected static void refuseToolCallInOwnMappings(Element processElement, Element agent) {
    Element connector = BpmnParseUtil.findCamundaExtensionElement(agent, "connector");
    refuseToolCallIn(processElement, agent, (connector == null) ? null
        : connector.elementNS(BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS, "inputOutput"), true);
    refuseToolCallIn(processElement, agent,
        BpmnParseUtil.findCamundaExtensionElement(agent, "inputOutput"), false);
  }

  /** @param connector whether the mappings are the connector's, rather than the scope's own */
  protected static void refuseToolCallIn(Element processElement, Element agent,
      Element inputOutput, boolean connector) {
    if (inputOutput == null) {
      return;
    }
    for (Element parameter : inputOutput.elements()) {
      if (!readsToolCallBelow(parameter)) {
        continue;
      }
      String which = "outputParameter".equals(parameter.getTagName()) ? "output" : "input";
      throw new BpmnParseException("Process '" + processElement.attribute("id") + "', element '"
          + agent.attribute("id") + "': the " + which + " parameter '" + inputName(parameter)
          + "'" + (connector ? " of its connector" : "") + " reads "
          + AdHocToolDescriptor.TOOL_CALL_VARIABLE + ". "
          + (connector ? "The connector's inputs are read on every turn of the agent"
              : "The scope's own mappings are read when it is entered and when it ends")
          + ", where no tool call's arguments arrive, so it would read null. Read a process"
          + " variable there; a tool reads its call's arguments in its own input parameters.",
          agent);
    }
  }

  protected static BpmnParseException outsideAgents(Element processElement, Element element,
      String attribute, Element owner) {
    String where = "Process '" + processElement.attribute("id") + "'"
        + ((owner != element && owner.attribute("id") != null)
            ? ", element '" + owner.attribute("id") + "'" : "");
    return new BpmnParseException(where + ": " + AgenticToolParameterFunction.NAME + "(...) in "
        + describe(element, attribute) + " declares nothing. A tool parameter is declared only"
        + " in an input parameter of an activity an agentic ad hoc sub process starts, its own"
        + " or its connector's; nothing"
        + " passes " + AdHocToolDescriptor.TOOL_CALL_VARIABLE + " here, so the expression would"
        + " fail when it runs. Move the declaration into such an input parameter, or read a"
        + " process variable here.", pointedAt(element, owner));
  }

  /**
   * How the engine reads the text of an element (CIB7-2136), as {@link ScriptUtil} decides: as an
   * expression; as script code, which no expression reads; as a script in JUEL, which the JUEL
   * script engine evaluates as an expression that knows none of the engine's functions; or as a
   * dynamic script, whose source starts with ${ and is an expression that computes the code.
   */
  protected enum Reading {
    EXPRESSION, SCRIPT, JUEL_SCRIPT, DYNAMIC_SCRIPT
  }

  /**
   * How the engine reads {@link #textOf} the element: a {@code camunda:script}, a script task and
   * a condition with a {@code language} hold a script, anything else an expression.
   */
  protected static Reading reading(Element element) {
    String tag = element.getTagName();
    String language;
    if ("scriptTask".equals(tag)) {
      language = element.attribute("scriptFormat");
      if (language == null || language.trim().isEmpty()) {
        language = ScriptingEngines.DEFAULT_SCRIPTING_LANGUAGE;
      }
    } else if ("script".equals(tag)) {
      language = (element.attribute("scriptFormat") == null) ? ""
          : element.attribute("scriptFormat");
    } else if ("conditionExpression".equals(tag) && element.attribute("language") != null
        && !element.attribute("language").trim().isEmpty()) {
      language = element.attribute("language");
    } else {
      return Reading.EXPRESSION;
    }
    language = language.trim();
    if (JuelScriptEngineFactory.names.contains(language.toLowerCase(Locale.ROOT))) {
      return Reading.JUEL_SCRIPT;
    }
    String text = textOf(element);
    return (text != null && ScriptUtil.isDynamicScriptExpression(language, text))
        ? Reading.DYNAMIC_SCRIPT : Reading.SCRIPT;
  }

  /** The text of the element as the engine reads it: its own, or a script task's script. */
  protected static String textOf(Element element) {
    if ("scriptTask".equals(element.getTagName())) {
      Element script = element.element("script");
      return (script != null) ? script.getText() : null;
    }
    return element.getText();
  }

  /** Whether the element is the script of a script task, which {@link #textOf} reads with it. */
  protected static boolean isTaskScript(Element parent, Element element) {
    return "scriptTask".equals(parent.getTagName()) && "script".equals(element.getTagName());
  }

  /** The attributes of the element whose value may be an expression: all but a fixed value. */
  protected static List<String> codeAttributes(Element element) {
    List<String> keys = new ArrayList<String>();
    for (String key : element.attributes()) {
      if (!NO_CODE_ATTRIBUTES.contains(key.substring(key.lastIndexOf(':') + 1))) {
        keys.add(key);
      }
    }
    return keys;
  }

  /** Whether an element is an agentic scope itself: an ad hoc sub process with the agent. */
  protected static boolean isAgent(Element element) {
    return "adHocSubProcess".equals(element.getTagName())
        && AgenticTurnRunner.AGENT_CONNECTOR_ID.equals(AgenticAdHocParseListener.connectorId(
            BpmnParseUtil.findCamundaExtensionElement(element, "connector")));
  }

  /**
   * The element a refusal is reported on: the element itself where it has an id, otherwise the one
   * with an id that holds it, so that the parse error names an element a modeller or a tool can
   * find (an input parameter or a script has none). The message names the element itself.
   */
  protected static Element pointedAt(Element element, Element holder) {
    return (element.attribute("id") != null || holder == null) ? element : holder;
  }

  /**
   * The element a refusal points to, as the modeller finds it in the model: its tag with its id,
   * or its name where it has no id, as an input parameter; and the attribute, if it is one.
   */
  protected static String describe(Element element, String attribute) {
    String label = (element.attribute("id") != null) ? element.attribute("id")
        : element.attribute("name");
    String what = element.getTagName() + ((label != null) ? " '" + label + "'" : "");
    return (attribute == null) ? what
        : "the attribute " + attribute.substring(attribute.lastIndexOf(':') + 1) + " of " + what;
  }

  protected static String inputName(Element element) {
    String name = element.attribute("name");
    return (name != null) ? name : element.getTagName();
  }

  protected static String where(Element scopeElement, Element child) {
    return "Ad hoc sub process '" + scopeElement.attribute("id") + "', child '"
        + child.attribute("id") + "'";
  }

  protected static BpmnParseException reservedName(String where, Element child) {
    return new BpmnParseException(where + ": '" + AdHocToolDescriptor.TOOL_CALL_VARIABLE
        + "' cannot be a parameter name: it holds the arguments of a call. Choose another name.",
        child);
  }

  protected static String firstPresent(String first, String second) {
    return (first != null) ? first : (second == null ? "" : second);
  }

  protected static void addIfPresent(Set<String> names, String candidate) {
    if (candidate != null && !candidate.trim().isEmpty()) {
      names.add(candidate.trim());
    }
  }
}
