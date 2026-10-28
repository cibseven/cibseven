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
package org.cibseven.connect.ai.agent.agentic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocSubProcessActivityBehavior;
import org.cibseven.bpm.engine.impl.bpmn.helper.BpmnProperties;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.interceptor.CommandContextListener;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.PvmActivity;
import org.cibseven.bpm.engine.impl.pvm.delegate.ActivityExecution;
import org.cibseven.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;
import org.cibseven.bpm.engine.variable.Variables;

/**
 * Holds state belonging to a client that drives an ad hoc sub process — an agent's
 * pending activations, its turn count, its conversation — where the scope's own
 * children cannot reach it.
 *
 * <p>{@code setVariable} walks strictly up the parent chain, and the scope execution is
 * an ancestor of every ad hoc child — and in an agentic scope the children <em>are</em>
 * the tools. The execution returned here is their <em>sibling</em> instead, so a child
 * writing the same name creates its own variable at the process instance. Event scope
 * keeps it out of child iteration and completion checks, and has it removed with the
 * scope like any event scope -- except when the scope is kept for compensation
 * (CIB7-2154).
 *
 * <p>Deliberately separate from the state execution
 * {@link AdHocSubProcessActivityBehavior} keeps for its {@code adHocActivated} marker:
 * sharing would tie the engine's "has this scope ever started anything" answer to
 * whether an agent happened to store something. The cost is one further execution per
 * instance.
 *
 * <p>What only a step writes is one map, held by the marker variable itself, so a step
 * writes one variable rather than one per counter (CIB7-2200). It is a {@link TreeMap} of
 * JDK types, serialized as Java: every engine can read it, Cockpit shows it as a map, and
 * its bytes depend only on what it holds. That matters because the engine serializes an
 * object it read again when the command ends and writes it back if the bytes differ; a
 * hash map can come back with another table size, which would turn a read into a write.
 * For the same reason the map read is never changed in place: {@link #updateState}
 * builds a new one.
 *
 * <p>Within a command the changes collect, and the map is written once, when the command
 * closes, if it then differs from what was stored. A step changes the map several times
 * (the turn, the model call, every tool call, the read position in the results), and each
 * write of a variable is a history row of its own at history level {@code full}. The engine
 * tells its listeners that a command closes before it flushes, so the write still goes
 * with the step's transaction; a command that fails drops what collected, as the
 * transaction does.
 */
public final class AdHocAgentState {

    /**
     * Marks this execution, and holds the map of what only a step writes.
     *
     * <p>A fixed marker is needed because some of the state has no fixed variable
     * name — a conversation is keyed by a memory id — so recognition cannot work by
     * looking for the payload.
     */
    public static final String STATE_MARKER = "adHocAgentState";

    /** The layout of the map, kept in it, so a later version can tell an older one. */
    public static final String LAYOUT_KEY = "v";
    public static final int LAYOUT = 1;

    /**
     * Set when the agent has asked for the scope to end, but the scope must not end
     * yet.
     *
     * <p>A turn may still have work running that it started, or that someone started
     * from outside after the request was made. Ending the scope then would cancel it,
     * which is what {@code completeScope} refuses for. The request is recorded here and
     * acted on at the end of a turn that finds nothing running.
     */
    private static final String COMPLETION_REQUESTED = "completionRequested";

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
     * Records that the scope should end once nothing is running in it any more.
     *
     * @see #COMPLETION_REQUESTED
     */
    public static void requestCompletion(ActivityExecution scopeExecution) {
        updateState(scopeExecution, COMPLETION_REQUESTED, Boolean.TRUE);
    }

    /** Whether {@link #requestCompletion} was called for this scope. */
    public static boolean isCompletionRequested(ActivityExecution scopeExecution) {
        return Boolean.TRUE.equals(stateValue(scopeExecution, COMPLETION_REQUESTED));
    }

    /** One entry of the state map, or {@code null}. Never creates the state. */
    public static Object stateValue(ActivityExecution scopeExecution, String key) {
        PvmExecutionImpl state = find(scopeExecution);
        return (state == null) ? null : current(state).get(key);
    }

