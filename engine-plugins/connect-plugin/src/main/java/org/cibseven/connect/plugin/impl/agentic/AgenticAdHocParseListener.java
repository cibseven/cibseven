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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.ExecutionListener;
import org.cibseven.bpm.engine.delegate.VariableScope;
import org.cibseven.bpm.engine.impl.Condition;
import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocSubProcessActivityBehavior;
import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocToolDescriptor;
import org.cibseven.bpm.engine.impl.bpmn.helper.BpmnProperties;
import org.cibseven.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParseUtil;
import org.cibseven.bpm.engine.impl.core.model.PropertyKey;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.impl.pvm.process.ScopeImpl;
import org.cibseven.bpm.engine.impl.util.xml.Element;

/**
 * Turns an ad hoc sub process carrying {@code cibseven.agentic.enabled} into one whose turns are
 * driven by a job: parks it, records its configuration, and attaches the listeners that schedule
 * the turns.
 *
 * <p>Registered as a <em>post</em> parse listener. It needs the behaviour, the children and their
 * sequence flows, none of which exist while the scope is being built.
 */
public class AgenticAdHocParseListener extends AbstractBpmnParseListener {

  public static final String PROPERTY_PREFIX = "cibseven.agentic.";
  public static final String ENABLED = PROPERTY_PREFIX + "enabled";

  /** The {@code cibseven.agentic.*} properties, read at parse time so no child can rewrite them. */
  public static final PropertyKey<Map<String, String>> AGENTIC_CONFIG =
      new PropertyKey<Map<String, String>>("agenticConfig");

  @Override
  public void parseSubProcess(Element scopeElement, ScopeImpl scope, ActivityImpl activity) {
    Map<String, String> properties = BpmnParseUtil.parseCamundaExtensionProperties(scopeElement);
    if (properties == null || !Boolean.parseBoolean(properties.get(ENABLED))) {
      return;
    }

    // Reached only by a model that asked to be agentic, so this is a modelling error rather than
    // a case to skip.
    if (!(activity.getActivityBehavior() instanceof AdHocSubProcessActivityBehavior)) {
      throw new ProcessEngineException("'" + activity.getId() + "': " + ENABLED
          + " is only valid on an adHocSubProcess.");
    }
    AdHocSubProcessActivityBehavior behavior =
        (AdHocSubProcessActivityBehavior) activity.getActivityBehavior();

    if (behavior.hasCompletionCondition()) {
      throw new ProcessEngineException("Ad hoc sub process '" + activity.getId()
          + "': an agentic scope decides its own end, so it must not carry a completionCondition.");
    }

    List<String> startable = activity.getProperties().get(BpmnProperties.AD_HOC_STARTABLE_ACTIVITIES);
    if (startable == null || startable.isEmpty()) {
      throw new ProcessEngineException("Ad hoc sub process '" + activity.getId()
          + "': an agentic scope needs at least one directly startable child, but every child is"
          + " the target of a sequence flow inside the scope.");
    }

    // The agent needs a task. Dynamic, from process data: the 'instruction' input
    // parameter (CIB7-1890 split). Static: the message property. Refused here, where
    // fixing the model is cheap, rather than on the first turn of the first instance.
    if (!declaresInstruction(scopeElement) && blank(properties.get("cibseven.agentic.message"))) {
      throw new ProcessEngineException("Ad hoc sub process '" + activity.getId()
          + "': an agentic scope needs a task for its agent. Declare a camunda:inputParameter"
          + " 'instruction' (may be assembled from process data), or the property"
          + " cibseven.agentic.message for a task that is fixed at parse time.");
    }

    // Parking. Without it the engine ends the scope as soon as no child is active, and a pending
    // job is not a child -- the scope would be gone before the turn ran.
    behavior.setCompletionCondition(new NeverCondition());

    // Results are gathered per performance, as each child ends. That is the one moment the
    // child's declared variables still hold what THIS run produced; reading them at the start
    // of the next turn shows only what the last performance left.
    if (behavior.getOutputCollectionName() == null) {
      behavior.setOutputCollectionName(AgenticToolResultExpression.COLLECTION);
      behavior.setOutputElement(new AgenticToolResultExpression());
    }

    activity.getProperties().set(AGENTIC_CONFIG, agenticConfig(properties));
    // The whole map under the engine's own key as well, so anything that reads a scope's
    // camunda:property at runtime finds it. The parser sets this key only for an external
    // service task, and the tool's caps sit on the scope.
    activity.getProperties().set(BpmnProperties.EXTENSION_PROPERTIES, properties);

    // The tool catalogue: one descriptor per startable child, built here so a broken
    // declaration refuses the deployment rather than a turn.
    activity.getProperties().set(AdHocToolDescriptor.CATALOG,
        AgenticToolCatalogParser.parse(scopeElement, startable));

    activity.addListener(ExecutionListener.EVENTNAME_START, new AgenticAdHocStartListener());

    for (ActivityImpl child : activity.getActivities()) {
      // Only the end of a chain. A child with an outgoing flow does not end when its activity
      // does -- the execution moves on -- so the agent would wake on a half-finished chain.
      if (child.getOutgoingTransitions().isEmpty()) {
        child.addListener(ExecutionListener.EVENTNAME_END, new AgenticAdHocEndListener());
      }
    }
  }

  /** Whether the scope declares the {@code instruction} input parameter. */
  protected boolean declaresInstruction(Element scopeElement) {
    Element inputOutput = BpmnParseUtil.findCamundaExtensionElement(scopeElement, "inputOutput");
    if (inputOutput == null) {
      return false;
    }
    for (Element parameter : inputOutput.elementsNS(
        org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS,
        "inputParameter")) {
      if (AgenticTurnRunner.INSTRUCTION_PARAMETER.equals(parameter.attribute("name"))) {
        return true;
      }
    }
    return false;
  }

  protected static boolean blank(String value) {
    return value == null || value.trim().isEmpty();
  }

  protected Map<String, String> agenticConfig(Map<String, String> properties) {
    Map<String, String> config = new HashMap<String, String>();
    for (Map.Entry<String, String> property : properties.entrySet()) {
      if (property.getKey() != null && property.getKey().startsWith(PROPERTY_PREFIX)) {
        config.put(property.getKey().substring(PROPERTY_PREFIX.length()), property.getValue());
      }
    }
    return Collections.unmodifiableMap(config);
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
