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

import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.ExecutionListener;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;

/**
 * Gives the scope a further turn when one of its children has finished.
 *
 * <p>Runs in the child's transaction and does no more than insert a job row, so completing a user
 * task waits for an INSERT rather than for a language model.
 */
public class AgenticAdHocEndListener implements ExecutionListener {

  @Override
  public void notify(DelegateExecution execution) {
    // Searched from the behaviour upwards, not through getParent(): a child that is itself a
    // scope sits one level deeper, and its parent is not the ad hoc scope.
    ExecutionEntity scope = AgenticScopes.findAdHocScope((ExecutionEntity) execution);
    if (scope != null) {
      AgenticTurnScheduler.scheduleTurn(scope);
    }
  }
}
