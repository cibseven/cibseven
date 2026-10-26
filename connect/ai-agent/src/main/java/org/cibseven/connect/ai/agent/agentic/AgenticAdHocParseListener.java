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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.ActivityTypes;
import org.cibseven.bpm.engine.BpmnParseException;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.ExecutionListener;
import org.cibseven.bpm.engine.delegate.VariableScope;
import org.cibseven.bpm.engine.impl.Condition;
import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocSubProcessActivityBehavior;
import org.cibseven.bpm.engine.impl.bpmn.behavior.CallableElementActivityBehavior;
import org.cibseven.bpm.engine.impl.bpmn.behavior.MultiInstanceActivityBehavior;
import org.cibseven.bpm.engine.impl.bpmn.behavior.ParallelMultiInstanceActivityBehavior;
import org.cibseven.bpm.engine.impl.bpmn.helper.BpmnProperties;
import org.cibseven.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParse;
import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParseUtil;
import org.cibseven.bpm.engine.impl.bpmn.parser.ErrorEventDefinition;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.core.model.PropertyKey;
import org.cibseven.bpm.engine.impl.core.variable.mapping.InputParameter;
import org.cibseven.bpm.engine.impl.core.variable.mapping.IoMapping;
import org.cibseven.bpm.engine.impl.core.variable.mapping.value.NullValueProvider;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.DeploymentEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.cibseven.bpm.engine.impl.pvm.PvmActivity;
import org.cibseven.bpm.engine.impl.pvm.PvmTransition;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityStartBehavior;
import org.cibseven.bpm.engine.impl.pvm.process.ProcessDefinitionImpl;
import org.cibseven.bpm.engine.impl.pvm.process.ScopeImpl;
import org.cibseven.bpm.engine.impl.util.xml.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns an agentic ad hoc sub process into one whose turns are driven by a job: parks it, records
 * its configuration, and attaches what schedules the turns: a start listener and a trigger the
 * scope's behaviour tells of every child that ends.
 *
 * <p>A scope is agentic when it carries the AI agent's connector, {@code camunda:connector} with
 * the id {@code cibseven-ai-agent} -- the same element and the same input parameters as an AI
 * agent service task, evaluated on every turn instead of once. Only the agent's limits stay
 * properties of the scope.
 *
 * <p>Every other trace of an agent on a sub process -- the agent's element template, its limits,
 * its connector on the wrong element -- without a scope the agent can run refuses the deployment,
 * saying why and what to do. A modeler drops a connector it does not expect on a sub process when
 * the element is copied or its type is changed; without this check such a scope would deploy as a
 * plain ad hoc scope and wait for ever.
 *
 * <p>In every process it parses, agentic or not, it also refuses a call of the expression function
 * {@code toolParameter} that no agentic scope reads (CIB7-2136): the function resolves on every
 * engine, and such a call would deploy and fail when it runs.
 *
 * <p>Registered as a <em>post</em> parse listener. It needs the behaviour, the children and their
 * sequence flows, none of which exist while the scope is being built.
 */
public class AgenticAdHocParseListener extends AbstractBpmnParseListener {

  private static final Logger LOG = LoggerFactory.getLogger(AgenticAdHocParseListener.class);

  public static final String PROPERTY_PREFIX = "cibseven.agentic.";
  public static final String MAX_TURNS = PROPERTY_PREFIX + "maxTurns";
  public static final String MAX_MODEL_CALLS = PROPERTY_PREFIX + "maxModelCalls";

  /** Where the conversation lives and what bounds it across entries (CIB7-2137). */
  public static final String CONVERSATION = AdHocConversation.MODE_PROPERTY;
  public static final String MAX_TURNS_TOTAL = AdHocConversation.MAX_TURNS_TOTAL_PROPERTY;

  /** The limits stay properties with the connector: they are known at parse time and no child may change them. */
  protected static final List<String> LIMITS =
      Arrays.asList(MAX_TURNS, MAX_MODEL_CALLS, MAX_TURNS_TOTAL);

  /** What a limit does to the scope: end it as any turn would, or with a BPMN error (CIB7-2151). */
  public static final String ON_LIMIT = AgenticOutcome.ON_LIMIT_PROPERTY;

  /** Settings of the agent that are not limits, and mark a scope as an agent just as much. */
  protected static final List<String> SETTINGS = Arrays.asList(CONVERSATION, ON_LIMIT);

  /** What the element template for an agentic scope writes into {@code camunda:modelerTemplate}. */
  public static final String AD_HOC_TEMPLATE_ID = "org.cibseven.connect.ai.agent.AdHocSubProcess";
  public static final String AD_HOC_TEMPLATE_NAME = "CIB seven AI Agent (ad hoc sub process)";

  /** Input parameters the agentic loop sets itself: each scope keeps its own conversation. */
  protected static final List<String> LOOP_PARAMETERS = Arrays.asList("useChatMemory", "memoryId");

  /** The connector's input parameters the agent's task and prompt come from. */
  protected static final List<String> TASK_PARAMETERS = Arrays.asList("message", "instruction");

