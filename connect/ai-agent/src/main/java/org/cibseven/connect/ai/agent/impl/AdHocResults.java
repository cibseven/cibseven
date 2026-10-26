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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.cibseven.connect.ai.agent.agentic.AdHocAgentState;
import org.cibseven.connect.ai.agent.agentic.AdHocToolDescriptor;
import org.cibseven.bpm.engine.impl.bpmn.behavior.MultiInstanceActivityBehavior;
import org.cibseven.bpm.engine.impl.core.variable.mapping.IoMapping;
import org.cibseven.bpm.engine.impl.core.variable.mapping.OutputParameter;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.PvmActivity;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.impl.pvm.process.ScopeImpl;
import org.cibseven.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.type.ValueType;
import org.cibseven.bpm.engine.variable.value.TypedValue;

/**
 * What the agent is told finished since it last looked: one entry per performance of a tool,
 * taken as the child ends and kept on the agent state until it has been shown.
 *
 * <p>Taken as the child ends, because that is the one moment its declared variables still hold
 * what this performance produced: two runs of one tool leave one set of variables, and reading
 * them at the next step would show the second twice or the first never. The scope's behaviour
 * says when that moment is ({@code AdHocSubProcessObserver}), so nothing is installed into the
 * scope's {@code outputCollection}; that belongs to the modeller (CIB7-2147).
 *
 * <p>Kept on the agent state, a sibling of the tools, so no tool can rewrite what the agent is
 * told, and one per scope execution, so two agents, the instances of a multi-instance agent and a
 * scope entered again each see only their own. The list goes with the scope.
 *
 * <p>A tool that holds its results in an execution of its own loses them before its child ends;
 * {@link #carry} keeps them on the child's execution as the tool ends, and the child's end takes
 * them into the list (CIB7-2215). The list itself is still written only there.
 *
 * <p>Only a child that ends writes the list; a step moves a <b>read position</b> and records what
 * a tool result already showed, both in the agent's state map, which only a step writes. A child
 * ending while a step waits for the model therefore meets no row the step changes, and neither has
 * to be repeated (T01). Two children ending at once both append, and one waits for the other.
 *
 * <p>A step that reads the list must not write it either. The engine serializes an object it read
 * again when the command ends, and writes it back if the bytes differ; a hash map can come back with
 * a different table size, on Java 17 at twelve entries and every doubling of that. So every map in
 * the list is a {@link TreeMap}, whose bytes depend only on what it holds, and the list is rebuilt
 * that way whenever it is written.
 */
public final class AdHocResults {

    /** Every entry the scope has gathered, oldest first; written only as children end. */
    private static final String RESULTS = "adHocAgentResults";

    /** How far into the list the agent has been shown, as a count of entries; in the state map. */
    private static final String READ_POSITION = "resultsRead";

    /**
     * Performances the agent was already shown in a tool result: a tool that finished during its
     * call. Skipped once when the read position passes them, so the next step does not report them
     * a second time (CIB7-2148: every step reads the list). In the state map.
     */
    private static final String REPORTED = "resultsReported";

    /**
     * Declared results a tool held in an execution of its own, carried out of it as it ended
     * (CIB7-2215). A variable, as {@link #RESULTS} is, not a key of an entry: a local one of the
     * execution the activation created, which goes with the tool.
     */
    private static final String CARRIED = "adHocAgentCarriedResults";

    /**
     * The two parts of one carried result: what the tool held, and what a lookup from the
     * activation's execution showed for the name at that moment, outside the tool.
     */
    private static final String CARRIED_VALUE = "value";
    private static final String CARRIED_SEEN = "seen";

    /** Keys of one entry, so writer and reader cannot drift apart. */
    private static final String ACTIVITY_ID = "activityId";
    private static final String ACTIVATION_ID = "activationId";
    private static final String VALUES = "values";
    private static final String MULTI_INSTANCE = "multiInstance";
    private static final String ENDED_AT = "endedAt";

