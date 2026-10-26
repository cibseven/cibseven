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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.ExecutionListener;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.cibseven.bpm.engine.impl.bpmn.helper.BpmnProperties;
import org.cibseven.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.impl.pvm.process.ScopeImpl;
import org.cibseven.bpm.engine.impl.util.xml.Element;
import org.cibseven.bpm.engine.runtime.Execution;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.runtime.VariableInstance;
import org.cibseven.bpm.engine.history.HistoricVariableUpdate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link AdHocConversation} — where an agent's conversation lives when a scope keeps it across
 * entries (CIB7-2137), and how long it lives.
 *
 * <p>Its own engine, deliberately without the agentic plugin, the same choice
 * {@link AdHocAgentStateTest} makes: these tests are about the holder execution, and a real turn
 * taking jobs of its own would add executions in the middle of assertions about where a variable
 * sits. {@code cibseven.agentic.conversation = perActivity} is stamped onto a plain ad hoc scope
 * by a test-only post parse listener instead, so the mechanics are exercised without the real
 * connector. The wiring into a real turn -- {@code followUp}, {@code maxTurnsTotal}, and the
 * chat-memory store picking the holder over the agent state -- is covered where the agent lives,
 * against a scripted model.
 */
public class AdHocConversationTest {

  protected ProcessEngine engine;
  protected RuntimeService runtimeService;

  /** What {@link EnterAndRecord} observed on each entry, oldest first. */
  protected static final List<int[]> ENTRY_COUNTS = new ArrayList<int[]>();
  protected static final List<Boolean> RE_ENTRY_FLAGS = new ArrayList<Boolean>();
  protected static final List<String> MEMORY_IDS = new ArrayList<String>();

  /** What {@link BumpTotal} observed, oldest first. */
  protected static final List<Integer> TOTALS = new ArrayList<Integer>();