  /** The input and output parameters of the agent's connector on the scope, applied every turn. */
  public static final PropertyKey<IoMapping> AGENT_CONNECTOR_IO =
      new PropertyKey<IoMapping>("agentConnectorIo");

  /**
   * The connector's {@code followUp} input parameter, held separately from {@link #AGENT_CONNECTOR_IO}
   * so nothing evaluates it but {@link AgenticTurnRunner#snapshotFollowUp}, and only when that
   * is actually called (CIB7-2137).
   */
  public static final PropertyKey<InputParameter> FOLLOW_UP_INPUT =
      new PropertyKey<InputParameter>("followUpInput");

  /** Stateless, so one serves every tool of every scope. */
  protected static final AgenticToolResultCarrier RESULT_CARRIER = new AgenticToolResultCarrier();

  /** Stateless as well: one records for every boundary event on every tool (CIB7-2166). */
  protected static final AgenticToolEndRecorder END_RECORDER = new AgenticToolEndRecorder();

  @Override
  public void parseSubProcess(Element scopeElement, ScopeImpl scope, ActivityImpl activity) {
    Map<String, String> properties = BpmnParseUtil.parseCamundaExtensionProperties(scopeElement);
    if (properties == null) {
      properties = Collections.emptyMap();
    }
    Element connector = BpmnParseUtil.findCamundaExtensionElement(scopeElement, "connector");
    String connectorId = connectorId(connector);
    boolean agentConnector = AgenticTurnRunner.AGENT_CONNECTOR_ID.equals(connectorId);
    boolean template = AD_HOC_TEMPLATE_ID.equals(
        scopeElement.attributeNS(BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS, "modelerTemplate"));
    List<String> limits = presentLimits(properties);
    List<String> settings = present(properties, SETTINGS);
    boolean adHoc = activity.getActivityBehavior() instanceof AdHocSubProcessActivityBehavior;

    if (connector != null && !agentConnector && adHoc) {
      // Reported, not refused (CIB7-2180). Another plugin may drive an ad hoc scope with a
      // connector of its own, found the way this module's plugin is found; refusing every
      // other id would block that. A typo in this agent's id lands here as well -- unless the
      // scope also carries the template link or the limits, which is refused below.
      LOG.warn("Ad hoc sub process '{}' carries the connector '{}', not the AI agent's"
          + " connector '{}', so it is not an agentic scope.", activity.getId(), connectorId,
          AgenticTurnRunner.AGENT_CONNECTOR_ID);
    }

    if (!agentConnector && !template && limits.isEmpty() && settings.isEmpty()) {
      return;
    }

    // From here on the model asked for an agent, so anything missing is a modelling error rather
    // than a case to skip.
    if (!adHoc) {
      throw new BpmnParseException("'" + activity.getId() + "': the AI agent is only valid on"
          + " an adHocSubProcess, and '" + activity.getId() + "' is a " + scopeElement.getTagName()
          + ". Make it an ad hoc sub process, or remove the agent from it: the connector '"
          + AgenticTurnRunner.AGENT_CONNECTOR_ID + "', the template '" + AD_HOC_TEMPLATE_NAME
          + "' and the limits " + MAX_TURNS + " and " + MAX_MODEL_CALLS + ".", scopeElement);
    }

    if (!agentConnector) {
      throw new BpmnParseException("Ad hoc sub process '" + activity.getId() + "' is marked as"
          + " an AI agent (" + marks(template, limits, settings) + "), but carries no connector '"
          + AgenticTurnRunner.AGENT_CONNECTOR_ID + "', so no agent would ever run it and the scope"
          + " would wait for ever. A modeler drops that connector when the element is copied and"
          + " pasted, or when its type is changed. Apply the template '" + AD_HOC_TEMPLATE_NAME
          + "' to the element again; or, if it is meant to be a plain ad hoc sub process, remove"
          + " the template and the limits.", scopeElement);
    }

    refuseTheTaskOnTheScope(scopeElement, activity);
    refuseAnImpossibleConversation(scopeElement, scope, activity, properties);
    refuseAnUnknownOnLimit(scopeElement, activity, properties);

    AdHocSubProcessActivityBehavior behavior =
        (AdHocSubProcessActivityBehavior) activity.getActivityBehavior();

    if (behavior.hasCompletionCondition()) {
      throw new BpmnParseException("Ad hoc sub process '" + activity.getId()
          + "': an agentic scope decides its own end, so it must not carry a completionCondition.", scopeElement);
    }

    List<String> startable = activity.getProperties().get(BpmnProperties.AD_HOC_STARTABLE_ACTIVITIES);
    if (startable == null || startable.isEmpty()) {
      throw new BpmnParseException("Ad hoc sub process '" + activity.getId()
          + "': an agentic scope needs at least one directly startable child, but every child is"
          + " the target of a sequence flow inside the scope.", scopeElement);
    }

    // The connector's own mapping, applied on every turn. Its message is the task. Its presence
    // on the scope is what makes the scope agentic at runtime (AgenticScopes.isAgentic).
    IoMapping io = BpmnParseUtil.parseInputOutput(connector);
    refuseAConnectorWithoutATask(scopeElement, activity, io);
    refuseAnUndersizedPerActivityMemoryWindow(scopeElement, activity, connector, io, properties);
    // followUp pulled out before the mapping is stored: unlike every other input parameter, it
    // must not be evaluated on every step of every turn (CIB7-2148), only once, by the loop
    // itself, and only on a re-entry (CIB7-2137). Left in, the generic per-step evaluation in
    // AgenticTurnRunner.askTheAgent would run its expression on every step regardless, which can
    // fail a turn that never uses it on a variable the expression needs but a first entry has not
    // set yet.
    InputParameter followUp = extractFollowUp(io);
    if (followUp != null) {
      activity.getProperties().set(FOLLOW_UP_INPUT, followUp);
    }
    activity.getProperties().set(AGENT_CONNECTOR_IO, io);

    // Parking. Without it the engine ends the scope as soon as no child is active, and a pending
    // job is not a child -- the scope would be gone before the turn ran.
    behavior.setCompletionCondition(new NeverCondition());

    // The properties under the engine's own key, so anything that reads a scope's
    // camunda:property at runtime finds it. The parser sets this key only for an external
    // service task, and the tool's caps sit on the scope.
    activity.getProperties().set(BpmnProperties.EXTENSION_PROPERTIES, properties);

    // The tool catalogue: one descriptor per startable child, built here so a broken
    // declaration refuses the deployment rather than a turn.
    activity.getProperties().set(AdHocToolDescriptor.CATALOG,
        AgenticToolCatalogParser.parse(scopeElement, startable,
            parsing(activity.getProcessDefinition())));

    // A tool that holds variables of its own loses them before this scope is told it ended: they
    // are carried out as it ends (CIB7-2215). So does any such activity further along a tool's
    // chain, reached by an inner flow: the chain is reported under the tool, with its results.
    // Inside a multi-instance body every instance holds its loop variables, the element and the
    // counter, so such an activity always holds variables of its own (review F4).
    for (ActivityImpl child : activity.getActivities()) {
      ActivityImpl performed = innerActivity(child);
      if (!performed.isTriggeredByEvent() && !performed.isCompensationHandler()
          && (performed != child || holdsVariablesOfItsOwn(performed))) {
        performed.addBuiltInListener(ExecutionListener.EVENTNAME_END, RESULT_CARRIER);
      }
    }

    // How a tool ended, when an interrupting boundary event on it, or on an activity further along
    // its chain, ended it: which event, of which kind, and for an error, which code (CIB7-2166).
    List<ActivityImpl> interrupted = new ArrayList<ActivityImpl>();
    for (ActivityImpl child : activity.getActivities()) {
      ActivityImpl attachedTo = recordInterruptions(child);
      if (attachedTo != null && !interrupted.contains(attachedTo)) {
        interrupted.add(attachedTo);
      }
    }
    assignRewinds(activity, startable, interrupted);

    // How a failed turn is retried: the scope's cycle, the engine's, or a default that is not
    // immediate (CIB7-2149). A cycle that cannot be read refuses the deployment.
    AgenticTurnRetry.configure(scopeElement, activity);

    // Built in, not custom: skipCustomListeners would otherwise enter the scope without its
    // first turn, and nothing would ever give it one.
    activity.addBuiltInListener(ExecutionListener.EVENTNAME_START, new AgenticAdHocStartListener());

    // Every further turn: the behaviour tells the trigger of each child that ends or is removed,
    // once per performance and at the end of a chain, whatever listeners are skipped.
    behavior.addObserver(new AgenticTurnTrigger());
  }

