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
package org.cibseven.bpm.engine.impl.bpmn.behavior;

import org.cibseven.bpm.engine.impl.pvm.delegate.ActivityExecution;

/**
 * Told when a child of an ad hoc sub process ends or is removed, so that whatever decides what the
 * scope starts next can act on it.
 *
 * <p>The scope's own behaviour calls it, so it is not a listener a caller can switch off:
 * {@code skipCustomListeners} does not reach it. It is called once per performance of a direct child,
 * whatever the child is -- an activity, a chain of activities, an embedded or nested ad hoc scope, a
 * multi-instance body -- and never for what runs inside such a child. A fork inside a child, a
 * parallel gateway in its flow, is not supported yet: it breaks the scope's execution tree
 * (CIB7-2264). Not reported: children cancelled because the scope completes, is interrupted or is
 * cancelled; a child whose inner flow meets the completion condition, whose path is gathered as the
 * scope completes there; and a compensation handler of a child that runs after the scope has left.
 * It runs in the transaction that ends or removes the child. Registered on the behaviour by a parse
 * listener, so it is part of the deployed definition.
 *
 * <p>Migration: today the engine refuses to migrate a process instance while an ad hoc sub process in
 * it is active, so no migration calls an observer. Should that change, a migration that drops a child
 * of the scope, or moves one under another parent, reports it to {@link #childRemoved} before the
 * child's execution under the scope is removed. The scope execution may then still reference the
 * source definition, through its process definition or its activity, so what an observer creates from
 * it, such as a job, may belong to the source definition; the migration does not move it. Whether the
 * source's or the target's observers are called depends on how far the migration has got.
 */
public interface AdHocSubProcessObserver {

  /**
   * A child ended, and its result, if the scope gathers, is already in the collection. Called before
   * the scope consults its completion condition and before the ended child is removed.
   */
  void childEnded(ActivityExecution scopeExecution, ActivityExecution endedExecution);

  /**
   * A child was removed without ending: cancelled by a process instance modification, or dropped or
   * moved by a migration (see above). After a modification the child's work has already been
   * cancelled: {@code removedExecution} has ended, is no longer among the scope's children, and its
   * variables are gone. Its id and its activity are still there, which tells which child it was;
   * read nothing else from it, and create nothing on it. A modification that cancels the scope's
   * only child cancels the scope with it, and nothing is reported.
   */
  void childRemoved(ActivityExecution scopeExecution, ActivityExecution removedExecution);
}
