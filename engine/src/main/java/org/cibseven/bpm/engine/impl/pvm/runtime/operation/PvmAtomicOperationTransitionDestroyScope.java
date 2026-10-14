/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
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
package org.cibseven.bpm.engine.impl.pvm.runtime.operation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.impl.ProcessEngineLogger;
import org.cibseven.bpm.engine.impl.pvm.PvmActivity;
import org.cibseven.bpm.engine.impl.pvm.PvmLogger;
import org.cibseven.bpm.engine.impl.pvm.PvmTransition;
import org.cibseven.bpm.engine.impl.pvm.delegate.ForkObserverBehavior;
import org.cibseven.bpm.engine.impl.pvm.runtime.LegacyBehavior;
import org.cibseven.bpm.engine.impl.pvm.runtime.OutgoingExecution;
import org.cibseven.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;


/**
 * @author Tom Baeyens
 * @author Daniel Meyer
 * @author Thorben Lindhauer
 */
public class PvmAtomicOperationTransitionDestroyScope implements PvmAtomicOperation {

  private final static PvmLogger LOG = ProcessEngineLogger.PVM_LOGGER;

  public boolean isAsync(PvmExecutionImpl instance) {
    return false;
  }

  public boolean isAsyncCapable() {
    return false;
  }

  public void execute(PvmExecutionImpl execution) {

    // calculate the propagating execution
    PvmExecutionImpl propagatingExecution = execution;

    PvmActivity activity = execution.getActivity();
    List<PvmTransition> transitionsToTake = execution.getTransitionsToTake();
    execution.setTransitionsToTake(null);

    // check whether the current scope needs to be destroyed
    if (execution.isScope() && activity.isScope()) {

      if (!LegacyBehavior.destroySecondNonScope(execution)) {
        if (execution.isConcurrent()) {
          // legacy behavior
          LegacyBehavior.destroyConcurrentScope(execution);
        }
        else {
          propagatingExecution = execution.getParent();
          LOG.debugDestroyScope(execution, propagatingExecution);
          execution.destroy();
          propagatingExecution.setActivity(execution.getActivity());
          propagatingExecution.setTransition(execution.getTransition());
          propagatingExecution.setActive(true);
          execution.remove();
        }
      }

    } else {
      // activity is not scope => nothing to do
      propagatingExecution = execution;
    }

    // take the specified transitions
    if (transitionsToTake.isEmpty()) {
      throw new ProcessEngineException(execution.toString() + ": No outgoing transitions from "
          + "activity " + activity);
    }
    else if (transitionsToTake.size() == 1) {
      // CIB7-2264: an activity that can fork tells a scope that observes forks also when it takes a
      // single flow, so that a join does not count the path with other activations
      ForkObserverBehavior forkObserver = propagatingExecution.getForkObserver();
      if (forkObserver != null && activity.getOutgoingTransitions().size() > 1) {
        forkObserver.branchesForked(propagatingExecution.getParent(), propagatingExecution,
            Collections.<PvmExecutionImpl>emptyList());
      }
      propagatingExecution.setTransition(transitionsToTake.get(0));
      propagatingExecution.take();
    }
    else {
      propagatingExecution.inactivate();

      List<OutgoingExecution> outgoingExecutions = new ArrayList<OutgoingExecution>();
      List<PvmExecutionImpl> branches = new ArrayList<PvmExecutionImpl>();

      for (int i = 0; i < transitionsToTake.size(); i++) {
        PvmTransition transition = transitionsToTake.get(i);

        PvmExecutionImpl scopeExecution = propagatingExecution.isScope() ?
            propagatingExecution : propagatingExecution.getParent();

        // reuse concurrent, propagating execution for first transition
        PvmExecutionImpl concurrentExecution = null;
        if (i == 0) {
          concurrentExecution = propagatingExecution;
        }
        else {
          concurrentExecution = scopeExecution.createConcurrentExecution();
          branches.add(concurrentExecution);

          if (i == 1 && !propagatingExecution.isConcurrent()) {
            outgoingExecutions.remove(0);
            // get a hold of the concurrent execution that replaced the scope propagating execution
            PvmExecutionImpl replacingExecution = null;
            for (PvmExecutionImpl concurrentChild : scopeExecution.getNonEventScopeExecutions())  {
              if (!(concurrentChild == propagatingExecution)) {
                replacingExecution = concurrentChild;
                break;
              }
            }

            outgoingExecutions.add(new OutgoingExecution(replacingExecution, transitionsToTake.get(0)));
          }
        }

        outgoingExecutions.add(new OutgoingExecution(concurrentExecution, transition));
      }

      // CIB7-2264: a scope that observes forks learns of this one before any branch moves, so a
      // join a branch reaches at once already tells the branches apart
      ForkObserverBehavior forkObserver = propagatingExecution.getForkObserver();
      if (forkObserver != null) {
        forkObserver.branchesForked(propagatingExecution.getParent(), propagatingExecution, branches);
      }

      // start executions in reverse order (order will be reversed again in command context with the effect that they are
      // actually be started in correct order :) )
      Collections.reverse(outgoingExecutions);

      for (OutgoingExecution outgoingExecution : outgoingExecutions) {
        outgoingExecution.take();
      }
    }

  }

  public String getCanonicalName() {
    return "transition-destroy-scope";
  }
}
