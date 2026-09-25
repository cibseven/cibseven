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
package org.cibseven.bpm.engine.impl.bpmn.behavior;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.PvmActivity;
import org.cibseven.bpm.engine.impl.pvm.delegate.ActivityExecution;
import org.cibseven.bpm.engine.delegate.ExecutionListener;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.impl.pvm.PvmScope;
import org.cibseven.bpm.engine.impl.pvm.delegate.ActivityBehavior;
import org.cibseven.bpm.engine.impl.pvm.delegate.CompositeActivityBehavior;
import org.cibseven.bpm.engine.impl.pvm.delegate.ModificationObserverBehavior;
import org.cibseven.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;
import org.cibseven.bpm.engine.impl.pvm.runtime.operation.PvmAtomicOperation;
import org.cibseven.bpm.engine.impl.Condition;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.delegate.Expression;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.impl.pvm.process.ScopeImpl;
import org.cibseven.bpm.engine.impl.bpmn.helper.CompensationUtil;
import org.cibseven.bpm.engine.impl.bpmn.helper.BpmnProperties;
import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParse;
import java.util.Collection;
import java.util.Collections;
import org.cibseven.bpm.engine.impl.pvm.runtime.Callback;
import org.cibseven.bpm.engine.impl.util.JsonUtil;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

/**
 * Runtime behavior of a BPMN adHocSubProcess.
 *
 * <p>No child is reached from the scope's own start. A child is started directly, by the activation
 * API or by the entry list from CIB7-1891, and may reach others from there along the sequence flows
 * between children that CIB7-1882 allows.
 *
 * <p>Notes on the parts that are not obvious from the code:
 *
 * <ul>
 * <li><b>Entry starts only the entry list.</b> {@link #execute(ActivityExecution)} starts what
 *     {@code activeElementsCollection} names, if anything, and otherwise leaves the scope waiting.</li>
 * <li><b>{@link #initializeScope} must honour {@code numberOfInstances}.</b> Its caller,
 *     {@code PvmAtomicOperationActivityInitStackNotifyListenerStart}, passes 1 and immediately does
 *     {@code get(0)}. That path is process-instance modification instantiating a dormant scope, not
 *     normal entry, so returning an empty list there would throw.</li>
 * <li><b>Both completion callbacks are reachable.</b> {@code tryPruneLastConcurrentChild} is not
 *     involved: it runs only where the flow scope is the process definition, and an ad hoc child's
 *     flow scope is the ad hoc scope. What decides is {@code PvmAtomicOperationActivityEnd}. Every
 *     child runs on a concurrent execution, and one that is itself a scope hands its end to that
 *     concurrent execution as its own is removed, so every child ends through
 *     {@link #concurrentChildExecutionEnded}. {@link #complete} is reached by work running on the
 *     scope execution itself, which is an interrupting event sub process. Unlike parallel
 *     multi-instance, {@code complete} here is NOT "can't happen".</li>
 * </ul>
 */
