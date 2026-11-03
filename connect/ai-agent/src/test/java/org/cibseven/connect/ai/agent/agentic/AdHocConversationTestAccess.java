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
package org.cibseven.connect.ai.agent.agentic;

import java.util.function.Supplier;

import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;

/**
 * Gives tests outside this package {@link AdHocConversation#drive}, which only the turn runner
 * calls, so that a change to its signature breaks them when they are compiled (CIB7-2156).
 */
public final class AdHocConversationTestAccess {

  private AdHocConversationTestAccess() {
  }

  /** Runs {@code call} as the agent that drives {@code scopeExecution}. */
  public static <T> T drive(ExecutionEntity scopeExecution, Supplier<T> call) {
    return AdHocConversation.drive(scopeExecution, call);
  }
}
