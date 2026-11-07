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

import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocSubProcessActivityBehavior;
import org.cibseven.bpm.engine.impl.core.variable.mapping.InputParameter;
import org.cibseven.bpm.engine.impl.core.variable.mapping.IoMapping;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.PvmActivity;
import org.cibseven.bpm.engine.impl.pvm.process.ScopeImpl;

/** Finding an ad hoc scope from an execution, and reading what the parse listener left on it. */
public final class AgenticScopes {

  private AgenticScopes() {
  }

  /**
   * The innermost ad hoc scope at or above {@code execution}, or null.
   *
   * <p>One implementation, in {@link AdHocAgentState}. There were two identical ones for a
   * while, and callers picked whichever they had imported.
   */
  public static ExecutionEntity findAdHocScope(ExecutionEntity execution) {
    return AdHocAgentState.findAdHocScope(execution);
  }

  /**
   * Agentic is what the parse listener made agentic: it leaves the mapping of the agent's
   * connector on every such scope.
   */
  public static boolean isAgentic(ExecutionEntity scopeExecution) {
    return agentConnectorIo(scopeExecution) != null;
  }

  /** The input and output parameters of the agent's connector on the scope, or null. */
  public static IoMapping agentConnectorIo(ExecutionEntity scopeExecution) {
    PvmActivity activity = scopeExecution.getActivity();
    return (activity instanceof ScopeImpl)
        ? ((ScopeImpl) activity).getProperties().get(AgenticAdHocParseListener.AGENT_CONNECTOR_IO)
        : null;
  }

  /** The connector's {@code followUp} input parameter, held apart from every other one, or null. */
  public static InputParameter followUpInputParameter(ExecutionEntity scopeExecution) {
    PvmActivity activity = scopeExecution.getActivity();
    return (activity instanceof ScopeImpl)
        ? ((ScopeImpl) activity).getProperties().get(AgenticAdHocParseListener.FOLLOW_UP_INPUT)
        : null;
  }
}
