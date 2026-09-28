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
import org.cibseven.bpm.engine.impl.persistence.entity.JobEntity;
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

  public static final String DEFAULT_RESULT_VARIABLE = "agentOutput";

  /** Read by this class or by the tool, not passed on to the connector. */
  protected static final Set<String> RESERVED = new HashSet<String>(Arrays.asList(
      "enabled", "maxModelCalls", "maxTurns", "resultVariable"));

  private AgenticTurnRunner() {
  }

  public static void runTurn(ExecutionEntity scopeExecution, CommandContext commandContext) {
    Map<String, String> config = AgenticScopes.agenticConfig(scopeExecution);
    if (config.isEmpty()) {
      return;
    }

    // A request an earlier turn left pending, because work was still running then. Acting on
    // it here rather than asking again: the answer could not change anything, and a model
    // call is the expensive part of a turn.
    if (AdHocAgentState.isCompletionRequested(scopeExecution)) {
      endOfTurn(scopeExecution);
      return;
    }

    // The turn cap, before the model costs anything. Counted against the job's id, so a
    // failed turn and its retry are one turn, not two. Over the cap the scope ends with a
    // readable answer instead of asking a model whose loop plainly is not converging.
    int turn = AdHocAgentState.beginTurn(scopeExecution, turnId(commandContext));
    int maxTurns = AdHocAgentState.positiveProperty(scopeExecution,
        AdHocAgentState.MAX_TURNS_PROPERTY, AdHocAgentState.DEFAULT_MAX_TURNS);
    if (turn > maxTurns) {
      writeAnswer(scopeExecution, config, "The turn limit of " + maxTurns + " for this ad hoc"
          + " sub process was reached, so the scope was ended without asking the agent again.");
      Context.getProcessEngineConfiguration().getRuntimeService()
          .completeAdHocSubProcess(scopeExecution.getId());
      return;
    }

    String answer = askTheAgent(scopeExecution, config);
    writeAnswer(scopeExecution, config, answer);

    endOfTurn(scopeExecution);
  }

  protected static void writeAnswer(ExecutionEntity scopeExecution, Map<String, String> config,
      String answer) {
    String resultVariable = config.get("resultVariable");
    scopeExecution.setVariable(
        (resultVariable == null) ? DEFAULT_RESULT_VARIABLE : resultVariable, answer);
  }

  /** What tells one turn from the next: the turn job. Null outside one counts safely. */
  protected static String turnId(CommandContext commandContext) {
    JobEntity job = (commandContext == null) ? null : commandContext.getCurrentJob();
    return (job == null) ? null : "job:" + job.getId();
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

  /**
   * Two exits, decided by one question: is anything still running inside the scope?
   *
   * <p>Nothing is. Then the scope ends -- either the agent asked for that, or it did not but
   * started nothing either, and no further turn would ever be scheduled.
   *
   * <p>Something is. Then the scope stands and that child's end brings the next turn. This
   * holds even when the agent asked to end: ending here would cancel live work, which is the
   * very thing {@code completeScope} refuses for, and a child can still be started from
   * outside after the request was made. The request keeps, and the turn that child's end
   * schedules acts on it -- see {@link #runTurn}.
   */
  protected static void endOfTurn(ExecutionEntity scopeExecution) {
    if (!hasChildren(scopeExecution)) {
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