public class AdHocSubProcessActivityBehavior extends AbstractBpmnActivityBehavior
    implements CompositeActivityBehavior, ModificationObserverBehavior {

  /**
   * Records that this scope instance has activated something; drives the no-condition completion
   * rule. Written on the first activation and never again.
   */
  public static final String ACTIVATED = "adHocActivated";

  /**
   * Records that the completion condition has held. Needed because
   * {@code cancelRemainingInstances="false"} leaves the scope open while its survivors finish, and
   * FR-15 requires it to start nothing further in the meantime — a fact that has to be remembered
   * rather than re-derived, since the condition may read variables that keep changing.
   */
  public static final String COMPLETION_CONDITION_SATISFIED = "adHocCompletionConditionSatisfied";

  protected Condition completionCondition;

  /**
   * Activities to start when the scope is entered, from CIB7-1891. Null means the historical
   * behaviour: entering starts nothing and every activity arrives through the activation API.
   */
  protected Expression entryActivityIds;
  protected boolean cancelRemainingInstances = true;

  /** CIB7-1892. The variable each performance's result is appended to, or null when not gathering. */
  protected String outputCollectionName;

  /** CIB7-1892. Evaluated once per completed child; its value is what gets appended. */
  protected Expression outputElement;

  @Override
  public void execute(ActivityExecution execution) throws Exception {
    // Entering an ad-hoc scope starts nothing, and deliberately records nothing: writing the flag
    // here would put a variable — and, since the flag lives on a marker, an execution — into every
    // ad hoc instance from birth, including scopes that never activate anything. The flag is written
    // on first activation instead, so a never-activated scope carries neither.

    // Do NOT null the activity here, even though parallel multi-instance does.
    // An execution with activity == null does not appear in createActivityExecutionMapping(),
    // so an empty ad-hoc scope becomes untargetable: AbstractInstantiationCmd:152 walks looking
    // for a scope with executions, finds none, and throws a raw NoSuchElementException from
    // flowScopeExecutions.iterator().next(). Multi-instance survives this only because it always
    // has concurrent children to resolve through; an empty ad-hoc scope has none.
    execution.inactivate();

    startEntryActivities(execution);
  }

  /**
   * Starts the activities named by {@code camunda:property activeElementsCollection}, the declarative entry
   * activation of CIB7-1891. Does nothing when the property is absent, which is every model written
   * before it existed.
   *
   * <p>Two behaviours here are deliberate and easy to get wrong.
   *
   * <p>It runs <em>after</em> {@code inactivate()}, so the scope is in exactly the state the
   * activation API finds it in. Anything else would make entry activation a second code path with its
   * own bugs.
   *
   * <p>And if the completion condition already holds, it starts nothing rather than refusing. The
   * refusal that CIB7-1850 added throws, and throwing here would fail the process start outright — a
   * model whose condition happens to be true on entry would become undeployable in practice. Starting
   * nothing leaves the scope waiting, which is what CIB7-1851 decided such a scope does anyway.
   */
  protected void startEntryActivities(ActivityExecution scopeExecution) {
    if (entryActivityIds == null) {
      return;
    }
    if (isConditionSatisfied(scopeExecution) || conditionHoldsNow(scopeExecution)) {
      return;
    }

    ScopeImpl scope = (ScopeImpl) scopeExecution.getActivity();
    Object value = entryActivityIds.getValue(scopeExecution);
    List<String> requested = activityIdsOf(value, scope.getId());
    if (requested.isEmpty()) {
      return;
    }

    List<String> unknown = new ArrayList<String>();
    List<ActivityImpl> targets = resolveStartableChildren(scope, requested, unknown);
    if (!unknown.isEmpty()) {
      throw new ProcessEngineException("Ad hoc sub process '" + scope.getId()
          + "': activeElementsCollection names " + unknown + ", which " + (unknown.size() == 1 ? "is" : "are")
          + " not directly startable here. The startable activities are " + startableActivityIds(scope) + "."
          + evaluatedTo(value));
    }

    // Two passes, for the same reason ActivateAdHocSubProcessActivitiesCmd splits its loop: starting a child can
    // run it to completion, and a child completing while nothing else is active completes the whole
    // scope, so the next create would operate on an execution that has already ended. Creating every
    // child first is also what keeps the scope open for the batch when it has no completion condition.
    List<PvmExecutionImpl> children = new ArrayList<PvmExecutionImpl>();
    for (int i = 0; i < requested.size(); i++) {
      children.add((PvmExecutionImpl) createInnerInstance(scopeExecution));
    }

    // The second pass cannot start the children here, and that is the difference from the command.
    // This runs inside the scope's own execute, i.e. inside an atomic operation, where executeActivities
    // does not run anything: it only pushes an operation, and the operations run afterwards off a stack,
    // last pushed first. Starting them in a loop therefore reversed the order, and asked "was this child
    // removed?" before any child had run -- so a child that should have been skipped because an earlier
    // one completed the scope was started anyway and then cancelled.
    //
    // So each start is pushed as an operation of its own, in reverse, and it decides when its turn
    // comes. The stack unwinds them in the requested order, and each one runs only after the previous
    // child has gone as far as it can -- which is exactly when the question has an answer.
    for (int i = requested.size() - 1; i >= 0; i--) {
      children.get(i).performOperation(new StartEntryChild(targets.get(i)));
    }
  }

  /**
   * Starts one entry activity when its turn comes on the operation stack, and only if its execution is
   * still there. Pushed rather than run, so that it sees what the children before it have done.
   *
   * <p>Deliberately never asynchronous: it creates no job and is never serialised. It is async
   * <em>capable</em> only so that, pushed from inside a running operation, it waits its turn rather
   * than running on the spot, which is the whole point.
   */
  protected static class StartEntryChild implements PvmAtomicOperation {

    protected final PvmActivity activity;

    protected StartEntryChild(PvmActivity activity) {
      this.activity = activity;
    }

    @Override
    public void execute(PvmExecutionImpl childExecution) {
      // An earlier child can have satisfied the completion condition, and completeScope then deletes
      // the children not yet started. Starting a deleted execution fails on flush.
      if (childExecution.isEnded() || childExecution.isRemoved()) {
        return;
      }
      childExecution.executeActivities(Collections.<PvmActivity>emptyList(), activity, null, null, null,
          false, false);
    }

    @Override
    public boolean isAsync(PvmExecutionImpl execution) {
      return false;
    }

    @Override
    public boolean isAsyncCapable() {
      return true;
    }

    @Override
    public String getCanonicalName() {
      return "ad-hoc-start-entry-child";
    }
  }

  /**
   * The ids of the activities that can be started directly in this scope, computed at parse time
   * (CIB7-1853). Never null.
   */
  public List<String> startableActivityIds(ScopeImpl scope) {
    List<String> startable = scope.getProperties().get(BpmnProperties.AD_HOC_STARTABLE_ACTIVITIES);
    return startable == null ? Collections.<String>emptyList() : startable;
  }

  /**
   * The children to start for the given ids, one per id and in the order given, so an id named twice
   * is started twice. This is the one place both ways in -- the activation API and entry activation
   * -- decide what a request means, which is why they cannot drift apart again (review finding 3 was
   * the two copies disagreeing).
   *
   * <p>An id that is not directly startable gets no child and is added to {@code rejected} instead,
   * so that each caller refuses in its own terms: the API answers its caller, entry activation fails
   * the start of the process.
   */
  public List<ActivityImpl> resolveStartableChildren(ScopeImpl scope, Collection<String> activityIds,
      List<String> rejected) {
    List<String> startable = startableActivityIds(scope);
    List<ActivityImpl> children = new ArrayList<ActivityImpl>();
    for (String activityId : activityIds) {
      if (activityId != null && startable.contains(activityId)) {
        children.add(findStartableChild(scope, activityId));
      } else {
        rejected.add(String.valueOf(activityId));
      }
    }
    return children;
  }

  /**
   * The child to execute, which is not always the activity carrying the requested id.
   *
   * <p>Loop characteristics make the parser wrap the activity: the direct child of the scope is a
   * generated multi-instance body with the requested activity nested inside it. A recursive lookup
   * would find the nested one, and executing that bypasses the body that owns the loop, so only
   * direct children are considered and the generated body is resolved by name.
   */
  public ActivityImpl findStartableChild(ScopeImpl scope, String activityId) {
    for (ActivityImpl child : scope.getActivities()) {
      if (activityId.equals(child.getId())) {
        return child;
      }
    }
    String bodyId = activityId + BpmnParse.MULTI_INSTANCE_BODY_ID_SUFFIX;
    for (ActivityImpl child : scope.getActivities()) {
      if (bodyId.equals(child.getId())) {
        return child;
      }
    }
    // The startable set is derived from the same element at parse time, so this cannot happen
    // unless the two fall out of step -- an engine fault, not a bad request.
    throw new ProcessEngineException("Ad hoc sub process '" + scope.getId() + "' reports '"
        + activityId + "' as startable but has no such child activity.");
  }

  /**
   * The activity ids an entry list names. Shared by the parser, which checks a literal list at
   * deployment, and by entry activation, which evaluates the expression when the scope is entered.
   *
   * <p>A collection or an array gives its elements. Anything else is read as text. Text that starts
   * with {@code [} is a JSON array of ids -- which is what a Json variable holds, and an id, being an
   * NCName, never starts with {@code [}. Any other text is a comma-separated list, which is what makes
   * the static authoring form work: {@code value="taskA,taskB"} is a literal to the expression
   * manager, so one path serves a literal list and an expression over process data alike.
   */
  public static List<String> activityIdsOf(Object value, String scopeId) {
    List<String> ids = new ArrayList<String>();
    if (value == null) {
      return ids;
    }
    if (value instanceof Collection) {
      for (Object item : (Collection<?>) value) {
        addActivityId(ids, item);
      }
      return ids;
    }
    if (value.getClass().isArray()) {
      for (int i = 0; i < Array.getLength(value); i++) {
        addActivityId(ids, Array.get(value, i));
      }
      return ids;
    }
    String text = String.valueOf(value).trim();
    if (text.startsWith("[")) {
      for (JsonElement element : jsonArrayOfIds(text, scopeId)) {
        addActivityId(ids, element.getAsString());
      }
      return ids;
    }
    for (String part : text.split(",")) {
      addActivityId(ids, part);
    }
    return ids;
  }

  protected static void addActivityId(List<String> ids, Object item) {
    if (item != null && !String.valueOf(item).trim().isEmpty()) {
      ids.add(String.valueOf(item).trim());
    }
  }

  /** Parses a JSON array whose every element is a string, or refuses it by name. */
  protected static JsonArray jsonArrayOfIds(String text, String scopeId) {
    JsonArray array = null;
    try {
      array = JsonUtil.getGsonMapper().fromJson(text, JsonArray.class);
    } catch (RuntimeException e) {
      // not JSON at all: refused below, with the text that was given
    }
    boolean ofStrings = array != null;
    if (ofStrings) {
      for (JsonElement element : array) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
          ofStrings = false;
          break;
        }
      }
    }
    if (!ofStrings) {
      throw new ProcessEngineException("Ad hoc sub process '" + scopeId + "': activeElementsCollection gives "
          + text + ", which is not a JSON array of activity ids.");
    }
    return array;
  }

  /**
   * Names the type of an entry list that was neither text, a collection nor an array, for a refusal:
   * such a value is read through its string form, and without the type the ids it produced make no
   * sense to whoever reads the message.
   */
  protected static String evaluatedTo(Object value) {
    if (value == null || value instanceof String || value instanceof Collection
        || value.getClass().isArray()) {
      return "";
    }
    return " The expression evaluated to a " + value.getClass().getName() + ".";
  }

  public void setEntryActivityIds(Expression entryActivityIds) {
    this.entryActivityIds = entryActivityIds;
  }

  @Override
  public List<ActivityExecution> initializeScope(ActivityExecution scopeExecution, int numberOfInstances) {
    // Called on the instantiation-stack path (process instance modification), never on normal entry.
    // The caller dereferences get(0), so numberOfInstances must be honoured.
    ensureFurtherActivationAllowed(scopeExecution);
    if (numberOfInstances > 0) {
      markActivated(scopeExecution);
    }

    List<ActivityExecution> executions = new ArrayList<ActivityExecution>();
    for (int i = 0; i < numberOfInstances; i++) {
      executions.add(createConcurrentExecution(scopeExecution));
    }
    return executions;
  }

  @Override
  public ActivityExecution createInnerInstance(ActivityExecution scopeExecution) {
    ensureFurtherActivationAllowed(scopeExecution);
    markActivated(scopeExecution);
    return createConcurrentExecution(scopeExecution);
  }

  @Override
  public void destroyInnerInstance(ActivityExecution concurrentExecution) {
    ActivityExecution scopeExecution = concurrentExecution.getParent();
    concurrentExecution.remove();
    scopeExecution.forceUpdate();
  }

  @Override
  public void concurrentChildExecutionEnded(ActivityExecution scopeExecution, ActivityExecution endedExecution) {
    gatherResult(scopeExecution, endedExecution);

    // Decide before disposing of anything: a completion condition normally reads a variable the
    // ended child just wrote, and completeScope needs the ended execution's activity to find the
    // flow scope to return to.
    if (isCompleted(scopeExecution, endedExecution)) {
      completeScope(scopeExecution, endedExecution);
      return;
    }

    // The scope goes back to waiting for the next activation, and the ended child has no further
    // purpose, so it is removed.
    //
    // Parallel multi-instance only inactivates its ended children at this point, because its join
    // counts the inactive ones against a fixed cardinality; an ended instance is still evidence.
    // An ad hoc scope has no cardinality. Children are activated on demand, any number of times,
    // and nothing ever consumes those inactive executions. Copying the multi-instance idiom here
    // leaked one execution per activation, without bound, for the life of the instance.
    //
    // It also cost the scope its identity. Each retained child is a non-scope execution under the
    // scope carrying the child's own activity, so createActivityExecutionMapping resolved the ad
    // hoc scope through one of them, and the runtime tree reported the last child that ran instead
    // of the scope itself. Removing the child here is what fixes both.
    endedExecution.remove();
    scopeExecution.forceUpdate();

    ((ExecutionEntity) scopeExecution).dispatchDelayedEventsAndPerformOperation((Callback<PvmExecutionImpl, Void>) null);
  }

  /**
   * CIB7-1967. Hands the children's compensation subscriptions to the parent scope on the way out.
   *
   * <p>The parser marks an ad hoc scope as consuming compensation, and a child may carry a
   * compensation boundary event whose handler sits inside the scope. Those subscriptions live on the
   * scope execution while it runs, and they die with it unless they are copied onto an event scope
   * execution under the parent first. {@code SubProcessActivityBehavior} does exactly this, and an
   * ad hoc scope has the same obligation for the same reason: without it, throwing compensation at
   * the scope afterwards finds nothing and silently does nothing.
   *
   * <p>The marker holding the scope's state is removed first. It is an event-scope child as well, and
   * {@code createEventScopeExecution} moves every event-scope child into the compensation event
   * scope, where the marker outlived the scope with its variables until the process ended (review
   * finding 6). It belongs to the running scope, and the scope is leaving.
   */
  @Override
  public void doLeave(ActivityExecution execution) {
    PvmExecutionImpl marker = findStateExecution(execution);
    if (marker != null) {
      marker.remove();
    }
    CompensationUtil.createEventScopeExecution((ExecutionEntity) execution);
    super.doLeave(execution);
  }

  /**
   * Reached when work running on the scope execution itself ends, which in practice means an
   * interrupting event sub process that has already cancelled the children. A child, whether or not
   * it is a scope, ends through {@link #concurrentChildExecutionEnded} instead.
   */
  @Override
  public void complete(ActivityExecution scopeExecution) {
    // No gatherResult here, and no completion condition either. Both omissions have one cause,
    // measured across the ad hoc suites rather than assumed: the only thing that reaches this
    // callback is an interrupting event sub process finishing, with no children left under the
    // scope. That is an event handler rather than a performance, so there is nothing to gather --
    // gathering here would evaluate the element expression against whatever the last real
    // performance left behind and append it a second time.
    // CIB7-2074. The completion condition is not consulted here, and that is the point. An
    // interruption has already cancelled the discretionary work, so there is nothing left for a
    // rule about discretionary work to decide. Asking it anyway is what the defect was: a scope
    // parked on a never-true condition went back to waiting after being interrupted -- waiting for
    // a caller that the interruption had just cancelled, in the agentic shape where that caller is
    // the driver. Measured: no model can recover it, only an external completeAdHocSubProcess.
    //
    // Safe only while a non-interrupting event sub process and a non-interrupting boundary event
    // inside the scope are refused at deployment (CIB7-1967). Such a handler runs *beside* the
    // scope and must not end it. If that refusal is ever lifted, this has to tell the two apart
    // again. A model that wants to react without ending the scope already has a supported way to
    // say so: a non-interrupting boundary event on the ad hoc sub process itself, which runs
    // alongside and leaves the scope open -- measured on a distribution.
    //
    // CIB7-1961 keeps its guard: under cancelRemainingInstances="false" the survivors decide when
    // the scope may go, and until they have finished it does not.
    if (disposeOfRemainingChildren(scopeExecution)) {
      leave(scopeExecution);
    }
  }

  /**
   * Appends this performance's result to the gathering variable, if the scope gathers (CIB7-1892).
   *
   * <p>Called as a child ends, and <em>before</em> the completion condition is consulted -- so a
   * condition can be written against what has been gathered so far, which is the natural way to say
   * "enough". One call site, not two: see {@link #complete(ActivityExecution)} for why the other
   * callback a child can end through is not one of them.
   *
   * <p>Timing is the whole of it. The child's own output mapping has already run by now: an ad hoc
   * child that carries one is a scope, and a scope executes its output parameters as it is
   * destroyed, which happens before the flow scope's behaviour is notified. So the expression reads
   * the value this performance produced, in the one moment it is still the current one -- the next
   * performance of the same child overwrites it, and that is precisely the defect being answered.
   *
   * <p>The list is rebuilt and written back rather than mutated in place, because a mutation of the
   * value an entity already holds is not reliably detected as a change.
   *
   * <p>Two rules make one expression serve every child of the scope: it is evaluated against the
   * child that ended, so {@code execution} names that child, and a null result adds nothing. Between
   * them a model can gather selectively -- {@code ${execution.currentActivityId == 'tool' ? result :
   * null}} -- and record which performance produced an entry.
   */
  protected void gatherResult(ActivityExecution scopeExecution, ActivityExecution endedExecution) {
    if (outputCollectionName == null) {
      return;
    }

    // Evaluated against the child that just ended, not against the scope. Both resolve the same
    // variables -- the child has none of its own and the lookup walks up to the scope -- but only
    // this one puts the ending child in reach of the expression, as {@code execution}. Without that
    // the expression cannot tell which performance it is being asked about, and a child that wrote
    // nothing appends whatever the previous one left. Measured before it was fixed: a scope with a
    // child that writes nothing gathered the same value twice.
    Object value = outputElement.getValue(endedExecution);

    // Null means this performance contributed nothing, and is how an expression declines. It is the
    // only way a model can say "gather this child and not that one", because one expression serves
    // every child of the scope.
    if (value == null) {
      return;
    }

    Object current = scopeExecution.getVariable(outputCollectionName);
    List<Object> gathered = new ArrayList<Object>();
    if (current instanceof Collection) {
      gathered.addAll((Collection<?>) current);
    } else if (current != null) {
      throw new ProcessEngineException("Ad hoc sub process '" + scopeExecution.getActivity().getId()
          + "' gathers results into '" + outputCollectionName + "', but that variable already holds"
          + " a " + current.getClass().getSimpleName() + " rather than a collection. Choose a name"
          + " nothing else writes to.");
    }
    gathered.add(value);

    // Deliberately setVariable, not setVariableLocal: a variable local to the scope execution dies
    // with the scope, and results are wanted after it has left.
    scopeExecution.setVariable(outputCollectionName, gathered);
  }

  /**
   * With a completion condition: the condition decides.
   * Without one: complete when nothing is active AND at least one child was activated
   * (PRD FR-16a — the second clause stops an empty scope completing on entry).
   */
  protected boolean isCompleted(ActivityExecution scopeExecution, ActivityExecution endedExecution) {
    if (completionCondition != null) {
      // Latched, and it has to be: "once the condition holds" must survive the condition going
      // false again, which it can between two child-end events while cancelRemainingInstances=
      // "false" keeps the scope open for its survivors. Re-deciding then would park the scope
      // forever on a completion that had already been determined.
      if (isConditionSatisfied(scopeExecution)) {
        return true;
      }
      if (!completionCondition.evaluate(scopeExecution)) {
        return false;
      }
      setConditionSatisfied(scopeExecution);
      return true;
    }
    return isActivated(scopeExecution) && !hasActiveChildren(scopeExecution, endedExecution);
  }

  /**
   * Refuses activation once the completion condition has held. Placed here rather than in
   * {@code ActivateAdHocSubProcessActivitiesCmd} because process instance modification reaches the same two
   * entry points without going through that command, and the rule is an invariant of the scope
   * rather than of one API.
   */
  protected void ensureFurtherActivationAllowed(ActivityExecution scopeExecution) {
    if (!isConditionSatisfied(scopeExecution) && !conditionHoldsNow(scopeExecution)) {
      return;
    }
    PvmActivity scopeActivity = scopeExecution.getActivity();
    throw new BadUserRequestException("Ad hoc sub process '"
        + (scopeActivity == null ? "" : scopeActivity.getId())
        + "' has already satisfied its completion condition, so it starts no further activities.");
  }

  /**
   * Evaluates the completion condition here and now, solely to decide whether to refuse activation.
   *
   * <p>This is what makes FR-15 hold for a condition satisfied from <em>outside</em> the scope while
   * every child is still running. The recorded flag cannot cover that case, because the condition is
   * only ever evaluated when a child ends, so a condition nothing has observed leaves no trace. The
   * two are complementary rather than redundant: the flag is a latch, so a condition that held once
   * and has since gone false again still blocks activation, which is what "once the condition holds"
   * means.
   *
   * <p>It is deliberately <em>not</em> an evaluation point for completion. Nothing here completes the
   * scope, so no deployed model changes outcome, and the entry semantics settled in CIB7-1851 are
   * untouched. It costs one evaluation per activation request — an explicit, occasional operation —
   * rather than the listener on every variable write that watching would need.
   *
   * <p>An unresolvable condition means "cannot tell", and cannot-tell must not refuse. An unguarded
   * {@code ${x}} throws before anything sets x, and refusing on that would move a modelling mistake's
   * failure forward to the activation call for no benefit — the same trade that ruled out evaluating
   * on entry, in miniature.
   */
  protected boolean conditionHoldsNow(ActivityExecution scopeExecution) {
    if (completionCondition == null) {
      return false;
    }
    // tryEvaluate, not a catch-all: it suppresses exactly the unresolved-property case and lets
    // everything else through. Swallowing every RuntimeException here would turn an optimistic
    // locking conflict, a bad result type or a failing script into "condition is false" and then
    // start the requested child, which is the opposite of safe.
    return completionCondition.tryEvaluate(scopeExecution, scopeExecution);
  }

  protected boolean isConditionSatisfied(ActivityExecution scopeExecution) {
    PvmExecutionImpl marker = findStateExecution(scopeExecution);
    return marker != null && Boolean.TRUE.equals(marker.getVariableLocal(COMPLETION_CONDITION_SATISFIED));
  }

  protected void setConditionSatisfied(ActivityExecution scopeExecution) {
    PvmExecutionImpl marker = findStateExecution(scopeExecution);
    if (marker == null) {
      marker = createStateExecution(scopeExecution);
    }
    marker.setVariableLocal(COMPLETION_CONDITION_SATISFIED, true);
  }

  protected boolean hasActiveChildren(ActivityExecution scopeExecution, ActivityExecution endedExecution) {
    // The same question disposeOfRemainingChildren asks, asked the same way. A child that is itself a
    // scope -- an embedded sub process, or any task that becomes one through a boundary event or an io
    // mapping -- leaves its concurrent execution inactive with a null activity while its work runs one
    // level below. isActive() alone reads that as idle, so the scope completed and cancelled work that
    // was still running. The event-scope marker is inactive with a null activity too, which is why the
    // walk is over the non-event-scope children: counting the marker would park every scope forever.
    for (ActivityExecution child : ((PvmExecutionImpl) scopeExecution).getNonEventScopeExecutions()) {
      if (child != endedExecution && (child.isActive() || child.getActivity() == null)) {
        return true;
      }
    }
    return false;
  }

  protected void completeScope(ActivityExecution scopeExecution, ActivityExecution endedExecution) {
    if (!disposeOfRemainingChildren(scopeExecution)) {
      return;
    }

    scopeExecution.setActivity((PvmActivity) endedExecution.getActivity().getFlowScope());
    scopeExecution.setActive(true);
    leave(scopeExecution);
  }

  /**
   * Disposes of whatever is still running once the completion condition has held, and says whether
   * the scope may leave now.
   *
   * <p>A child is still running when it is active <em>or</em> when its activity is null. The second
   * case is a child that has entered a scope of its own -- an embedded sub process, or any activity
   * carrying an io mapping -- where {@code PvmAtomicOperationCreateScope} inactivates the child and
   * moves the live work one level down.
   *
   * <p>CIB7-1961: the earlier form re-tested {@code isActive()} to decide whether to wait, after the
   * outer condition had already been satisfied by the other disjunct. For a scope child neither
   * branch then ran, the scope left with a live subtree beneath it, and deleting its execution
   * violated ACT_FK_EXE_PARENT -- failing the very operation that satisfied the condition and
   * leaving the instance unfinishable.
   *
   * @return true when nothing is left to wait for; false when {@code cancelRemainingInstances} is
   *         false and a child is still running, in which case the scope leaves when the last of
   *         them ends and this is reached again
   */
  protected boolean disposeOfRemainingChildren(ActivityExecution scopeExecution) {
    return disposeOfRemainingChildren(scopeExecution, cancelRemainingInstances,
        "Ad hoc sub process completion condition satisfied.");
  }

  /**
   * Removes the children that are done and cancels the ones still running -- or, with
   * {@code cancelRunning} false, stops at the first one still running and reports it.
   *
   * @param reason recorded as the delete reason of each cancelled activity instance
   */
  protected boolean disposeOfRemainingChildren(ActivityExecution scopeExecution, boolean cancelRunning,
      String reason) {
    List<ActivityExecution> children = new ArrayList<ActivityExecution>(
        ((PvmExecutionImpl) scopeExecution).getNonEventScopeExecutions());

    for (ActivityExecution child : children) {
      if (child.isActive() || child.getActivity() == null) {
        if (!cancelRunning) {
          return false;
        }
        cancelChild(scopeExecution, child, reason);
      } else {
        child.remove();
      }
    }
    return true;
  }

  /**
   * Cancels one child of a scope that is leaving, unless the child never started.
   *
   * <p>A batch creates every child before it starts any, and {@code createExecution()} gives each the
   * scope's own activity. If an earlier child ends the scope, the later ones are still standing on
   * it, and cancelling them fires END on the scope itself -- which the scope fires again as it leaves
   * (review finding 2). Nothing has run on such a child: its variables are set only when it starts,
   * and it has no task and no activity instance of its own. So it is removed rather than cancelled.
   */
  protected void cancelChild(ActivityExecution scopeExecution, ActivityExecution child, String reason) {
    if (child.getActivity() == scopeExecution.getActivity()) {
      child.remove();
    } else {
      ((PvmExecutionImpl) child).deleteCascade(reason);
    }
  }

  /**
   * Ends the scope because a performer asked it to, rather than because a condition became true.
   *
   * <p>BPMN 2.0.0 section 10.3.5 puts the performers in charge of when an ad hoc scope is finished,
   * so there has to be a way to say so. Without it a scope whose completion condition never becomes
   * true has no exit short of deleting the process instance.
   *
   * <p>Cancels whatever is still running, regardless of {@code cancelRemainingInstances}. That
   * attribute describes what happens when the completion <em>condition</em> is satisfied (Table
   * 10.22); applying it here would let an explicit request to finish silently not finish, which is
   * worse behaviour than either branch of the attribute was meant to produce.
   *
   * <p>Unlike {@link #completeScope}, this does not restore the scope's activity from an ended
   * child, because there is no ended child: the scope execution is the target and already carries
   * its own activity.
   */
  public void completeScopeOnRequest(ActivityExecution scopeExecution) {
    disposeOfRemainingChildren(scopeExecution, true, "Ad hoc sub process completed on request.");

    scopeExecution.setActive(true);
    leave(scopeExecution);
  }

  /**
   * A child has completed an activity and is taking a sequence flow to the next one (CIB7-1882).
   *
   * <p>This is the fourth point at which the completion condition is consulted, and it exists
   * because taking a flow is not an end: without it, a condition satisfied by the very completion
   * that takes the flow would go unnoticed and the target would be performed regardless.
   *
   * <p>What happens then is decided by {@code cancelRemainingInstances}, which is already the
   * attribute that answers "what becomes of work in progress when the condition fires", rather than
   * by a new rule. With the default {@code true} the target is not performed, which is what the
   * latch from CIB7-1850 implies -- once the condition holds the scope starts nothing further. With
   * {@code false} the scope waits for its survivors, and the target is performed, which is the
   * guarantee BPMN 2.0.0 section 10.3.5 p.182 attaches to an inner flow.
   *
   * <p>With {@code true} the path is also gathered here (CIB7-1892), because it never reaches the
   * end where a path is otherwise gathered. What it has done is finished work, not remaining work:
   * without this, a completed step was kept when no flow followed it and lost when one did. With
   * {@code false} the path goes on and is gathered at its end, once.
   *
   * <p>Without a completion condition there is nothing to consult: the no-condition rule is asked
   * when a child ends, and taking a flow is not an end.
   */
  public void childTransitioned(ActivityExecution scopeExecution, ActivityExecution transitioning) {
    if (completionCondition == null || !isCompleted(scopeExecution, transitioning)) {
      return;
    }
    if (!cancelRemainingInstances) {
      return;
    }
    // Before completing: completing deletes the transitioning execution, and with it the only
    // execution the element expression can be evaluated against.
    gatherResult(scopeExecution, transitioning);
    completeScopeOnRequest(scopeExecution);
  }

  /**
   * Consults the ad hoc scope when one of its children takes an inner sequence flow.
   *
   * <p>Attached by the parser to the outgoing transitions of the scope's children, which is the
   * seam the engine already provides: {@code TransitionImpl} accepts execution listeners, so this
   * needs no new process-virtual-machine interface.
   */
  public static class InnerTransitionListener implements ExecutionListener {

    @Override
    public void notify(DelegateExecution execution) {
      ActivityExecution transitioning = (ActivityExecution) execution;
      PvmActivity source = (PvmActivity) transitioning.getActivity();
      if (source == null) {
        return;
      }
      PvmScope flowScope = source.getFlowScope();
      if (!(flowScope instanceof PvmActivity)) {
        return;
      }
      ActivityBehavior behavior = ((PvmActivity) flowScope).getActivityBehavior();
      if (behavior instanceof AdHocSubProcessActivityBehavior) {
        ((AdHocSubProcessActivityBehavior) behavior)
            .childTransitioned(transitioning.getParent(), transitioning);
      }
    }
  }

  protected ActivityExecution createConcurrentExecution(ActivityExecution scopeExecution) {
    ActivityExecution concurrentChild = scopeExecution.createExecution();
    scopeExecution.forceUpdate();
    concurrentChild.setConcurrent(true);
    concurrentChild.setScope(false);
    return concurrentChild;
  }

  /**
   * Whether this scope instance has activated anything yet. The no-condition completion rule needs
   * that and nothing more: with nothing active, a scope that has run something is done, and one
   * that has not is still waiting for its first activation.
   */
  protected boolean isActivated(ActivityExecution scopeExecution) {
    // CIB7-1850: read from a marker execution held off the children's ancestor path, instead of
    // from the scope execution itself. Compared rather than cast, so whatever else reaches this
    // name cannot throw from inside a PVM atomic operation.
    PvmExecutionImpl marker = findStateExecution(scopeExecution);
    return marker != null && Boolean.TRUE.equals(marker.getVariableLocal(ACTIVATED));
  }

  /**
   * Written once, on the first activation. Every later activation finds it set and writes nothing,
   * so activating again costs no variable update and, with full history, no history row.
   */
  protected void markActivated(ActivityExecution scopeExecution) {
    PvmExecutionImpl marker = findStateExecution(scopeExecution);
    if (marker == null) {
      marker = createStateExecution(scopeExecution);
    }
    if (!Boolean.TRUE.equals(marker.getVariableLocal(ACTIVATED))) {
      marker.setVariableLocal(ACTIVATED, true);
    }
  }

  /**
   * CIB7-1850. The scope's state — the activation flag and the condition latch — lives on a
   * dedicated event-scope child of the ad hoc scope rather than on the scope execution.
   *
   * <p>The point is the variable resolution order. {@code setVariable} walks strictly up the parent
   * chain and writes to the first ancestor that already holds the name; the scope execution is an
   * ancestor of every ad hoc child, which is why a child could overwrite a variable kept there. A
   * marker execution is a <em>sibling</em> of those children, so it is never on their walk-up path:
   * a child writing this name creates its own variable at the process instance instead, and the
   * engine's copy is untouched.
   *
   * <p>Event scope is what keeps it out of the way of existing code — {@code
   * getNonEventScopeExecutions()} is what child iteration, completion checks and delete cascade all
   * use, so the marker is invisible to them without changing any of them.
   */
  protected PvmExecutionImpl findStateExecution(ActivityExecution scopeExecution) {
    for (PvmExecutionImpl candidate : ((PvmExecutionImpl) scopeExecution).getEventScopeExecutions()) {
      if (candidate.hasVariableLocal(ACTIVATED)
          || candidate.hasVariableLocal(COMPLETION_CONDITION_SATISFIED)) {
        return candidate;
      }
    }
    return null;
  }

  protected PvmExecutionImpl createStateExecution(ActivityExecution scopeExecution) {
    ExecutionEntity marker = ((ExecutionEntity) scopeExecution).createExecution();
    // createExecution() copies the parent's activity, and the marker must NOT keep it: on removal
    // the engine calls destroy() on every event-scope child, and destroy() runs that activity's
    // output io-mapping. Measured -- with the activity left in place, testIoMappingOnTheScopeItself
    // fails with "Cannot resolve identifier 'budget'" from removeEventScopes -> destroy. Clearing
    // it also keeps the marker out of createActivityExecutionMapping(), which is what we want.
    marker.setActivity(null);
    marker.setActive(false);
    marker.setConcurrent(false);
    marker.setEventScope(true);
    return marker;
  }

  /**
   * Whether this scope's completion is decided by a condition rather than by whether anything was
   * activated.
   * Read by the migration validator, which has to refuse a mapping that would change the rule.
   */
  public boolean hasCompletionCondition() {
    return completionCondition != null;
  }

  public void setCompletionCondition(Condition completionCondition) {
    this.completionCondition = completionCondition;
  }

  public void setOutputCollectionName(String outputCollectionName) {
    this.outputCollectionName = outputCollectionName;
  }

  public void setOutputElement(Expression outputElement) {
    this.outputElement = outputElement;
  }

  public void setCancelRemainingInstances(boolean cancelRemainingInstances) {
    this.cancelRemainingInstances = cancelRemainingInstances;
  }

}
