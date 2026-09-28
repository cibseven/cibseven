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
import java.util.Map;

import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocSubProcessActivityBehavior;
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
   * <p>Walks the parent chain rather than taking getParent() once, because a child that is itself
   * a scope hands its end to an execution one level below the ad hoc scope.
   */
  public static ExecutionEntity findAdHocScope(ExecutionEntity execution) {
    ExecutionEntity current = execution;
    while (current != null) {
      PvmActivity activity = current.getActivity();
      if (activity != null
          && activity.getActivityBehavior() instanceof AdHocSubProcessActivityBehavior) {
        return current;
      }
      current = current.getParent();
    }
    return null;
  }

  /** The cibseven.agentic.* properties of the scope, empty when the scope is not agentic. */
  public static Map<String, String> agenticConfig(ExecutionEntity scopeExecution) {
    PvmActivity activity = scopeExecution.getActivity();
    if (!(activity instanceof ScopeImpl)) {
      return Collections.emptyMap();
    }
    Map<String, String> config =
        ((ScopeImpl) activity).getProperties().get(AgenticAdHocParseListener.AGENTIC_CONFIG);
    return (config == null) ? Collections.<String, String>emptyMap() : config;
  }

  public static boolean isAgentic(ExecutionEntity scopeExecution) {
    return !agenticConfig(scopeExecution).isEmpty();
  }
}
