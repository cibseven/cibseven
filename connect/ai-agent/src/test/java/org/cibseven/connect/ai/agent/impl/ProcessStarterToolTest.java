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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mockito.ArgumentCaptor;

import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.history.HistoricProcessInstance;
import org.cibseven.bpm.engine.history.HistoricProcessInstanceQuery;
import org.cibseven.bpm.engine.history.HistoricVariableInstance;
import org.cibseven.bpm.engine.history.HistoricVariableInstanceQuery;
import org.cibseven.bpm.engine.impl.identity.Authentication;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.runtime.ProcessInstanceQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.langchain4j.agent.tool.Tool;

/**
 * Tests for {@link ProcessStarterTool} — the LangChain4j {@code @Tool} class
 * the AI agent uses to start a CIB seven process, poll until it terminates,
 * and read its outputs.
 *
 * <p>The {@link ProcessEngine}, its services and the queries are stubbed with
 * Mockito so no live engine is required. {@link ProcessStarterToolContext} is
 * the thread-local injection point used in production; tests populate it
 * before invoking the tool method and clear it in {@link #tearDown()}.
 *
 * <p>Polling intervals are clamped to {@code 0 ms} so each test runs in
 * milliseconds even when the polling loop iterates several times.
 */
public class ProcessStarterToolTest {

  private ProcessEngine engine;
  private RuntimeService runtimeService;
  private HistoryService historyService;
  private IdentityService identityService;
  private ProcessStarterTool tool;

  @BeforeEach
  public void setUp() {
    engine = mock(ProcessEngine.class);
    runtimeService = mock(RuntimeService.class);
    historyService = mock(HistoryService.class);
    identityService = mock(IdentityService.class);
    when(engine.getRuntimeService()).thenReturn(runtimeService);
    when(engine.getHistoryService()).thenReturn(historyService);
    when(engine.getIdentityService()).thenReturn(identityService);

    tool = new ProcessStarterTool();
    ProcessStarterToolContext.setEngine(engine);
    ProcessStarterToolContext.setAuthentication(null);
  }

  @AfterEach
  public void tearDown() {
    ProcessStarterToolContext.clear();
  }

  // ── @Tool annotation discoverable by LangChain4j ─────────────────────────

  /**
   * Verifies the tool method carries the LangChain4j {@link Tool} annotation
   * and a non-empty description — this is the single piece LangChain4j relies
   * on to register the method with the LLM, so a missing annotation here would
   * silently disable the tool at runtime.
   */
  @Test
  public void shouldExposeRunProcessByKeyAsLangChainTool() throws NoSuchMethodException {
    Method m = ProcessStarterTool.class.getDeclaredMethod(
        "runProcessByKey",
        String.class, Map.class, String.class, Integer.class, Long.class);

    Tool annotation = m.getAnnotation(Tool.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.value()).isNotEmpty();
    // Description must mention the canonical result keys so the LLM picks the tool correctly.
    String description = String.join(" ", annotation.value());
    assertThat(description).contains("processInstanceId");
    assertThat(description).contains("outputs");
  }

  // ── runProcessByKey: happy path on a process that ends synchronously ─────

