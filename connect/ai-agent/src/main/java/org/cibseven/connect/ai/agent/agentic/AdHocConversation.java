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

import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.impl.bpmn.helper.BpmnProperties;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.PvmActivity;
import org.cibseven.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;
import org.cibseven.bpm.engine.variable.Variables;

/**
 * Where an agent's conversation lives, and how long it lives (CIB7-2137).
 *
 * <p>The agent meant is the one that drives the scope, and the turn runner says which call is
 * that agent's ({@link #drive}). Any other AI agent task inside an ad hoc sub process, in a plain
 * one or used as a tool of an agentic one, keeps its conversation where every AI agent task keeps
 * it: at the process level, so a later task with the same {@code memoryId} continues it after the
 * scope has ended (CIB7-2232).
 *
 * <p>{@code perEntry}, the default and what the loop always did, keeps it on the agent state:
 * the conversation belongs to the scope execution, so entering the scope again starts a new
 * one. {@code perActivity} keeps it on a holder one level up, so every entry of this scope
 * within the enclosing scope continues the same conversation — what a feedback loop needs,
 * where the process leaves the scope for a user task and comes back.
 *
 * <p>The holder is an event-scope execution with activity = null under the execution enclosing
 * the scope, the same trick {@link AdHocAgentState} uses one level down, and for the same
 * reason: it is a <em>sibling</em> of the scope rather than an ancestor of its tools, so a tool
 * writing the memory's name creates its own variable at the process instance instead of
 * rewriting the conversation. It is removed with the enclosing scope, and clearing its activity
 * keeps it out of Cockpit's activity tree.
 * A holder directly under the process instance survives until that instance ends and blocks
 * all migration while it exists. This MVP restriction awaits the generic engine SPI in
 * CIB7-2167; the engine must not depend on AI-specific state.
 *
 * <p>Only the configured message window survives, not all history. With {@code perActivity},
 * an unspecified {@code chatMemoryMaxMessages} defaults to at least 53 messages and grows with
 * the effective model-call limit. The minimum is {@code 2 * maxModelCalls + 3}; it is a bounded
 * window, not a guarantee of unlimited history or of retaining arbitrary tool-call batches.
 * An explicitly smaller window is rejected. {@code perEntry} keeps the connector's existing
 * 20-message default and behaviour.
 * Sequential multi-instance iterations share this holder and total cap: later iterations
 * are re-entries and use {@code followUp} on their first turn.
 *
 * <p>Recognised by the activity id it carries, not by a fixed marker: two agentic scopes under
 * one enclosing execution each have a holder, and {@link AdHocAgentState#find} — which matches a
 * fixed marker — would hand out whichever came first.
 */
public final class AdHocConversation {

  /** {@code camunda:property} on the scope choosing where the conversation lives. */
  public static final String MODE_PROPERTY = "cibseven.agentic.conversation";

  /** Every entry of the scope starts a new conversation. The default. */
  public static final String PER_ENTRY = "perEntry";

  /** The conversation continues on every entry of this scope within the enclosing scope. */
  public static final String PER_ACTIVITY = "perActivity";

  /** {@code camunda:property} bounding the turns of every entry together. */
  public static final String MAX_TURNS_TOTAL_PROPERTY = "cibseven.agentic.maxTurnsTotal";

  /** Marks the holder and holds its versioned bookkeeping map; conversation stays separate. */
  public static final String HOLDER_MARKER = "adHocAgentConversationOf";
  public static final String ACTIVITY_ID = "activityId";
  public static final String LAYOUT_KEY = "v";
  public static final int LAYOUT = 1;

  /** Contextual default with the default model-call limit: 2 * 25 + 3. */
  public static final int DEFAULT_PER_ACTIVITY_MEMORY_WINDOW = 53;

  /** Entries of the scope so far, counted when it is entered. */
  public static final String ENTRIES = "adHocAgentEntries";

  /** Turns across every entry, and the step last counted against them. */
  public static final String TURNS_TOTAL = "adHocAgentTurnsTotal";
  public static final String TURNS_TOTAL_MARKER = "adHocAgentTurnsTotalMarker";

  private AdHocConversation() {
    // utility class
  }

  /**
   * Minimum retained message count for a perActivity conversation, computed in long arithmetic.
   */
  public static long requiredMemoryWindow(int maxModelCalls) {
    return 2L * maxModelCalls + 3L;
  }

