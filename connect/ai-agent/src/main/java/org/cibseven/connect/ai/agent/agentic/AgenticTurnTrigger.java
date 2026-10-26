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

import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocSubProcessObserver;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.delegate.ActivityExecution;
import org.cibseven.connect.ai.agent.impl.AdHocResults;

/**
 * Records what a child of an agentic scope produced as it ends, and gives the scope a further turn
 * when one of its children has ended or has been removed.
 *
 * <p>The record is taken here, in the one moment the child's declared variables still hold what
 * this performance produced, and kept on the agent state (CIB7-2147): not through the scope's
 * {@code outputCollection}, which belongs to the modeller, and not in a process variable, which
 * every tool and every other agent in the process could read and rewrite.
 *
 * <p>Told by the scope's own behaviour, not by an END listener on each child. The listener missed
 * three cases (CIB7-2140): a tool with {@code asyncAfter}, whose END fires before its continuation,
 * while the turn that started it is still running, so nothing was scheduled when it really ended; a
 * nested ad hoc scope as a tool, whose end the listener attributed to the inner scope; and
 * {@code skipCustomListeners}, which skips any listener. The behaviour reports a child once, when its
 * execution leaves the scope, which is also the end of a chain.
 *
 * <p>Runs in the child's transaction and does no more than insert a job row, so completing a user
 * task waits for an INSERT rather than for a language model.
 */
public class AgenticTurnTrigger implements AdHocSubProcessObserver {

  @Override
  public void childEnded(ActivityExecution scopeExecution, ActivityExecution endedExecution) {
    ExecutionEntity scope = (ExecutionEntity) scopeExecution;
    if (!scope.isEventScope()) {
      AdHocResults.gather(scope, (ExecutionEntity) endedExecution);
    }
    schedule(scopeExecution);
  }

  /** A tool cancelled from outside is news for the agent too: it may have been waiting for it. */
  @Override
  public void childRemoved(ActivityExecution scopeExecution, ActivityExecution removedExecution) {
    schedule(scopeExecution);
  }

  protected void schedule(ActivityExecution scopeExecution) {
    ExecutionEntity scope = (ExecutionEntity) scopeExecution;
    // What runs on an event scope execution is compensation of a scope that has already left;
    // no agent drives it any more.
    if (scope.isEventScope()) {
      return;
    }
    AgenticTurnScheduler.scheduleTurn(scope);
  }
}
