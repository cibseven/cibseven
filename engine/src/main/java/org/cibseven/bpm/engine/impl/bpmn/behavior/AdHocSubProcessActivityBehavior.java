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

import java.util.ArrayList;
import java.util.List;

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.PvmActivity;
import org.cibseven.bpm.engine.impl.pvm.delegate.ActivityExecution;
import org.cibseven.bpm.engine.impl.pvm.delegate.CompositeActivityBehavior;
import org.cibseven.bpm.engine.impl.pvm.delegate.ModificationObserverBehavior;
import org.cibseven.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;
import org.cibseven.bpm.engine.impl.Condition;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.delegate.Expression;
import org.cibseven.bpm.engine.impl.pvm.PvmActivity;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.impl.pvm.process.ScopeImpl;
import org.cibseven.bpm.engine.impl.bpmn.helper.BpmnProperties;
import java.util.Collection;
import java.util.Collections;
import org.cibseven.bpm.engine.impl.pvm.runtime.Callback;

/**
 * Runtime behavior of a BPMN adHocSubProcess.
 *
 * <p>SPIKE IMPLEMENTATION — this class exists to answer the open runtime questions recorded in the
 * architecture spine (T-SP-01 … T-SP-04). It is deliberately minimal and is NOT production code.
 *
 * <p>Design notes established empirically while writing it:
 *
 * <ul>
 * <li><b>Entry creates no children.</b> {@link #execute(ActivityExecution)} leaves the scope waiting.
 *     This is where "the scope starts empty" belongs.</li>
 * <li><b>{@link #initializeScope} must honour {@code numberOfInstances}.</b> Its caller,
 *     {@code PvmAtomicOperationActivityInitStackNotifyListenerStart}, passes 1 and immediately does
 *     {@code get(0)}. That path is process-instance modification instantiating a dormant scope, not
 *     normal entry, so returning an empty list there would throw.</li>
 * <li><b>Both completion callbacks are reachable</b>, but not for the reason first recorded here.
 *     {@code tryPruneLastConcurrentChild} is not involved: it runs only where the flow scope is the
 *     process definition, and an ad hoc child's flow scope is the ad hoc scope. What decides is
 *     {@code PvmAtomicOperationActivityEnd}, which routes a plain concurrent child to
 *     {@link #concurrentChildExecutionEnded} whatever the child count, and a child that is itself a
 *     scope to {@link #complete}. Unlike parallel multi-instance, {@code complete} here is NOT
 *     "can't happen".</li>
 * </ul>
 */
