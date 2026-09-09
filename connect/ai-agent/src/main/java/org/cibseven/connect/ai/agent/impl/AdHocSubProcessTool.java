package org.cibseven.connect.ai.agent.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.history.HistoricDetail;
import org.cibseven.bpm.engine.history.HistoricVariableUpdate;
import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocSubProcessActivityBehavior;
import org.cibseven.bpm.engine.impl.bpmn.helper.BpmnProperties;
import org.cibseven.bpm.engine.impl.context.BpmnExecutionContext;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.PvmActivity;
import org.cibseven.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;
import org.cibseven.bpm.engine.runtime.ActivityInstance;
import org.cibseven.bpm.engine.variable.type.ValueType;
import org.cibseven.bpm.engine.variable.value.TypedValue;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * {@code @Tool} class that lets the model see and start the activities of the ad
 * hoc sub process it is running in, and end that scope when it is done.
 *
 * <p>Wired in through the connector's {@code toolClasses} input, like
 * {@link ProcessStarterTool}. Requires no change to the connector itself.
 *
 * <h3>What the model has to be given</h3>
 * The agent service task has to sit <em>inside</em> an ad hoc sub process, and
 * that scope has to carry {@code explicitCompletionOnly="true"}. Without the
 * property the scope ends as soon as the first activity the agent starts and
 * finishes does. With {@code adHocDriverActivity} naming the agent task, the
 * engine gives the agent a further turn whenever another child ends.
 *
 * <h3>How the model learns the loop's state</h3>
 * Through {@link #listAvailableActivities()}, not through the system message. That
 * is deliberate: a rendered context block in the system message is process data
 * placed next to instructions, and hardening it needs delimiting, escaping, size
 * caps and its own audit event. A tool result is a channel already built for data,
 * so none of those are needed here.
 *
 * <h3>No thread hop, unlike ProcessStarterTool</h3>
 * {@link ProcessStarterTool} deliberately runs each engine call on a separate
 * thread so it gets its own transaction, which is right for starting a
 * <em>different</em> process instance. Here every call mutates the instance this
 * connector is already running in. A second thread would wait for row locks the
 * current transaction holds while the current transaction waits for the tool call,
 * so the calls stay on this thread and join the surrounding transaction — which is
 * also what makes them roll back with the activity if the turn fails.
 */
public class AdHocSubProcessTool {

    private static final Logger LOG = LoggerFactory.getLogger(AdHocSubProcessTool.class);

    /** {@code camunda:property} on the scope overriding {@link #DEFAULT_MAX_TURNS}. */
    static final String MAX_TURNS_PROPERTY = "adHocMaxTurns";

    /**
     * Turns allowed before the agent must stop.
     *
     * <p>A loop that invokes a language model per turn has no natural end, so an
     * unbounded one is unbounded spend on a bad prompt. Ten is what Camunda 8
     * defaults its model-call limit to.
     */
    static final int DEFAULT_MAX_TURNS = 10;

    /**
     * Characters allowed per reported result value.
     *
     * <p>A result variable can hold a whole document, and prompt size is the
     * dominant cost lever, so a long value is cut and says so rather than being
     * sent whole.
     */
    static final int MAX_RESULT_VALUE_CHARS = 2000;

    /** Characters allowed across all result values of one turn. */
    static final int MAX_RESULT_BLOCK_CHARS = 20000;

    @Tool("Lists what this agent can do in the ad hoc sub process it is running in, and what it is "
            + "already waiting for. 'activities' are the ones you may start, each with 'id', 'name' and "
            + "'documentation'. 'finishedSinceLastTurn' are the ones that completed since you last ran, "
            + "each with 'activityId' and 'results' — the values of the variables that activity wrote. "
            + "A long value is truncated and says so; a file or an object is replaced by a short "
            + "description of its type. 'resultsFrom' says where the values came from, and an empty "
            + "'results' with a note means nothing could be determined rather than that the activity "
            + "produced nothing. 'stillRunning' are the ones you already started that have not finished "
            + "— do not start those again, and do not try to end the scope while they are listed. "
            + "An activity carrying 'startableNow' false cannot be started yet: it depends on work "
            + "still in flight, and 'blockedBecause' says which. Do not attempt it, it will be "
            + "refused; wait for the turn you get when that work finishes. "
            + "'turn' is how many turns you have taken and 'maxTurns' the limit. Call this first in "
            + "every turn.")
    public Map<String, Object> listAvailableActivities() {
        ExecutionEntity scope = requireAdHocScope();
        ProcessEngine engine = requireEngine();
        String adHocActivityId = scope.getActivity().getId();

        // Reconcile first, so the answer describes the situation the model is about to
        // act on rather than the one at the end of the previous turn.
        Map<String, String> finished =
                AdHocLoopState.harvestFinished(scope, runningActivityInstanceIds(engine, scope));
        AdHocLoopState.countTurn(scope);

        // Read after the reconciliation above, so an activity that finished during
        // the previous turn no longer counts as blocking.
        List<String> othersRunning = otherRunningActivityIds(engine, scope);

        List<Map<String, Object>> activities = new ArrayList<>();
        Map<String, List<String>> declaredResults = new LinkedHashMap<>();
        for (AdHocToolCatalog.Entry entry : AdHocToolCatalog.read(
                engine.getRepositoryService(), scope.getProcessDefinitionId(), adHocActivityId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", entry.getId());
            item.put("name", entry.getName());
            item.put("documentation", entry.getDocumentation());
            // Only said when it is true, so the ordinary case costs no prompt.
            if (entry.isBlockedWhileOthersRun() && !othersRunning.isEmpty()) {
                item.put("startableNow", Boolean.FALSE);
                item.put("blockedBecause", "Depends on work still in flight: " + othersRunning
                        + ". You will get another turn when that finishes.");
            }
            activities.add(item);
            declaredResults.put(entry.getId(), entry.getResultVariables());
        }

        Map<String, String> pending = AdHocLoopState.pending(scope);
        LOG.debug("listAvailableActivities: scope='{}', {} startable, {} finished, {} pending, turn {}",
                adHocActivityId, activities.size(), finished.size(), pending.size(),
                AdHocLoopState.turns(scope));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("adHocActivityId", adHocActivityId);
        result.put("activities", activities);
        result.put("finishedSinceLastTurn", describeFinished(engine, scope, finished, declaredResults));
        result.put("stillRunning", new ArrayList<>(pending.values()));
        result.put("turn", AdHocLoopState.turns(scope));
        result.put("maxTurns", maxTurns(scope));
        if (pending.isEmpty()) {
            // Said out loud, because the scope is parked and the agent is its driver:
            // a driver does not re-activate itself, so if this turn ends without
            // starting something that waits and without completing the scope, nothing
            // will wake it again. The timer boundary event on the scope is the net
            // under that, not a substitute for deciding.
            result.put("note", "Nothing you started is still running. If you end this turn without "
                    + "starting an activity that waits for a person or another system, and without "
                    + "calling completeScope, the process stops here and nothing will wake it. "
                    + "Decide now.");
        }
        return result;
    }

    @Tool("Starts one activity of the ad hoc sub process this agent is running in, optionally with "
            + "variables that only that activity sees. 'status' tells you what happened: 'finished' "
            + "means it ran without waiting and is already done, and 'results' holds the values it "
            + "wrote — this is the only turn in which you see them, so use them now. 'waiting' means "
            + "it waits for a person or another system, 'results' is empty, and you will get another "
            + "turn when it finishes. Call this once per activity you want started; do not try to "
            + "start several in one call.")
    public Map<String, Object> startActivity(
            @P("Id of the activity to start, exactly as returned by listAvailableActivities")
            String activityId,
            @P("Variables for this activity only; pass an empty object when it needs none")
            Map<String, Object> variables) {

        ExecutionEntity scope = requireAdHocScope();
        ProcessEngine engine = requireEngine();

        int turns = AdHocLoopState.turns(scope);
        int limit = maxTurns(scope);
        if (turns > limit) {
            throw new AgentConnectorException("The turn limit of " + limit + " for this ad hoc sub "
                    + "process is reached (" + turns + " turns taken), so no further activity is started. "
                    + "End the scope, or let a person take over.");
        }

        List<String> othersRunning = otherRunningActivityIds(engine, scope);
        if (!othersRunning.isEmpty() && isBlockedWhileOthersRun(engine, scope, activityId)) {
            throw new AgentConnectorException("Cannot start '" + activityId + "' while "
                    + othersRunning + " " + (othersRunning.size() == 1 ? "is" : "are")
                    + " still running. This activity is marked as depending on work that is still"
                    + " in flight — a decision someone else has to make first. Start something"
                    + " else, or end your turn: you will get another turn when that work"
                    + " finishes.");
        }

        Map<String, Map<String, Object>> perActivity = (variables == null || variables.isEmpty())
                ? null
                : Collections.singletonMap(activityId, variables);

        List<String> activityInstanceIds = engine.getRuntimeService().triggerAdHocActivities(
                scope.getId(), Collections.singletonList(activityId), perActivity);

        String activityInstanceId = activityInstanceIds.isEmpty() ? null : activityInstanceIds.get(0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activityId", activityId);
        result.put("activityInstanceId", activityInstanceId);

        // Whether the activity is still in the runtime tree decides everything else.
        // One that runs without waiting has already finished inside the call above,
        // and this is the only turn in which the agent can see its result: the agent
        // is the scope's driver and is running, so that activity's end gives it no
        // further turn.
        boolean stillRunning = activityInstanceId != null
                && runningActivityInstanceIds(engine, scope).contains(activityInstanceId);

        if (stillRunning) {
            AdHocLoopState.addPending(scope, activityInstanceId, activityId);
            result.put("status", "waiting");
            result.put("results", Collections.emptyMap());
            result.put("resultsNote", "Still running. You will get another turn when it finishes.");
        } else {
            // Not recorded as pending: there is nothing to wait for, and an entry that
            // is already finished would refuse completeScope until a later turn
            // reconciled it away.
            result.put("status", "finished");
            // Model-derived names rather than the history: the activity ran in the
            // transaction this call is part of, and whether its variable updates are
            // already queryable from history here is unmeasured. The values themselves
            // are read from the scope, where an output mapping has just written them.
            List<String> names = declaredResultVariables(engine, scope, activityId);
            ResultBlock block = readResults(scope, names, MAX_RESULT_BLOCK_CHARS);
            result.put("results", block.values);
            result.put("resultsFrom", names.isEmpty() ? "nothing declared" : "model declaration");
            if (block.note != null) {
                result.put("resultsNote", block.note);
            } else if (names.isEmpty()) {
                result.put("resultsNote", "This activity declares no output mapping, result variable "
                        + "or form fields, so what it wrote cannot be determined from the model. Add "
                        + "one of those, or camunda:property adHocResultVariables, if the agent needs "
                        + "its values.");
            }
        }

        LOG.debug("startActivity: scope='{}', activity='{}', activityInstanceId='{}', status='{}'",
                scope.getActivity().getId(), activityId, activityInstanceId, result.get("status"));
        publishAuditRecord("startActivity", scope, activityId, activityInstanceId);
        return result;
    }

    @Tool("Ends the ad hoc sub process this agent is running in, so the process continues after it. "
            + "Call this only when nothing you started is still running and no further activity is "
            + "needed. It is refused while something is still running, because ending the scope cancels "
            + "whatever is inside it, including a task a person has not finished.")
    public Map<String, Object> completeScope() {
        ExecutionEntity scope = requireAdHocScope();
        ProcessEngine engine = requireEngine();

        // Reconcile before refusing, so an entry whose activity finished during this
        // turn does not block the scope on stale bookkeeping.
        AdHocLoopState.harvestFinished(scope, runningActivityInstanceIds(engine, scope));
        Map<String, String> stillPending = AdHocLoopState.pending(scope);
        if (!stillPending.isEmpty()) {
            throw new AgentConnectorException("Cannot end the ad hoc sub process while "
                    + stillPending.values() + " have not finished. Ending it would cancel them, including a "
                    + "task a person may be working on. Wait: you will get another turn when they finish.");
        }

        String adHocActivityId = scope.getActivity().getId();
        engine.getRuntimeService().completeAdHocSubProcess(scope.getId());
        LOG.debug("completeScope: scope='{}'", adHocActivityId);
        publishAuditRecord("completeScope", scope, null, null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("adHocActivityId", adHocActivityId);
        result.put("completed", true);
        return result;
    }

    /**
     * The execution of the ad hoc sub process enclosing the activity this connector
     * is running in.
     *
     * <p>Identified by the activity's behaviour rather than by element name, the same
     * way {@code TriggerAdHocActivitiesCmd} and the migration validator do, so the
     * check cannot drift from what the runtime actually does.
     *
     * @throws AgentConnectorException when the connector is not running inside an ad
     *     hoc sub process, or not inside the engine at all. Both are configuration
     *     errors, and a tool that silently did nothing would leave the model
     *     inventing an explanation for it.
     */
    private static ExecutionEntity requireAdHocScope() {
        BpmnExecutionContext executionContext = Context.getBpmnExecutionContext();
        ExecutionEntity execution = (executionContext == null) ? null : executionContext.getExecution();
        if (execution == null) {
            throw new AgentConnectorException(
                    "The ad hoc sub process tool needs a running BPMN activity, but no BPMN execution is "
                            + "available on this thread. It only works when the connector runs from a service task "
                            + "inside the engine.");
        }

        ExecutionEntity current = execution;
        while (current != null) {
            PvmActivity activity = current.getActivity();
            if (activity != null
                    && activity.getActivityBehavior() instanceof AdHocSubProcessActivityBehavior) {
                return current;
            }
            current = current.getParent();
        }

        throw new AgentConnectorException(
                "The ad hoc sub process tool is configured, but activity '"
                        + (execution.getActivity() == null ? "?" : execution.getActivity().getId())
                        + "' is not inside an ad hoc sub process. Move the agent task into the ad hoc sub process, "
                        + "or remove " + AdHocSubProcessTool.class.getName() + " from 'toolClasses'.");
    }

    /** The engine captured by the connector on its calling thread. */
    private static ProcessEngine requireEngine() {
        ProcessEngine engine = ProcessStarterToolContext.getEngine();
        if (engine == null) {
            throw new AgentConnectorException(
                    "No ProcessEngine available in ProcessStarterToolContext — the connector could not "
                            + "resolve the engine on its calling thread.");
        }
        return engine;
    }

    /**
     * The activity instance ids currently alive in the process instance.
     *
     * <p>The engine's runtime activity instance tree is the authority on what is
     * still running, and comparing the pending list against it needs no separate
     * notification: whatever is absent has finished, been cancelled or been deleted,
     * and all three mean "stop waiting for it".
     */
    private static Set<String> runningActivityInstanceIds(ProcessEngine engine, ExecutionEntity scope) {
        Set<String> ids = new HashSet<>();
        ActivityInstance tree = engine.getRuntimeService()
                .getActivityInstance(scope.getProcessInstanceId());
        if (tree != null) {
            collectIds(tree, ids);
        }
        return ids;
    }

    private static void collectIds(ActivityInstance node, Set<String> ids) {
        ids.add(node.getId());
        for (ActivityInstance child : node.getChildActivityInstances()) {
            collectIds(child, ids);
        }
    }

    /**
     * The activity ids alive inside this scope, excluding the caller's own.
     *
     * <p>Excluding the caller matters: the agent is a child of the scope and is
     * running while it asks, so without that exclusion a marked activity would be
     * blocked in every turn, for ever.
     *
     * <p>Asked of the engine rather than of {@link AdHocLoopState}, because the
     * pending list holds only what the <em>agent</em> started. A decision a person
     * activated over the REST API is exactly the kind this marking is about, and it
     * would be missing there.
     *
     * <p>Read from the execution tree, not the activity instance tree. A child
     * marked {@code camunda:asyncBefore} has no activity instance while its job
     * waits to be picked up, so the activity instance tree reports it as absent and
     * a marked activity would not be held back by queued work. Its execution
     * exists and already carries the activity — the same reason the engine's own
     * {@code isDriverPresent} reads executions.
     *
     * <p>Only the scope's own children, so a parallel branch elsewhere in the
     * process instance does not block the agent — that branch has nothing to do
     * with this scope's work. {@code getNonEventScopeExecutions()} keeps the agent
     * state execution out, which carries no activity and never runs.
     */
    private static List<String> otherRunningActivityIds(ProcessEngine engine, ExecutionEntity scope) {
        String ownExecutionId = ownExecutionId();
        Set<String> ids = new LinkedHashSet<>();
        for (PvmExecutionImpl child : ((PvmExecutionImpl) scope).getNonEventScopeExecutions()) {
            if (ownExecutionId != null && ownExecutionId.equals(child.getId())) {
                continue;
            }
            PvmActivity activity = child.getActivity();
            if (activity != null) {
                ids.add(activity.getId());
            }
        }
        return new ArrayList<>(ids);
    }

    /** The execution of the activity this tool is being called from. */
    private static String ownExecutionId() {
        BpmnExecutionContext executionContext = Context.getBpmnExecutionContext();
        ExecutionEntity execution = (executionContext == null) ? null : executionContext.getExecution();
        return (execution == null) ? null : execution.getId();
    }

    /** Whether {@code activityId} carries the blocking marking in the model. */
    private static boolean isBlockedWhileOthersRun(ProcessEngine engine, ExecutionEntity scope,
                                                   String activityId) {
        for (AdHocToolCatalog.Entry entry : AdHocToolCatalog.read(engine.getRepositoryService(),
                scope.getProcessDefinitionId(), scope.getActivity().getId())) {
            if (activityId.equals(entry.getId())) {
                return entry.isBlockedWhileOthersRun();
            }
        }
        return false;
    }

    /**
     * Describes the activities that finished since the previous turn, each with the
     * values it wrote.
     *
     * <p>The history is asked first, because it is the only source that knows what
     * an activity <em>actually</em> wrote: at history level {@code full} every
     * variable update carries the activity instance that caused it, so a delegate
     * calling {@code setVariable} with no mapping anywhere is covered too, and a
     * variable the activity never touched cannot appear. The model declaration is
     * the fallback, for a deployment on a lower history level.
     *
     * <p>Values are read now rather than when the activity ended, because the
     * connector is not on the thread that ends a child. That is also correct: an
     * output mapping writes to the scope as the child ends, so by the time this runs
     * the values are in place.
     *
     * <p>Two performances of the same activity write the same variable names, so the
     * second overwrites the first. That is the documented behaviour of the activation
     * API's per-activity variables and applies here unchanged; an activity meant to
     * run repeatedly should write into a collection rather than a scalar.
     */
    private static List<Map<String, Object>> describeFinished(ProcessEngine engine,
            ExecutionEntity scope, Map<String, String> finished,
            Map<String, List<String>> declaredResults) {

        List<Map<String, Object>> described = new ArrayList<>();
        int budget = MAX_RESULT_BLOCK_CHARS;

        for (Map.Entry<String, String> entry : finished.entrySet()) {
            String activityInstanceId = entry.getKey();
            String activityId = entry.getValue();

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("activityId", activityId);
            item.put("activityInstanceId", activityInstanceId);

            List<String> names = historyResultVariables(engine, activityInstanceId);
            String source = "history";
            if (names.isEmpty()) {
                List<String> declared = declaredResults.get(activityId);
                names = (declared == null) ? Collections.<String>emptyList() : declared;
                source = names.isEmpty() ? "nothing determined" : "model declaration";
            }

            ResultBlock block = readResults(scope, names, budget);
            budget -= block.charsUsed;
            item.put("results", block.values);
            item.put("resultsFrom", source);
            if (block.note != null) {
                item.put("resultsNote", block.note);
            } else if (names.isEmpty()) {
                item.put("resultsNote", "Neither the history nor the model says what this activity "
                        + "wrote. Either the history level is below 'full' and the activity declares "
                        + "no output mapping, result variable or form fields, or it wrote nothing.");
            }
            described.add(item);
        }
        return described;
    }

    /**
     * The names of the variables the activity instance actually wrote, from history.
     *
     * <p>Empty when the history level does not record variable updates — only
     * {@code full} does — or when the activity wrote nothing. The two cases are not
     * distinguishable here, which is why the caller falls back to the model
     * declaration rather than reporting "wrote nothing".
     *
     * <p>Failures are swallowed: a missing history is a deployment choice, not a
     * reason to fail the agent's turn.
     */
    private static List<String> historyResultVariables(ProcessEngine engine, String activityInstanceId) {
        if (activityInstanceId == null) {
            return Collections.emptyList();
        }
        try {
            Set<String> names = new LinkedHashSet<>();
            List<HistoricDetail> details = engine.getHistoryService()
                    .createHistoricDetailQuery()
                    .activityInstanceId(activityInstanceId)
                    .variableUpdates()
                    .list();
            for (HistoricDetail detail : details) {
                if (detail instanceof HistoricVariableUpdate) {
                    String name = ((HistoricVariableUpdate) detail).getVariableName();
                    if (name != null && !name.isEmpty()) {
                        names.add(name);
                    }
                }
            }
            return new ArrayList<>(names);
        } catch (RuntimeException e) {
            LOG.debug("Could not read variable updates for activity instance '{}': {}",
                    activityInstanceId, e.toString());
            return Collections.emptyList();
        }
    }

    /** The result variables the model declares for {@code activityId}, or an empty list. */
    private static List<String> declaredResultVariables(ProcessEngine engine, ExecutionEntity scope,
            String activityId) {
        for (AdHocToolCatalog.Entry entry : AdHocToolCatalog.read(engine.getRepositoryService(),
                scope.getProcessDefinitionId(), scope.getActivity().getId())) {
            if (activityId.equals(entry.getId())) {
                return entry.getResultVariables();
            }
        }
        return Collections.emptyList();
    }

    /** The values of {@code names}, capped, plus how much of the budget was used. */
    private static ResultBlock readResults(ExecutionEntity scope, List<String> names, int budget) {
        ResultBlock block = new ResultBlock();
        int remaining = budget;
        for (String name : names) {
            if (remaining <= 0) {
                block.note = "Some values were omitted because the size limit of "
                        + MAX_RESULT_BLOCK_CHARS + " characters for one turn was reached.";
                break;
            }
            Object value = readResult(scope, name);
            block.values.put(name, value);
            int used = String.valueOf(value).length();
            remaining -= used;
            block.charsUsed += used;
        }
        return block;
    }

    /**
     * One result value, in a form that is safe to put in a tool result.
     *
     * <p>Read without deserializing, so a customer POJO whose class is absent from
     * this classloader does not fail the turn — the normal case for an object
     * variable, and the reason {@code getValue()} is not called on one. Only a
     * primitive value is passed through; anything else becomes a descriptor naming
     * its type, which keeps a file, a byte array or a serialized object out of the
     * prompt.
     */
    private static Object readResult(ExecutionEntity scope, String name) {
        TypedValue typed = scope.getVariableTyped(name, false);
        if (typed == null) {
            return null;
        }
        ValueType type = typed.getType();
        if (type == null || !type.isPrimitiveValueType()) {
            return "<" + (type == null ? "unknown" : type.getName()) + " value, not shown>";
        }
        Object value = typed.getValue();
        if (value instanceof String && ((String) value).length() > MAX_RESULT_VALUE_CHARS) {
            String text = (String) value;
            return text.substring(0, MAX_RESULT_VALUE_CHARS)
                    + "… (truncated, " + text.length() + " characters total)";
        }
        return value;
    }

    /** The values read for one activity, with what they cost and why some are missing. */
    private static final class ResultBlock {
        private final Map<String, Object> values = new LinkedHashMap<>();
        private int charsUsed;
        private String note;
    }

    /**
     * The turn cap for this scope, from {@link #MAX_TURNS_PROPERTY} or
     * {@link #DEFAULT_MAX_TURNS}.
     *
     * <p>Read from the activity's extension properties, which are parsed at
     * deployment and never become process variables, so a child of the scope cannot
     * raise its own agent's limit.
     */
    private static int maxTurns(ExecutionEntity scope) {
        Object raw = scope.getActivity().getProperty(BpmnProperties.EXTENSION_PROPERTIES.getName());
        if (raw instanceof Map) {
            Object configured = ((Map<?, ?>) raw).get(MAX_TURNS_PROPERTY);
            if (configured != null) {
                try {
                    return Integer.parseInt(String.valueOf(configured).trim());
                } catch (NumberFormatException e) {
                    LOG.warn("Ignoring unparseable {} '{}'; using {}",
                            MAX_TURNS_PROPERTY, configured, DEFAULT_MAX_TURNS);
                }
            }
        }
        return DEFAULT_MAX_TURNS;
    }

    /**
     * Publishes a side-effect record onto the active {@link AgentChatListener} so the
     * audit log carries what the agent changed in the running process, and under
     * which principal. No-op outside a connector turn.
     */
    private static void publishAuditRecord(String tool, ExecutionEntity scope,
                                           String activityId, String activityInstanceId) {
        AgentChatListener listener = ProcessStarterToolContext.getActiveListener();
        if (listener == null) {
            return;
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("tool", tool);
        record.put("processInstanceId", scope.getProcessInstanceId());
        record.put("adHocActivityId", scope.getActivity() == null ? null : scope.getActivity().getId());
        record.put("adHocExecutionId", scope.getId());
        if (activityId != null) {
            record.put("activityId", activityId);
        }
        if (activityInstanceId != null) {
            record.put("activityInstanceId", activityInstanceId);
        }
        record.put("turn", AdHocLoopState.turns(scope));
        try {
            listener.recordToolSideEffect(record);
        } catch (RuntimeException e) {
            // Audit publishing must never break the tool itself.
            LOG.debug("Could not publish tool audit record: {}", e.toString());
        }
    }
}
