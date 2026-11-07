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
import org.cibseven.connect.ai.agent.impl.AdHocResults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * At the end of a tool that holds variables of its own (a scope, or each instance of a
 * multi-instance tool with a mapping or a call activity): carries the declared results it holds
 * there out of it, before its execution and its variables go (CIB7-2215). Built in, so
 * {@code skipCustomListeners} does not skip it; it only keeps values, the scope's observer still
 * brings the turn.
 */
public class AgenticToolResultCarrier implements ExecutionListener {

  private static final Logger LOG = LoggerFactory.getLogger(AgenticToolResultCarrier.class);

  /**
   * Never fails the end it listens to (review F8): that end may be a person completing a task,
   * and keeping values for the agent must not undo it. What could not be kept is then what a
   * lookup finds when the agent is told.
   */
  @Override
  public void notify(DelegateExecution execution) {
    if (!(execution instanceof ExecutionEntity)) {
      return;
    }
    try {
      AdHocResults.carry((ExecutionEntity) execution);
    } catch (RuntimeException e) {
      LOG.warn("Could not carry the results of '{}' out of its scope; the agent is told what a"
          + " lookup finds instead.", execution.getCurrentActivityId(), e);
    }
  }
}
