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
import org.cibseven.bpm.engine.delegate.ExecutionListener;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.impl.pvm.PvmScope;
import org.cibseven.bpm.engine.impl.pvm.delegate.ActivityBehavior;
import org.cibseven.bpm.engine.impl.pvm.delegate.CompositeActivityBehavior;
import org.cibseven.bpm.engine.impl.pvm.delegate.ModificationObserverBehavior;
import org.cibseven.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;
import org.cibseven.bpm.engine.impl.Condition;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.delegate.Expression;
import org.cibseven.bpm.engine.impl.pvm.PvmActivity;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.impl.pvm.process.ScopeImpl;
import org.cibseven.bpm.engine.impl.bpmn.helper.CompensationUtil;
import org.cibseven.bpm.engine.impl.bpmn.helper.BpmnProperties;
import java.util.Collection;
import java.util.Collections;
import org.cibseven.bpm.engine.impl.pvm.runtime.Callback;

/**
 * Runtime behavior of a BPMN adHocSubProcess.
 *
 * <p>The scope is entered without children: nothing inside it is reached by an incoming flow, and
 * every child is created by the activation API or by the entry list from CIB7-1891.
 *
 * <p>Notes on the parts that are not obvious from the code:
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
  protected boolean cancelRemainingInstances = true;

  @Override
  public void execute(ActivityExecution execution) throws Exception {
    // Entering an ad-hoc scope starts nothing, and deliberately records nothing: writing a zero
    // counter here put a variable — and, with the counter relocated, an execution — into every ad
    // hoc instance from birth, including scopes that never activate anything. The count is created
    // on first activation instead, so a never-activated scope carries neither.

    // Do NOT null the activity here, even though parallel multi-instance does.
    // An execution with activity == null does not appear in createActivityExecutionMapping(),
    // so an empty ad-hoc scope becomes untargetable: AbstractInstantiationCmd:152 walks looking
    // for a scope with executions, finds none, and throws a raw NoSuchElementException from
    // flowScopeExecutions.iterator().next(). Multi-instance survives this only because it always
    // has concurrent children to resolve through; an empty ad-hoc scope has none.
    execution.inactivate();
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
   * Reached when the ending child is not a plain concurrent execution, which in practice means a
   * child that is itself a scope, such as an embedded sub-process. A non-scope child routes to
   * {@link #concurrentChildExecutionEnded} instead.
   */
  /**
   * CIB7-1967. Hands the children's compensation subscriptions to the parent scope on the way out.
   *
   * <p>The parser marks an ad hoc scope as consuming compensation, and a child may carry a
   * compensation boundary event whose handler sits inside the scope. Those subscriptions live on the
   * scope execution while it runs, and they die with it unless they are copied onto an event scope
   * execution under the parent first. {@code SubProcessActivityBehavior} does exactly this, and an
   * ad hoc scope has the same obligation for the same reason: without it, throwing compensation at
   * the scope afterwards finds nothing and silently does nothing.
   */
  @Override
  public void doLeave(ActivityExecution execution) {
    CompensationUtil.createEventScopeExecution((ExecutionEntity) execution);
    super.doLeave(execution);
  }

  @Override
  public void complete(ActivityExecution scopeExecution) {
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
    return getActivatedCount(scopeExecution) > 0 && !hasActiveChildren(scopeExecution, endedExecution);
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
    for (ActivityExecution child : scopeExecution.getExecutions()) {
      if (child != endedExecution && child.isActive()) {
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
    List<ActivityExecution> children = new ArrayList<ActivityExecution>(
        ((PvmExecutionImpl) scopeExecution).getNonEventScopeExecutions());

    for (ActivityExecution child : children) {
      if (child.isActive() || child.getActivity() == null) {
        if (!cancelRemainingInstances) {
          return false;
        }
        ((PvmExecutionImpl) child).deleteCascade("Ad hoc sub process completion condition satisfied.");
      } else {
        child.remove();
      }
    }
    return true;
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
   * <p>Without a completion condition there is nothing to consult: the no-condition rule ends the
   * scope when nothing is active, and a child in mid-transition is active.
   */
  public void childTransitioned(ActivityExecution scopeExecution, ActivityExecution transitioning) {
    if (completionCondition == null || !isCompleted(scopeExecution, transitioning)) {
      return;
    }
    if (!cancelRemainingInstances) {
      return;
    }
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

  protected int getActivatedCount(ActivityExecution scopeExecution) {
    // CIB7-1850: read the count from a marker execution held off the children's
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
   * CIB7-1850. The completion counter lives on a dedicated event-scope child of the ad hoc
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

  public void setCompletionCondition(Condition completionCondition) {
    this.completionCondition = completionCondition;
  }

  public void setCancelRemainingInstances(boolean cancelRemainingInstances) {
    this.cancelRemainingInstances = cancelRemainingInstances;
  }

}
