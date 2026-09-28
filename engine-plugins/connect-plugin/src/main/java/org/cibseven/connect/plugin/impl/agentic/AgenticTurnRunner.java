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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocAgentState;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;
import org.cibseven.connect.Connectors;
import org.cibseven.connect.spi.Connector;
import org.cibseven.connect.spi.ConnectorRequest;
import org.cibseven.connect.spi.ConnectorResponse;

/**
 * Runs one turn: asks the agent what to do next and acts on what it did.
 *
 * <p>The agent is reached through the connector registry rather than through a compile-time
 * dependency, the same way a service task with {@code camunda:connector} reaches it.
 */
public final class AgenticTurnRunner {

  public static final String AGENT_CONNECTOR_ID = "cibseven-ai-agent";

  /** The tool through which the agent starts and finishes the children of its scope. */
  public static final String AD_HOC_TOOL_CLASS =
      "org.cibseven.connect.ai.agent.impl.AdHocSubProcessTool";

  public static final String DEFAULT_RESULT_VARIABLE = "agentOutput";

  /** Read by this class, not passed on to the connector. */
  protected static final Set<String> RESERVED = new HashSet<String>(Arrays.asList(
      "enabled", "maxModelCalls", "resultVariable"));

  private AgenticTurnRunner() {
  }

  public static void runTurn(ExecutionEntity scopeExecution, CommandContext commandContext) {
    Map<String, String> config = AgenticScopes.agenticConfig(scopeExecution);
    if (config.isEmpty()) {
      return;
    }

    String answer = askTheAgent(scopeExecution, config);

    String resultVariable = config.get("resultVariable");
    scopeExecution.setVariable(
        (resultVariable == null) ? DEFAULT_RESULT_VARIABLE : resultVariable, answer);

    endOfTurn(scopeExecution);
  }

  protected static String askTheAgent(ExecutionEntity scopeExecution, Map<String, String> config) {
    Connector<ConnectorRequest<?>> connector = Connectors.getConnector(AGENT_CONNECTOR_ID);
    if (connector == null) {
      throw new ProcessEngineException("Ad hoc sub process '" + scopeExecution.getActivityId()
          + "' is agentic, but no connector '" + AGENT_CONNECTOR_ID + "' is registered.");
    }

    ConnectorRequest<?> request = connector.createRequest();
    for (Map.Entry<String, String> entry : config.entrySet()) {
      if (!RESERVED.contains(entry.getKey())) {
        request.setRequestParameter(entry.getKey(), entry.getValue());
      }
    }
    request.setRequestParameter("toolClasses", withAdHocTool(config.get("toolClasses")));
    request.setRequestParameter("useChatMemory", Boolean.TRUE);
    request.setRequestParameter("memoryId", "adhoc-" + scopeExecution.getId());
    // The connector refuses a request without a name. The scope's id is one the modeller
    // already chose, and it says which scope a log line belongs to.
    if (config.get("agentName") == null) {
      request.setRequestParameter("agentName", scopeExecution.getActivityId());
    }

    // The tool finds its scope through the thread's BPMN execution context, which a job does not
    // set. Without this the tool refuses, saying no BPMN execution is available.
    Context.setExecutionContext(scopeExecution);
    try {
      ConnectorResponse response = request.execute();
      Object output = response.getResponseParameters().get("output");
      return (output == null) ? null : output.toString();
    } finally {
      Context.removeExecutionContext();
    }
  }

  protected static String withAdHocTool(String configured) {
    if (configured == null || configured.trim().isEmpty()) {
      return AD_HOC_TOOL_CLASS;
    }
    return configured.contains(AD_HOC_TOOL_CLASS) ? configured : configured + "," + AD_HOC_TOOL_CLASS;
  }

  /**
   * Three exits. The scope ends when the agent asked for it, and when it did not but started
   * nothing either -- no further turn would ever be scheduled. Otherwise the children it started
   * run, and their end listener brings the next turn.
   */
  protected static void endOfTurn(ExecutionEntity scopeExecution) {
    boolean requested = AdHocAgentState.isCompletionRequested(scopeExecution);
    if (requested || !hasChildren(scopeExecution)) {
      Context.getProcessEngineConfiguration().getRuntimeService()
          .completeAdHocSubProcess(scopeExecution.getId());
    }
  }

  protected static boolean hasChildren(ExecutionEntity scopeExecution) {
    for (PvmExecutionImpl child : scopeExecution.getNonEventScopeExecutions()) {
      if (child != null) {
        return true;
      }
    }
    return false;
  }
}
