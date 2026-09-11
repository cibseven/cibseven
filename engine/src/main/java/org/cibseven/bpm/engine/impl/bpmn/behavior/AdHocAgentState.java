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

import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.PvmActivity;
import org.cibseven.bpm.engine.impl.pvm.delegate.ActivityExecution;
import org.cibseven.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;

/**
 * Holds state belonging to a client that drives an ad hoc sub process — an agent's
 * pending activations, its turn count, its conversation — where the scope's own
 * children cannot reach it.
 *
 * <p>{@code setVariable} walks strictly up the parent chain, and the scope execution
 * is an ancestor of every ad hoc child, so state kept there is writable by all of
 * them. In an agentic scope the children <em>are</em> the tools, so they are the
 * untrusted party. The execution returned here is their <em>sibling</em> instead: a
 * child writing the same name creates its own variable at the process instance, and
 * the copy kept here is untouched. Event scope keeps it invisible to child
 * iteration, completion checks and delete cascade, none of which had to change.
 *
 * <p>Deliberately separate from the state execution
 * {@link AdHocSubProcessActivityBehavior} keeps for its activation counter, and this
 * class never recognises that one's variable names. Sharing would stop the
 * behaviour's {@code getActivatedCount} fallback from firing as soon as an agent
 * stored anything, and a scope without a completion condition would then never
 * complete again. The cost is one further execution per instance.
 */
public final class AdHocAgentState {

    /**
     * Marks this execution, independently of what else it carries.
     *
     * <p>A fixed marker is needed because some of the state has no fixed variable
     * name — a conversation is keyed by a memory id — so recognition cannot work by
     * looking for the payload.
     */
    public static final String STATE_MARKER = "adHocAgentState";

    /**
     * Set when the driver has asked for the scope to end, but the scope must not end
     * yet.
     *
     * <p>A driver runs inside the scope, so completing it from there deletes the
     * driver's own execution while it is still running. The request is recorded here
     * and acted on once that execution has ended.
     */
    public static final String COMPLETION_REQUESTED = "adHocAgentCompletionRequested";

    private AdHocAgentState() {
        // utility class
    }

    /**
     * The agent state execution of {@code scopeExecution}, or {@code null} when
     * nothing has been stored yet. Never creates one, so it is safe on a read path.
     *
     * <p>Looks for {@link #STATE_MARKER} only. It must not fall back to the
     * behaviour's variable names — see the class comment.
     */
    public static PvmExecutionImpl find(ActivityExecution scopeExecution) {
        for (PvmExecutionImpl candidate : ((PvmExecutionImpl) scopeExecution).getEventScopeExecutions()) {
            if (candidate.hasVariableLocal(STATE_MARKER)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Records that the scope should end once the driver's turn is over.
     *
     * @see #COMPLETION_REQUESTED
     */
    public static void requestCompletion(ActivityExecution scopeExecution) {
        findOrCreate(scopeExecution).setVariableLocal(COMPLETION_REQUESTED, Boolean.TRUE);
    }

    /** Whether {@link #requestCompletion} was called for this scope. */
    public static boolean isCompletionRequested(ActivityExecution scopeExecution) {
        PvmExecutionImpl state = find(scopeExecution);
        return state != null && Boolean.TRUE.equals(state.getVariableLocal(COMPLETION_REQUESTED));
    }

    /**
     * The agent state execution of {@code scopeExecution}, created on first use.
     *
     * <p>Created lazily so a scope no agent ever drives carries neither an extra
     * execution nor an extra variable.
     */
    public static PvmExecutionImpl findOrCreate(ActivityExecution scopeExecution) {
        PvmExecutionImpl existing = find(scopeExecution);
        if (existing != null) {
            return existing;
        }

        ExecutionEntity state = ((ExecutionEntity) scopeExecution).createExecution();
        // createExecution() copies the parent's activity, and this execution must NOT
        // keep it: on removal the engine calls destroy() on every event-scope child,
        // and destroy() runs that activity's output io-mapping, which fails when the
        // mapping reads variables this execution does not have. Clearing it also keeps
        // the execution out of createActivityExecutionMapping(), so Cockpit's activity
        // tree is unaffected. Both were measured while CIB7-1850 introduced the trick.
        state.setActivity(null);
        state.setActive(false);
        state.setConcurrent(false);
        state.setEventScope(true);
        state.setVariableLocal(STATE_MARKER, Boolean.TRUE);
        return state;
    }

    /**
     * Walks up from {@code execution} to the enclosing ad hoc sub process scope, or
     * returns {@code null} when there is none.
     *
     * <p>Identified by the activity's behaviour rather than by element name, the same
     * way {@code TriggerAdHocActivitiesCmd} and the migration validator do, so the
     * check cannot drift from what the runtime actually does. Used by callers that
     * only have the execution of an activity <em>inside</em> the scope — a service
     * task, for instance — and need the scope's own execution.
     */
    public static ExecutionEntity findAdHocScope(ExecutionEntity execution) {
        ExecutionEntity current = execution;
        while (current != null) {
            PvmActivity activity = current.getActivity();
            if (activity != null
                    && activity.getActivityBehavior() instanceof AdHocSubProcessActivityBehavior) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }
}