    /**
     * Sets one entry of the state map; {@code null} removes it.
     *
     * @see #updateState(ActivityExecution, Map)
     */
    public static void updateState(ActivityExecution scopeExecution, String key, Object value) {
        Map<String, Object> change = new LinkedHashMap<String, Object>();
        change.put(key, value);
        updateState(scopeExecution, change);
    }

    /**
     * Sets entries of the state map, a {@code null} value removing its key, and writes the
     * map once, only if it changed. Lists and maps in a value are stored as an
     * {@link ArrayList} and a {@link TreeMap}, whatever the caller passed.
     */
    public static void updateState(ActivityExecution scopeExecution, Map<String, ?> changes) {
        PvmExecutionImpl state = findOrCreate(scopeExecution);
        Map<String, Object> current = current(state);
        Map<String, Object> updated = new TreeMap<String, Object>();
        for (Map.Entry<String, Object> entry : current.entrySet()) {
            updated.put(entry.getKey(), stored(entry.getValue()));
        }
        for (Map.Entry<String, ?> change : changes.entrySet()) {
            if (change.getValue() == null) {
                updated.remove(change.getKey());
            } else {
                updated.put(change.getKey(), stored(change.getValue()));
            }
        }
        updated.put(LAYOUT_KEY, Integer.valueOf(LAYOUT));
        if (updated.equals(current)) {
            return;
        }
        stageMap(state, STATE_MARKER, updated);
    }

    /** The state map as this command left it so far: what collected, else what is stored. */
    private static Map<String, Object> current(PvmExecutionImpl state) {
        return currentMap(state, STATE_MARKER);
    }

    static Map<String, Object> currentMap(PvmExecutionImpl state, String marker) {
        CommandContext commandContext = Context.getCommandContext();
        Writes writes = (commandContext == null) ? null : Writes.OPEN.get().get(commandContext);
        Map<String, Map<String, Object>> maps = (writes == null) ? null : writes.maps.get(state);
        Map<String, Object> collected = (maps == null) ? null : maps.get(marker);
        return (collected != null) ? collected : mapOf(state, marker);
    }

    static void stageMap(PvmExecutionImpl state, String marker, Map<String, Object> map) {
        CommandContext commandContext = Context.getCommandContext();
        if (commandContext == null) {
            store(state, marker, map);
        } else {
            Writes.of(commandContext).maps.computeIfAbsent(state, key -> new TreeMap<>())
                .put(marker, map);
        }
    }

    private static void store(PvmExecutionImpl state, String marker, Map<String, Object> map) {
        state.setVariableLocal(marker, Variables.objectValue(map).create());
    }

    /**
     * The state maps one command changed, written when it closes. Kept per command context,
     * so a command run inside another, on a context of its own, writes its own.
     */
    private static final class Writes implements CommandContextListener {

        private static final ThreadLocal<Map<CommandContext, Writes>> OPEN =
                ThreadLocal.withInitial(IdentityHashMap::new);

        private final Map<PvmExecutionImpl, Map<String, Map<String, Object>>> maps =
                new IdentityHashMap<>();

        static Writes of(CommandContext commandContext) {
            Writes writes = OPEN.get().get(commandContext);
            if (writes == null) {
                writes = new Writes();
                OPEN.get().put(commandContext, writes);
                commandContext.registerCommandContextListener(writes);
            }
            return writes;
        }

        @Override
        public void onCommandContextClose(CommandContext commandContext) {
            OPEN.get().remove(commandContext);
            for (Map.Entry<PvmExecutionImpl, Map<String, Map<String, Object>>> entry : maps.entrySet()) {
                PvmExecutionImpl state = entry.getKey();
                // A step that ended the scope removed the state with it, its variables too;
                // writing now would bring one back on an execution that is gone.
                if (!state.isRemoved()) {
                    for (Map.Entry<String, Map<String, Object>> variable : entry.getValue().entrySet()) {
                        if (!variable.getValue().equals(mapOf(state, variable.getKey()))) {
                            store(state, variable.getKey(), variable.getValue());
                        }
                    }
                }
            }
        }

        @Override
        public void onCommandFailed(CommandContext commandContext, Throwable t) {
            OPEN.get().remove(commandContext);
        }
    }

