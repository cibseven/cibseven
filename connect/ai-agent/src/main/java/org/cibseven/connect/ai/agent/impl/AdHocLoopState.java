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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cibseven.connect.ai.agent.agentic.AdHocAgentState;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;

/**
 * The agent's bookkeeping for one ad hoc scope: which activities it started and
 * has not seen finish, and how many turns it has taken.
 *
 * <p>Needed because the engine knows which activities are alive but not which of
 * them <em>this agent</em> started: a person and the agent can activate the same
 * scope, and the agent must not report someone else's task as its own outstanding
 * work.
 *
 * <p>Kept in the state map of the execution {@link AdHocAgentState} provides, a sibling
 * of the scope's children rather than their ancestor. In an agentic scope those children
 * are the agent's tools, and a tool that can clear the pending list can make the
 * agent believe its work is done.
 *
 * <p>The pending list is stored as a list of pairs, not as a map: its order is the order
 * the agent sees in {@code stillRunning}, and a list keeps it with bytes that do not
 * change when the state is read. A child's end reads the list, and a read must stay a
 * read (see {@link AdHocAgentState}).
 *
 * <p>No separate mechanism for late or duplicate results: the list is reconciled
 * against the scope's live child executions at the start of each step, and an entry
 * whose execution is gone is finished however it ended — completed, cancelled or
 * deleted all mean "stop waiting for it".
 */
final class AdHocLoopState {

    /**
     * Started activities not yet seen finished: pairs of the scope's child execution of
     * the performance and the activity the agent called (CIB7-2147).
     */
    private static final String PENDING = "pending";

    /** Id of the step whose calls are being counted, so the next step resets the count. */
    private static final String CALLS_STEP = "callsStep";

    /** Tool calls made in the step now running, in one answer of the model. */
    private static final String CALLS = "callsThisAnswer";

    private AdHocLoopState() {
        // utility class
    }

    /**
     * The activities started and not yet seen finished, the performance's execution to the
     * activity, in the order they were started. A copy: changing it changes nothing stored.
     */
    static Map<String, String> pending(ExecutionEntity adHocScope) {
        Map<String, String> pending = new LinkedHashMap<>();
        Object raw = AdHocAgentState.stateValue(adHocScope, PENDING);
        if (!(raw instanceof Collection)) {
            return pending;
        }
        for (Object pair : (Collection<?>) raw) {
            if (pair instanceof List && ((List<?>) pair).size() == 2) {
                List<?> entry = (List<?>) pair;
                pending.put(String.valueOf(entry.get(0)), String.valueOf(entry.get(1)));
            }
        }
        return pending;
    }

    /**
     * Records a started activity.
     *
     * <p>A null execution id is ignored: the performance has already finished, and there
     * is nothing to wait for.
     */
    static void addPending(ExecutionEntity adHocScope, String executionId, String activityId) {
        if (executionId == null) {
            return;
        }
        Map<String, String> updated = pending(adHocScope);
        updated.put(executionId, activityId);
        writePending(adHocScope, updated);
    }

    /**
     * Removes every pending entry whose activity instance is no longer in
     * {@code stillRunning}, and returns what was removed.
     *
     * <p>Called at the start of a step rather than when a child ends, because the
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
            writePending(adHocScope, remaining);
        }
        return finished;
    }

    /** Stores the pending activities as a list of pairs, in their order. */
    private static void writePending(ExecutionEntity adHocScope, Map<String, String> pending) {
        List<List<String>> pairs = new ArrayList<>();
        for (Map.Entry<String, String> entry : pending.entrySet()) {
            pairs.add(new ArrayList<>(Arrays.asList(entry.getKey(), entry.getValue())));
        }
        AdHocAgentState.updateState(adHocScope, PENDING, pairs.isEmpty() ? null : pairs);
    }

    /** Turns taken so far. Counting lives in the engine now; this reads it for reports. */
    static int turns(ExecutionEntity adHocScope) {
        return AdHocAgentState.turns(adHocScope);
    }

    /**
     * Counts one tool call of the step {@code stepId}, resetting when the next step
     * starts.
     *
     * <p>A step is one model call (CIB7-2148), so this counts the tool calls one answer
     * asks for, and the cap on it refuses an answer that asks for too many. The model
     * calls themselves are counted per turn by the runner — see
     * {@code AdHocAgentState.countModelCall}.
     *
     * @param stepId something that is the same for every call of one step and
     *     different in the next. A {@code null} id counts every call as its own
     *     step's first, which is the safe direction for the cap.
     */
    static void registerCall(ExecutionEntity adHocScope, String stepId) {
        Map<String, Object> changes = new LinkedHashMap<>();
        if (stepId != null && stepId.equals(AdHocAgentState.stateValue(adHocScope, CALLS_STEP))) {
            changes.put(CALLS, Integer.valueOf(callsThisTurn(adHocScope) + 1));
        } else {
            changes.put(CALLS_STEP, stepId);
            changes.put(CALLS, Integer.valueOf(1));
        }
        AdHocAgentState.updateState(adHocScope, changes);
    }

    /** Tool calls made in the step now running, in one answer of the model. */
    static int callsThisTurn(ExecutionEntity adHocScope) {
        Object raw = AdHocAgentState.stateValue(adHocScope, CALLS);
        return (raw instanceof Number) ? ((Number) raw).intValue() : 0;
    }
}
