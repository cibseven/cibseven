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
package org.cibseven.bpm.engine.impl.pvm.delegate;

import java.util.List;

/**
 * The behavior of a scope whose execution stays on its own activity while each child it starts runs
 * on a concurrent execution of its own, as an ad hoc sub process does (CIB7-2264).
 *
 * <p>A child of such a scope that forks adds its branches beside every other child, so the
 * branches of two activations of the same child are siblings, and a join would count them together.
 * The scope is told of each fork and join among its children, and decides which branches a join may
 * merge.
 *
 * <p>The engine consults it only for a concurrent child of a scope execution that stands on the
 * scope's own activity.
 */
public interface ForkObserverBehavior extends ActivityBehavior {

  /**
   * A child of the scope forked: {@code forkingExecution} goes on along the first transition, and
   * {@code branches} along the others. Called before any of them takes its transition.
   *
   * <p>Also called, with no branches, when an activity with more than one outgoing flow takes a
   * single one of them, as an inclusive gateway or conditional flows do when one condition holds.
   */
  void branchesForked(ActivityExecution scopeExecution, ActivityExecution forkingExecution,
      List<? extends ActivityExecution> branches);

  /**
   * Whether a join that {@code arrivingExecution} reaches may count {@code otherExecution} with it:
   * one waiting at the join, or one that may still reach it. Either may be a child of the scope or
   * an execution below one.
   */
  boolean mayJoin(ActivityExecution scopeExecution, ActivityExecution arrivingExecution,
      ActivityExecution otherExecution);

  /**
   * A join merged {@code joinedExecutions}, and {@code continuingExecution}, one of them, goes on.
   * Called after the local variables of {@code continuingExecution} were removed and before the
   * others end.
   */
  void branchesJoined(ActivityExecution scopeExecution, ActivityExecution continuingExecution,
      List<? extends ActivityExecution> joinedExecutions);
}
