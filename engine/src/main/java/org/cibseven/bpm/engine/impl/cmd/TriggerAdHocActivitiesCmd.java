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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.history.UserOperationLogEntry;
import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocSubProcessActivityBehavior;
import org.cibseven.bpm.engine.impl.bpmn.helper.BpmnProperties;
import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParse;
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
public class TriggerAdHocActivitiesCmd implements Command<List<String>>, Serializable {

  private static final long serialVersionUID = 1L;

  protected String executionId;
  protected Collection<String> activityIds;
  protected Map<String, Map<String, Object>> activityVariables;

  public TriggerAdHocActivitiesCmd(String executionId, Collection<String> activityIds,
      Map<String, Map<String, Object>> activityVariables) {
    this.executionId = executionId;
    this.activityIds = activityIds;
    this.activityVariables = activityVariables;
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

    ActivityImpl scope = resolveAdHocScope(scopeExecution);
    Map<String, ActivityImpl> resolved = resolveStartableActivities(scope);

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
      PvmExecutionImpl child = createChildExecution(scopeExecution, resolved.get(requested.get(i)));
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
      startChild(child, resolved.get(requested.get(i)), requested.get(i));
    }

    List<String> activityInstanceIds = new ArrayList<>();
    for (AtomicReference<String> sink : sinks) {
      activityInstanceIds.add(sink.get());
    }

    writeUserOperationLog(commandContext, scopeExecution);
    return activityInstanceIds;
  }

  /**
   * The execution must be the ad hoc scope's own execution, identified by its behaviour rather than
   * by element name, so the check cannot drift from what the runtime actually does.
   */
  protected ActivityImpl resolveAdHocScope(ExecutionEntity scopeExecution) {
    PvmActivity activity = scopeExecution.getActivity();
    if (activity == null || !(activity.getActivityBehavior() instanceof AdHocSubProcessActivityBehavior)) {
      throw new BadUserRequestException("Execution '" + executionId
          + "' is not an ad hoc sub process instance, so no ad hoc activity can be started in it."
          + " Pass the execution of the ad hoc sub process scope itself.");
    }
    return (ActivityImpl) activity;
  }

  /**
   * Resolves every requested id against the startable set computed at parse time, and reports all
   * the bad ones at once rather than only the first, so a caller fixing a batch does not have to
   * discover its mistakes one call at a time.
   */
  protected Map<String, ActivityImpl> resolveStartableActivities(ActivityImpl scope) {
    List<String> startable = scope.getProperties().get(BpmnProperties.AD_HOC_STARTABLE_ACTIVITIES);
    if (startable == null) {
      startable = Collections.emptyList();
    }

    Map<String, ActivityImpl> resolved = new LinkedHashMap<>();
    List<String> rejected = new ArrayList<>();
    for (String activityId : activityIds) {
      if (activityId != null && startable.contains(activityId)) {
        resolved.put(activityId, findChild(scope, activityId));
      } else if (!resolved.containsKey(activityId)) {
        rejected.add(String.valueOf(activityId));
      }
    }

    if (!rejected.isEmpty()) {
      throw new BadUserRequestException("Cannot start " + rejected + " in ad hoc sub process '"
          + scope.getId() + "'. Its directly startable activities are " + startable
          + ". An element is directly startable if it is an activity and has no incoming sequence"
          + " flow from within the scope, so a gateway or an intermediate event is reachable by flow"
          + " but never started directly.");
    }
    return resolved;
  }

  /**
   * Finds the child to start, which is not always the activity that carries the requested id.
   *
   * <p>An activity with loop characteristics is wrapped at parse time: the direct child of the scope
   * is a generated multi-instance body and the requested activity is nested inside it. Starting the
   * nested activity directly would bypass the body that owns the loop, so the body is what gets
   * started.
   */
  protected ActivityImpl findChild(ActivityImpl scope, String activityId) {
    for (ActivityImpl child : scope.getActivities()) {
      if (activityId.equals(child.getId())) {
        return child;
      }
    }
    String multiInstanceBodyId = activityId + BpmnParse.MULTI_INSTANCE_BODY_ID_SUFFIX;
    for (ActivityImpl child : scope.getActivities()) {
      if (multiInstanceBodyId.equals(child.getId())) {
        return child;
      }
    }
    // The startable set is derived from the same element at parse time, so this cannot happen
    // unless the two fall out of step.
    throw new BadUserRequestException("Ad hoc sub process '" + scope.getId()
        + "' reports '" + activityId + "' as startable but has no such child activity.");
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

  protected void startChild(PvmExecutionImpl childExecution, ActivityImpl child, String activityId) {
    Map<String, Object> variablesLocal = activityVariables == null ? null : activityVariables.get(activityId);

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
