/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.cibseven.connect.ai.agent.impl;

/**
 * A tool call the agent may not make, phrased for the agent to read and act on.
 *
 * <p>The one exception an ad hoc tool hands to the model. "You already asked to end this
 * scope", "that parameter must be a number", "wait, something is still running" — each is
 * an answer, and a model that reads it can call again differently. The turn carries on.
 *
 * <p>Everything else a tool throws is a failure of the engine or of the deployment, and
 * the model can do nothing with it. Passing an {@code OptimisticLockingException} on as a
 * tool result told the model to carry on in a transaction already marked rollback-only:
 * the commit failed at the end of the turn, the job ran three times, every model call was
 * paid three times, and the incident carried the rollback and not the cause. Those are
 * rethrown instead, and end the turn where they happened — see
 * {@code AgentConnectorImpl.toolFailure}.
 *
 * <p>So the choice of type at a throw site is a decision about the reader: this class when
 * the model is meant to read it, {@link AgentConnectorException} when a person is. A
 * message naming {@code toolClasses} or a misplaced agent task belongs to the person —
 * the model cannot move an activity in the diagram.
 */
public class AdHocToolRefusedException extends AgentConnectorException {

  public AdHocToolRefusedException(String message) {
    super(message);
  }

}
