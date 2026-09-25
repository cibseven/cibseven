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
package org.cibseven.bpm.engine.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.impl.cmd.ActivateAdHocSubProcessActivitiesCmd;
import org.cibseven.bpm.engine.impl.interceptor.CommandExecutor;
import org.cibseven.bpm.engine.runtime.AdHocSubProcessActivationBuilder;

/**
 * Collects the performances and hands them to the command as two aligned lists.
 *
 * <p>The lists are built here rather than offered to callers, because keeping them aligned is the
 * whole point: a caller given two lists can get their lengths wrong, and a builder cannot.
 */
public class AdHocSubProcessActivationBuilderImpl implements AdHocSubProcessActivationBuilder {

  protected CommandExecutor commandExecutor;
  protected String executionId;

  protected List<String> activityIds = new ArrayList<String>();
  protected List<Map<String, Object>> variables = new ArrayList<Map<String, Object>>();

  public AdHocSubProcessActivationBuilderImpl(CommandExecutor commandExecutor, String executionId) {
    this.commandExecutor = commandExecutor;
    this.executionId = executionId;
  }

  @Override
  public AdHocSubProcessActivationBuilder startActivity(String activityId) {
    activityIds.add(activityId);
    variables.add(null);
    return this;
  }

  @Override
  public AdHocSubProcessActivationBuilder setVariable(String name, Object value) {
    currentVariables().put(name, value);
    return this;
  }

  @Override
  public AdHocSubProcessActivationBuilder setVariables(Map<String, Object> variables) {
    if (variables != null) {
      currentVariables().putAll(variables);
    }
    return this;
  }

  /**
   * The variable map of the performance named last, created on first use.
   *
   * <p>Refusing before any activity is named is deliberate. The alternative readings -- apply to
   * every performance, or to the next one -- are both defensible, which is exactly why a caller
   * should not have to guess which one was chosen.
   */
  protected Map<String, Object> currentVariables() {
    if (activityIds.isEmpty()) {
      throw new BadUserRequestException("Cannot set a variable before an activity has been named."
          + " Call startActivity first: variables belong to the performance named before them.");
    }
    int last = activityIds.size() - 1;
    Map<String, Object> current = variables.get(last);
    if (current == null) {
      current = new LinkedHashMap<String, Object>();
      variables.set(last, current);
    }
    return current;
  }

  @Override
  public List<String> execute() {
    return commandExecutor.execute(
        new ActivateAdHocSubProcessActivitiesCmd(executionId, activityIds, variables));
  }
}