public class AdHocSubProcessActivityBehavior extends AbstractBpmnActivityBehavior
    implements CompositeActivityBehavior, ModificationObserverBehavior {

  /** Counts children activated during this scope instance; drives the no-condition completion rule. */
  public static final String NUMBER_OF_ACTIVATED_INSTANCES = "nrOfActivatedInstances";

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

  /**
   * When set, {@link #isCompleted} never holds: neither a completion condition nor the "nothing
   * active" rule ends the scope there.
   *
   * <p>Exists because a scope driven turn by turn has to survive the moment between two turns, in
   * which nothing is active. Without it the only way to express that is a completion condition
   * written so that it can never hold, which is untrue in the model — Cockpit then shows a
   * completion condition that will never fire — and which the modeller has to remember to write.
   *
   * <p>It does <em>not</em> mean the scope can only ever be ended from outside. A driven scope
   * still ends by itself once the driver has ended without starting anything, through
   * {@link #completeOnIdleDrivenScope} — the same "nothing active" rule, evaluated after the
   * driver has been offered its turn instead of before. Suppressing it here and applying it there
   * is the difference between a scope that keeps its turns and one that parks for good.
   *
   * <p>Carried as an extension property rather than a new namespace, per CIB7-1890, so it is
   * read at parse time and never becomes a process variable that a child of the scope could
   * rewrite.
   */
  protected boolean explicitCompletionOnly;

  /**
   * Id of the child activity to re-activate whenever another child of this scope ends, from
   * {@code camunda:property adHocDriverActivity}. Null means the historical behaviour: nothing is
   * re-activated and every activation arrives from outside.
   *
   * <p>This is what lets one child drive the scope turn by turn. It carries no knowledge of what
   * that child does — it is an ordinary activity that happens to decide what to start next, so a
   * human client, a script or an agent are all equally valid drivers and the element still
   * deploys and runs with no AI artefact on the classpath.
   *
   * <p>Only meaningful together with {@link #explicitCompletionOnly}: without it the scope ends
   * as soon as the driver's first turn does, because nothing is active afterwards, so there would
   * never be a second turn. The parser refuses that combination.
   */
  protected String driverActivityId;


  @Override
  public void execute(ActivityExecution execution) throws Exception {
    // Entering an ad-hoc scope starts nothing, and deliberately records nothing: writing a zero
    // counter here put a variable — and, with the counter relocated, an execution — into every ad
    // hoc instance from birth, including scopes that never activate anything. The count is created
    // on first activation instead, so a never-activated scope carries neither.

    // SPIKE FINDING: do NOT null the activity here, even though parallel multi-instance does.
    // An execution with activity == null does not appear in createActivityExecutionMapping(),
    // so an empty ad-hoc scope becomes untargetable: AbstractInstantiationCmd:152 walks looking
    // for a scope with executions, finds none, and throws a raw NoSuchElementException from
    // flowScopeExecutions.iterator().next(). Multi-instance survives this only because it always
    // has concurrent children to resolve through; an empty ad-hoc scope has none.
    execution.inactivate();

    startEntryActivities(execution);
  }

  /**
   * Starts the activities named by {@code camunda:property activeActivityIds}, the declarative entry
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

    List<String> requested = resolveEntryActivityIds(scopeExecution);
    if (requested.isEmpty()) {
      return;
    }

    ScopeImpl scope = (ScopeImpl) scopeExecution.getActivity();
    List<String> startable = scope.getProperties().get(BpmnProperties.AD_HOC_STARTABLE_ACTIVITIES);
    List<String> unknown = new ArrayList<String>();
    for (String id : requested) {
      if (startable == null || !startable.contains(id)) {
        unknown.add(id);
      }
    }
    if (!unknown.isEmpty()) {
      throw new ProcessEngineException("Ad hoc sub process '" + scope.getId()
          + "': activeActivityIds names " + unknown + ", which " + (unknown.size() == 1 ? "is" : "are")
          + " not directly startable here. The startable activities are " + startable + ".");
    }

    // Two passes, for the same reason TriggerAdHocActivitiesCmd splits its loop: starting a child can
    // run it to completion, and a child completing while nothing else is active completes the whole
    // scope, so the next create would operate on an execution that has already ended.
    List<PvmExecutionImpl> children = new ArrayList<PvmExecutionImpl>();
    for (int i = 0; i < requested.size(); i++) {
      children.add((PvmExecutionImpl) createInnerInstance(scopeExecution));
    }
    for (int i = 0; i < requested.size(); i++) {
      PvmExecutionImpl childExecution = children.get(i);
      // A child started earlier in this loop can satisfy the completion condition, and completeScope
      // then deletes the ones not yet started. Starting a deleted execution fails on flush.
      if (childExecution.isEnded() || childExecution.isRemoved()) {
        continue;
      }
      childExecution.executeActivities(Collections.<PvmActivity>emptyList(),
          findEntryChild(scope, requested.get(i)), null, null, null, false, false);
    }
  }

  /**
   * The activity to execute, which is not always the one carrying the requested id.
   *
   * <p>Loop characteristics make the parser wrap the activity: the direct child of the scope is a
   * generated multi-instance body with the requested activity nested inside it. A recursive lookup
   * would find the nested one and executing that bypasses the body that owns the loop, so only direct
   * children are considered and the generated body is resolved by name — the same resolution
   * {@code TriggerAdHocActivitiesCmd} makes for the activation API.
   */
  protected ActivityImpl findEntryChild(ScopeImpl scope, String activityId) {
    for (ActivityImpl child : scope.getActivities()) {
      if (activityId.equals(child.getId())) {
        return child;
      }
    }
    String bodyId = activityId + "#multiInstanceBody";
    for (ActivityImpl child : scope.getActivities()) {
      if (bodyId.equals(child.getId())) {
        return child;
      }
    }
    throw new ProcessEngineException("Ad hoc sub process '" + scope.getId() + "': activeActivityIds"
        + " names '" + activityId + "', which is startable but has no such child activity.");
  }

  /**
   * Evaluates the entry expression to a list of activity ids.
   *
   * <p>A collection is taken as-is. A plain string is split on commas, which is what makes the static
   * authoring form work: {@code <camunda:property name="activeActivityIds" value="taskA,taskB"/>} is
   * a literal to the expression manager, so one runtime path serves both a literal list and a real
   * expression over process data.
   */
  protected List<String> resolveEntryActivityIds(ActivityExecution scopeExecution) {
    Object value = entryActivityIds.getValue(scopeExecution);
    List<String> ids = new ArrayList<String>();
    if (value == null) {
      return ids;
    }
    if (value instanceof Collection) {
      for (Object item : (Collection<?>) value) {
        if (item != null && !String.valueOf(item).trim().isEmpty()) {
          ids.add(String.valueOf(item).trim());
        }
      }
      return ids;
    }
    for (String part : String.valueOf(value).split(",")) {
      if (!part.trim().isEmpty()) {
        ids.add(part.trim());
      }
    }
    return ids;
  }

  public void setEntryActivityIds(Expression entryActivityIds) {
    this.entryActivityIds = entryActivityIds;
  }

  @Override
  public List<ActivityExecution> initializeScope(ActivityExecution scopeExecution, int numberOfInstances) {
    // Called on the instantiation-stack path (process instance modification), never on normal entry.
    // The caller dereferences get(0), so numberOfInstances must be honoured.
    ensureFurtherActivationAllowed(scopeExecution);
    setActivatedCount(scopeExecution, getActivatedCount(scopeExecution) + numberOfInstances);

    List<ActivityExecution> executions = new ArrayList<ActivityExecution>();
    for (int i = 0; i < numberOfInstances; i++) {
      executions.add(createConcurrentExecution(scopeExecution));
    }
    return executions;
  }

  @Override
  public ActivityExecution createInnerInstance(ActivityExecution scopeExecution) {
    ensureFurtherActivationAllowed(scopeExecution);
    setActivatedCount(scopeExecution, getActivatedCount(scopeExecution) + 1);
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
    // Decide before disposing of anything: a completion condition normally reads a variable the
    // ended child just wrote, and completeScope needs the ended execution's activity to find the
    // flow scope to return to.
    if (isCompleted(scopeExecution, endedExecution)) {
      completeScope(scopeExecution, endedExecution);
      return;
    }

    if (completeOnDriverEnd(scopeExecution, endedExecution)) {
      return;
    }

    // The driver is offered its turn before the scope is judged idle, and that order is the whole
    // point: judging first is what made explicitCompletionOnly necessary, because a worker ending
    // left nothing active and completed the scope before the driver could react to it.
    if (!reactivateDriver(scopeExecution, endedExecution)
        && completeOnIdleDrivenScope(scopeExecution, endedExecution)) {
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
   * Ends the scope when the driver asked for it during its turn, and returns whether it did.
   *
   * <p>A driver cannot end its own scope while it runs: completing the scope deletes the driver's
   * execution, so the rest of the turn fails and the engine cannot finish its bookkeeping for the
   * activity. The driver therefore records the request through
   * {@link AdHocAgentState#requestCompletion}, and it is honoured here — the one moment at which
   * the driver has ended and nothing of it is left running.
   *
   * <p>Refused while another child is still alive. The driver checks that before asking, but a turn
   * can start something after asking, and cancelling a task a person is working on is exactly what
   * the check in {@code completeScope} exists to prevent. In that case the request stays recorded
   * and takes effect when that child ends.
   */
  protected boolean completeOnDriverEnd(ActivityExecution scopeExecution, ActivityExecution endedExecution) {
    if (!AdHocAgentState.isCompletionRequested(scopeExecution)) {
      return false;
    }
    if (!hasNoOtherLiveChild(scopeExecution, endedExecution)) {
      return false;
    }
    completeScopeOnRequest(scopeExecution);
    return true;
  }

  /**
   * Ends a driven scope that has nothing left to run and no turn left to give, and returns whether
   * it did.
   *
   * <p>Reached when the driver has ended without starting anything: nothing is under the scope, and
   * a driver is not brought back by its own end, so nothing can ever reach the scope again. The
   * state is terminal either way and the only question is whether the engine says so. It used to
   * leave the instance standing — with no task, no job and no incident, invisible until someone
   * went looking for it.
   *
   * <p>This is the ordinary BPMN rule for an ad hoc scope with nothing active, evaluated at the one
   * point where a driven scope can be judged. {@link #isCompleted} cannot serve here: it runs
   * before the driver is offered its turn, which is exactly why {@link #explicitCompletionOnly} had
   * to switch it off.
   *
   * <p>Restricted to scopes with a driver. Without one, a scope with nothing active is waiting for
   * activation from outside, which is a state a client can still act on and therefore not terminal.
   */
  protected boolean completeOnIdleDrivenScope(ActivityExecution scopeExecution,
                                              ActivityExecution endedExecution) {
    if (driverActivityId == null) {
      return false;
    }
    if (!hasNoOtherLiveChild(scopeExecution, endedExecution)) {
      return false;
    }
    completeScopeOnRequest(scopeExecution);
    return true;
  }

  /**
   * Whether nothing but the ended child lives under the scope.
   *
   * <p>Reads the execution tree rather than asking {@code isActive()}, and that distinction decides
   * whether work is lost: a child whose {@code asyncBefore} job is still queued already has an
   * execution carrying its activity but is not active yet. Judged by activity it looks like an
   * empty scope, and completing then cancels work that was requested and never ran.
   *
   * <p>{@code getNonEventScopeExecutions()} keeps the scope's state execution out of it, which
   * carries no activity and never runs.
   */
  protected boolean hasNoOtherLiveChild(ActivityExecution scopeExecution,
                                        ActivityExecution endedExecution) {
    for (PvmExecutionImpl child : ((PvmExecutionImpl) scopeExecution).getNonEventScopeExecutions()) {
      if (child != endedExecution && child.getActivity() != null) {
        return false;
      }
    }
    return true;
  }

  /**
   * Re-activates the driver activity after another child ended, which is what gives the scope its
   * next turn.
   *
   * <p>Returns whether a turn was started, which is what lets the caller tell a scope that is
   * between turns from one that has no turn left to give and is therefore finished.
   *
   * <p>Starts nothing in four cases.
   *
   * <p>When no driver is configured, which is every model written before this existed.
   *
   * <p>When the ended child <em>is</em> the driver. A driver that re-activated itself on its own
   * end would run without bound, and there would be no way to stop it from the model.
   *
   * <p>When a driver instance is already present. Two children ending at nearly the same moment
   * must produce one further turn rather than two, and this is where that coalescing comes from.
   * It is decided against the execution tree — the engine's own transactional bookkeeping —
   * rather than against a list this class would have to maintain and protect from the children.
   *
   * <p>And when the scope no longer accepts activation. {@code createInnerInstance} enforces that
   * anyway by throwing, but a child ending is not the place for an exception that is not an
   * error, so it is checked first.
   *
   * <p>Started the same way {@link #startEntryActivities} starts a child, so a driver marked
   * {@code camunda:asyncBefore} becomes a job and its work runs in its own transaction rather
   * than in the one that completed the previous child. For a driver that calls an external
   * service that is not an optimisation but a requirement: without it a person completing a user
   * task waits for that call, and a timeout rolls their completion back.
   */
  protected boolean reactivateDriver(ActivityExecution scopeExecution, ActivityExecution endedExecution) {
    if (driverActivityId == null) {
      return false;
    }
    PvmActivity endedActivity = endedExecution.getActivity();
    if (endedActivity != null && driverActivityId.equals(endedActivity.getId())) {
      return false;
    }
    if (isDriverPresent(scopeExecution, endedExecution)) {
      return false;
    }
    if (isConditionSatisfied(scopeExecution) || conditionHoldsNow(scopeExecution)) {
      return false;
    }

    ScopeImpl scope = (ScopeImpl) scopeExecution.getActivity();
    List<String> startable = scope.getProperties().get(BpmnProperties.AD_HOC_STARTABLE_ACTIVITIES);
    if (startable == null || !startable.contains(driverActivityId)) {
      // Validated at deployment, so reaching this means the model and the parsed form disagree.
      throw new ProcessEngineException("Ad hoc sub process '" + scope.getId()
          + "': adHocDriverActivity names '" + driverActivityId
          + "', which is not directly startable here. The startable activities are "
          + startable + ".");
    }

    PvmExecutionImpl driver = (PvmExecutionImpl) createInnerInstance(scopeExecution);
    driver.executeActivities(Collections.<PvmActivity>emptyList(),
            findEntryChild(scope, driverActivityId), null, null, null, false, false);
    return true;
  }

  /**
   * Whether an instance of the driver activity is already under the scope.
   *
   * <p>Counts an instance whose work has not started yet: with {@code asyncBefore} the execution
   * exists and already carries the driver activity while its job waits to be picked up, and that
   * is exactly the state in which a second one must not be created.
   *
   * <p>The execution that just ended is excluded, because it is removed immediately after the
   * caller returns and would otherwise block its own successor when the driver ends.
   *
   * <p>Uses {@code getNonEventScopeExecutions()} rather than {@code getExecutions()} so the
   * scope's state execution — which carries no activity and never runs — is not considered.
   */
  protected boolean isDriverPresent(ActivityExecution scopeExecution, ActivityExecution endedExecution) {
    for (PvmExecutionImpl child : ((PvmExecutionImpl) scopeExecution).getNonEventScopeExecutions()) {
      if (child == endedExecution) {
        continue;
      }
      PvmActivity activity = child.getActivity();
      if (activity != null && driverActivityId.equals(activity.getId())) {
        return true;
      }
    }
    return false;
  }


  /**
   * Reached when the ending child is not a plain concurrent execution, which in practice means a
   * child that is itself a scope, such as an embedded sub-process. A non-scope child routes to
   * {@link #concurrentChildExecutionEnded} instead.
   */
  @Override
  public void complete(ActivityExecution scopeExecution) {
    if (isCompleted(scopeExecution, scopeExecution)) {
      leave(scopeExecution);
    } else {
      // Nothing active, condition not met: keep waiting for further activation.
      //
      // Deliberately does not null the activity, although parallel multi-instance does. An
      // execution with a null activity does not appear in createActivityExecutionMapping(), so the
      // scope becomes untargetable: AbstractInstantiationCmd walks looking for a scope execution,
      // finds none, and the next activation fails with a raw NoSuchElementException. Multi-instance
      // survives that because it always has concurrent children to resolve through. An ad hoc
      // scope must stay addressable in exactly the state where it has no children at all, which is
      // the state this branch produces.
      scopeExecution.inactivate();
    }
  }

  /**
   * With a completion condition: the condition decides.
   * Without one: complete when nothing is active AND at least one child was activated
   * (PRD FR-16a — the second clause stops an empty scope completing on entry).
   *
   * <p>Evaluated before the driver is offered its turn, so a driven scope must not be decided here
   * — a worker ending leaves nothing active, and completing then would end the scope before the
   * driver could react. That is what {@link #explicitCompletionOnly} switches off, and
   * {@link #completeOnIdleDrivenScope} is where the same rule takes effect for such a scope.
   */
  protected boolean isCompleted(ActivityExecution scopeExecution, ActivityExecution endedExecution) {
    if (explicitCompletionOnly) {
      return false;
    }
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
    return getActivatedCount(scopeExecution) > 0 && !hasActiveChildren(scopeExecution, endedExecution);
  }

  /**
   * Refuses activation once the completion condition has held. Placed here rather than in
   * {@code TriggerAdHocActivitiesCmd} because process instance modification reaches the same two
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
    for (ActivityExecution child : scopeExecution.getExecutions()) {
      if (child != endedExecution && child.isActive()) {
        return true;
      }
    }
    return false;
  }

  protected void completeScope(ActivityExecution scopeExecution, ActivityExecution endedExecution) {
    List<ActivityExecution> children = new ArrayList<ActivityExecution>(
        ((PvmExecutionImpl) scopeExecution).getNonEventScopeExecutions());

    for (ActivityExecution child : children) {
      if (child.isActive() || child.getActivity() == null) {
        if (cancelRemainingInstances) {
          ((PvmExecutionImpl) child).deleteCascade("Ad hoc sub process completion condition satisfied.");
        }
        // cancelRemainingInstances == false: leave active children running; the scope
        // leaves once the last of them ends and this method is reached again.
        else if (child.isActive()) {
          return;
        }
      } else {
        child.remove();
      }
    }

    scopeExecution.setActivity((PvmActivity) endedExecution.getActivity().getFlowScope());
    scopeExecution.setActive(true);
    leave(scopeExecution);
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
    List<ActivityExecution> children = new ArrayList<ActivityExecution>(
        ((PvmExecutionImpl) scopeExecution).getNonEventScopeExecutions());

    for (ActivityExecution child : children) {
      if (child.isActive() || child.getActivity() == null) {
        ((PvmExecutionImpl) child).deleteCascade("Ad hoc sub process completed on request.");
      } else {
        child.remove();
      }
    }

    scopeExecution.setActive(true);
    leave(scopeExecution);
  }

  protected ActivityExecution createConcurrentExecution(ActivityExecution scopeExecution) {
    ActivityExecution concurrentChild = scopeExecution.createExecution();
    scopeExecution.forceUpdate();
    concurrentChild.setConcurrent(true);
    concurrentChild.setScope(false);
    return concurrentChild;
  }

  protected int getActivatedCount(ActivityExecution scopeExecution) {
    // SPIKE (CIB7-1850): read the count from a marker execution held off the children's
    // ancestor path, instead of from the scope execution itself.
    PvmExecutionImpl marker = findStateExecution(scopeExecution);
    // No marker can mean two things: nothing has been activated yet, or the instance was started by
    // a build that still kept the counter on the scope execution itself. Reading the old location as
    // a fallback keeps such an instance completing instead of parking forever. It is safe against the
    // tamper this relocation fixed, because a child's setVariable only reaches the scope's locals if
    // the name is already there -- which, with the lazy write, it never is for a new instance.
    Object value = (marker == null)
        ? scopeExecution.getVariableLocal(NUMBER_OF_ACTIVATED_INSTANCES)
        : marker.getVariableLocal(NUMBER_OF_ACTIVATED_INSTANCES);
    // A child of the scope writing this name via a JUEL expression produces a Long, not an
    // Integer, so a plain cast throws ClassCastException from inside a PVM atomic operation.
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    return 0;
  }

  protected void setActivatedCount(ActivityExecution scopeExecution, int count) {
    PvmExecutionImpl marker = findStateExecution(scopeExecution);
    if (marker == null) {
      marker = createStateExecution(scopeExecution);
    }
    marker.setVariableLocal(NUMBER_OF_ACTIVATED_INSTANCES, count);
  }

  /**
   * SPIKE (CIB7-1850). The completion counter lives on a dedicated event-scope child of the ad hoc
   * scope rather than on the scope execution.
   *
   * <p>The point is the variable resolution order. {@code setVariable} walks strictly up the parent
   * chain and writes to the first ancestor that already holds the name; the scope execution is an
   * ancestor of every ad hoc child, which is exactly why a child can overwrite the counter today. A
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
      if (candidate.hasVariableLocal(NUMBER_OF_ACTIVATED_INSTANCES)
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
   * Whether this scope's completion is decided by a condition rather than by the activation count.
   * Read by the migration validator, which has to refuse a mapping that would change the rule.
   */
  public boolean hasCompletionCondition() {
    return completionCondition != null;
  }

  /**
   * Whether this scope's completion is decided by a completion request alone. Read by the
   * migration validator, which has to refuse a mapping that would change the rule.
   */
  public boolean isExplicitCompletionOnly() {
    return explicitCompletionOnly;
  }
  public void setExplicitCompletionOnly(boolean explicitCompletionOnly) {
    this.explicitCompletionOnly = explicitCompletionOnly;
  }

  /**
   * The child activity re-activated whenever another child ends, or {@code null}. Read by the
   * migration validator, which has to refuse a mapping that would change it: a parked instance
   * whose driver changed would afterwards be driven by a different activity or by none.
   */
  public String getDriverActivityId() {
    return driverActivityId;
  }

  public void setDriverActivityId(String driverActivityId) {
    this.driverActivityId = driverActivityId;
  }


  public void setCompletionCondition(Condition completionCondition) {
    this.completionCondition = completionCondition;
  }

  public void setCancelRemainingInstances(boolean cancelRemainingInstances) {
    this.cancelRemainingInstances = cancelRemainingInstances;
  }

}