  /**
   * Makes {@code child}, a child of an agentic scope, record how it interrupted a tool's flow, if
   * it is an interrupting boundary event of a kind that does (see {@link AgenticToolEndRecorder}
   * for where the recorder sits), and returns the activity it is attached to; else null. A boundary
   * error event's definition on that activity is replaced by one that remembers the code it caught;
   * the engine hands the code to the event only through a process variable the modeller has to ask
   * for (see {@link AgenticCaughtError}). A non-interrupting event ends nothing; a compensation or
   * cancel event is not one the agent's tools are ended by.
   */
  protected static ActivityImpl recordInterruptions(ActivityImpl child) {
    String kind = AgenticToolEndRecorder.kindOf(child);
    if (child.getActivityStartBehavior() != ActivityStartBehavior.CANCEL_EVENT_SCOPE
        || kind == null || !(child.getEventScope() instanceof ActivityImpl)) {
      return null;
    }
    ActivityImpl attachedTo = (ActivityImpl) child.getEventScope();
    if (AgenticToolEndRecorder.ERROR.equals(kind)) {
      List<ErrorEventDefinition> definitions =
          attachedTo.getProperties().get(BpmnProperties.ERROR_EVENT_DEFINITIONS);
      List<ErrorEventDefinition> replaced = new ArrayList<ErrorEventDefinition>();
      for (ErrorEventDefinition definition : definitions) {
        replaced.add(child.getId().equals(definition.getHandlerActivityId())
            && !(definition instanceof AgenticCaughtError)
            ? new AgenticCaughtError(definition) : definition);
      }
      attachedTo.getProperties().set(BpmnProperties.ERROR_EVENT_DEFINITIONS, replaced);
      addRecorder(attachedTo, ExecutionListener.EVENTNAME_END);
    } else {
      addRecorder(child, ExecutionListener.EVENTNAME_START);
    }
    return attachedTo;
  }

