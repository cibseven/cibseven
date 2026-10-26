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

import java.util.Collections;
import java.util.List;

import org.cibseven.bpm.engine.ActivityTypes;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.ExecutionListener;
import org.cibseven.bpm.engine.impl.bpmn.helper.BpmnProperties;
import org.cibseven.bpm.engine.impl.core.model.PropertyKey;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.connect.ai.agent.impl.AdHocResults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records on a tool's performance which interrupting boundary event interrupted its flow: on the
 * tool itself, or on an activity further along its chain (CIB7-2166). The scope's observer takes
 * it into the entry the agent is told about, and a tool result into what the call returns.
 *
 * <p>Without it, the agent learnt only where the flow ended, {@code endedAt}; a tool whose error
 * path ran to its end within the call was even reported as finished.
 *
 * <p>Sits in three places, all set by the parse listener, and tells them apart by the activity and
 * the event it is notified of:
 * <ul>
 * <li>the start of an interrupting boundary event that is no error event: records it;</li>
 * <li>the end of an activity a boundary error event is attached to, as the error cancels it:
 * records the error with the code {@link AgenticCaughtError} caught. Here, not at the event's
 * start, because that end runs in the command that caught the error, also when the event itself
 * continues asynchronously;</li>
 * <li>the start of an activity the flow passes on its way to an activity a boundary event is
 * attached to, that activity included ({@link #REWINDS}): forgets what that activity's events
 * recorded earlier in the same performance. The flow went back to before the interruption, and
 * how it ends now is what counts.</li>
 * </ul>
 *
 * <p>Built in, so {@code skipCustomListeners} does not skip it.
 */
public class AgenticToolEndRecorder implements ExecutionListener {

  private static final Logger LOG = LoggerFactory.getLogger(AgenticToolEndRecorder.class);

  /** The kind of a boundary error event, the one whose interruption is recorded where it cancels. */
  static final String ERROR = "error";

  /**
   * The activities, by the id the agent knows them under, an interruption of which is forgotten
   * as the activity carrying this property starts: those a boundary event is attached to that the
   * flow can reach from it again along sequence flows, set by the parse listener.
   */
  public static final PropertyKey<List<String>> REWINDS =
      new PropertyKey<List<String>>("agenticRewinds");

  /** The kind of boundary event, as the agent is told it, or null for one that ends no tool. */
  public static String kindOf(ActivityImpl boundaryEvent) {
    String type = boundaryEvent.getProperties().get(BpmnProperties.TYPE);
    if (ActivityTypes.BOUNDARY_ERROR.equals(type)) {
      return ERROR;
    }
    if (ActivityTypes.BOUNDARY_TIMER.equals(type)) {
      return "timer";
    }
    if (ActivityTypes.BOUNDARY_MESSAGE.equals(type)) {
      return "message";
    }
    if (ActivityTypes.BOUNDARY_SIGNAL.equals(type)) {
      return "signal";
    }
    if (ActivityTypes.BOUNDARY_ESCALATION.equals(type)) {
      return "escalation";
    }
    if (ActivityTypes.BOUNDARY_CONDITIONAL.equals(type)) {
      return "conditional";
    }
    return null;
  }

  /**
   * Never fails what it listens to: that is the process handling a timer, an error or a message,
   * or a tool starting, and telling the agent about it must not undo it.
   */
  @Override
  public void notify(DelegateExecution execution) {
    if (!(execution instanceof ExecutionEntity)) {
      return;
    }
    ExecutionEntity entity = (ExecutionEntity) execution;
    ActivityImpl activity = entity.getActivity();
    if (activity == null) {
      return;
    }
    try {
      if (EVENTNAME_END.equals(execution.getEventName())) {
        AgenticCaughtError.Caught caught = AgenticCaughtError.takeCaughtOn(activity);
        if (caught != null) {
          AdHocResults.recordInterruption(entity, caught.handlerActivityId(),
              attachedTo(activity), ERROR, caught.errorCode(), caught.exceptionClass());
        }
        return;
      }
      // Forgotten first: a boundary event, too, may lead back to where it was recorded.
      List<String> rewinds = activity.getProperties().get(REWINDS);
      AdHocResults.forgetInterruption(entity,
          rewinds == null ? Collections.<String>emptyList() : rewinds);
      // An error event gets no start listener; it is recorded where it cancels.
      String kind = kindOf(activity);
      if (kind != null && activity.getEventScope() instanceof ActivityImpl) {
        AdHocResults.recordInterruption(entity, activity.getId(),
            attachedTo((ActivityImpl) activity.getEventScope()), kind, null, null);
      }
    } catch (RuntimeException e) {
      LOG.warn("Could not record how a boundary event on '{}' interrupted a tool; the agent is"
          + " told only where its flow ended.", activity.getId(), e);
    }
  }

  /** The activity a boundary event is attached to as the agent knows it, not its multi-instance body. */
  public static String attachedTo(ActivityImpl activity) {
    return AgenticAdHocParseListener.innerActivity(activity).getId();
  }
}