    /** A stored map, never changed in place; empty for a marker from an earlier layout. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(PvmExecutionImpl state, String marker) {
        Object raw = state.getVariableLocal(marker);
        return (raw instanceof Map) ? (Map<String, Object>) raw : Collections.<String, Object>emptyMap();
    }

    /** A value as it is stored: lists as an {@link ArrayList}, maps as a {@link TreeMap}, all the way down. */
    private static Object stored(Object value) {
        if (value instanceof Map) {
            Map<String, Object> map = new TreeMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                map.put(String.valueOf(entry.getKey()), stored(entry.getValue()));
            }
            return map;
        }
        if (value instanceof Collection) {
            List<Object> list = new ArrayList<Object>();
            for (Object element : (Collection<?>) value) {
                list.add(stored(element));
            }
            return list;
        }
        return value;
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
        Map<String, Object> empty = new TreeMap<String, Object>();
        empty.put(LAYOUT_KEY, Integer.valueOf(LAYOUT));
        state.setVariableLocal(STATE_MARKER, Variables.objectValue(empty).create());
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

    // --- the turn counter and its caps -----------------------------------------

    /** Turns taken so far, and the id of the turn last counted. */
    private static final String TURNS = "turns";
    private static final String TURN_MARKER = "turnMarker";

    /** {@code camunda:property} on the scope overriding {@link #DEFAULT_MAX_TURNS}. */
    public static final String MAX_TURNS_PROPERTY = "cibseven.agentic.maxTurns";

    /** {@code camunda:property} on the scope overriding {@link #DEFAULT_MAX_MODEL_CALLS}. */
    public static final String MAX_MODEL_CALLS_PROPERTY = "cibseven.agentic.maxModelCalls";

    /**
     * Turns allowed before the agent must stop.
     *
     * <p>A loop that invokes a language model per turn has no natural end, so an
     * unbounded one is unbounded spend on a bad prompt. The scope's
     * {@link #MAX_TURNS_PROPERTY} overrides it.
     *
     * <p>It stops the agent, not the work the agent already started. Over the cap no
     * model is asked again, but a scope with a child still running is left standing and
     * ends when that child does — ending it there would cancel a task someone may be
     * halfway through, which is the same reason {@code completeScope} refuses and
     * {@code endOfTurn} waits.
     */
    public static final int DEFAULT_MAX_TURNS = 10;

    /**
     * Model calls allowed within one turn — the second bound, and a different one:
     * the turn cap cannot see a model that keeps calling tools and never ends its
     * turn. Each call is a step of its own (CIB7-2148). The same number bounds the
     * tool calls one answer may ask for.
     */
    public static final int DEFAULT_MAX_MODEL_CALLS = 25;

    /**
     * Set while a turn goes on in a further step: the model asked for tools, they
     * ran, and it has not answered yet. A turn is one model call per job, and this
     * is what tells the next job that it continues a turn rather than starting one.
     */
    private static final String TURN_IN_PROGRESS = "turnInProgress";

    /** Model calls in the turn now running, and the step last counted. */
    private static final String MODEL_CALLS = "modelCalls";
    private static final String MODEL_CALL_MARKER = "modelCallMarker";

    /**
     * The tools the agent called in the turn now running, oldest first, one entry per call
     * that was carried out. Every step sends the task again, and the memory window can drop
     * the steps that made these calls; a further step lists them so the agent goes on from
     * there instead of starting the task again.
     */
    private static final String CALLED_THIS_TURN = "calledThisTurn";

    /** Turns taken so far, zero before the first one is counted. */
    public static int turns(ExecutionEntity scopeExecution) {
        Object raw = stateValue(scopeExecution, TURNS);
        return (raw instanceof Number) ? ((Number) raw).intValue() : 0;
    }

