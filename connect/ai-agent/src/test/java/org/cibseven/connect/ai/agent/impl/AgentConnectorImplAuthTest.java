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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;

import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.history.HistoricProcessInstance;
import org.cibseven.bpm.engine.history.HistoricProcessInstanceQuery;
import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.GroupQuery;
import org.cibseven.bpm.engine.impl.ProcessEngineImpl;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.identity.Authentication;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the private engine + authentication resolution methods of
 * {@link AgentConnectorImpl}:
 * <ul>
 *   <li>{@code captureProcessEngine()} — the no-platform-on-thread path.</li>
 *   <li>{@code captureCallerAuthentication(engine)} — null engine, present auth,
 *       missing auth (falls through to {@code deriveAuthenticationFromProcessInstance}).</li>
 *   <li>{@code deriveAuthenticationFromProcessInstance(engine)} — when no
 *       {@code BpmnExecutionContext} is on the current thread (unit-test JVM).</li>
 * </ul>
 *
 * <p>The methods are private; they are reached via reflection. The engine and
 * its {@link IdentityService} are stubbed with Mockito — no live engine is
 * required.
 */
public class AgentConnectorImplAuthTest {

  private AgentConnectorImpl connector;
  /** Tracks Context state we push during a test so {@link #tearDown()} can drain it. */
  private boolean pushedEngineConfig;
  private boolean pushedExecution;

  @BeforeEach
  public void setUp() {
    connector = new AgentConnectorImpl();
    pushedEngineConfig = false;
    pushedExecution = false;
  }

  @AfterEach
  public void tearDown() {
    // Context state is held in ThreadLocal Deques shared with the engine; leaks
    // would poison sibling tests.
    if (pushedEngineConfig) {
      try { Context.removeProcessEngineConfiguration(); } catch (Exception ignored) {}
    }
    if (pushedExecution) {
      try { Context.removeExecutionContext(); } catch (Exception ignored) {}
    }
  }

  // ── captureProcessEngine ────────────────────────────────────────────────

  /**
   * Unit-test JVMs never have a {@code BpmPlatform} default engine bootstrapped,
   * so the method must return {@code null} cleanly without throwing.
   */
  @Test
  public void captureProcessEngineShouldReturnNullWhenNoPlatformBootstrapped() throws Exception {
    Object engine = invokePrivate("captureProcessEngine");
    assertThat(engine).isNull();
  }

  // ── captureCallerAuthentication ─────────────────────────────────────────

  @Test
  public void captureCallerAuthenticationShouldReturnNullWhenEngineIsNull() throws Exception {
    Object auth = invokePrivate("captureCallerAuthentication",
        new Class<?>[] { ProcessEngine.class }, new Object[] { null });
    assertThat(auth).isNull();
  }

  @Test
  public void captureCallerAuthenticationShouldReturnCurrentEngineAuthentication() throws Exception {
    Authentication current = new Authentication("alice", List.of("admins"));
    ProcessEngine engine = mock(ProcessEngine.class);
    IdentityService identityService = mock(IdentityService.class);
    when(engine.getIdentityService()).thenReturn(identityService);
    when(identityService.getCurrentAuthentication()).thenReturn(current);

    Authentication captured = (Authentication) invokePrivate(
        "captureCallerAuthentication",
        new Class<?>[] { ProcessEngine.class },
        new Object[] { engine });

    assertThat(captured).isSameAs(current);
    assertThat(captured.getUserId()).isEqualTo("alice");
    assertThat(captured.getGroupIds()).containsExactly("admins");
  }

  @Test
  public void captureCallerAuthenticationShouldFallBackToDerivationWhenCurrentAuthIsNull() throws Exception {
    ProcessEngine engine = mock(ProcessEngine.class);
    IdentityService identityService = mock(IdentityService.class);
    when(engine.getIdentityService()).thenReturn(identityService);
    when(identityService.getCurrentAuthentication()).thenReturn(null);

    // Outside an engine command context, derivation can find no
    // BpmnExecutionContext on the thread and must return null.
    Authentication captured = (Authentication) invokePrivate(
        "captureCallerAuthentication",
        new Class<?>[] { ProcessEngine.class },
        new Object[] { engine });

    assertThat(captured).isNull();
  }

  @Test
  public void captureCallerAuthenticationShouldSwallowIdentityServiceFailure() throws Exception {
    ProcessEngine engine = mock(ProcessEngine.class);
    IdentityService identityService = mock(IdentityService.class);
    when(engine.getIdentityService()).thenReturn(identityService);
    when(identityService.getCurrentAuthentication())
        .thenThrow(new RuntimeException("engine offline"));

    // The exception must be caught and the method must still attempt the
    // process-instance derivation path; with no command context on the thread,
    // derivation returns null.
    Authentication captured = (Authentication) invokePrivate(
        "captureCallerAuthentication",
        new Class<?>[] { ProcessEngine.class },
        new Object[] { engine });

    assertThat(captured).isNull();
  }

  // ── deriveAuthenticationFromProcessInstance ─────────────────────────────

