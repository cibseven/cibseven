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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.cibseven.connect.ai.agent.agentic.AdHocAgentState;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;
import org.cibseven.bpm.engine.variable.Variables;

/**
 * The agent's bookkeeping for one ad hoc scope: which activities it started and
 * has not seen finish, and how many turns it has taken.
 *
 * <p>Needed because the engine knows which activities are alive but not which of
 * them <em>this agent</em> started: a person and the agent can activate the same
 * scope, and the agent must not report someone else's task as its own outstanding
 * work.
 *
 * <p>Kept on the execution {@link AdHocAgentState} provides, a sibling of the
 * scope's children rather than their ancestor. In an agentic scope those children
 * are the agent's tools, and a tool that can clear the pending list can make the
 * agent believe its work is done.
 *
 * <p>No separate mechanism for late or duplicate results: the list is reconciled
 * against the engine's live activity instance tree at the start of each turn, and an
 * entry no longer in the tree is finished however it ended — completed, cancelled or
 * deleted all mean "stop waiting for it".
 */
final class AdHocLoopState {

    /** Started activities not yet seen finished: activity instance id to activity id. */
    private static final String PENDING = "adHocAgentPending";

    /** Id of the turn whose calls are being counted, so a new turn resets the count. */
    private static final String CALLS_TURN_MARKER = "adHocAgentCallsTurnMarker";

    /** Tool calls made in the turn now running; reset when a new turn starts. */
    private static final String CALLS = "adHocAgentCallsThisTurn";

    private AdHocLoopState() {
        // utility class
    }

    /** The activities started and not yet seen finished, keyed by activity instance id. */
    @SuppressWarnings("unchecked")
    static Map<String, String> pending(ExecutionEntity adHocScope) {
        PvmExecutionImpl state = AdHocAgentState.find(adHocScope);
        if (state == null) {
            return Collections.emptyMap();
        }
        Object raw = state.getVariableLocal(PENDING);
        return (raw instanceof Map) ? (Map<String, String>) raw : Collections.<String, String>emptyMap();
    }

    /**
     * Records a started activity.
     *
     * <p>A null activity instance id is ignored. The activation API returns null for
     * an activity it did not start because an earlier one in the same call ran
     * synchronously and ended the scope — there is then nothing to wait for.
     */
    static void addPending(ExecutionEntity adHocScope, String activityInstanceId, String activityId) {
        if (activityInstanceId == null) {
            return;
        }
        Map<String, String> updated = new LinkedHashMap<>(pending(adHocScope));
        updated.put(activityInstanceId, activityId);
        write(adHocScope, PENDING, updated);
    }

    /**
     * Removes every pending entry whose activity instance is no longer in
     * {@code stillRunning}, and returns what was removed.
     *
     * <p>Called at the start of a turn rather than when a child ends, because the
     * child's end is handled by the engine and the connector is not on that thread.
     *
     * @return the finished entries, activity instance id to activity id
     */
    static Map<String, String> harvestFinished(ExecutionEntity adHocScope, Set<String> stillRunning) {
        Map<String, String> before = pending(adHocScope);
        if (before.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> finished = new LinkedHashMap<>();
        Map<String, String> remaining = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : before.entrySet()) {
            if (stillRunning.contains(entry.getKey())) {
                remaining.put(entry.getKey(), entry.getValue());
            } else {
                finished.put(entry.getKey(), entry.getValue());
            }
        }
        if (!finished.isEmpty()) {
            write(adHocScope, PENDING, remaining);
        }
        return finished;
    }

    /** Turns taken so far. Counting lives in the engine now; this reads it for reports. */
    static int turns(ExecutionEntity adHocScope) {
        return AdHocAgentState.turns(adHocScope);
    }

    /**
     * Counts one tool call of the turn {@code turnId}, resetting when a new turn
     * starts.
     *
     * <p>The turn itself is counted by the runner before the model is asked — see
     * {@code AdHocAgentState.beginTurn}. This counter only bounds the calls
     * <em>within</em> one turn, which the turn cap cannot see: a loop that starts
     * synchronous children never ends its turn.
     *
     * @param turnId something that is the same for every call of one turn and
     *     different in the next. A {@code null} id counts every call as its own
     *     turn's first, which is the safe direction for the cap.
     */
    static void registerCall(ExecutionEntity adHocScope, String turnId) {
        if (turnId != null) {
            PvmExecutionImpl state = AdHocAgentState.find(adHocScope);
            Object last = (state == null) ? null : state.getVariableLocal(CALLS_TURN_MARKER);
            if (turnId.equals(last)) {
                write(adHocScope, CALLS, Integer.valueOf(callsThisTurn(adHocScope) + 1));
                return;
            }
            write(adHocScope, CALLS_TURN_MARKER, turnId);
        }
        write(adHocScope, CALLS, Integer.valueOf(1));
    }

    /**
     * Tool calls made in the turn now running.
     *
     * <p>What the turn cap cannot see: a loop that starts synchronous children never
     * ends its turn, so no further turn is ever counted. It was bounded only by accident
     * while turns were counted per call.
     */
    static int callsThisTurn(ExecutionEntity adHocScope) {
        PvmExecutionImpl state = AdHocAgentState.find(adHocScope);
        Object raw = (state == null) ? null : state.getVariableLocal(CALLS);
        return (raw instanceof Number) ? ((Number) raw).intValue() : 0;
    }

    /**
     * Writes a value onto the scope's agent state execution, creating that execution
     * on first use.
     *
     * <p>An object value rather than a plain one, so the payload lands in the
     * byte-array table instead of hitting the variable column's 4000 character
     * limit once the pending list grows.
     */
    private static void write(ExecutionEntity adHocScope, String name, Object value) {
        AdHocAgentState.findOrCreate(adHocScope)
                .setVariableLocal(name, Variables.objectValue(value).create());
    }
}
