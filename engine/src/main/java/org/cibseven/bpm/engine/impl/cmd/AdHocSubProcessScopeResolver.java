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
package org.cibseven.bpm.engine.impl.cmd;

import java.util.ArrayList;
import java.util.List;

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocSubProcessActivityBehavior;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.PvmActivity;
import org.cibseven.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;

/**
 * Finds the ad hoc sub process scope an ad hoc command was addressed to (CIB7-1984).
 *
 * <p>Without multi-instance markers there is nothing to find: the caller passes the scope's own
 * execution and this returns it unchanged. With them there is one scope per item, and the caller
 * cannot pass that execution directly, because they have no way of recognising it.
 *
 * <p>The reason is worth stating, because it is the whole of CIB7-1984. The loop's element variable
 * and its {@code loopCounter} are written on the execution the multi-instance behaviour creates per
 * item, and entering the scope then creates a <em>child</em> of that execution and moves the
 * activity down to it. So the two things a caller needs sit one level apart: the activity id is on
 * the child, the variable that says <em>which item</em> is on the parent. {@code ExecutionQuery}
 * matches variables local to one execution, so no single query returns the scope execution, and a
 * query by activity id alone matches every instance equally.
 *
 * <p>Rather than adding a query that spans the two, this accepts the execution a caller can already
 * find. {@code ExecutionQuery.variableValueEquals("item", "...")} returns the per-item execution,
 * and this descends from it to the scope below. The activation itself never needed changing: it was
 * always precise about which execution it starts a child in, which is why an ad hoc scope under
 * multi-instance markers behaves correctly the moment it is allowed to deploy.
 *
 * <p>Descent is deliberately not offered from the process instance. An execution that is a process
 * instance keeps the refusal it has always had, so a caller who passes the instance id by mistake
 * is told what to pass instead rather than silently getting whichever scope happens to be the only
 * one. The one place ambiguity is possible -- the multi-instance body, which holds every instance --
 * is refused by name and by count.
 */
public final class AdHocSubProcessScopeResolver {

  private AdHocSubProcessScopeResolver() {
    // utility class
  }

  /**
   * The execution of the ad hoc sub process scope that {@code given} names.
   *
   * @param given the execution the caller passed
   * @param executionId the id as the caller wrote it, for the refusals
   * @throws BadUserRequestException when {@code given} names no scope, or more than one
   */
  public static ExecutionEntity resolve(ExecutionEntity given, String executionId) {
    if (isAdHocScope(given)) {
      // Measured: when one instance of a multi-instance ad hoc scope completes, its scope execution
      // is removed and the per-item execution above it takes the activity back, inactive, so that
      // multi-instance can count it against its cardinality. Such an execution still looks like an
      // ad hoc scope by activity, and is not one: it is a workspace that has closed.
      if (!given.isScope()) {
        throw new BadUserRequestException("Execution '" + executionId + "' names an instance of ad"
            + " hoc sub process '" + given.getActivity().getId() + "' that has already completed,"
            + " so nothing can be started in it.");
      }
      return given;
    }

    // Only an execution that carries no activity can stand above a scope: entering the scope is
    // what moved the activity down. A caller who passed something that is running an activity of
    // its own has not named a scope at all, and gets the refusal below.
    if (given.getActivity() == null && !given.isProcessInstanceExecution()) {
      List<ExecutionEntity> scopes = new ArrayList<ExecutionEntity>();
      collectScopes(given, scopes);

      if (scopes.size() == 1) {
        return scopes.get(0);
      }
      if (scopes.size() > 1) {
        PvmActivity activity = scopes.get(0).getActivity();
        throw new BadUserRequestException("Execution '" + executionId + "' holds " + scopes.size()
            + " instances of ad hoc sub process '" + activity.getId() + "', so it does not name one"
            + " of them. This is the multi-instance body. Name a single instance instead: query the"
            + " executions of this process instance for the loop's element variable, which is set on"
            + " the execution of each instance.");
      }
    }

    throw new BadUserRequestException("Execution '" + executionId
        + "' is not an ad hoc sub process instance, so no ad hoc activity can be started in it."
        + " Pass the execution of the ad hoc sub process scope itself.");
  }

  /**
   * Adds every ad hoc scope below {@code execution} to {@code found}, without descending into one.
   *
   * <p>Stopping at a scope is what keeps an ad hoc sub process nested inside another one out of the
   * answer: the caller named the outer one, and the inner ones are its children rather than
   * alternative readings of the same request.
   *
   * <p>An instance that has already completed is not counted. Its execution carries the scope's
   * activity again but is no longer a scope, so including it would report more instances than are
   * open and name a workspace the caller cannot act on.
   */
  protected static void collectScopes(ExecutionEntity execution, List<ExecutionEntity> found) {
    for (PvmExecutionImpl child : execution.getNonEventScopeExecutions()) {
      ExecutionEntity candidate = (ExecutionEntity) child;
      if (isAdHocScope(candidate) && candidate.isScope()) {
        found.add(candidate);
      } else if (candidate.getActivity() == null) {
        collectScopes(candidate, found);
      }
    }
  }

  protected static boolean isAdHocScope(ExecutionEntity execution) {
    PvmActivity activity = execution.getActivity();
    return activity != null
        && activity.getActivityBehavior() instanceof AdHocSubProcessActivityBehavior;
  }
}