  /**
   * Where an interruption is forgotten (CIB7-2166): at the start of every activity of the normal
   * flow from which an interrupted activity can be reached again, that activity included. When
   * the flow comes back there, it is again before the interruption, and how it ends now is what
   * counts; an event's path that leads back to the tool, or to the interrupted activity, and then
   * ends normally, reports no interruption.
   *
   * <p>The normal flow is what the sequence flows reach from the startable children. An activity
   * only an event's own path reaches is not part of it, so the first activity of that path, which
   * may well lead back, does not forget the interruption it is handling.
   */
  protected static void assignRewinds(ActivityImpl scope, List<String> startable,
      List<ActivityImpl> interrupted) {
    if (interrupted.isEmpty()) {
      return;
    }
    List<ActivityImpl> normalFlow = new ArrayList<ActivityImpl>();
    // The scope's child, a multi-instance body for a multi-instance tool: it has the flows.
    for (ActivityImpl child : scope.getActivities()) {
      if (startable.contains(innerActivity(child).getId())) {
        reach(child, true, normalFlow);
      }
    }
    for (ActivityImpl target : interrupted) {
      List<ActivityImpl> upstream = new ArrayList<ActivityImpl>();
      reach(target, false, upstream);
      String forgotten = AgenticToolEndRecorder.attachedTo(target);
      for (ActivityImpl activity : upstream) {
        if (!normalFlow.contains(activity)) {
          continue;
        }
        List<String> rewinds = activity.getProperties().get(AgenticToolEndRecorder.REWINDS);
        if (rewinds == null) {
          rewinds = new ArrayList<String>();
          activity.getProperties().set(AgenticToolEndRecorder.REWINDS, rewinds);
        }
        if (!rewinds.contains(forgotten)) {
          rewinds.add(forgotten);
        }
        addRecorder(activity, ExecutionListener.EVENTNAME_START);
      }
    }
  }

  /** Adds {@code from} and what its sequence flows reach, forwards or backwards, to {@code reached}. */
  protected static void reach(ActivityImpl from, boolean forwards, List<ActivityImpl> reached) {
    if (reached.contains(from)) {
      return;
    }
    reached.add(from);
    for (PvmTransition transition : forwards
        ? from.getOutgoingTransitions() : from.getIncomingTransitions()) {
      PvmActivity next = forwards ? transition.getDestination() : transition.getSource();
      if (next instanceof ActivityImpl) {
        reach((ActivityImpl) next, forwards, reached);
      }
    }
  }

  /** The recorder on {@code activity} for {@code event}, once, however many events need it there. */
  protected static void addRecorder(ActivityImpl activity, String event) {
    if (!activity.getBuiltInListeners(event).contains(END_RECORDER)) {
      activity.addBuiltInListener(event, END_RECORDER);
    }
  }

  /** The activity a child performs: itself, or the activity inside its multi-instance body. */
  protected static ActivityImpl innerActivity(ActivityImpl child) {
    if (child.getActivityBehavior() instanceof MultiInstanceActivityBehavior
        && !child.getActivities().isEmpty()) {
      return child.getActivities().get(0);
    }
    return child;
  }

  /**
   * Whether the tool's own execution holds variables that go with it: a tool that is a scope, and
   * the activity of a multi-instance tool that would be one without its multi-instance body. An
   * activity with an input or output mapping, or a call activity, is a scope of its own, except
   * inside a multi-instance body; there each instance's execution holds them instead, and is no
   * scope ({@code BpmnParse.parseActivityInputOutput}, {@code parseCallActivity}).
   */
  protected static boolean holdsVariablesOfItsOwn(ActivityImpl tool) {
    return tool.isScope() || tool.getIoMapping() != null
        || tool.getActivityBehavior() instanceof CallableElementActivityBehavior;
  }

