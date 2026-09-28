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

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.delegate.Expression;
import org.cibseven.bpm.engine.delegate.VariableScope;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.PvmActivity;
import org.cibseven.bpm.engine.impl.pvm.process.ScopeImpl;

/**
 * One entry of an agentic scope's result collection: what a single performance of a
 * tool produced.
 *
 * <p>The engine evaluates this once per completed child, against the child that ended
 * and in the one moment its declared variables still hold that performance's values —
 * the next performance of the same tool overwrites them. That timing is why the
 * collection can show two runs of one tool separately, which reading process variables
 * at the start of the next turn cannot.
 *
 * <p>An {@link Expression} rather than a registered EL function: the entry is built
 * from the parse-time catalogue, which is a Java object on the activity, so there is
 * nothing an expression language would add. It also keeps the collection out of reach
 * of a model that could otherwise name the function itself.
 *
 * <p>Returns {@code null} for a child that is not in the catalogue or declares no
 * results — the engine's way of saying this performance contributed nothing.
 */
public class AgenticToolResultExpression implements Expression, Serializable {

  private static final long serialVersionUID = 1L;

  /** The variable an agentic scope gathers into. Named so nothing else plausibly writes it. */
  public static final String COLLECTION = "cibsevenAgenticResults";

  /** Keys of one entry, so writer and reader cannot drift apart. */
  public static final String ACTIVITY_ID = "activityId";
  public static final String ACTIVATION_ID = "activationId";
  public static final String VALUES = "values";

  @Override
  public Object getValue(VariableScope variableScope) {
    if (!(variableScope instanceof ExecutionEntity)) {
      return null;
    }
    ExecutionEntity ended = (ExecutionEntity) variableScope;
    PvmActivity activity = ended.getActivity();
    if (activity == null) {
      return null;
    }

    ExecutionEntity scope = AdHocAgentState.findAdHocScope(ended);
    AdHocToolDescriptor descriptor = descriptorOf(scope, activity.getId());
    if (descriptor == null) {
      return null;
    }

    Map<String, Object> values = new LinkedHashMap<String, Object>();
    for (String name : descriptor.getResultVariables()) {
      // From the child that ended, so a value its own output mapping just wrote to the
      // scope is found by walking up, and a local one is found before it.
      values.put(name, ended.getVariable(name));
    }

    Map<String, Object> entry = new LinkedHashMap<String, Object>();
    entry.put(ACTIVITY_ID, activity.getId());
    // Which performance: the ending child's execution. Not its activity instance id --
    // by the time the scope is notified, that instance is resolved and the id read back
    // is the scope's own, the same for every entry. Measured: two performances of one
    // child both reported 'adHoc:8'. The execution is one performance's own and is also
    // one of the two handles the agent's pending list keys on.
    entry.put(ACTIVATION_ID, ended.getId());
    entry.put(VALUES, values);
    return entry;
  }

  protected AdHocToolDescriptor descriptorOf(ExecutionEntity scope, String activityId) {
    if (scope == null || scope.getActivity() == null) {
      return null;
    }
    List<AdHocToolDescriptor> catalog =
        ((ScopeImpl) scope.getActivity()).getProperties().get(AdHocToolDescriptor.CATALOG);
    if (catalog == null) {
      return null;
    }
    for (AdHocToolDescriptor descriptor : catalog) {
      if (descriptor.getActivityId().equals(activityId)) {
        return descriptor;
      }
    }
    return null;
  }

  @Override
  public void setValue(Object value, VariableScope variableScope) {
    throw new UnsupportedOperationException(
        "The agentic result collection is written by the engine, not assigned to.");
  }

  @Override
  public String getExpressionText() {
    return "toolResult()";
  }

  /** Not literal text: it is evaluated per ending child, which is the whole point. */
  @Override
  public boolean isLiteralText() {
    return false;
  }
}