    /**
     * Counts the turn {@code turnId} belongs to, once, and returns the count.
     *
     * <p>A turn and its retry are one turn, not two: the id — the turn job's, in
     * practice — is the same for both, so the retry finds it already counted. A
     * {@code null} id counts every call as its own turn, which is the safe
     * direction: a cap that bites early is better than one that never bites.
     */
    public static int beginTurn(ExecutionEntity scopeExecution, String turnId) {
        if (turnId != null && turnId.equals(stateValue(scopeExecution, TURN_MARKER))) {
            return turns(scopeExecution);
        }
        int counted = turns(scopeExecution) + 1;
        Map<String, Object> changes = new LinkedHashMap<String, Object>();
        changes.put(TURN_MARKER, turnId);
        changes.put(TURNS, Integer.valueOf(counted));
        // A new turn, so its model calls count from zero and it has called nothing yet.
        changes.put(MODEL_CALLS, Integer.valueOf(0));
        changes.put(MODEL_CALL_MARKER, null);
        changes.put(CALLED_THIS_TURN, null);
        updateState(scopeExecution, changes);
        return counted;
    }

    /** Records that the turn now running called {@code tool}, and the call was carried out. */
    public static void recordCall(ExecutionEntity scopeExecution, String tool) {
        List<String> called = calledThisTurn(scopeExecution);
        called.add(tool);
        updateState(scopeExecution, CALLED_THIS_TURN, called);
    }

    /** The tools the turn now running called, oldest first; empty before its first call. */
    public static List<String> calledThisTurn(ExecutionEntity scopeExecution) {
        Object raw = stateValue(scopeExecution, CALLED_THIS_TURN);
        List<String> called = new ArrayList<String>();
        if (raw instanceof Collection) {
            for (Object tool : (Collection<?>) raw) {
                called.add(String.valueOf(tool));
            }
        }
        return called;
    }

    /** Model calls in the turn now running, zero before the first one is counted. */
    public static int modelCalls(ExecutionEntity scopeExecution) {
        Object raw = stateValue(scopeExecution, MODEL_CALLS);
        return (raw instanceof Number) ? ((Number) raw).intValue() : 0;
    }

    /**
     * Counts the model call of the step {@code stepId}, once, and returns the count
     * for the turn. A step and its retry are one call against the cap, as a turn and
     * its retry are one turn; a {@code null} id counts every call.
     */
    public static int countModelCall(ExecutionEntity scopeExecution, String stepId) {
        if (stepId != null && stepId.equals(stateValue(scopeExecution, MODEL_CALL_MARKER))) {
            return modelCalls(scopeExecution);
        }
        int counted = modelCalls(scopeExecution) + 1;
        Map<String, Object> changes = new LinkedHashMap<String, Object>();
        changes.put(MODEL_CALL_MARKER, stepId);
        changes.put(MODEL_CALLS, Integer.valueOf(counted));
        updateState(scopeExecution, changes);
        return counted;
    }

    /** Whether the next step continues a turn the model has not ended yet. */
    public static boolean isTurnInProgress(ExecutionEntity scopeExecution) {
        return Boolean.TRUE.equals(stateValue(scopeExecution, TURN_IN_PROGRESS));
    }

    /** The model called tools and answers in a further step of the same turn. */
    public static void continueTurn(ExecutionEntity scopeExecution) {
        updateState(scopeExecution, TURN_IN_PROGRESS, Boolean.TRUE);
    }

    /** The turn is over: the model answered, or a limit ended it. */
    public static void endTurn(ExecutionEntity scopeExecution) {
        if (find(scopeExecution) != null) {
            updateState(scopeExecution, TURN_IN_PROGRESS, null);
        }
    }

    /**
     * A positive-integer {@code camunda:property} of the scope, or {@code fallback}.
     *
     * <p>A value that is not a positive number falls back rather than failing: the
     * value is a modelling mistake, and refusing to run would turn it into an
     * outage, while the fallback still ends the loop.
     */
    public static int positiveProperty(ExecutionEntity scopeExecution, String property,
            int fallback) {
        PvmActivity activity = scopeExecution.getActivity();
        Object raw = (activity == null)
                ? null
                : activity.getProperty(BpmnProperties.EXTENSION_PROPERTIES.getName());
        if (raw instanceof java.util.Map) {
            Object configured = ((java.util.Map<?, ?>) raw).get(property);
            if (configured != null) {
                try {
                    int parsed = Integer.parseInt(String.valueOf(configured).trim());
                    if (parsed > 0) {
                        return parsed;
                    }
                } catch (NumberFormatException ignored) {
                    // falls through to the fallback below
                }
            }
        }
        return fallback;
    }
}