  /**
   * Literal connector window sizes can be rejected before deployment. Dynamic values are left
   * untouched here; they are checked after the ordinary per-step mapping evaluates them.
   */
  protected void refuseAnUndersizedPerActivityMemoryWindow(Element scopeElement,
      ActivityImpl activity, Element connector, IoMapping io, Map<String, String> properties) {
    if (!AdHocConversation.PER_ACTIVITY.equals(
        String.valueOf(properties.get(CONVERSATION)).trim())) {
      return;
    }

    int maxModelCalls = AdHocConversation.effectiveMaxModelCalls(
        properties.get(MAX_MODEL_CALLS));
    long required = AdHocConversation.requiredMemoryWindow(maxModelCalls);
    if (required > Integer.MAX_VALUE) {
      throw new BpmnParseException("Ad hoc sub process '" + activity.getId() + "': "
          + "chatMemoryMaxMessages requires a minimum of " + required + " messages for "
          + maxModelCalls + " model calls, exceeding the supported integer window size "
          + Integer.MAX_VALUE + ".", scopeElement);
    }

    InputParameter memoryWindow = null;
    Element memoryWindowElement = null;
    Element inputOutput = connector.elementNS(BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS, "inputOutput");
    List<Element> inputElements = inputOutput.elementsNS(
        BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS, "inputParameter");
    for (int i = 0; i < io.getInputParameters().size(); i++) {
      InputParameter input = io.getInputParameters().get(i);
      if ("chatMemoryMaxMessages".equals(input.getName())) {
        memoryWindow = input;
        memoryWindowElement = inputElements.get(i);
      }
    }
    if (memoryWindow == null) {
      return;
    }

    if (memoryWindow.getValueProvider().isDynamic()
        || memoryWindowElement.elements().size() > 0) {
      return;
    }
    String configured = memoryWindowElement.getText().trim();
    if (configured.isEmpty()) {
      return;
    }

    int actual;
    try {
      actual = Integer.parseInt(configured.toString().trim());
    } catch (NumberFormatException e) {
      throw new BpmnParseException("Ad hoc sub process '" + activity.getId() + "': "
          + "chatMemoryMaxMessages must be a positive integer and at least " + required
          + " messages for " + maxModelCalls + " model calls, but is '" + configured + "'.",
          scopeElement);
    }
    if (actual < required) {
      throw new BpmnParseException("Ad hoc sub process '" + activity.getId() + "': "
          + "chatMemoryMaxMessages must be at least " + required + " messages for "
          + maxModelCalls + " model calls, but is " + actual + ".", scopeElement);
    }
  }

  /**
   * {@code cibseven.agentic.onLimit} is {@code continue}, the default, or {@code error}. A typo
   * would leave a modeller who asked for an error with a scope that silently goes on.
   */
  protected void refuseAnUnknownOnLimit(Element scopeElement, ActivityImpl activity,
      Map<String, String> properties) {
    String onLimit = properties.get(ON_LIMIT);
    if (onLimit == null || onLimit.trim().isEmpty()
        || AgenticOutcome.ON_LIMIT_CONTINUE.equals(onLimit.trim())
        || AgenticOutcome.ON_LIMIT_ERROR.equals(onLimit.trim())) {
      return;
    }
    throw new BpmnParseException("Ad hoc sub process '" + activity.getId() + "': '" + onLimit
        + "' is not a value of " + ON_LIMIT + ". Use '" + AgenticOutcome.ON_LIMIT_CONTINUE
        + "', where a limit ends the scope as any turn would and the outcome names it, or '"
        + AgenticOutcome.ON_LIMIT_ERROR + "', where a limit ends the scope with a BPMN error"
        + " whose code is the outcome.", scopeElement);
  }

  /**
   * An agentic scope with {@code onLimit = error} needs a boundary error event on it for every
   * limit it can reach (CIB7-2151). The engine ends an execution without a word when nothing
   * catches a BPMN error ({@code enableExceptionsAfterUnhandledBpmnError} is off by default), so
   * a limit no boundary event catches would end the scope with no trace in the process; and an
   * error event sub process inside the scope that catches the limit takes it before any boundary
   * event can. Checked here, once the whole process is parsed and its boundary events are known.
   *
   * <p>Also here: a tool parameter declared where no agentic scope reads it, outside every agentic
   * scope, refuses the deployment (CIB7-2136), since the function resolves on every engine.
   */
  /**
   * This parse of the definition, for the checks of tool parameters (CIB7-2136): its process, as
   * the modeller named it, and whether it is deployed now or parsed again from a deployment made
   * earlier. The engine marks a deployment it parses again as not new, on the very entity it reads
   * in the same command, before it parses it ({@code ResourceDefinitionCache}); a new one carries
   * the mark from its deployment. Outside a command, as when a model is only validated, it counts
   * as a deployment.
   */
  protected static AgenticToolCatalogParser.Parsing parsing(ProcessDefinitionImpl definition) {
    if (!(definition instanceof ProcessDefinitionEntity)) {
      return new AgenticToolCatalogParser.Parsing(definition.getId(), true);
    }
    ProcessDefinitionEntity entity = (ProcessDefinitionEntity) definition;
    CommandContext commandContext = Context.getCommandContext();
    DeploymentEntity deployment = (commandContext == null || entity.getDeploymentId() == null)
        ? null
        : commandContext.getDeploymentManager().findDeploymentById(entity.getDeploymentId());
    return new AgenticToolCatalogParser.Parsing(entity.getKey(),
        deployment == null || deployment.isNew());
  }

  @Override
  public void parseProcess(final Element processElement,
      ProcessDefinitionEntity processDefinition) {
    parsing(processDefinition).check(
        () -> AgenticToolCatalogParser.refuseCallsOutsideAgents(processElement));
    refuseUncaughtLimits(processElement, processDefinition, processDefinition.getActivities());
  }

