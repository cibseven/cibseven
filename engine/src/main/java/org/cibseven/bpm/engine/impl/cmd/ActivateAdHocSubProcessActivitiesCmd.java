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
package org.cibseven.bpm.engine.impl.cmd;

import static org.cibseven.bpm.engine.impl.util.EnsureUtil.ensureNotEmpty;
import static org.cibseven.bpm.engine.impl.util.EnsureUtil.ensureNotNull;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.history.UserOperationLogEntry;
import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocSubProcessActivityBehavior;
import org.cibseven.bpm.engine.impl.cfg.CommandChecker;
import org.cibseven.bpm.engine.impl.interceptor.Command;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.PropertyChange;
import org.cibseven.bpm.engine.impl.pvm.PvmActivity;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.impl.pvm.process.ScopeImpl;
import org.cibseven.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;

/**
 * Starts one or more children of an ad hoc sub process.
 *
 * <p>Returns the activity instance ids of what it started, in the order the activities were given.
 * A caller that starts activities inside a running instance needs those ids to join an audit record
 * against, and the return type is frozen at first release, so it returns them rather than void.

 * <p>The id is captured as each activity instance is entered rather than read from the execution
 * afterwards, so it is correct for an activity that runs synchronously and has already finished by
 * the time this returns.
 *
 * <p>All-or-nothing: every request is validated before anything is created, so a batch containing
 * one bad activity id starts none of the others. That property is the reason validation and
 * instantiation are two separate passes below rather than one loop.
 */
public class ActivateAdHocSubProcessActivitiesCmd implements Command<List<String>>, Serializable {

  private static final long serialVersionUID = 1L;

  protected String executionId;
  protected Collection<String> activityIds;

  /** Keyed by activity definition: every performance of one activity shares an entry. */
  protected Map<String, Map<String, Object>> activityVariables;

  /**
   * One entry per performance, aligned with {@link #activityIds}, or null when the caller used the
   * definition-keyed form. This is what lets the same activity be performed twice in one call with
   * different arguments (CIB7-1892); the map above cannot express it, because two performances of
   * one activity collapse onto one key.
   */
  protected List<Map<String, Object>> variablesPerPerformance;

  public ActivateAdHocSubProcessActivitiesCmd(String executionId, Collection<String> activityIds,
      Map<String, Map<String, Object>> activityVariables) {
    this.executionId = executionId;
    this.activityIds = activityIds;
    this.activityVariables = activityVariables;
  }

  public ActivateAdHocSubProcessActivitiesCmd(String executionId, List<String> activityIds,
      List<Map<String, Object>> variablesPerPerformance) {
    this.executionId = executionId;
    this.activityIds = activityIds;
    this.variablesPerPerformance = variablesPerPerformance;
  }

  @Override
  public List<String> execute(CommandContext commandContext) {
    ensureNotNull(BadUserRequestException.class, "executionId is null", "executionId", executionId);
    ensureNotNull(BadUserRequestException.class, "activityIds is null", "activityIds", activityIds);
    ensureNotEmpty(BadUserRequestException.class, "activityIds is empty", "activityIds", activityIds);

    ExecutionEntity scopeExecution = commandContext.getExecutionManager().findExecutionById(executionId);
    ensureNotNull(BadUserRequestException.class,
        "execution " + executionId + " doesn't exist", "execution", scopeExecution);

    for (CommandChecker checker : commandContext.getProcessEngineConfiguration().getCommandCheckers()) {
      checker.checkUpdateProcessInstance(scopeExecution);
    }

    // CIB7-1984: under multi-instance markers the caller cannot name the scope execution itself,
    // so the execution they can find is resolved to it here.
    scopeExecution = AdHocSubProcessScopeResolver.resolve(scopeExecution, executionId);
    ActivityImpl scope = (ActivityImpl) scopeExecution.getActivity();
    List<ActivityImpl> resolved = resolveStartableActivities(scope);

    // Everything is validated and resolved before anything is created, so a batch with one bad id
    // starts nothing at all.
    List<String> requested = new ArrayList<>(activityIds);

    // Two passes, and the split matters. Starting a child can run it to completion, and a child that
    // completes while nothing else is active completes the whole scope; the next createInnerInstance
    // would then be operating on an execution that has already ended. Creating every child first
    // means the scope still has active children when the first one finishes. Parallel multi-instance
    // splits its own loop for the same reason.
    List<PvmExecutionImpl> children = new ArrayList<>();
    List<AtomicReference<String>> sinks = new ArrayList<>();
    for (int i = 0; i < requested.size(); i++) {
      PvmExecutionImpl child = createChildExecution(scopeExecution, resolved.get(i));
      AtomicReference<String> sink = new AtomicReference<>();
      child.setEnteredActivityInstanceIdSink(sink);
      children.add(child);
      sinks.add(sink);
    }

    for (int i = 0; i < requested.size(); i++) {
      PvmExecutionImpl child = children.get(i);
      // A child started earlier in this loop can run to completion and satisfy the completion
      // condition, and completeScope then deletes every remaining child -- including the ones this
      // loop has created but not yet started. Starting a deleted execution fails on flush with an
      // OptimisticLockingException, which reads as a transient concurrency problem and is not: it is
      // deterministic. The scope has already left, so there is nothing left to start.
      if (child.isEnded() || child.isRemoved()) {
        continue;
      }
      startChild(child, resolved.get(i), variablesFor(i, requested.get(i)));
    }

    List<String> activityInstanceIds = new ArrayList<>();
    for (AtomicReference<String> sink : sinks) {
      activityInstanceIds.add(sink.get());
    }

    writeUserOperationLog(commandContext, scopeExecution);
    return activityInstanceIds;
  }

