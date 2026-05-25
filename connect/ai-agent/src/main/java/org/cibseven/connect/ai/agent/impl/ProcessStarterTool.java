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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.history.HistoricProcessInstance;
import org.cibseven.bpm.engine.history.HistoricVariableInstance;
import org.cibseven.bpm.engine.impl.identity.Authentication;
import org.cibseven.bpm.engine.runtime.ProcessInstance;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * {@code @Tool} class wired into the AI agent connector that lets the LLM
 * start a CIB seven process, wait for it to terminate and read its outputs in
 * a single call.
 *
 * <p>All engine interactions run on a dedicated thread (via
 * {@link #runAsCaller(Function)}) so that each API call gets its own
 * {@code CommandContext} and transaction — avoiding stale MyBatis caches
 * when this tool is invoked from inside a JobExecutor command context.
 *
 * <h3>Authorization</h3>
 * Engine calls run under the authentication carried by
 * {@link ProcessStarterToolContext}, populated by {@link AgentConnectorImpl}
 * from {@code IdentityService.getCurrentAuthentication()} at the start of the
 * connector invocation.
 */
public class ProcessStarterTool {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessStarterTool.class);

    /** Default polling budget when the caller does not supply {@code maxRetries}. */
    static final int DEFAULT_MAX_RETRIES = 5;

    /** Default delay between polls when the caller does not supply {@code pollIntervalMillis}. */
    static final long DEFAULT_POLL_INTERVAL_MILLIS = 2000L;

    /**
     * Dedicated executor for engine calls. A cached daemon-thread pool is used
     * instead of the JVM-wide {@code ForkJoinPool.commonPool()} because this
     * tool performs blocking engine queries and {@code Thread.sleep} polling —
     * the common pool is sized for CPU-bound parallel work and is shared with
     * the rest of the JVM (e.g. parallel streams), so blocking it would
     * degrade unrelated workloads.
     */
    private static final ExecutorService ENGINE_EXECUTOR = Executors.newCachedThreadPool(
            new ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger();
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "cibseven-agent-tool-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });

    @Tool("Starts a CIB seven process by its definition key, polls until it ends (or until the "
            + "retry budget is exhausted) and returns its output variables. The result map "
            + "contains: 'processInstanceId', 'processDefinitionId', 'state' (one of 'ACTIVE', "
            + "'SUSPENDED', 'COMPLETED', 'EXTERNALLY_TERMINATED', 'INTERNALLY_TERMINATED'), "
            + "'ended' (true once the process is no longer running), 'attempts' (number of polls "
            + "performed) and 'outputs' (process variables whose names start with the given "
            + "prefix). Use this tool when you need to start a process and react to its result; "
            + "do not orchestrate your own polling loop on the LLM side.")
    public Map<String, Object> runProcessByKey(
            @P("Process definition key (e.g. mcpProcess_hello)") String key,
            @P("Process variables to pass at start; may be empty") Map<String, Object> variables,
            @P("Prefix used to filter output variables (e.g. 'out_'); pass an empty string to return all variables") String outputPrefix,
            @P("Maximum number of polls to perform after starting the process before giving up. "
                    + "Omit or pass null to use the default of 5 retries.") Integer maxRetries,
            @P("Milliseconds to wait between polls (e.g. 3000 for a 3-second interval). "
                    + "Omit or pass null to use the default of 2000 ms.") Long pollIntervalMillis) {
        String prefix = outputPrefix != null ? outputPrefix : "";
        int retries = Math.max(0, maxRetries != null ? maxRetries : DEFAULT_MAX_RETRIES);
        long interval = Math.max(0L, pollIntervalMillis != null ? pollIntervalMillis : DEFAULT_POLL_INTERVAL_MILLIS);

        LOG.debug("runProcessByKey: key='{}', retries={}, interval={}ms", key, retries, interval);

        return runAsCaller(engine -> {
            RuntimeService runtimeService = engine.getRuntimeService();
            HistoryService historyService = engine.getHistoryService();

            Map<String, Object> startVars = variables != null ? variables : Map.of();
            ProcessInstance instance = runtimeService.startProcessInstanceByKey(key, startVars);
            String pid = instance.getId();
            String defId = instance.getProcessDefinitionId();
            LOG.debug("[{}] Started process, definitionId='{}'", pid, defId);

            boolean ended = instance.isEnded();
            String state = ended ? "COMPLETED" : "ACTIVE";
            int attempts = 0;

            while (!ended && attempts < retries) {
                attempts++;
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }

                ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                        .processInstanceId(pid)
                        .singleResult();
                if (pi == null) {
                    ended = true;
                } else {
                    state = pi.isSuspended() ? "SUSPENDED" : "ACTIVE";
                }
            }

            Map<String, Object> outputs = new HashMap<>();

            if (ended) {
                HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                        .processInstanceId(pid)
                        .singleResult();
                if (hpi != null) {
                    state = hpi.getState();
                }

                List<HistoricVariableInstance> vars = historyService.createHistoricVariableInstanceQuery()
                        .processInstanceId(pid)
                        .list();
                for (HistoricVariableInstance v : vars) {
                    if (v.getName() != null && v.getName().startsWith(prefix)) {
                        outputs.put(v.getName(), v.getValue());
                    }
                }
            } else {
                Map<String, Object> runtimeVars = safeGetRuntimeVariables(runtimeService, pid);
                if (runtimeVars != null) {
                    for (Map.Entry<String, Object> v : runtimeVars.entrySet()) {
                        if (v.getKey() != null && v.getKey().startsWith(prefix)) {
                            outputs.put(v.getKey(), v.getValue());
                        }
                    }
                }
            }

            LOG.debug("[{}] Returning: state={}, ended={}, attempts={}, outputKeys={}",
                    pid, state, ended, attempts, outputs.keySet());

            Map<String, Object> result = new HashMap<>();
            result.put("processInstanceId", pid);
            result.put("processDefinitionId", defId);
            result.put("state", state);
            result.put("ended", ended);
            result.put("attempts", attempts);
            result.put("outputs", outputs);

            publishAuditRecord(key, pid, defId, state, ended, attempts);
            return result;
        });
    }

    /**
     * Publishes a side-effect record onto the active {@link AgentChatListener}
     * (when one is registered for the current thread) so the audit log carries
     * the resulting {@code processInstanceId} and the principal under which
     * the process was started — the Art. 14 evidence trail for autonomous
     * agent actions. No-op when the tool runs outside an agent connector turn
     * (e.g. direct invocation in unit tests).
     */
    private static void publishAuditRecord(String key, String processInstanceId,
            String processDefinitionId, String state, boolean ended, int attempts) {
        AgentChatListener listener = ProcessStarterToolContext.getActiveListener();
        if (listener == null) {
            return;
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("tool", "runProcessByKey");
        record.put("processDefinitionKey", key);
        record.put("processInstanceId", processInstanceId);
        record.put("processDefinitionId", processDefinitionId);
        record.put("state", state);
        record.put("ended", ended);
        record.put("attempts", attempts);
        Authentication executingAs = ProcessStarterToolContext.getAuthentication();
        if (executingAs != null && executingAs.getUserId() != null) {
            record.put("executedAs", executingAs.getUserId());
        }
        try {
            listener.recordToolSideEffect(record);
        } catch (RuntimeException e) {
            // Audit publishing must never break the tool itself.
            LOG.debug("Could not publish tool audit record: {}", e.toString());
        }
    }

    /**
     * Reads runtime variables, returning {@code null} when the instance
     * is no longer present.
     */
    private static Map<String, Object> safeGetRuntimeVariables(
            RuntimeService runtimeService, String processInstanceId) {
        try {
            return runtimeService.getVariables(processInstanceId);
        } catch (Exception e) {
            LOG.debug("[{}] Runtime variable read failed: {}", processInstanceId, e.getMessage());
            return null;
        }
    }

    /**
     * Runs {@code action} on a separate thread so that each engine API call
     * gets its own {@code CommandContext} and transaction — necessary when
     * this tool is invoked from within an existing command context (e.g. the
     * JobExecutor), where otherwise all calls would share the parent
     * transaction's stale MyBatis cache.
     *
     * <p>The {@link ProcessEngine} reference, caller authentication, and
     * active {@link AgentChatListener} are read from
     * {@link ProcessStarterToolContext} (populated by {@link AgentConnectorImpl}
     * on the connector's calling thread, where the engine lookup is reliable).
     * The authentication is applied on the new thread so the engine's
     * authorization layer checks against the original user. The listener
     * pointer is re-published onto the executor thread's {@link ThreadLocal}
     * so {@link #publishAuditRecord} (and any other helper running inside
     * {@code action}) can stitch tool side-effects onto the in-flight audit
     * stream — without this bridge the side-effect record is silently dropped
     * because the ThreadLocal on the connector thread is invisible from the
     * executor thread. Both are cleared in {@code finally} so values do not
     * leak across pool reuse.
     */
    private <T> T runAsCaller(Function<ProcessEngine, T> action) {
        ProcessEngine engine = ProcessStarterToolContext.getEngine();
        if (engine == null) {
            throw new IllegalStateException(
                    "No ProcessEngine available in ProcessStarterToolContext — "
                    + "AgentConnectorImpl could not resolve BpmPlatform.getDefaultProcessEngine() "
                    + "on the connector's calling thread.");
        }
        Authentication caller = ProcessStarterToolContext.getAuthentication();
        AgentChatListener listener = ProcessStarterToolContext.getActiveListener();

        try {
            return CompletableFuture.supplyAsync(() -> {
                IdentityService identityService = engine.getIdentityService();
                if (caller != null) {
                    identityService.setAuthentication(caller);
                }
                if (listener != null) {
                    ProcessStarterToolContext.setActiveListener(listener);
                }
                try {
                    return action.apply(engine);
                } finally {
                    identityService.clearAuthentication();
                    ProcessStarterToolContext.setActiveListener(null);
                }
            }, ENGINE_EXECUTOR).get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
