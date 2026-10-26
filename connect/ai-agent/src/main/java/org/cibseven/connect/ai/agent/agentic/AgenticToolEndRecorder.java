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

import org.cibseven.bpm.engine.ActivityTypes;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.ExecutionListener;
import org.cibseven.bpm.engine.impl.bpmn.helper.BpmnProperties;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.connect.ai.agent.impl.AdHocResults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * At the start of an interrupting boundary event on a tool, or on an activity further along a
 * tool's chain: records on the tool's performance that this event ended it, of which kind, and for
 * an error, which code (CIB7-2166). The scope's observer takes it into the entry the agent is told
 * about, and a tool result into what the call returns.
 *
 * <p>Without it, the agent learnt only where the flow ended, {@code endedAt}; a tool whose error
 * path ran to its end within the call was even reported as finished.
 *
 * <p>Built in, so {@code skipCustomListeners} does not skip it.
 */
public class AgenticToolEndRecorder implements ExecutionListener {

  private static final Logger LOG = LoggerFactory.getLogger(AgenticToolEndRecorder.class);

  /** The kind of boundary event, as the agent is told it, or null for one that ends no tool. */
  public static String kindOf(ActivityImpl boundaryEvent) {
    String type = boundaryEvent.getProperties().get(BpmnProperties.TYPE);
    if (ActivityTypes.BOUNDARY_ERROR.equals(type)) {
      return "error";
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
   * Never fails the event it listens to: that event is the process handling a timer, an error or
   * a message, and telling the agent about it must not undo it.
   */
  @Override
  public void notify(DelegateExecution execution) {
    if (!(execution instanceof ExecutionEntity)) {
      return;
    }
    ExecutionEntity entity = (ExecutionEntity) execution;
    ActivityImpl boundaryEvent = entity.getActivity();
    // Taken whatever happens next, so no code is left on the thread.
    String errorCode = (boundaryEvent == null)
        ? null : AgenticCaughtError.takeCaughtCode(boundaryEvent.getId());
    String kind = (boundaryEvent == null) ? null : kindOf(boundaryEvent);
    if (kind == null) {
      return;
    }
    // The activity the event is attached to, not its multi-instance body.
    String attachedTo = (boundaryEvent.getEventScope() instanceof ActivityImpl)
        ? AgenticAdHocParseListener.innerActivity((ActivityImpl) boundaryEvent.getEventScope())
            .getId()
        : null;
    try {
      AdHocResults.recordInterruption(entity, boundaryEvent.getId(), attachedTo, kind, errorCode);
    } catch (RuntimeException e) {
      LOG.warn("Could not record that '{}' interrupted a tool; the agent is told only where its"
          + " flow ended.", boundaryEvent.getId(), e);
    }
  }
}
