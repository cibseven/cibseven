package org.cibseven.connect.ai.agent.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocSubProcessActivityBehavior;
import org.cibseven.bpm.engine.impl.bpmn.helper.BpmnProperties;
import org.cibseven.bpm.engine.impl.context.BpmnExecutionContext;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.PvmActivity;
import org.cibseven.bpm.engine.runtime.ActivityInstance;

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

    @Tool("Lists what this agent can do in the ad hoc sub process it is running in, and what it is "
            + "already waiting for. 'activities' are the ones you may start, each with 'id', 'name' and "
            + "'documentation'. 'finishedSinceLastTurn' are the ones that completed since you last ran; "
            + "their results are process variables now. 'stillRunning' are the ones you already started "
            + "that have not finished — do not start those again, and do not try to end the scope while "
            + "they are listed. 'turn' is how many turns you have taken and 'maxTurns' the limit. Call "
            + "this first in every turn.")
    public Map<String, Object> listAvailableActivities() {
        ExecutionEntity scope = requireAdHocScope();
        ProcessEngine engine = requireEngine();
        String adHocActivityId = scope.getActivity().getId();

        // Reconcile first, so the answer describes the situation the model is about to
        // act on rather than the one at the end of the previous turn.
        Map<String, String> finished =
                AdHocLoopState.harvestFinished(scope, runningActivityInstanceIds(engine, scope));
        AdHocLoopState.countTurn(scope);

        List<Map<String, Object>> activities = new ArrayList<>();
        for (AdHocToolCatalog.Entry entry : AdHocToolCatalog.read(
                engine.getRepositoryService(), scope.getProcessDefinitionId(), adHocActivityId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", entry.getId());
            item.put("name", entry.getName());
            item.put("documentation", entry.getDocumentation());
            activities.add(item);
        }

        Map<String, String> pending = AdHocLoopState.pending(scope);
        LOG.debug("listAvailableActivities: scope='{}', {} startable, {} finished, {} pending, turn {}",
                adHocActivityId, activities.size(), finished.size(), pending.size(),
                AdHocLoopState.turns(scope));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("adHocActivityId", adHocActivityId);
        result.put("activities", activities);
        result.put("finishedSinceLastTurn", new ArrayList<>(finished.values()));
        result.put("stillRunning", new ArrayList<>(pending.values()));
        result.put("turn", AdHocLoopState.turns(scope));
        result.put("maxTurns", maxTurns(scope));
        return result;
    }

    @Tool("Starts one activity of the ad hoc sub process this agent is running in, optionally with "
            + "variables that only that activity sees. Returns 'activityInstanceId'. The activity runs on "
            + "its own: a fully automatic one has already finished when this returns, one that waits for "
            + "a person or for another system is now waiting, and you will get another turn when it "
            + "finishes. This call does not return the activity's result — results become process "
            + "variables. Call this once per activity you want started; do not try to start several in "
            + "one call.")
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

        Map<String, Map<String, Object>> perActivity = (variables == null || variables.isEmpty())
                ? null
                : Collections.singletonMap(activityId, variables);

        List<String> activityInstanceIds = engine.getRuntimeService().triggerAdHocActivities(
                scope.getId(), Collections.singletonList(activityId), perActivity);

        String activityInstanceId = activityInstanceIds.isEmpty() ? null : activityInstanceIds.get(0);
        AdHocLoopState.addPending(scope, activityInstanceId, activityId);

        LOG.debug("startActivity: scope='{}', activity='{}', activityInstanceId='{}'",
                scope.getActivity().getId(), activityId, activityInstanceId);
        publishAuditRecord("startActivity", scope, activityId, activityInstanceId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activityId", activityId);
        result.put("activityInstanceId", activityInstanceId);
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