  /** Contextual default for an unspecified perActivity window. */
  public static int defaultMemoryWindow(int maxModelCalls) {
    long required = requiredMemoryWindow(maxModelCalls);
    if (required > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("required chat memory window " + required
          + " exceeds the supported maximum of " + Integer.MAX_VALUE + " messages");
    }
    return (int) Math.max(DEFAULT_PER_ACTIVITY_MEMORY_WINDOW, required);
  }

  /** Resolves the max-model-call property using the agent loop's positive-property fallback. */
  public static int effectiveMaxModelCalls(Object configured) {
    if (configured != null) {
      try {
        int parsed = Integer.parseInt(String.valueOf(configured).trim());
        if (parsed > 0) {
          return parsed;
        }
      } catch (NumberFormatException ignored) {
        // Match AdHocAgentState.positiveProperty: invalid values use the default.
      }
    }
    return AdHocAgentState.DEFAULT_MAX_MODEL_CALLS;
  }

  /**
   * Whether this scope keeps its conversation across entries.
   *
   * <p>An unknown value reads as {@link #PER_ENTRY} rather than failing, the same direction
   * {@code positiveProperty} falls back in: the parse listener already refused it at
   * deployment, so a value arriving here at all means a model that predates that rule, and
   * the old behaviour is the safe one.
   */
  public static boolean isPerActivity(ExecutionEntity scopeExecution) {
    PvmActivity activity = scopeExecution.getActivity();
    Object raw = (activity == null)
        ? null
        : activity.getProperty(BpmnProperties.EXTENSION_PROPERTIES.getName());
    if (raw instanceof Map) {
      Object configured = ((Map<?, ?>) raw).get(MODE_PROPERTY);
      return configured != null && PER_ACTIVITY.equals(String.valueOf(configured).trim());
    }
    return false;
  }

  /**
   * The nearest enclosing scope execution — the process instance for a scope modelled directly
   * in the process, the enclosing sub process otherwise.
   *
   * <p>{@code getParentScopeExecution} rather than {@code getParent()}: behind a parallel
   * gateway the direct parent is a concurrent execution the engine compacts away when the
   * branches join, and the conversation has to outlive that.
   */
  protected static PvmExecutionImpl enclosing(ExecutionEntity scopeExecution) {
    return scopeExecution.getParentScopeExecution(false);
  }

  /** The holder of this scope, or {@code null} when none was created yet. Never creates one. */
  public static PvmExecutionImpl find(ExecutionEntity scopeExecution) {
    PvmExecutionImpl enclosing = enclosing(scopeExecution);
    String activityId = scopeExecution.getActivityId();
    if (enclosing == null || activityId == null) {
      return null;
    }
    for (PvmExecutionImpl candidate : enclosing.getEventScopeExecutions()) {
      if (activityId.equals(activityId(candidate))) {
        return candidate;
      }
    }
    return null;
  }

  /** The holder of this scope, created on first use. */
  public static PvmExecutionImpl findOrCreate(ExecutionEntity scopeExecution) {
    PvmExecutionImpl existing = find(scopeExecution);
    if (existing != null) {
      migrateLegacy(existing);
      return existing;
    }
    return createHolder(scopeExecution, 0);
  }

  private static PvmExecutionImpl createHolder(ExecutionEntity scopeExecution, int entries) {
    PvmExecutionImpl enclosing = enclosing(scopeExecution);
    if (enclosing == null) {
      throw missingEnclosing(scopeExecution);
    }
    ExecutionEntity holder = ((ExecutionEntity) enclosing).createExecution();
    // The same four lines as AdHocAgentState.findOrCreate, and for the same measured reasons:
    // destroy() runs the activity's output io-mapping on removal and fails on variables this
    // execution does not have, and clearing the activity also keeps the holder out of
    // createActivityExecutionMapping(), so Cockpit's activity tree is unaffected.
    holder.setActivity(null);
    holder.setActive(false);
    holder.setConcurrent(false);
    holder.setEventScope(true);
    Map<String, Object> map = new TreeMap<>();
    map.put(LAYOUT_KEY, Integer.valueOf(LAYOUT));
    map.put(ACTIVITY_ID, scopeExecution.getActivityId());
    map.put(ENTRIES, Integer.valueOf(entries));
    holder.setVariableLocal(HOLDER_MARKER, Variables.objectValue(map).create());
    return holder;
  }