  @Test
  public void deriveAuthenticationFromProcessInstanceShouldReturnNullWhenNoBpmnContext() throws Exception {
    ProcessEngine engine = mock(ProcessEngine.class);

    Authentication derived = (Authentication) invokePrivate(
        "deriveAuthenticationFromProcessInstance",
        new Class<?>[] { ProcessEngine.class },
        new Object[] { engine });

    assertThat(derived).isNull();
    // Engine APIs must not be queried when there is no execution context.
    org.mockito.Mockito.verify(engine, org.mockito.Mockito.never()).getHistoryService();
  }

  @Test
  public void deriveAuthenticationFromProcessInstanceShouldNotThrowWhenEngineThrows() throws Exception {
    // Even though we never get past the BpmnExecutionContext check in a unit
    // test, this exercises the call path with a mock engine and proves the
    // method is null-safe and exception-free against any engine reference.
    ProcessEngine engine = mock(ProcessEngine.class);
    when(engine.getHistoryService()).thenThrow(new RuntimeException("boom"));
    when(engine.getIdentityService()).thenThrow(new RuntimeException("boom"));

    Authentication derived = (Authentication) invokePrivate(
        "deriveAuthenticationFromProcessInstance",
        new Class<?>[] { ProcessEngine.class },
        new Object[] { engine });

    assertThat(derived).isNull();
  }

  // ── captureProcessEngine with config on Context stack ────────────────────

  @Test
  public void captureProcessEngineShouldReturnEngineFromCommandContextConfig() throws Exception {
    ProcessEngineImpl engine = mock(ProcessEngineImpl.class);
    when(engine.getName()).thenReturn("default");
    ProcessEngineConfigurationImpl config = mock(ProcessEngineConfigurationImpl.class);
    when(config.getProcessEngine()).thenReturn(engine);

    Context.setProcessEngineConfiguration(config);
    pushedEngineConfig = true;

    Object captured = invokePrivate("captureProcessEngine");
    assertThat(captured).isSameAs(engine);
  }

  @Test
  public void captureProcessEngineShouldFallThroughWhenCommandContextConfigHasNullEngine() throws Exception {
    ProcessEngineConfigurationImpl config = mock(ProcessEngineConfigurationImpl.class);
    when(config.getProcessEngine()).thenReturn(null);

    Context.setProcessEngineConfiguration(config);
    pushedEngineConfig = true;

    // No BpmPlatform default engine in unit test → null fallback.
    Object captured = invokePrivate("captureProcessEngine");
    assertThat(captured).isNull();
  }

  @Test
  public void captureProcessEngineShouldSwallowConfigAccessFailure() throws Exception {
    ProcessEngineConfigurationImpl config = mock(ProcessEngineConfigurationImpl.class);
    when(config.getProcessEngine()).thenThrow(new RuntimeException("boom"));

    Context.setProcessEngineConfiguration(config);
    pushedEngineConfig = true;

    // Exception caught, falls through to BpmPlatform path → null in tests.
    Object captured = invokePrivate("captureProcessEngine");
    assertThat(captured).isNull();
  }

  // ── deriveAuthenticationFromProcessInstance with BpmnExecutionContext ────

  @Test
  public void deriveAuthenticationShouldReturnUserGroupsAndTenantsFromStartingProcessInstance()
      throws Exception {
    pushExecutionContext("pi-A");

    ProcessEngine engine = mockEngineWithHistoricInstance("pi-A", "alice", "tenant-1",
        List.of("g1", "g2"));

    Authentication derived = (Authentication) invokePrivate(
        "deriveAuthenticationFromProcessInstance",
        new Class<?>[] { ProcessEngine.class },
        new Object[] { engine });

    assertThat(derived).isNotNull();
    assertThat(derived.getUserId()).isEqualTo("alice");
    assertThat(derived.getGroupIds()).containsExactlyInAnyOrder("g1", "g2");
    assertThat(derived.getTenantIds()).containsExactly("tenant-1");
  }