  @BeforeEach
  public void startEngine() {
    ENTRY_COUNTS.clear();
    RE_ENTRY_FLAGS.clear();
    MEMORY_IDS.clear();
    TOTALS.clear();

    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName("adhoc-conversation");
    configuration.setJdbcUrl("jdbc:h2:mem:adhoc-conversation;DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    configuration.setEnforceHistoryTimeToLive(false);
    configuration.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
    // Stamps cibseven.agentic.conversation = perActivity on every scope whose id ends with
    // "PerActivity", the way the real parse listener would for a scope that asked for it --
    // without the connector and everything else that makes a scope agentic.
    List<org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParseListener> postParseListeners =
        new ArrayList<org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParseListener>();
    postParseListeners.add(new ConversationModeListener());
    configuration.setCustomPostBPMNParseListeners(postParseListeners);
    engine = configuration.buildProcessEngine();
    runtimeService = engine.getRuntimeService();
  }

  @AfterEach
  public void stopEngine() {
    engine.close();
  }

  /** Marks a scope perActivity by its id, and gives it the entry-start hook production uses. */
  public static class ConversationModeListener extends AbstractBpmnParseListener {
    @Override
    public void parseSubProcess(Element scopeElement, ScopeImpl scope, ActivityImpl activity) {
      if (activity.getId().endsWith("PerActivity")) {
        Map<String, String> properties = new HashMap<String, String>();
        properties.put(AdHocConversation.MODE_PROPERTY, AdHocConversation.PER_ACTIVITY);
        activity.getProperties().set(BpmnProperties.EXTENSION_PROPERTIES, properties);
      }
      // Every scope, marked or not: the same hook AgenticAdHocStartListener uses, so a plain
      // scope's entries() stays zero and a perActivity scope's counts from its first entry.
      activity.addListener(ExecutionListener.EVENTNAME_START, new ExecutionListener() {
        @Override
        public void notify(DelegateExecution execution) {
          AdHocConversation.enterScope((ExecutionEntity) execution);
        }
      });
    }
  }

  /** What {@link EnterAndRecord} observed for {@code turnsTotal} on each entry, oldest first. */
  protected static final List<Integer> TURNS_TOTAL_AT_ENTRY = new ArrayList<Integer>();

  /**
   * Records what the holder says about this entry, the way a real turn would ask it, and flips
   * the process variable the loop model's gateway reads: true on the first entry, false from the
   * second -- exactly two entries, however many scopes this deployment re-enters.
   */
  public static class EnterAndRecord implements JavaDelegate {
    @Override
    public void execute(DelegateExecution execution) {
      ExecutionEntity scope = AdHocAgentState.findAdHocScope((ExecutionEntity) execution);
      ENTRY_COUNTS.add(new int[] { AdHocConversation.entries(scope) });
      RE_ENTRY_FLAGS.add(AdHocConversation.isReEntry(scope));
      MEMORY_IDS.add(AdHocConversation.memoryId(scope));
      TURNS_TOTAL_AT_ENTRY.add(AdHocConversation.turnsTotal(scope));
      execution.setVariable("loopAgain", Boolean.valueOf(ENTRY_COUNTS.size() < 2));
    }
  }

  /** A tool forging the holder's own marker, the way an untrusted child could. */
  public static class TamperHolderMarker implements JavaDelegate {
    @Override
    public void execute(DelegateExecution execution) {
      execution.setVariable(AdHocConversation.HOLDER_MARKER, "forged");
    }
  }

  /** Counts two steps against the total cap, with a retry of the first mixed in. */
  public static class BumpTotal implements JavaDelegate {
    @Override
    public void execute(DelegateExecution execution) {
      ExecutionEntity scope = AdHocAgentState.findAdHocScope((ExecutionEntity) execution);
      TOTALS.add(AdHocConversation.beginTurnTotal(scope, "step-1"));
      TOTALS.add(AdHocConversation.beginTurnTotal(scope, "step-1")); // the retry
      TOTALS.add(AdHocConversation.beginTurnTotal(scope, "step-2"));
    }
  }

  protected List<VariableInstance> variables(String processInstanceId, String name) {
    return runtimeService.createVariableInstanceQuery()
        .processInstanceIdIn(processInstanceId)
        .variableName(name)
        .list();
  }

  protected String scopeExecutionId(String processInstanceId, String activityId) {
    for (Execution execution : runtimeService.createExecutionQuery()
        .processInstanceId(processInstanceId).list()) {
      if (activityId.equals(((ExecutionEntity) execution).getActivityId())) {
        return execution.getId();
      }
    }
    throw new AssertionError("no execution sitting on '" + activityId + "'");
  }

  protected void deploy(String resource) {
    engine.getRepositoryService().createDeployment()
        .addClasspathResource("agentic/" + resource).deploy();
  }

  // ─── placement, tamper, and two scopes under one enclosing execution ──────────

  /**
   * The holder sits beside the scope, one level up -- a child of the execution enclosing it,
   * not of the scope execution itself and not of the process instance it happens to equal here.
   */
  @Test
  public void theHolderLivesUnderTheEnclosingExecutionNotOnTheScope() {
    deploy("AdHocConversationTest.placement.bpmn20.xml");
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("conversationPlacement");
    String scope = scopeExecutionId(pi.getId(), "adHocPerActivity");

    runtimeService.activateAdHocSubProcessActivities(scope, Collections.singletonList("enter"));

    VariableInstance marker = variables(pi.getId(), AdHocConversation.HOLDER_MARKER).get(0);
    assertThat(((Map<?, ?>) marker.getValue()).get(AdHocConversation.ACTIVITY_ID))
        .isEqualTo("adHocPerActivity");
    assertThat(marker.getValue()).isInstanceOf(TreeMap.class);
    assertThat(variables(pi.getId(), AdHocConversation.ENTRIES)).isEmpty();
    assertThat(marker.getExecutionId()).isNotEqualTo(scope);
  }

  /**
   * A child of the scope forging the holder's marker must not reach the real holder: its write
   * lands on its own variable at the process instance, which is what every ancestor it can walk
   * up to resolves to -- the holder is not on that path.
   */
  @Test
  public void aChildCannotTamperWithTheHolder() {
    deploy("AdHocConversationTest.placement.bpmn20.xml");
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("conversationPlacement");
    String scope = scopeExecutionId(pi.getId(), "adHocPerActivity");

    runtimeService.activateAdHocSubProcessActivities(scope,
        java.util.Arrays.asList("enter", "tamperHolder"));

    // Both children are synchronous, so by the time this returns, adHocPerActivity has also
    // completed and the flow has already entered adHoc2PerActivity -- which gets a holder of
    // its own on entry, before anything ever activates its children. That is not what this test
    // is about, so it looks for the one marker it cares about rather than counting all of them.
    List<VariableInstance> markers = variables(pi.getId(), AdHocConversation.HOLDER_MARKER);
    List<VariableInstance> real = new ArrayList<VariableInstance>();
    List<VariableInstance> forged = new ArrayList<VariableInstance>();
    for (VariableInstance marker : markers) {
      if (marker.getValue() instanceof Map && "adHocPerActivity".equals(
          ((Map<?, ?>) marker.getValue()).get(AdHocConversation.ACTIVITY_ID))) {
        real.add(marker);
      } else if ("forged".equals(marker.getValue())) {
        forged.add(marker);
      }
    }
    assertThat(real).as("the real holder's marker, exactly once").hasSize(1);
    assertThat(forged).as("the tool's forged copy, landing beside it, not on it").hasSize(1);
    assertThat(real.get(0).getExecutionId())
        .as("the real marker is not the forged copy's execution")
        .isNotEqualTo(forged.get(0).getExecutionId());
  }

  /**
   * Two scopes sharing one enclosing execution each get their own holder, found by the activity
   * id they carry -- not by a fixed marker, which would hand out whichever came first.
   */
  @Test
  public void twoScopesUnderOneEnclosingExecutionGetDistinctHolders() {
    deploy("AdHocConversationTest.placement.bpmn20.xml");
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("conversationPlacement");

    runtimeService.activateAdHocSubProcessActivities(
        scopeExecutionId(pi.getId(), "adHocPerActivity"), Collections.singletonList("enter"));
    runtimeService.activateAdHocSubProcessActivities(
        scopeExecutionId(pi.getId(), "adHoc2PerActivity"), Collections.singletonList("enter2"));

    List<VariableInstance> markers = variables(pi.getId(), AdHocConversation.HOLDER_MARKER);
    assertThat(markers).extracting(marker ->
        (String) ((Map<?, ?>) marker.getValue()).get(AdHocConversation.ACTIVITY_ID))
        .containsExactlyInAnyOrder("adHocPerActivity", "adHoc2PerActivity");
    assertThat(markers.get(0).getExecutionId()).isNotEqualTo(markers.get(1).getExecutionId());
  }

  /**
   * A scope that never asked for perActivity never gets a holder: {@code enterScope} is a no-op
   * for it, so {@code entries()} stays zero, {@code isReEntry()} stays false, and the total-turn
   * counter -- which only ever lives on a holder -- reads zero as well. Reached by driving the
   * model's whole sequential chain, so this scope is entered the way the deployment reaches it.
   */
  @Test
  public void aScopeWithoutPerActivityNeverGetsAHolder() {
    deploy("AdHocConversationTest.placement.bpmn20.xml");
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("conversationPlacement");

    runtimeService.activateAdHocSubProcessActivities(
        scopeExecutionId(pi.getId(), "adHocPerActivity"), Collections.singletonList("enter"));
    runtimeService.activateAdHocSubProcessActivities(
        scopeExecutionId(pi.getId(), "adHoc2PerActivity"), Collections.singletonList("enter2"));
    runtimeService.activateAdHocSubProcessActivities(
        scopeExecutionId(pi.getId(), "adHocPlain"), Collections.singletonList("enterPlain"));

    assertThat(ENTRY_COUNTS).as("one entry recorded per scope").hasSize(3);
    assertThat(ENTRY_COUNTS.get(2)[0])
        .as("adHocPlain's entries() stays zero: enterScope is a no-op without perActivity")
        .isZero();
    assertThat(RE_ENTRY_FLAGS.get(2)).isFalse();
    assertThat(TURNS_TOTAL_AT_ENTRY.get(2))
        .as("no holder to total turns on, so it reads zero like every perEntry scope")
        .isZero();
  }

  /**
   * The holder is removed with everything else once the execution enclosing it ends -- an
   * ordinary event-scope execution, disposed the same way {@link AdHocAgentState}'s is one level
   * down.
   */
  @Test
  public void theHolderIsRemovedWhenTheEnclosingExecutionEnds() {
    deploy("AdHocConversationTest.placement.bpmn20.xml");
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("conversationPlacement");

    runtimeService.activateAdHocSubProcessActivities(
        scopeExecutionId(pi.getId(), "adHocPerActivity"), Collections.singletonList("enter"));
    // adHocPerActivity completed as part of that call, and the flow already entered
    // adHoc2PerActivity: its holder exists too, created on entry, before "enter2" ever runs.
    assertThat(variables(pi.getId(), AdHocConversation.HOLDER_MARKER))
        .as("both holders exist while the enclosing subprocess is still open").hasSize(2);

    runtimeService.activateAdHocSubProcessActivities(
        scopeExecutionId(pi.getId(), "adHoc2PerActivity"), Collections.singletonList("enter2"));
    runtimeService.activateAdHocSubProcessActivities(
        scopeExecutionId(pi.getId(), "adHocPlain"), Collections.singletonList("enterPlain"));

    // The model's enclosing subprocess has no more children once the three scopes above have
    // each run their one activity and completed; completing the last one ends it.
    assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId())
        .singleResult())
        .as("the whole instance ended cleanly, taking the holders with it")
        .isNull();
    assertThat(variables(pi.getId(), AdHocConversation.HOLDER_MARKER)).isEmpty();
  }

  // ─── entries, re-entry, and a stable conversation id across real re-entry ─────

  /**
   * Entering the scope again -- ended, then reached a second time through the loop -- counts as
   * a further entry, flips {@code isReEntry}, and keeps the conversation id the entry before it
   * had: it is derived from the enclosing execution and the activity id, neither of which
   * changes when the scope itself ends and is created afresh.
   */
  @Test
  public void entriesCountIsReEntryFlipsAndTheConversationIdSurvivesTheScopeRestarting() {
    deploy("AdHocConversationTest.loop.bpmn20.xml");
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("conversationLoop");

    // First entry.
    runtimeService.activateAdHocSubProcessActivities(
        scopeExecutionId(pi.getId(), "adHocLoopPerActivity"), Collections.singletonList("enter"));
    // The delegate set loopAgain=true, so the gateway sent the flow back into the scope: a
    // fresh scope execution, with a new id, but the same enclosing execution and activity id.
    runtimeService.activateAdHocSubProcessActivities(
        scopeExecutionId(pi.getId(), "adHocLoopPerActivity"), Collections.singletonList("enter"));

    assertThat(ENTRY_COUNTS).extracting(counts -> counts[0]).containsExactly(1, 2);
    assertThat(RE_ENTRY_FLAGS).containsExactly(false, true);
    assertThat(MEMORY_IDS.get(0))
        .as("one conversation id for both entries")
        .isEqualTo(MEMORY_IDS.get(1));
  }

  // ─── the total-turn counter ────────────────────────────────────────────────────

  /**
   * A step and its retry share one id and must count once; a further step counts on top of it.
   * Mirrors {@link AdHocAgentState#beginTurn}, one level up and across entries rather than
   * within one.
   */
  @Test
  public void beginTurnTotalCountsAStepOnceAndADifferentStepFurther() {
    deploy("AdHocConversationTest.placement.bpmn20.xml");
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("conversationPlacement");

    runtimeService.activateAdHocSubProcessActivities(
        scopeExecutionId(pi.getId(), "adHocPerActivity"), Collections.singletonList("bumpTotal"));

    assertThat(TOTALS).as("step-1, its retry, then step-2").containsExactly(1, 1, 2);
    assertThat(holderWrites(pi.getId()))
        .as("one initial marker and one batched update, despite three counter calls").isEqualTo(2);
    assertThat(variables(pi.getId(), AdHocConversation.TURNS_TOTAL)).isEmpty();
    assertThat(variables(pi.getId(), AdHocConversation.TURNS_TOTAL_MARKER)).isEmpty();
  }

  private long holderWrites(String instanceId) {
    return engine.getHistoryService().createHistoricDetailQuery()
        .processInstanceId(instanceId).variableUpdates().list().stream()
        .map(detail -> (HistoricVariableUpdate) detail)
        .filter(update -> AdHocConversation.HOLDER_MARKER.equals(update.getVariableName()))
        .filter(update -> update.getValue() instanceof Map && "adHocPerActivity".equals(
            ((Map<?, ?>) update.getValue()).get(AdHocConversation.ACTIVITY_ID)))
        .count();
  }

  @Test
  public void readsDoNotWriteAndFailedCommandsDoNotPersistCollectedCounters() {
    deploy("AdHocConversationTest.placement.bpmn20.xml");
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("conversationPlacement");
    String scopeId = scopeExecutionId(pi.getId(), "adHocPerActivity");
    ProcessEngineConfigurationImpl configuration =
        (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
    long before = holderWrites(pi.getId());
    configuration.getCommandExecutorTxRequired().execute(context -> {
      ExecutionEntity scope = context.getExecutionManager().findExecutionById(scopeId);
      assertThat(AdHocConversation.entries(scope)).isEqualTo(1);
      assertThat(AdHocConversation.turnsTotal(scope)).isZero();
      assertThat(AdHocConversation.findMemoryExecution(scope)).isNotNull();
      return null;
    });
    assertThat(holderWrites(pi.getId())).isEqualTo(before);

    assertThatThrownBy(() -> configuration.getCommandExecutorTxRequired().execute(context -> {
      ExecutionEntity scope = context.getExecutionManager().findExecutionById(scopeId);
      assertThat(AdHocConversation.beginTurnTotal(scope, "rolled-back")).isEqualTo(1);
      throw new ProcessEngineException("rollback");
    })).hasMessageContaining("rollback");
    assertThat(holderWrites(pi.getId())).isEqualTo(before);
    configuration.getCommandExecutorTxRequired().execute(context -> {
      ExecutionEntity scope = context.getExecutionManager().findExecutionById(scopeId);
      assertThat(AdHocConversation.turnsTotal(scope)).isZero();
      assertThat(AdHocConversation.beginTurnTotal(scope, "committed")).isEqualTo(1);
      return null;
    });
    assertThat(holderWrites(pi.getId())).isEqualTo(before + 1);
  }

  @Test
  public void legacyHolderKeepsItsCountersAndTurnMarkerWhenUpgraded() {
    deploy("AdHocConversationTest.placement.bpmn20.xml");
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("conversationPlacement");
    String scopeId = scopeExecutionId(pi.getId(), "adHocPerActivity");
    String holderId = variables(pi.getId(), AdHocConversation.HOLDER_MARKER).get(0).getExecutionId();
    runtimeService.setVariableLocal(holderId, AdHocConversation.HOLDER_MARKER, "adHocPerActivity");
    runtimeService.setVariableLocal(holderId, AdHocConversation.ENTRIES, 4);
    runtimeService.setVariableLocal(holderId, AdHocConversation.TURNS_TOTAL, 7);
    runtimeService.setVariableLocal(holderId, AdHocConversation.TURNS_TOTAL_MARKER, "previous");
    runtimeService.setVariableLocal(holderId, "conversation-payload", "keep me");

    ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
        .getCommandExecutorTxRequired().execute(context -> {
          ExecutionEntity scope = context.getExecutionManager().findExecutionById(scopeId);
          assertThat(AdHocConversation.find(scope).getId()).isEqualTo(holderId);
          assertThat(AdHocConversation.entries(scope)).isEqualTo(4);
          assertThat(AdHocConversation.turnsTotal(scope)).isEqualTo(7);
          assertThat(AdHocConversation.beginTurnTotal(scope, "previous")).isEqualTo(7);
          AdHocConversation.enterScope(scope);
          assertThat(AdHocConversation.entries(scope)).isEqualTo(5);
          assertThat(AdHocConversation.beginTurnTotal(scope, "next")).isEqualTo(8);
          assertThat(AdHocConversation.beginTurnTotal(scope, "next")).isEqualTo(8);
          return null;
        });

    Map<?, ?> map = (Map<?, ?>) runtimeService.getVariableLocal(holderId,
        AdHocConversation.HOLDER_MARKER);
    assertThat(map.get(AdHocConversation.ACTIVITY_ID)).isEqualTo("adHocPerActivity");
    assertThat(map.get(AdHocConversation.LAYOUT_KEY)).isEqualTo(1);
    assertThat(map.get(AdHocConversation.ENTRIES)).isEqualTo(5);
    assertThat(map.get(AdHocConversation.TURNS_TOTAL)).isEqualTo(8);
    assertThat(map.get(AdHocConversation.TURNS_TOTAL_MARKER)).isEqualTo("next");
    assertThat(runtimeService.getVariablesLocal(holderId)).doesNotContainKeys(
        AdHocConversation.ENTRIES, AdHocConversation.TURNS_TOTAL,
        AdHocConversation.TURNS_TOTAL_MARKER);
    assertThat(runtimeService.getVariableLocal(holderId, "conversation-payload")).isEqualTo("keep me");
  }

  // --- drive(): which call is the driving agent's -------------------------------

  /** After {@code drive}, the scope is no longer the driven one on this thread. */
  @Test
  public void afterDriveTheScopeIsNoLongerDriven() throws Throwable {
    onAFreshThread(() -> {
      ExecutionEntity scope = new ExecutionEntity();
      AdHocConversation.drive(scope, () -> null);
      assertThat(AdHocConversation.drivenScope(scope)).isNull();
    });
  }

  /** The same when the call fails, as a model call can. */
  @Test
  public void afterAFailingDriveTheScopeIsNoLongerDriven() throws Throwable {
    onAFreshThread(() -> {
      ExecutionEntity scope = new ExecutionEntity();
      assertThatThrownBy(() -> AdHocConversation.drive(scope, () -> {
        throw new IllegalStateException("model down");
      })).isInstanceOf(IllegalStateException.class);
      assertThat(AdHocConversation.drivenScope(scope)).isNull();
    });
  }

  /** A nested {@code drive} hands the thread back to the outer scope when it returns. */
  @Test
  public void aNestedDriveRestoresTheOuterScope() throws Throwable {
    onAFreshThread(() -> {
      ExecutionEntity outer = new ExecutionEntity();
      ExecutionEntity inner = new ExecutionEntity();
      AdHocConversation.drive(outer, () -> {
        AdHocConversation.drive(inner, () -> {
          assertThat(AdHocConversation.drivenScope(inner)).isSameAs(inner);
          assertThat(AdHocConversation.drivenScope(outer)).isNull();
          return null;
        });
        assertThat(AdHocConversation.drivenScope(outer)).isSameAs(outer);
        assertThat(AdHocConversation.drivenScope(inner)).isNull();
        return null;
      });
      assertThat(AdHocConversation.drivenScope(outer)).isNull();
    });
  }

  /**
   * A tool runs outside the driving agent's call, and the agent's call goes on afterwards, also
   * when the tool fails: the agent's own writes after the tool still go where only it reaches.
   */
  @Test
  public void aToolRunsOutsideTheDriveWhichGoesOnAfterIt() throws Throwable {
    onAFreshThread(() -> {
      ExecutionEntity scope = new ExecutionEntity();
      AdHocConversation.drive(scope, () -> {
        AdHocConversation.outsideDrive(() -> {
          assertThat(AdHocConversation.drivenScope(scope)).as("while the tool runs").isNull();
          return null;
        });
        assertThat(AdHocConversation.drivenScope(scope)).as("after the tool").isSameAs(scope);
        assertThatThrownBy(() -> AdHocConversation.outsideDrive(() -> {
          throw new IllegalStateException("tool failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(AdHocConversation.drivenScope(scope)).as("after a failing tool").isSameAs(scope);
        return null;
      });
      assertThat(AdHocConversation.drivenScope(scope)).as("after the agent's call").isNull();
    });
  }

  /**
   * Runs {@code body} on a thread of its own, whose thread-locals start empty, and rethrows what
   * it threw: on a thread that drove scopes before, a value left behind would pass for the outer
   * scope a nested call restores, and hide the very leak these tests look for.
   */
  private static void onAFreshThread(Runnable body) throws Throwable {
    Throwable[] failure = new Throwable[1];
    Thread thread = new Thread(() -> {
      try {
        body.run();
      } catch (Throwable t) {
        failure[0] = t;
      }
    }, "drive-test");
    thread.start();
    thread.join();
    if (failure[0] != null) {
      throw failure[0];
    }
  }

}