  /**
   * Counts this entry of the scope, so a later turn can tell a re-entry from a first entry.
   *
   * <p>Called when the scope is entered, in that transaction, like the agent state itself: the
   * first turn runs in a job of its own and must already see the right count.
   */
  public static void enterScope(ExecutionEntity scopeExecution) {
    if (!isPerActivity(scopeExecution)) {
      return;
    }
    PvmExecutionImpl holder = find(scopeExecution);
    if (holder == null) {
      createHolder(scopeExecution, 1);
      return;
    }
    migrateLegacy(holder);
    Map<String, Object> map = holderMap(holder);
    map.put(ENTRIES, Integer.valueOf(number(map.get(ENTRIES)) + 1));
    AdHocAgentState.stageMap(holder, HOLDER_MARKER, map);
  }

  /** Entries of this scope so far, zero before the first one is counted. */
  public static int entries(ExecutionEntity scopeExecution) {
    PvmExecutionImpl holder = find(scopeExecution);
    return (holder == null) ? 0 : number(holderMap(holder).get(ENTRIES));
  }

  /** Whether the scope was entered before, so the agent has a conversation to continue. */
  public static boolean isReEntry(ExecutionEntity scopeExecution) {
    return entries(scopeExecution) > 1;
  }

  /** The execution the conversation is written to, created on first use. */
  public static PvmExecutionImpl memoryExecution(ExecutionEntity scopeExecution) {
    return isPerActivity(scopeExecution)
        ? findOrCreate(scopeExecution)
        : AdHocAgentState.findOrCreate(scopeExecution);
  }

  /** The execution the conversation is read from, or {@code null}. Never creates one. */
  public static PvmExecutionImpl findMemoryExecution(ExecutionEntity scopeExecution) {
    return isPerActivity(scopeExecution)
        ? find(scopeExecution)
        : AdHocAgentState.find(scopeExecution);
  }

  /** The scope whose agent {@link #drive} is asking on this thread, while it does. */
  private static final ThreadLocal<ExecutionEntity> DRIVEN = new ThreadLocal<ExecutionEntity>();

  /**
   * Runs {@code call} as the agent that drives {@code scopeExecution}: while it runs, the
   * conversation read and written on the scope execution itself is that agent's, and the chat
   * memory store keeps it on {@link #memoryExecution}, where the scope's tools cannot reach it.
   *
   * <p>The turn runner says so, as the one caller that drives a scope, rather than the store
   * guessing it from the execution tree (CIB7-2232). Guessed from the tree, every AI agent task
   * inside any ad hoc sub process was the scope's agent: its conversation went onto an agent state,
   * created under a plain scope as well, and ended with the scope. An AI agent task used as a tool
   * runs on an execution of its own, so even while {@code call} runs its conversation is not the
   * agent's. Nor is anything a tool of the step sets off on the scope execution itself: the tools
   * run outside the call ({@link #outsideDrive}).
   */
  static <T> T drive(ExecutionEntity scopeExecution, Supplier<T> call) {
    ExecutionEntity outer = DRIVEN.get();
    DRIVEN.set(scopeExecution);
    try {
      return call.get();
    } finally {
      if (outer == null) {
        DRIVEN.remove();
      } else {
        DRIVEN.set(outer);
      }
    }
  }

  /**
   * Runs {@code call}, a tool the driving agent called, outside the agent's call. The tools of an
   * agentic step run within {@link #drive}, on the same thread, and so does everything the engine
   * does while they run: a tool's BPMN error, caught by a boundary event on the scope, cancels the
   * scope there and then, and the scope's end listeners run on the very execution {@link #drive}
   * marks. They would pass for the driving agent and could read or clear its conversation, which
   * neither a tool nor code on the scope may (CIB7-2232). The mark is put back afterwards, for
   * the agent's own writes once the tool has run.
   */
  public static <T> T outsideDrive(Supplier<T> call) {
    ExecutionEntity driven = DRIVEN.get();
    if (driven == null) {
      return call.get();
    }
    DRIVEN.remove();
    try {
      return call.get();
    } finally {
      DRIVEN.set(driven);
    }
  }

  /**
   * {@code execution}, while {@link #drive} runs for it on this thread: the conversation read or
   * written on it is then the scope's own. {@code null} for every other execution, that of an AI
   * agent task inside the scope included.
   */
  public static ExecutionEntity drivenScope(ExecutionEntity execution) {
    ExecutionEntity driven = DRIVEN.get();
    return (driven != null && driven == execution) ? driven : null;
  }

  /**
   * What names the conversation.
   *
   * <p>{@code perEntry}: the scope execution, so every entry is its own. {@code perActivity}:
   * the enclosing execution and the activity id, both of which the next entry of the same scope
   * finds again. Derived rather than read off the holder, so it stays a pure function and a
   * read path never has to create one.
   */
  public static String memoryId(ExecutionEntity scopeExecution) {
    return isPerActivity(scopeExecution)
        ? scopeAnchor(scopeExecution)
        : "adhoc-" + scopeExecution.getId();
  }