  /**
   * The children to start, one per requested id and in the order requested, resolved by the
   * behaviour, the one place both ways in -- this API and entry activation -- decide what a request
   * means. A request naming anything that is not directly startable is refused as a whole.
   */
  protected List<ActivityImpl> resolveStartableActivities(ActivityImpl scope) {
    AdHocSubProcessActivityBehavior behavior = (AdHocSubProcessActivityBehavior) scope.getActivityBehavior();
    List<String> rejected = new ArrayList<>();
    List<ActivityImpl> resolved = behavior.resolveStartableChildren(scope, activityIds, rejected);

    if (!rejected.isEmpty()) {
      throw new BadUserRequestException("Cannot start " + rejected + " in ad hoc sub process '"
          + scope.getId() + "'. Its directly startable activities are "
          + behavior.startableActivityIds(scope)
          + ". An element is directly startable if it is an activity and has no incoming sequence"
          + " flow from within the scope, so a gateway or an intermediate event is reachable by flow"
          + " but never started directly.");
    }
    return resolved;
  }

  /**
   * Creates the concurrent child and starts it.
   *
   * <p>Per-activation variables are set on the child execution, not on the scope. The runtime
   * variable table is unique on scope and name, so writing them to the scope keyed by activity id
   * would make a second performance of the same child silently overwrite the first, and the
   * specification explicitly allows an activity to be performed more than once.
   */
  protected PvmExecutionImpl createChildExecution(ExecutionEntity scopeExecution, ActivityImpl child) {
    AdHocSubProcessActivityBehavior behavior =
        (AdHocSubProcessActivityBehavior) ((ScopeImpl) child.getFlowScope()).getActivityBehavior();
    return (PvmExecutionImpl) behavior.createInnerInstance(scopeExecution);
  }

  /**
   * The variables of one performance: by position when the caller named them per performance,
   * otherwise by activity id, which is all the definition-keyed form can offer.
   */
  protected Map<String, Object> variablesFor(int index, String activityId) {
    if (variablesPerPerformance != null) {
      return index < variablesPerPerformance.size() ? variablesPerPerformance.get(index) : null;
    }
    return activityVariables == null ? null : activityVariables.get(activityId);
  }

  protected void startChild(PvmExecutionImpl childExecution, ActivityImpl child,
      Map<String, Object> variablesLocal) {
    childExecution.executeActivities(Collections.<PvmActivity>emptyList(), child, null,
        null, variablesLocal, false, false);
  }

  protected void writeUserOperationLog(CommandContext commandContext, ExecutionEntity scopeExecution) {
    List<PropertyChange> propertyChanges = new ArrayList<>();
    propertyChanges.add(new PropertyChange("activityIds", null, activityIds));
    propertyChanges.add(new PropertyChange("nrOfActivities", null, activityIds.size()));

    // MODIFY_PROCESS_INSTANCE, not ACTIVATE. ACTIVATE means unsuspending a process instance, and an
    // audit consumer would otherwise read ad hoc activation as an unsuspension. Starting an activity
    // inside a running instance is what the modification commands log, and this is the same kind of
    // act.
    commandContext.getOperationLogManager().logProcessInstanceOperation(
        UserOperationLogEntry.OPERATION_TYPE_MODIFY_PROCESS_INSTANCE,
        scopeExecution.getProcessInstanceId(),
        scopeExecution.getProcessDefinitionId(),
        null,
        propertyChanges);
  }

}