  @Test
  public void shouldReturnCompletedStateWhenProcessEndsImmediately() {
    ProcessInstance instance = mockInstance("pi-1", "def-1", /*ended*/ true, /*suspended*/ false);
    when(runtimeService.startProcessInstanceByKey(eq("greet"), any(Map.class))).thenReturn(instance);

    HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
    when(hpi.getState()).thenReturn("COMPLETED");
    HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
    when(hpiQuery.processInstanceId("pi-1")).thenReturn(hpiQuery);
    when(hpiQuery.singleResult()).thenReturn(hpi);
    when(historyService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

    HistoricVariableInstance v1 = mockHistoricVariable("out_amount", 42);
    HistoricVariableInstance v2 = mockHistoricVariable("internal_state", "ignored");
    List<HistoricVariableInstance> vars = Arrays.asList(v1, v2);
    HistoricVariableInstanceQuery hviQuery = mock(HistoricVariableInstanceQuery.class);
    when(hviQuery.processInstanceId("pi-1")).thenReturn(hviQuery);
    when(hviQuery.list()).thenReturn(vars);
    when(historyService.createHistoricVariableInstanceQuery()).thenReturn(hviQuery);

    Map<String, Object> result = tool.runProcessByKey(
        "greet", Map.of("name", "Bob"), "out_", /*maxRetries*/ 0, /*pollIntervalMillis*/ 0L);

    assertThat(result).containsEntry("processInstanceId", "pi-1");
    assertThat(result).containsEntry("processDefinitionId", "def-1");
    assertThat(result).containsEntry("state", "COMPLETED");
    assertThat(result).containsEntry("ended", true);
    assertThat(result).containsEntry("attempts", 0);
    @SuppressWarnings("unchecked")
    Map<String, Object> outputs = (Map<String, Object>) result.get("outputs");
    assertThat(outputs).containsExactly(Map.entry("out_amount", 42));
    assertThat(outputs).doesNotContainKey("internal_state");

    // Runtime variable lookup must NOT happen on the synchronous-ended path.
    verify(runtimeService, never()).getVariables(any());
  }

  // ── runProcessByKey: process still active, polls until end ───────────────

  @Test
  public void shouldPollUntilProcessEndsAndThenReadHistory() {
    ProcessInstance running = mockInstance("pi-2", "def-2", false, false);
    when(runtimeService.startProcessInstanceByKey(eq("longRun"), any(Map.class)))
        .thenReturn(running);

    ProcessInstance stillRunning = mockInstance("pi-2", "def-2", false, false);
    ProcessInstanceQuery query = mock(ProcessInstanceQuery.class);
    when(query.processInstanceId("pi-2")).thenReturn(query);
    when(query.singleResult())
        .thenReturn(stillRunning) // first poll
        .thenReturn(null);        // second poll: process no longer in runtime → ended
    when(runtimeService.createProcessInstanceQuery()).thenReturn(query);

    HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
    when(hpi.getState()).thenReturn("COMPLETED");
    HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
    when(hpiQuery.processInstanceId("pi-2")).thenReturn(hpiQuery);
    when(hpiQuery.singleResult()).thenReturn(hpi);
    when(historyService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

    HistoricVariableInstance done = mockHistoricVariable("out_done", true);
    HistoricVariableInstanceQuery hviQuery = mock(HistoricVariableInstanceQuery.class);
    when(hviQuery.processInstanceId("pi-2")).thenReturn(hviQuery);
    when(hviQuery.list()).thenReturn(List.of(done));
    when(historyService.createHistoricVariableInstanceQuery()).thenReturn(hviQuery);

    Map<String, Object> result = tool.runProcessByKey(
        "longRun", null, "out_", /*maxRetries*/ 3, /*pollIntervalMillis*/ 0L);

    assertThat(result.get("ended")).isEqualTo(true);
    assertThat(result.get("attempts")).isEqualTo(2);
    assertThat(result.get("state")).isEqualTo("COMPLETED");
    @SuppressWarnings("unchecked")
    Map<String, Object> outputs = (Map<String, Object>) result.get("outputs");
    assertThat(outputs).containsExactly(Map.entry("out_done", true));
  }

  // ── runProcessByKey: still-active SUSPENDED state after polls exhausted ──

  @Test
  public void shouldReportSuspendedStateAndRuntimeVariablesWhenRetriesExhausted() {
    ProcessInstance starting = mockInstance("pi-3", "def-3", false, false);
    when(runtimeService.startProcessInstanceByKey(eq("idle"), any(Map.class))).thenReturn(starting);

    ProcessInstance suspended = mockInstance("pi-3", "def-3", false, /*suspended*/ true);
    ProcessInstanceQuery query = mock(ProcessInstanceQuery.class);
    when(query.processInstanceId("pi-3")).thenReturn(query);
    when(query.singleResult()).thenReturn(suspended);
    when(runtimeService.createProcessInstanceQuery()).thenReturn(query);

    Map<String, Object> runtimeVars = new HashMap<>();
    runtimeVars.put("out_partial", "halfway");
    runtimeVars.put("ignore_me", "secret");
    when(runtimeService.getVariables("pi-3")).thenReturn(runtimeVars);

    Map<String, Object> result = tool.runProcessByKey(
        "idle", Map.of(), "out_", /*maxRetries*/ 2, /*pollIntervalMillis*/ 0L);

    assertThat(result.get("ended")).isEqualTo(false);
    assertThat(result.get("attempts")).isEqualTo(2);
    assertThat(result.get("state")).isEqualTo("SUSPENDED");
    @SuppressWarnings("unchecked")
    Map<String, Object> outputs = (Map<String, Object>) result.get("outputs");
    assertThat(outputs).containsExactly(Map.entry("out_partial", "halfway"));
    // History queries must NOT run on the not-ended path.
    verify(historyService, never()).createHistoricProcessInstanceQuery();
  }

  // ── runProcessByKey: outputPrefix == "" returns all variables ────────────

  @Test
  public void shouldReturnAllVariablesWhenPrefixIsEmpty() {
    ProcessInstance instance = mockInstance("pi-4", "def-4", true, false);
    when(runtimeService.startProcessInstanceByKey(eq("k"), any(Map.class))).thenReturn(instance);

    HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
    when(hpiQuery.processInstanceId("pi-4")).thenReturn(hpiQuery);
    when(hpiQuery.singleResult()).thenReturn(null);
    when(historyService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

    HistoricVariableInstance va = mockHistoricVariable("a", 1);
    HistoricVariableInstance vb = mockHistoricVariable("b", 2);
    HistoricVariableInstance vNull = mockHistoricVariable(null, "should-be-skipped");
    HistoricVariableInstanceQuery hviQuery = mock(HistoricVariableInstanceQuery.class);
    when(hviQuery.processInstanceId("pi-4")).thenReturn(hviQuery);
    when(hviQuery.list()).thenReturn(Arrays.asList(va, vb, vNull));
    when(historyService.createHistoricVariableInstanceQuery()).thenReturn(hviQuery);

    Map<String, Object> result = tool.runProcessByKey(
        "k", null, /*prefix*/ "", null, null);

    @SuppressWarnings("unchecked")
    Map<String, Object> outputs = (Map<String, Object>) result.get("outputs");
    assertThat(outputs).containsEntry("a", 1).containsEntry("b", 2);
    // Null-named variables are skipped (.startsWith would NPE otherwise).
    assertThat(outputs).doesNotContainKey(null);
    // hpi.getState() returned null → state must remain "COMPLETED" (initial value).
    assertThat(result.get("state")).isEqualTo("COMPLETED");
  }

  // ── runProcessByKey: defaults applied when nulls passed ──────────────────

  @Test
  public void shouldApplyDefaultRetriesAndPollIntervalWhenNullsPassed() {
    ProcessInstance instance = mockInstance("pi-5", "def-5", true, false);
    when(runtimeService.startProcessInstanceByKey(eq("k"), any(Map.class))).thenReturn(instance);

    HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
    when(hpiQuery.processInstanceId("pi-5")).thenReturn(hpiQuery);
    when(hpiQuery.singleResult()).thenReturn(null);
    when(historyService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

    HistoricVariableInstanceQuery hviQuery = mock(HistoricVariableInstanceQuery.class);
    when(hviQuery.processInstanceId("pi-5")).thenReturn(hviQuery);
    when(hviQuery.list()).thenReturn(List.of());
    when(historyService.createHistoricVariableInstanceQuery()).thenReturn(hviQuery);

    Map<String, Object> result = tool.runProcessByKey("k", null, "out_", null, null);

    // Already-ended → no polling regardless of default retries: attempts must be 0.
    assertThat(result.get("attempts")).isEqualTo(0);
    assertThat(result.get("ended")).isEqualTo(true);
  }

  // ── runProcessByKey: runtime variable lookup gracefully degrades ─────────

  @Test
  public void shouldTreatRuntimeVariableLookupFailureAsEmptyOutputs() {
    ProcessInstance starting = mockInstance("pi-6", "def-6", false, false);
    when(runtimeService.startProcessInstanceByKey(eq("k"), any(Map.class))).thenReturn(starting);

    ProcessInstance suspended = mockInstance("pi-6", "def-6", false, true);
    ProcessInstanceQuery query = mock(ProcessInstanceQuery.class);
    when(query.processInstanceId("pi-6")).thenReturn(query);
    when(query.singleResult()).thenReturn(suspended);
    when(runtimeService.createProcessInstanceQuery()).thenReturn(query);

    when(runtimeService.getVariables("pi-6")).thenThrow(new RuntimeException("transient db error"));

    Map<String, Object> result = tool.runProcessByKey(
        "k", null, "out_", /*maxRetries*/ 1, /*pollIntervalMillis*/ 0L);

    @SuppressWarnings("unchecked")
    Map<String, Object> outputs = (Map<String, Object>) result.get("outputs");
    assertThat(outputs).isEmpty();
    assertThat(result.get("ended")).isEqualTo(false);
  }

  // ── runProcessByKey: missing engine → IllegalStateException ──────────────

  @Test
  public void shouldThrowWhenNoEngineRegisteredInContext() {
    ProcessStarterToolContext.clear();

    assertThatThrownBy(() -> tool.runProcessByKey("k", null, "out_", 0, 0L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No ProcessEngine available");
  }

  // ── runProcessByKey: authentication is forwarded ─────────────────────────

  @Test
  public void shouldApplyContextAuthenticationOnEngineThread() {
    Authentication caller = new Authentication("alice", List.of("g1"));
    ProcessStarterToolContext.setAuthentication(caller);

    ProcessInstance instance = mockInstance("pi-7", "def-7", true, false);
    when(runtimeService.startProcessInstanceByKey(eq("k"), any(Map.class))).thenReturn(instance);

    HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
    when(hpiQuery.processInstanceId("pi-7")).thenReturn(hpiQuery);
    when(hpiQuery.singleResult()).thenReturn(null);
    when(historyService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

    HistoricVariableInstanceQuery hviQuery = mock(HistoricVariableInstanceQuery.class);
    when(hviQuery.processInstanceId("pi-7")).thenReturn(hviQuery);
    when(hviQuery.list()).thenReturn(List.of());
    when(historyService.createHistoricVariableInstanceQuery()).thenReturn(hviQuery);

    tool.runProcessByKey("k", null, "out_", 0, 0L);

    // Authentication is set on the executor thread and cleared in finally.
    verify(identityService, times(1)).setAuthentication(caller);
    verify(identityService, times(1)).clearAuthentication();
  }

  @Test
  public void shouldClearAuthenticationButNotSetItWhenContextHasNoAuth() {
    ProcessInstance instance = mockInstance("pi-8", "def-8", true, false);
    when(runtimeService.startProcessInstanceByKey(eq("k"), any(Map.class))).thenReturn(instance);

    HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
    when(hpiQuery.processInstanceId("pi-8")).thenReturn(hpiQuery);
    when(hpiQuery.singleResult()).thenReturn(null);
    when(historyService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

    HistoricVariableInstanceQuery hviQuery = mock(HistoricVariableInstanceQuery.class);
    when(hviQuery.processInstanceId("pi-8")).thenReturn(hviQuery);
    when(hviQuery.list()).thenReturn(List.of());
    when(historyService.createHistoricVariableInstanceQuery()).thenReturn(hviQuery);

    tool.runProcessByKey("k", null, "out_", 0, 0L);

    verify(identityService, never()).setAuthentication(any(Authentication.class));
    verify(identityService, times(1)).clearAuthentication();
  }

  // ── runProcessByKey: tool side-effect reaches listener across executor ───

  /**
   * Guards the {@link ProcessStarterToolContext#setActiveListener} bridge in
   * {@code runAsCaller}: the listener pointer set on the connector's calling
   * thread must be re-published onto the {@link ProcessStarterTool#ENGINE_EXECUTOR}
   * worker thread before {@code publishAuditRecord} runs. Without the bridge,
   * {@code ProcessStarterToolContext.getActiveListener()} returns {@code null}
   * on the executor thread and the Art. 14 side-effect record is silently
   * dropped — which this test would catch (the {@code verify(... times(1))}
   * call fails).
   */
  @Test
  public void shouldPublishToolSideEffectToActiveListenerAcrossExecutorThread() {
    AgentChatListener listener = mock(AgentChatListener.class);
    ProcessStarterToolContext.setActiveListener(listener);

    ProcessInstance instance = mockInstance("pi-9", "def-9", true, false);
    when(runtimeService.startProcessInstanceByKey(eq("k"), any(Map.class))).thenReturn(instance);

    HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
    when(hpiQuery.processInstanceId("pi-9")).thenReturn(hpiQuery);
    when(hpiQuery.singleResult()).thenReturn(null);
    when(historyService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

    HistoricVariableInstanceQuery hviQuery = mock(HistoricVariableInstanceQuery.class);
    when(hviQuery.processInstanceId("pi-9")).thenReturn(hviQuery);
    when(hviQuery.list()).thenReturn(List.of());
    when(historyService.createHistoricVariableInstanceQuery()).thenReturn(hviQuery);

    tool.runProcessByKey("k", null, "out_", 0, 0L);

    @SuppressWarnings({"unchecked", "rawtypes"})
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass((Class) Map.class);
    verify(listener, times(1)).recordToolSideEffect(captor.capture());
    Map<String, Object> record = captor.getValue();
    assertThat(record).containsEntry("tool", "runProcessByKey");
    assertThat(record).containsEntry("processDefinitionKey", "k");
    assertThat(record).containsEntry("processInstanceId", "pi-9");
    assertThat(record).containsEntry("processDefinitionId", "def-9");
    assertThat(record).containsEntry("ended", true);
    assertThat(record).containsEntry("attempts", 0);
  }

  // ── runProcessByKey: engine RuntimeException propagates ──────────────────

  @Test
  public void shouldPropagateRuntimeExceptionFromEngineStart() {
    when(runtimeService.startProcessInstanceByKey(eq("boom"), any(Map.class)))
        .thenThrow(new RuntimeException("definition not deployed"));

    assertThatThrownBy(() -> tool.runProcessByKey("boom", null, "out_", 0, 0L))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("definition not deployed");
  }

  // ── Defaults constants — guard against accidental tuning ─────────────────

  @Test
  public void defaultsShouldMatchDocumentedValues() {
    assertThat(ProcessStarterTool.DEFAULT_MAX_RETRIES).isEqualTo(5);
    assertThat(ProcessStarterTool.DEFAULT_POLL_INTERVAL_MILLIS).isEqualTo(2000L);
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private static ProcessInstance mockInstance(String pid, String defId, boolean ended, boolean suspended) {
    ProcessInstance instance = mock(ProcessInstance.class);
    when(instance.getId()).thenReturn(pid);
    when(instance.getProcessDefinitionId()).thenReturn(defId);
    when(instance.isEnded()).thenReturn(ended);
    when(instance.isSuspended()).thenReturn(suspended);
    return instance;
  }

  private static HistoricVariableInstance mockHistoricVariable(String name, Object value) {
    HistoricVariableInstance v = mock(HistoricVariableInstance.class);
    when(v.getName()).thenReturn(name);
    when(v.getValue()).thenReturn(value);
    return v;
  }
}