  /**
   * {@code "adhoc-"} plus the enclosing execution's id plus the scope's own activity id — stable
   * across a {@code perActivity} re-entry, unlike the scope execution's own id, which a re-entry
   * recreates.
   *
   * <p>Only {@link #memoryId} uses it, for {@code perActivity}. The derived id of a tool without
   * a {@code memoryId} of its own ({@code AgentConnectorImpl.adHocScopeMemoryId()}, CIB7-2155) is
   * built on {@link #memoryId}, and so, with {@code perActivity}, on this anchor: it appends
   * {@code ":tool:"} and the calling activity's id, which keeps it apart from every scope's own
   * key.
   *
   * @throws ProcessEngineException when the scope has no enclosing scope execution — not
   *     expected in practice, see {@link #missingEnclosing}.
   */
  private static String scopeAnchor(ExecutionEntity scopeExecution) {
    PvmExecutionImpl enclosing = enclosing(scopeExecution);
    if (enclosing == null) {
      throw missingEnclosing(scopeExecution);
    }
    return "adhoc-" + enclosing.getId() + "-" + scopeExecution.getActivityId();
  }

  /** Turns across every entry, zero before the first one is counted. */
  public static int turnsTotal(ExecutionEntity scopeExecution) {
    PvmExecutionImpl holder = find(scopeExecution);
    return (holder == null) ? 0 : number(holderMap(holder).get(TURNS_TOTAL));
  }

  /**
   * Counts the turn {@code turnId} against every entry together, once, and returns the count.
   *
   * <p>Zero without a holder, which is every {@code perEntry} scope: there is no total to
   * count, and the caller's {@code > 0} guard on the configured limit never fires.
   */
  public static int beginTurnTotal(ExecutionEntity scopeExecution, String turnId) {
    PvmExecutionImpl holder = find(scopeExecution);
    if (holder == null) {
      return 0;
    }
    migrateLegacy(holder);
    Map<String, Object> map = holderMap(holder);
    if (turnId != null && turnId.equals(map.get(TURNS_TOTAL_MARKER))) {
      return number(map.get(TURNS_TOTAL));
    }
    if (turnId == null) {
      map.remove(TURNS_TOTAL_MARKER);
    } else {
      map.put(TURNS_TOTAL_MARKER, turnId);
    }
    int counted = number(map.get(TURNS_TOTAL)) + 1;
    map.put(TURNS_TOTAL, Integer.valueOf(counted));
    AdHocAgentState.stageMap(holder, HOLDER_MARKER, map);
    return counted;
  }

  /** Recognises both the legacy activity-id string and the versioned holder map. */
  static String activityId(PvmExecutionImpl holder) {
    Object activity = AdHocAgentState.currentMap(holder, HOLDER_MARKER).get(ACTIVITY_ID);
    if (activity == null) {
      Object legacy = holder.getVariableLocal(HOLDER_MARKER);
      activity = (legacy instanceof String) ? legacy : null;
    }
    return (activity == null) ? null : activity.toString();
  }

  private static Map<String, Object> holderMap(PvmExecutionImpl holder) {
    Map<String, Object> map = new TreeMap<>(AdHocAgentState.currentMap(holder, HOLDER_MARKER));
    if (map.isEmpty()) {
      map.put(LAYOUT_KEY, Integer.valueOf(LAYOUT));
      map.put(ACTIVITY_ID, activityId(holder));
      for (String key : new String[] { ENTRIES, TURNS_TOTAL, TURNS_TOTAL_MARKER }) {
        Object value = holder.getVariableLocal(key);
        if (value != null) {
          map.put(key, value);
        }
      }
    }
    return map;
  }

  private static void migrateLegacy(PvmExecutionImpl holder) {
    if (holder.getVariableLocal(HOLDER_MARKER) instanceof String) {
      // Initialise the mutable object before command-close listeners run, as agent state does.
      holder.setVariableLocal(HOLDER_MARKER, Variables.objectValue(holderMap(holder)).create());
      for (String key : new String[] { ENTRIES, TURNS_TOTAL, TURNS_TOTAL_MARKER }) {
        holder.removeVariableLocal(key);
      }
    }
  }

  private static int number(Object value) {
    return (value instanceof Number) ? ((Number) value).intValue() : 0;
  }

  private static ProcessEngineException missingEnclosing(ExecutionEntity scope) {
    return new ProcessEngineException("Ad hoc sub process '" + scope.getActivityId()
        + "' has no enclosing scope execution.");
  }
}
