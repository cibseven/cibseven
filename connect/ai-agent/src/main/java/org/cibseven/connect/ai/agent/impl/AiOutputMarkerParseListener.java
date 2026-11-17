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

import java.util.concurrent.atomic.AtomicBoolean;

import org.cibseven.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.impl.pvm.process.ScopeImpl;
import org.cibseven.bpm.engine.impl.util.xml.Element;
import org.cibseven.connect.ai.agent.agentic.AgenticTurnRunner;
import org.cibseven.connect.plugin.impl.ServiceTaskConnectorActivityBehavior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Has the AI agent connector store its AI-output marker through {@link AiOutputMarker}
 * (CIB7-2819) wherever the connect plugin runs a connector: on a service task, send task or
 * business rule task, and on a throwing message event.
 *
 * <p>The engine applies such a connector's output parameters itself, and EL hands the marker over
 * as a plain map, so the connector cannot choose how it is stored. The connect plugin's parse
 * listener, which runs before this one, has set the connector's behaviour with its input and
 * output parameters; this one adapts the parameter that maps the marker as it is. The agentic ad
 * hoc sub process does the same for its own connector when it parses the scope.
 */
public class AiOutputMarkerParseListener extends AbstractBpmnParseListener {

  private static final Logger LOG = LoggerFactory.getLogger(AiOutputMarkerParseListener.class);

  /** Whether the warning about an older connect plugin has been logged. */
  private static final AtomicBoolean OLDER_CONNECT_PLUGIN_LOGGED = new AtomicBoolean();

  @Override
  public void parseServiceTask(Element element, ScopeImpl scope, ActivityImpl activity) {
    storeTheMarkerOf(activity);
  }

  @Override
  public void parseSendTask(Element element, ScopeImpl scope, ActivityImpl activity) {
    storeTheMarkerOf(activity);
  }

  @Override
  public void parseBusinessRuleTask(Element element, ScopeImpl scope, ActivityImpl activity) {
    storeTheMarkerOf(activity);
  }

  @Override
  public void parseEndEvent(Element element, ScopeImpl scope, ActivityImpl activity) {
    storeTheMarkerOf(activity);
  }

  @Override
  public void parseIntermediateThrowEvent(Element element, ScopeImpl scope,
      ActivityImpl activity) {
    storeTheMarkerOf(activity);
  }

  /** Adapts the marker's output parameter when {@code activity} runs the AI agent connector. */
  protected void storeTheMarkerOf(ActivityImpl activity) {
    if (!(activity.getActivityBehavior() instanceof ServiceTaskConnectorActivityBehavior)) {
      return;
    }
    ServiceTaskConnectorActivityBehavior behavior =
        (ServiceTaskConnectorActivityBehavior) activity.getActivityBehavior();
    try {
      if (AgenticTurnRunner.AGENT_CONNECTOR_ID.equals(behavior.getConnectorId())) {
        AiOutputMarker.storeTheMarkerOf(behavior.getIoMapping());
      }
    } catch (NoSuchMethodError olderConnectPlugin) {
      // The two jars come from one build in every distribution. An older connect plugin beside
      // this connector leaves the marker in the engine's default format rather than fail a
      // deployment.
      if (OLDER_CONNECT_PLUGIN_LOGGED.compareAndSet(false, true)) {
        LOG.warn("The connect plugin is older than the AI agent connector, so the AI-output"
            + " marker of '{}' and of every other task is stored in the engine's default format,"
            + " which may not read it back: {}", activity.getId(), olderConnectPlugin.toString());
      }
    }
  }
}
