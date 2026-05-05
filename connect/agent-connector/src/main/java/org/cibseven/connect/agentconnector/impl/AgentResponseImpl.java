/*
 * Copyright CIB seven GmbH and/or licensed to CIB seven GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB seven licenses this file to you under the Apache License,
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
package org.cibseven.connect.agentconnector.impl;

import java.util.Map;

import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.connect.agentconnector.AgentConnector;
import org.cibseven.connect.agentconnector.AgentResponse;
import org.cibseven.connect.impl.AbstractConnectorResponse;

/**
 * Default implementation of {@link AgentResponse}.
 *
 * <p>Stores the agent output text returned by the connector execution and exposes it
 * both through the generic parameter map (for BPMN output variable mapping) and
 * through a typed getter method.
 */
public class AgentResponseImpl extends AbstractConnectorResponse implements AgentResponse {

  /** Camunda stores String variables in TEXT_ VARCHAR(4000); larger values need a different type. */
  private static final int CAMUNDA_STRING_LIMIT = 4000;

  private final String output;
  private final String memoryId;

  public AgentResponseImpl(String output) {
    this(output, "");
  }

  public AgentResponseImpl(String output, String memoryId) {
    this.output = output;
    this.memoryId = memoryId;
  }

  // ── AbstractConnectorResponse ──────────────────────────────────────────────

  @Override
  protected void collectResponseParameters(Map<String, Object> responseParameters) {
    // For outputs exceeding Camunda's VARCHAR(4000) TEXT_ column, store as a JSON-serialised
    // ObjectValue (written to ACT_GE_BYTEARRAY). Transparent to consumers: ${output} in BPMN
    // EL and execution.getVariable("output") in Java delegates still resolve to a String.
    Object outputValue = (output != null && output.length() > CAMUNDA_STRING_LIMIT)
        ? Variables.objectValue(output).serializationDataFormat("application/json").create()
        : output;
    responseParameters.put(AgentConnector.PARAM_NAME_OUTPUT, outputValue);

    // Always expose the memoryId key — even when chat memory is disabled and the value is null —
    // so BPMN output mappings such as <camunda:outputParameter name="memoryId">${memoryId}</camunda:outputParameter>
    // resolve cleanly to null instead of failing with PropertyNotFoundException.
    responseParameters.put(AgentConnector.PARAM_NAME_MEMORY_ID, memoryId);
  }

  // ── AgentResponse ──────────────────────────────────────────────────────────

  @Override
  public String getOutput() {
    return output;
  }

  @Override
  public String getMemoryId() {
    return memoryId;
  }

}