    /**
     * The value types whose value {@link #safe} shows: those whose value is a string, a number or a
     * boolean, or null.
     */
    private static final Set<String> SHOWN_TYPES = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
                    ValueType.STRING.getName(), ValueType.BOOLEAN.getName(),
                    ValueType.SHORT.getName(), ValueType.INTEGER.getName(),
                    ValueType.LONG.getName(), ValueType.DOUBLE.getName(),
                    ValueType.NUMBER.getName(), ValueType.NULL.getName())));

    private AdHocResults() {
        // utility class
    }

    /**
     * Records what the child {@code ended} of the agentic scope {@code scope} produced: the values
     * of the variables its tool declares, made safe for a prompt now, while they still hold this
     * performance's values. A child that is not one of the scope's tools records nothing.
     *
     * <p>The tool is the one the agent called for this performance, looked up by the child's
     * execution (CIB7-2147), so a chain along inner flows is reported under its first activity,
     * with where it ended. A child someone else started is looked up by its activity. A
     * multi-instance tool ends once, as its multi-instance body, after its last instance
     * (CIB7-2153): it is reported under the activity the agent called, with the values its
     * declared variables hold then.
     */
    public static void gather(ExecutionEntity scope, ExecutionEntity ended) {
        PvmActivity endedIn = ended.getActivity();
        boolean multiInstance = endedIn != null
                && endedIn.getActivityBehavior() instanceof MultiInstanceActivityBehavior
                && !endedIn.getActivities().isEmpty();
        PvmActivity activity = multiInstance ? endedIn.getActivities().get(0) : endedIn;
        String called = calledTool(scope, ended);
        String toolId = (called != null) ? called : (activity == null ? null : activity.getId());
        AdHocToolDescriptor descriptor = (toolId == null) ? null : descriptorOf(scope, toolId);
        if (descriptor == null) {
            return;
        }
        Map<String, Object> values = new TreeMap<String, Object>();
        Map<?, ?> carried = carried(ended);
        for (String name : descriptor.getResultVariables()) {
            // From the child that ended, so a value its own output mapping just wrote above it is
            // found by walking up, and a local one is found before it. Typed and not deserialized:
            // this is someone's completion, and an object that cannot be read here must not fail it.
            // A value the tool held in a scope of its own is gone by now; carry() kept it, unless
            // an activity later in the chain wrote the name after that.
            values.put(name,
                    carriedUnlessChanged(carried.get(name), safe(ended.getVariableTyped(name, false))));
        }
        Map<String, Object> entry = new TreeMap<String, Object>();
        entry.put(ACTIVITY_ID, toolId);
        if (activity != null && !activity.getId().equals(toolId)) {
            // The flow the tool started moved on, along an inner flow or a boundary event, and
            // ended elsewhere: where is part of how it ended.
            entry.put(ENDED_AT, activity.getId());
        }
        // Which performance: the ending child's execution. Not its activity instance id -- by the
        // time the scope is told, that instance is resolved and the id read back is the scope's
        // own, the same for every entry. Measured: two performances of one child both reported
        // 'adHoc:8'. The execution is one performance's own.
        entry.put(ACTIVATION_ID, ended.getId());
        entry.put(VALUES, values);
        if (multiInstance) {
            entry.put(MULTI_INSTANCE, Boolean.TRUE);
        }
        List<Map<String, Object>> entries = entries(scope);
        entries.add(entry);
        AdHocAgentState.findOrCreate(scope).setVariableLocal(RESULTS,
                Variables.objectValue(stored(entries)).create());
    }

    /**
     * The list as it is written: every entry, and the values in it, as a {@link TreeMap}. An entry
     * written before, by an earlier version, may be another map; it is rebuilt too.
     */
    private static List<Map<String, Object>> stored(List<Map<String, Object>> entries) {
        List<Map<String, Object>> stored = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> entry : entries) {
            Map<String, Object> copy = new TreeMap<String, Object>();
            for (Map.Entry<String, Object> field : entry.entrySet()) {
                Object value = field.getValue();
                if (value instanceof Map) {
                    Map<String, Object> values = new TreeMap<String, Object>();
                    for (Map.Entry<?, ?> declared : ((Map<?, ?>) value).entrySet()) {
                        values.put(String.valueOf(declared.getKey()), declared.getValue());
                    }
                    value = values;
                }
                copy.put(field.getKey(), value);
            }
            stored.add(copy);
        }
        return stored;
    }

    /**
     * The entries added since the agent last looked, in the shape the model is shown, and moves
     * the read position past them.
     *
     * <p>Capped like every other model-facing block: a scope that ran many tools while the agent
     * waited must not hand the whole history to the model at once. What was cut is said, and stays
     * unread, so the next state block continues where this one stopped rather than skipping it.
     */
    static List<Map<String, Object>> since(ExecutionEntity scope) {
        List<Map<String, Object>> entries = entries(scope);
        int from = readPosition(scope);
        if (from >= entries.size()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> described = new ArrayList<Map<String, Object>>();
        Set<String> reported = reported(scope);
        int budget = AdHocSubProcessTool.MAX_RESULT_BLOCK_CHARS;
        int index = from;
        String note = null;
        for (; index < entries.size(); index++) {
            if (budget <= 0) {
                note = "Further results are waiting: the size limit of "
                        + AdHocSubProcessTool.MAX_RESULT_BLOCK_CHARS + " characters for one turn"
                        + " was reached. You will be shown them in your next turn.";
                break;
            }
            if (reported.remove(activationIdOf(entries.get(index)))) {
                continue;
            }
            Map<String, Object> entry = describe(entries.get(index));
            // What the values take in the prompt as written, not their toString (CIB7-2233).
            budget -= AdHocToolProvider.writtenLength(entry.get("results"));
            described.add(entry);
        }
        if (note != null) {
            Map<String, Object> marker = new LinkedHashMap<String, Object>();
            marker.put("note", note);
            described.add(marker);
        }
        Map<String, Object> changes = new LinkedHashMap<String, Object>();
        changes.put(READ_POSITION, Integer.valueOf(index));
        changes.put(REPORTED, reported.isEmpty() ? null : new ArrayList<String>(reported));
        AdHocAgentState.updateState(scope, changes);
        return described;
    }

    /** How many entries the scope has gathered so far. */
    static int gathered(ExecutionEntity scope) {
        return entries(scope).size();
    }

    /**
     * Records that the entries from {@code from} to the end were shown to the agent already: what
     * was gathered while a tool ran within its call, whose values the tool result carried.
     */
    static void markReported(ExecutionEntity scope, int from) {
        List<Map<String, Object>> entries = entries(scope);
        if (from >= entries.size()) {
            return;
        }
        Set<String> reported = reported(scope);
        for (Map<String, Object> entry : entries.subList(from, entries.size())) {
            String id = activationIdOf(entry);
            if (id != null) {
                reported.add(id);
            }
        }
        AdHocAgentState.updateState(scope, REPORTED,
                reported.isEmpty() ? null : new ArrayList<String>(reported));
    }

    /**
     * The values gathered, from entry {@code from} on, for a performance of {@code activityId}
     * that ended within the call that started it, or null when none was gathered (CIB7-2215).
     *
     * <p>The observer took them as the child ended, from where the tool held them: a sub process
     * tool's own scope included, which is gone by the time the call returns, so the scope's
     * variables would read null for it. A performance the agent started earlier is pending; its
     * entry is not this call's.
     */
    static Map<String, Object> gatheredWithinTheCall(ExecutionEntity scope, int from,
            String activityId) {
        List<Map<String, Object>> entries = entries(scope);
        Map<String, String> pending = AdHocLoopState.pending(scope);
        for (int index = entries.size() - 1; index >= from; index--) {
            Map<String, Object> entry = entries.get(index);
            if (!activityId.equals(entry.get(ACTIVITY_ID))
                    || pending.containsKey(activationIdOf(entry))) {
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<String, Object>();
            Object declared = entry.get(VALUES);
            if (declared instanceof Map) {
                for (Map.Entry<?, ?> value : ((Map<?, ?>) declared).entrySet()) {
                    values.put(String.valueOf(value.getKey()), value.getValue());
                }
            }
            return values;
        }
        return null;
    }

    /**
     * Carries what a tool that holds variables of its own declares as its result and holds there
     * only, as its execution ends (CIB7-2215). An embedded sub process whose input parameter
     * creates its result variable, for one: by the time the agentic scope is told the tool ended,
     * the sub process's scope execution and its variables are gone, and {@link #gather} would
     * read null. Likewise each instance of a multi-instance task with an input parameter, or of a
     * multi-instance call activity with {@code camunda:out local}: the instance's execution is no
     * scope, but its variables go with it.
     *
     * <p>Called at the end of the tool's own execution, while its variables are still there. The
     * values, made safe for a prompt as gather makes them, are kept on the execution the
     * activation created, where gather reads them: in this transaction or, after an
     * {@code asyncAfter}, in the next. That execution goes with the tool, so nothing reaches the
     * process's variables. Only a value the tool's scope holds itself is carried; one found higher
     * up is still there for gather. Nor is a name the tool's own output parameter writes: that
     * mapping runs after this, as the scope is destroyed, and what it hands out of the scope is
     * what gather is to read. Of a multi-instance tool, the value of the last instance that held
     * the name locally is what is kept.
     *
     * <p>A tool that is cancelled carries nothing: one interrupted by a boundary event reports what
     * a lookup finds, as before the carrier.
     *
     * <p>Each value is kept with what a lookup from the activation's execution showed for its name
     * at that moment. A chain that goes on after the tool, along an inner flow, may write the name
     * again; the lookup then shows the newer value, and gather reports that one instead of the
     * carried one.
     */
    public static void carry(ExecutionEntity toolScope) {
        // A tool cancelled before it ends hands nothing out -- by a boundary event, a modification,
        // the end of its scope or of the instance -- and compensation runs no tool for the agent:
        // the observer does not report either, so nothing would read what is kept (review F5).
        if (toolScope.isCanceled() || toolScope.isEventScope()) {
            return;
        }
        ExecutionEntity scope = (toolScope.getParent() == null)
                ? null : AdHocAgentState.findAdHocScope(toolScope.getParent());
        if (scope == null || scope.isEventScope()) {
            return;
        }
        ExecutionEntity handle = toolScope;
        while (handle.getParent() != null && !scope.getId().equals(handle.getParentId())) {
            handle = handle.getParent();
        }
        // The tool the agent called for this performance: an activity further along its chain
        // carries what that tool declares.
        String called = calledTool(scope, handle);
        AdHocToolDescriptor descriptor =
                descriptorOf(scope, called != null ? called : toolScope.getActivityId());
        if (handle == toolScope || descriptor == null) {
            return;
        }
        Map<String, Object> kept = new TreeMap<String, Object>();
        for (Map.Entry<?, ?> entry : carried(handle).entrySet()) {
            kept.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        Set<String> handedOut = outputParameterNames(toolScope);
        boolean any = false;
        for (String name : descriptor.getResultVariables()) {
            if (toolScope.hasVariableLocal(name) && !handedOut.contains(name)) {
                Map<String, Object> carriedValue = new TreeMap<String, Object>();
                carriedValue.put(CARRIED_VALUE, safe(toolScope.getVariableLocalTyped(name, false)));
                carriedValue.put(CARRIED_SEEN, safe(handle.getVariableTyped(name, false)));
                kept.put(name, carriedValue);
                any = true;
            }
        }
        if (any) {
            handle.setVariableLocal(CARRIED, Variables.objectValue(kept).create());
        }
    }

    /** The names the output parameters of the activity {@code execution} is in write. */
    private static Set<String> outputParameterNames(ExecutionEntity execution) {
        Set<String> names = new LinkedHashSet<String>();
        ActivityImpl activity = execution.getActivity();
        IoMapping mapping = (activity == null) ? null : activity.getIoMapping();
        if (mapping != null) {
            for (OutputParameter parameter : mapping.getOutputParameters()) {
                names.add(parameter.getName());
            }
        }
        return names;
    }

    /**
     * The carried value of a name, unless the name changed after it was carried (CIB7-2215): when
     * an activity later in the chain writes it, a lookup from the activation no longer shows what
     * it showed as the tool ended, and the newer value is what the performance ended with.
     * {@code found} is that lookup now; it is also the answer for a name nothing carried.
     */
    private static Object carriedUnlessChanged(Object carried, Object found) {
        if (!(carried instanceof Map) || !((Map<?, ?>) carried).containsKey(CARRIED_VALUE)) {
            return found;
        }
        Map<?, ?> value = (Map<?, ?>) carried;
        Object seen = value.get(CARRIED_SEEN);
        return (seen == null ? found == null : seen.equals(found)) ? value.get(CARRIED_VALUE) : found;
    }

    /**
     * The tool the agent called for the performance whose execution is {@code handle}, the scope's
     * child: pending once its call returned, or the activation running now while the call runs.
     * Null for a child someone else started.
     */
    private static String calledTool(ExecutionEntity scope, ExecutionEntity handle) {
        String called = AdHocLoopState.pending(scope).get(handle.getId());
        return (called != null) ? called : AdHocSubProcessTool.activatedTool(scope, handle.getId());
    }

    /** What {@link #carry} kept on this execution, or nothing. */
    private static Map<?, ?> carried(ExecutionEntity execution) {
        Object raw = execution.getVariableLocal(CARRIED);
        return (raw instanceof Map) ? (Map<?, ?>) raw : Collections.emptyMap();
    }

    private static AdHocToolDescriptor descriptorOf(ExecutionEntity scope, String activityId) {
        if (scope == null || scope.getActivity() == null) {
            return null;
        }
        List<AdHocToolDescriptor> catalog =
                ((ScopeImpl) scope.getActivity()).getProperties().get(AdHocToolDescriptor.CATALOG);
        if (catalog == null) {
            return null;
        }
        for (AdHocToolDescriptor descriptor : catalog) {
            if (descriptor.getActivityId().equals(activityId)) {
                return descriptor;
            }
        }
        return null;
    }

    /** One entry in the shape the model is shown. */
    private static Map<String, Object> describe(Map<String, Object> gathered) {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("activityId", gathered.get(ACTIVITY_ID));
        entry.put("activityInstanceId", gathered.get(ACTIVATION_ID));
        if (gathered.get(ENDED_AT) != null) {
            entry.put("endedAt", gathered.get(ENDED_AT));
        }

        Map<String, Object> values = new LinkedHashMap<String, Object>();
        Object declared = gathered.get(VALUES);
        if (declared instanceof Map) {
            for (Map.Entry<?, ?> value : ((Map<?, ?>) declared).entrySet()) {
                values.put(String.valueOf(value.getKey()), value.getValue());
            }
        }
        entry.put("results", values);
        entry.put("resultsFrom", values.isEmpty() ? "nothing declared" : "model declaration");
        // Both can hold: a multi-instance activity that declares nothing is still reported once.
        List<String> notes = new ArrayList<String>();
        if (Boolean.TRUE.equals(gathered.get(MULTI_INSTANCE))) {
            notes.add("A multi-instance activity, reported once, when its last instance ended. A "
                    + "variable every instance writes holds the last value.");
        }
        if (values.isEmpty()) {
            notes.add("This activity declares no output mapping, result variable or editable form "
                    + "field, so nothing is reported even if it wrote something. Only declared "
                    + "variables are shown. Add one of those, or camunda:property "
                    + "adHocResultVariables, if the agent needs its values.");
        }
        if (!notes.isEmpty()) {
            entry.put("resultsNote", String.join(" ", notes));
        }
        return entry;
    }

    /**
     * A value in a form that is safe to put in a prompt, and to keep on the agent state.
     *
     * <p>Only a string (capped), a number or a boolean is shown. Anything else is replaced by a
     * descriptor naming its type: it has no place in a prompt, its {@code toString} is not
     * something a model should be asked to interpret, and kept as it is it would have to be
     * serializable to be stored at all. That includes a file, bytes and a date, which the engine
     * counts as primitive value types (CIB7-2233).
     *
     * <p>A typed value is decided by its type, before its value is taken, so a file or bytes is
     * never turned into a stream or an array here, and a type that is added later stays hidden
     * until it is listed in {@link #SHOWN_TYPES}.
     *
     * <p>Every declared result the agent is shown passes here: the values the observer gathers
     * as a child ends, and the ones a tool result reads from the scope when nothing was gathered
     * ({@code AdHocSubProcessTool.readResult}). So a value reads the same on either path.
     */
    static Object safe(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof TypedValue) {
            TypedValue typed = (TypedValue) value;
            ValueType type = typed.getType();
            if (type == null || !SHOWN_TYPES.contains(type.getName())) {
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(ExecutionEntity scope) {
        PvmExecutionImpl state = AdHocAgentState.find(scope);
        Object raw = (state == null) ? null : state.getVariableLocal(RESULTS);
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        if (raw instanceof Collection) {
            for (Object entry : (Collection<?>) raw) {
                if (entry instanceof Map) {
                    entries.add((Map<String, Object>) entry);
                }
            }
        }
        return entries;
    }

    private static String activationIdOf(Map<String, Object> entry) {
        Object id = entry.get(ACTIVATION_ID);
        return (id == null) ? null : id.toString();
    }

    private static int readPosition(ExecutionEntity scope) {
        Object raw = AdHocAgentState.stateValue(scope, READ_POSITION);
        return (raw instanceof Number) ? ((Number) raw).intValue() : 0;
    }

    /** A copy of what a tool result already showed. */
    private static Set<String> reported(ExecutionEntity scope) {
        Object raw = AdHocAgentState.stateValue(scope, REPORTED);
        Set<String> reported = new LinkedHashSet<String>();
        if (raw instanceof Collection) {
            for (Object id : (Collection<?>) raw) {
                reported.add(String.valueOf(id));
            }
        }
        return reported;
    }
}
