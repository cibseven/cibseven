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
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.connect.ai.agent.agentic.AdHocAgentState;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;
import org.cibseven.bpm.engine.variable.type.ValueType;
import org.cibseven.bpm.engine.variable.value.TypedValue;

/**
 * What the agent is told finished since its last turn, read from the scope's result
 * collection.
 *
 * <p>The collection is written by the engine as each child ends — one entry per
 * performance, with the values that performance produced. Reading process variables
 * instead, at the start of the next turn, cannot do that: two runs of one tool leave
 * one set of variables, and the agent would see the second twice or the first never.
 *
 * <p>"Since the last turn" is a remembered <b>read position</b> on the state
 * execution, not a timestamp. One more number in the state, and in exchange it is
 * unaffected by clocks, time zones and two children ending in the same instant.
 *
 * <p>The collection itself stays whole: it is the process's durable record of every
 * performance, and it outlives the scope. Only the position moves.
 */
final class AdHocResults {

    /** How far into the collection the agent has been shown, as a count of entries. */
    private static final String READ_POSITION = "adHocAgentResultsRead";

    /**
     * The variable the engine gathers into. Spelled out rather than imported: this
     * module cannot see the engine plugin that writes it, and the name is the contract
     * between them.
     */
    private static final String COLLECTION = "cibsevenAgenticResults";

    private AdHocResults() {
        // utility class
    }

    /**
     * The entries added since the last call, and moves the read position past them.
     *
     * <p>Capped per turn like every other model-facing block: a scope that ran many
     * tools between two turns must not hand the whole history to the model at once.
     * What was cut is said, and stays unread, so the next turn continues where this
     * one stopped rather than skipping it.
     */
    static List<Map<String, Object>> since(ExecutionEntity scope) {
        List<Object> all = collection(scope);
        int from = readPosition(scope);
        if (from >= all.size()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> described = new ArrayList<Map<String, Object>>();
        int budget = AdHocSubProcessTool.MAX_RESULT_BLOCK_CHARS;
        int index = from;
        String note = null;
        for (; index < all.size(); index++) {
            if (budget <= 0) {
                note = "Further results are waiting: the size limit of "
                        + AdHocSubProcessTool.MAX_RESULT_BLOCK_CHARS + " characters for one turn"
                        + " was reached. You will be shown them in your next turn.";
                break;
            }
            Map<String, Object> entry = describe(all.get(index));
            if (entry == null) {
                continue;
            }
            budget -= String.valueOf(entry.get("results")).length();
            described.add(entry);
        }
        if (note != null) {
            Map<String, Object> marker = new LinkedHashMap<String, Object>();
            marker.put("note", note);
            described.add(marker);
        }
        writeReadPosition(scope, index);
        return described;
    }

    /** One collection entry in the shape the model is shown. */
    private static Map<String, Object> describe(Object raw) {
        if (!(raw instanceof Map)) {
            return null;
        }
        Map<?, ?> gathered = (Map<?, ?>) raw;
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("activityId", gathered.get("activityId"));
        entry.put("activityInstanceId", gathered.get("activationId"));

        Map<String, Object> values = new LinkedHashMap<String, Object>();
        Object declared = gathered.get("values");
        if (declared instanceof Map) {
            for (Map.Entry<?, ?> value : ((Map<?, ?>) declared).entrySet()) {
                values.put(String.valueOf(value.getKey()), safe(value.getValue()));
            }
        }
        entry.put("results", values);
        entry.put("resultsFrom", values.isEmpty() ? "nothing declared" : "model declaration");
        if (values.isEmpty()) {
            entry.put("resultsNote", "This activity declares no output mapping, result variable "
                    + "or form fields, so nothing is reported even if it wrote something. Only "
                    + "declared variables are shown. Add one of those, or camunda:property "
                    + "adHocResultVariables, if the agent needs its values.");
        }
        return entry;
    }

    /**
     * A gathered value in a form that is safe to put in a prompt.
     *
     * <p>The values were read by the engine as the child ended, so unlike the variable
     * read this replaces, a customer POJO arrives here already deserialized. A
     * non-primitive is still replaced by a descriptor: it has no place in a prompt, and
     * its {@code toString} is not something a model should be asked to interpret.
     */
    private static Object safe(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof TypedValue) {
            TypedValue typed = (TypedValue) value;
            ValueType type = typed.getType();
            if (type == null || !type.isPrimitiveValueType()) {
                return "<" + (type == null ? "unknown" : type.getName()) + " value, not shown>";
            }
            return safe(typed.getValue());
        }
        if (value instanceof String) {
            return AdHocSubProcessTool.truncate((String) value,
                    AdHocSubProcessTool.MAX_RESULT_VALUE_CHARS);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return "<" + value.getClass().getSimpleName() + " value, not shown>";
    }

    /** The scope's result collection, in the order the engine gathered it. */
    private static List<Object> collection(ExecutionEntity scope) {
        Object raw = scope.getVariable(COLLECTION);
        if (raw instanceof Collection) {
            return new ArrayList<Object>((Collection<?>) raw);
        }
        return Collections.emptyList();
    }

    /**
     * On the state execution, not on the scope: the scope is an ancestor of every
     * child, and a tool that can rewind the position can make the agent read the same
     * results again or skip them.
     */
    private static int readPosition(ExecutionEntity scope) {
        PvmExecutionImpl state = AdHocAgentState.find(scope);
        Object raw = (state == null) ? null : state.getVariableLocal(READ_POSITION);
        return (raw instanceof Number) ? ((Number) raw).intValue() : 0;
    }

    private static void writeReadPosition(ExecutionEntity scope, int position) {
        AdHocAgentState.findOrCreate(scope).setVariableLocal(READ_POSITION,
                Integer.valueOf(position));
    }
}
