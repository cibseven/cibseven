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

import org.cibseven.bpm.engine.BpmnParseException;
import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParseUtil;
import org.cibseven.bpm.engine.impl.bpmn.parser.FailedJobRetryConfiguration;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.core.model.PropertyKey;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.impl.util.ParseUtil;
import org.cibseven.bpm.engine.impl.util.xml.Element;

/**
 * How a failed turn is retried (CIB7-2149).
 *
 * <p>The engine reads a retry cycle only for timer and async-continuation jobs, so a failed step
 * was retried at once, three times within a fraction of a second, and then ended in an incident.
 * The model call itself is already retried by LangChain4j; what that does not cover is a rate limit
 * that lasts longer, or a step that fails elsewhere. A pause between the attempts gives those a
 * chance.
 *
 * <p>The cycle is the scope's {@code camunda:failedJobRetryTimeCycle}, else the engine's
 * {@code failedJobRetryTimeCycle}, else {@link #DEFAULT_RETRY_TIME_CYCLE}. It is kept under a key
 * of its own: the engine's key on the same activity belongs to the scope's own asynchronous
 * continuation, and a default set there would change that job's retries too.
 */
public final class AgenticTurnRetry {

  /** Three attempts, thirty seconds apart, when neither the scope nor the engine sets a cycle. */
  public static final String DEFAULT_RETRY_TIME_CYCLE = "R3/PT30S";

  /** The retry cycle of the scope's turn jobs. */
  public static final PropertyKey<FailedJobRetryConfiguration> RETRY_CONFIGURATION =
      new PropertyKey<FailedJobRetryConfiguration>("agenticTurnRetryConfiguration");

  protected static final String FAILED_JOB_RETRY_TIME_CYCLE = "failedJobRetryTimeCycle";

  private AgenticTurnRetry() {
    // utility class
  }

  /** Parses the scope's retry cycle onto its activity. */
  public static void configure(Element scopeElement, ActivityImpl activity) {
    String cycle = cycleOf(scopeElement);
    FailedJobRetryConfiguration configuration = ParseUtil.parseRetryIntervals(cycle);
    if (configuration == null) {
      throw new BpmnParseException("Ad hoc sub process '" + activity.getId()
          + "': '" + cycle + "' is not a retry cycle for its turns. Use a repeating ISO 8601 interval"
          + " such as '" + DEFAULT_RETRY_TIME_CYCLE + "', a list of durations such as 'PT10S,PT1M',"
          + " or an expression.", scopeElement);
    }
    activity.getProperties().set(RETRY_CONFIGURATION, configuration);
  }

  /** The scope's own cycle, the engine's, or the default, in that order. */
  protected static String cycleOf(Element scopeElement) {
    Element element = BpmnParseUtil.findCamundaExtensionElement(scopeElement, FAILED_JOB_RETRY_TIME_CYCLE);
    String cycle = (element == null) ? null : element.getText().trim();
    if (cycle == null || cycle.isEmpty()) {
      cycle = Context.getProcessEngineConfiguration().getFailedJobRetryTimeCycle();
    }
    if (cycle == null || cycle.trim().isEmpty()) {
      cycle = DEFAULT_RETRY_TIME_CYCLE;
    }
    return cycle.trim();
  }
}