  protected void refuseUncaughtLimits(Element processElement, ProcessDefinitionEntity definition,
      List<ActivityImpl> activities) {
    for (ActivityImpl activity : activities) {
      refuseUncaughtLimits(processElement, definition, activity.getActivities());
      if (activity.getProperties().get(AGENT_CONNECTOR_IO) == null) {
        continue;
      }
      Map<String, String> properties =
          activity.getProperties().get(BpmnProperties.EXTENSION_PROPERTIES);
      if (properties == null || !AgenticOutcome.ON_LIMIT_ERROR.equals(
          String.valueOf(properties.get(ON_LIMIT)).trim())) {
        continue;
      }
      List<String> uncaught = new ArrayList<String>();
      Map<String, List<String>> caughtInside = new LinkedHashMap<String, List<String>>();
      for (String limit : reachableLimits(properties)) {
        ActivityImpl handler = firstHandler(definition, activity, limit);
        if (handler == null) {
          uncaught.add(limit);
        } else if (!ActivityTypes.BOUNDARY_ERROR.equals(
            handler.getProperties().get(BpmnProperties.TYPE))) {
          if (!caughtInside.containsKey(handler.getId())) {
            caughtInside.put(handler.getId(), new ArrayList<String>());
          }
          caughtInside.get(handler.getId()).add(limit);
        }
      }
      // One refusal names every limit that does not reach a boundary event, so a model is not
      // deployed again only to learn of the next one.
      List<String> problems = new ArrayList<String>();
      if (!uncaught.isEmpty()) {
        problems.add("no boundary error event on it catches " + String.join(", ", uncaught)
            + ". Attach one that catches every error, or one for each limit's code; an error"
            + " nothing catches would end the scope silently.");
      }
      if (!caughtInside.isEmpty()) {
        List<String> handlers = new ArrayList<String>();
        for (Map.Entry<String, List<String>> inside : caughtInside.entrySet()) {
          handlers.add("the event sub process '" + inside.getKey() + "' inside it catches "
              + String.join(", ", inside.getValue()) + " first");
        }
        problems.add(String.join(", and ", handlers) + ". The engine offers an error to the event"
            + " sub processes inside a scope before any boundary event on it, whatever their"
            + " codes, so the limit would be handled inside the scope, which then ends on its"
            + " normal path. "
            + (caughtInside.size() == 1
                ? "Let '" + caughtInside.keySet().iterator().next() + "' catch"
                : "Let each of them catch")
            + " only its own error codes, none of the limits.");
      }
      if (!problems.isEmpty()) {
        Element scopeElement = elementWithId(processElement, activity.getId());
        throw new BpmnParseException("Ad hoc sub process '" + activity.getId() + "': " + ON_LIMIT
            + " is '" + AgenticOutcome.ON_LIMIT_ERROR + "', but " + String.join(" And ", problems),
            scopeElement == null ? processElement : scopeElement);
      }
    }
  }

  /** The limits a scope can reach: the total only where the entries share a conversation. */
  protected static List<String> reachableLimits(Map<String, String> properties) {
    List<String> limits = new ArrayList<String>(
        Arrays.asList(AgenticOutcome.TURN_LIMIT, AgenticOutcome.CALL_LIMIT));
    String total = properties.get(MAX_TURNS_TOTAL);
    if (AdHocConversation.PER_ACTIVITY.equals(String.valueOf(properties.get(CONVERSATION)).trim())
        && total != null && !total.trim().isEmpty()) {
      limits.add(AgenticOutcome.TOTAL_TURN_LIMIT);
    }
    return limits;
  }

  /**
   * The handler the engine gives the code to when the scope throws it, or null when none on the
   * scope catches it: the first of the scope's error handlers that catches the code, as
   * {@code ErrorDeclarationForProcessInstanceFinder} picks it. The list holds the boundary error
   * events on the scope and the error start events of the event sub processes inside it, sorted
   * by precedence; an event sub process ranks above every boundary event, whatever their codes.
   */
  protected static ActivityImpl firstHandler(ProcessDefinitionEntity definition,
      ActivityImpl scope, String code) {
    List<ErrorEventDefinition> definitions =
        scope.getProperties().get(BpmnProperties.ERROR_EVENT_DEFINITIONS);
    for (ErrorEventDefinition errorDefinition : definitions) {
      if (errorDefinition.catchesError(code)) {
        return definition.findActivity(errorDefinition.getHandlerActivityId());
      }
    }
    return null;
  }

