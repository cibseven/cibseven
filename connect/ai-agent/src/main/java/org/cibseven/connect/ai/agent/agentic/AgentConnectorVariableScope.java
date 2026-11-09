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

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.impl.core.variable.CoreVariableInstance;
import org.cibseven.bpm.engine.impl.core.variable.scope.AbstractVariableScope;
import org.cibseven.bpm.engine.impl.core.variable.scope.SimpleVariableInstance;
import org.cibseven.bpm.engine.impl.core.variable.scope.SimpleVariableInstance.SimpleVariableInstanceFactory;
import org.cibseven.bpm.engine.impl.core.variable.scope.VariableInstanceFactory;
import org.cibseven.bpm.engine.impl.core.variable.scope.VariableInstanceLifecycleListener;
import org.cibseven.bpm.engine.impl.core.variable.scope.VariableStore;
import org.cibseven.connect.spi.ConnectorRequest;

/**
 * The connector's input or output parameters of one turn, held apart from the scope.
 *
 * <p>The input parameters of the agent's connector are evaluated into this scope on every turn,
 * against the scope execution, and handed to the request from here. Nothing of it is stored: a
 * child cannot rewrite the task, and a changed process variable reaches the next turn. The output
 * parameters read the response from here and write to the scope execution, as a service task's
 * connector does.
 *
 * <p>The same thing the connect plugin keeps for a service task's connector. It is not that class
 * because this module must not require the connect plugin on its class path at runtime.
 */
public class AgentConnectorVariableScope extends AbstractVariableScope {

  private static final long serialVersionUID = 1L;

  protected final AbstractVariableScope parent;

  protected final VariableStore<SimpleVariableInstance> variableStore =
      new VariableStore<SimpleVariableInstance>();

  public AgentConnectorVariableScope(AbstractVariableScope parent) {
    this.parent = parent;
  }

  /** The name a script among the parameters finds this scope under, as on a service task. */
  @Override
  public String getVariableScopeKey() {
    return "connector";
  }

  @Override
  @SuppressWarnings({ "unchecked", "rawtypes" })
  protected VariableStore<CoreVariableInstance> getVariableStore() {
    return (VariableStore) variableStore;
  }

  @Override
  @SuppressWarnings({ "unchecked", "rawtypes" })
  protected VariableInstanceFactory<CoreVariableInstance> getVariableInstanceFactory() {
    return (VariableInstanceFactory) SimpleVariableInstanceFactory.INSTANCE;
  }

  @Override
  protected List<VariableInstanceLifecycleListener<CoreVariableInstance>> getVariableInstanceLifecycleListeners() {
    return Collections.emptyList();
  }

  @Override
  public AbstractVariableScope getParentVariableScope() {
    return parent;
  }

  /** Every evaluated input parameter becomes a request parameter of the same name. */
  public void writeTo(ConnectorRequest<?> request) {
    for (CoreVariableInstance variable : variableStore.getVariables()) {
      request.setRequestParameter(variable.getName(), variable.getTypedValue(true).getValue());
    }
  }

  /** The response parameters, for the output parameters to read. */
  public void read(Map<String, Object> responseParameters) {
    for (Map.Entry<String, Object> entry : responseParameters.entrySet()) {
      setVariableLocal(entry.getKey(), entry.getValue());
    }
  }
}
