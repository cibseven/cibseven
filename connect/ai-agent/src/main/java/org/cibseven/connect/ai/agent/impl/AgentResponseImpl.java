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

import java.util.Collections;
import java.util.Map;

import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.connect.ai.agent.AgentConnector;
import org.cibseven.connect.ai.agent.AgentResponse;
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
  private final Map<String, Object> outputAiMeta;

  public AgentResponseImpl(String output) {
    this(output, "", null);
  }

  public AgentResponseImpl(String output, String memoryId) {
    this(output, memoryId, null);
  }

  public AgentResponseImpl(String output, String memoryId, Map<String, Object> outputAiMeta) {
    this.output = output;
    this.memoryId = memoryId;
    this.outputAiMeta = (outputAiMeta == null) ? null
        : Collections.unmodifiableMap(new java.util.LinkedHashMap<>(outputAiMeta));
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

    // EU AI Act Art. 50(2) marker. Always present in the parameter map so the
    // BPMN ${outputAiMeta} expression resolves cleanly (to null when no model
    // response was observed). Map values flow through the engine as a plain
    // java.util.Map — accessible from EL as ${outputAiMeta.aiGenerated} etc.
    responseParameters.put(AgentConnector.PARAM_NAME_OUTPUT_AI_META, outputAiMeta);
  }

  // ── AgentResponse ──────────────────────────────────────────────────────────

  @Override
  public String getOutput() {
    return output;
  }

  @Override
  public Map<String, Object> getOutputAiMeta() {
    return outputAiMeta;
  }

  @Override
  public String getMemoryId() {
    return memoryId;
  }

}
