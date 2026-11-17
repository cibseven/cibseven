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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.impl.context.BpmnExecutionContext;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.variable.serializer.JavaObjectSerializer;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.ObjectValue;
import org.cibseven.bpm.engine.variable.value.TypedValue;
import org.cibseven.connect.ai.agent.AgentConnector;
import org.cibseven.connect.ai.agent.AgentResponse;
import org.cibseven.connect.ai.agent.agentic.AdHocAgentState;
import org.cibseven.connect.ai.agent.agentic.AgenticAdHocParseListener;
import org.cibseven.connect.ai.agent.agentic.AgenticTurnRunner;
import org.cibseven.connect.impl.AbstractConnectorResponse;
import org.cibseven.connect.spi.CloseableConnectorResponse;

/**
 * Default implementation of {@link AgentResponse}.
 *
 * <p>Stores the agent output text returned by the connector execution and exposes it
 * both through the generic parameter map (for BPMN output variable mapping) and
 * through a typed getter method.
 */
public class AgentResponseImpl extends AbstractConnectorResponse
    implements AgentResponse, CloseableConnectorResponse {

  /** Camunda stores String variables in TEXT_ VARCHAR(4000); larger values need a different type. */
  private static final int CAMUNDA_STRING_LIMIT = 4000;

  private final String output;
  private final String memoryId;
  private final Map<String, Object> outputAiMeta;

  /**
   * Set in a step of an agentic turn whose model called tools instead of answering (CIB7-2148):
   * the tools ran, and the turn goes on in a further step. Never set for a service task.
   */
  private final boolean stepCalledTools;

  public AgentResponseImpl(String output) {
    this(output, "", null);
  }

  public AgentResponseImpl(String output, String memoryId) {
    this(output, memoryId, null);
  }

  public AgentResponseImpl(String output, String memoryId, Map<String, Object> outputAiMeta) {
    this(output, memoryId, outputAiMeta, false);
  }

  public AgentResponseImpl(String output, String memoryId, Map<String, Object> outputAiMeta,
      boolean stepCalledTools) {
    this.stepCalledTools = stepCalledTools;
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

    // Only in a step of an agentic turn, for the turn runner: there is no answer yet.
    if (stepCalledTools) {
      responseParameters.put(AgenticTurnRunner.STEP_CALLED_TOOLS, Boolean.TRUE);
    }

    // EU AI Act Art. 50(2) marker. Always present in the parameter map so the
    // BPMN ${outputAiMeta} expression resolves cleanly (to null when no model
    // response was observed). Map values flow through the engine as a plain
    // java.util.Map — accessible from EL as ${outputAiMeta.aiGenerated} etc.
    // A LinkedHashMap rather than the unmodifiable view (CIB7-2819): stored as JSON under type
    // validation, Collections$UnmodifiableMap is not on the allow list. The variable that holds
    // the marker itself ends up Java-serialized after either activity (close() here, and
    // AgenticTurnRunner.writeAnswer).
    responseParameters.put(AgentConnector.PARAM_NAME_OUTPUT_AI_META,
        (outputAiMeta == null) ? null : new LinkedHashMap<>(outputAiMeta));
  }

  // ── CloseableConnectorResponse ─────────────────────────────────────────────

  /**
   * Once the engine has applied an AI agent service task's output parameters, stores the one that
   * holds the AI-output marker itself Java-serialized, as the agentic ad hoc sub process does
   * (CIB7-2819). Where JSON is the default format, the engine stores it as JSON, and Spin's map
   * type detector names the marker's type after its first value, a Boolean: the variable could not
   * be read back, nor could any read of all of the instance's variables.
   *
   * <p>The connect plugin calls this right after it applied the output parameters, before the task
   * is left, also after an output parameter failed; it logs an exception of this method and goes
   * on. The marker is found by identity among the connector's output parameters, whatever their
   * expression; a copy of it, or a map that holds it, stays as the engine stored it, and so does an
   * activity's own output parameter, which the engine applies after this. Nothing happens unless
   * JSON is the default format, outside a service task, where the agent's engine plugin did not
   * parse the task, or where the engine cannot store the marker Java-serialized.
   *
   * <p>The variable is written twice in one command: one more variable update for listeners and
   * conditional events, and at history level full one more history row. That first row keeps the
   * engine's JSON; under the map type detector it does not read back, while the variable's
   * history does.
   */
  @Override
  public void close() {
    Object marker = (responseParameters == null) ? null
        : responseParameters.get(AgentConnector.PARAM_NAME_OUTPUT_AI_META);
    BpmnExecutionContext context = Context.getBpmnExecutionContext();
    ExecutionEntity execution = (context == null) ? null : context.getExecution();
    // An output parameter that starts a non-interrupting event sub process of the instance makes
    // the engine replace the task's execution; the task goes on in the replacing one.
    while (execution != null && execution.getReplacedBy() != null) {
      execution = execution.getReplacedBy();
    }
    if (marker == null || execution == null || execution.getActivity() == null
        || !Variables.SerializationDataFormats.JSON.getName().equals(
            Context.getProcessEngineConfiguration().getDefaultSerializationFormat())
        || !javaSerializable(marker)) {
      return;
    }
    List<String> outputs = execution.getActivity().getProperties()
        .get(AgenticAdHocParseListener.AGENT_TASK_OUTPUTS);
    if (outputs == null) {
      return;
    }
    for (String name : outputs) {
      TypedValue stored = execution.getVariableTyped(name, false);
      if (stored instanceof ObjectValue && ((ObjectValue) stored).isDeserialized()
          && ((ObjectValue) stored).getValue() == marker) {
        execution.setVariable(name, AdHocAgentState.javaSerialized(marker));
      }
    }
  }

  /**
   * Whether the engine can store the marker Java-serialized. A write that failed would leave the
   * variable without its value: the engine clears the old value before it looks for the
   * serializer, and the connect plugin only logs the exception.
   */
  private static boolean javaSerializable(Object marker) {
    if (Context.getProcessEngineConfiguration().getVariableSerializers()
        .getSerializerByName(JavaObjectSerializer.NAME) == null) {
      return false;
    }
    try (ObjectOutputStream out = new ObjectOutputStream(new ByteArrayOutputStream())) {
      out.writeObject(marker);
      return true;
    } catch (IOException | RuntimeException e) {
      return false;
    }
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