  @Test
  public void deriveAuthenticationShouldReturnEmptyGroupsWhenGroupQueryFails() throws Exception {
    pushExecutionContext("pi-B");

    HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
    when(hpi.getStartUserId()).thenReturn("bob");
    when(hpi.getTenantId()).thenReturn(null); // covers the null-tenants branch
    HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
    when(hpiQuery.processInstanceId("pi-B")).thenReturn(hpiQuery);
    when(hpiQuery.singleResult()).thenReturn(hpi);
    HistoryService historyService = mock(HistoryService.class);
    when(historyService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

    IdentityService identityService = mock(IdentityService.class);
    when(identityService.createGroupQuery()).thenThrow(new RuntimeException("identity offline"));

    ProcessEngine engine = mock(ProcessEngine.class);
    when(engine.getHistoryService()).thenReturn(historyService);
    when(engine.getIdentityService()).thenReturn(identityService);

    Authentication derived = (Authentication) invokePrivate(
        "deriveAuthenticationFromProcessInstance",
        new Class<?>[] { ProcessEngine.class },
        new Object[] { engine });

    assertThat(derived).isNotNull();
    assertThat(derived.getUserId()).isEqualTo("bob");
    assertThat(derived.getGroupIds()).isEmpty();
    assertThat(derived.getTenantIds()).isNull();
  }

  @Test
  public void deriveAuthenticationShouldReturnNullWhenHistoricInstanceMissing() throws Exception {
    pushExecutionContext("pi-C");

    HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
    when(hpiQuery.processInstanceId("pi-C")).thenReturn(hpiQuery);
    when(hpiQuery.singleResult()).thenReturn(null);
    HistoryService historyService = mock(HistoryService.class);
    when(historyService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

    ProcessEngine engine = mock(ProcessEngine.class);
    when(engine.getHistoryService()).thenReturn(historyService);

    Authentication derived = (Authentication) invokePrivate(
        "deriveAuthenticationFromProcessInstance",
        new Class<?>[] { ProcessEngine.class },
        new Object[] { engine });

    assertThat(derived).isNull();
  }

  @Test
  public void deriveAuthenticationShouldReturnNullWhenStartUserIdEmpty() throws Exception {
    pushExecutionContext("pi-D");

    HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
    when(hpi.getStartUserId()).thenReturn("");
    HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
    when(hpiQuery.processInstanceId("pi-D")).thenReturn(hpiQuery);
    when(hpiQuery.singleResult()).thenReturn(hpi);
    HistoryService historyService = mock(HistoryService.class);
    when(historyService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

    ProcessEngine engine = mock(ProcessEngine.class);
    when(engine.getHistoryService()).thenReturn(historyService);

    Authentication derived = (Authentication) invokePrivate(
        "deriveAuthenticationFromProcessInstance",
        new Class<?>[] { ProcessEngine.class },
        new Object[] { engine });

    assertThat(derived).isNull();
  }

  @Test
  public void deriveAuthenticationShouldSwallowHistoryServiceFailure() throws Exception {
    pushExecutionContext("pi-E");

    ProcessEngine engine = mock(ProcessEngine.class);
    when(engine.getHistoryService()).thenThrow(new RuntimeException("db down"));

    Authentication derived = (Authentication) invokePrivate(
        "deriveAuthenticationFromProcessInstance",
        new Class<?>[] { ProcessEngine.class },
        new Object[] { engine });

    assertThat(derived).isNull();
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  /**
   * Pushes a {@link org.cibseven.bpm.engine.impl.context.BpmnExecutionContext}
   * onto the engine's per-thread context stack, holding a stub
   * {@link ExecutionEntity} that reports the given process-instance id.
   */
  private void pushExecutionContext(String processInstanceId) {
    ExecutionEntity execution = mock(ExecutionEntity.class);
    when(execution.getProcessInstanceId()).thenReturn(processInstanceId);
    Context.setExecutionContext(execution);
    pushedExecution = true;
  }

  /**
   * Wires a {@link ProcessEngine} mock with a historic process instance for
   * {@code processInstanceId}, started by {@code startUserId}, tagged with
   * {@code tenantId}, and whose start user belongs to {@code groupIds}.
   */
  private ProcessEngine mockEngineWithHistoricInstance(String processInstanceId,
      String startUserId, String tenantId, List<String> groupIds) {
    HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
    when(hpi.getStartUserId()).thenReturn(startUserId);
    when(hpi.getTenantId()).thenReturn(tenantId);

    HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
    when(hpiQuery.processInstanceId(processInstanceId)).thenReturn(hpiQuery);
    when(hpiQuery.singleResult()).thenReturn(hpi);

    HistoryService historyService = mock(HistoryService.class);
    when(historyService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

    java.util.List<Group> groups = new java.util.ArrayList<>();
    for (String id : groupIds) {
      Group g = mock(Group.class);
      when(g.getId()).thenReturn(id);
      groups.add(g);
    }
    GroupQuery groupQuery = mock(GroupQuery.class);
    when(groupQuery.groupMember(startUserId)).thenReturn(groupQuery);
    when(groupQuery.list()).thenReturn(groups);

    IdentityService identityService = mock(IdentityService.class);
    when(identityService.createGroupQuery()).thenReturn(groupQuery);

    ProcessEngine engine = mock(ProcessEngine.class);
    when(engine.getHistoryService()).thenReturn(historyService);
    when(engine.getIdentityService()).thenReturn(identityService);
    return engine;
  }

  // ── Reflection helpers ───────────────────────────────────────────────────

  private Object invokePrivate(String name) throws Exception {
    Method m = AgentConnectorImpl.class.getDeclaredMethod(name);
    m.setAccessible(true);
    return m.invoke(connector);
  }

  private Object invokePrivate(String name, Class<?>[] paramTypes, Object[] args) throws Exception {
    Method m = AgentConnectorImpl.class.getDeclaredMethod(name, paramTypes);
    m.setAccessible(true);
    return m.invoke(connector, args);
  }
}
