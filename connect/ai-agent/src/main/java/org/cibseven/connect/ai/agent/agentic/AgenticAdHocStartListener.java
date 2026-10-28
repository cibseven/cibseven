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

import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.ExecutionListener;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.process.ScopeImpl;

/**
 * Gives an agentic scope its state and its first turn when it is entered.
 *
 * <p>The state is created here, in the transaction that enters the scope, and not by whoever needs
 * it first. A tool started on entry, or from outside, can end while the first step waits for the
 * model; each would otherwise create a state of its own, and the agent would keep its conversation in
 * one and that tool's result in the other.
 */
public class AgenticAdHocStartListener implements ExecutionListener {

  @Override
  public void notify(DelegateExecution execution) {
    ExecutionEntity scopeExecution = (ExecutionEntity) execution;
    if (AgenticScopes.isAgentic(scopeExecution)) {
      AdHocAgentState.findOrCreate(scopeExecution);
      // An empty toolCall for a tool someone other than the agent starts (CIB7-2136): its input
      // mapping reads toolCall.<name> as null rather than failing. A call of the agent sets its
      // own, on the performance, which hides this one. Not where a toolCall is visible already:
      // a scope that is itself the tool of an outer agent sits under that call's performance, and
      // an empty map here would hide the outer call's arguments from its outputs and everything
      // inside it.
      if (AdHocToolDescriptor.anyParameters(((ScopeImpl) scopeExecution.getActivity())
          .getProperties().get(AdHocToolDescriptor.CATALOG))
          && !scopeExecution.hasVariable(AdHocToolDescriptor.TOOL_CALL_VARIABLE)) {
        scopeExecution.setVariableLocal(AdHocToolDescriptor.TOOL_CALL_VARIABLE,
            AdHocToolDescriptor.toolCallValue(Collections.<String, Object>emptyMap()));
      }
      // The conversation holder, for a scope that keeps its conversation across entries, and
      // this entry counted on it: the first turn runs in a job of its own and has to be able
      // to tell a re-entry from a first entry (CIB7-2137).
      AdHocConversation.enterScope(scopeExecution);
      // followUp, evaluated here and only here: once, in this transaction, and only when this
      // entry is a re-entry -- not on the first entry, whose result nothing would use, and not
      // again on whatever later job eventually runs the first turn.
      if (AdHocConversation.isReEntry(scopeExecution)) {
        AgenticTurnRunner.snapshotFollowUp(scopeExecution);
      }
    }
    AgenticTurnScheduler.scheduleTurn(scopeExecution);
  }
}