  protected static Element elementWithId(Element parent, String id) {
    for (Element child : parent.elements()) {
      if (id.equals(child.attribute("id"))) {
        return child;
      }
      Element found = elementWithId(child, id);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /**
   * The agent reads its task and prompt from its connector. The same input parameter on the scope
   * itself is refused: it would be evaluated once, on entry, into a variable any child can
   * rewrite, and the agent would never see it.
   */
  protected void refuseTheTaskOnTheScope(Element scopeElement, ActivityImpl activity) {
    for (String name : scopeInputParameters(scopeElement)) {
      if (TASK_PARAMETERS.contains(name)) {
        throw new BpmnParseException("Ad hoc sub process '" + activity.getId() + "': '" + name
            + "' is an input parameter of the scope, but the agent reads it from its connector."
            + " As an input parameter of the scope it would be evaluated once, on entry, into a"
            + " variable any child can rewrite. Move it into the input parameters of the"
            + " connector '" + AgenticTurnRunner.AGENT_CONNECTOR_ID + "'.", scopeElement);
      }
    }
  }

  /**
   * What the conversation settings of CIB7-2137 cannot mean, refused at deployment rather than
   * on the first turn of the first instance.
   *
   * <p>Four things: a mode that is not one of the two; a total turn limit on a scope whose
   * entries share no conversation, where it would bound nothing a per-entry limit does not bound
   * already; that same limit when it is not a positive integer; and a continued conversation on
   * a parallel multi-instance scope, whose instances run at once and would write one conversation
   * concurrently — the losers fail with an {@code OptimisticLockingException} and replay a model
   * call each. Sequential multi-instance iterations share the conversation and total cap;
   * later iterations are re-entries and use followUp on their first turn.
   */
  protected void refuseAnImpossibleConversation(Element scopeElement, ScopeImpl scope,
      ActivityImpl activity, Map<String, String> properties) {
    String mode = properties.get(CONVERSATION);
    String trimmed = (mode == null) ? null : mode.trim();
    boolean perActivity = AdHocConversation.PER_ACTIVITY.equals(trimmed);

    if (trimmed != null && !perActivity && !AdHocConversation.PER_ENTRY.equals(trimmed)) {
      throw new BpmnParseException("Ad hoc sub process '" + activity.getId() + "': '" + mode
          + "' is not a value of " + CONVERSATION + ". Use '" + AdHocConversation.PER_ENTRY
          + "', where every entry of the scope starts a new conversation, or '"
          + AdHocConversation.PER_ACTIVITY + "', where the conversation continues on every entry"
          + " of this scope within the scope enclosing it.", scopeElement);
    }

    String rawTotal = properties.get(MAX_TURNS_TOTAL);
    if (rawTotal != null && !rawTotal.trim().isEmpty()) {
      if (!perActivity) {
        throw new BpmnParseException("Ad hoc sub process '" + activity.getId() + "': "
            + MAX_TURNS_TOTAL + " bounds the turns of every entry together, which only has a"
            + " meaning when the entries share a conversation. Set " + CONVERSATION + " to '"
            + AdHocConversation.PER_ACTIVITY + "', or use " + MAX_TURNS + " instead, which bounds"
            + " one entry.", scopeElement);
      }
      // Unlike maxTurns, an invalid value here cannot fall back to a safe default: there is no
      // sensible total to substitute, so AdHocAgentState.positiveProperty's fallback of 0 reads
      // at runtime as "no total cap" -- the one direction a safety limit must never fail open
      // in. Refused here instead, where a mistyped value is cheap to fix.
      if (!isPositiveInteger(rawTotal)) {
        throw new BpmnParseException("Ad hoc sub process '" + activity.getId() + "': "
            + MAX_TURNS_TOTAL + " must be a positive integer, but is '" + rawTotal + "'. An"
            + " invalid value would silently disable the total cap at runtime instead of falling"
            + " back to one, unlike " + MAX_TURNS + ", which falls back to a safe default.",
            scopeElement);
      }
    }

    // Reserve the engine's full 64-character execution id without changing normal memory ids.
    int maxActivityIdLength = 255
        - org.cibseven.connect.ai.agent.AgentConnectorConstants.AGENT_CONNECTOR_MEMORY_PREFIX.length()
        - "adhoc-".length() - 64 - 1;
    if (perActivity && activity.getId().length() > maxActivityIdLength) {
      throw new BpmnParseException("Ad hoc sub process '" + activity.getId() + "': perActivity"
          + " conversation requires an activity id of at most " + maxActivityIdLength
          + " characters so the memory variable name fits the 255-character limit (reserving"
          + " 64 characters for the enclosing execution id). Shorten the activity id or use"
          + " perEntry.", scopeElement);
    }

    if (perActivity && scope instanceof ActivityImpl && ((ActivityImpl) scope)
        .getActivityBehavior() instanceof ParallelMultiInstanceActivityBehavior) {
      throw new BpmnParseException("Ad hoc sub process '" + activity.getId() + "': "
          + CONVERSATION + " '" + AdHocConversation.PER_ACTIVITY + "' is not supported on a"
          + " parallel multi-instance scope, because its instances run at the same time and"
          + " would write one conversation concurrently. Make the multi-instance sequential, or"
          + " use '" + AdHocConversation.PER_ENTRY + "'.", scopeElement);
    }
  }

  /** Whether {@code raw}, trimmed, parses as a positive {@code int}. */
  protected static boolean isPositiveInteger(String raw) {
    if (raw == null) {
      return false;
    }
    try {
      return Integer.parseInt(raw.trim()) > 0;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  /**
   * Pulls the {@code followUp} input parameter out of {@code io}, if the connector declares one,
   * and returns it -- so the per-step evaluation in {@code AgenticTurnRunner.askTheAgent} never
   * sees it. {@code io.getInputParameters()} is the live list {@code IoMapping} keeps, not a
   * copy, so removing from it here is enough.
   */
  protected static InputParameter extractFollowUp(IoMapping io) {
    if (io == null) {
      return null;
    }
    java.util.Iterator<InputParameter> inputs = io.getInputParameters().iterator();
    while (inputs.hasNext()) {
      InputParameter candidate = inputs.next();
      if (AgenticTurnRunner.FOLLOW_UP_PARAMETER.equals(candidate.getName())) {
        inputs.remove();
        return candidate;
      }
    }
    return null;
  }

  /** The agent's connector needs a task, and must leave the conversation to the loop. */
  protected void refuseAConnectorWithoutATask(Element scopeElement, ActivityImpl activity,
      IoMapping io) {
    List<String> inputs = new ArrayList<String>();
    InputParameter message = null;
    if (io != null) {
      for (InputParameter input : io.getInputParameters()) {
        inputs.add(input.getName());
        if ("message".equals(input.getName())) {
          message = input;
        }
      }
    }
    if (message == null) {
      throw new BpmnParseException("Ad hoc sub process '" + activity.getId()
          + "': an agentic scope needs a task for its agent. Add the input parameter 'message'"
          + " to the connector '" + AgenticTurnRunner.AGENT_CONNECTOR_ID + "'; it is evaluated"
          + " on every turn and may be assembled from process data, e.g. 'Handle order"
          + " ${orderNumber}.'", scopeElement);
    }
    // What the template writes until the field is filled in. It would deploy, and every turn
    // would then fail on the model's side with a request the agent cannot answer.
    if (message.getValueProvider() instanceof NullValueProvider) {
      throw new BpmnParseException("Ad hoc sub process '" + activity.getId()
          + "': an agentic scope needs a task for its agent, but the input parameter 'message'"
          + " of the connector '" + AgenticTurnRunner.AGENT_CONNECTOR_ID + "' is empty. A"
          + " modeler leaves it empty when the template is applied. Fill in the agent's task; it"
          + " is evaluated on every turn and may be assembled from process data, e.g. 'Handle"
          + " order ${orderNumber}.'", scopeElement);
    }
    // The scope's tools are its children. A class registered through toolClasses would run inside
    // the step, outside the scope, and would not return at once as every tool of a step must
    // (CIB7-2148); ProcessStarterTool, the bundled one, even commits on a thread of its own.
    if (inputs.contains("toolClasses")) {
      throw new BpmnParseException("Ad hoc sub process '" + activity.getId() + "': the input"
          + " parameter 'toolClasses' of the connector '" + AgenticTurnRunner.AGENT_CONNECTOR_ID
          + "' is not supported on an ad hoc sub process. Its tools are its own activities; model"
          + " what the class does as one of them, for example a service task or a call activity.",
          scopeElement);
    }
    for (String loopOwned : LOOP_PARAMETERS) {
      if (inputs.contains(loopOwned)) {
        throw new BpmnParseException("Ad hoc sub process '" + activity.getId() + "': the"
            + " input parameter '" + loopOwned + "' of the connector '"
            + AgenticTurnRunner.AGENT_CONNECTOR_ID + "' is set by the agentic loop itself, which"
            + " keeps one conversation per scope. Remove it from the connector.", scopeElement);
      }
    }
  }

  /** The {@code camunda:connectorId} of a connector element, or null. */
  protected static String connectorId(Element connector) {
    if (connector == null) {
      return null;
    }
    Element id = connector.element("connectorId");
    return (id == null) ? null : id.getText().trim();
  }

  protected static List<String> presentLimits(Map<String, String> properties) {
    return present(properties, LIMITS);
  }

  /** Which of {@code names} the scope carries, in the order they are declared. */
  protected static List<String> present(Map<String, String> properties, List<String> names) {
    List<String> present = new ArrayList<String>();
    for (String name : names) {
      if (properties.containsKey(name)) {
        present.add(name);
      }
    }
    return present;
  }

  /** What marks a scope as an agent, in words for a refusal. */
  protected static String marks(boolean template, List<String> limits, List<String> settings) {
    List<String> found = new ArrayList<String>();
    if (template) {
      found.add("the template '" + AD_HOC_TEMPLATE_NAME + "'");
    }
    if (!limits.isEmpty()) {
      found.add("the agent's limits " + String.join(", ", limits));
    }
    if (!settings.isEmpty()) {
      found.add("the agent's settings " + String.join(", ", settings));
    }
    return String.join(" and ", found);
  }

  /** The names of the scope's own {@code camunda:inputParameter}s, not those of a connector. */
  protected static List<String> scopeInputParameters(Element scopeElement) {
    List<String> names = new ArrayList<String>();
    Element inputOutput = BpmnParseUtil.findCamundaExtensionElement(scopeElement, "inputOutput");
    if (inputOutput != null) {
      for (Element parameter : inputOutput.elementsNS(BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS,
          "inputParameter")) {
        names.add(parameter.attribute("name"));
      }
    }
    return names;
  }

  /** The parking condition: an agentic scope ends through its handler, never on its own. */
  public static class NeverCondition implements Condition {
    @Override
    public boolean evaluate(DelegateExecution execution) {
      return false;
    }

    @Override
    public boolean evaluate(VariableScope scope, DelegateExecution execution) {
      return false;
    }

    @Override
    public boolean tryEvaluate(VariableScope scope, DelegateExecution execution) {
      return false;
    }
  }
}
