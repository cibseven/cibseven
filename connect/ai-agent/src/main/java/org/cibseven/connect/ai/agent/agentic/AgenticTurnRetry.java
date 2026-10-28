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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jakarta.el.ELException;

import org.cibseven.bpm.engine.BpmnParseException;
import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParseUtil;
import org.cibseven.bpm.engine.impl.bpmn.parser.FailedJobRetryConfiguration;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.core.model.PropertyKey;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.impl.util.ParseUtil;
import org.cibseven.bpm.engine.impl.util.xml.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final Logger LOG = LoggerFactory.getLogger(AgenticTurnRetry.class);

  /** Three attempts, thirty seconds apart, when neither the scope nor the engine sets a cycle. */
  public static final String DEFAULT_RETRY_TIME_CYCLE = "R3/PT30S";

  /** The retry cycle of the scope's turn jobs. */
  public static final PropertyKey<FailedJobRetryConfiguration> RETRY_CONFIGURATION =
      new PropertyKey<FailedJobRetryConfiguration>("agenticTurnRetryConfiguration");

  protected static final String FAILED_JOB_RETRY_TIME_CYCLE = "failedJobRetryTimeCycle";

  private AgenticTurnRetry() {
    // utility class
  }

  /**
   * Parses the scope's retry cycle onto its activity. On a parse again a cycle of the scope that
   * cannot be read is left out, and the next one in the order retries the turns: the engine's, else
   * the default (CIB7-2367).
   *
   * <p>Only the scope's own cycle is the model's, so only it refuses a deployment. The engine's is
   * its configuration: one that cannot be read as text is logged, and the default retries the
   * turns. The engine itself treats such a text cycle as unset for its own jobs. An engine cycle
   * that is an expression which cannot be read is another matter: the engine refuses every model
   * with an asynchronous element then, with an agent or without, as it always did.
   */
  public static void configure(Element scopeElement, ActivityImpl activity,
      AgenticToolCatalogParser.Parsing parsing) {
    List<String> cycles = cyclesOf(scopeElement);
    boolean ownFirst = ownCycleOf(scopeElement) != null;
    FailedJobRetryConfiguration configuration = null;
    String used = DEFAULT_RETRY_TIME_CYCLE;
    List<String> unread = new ArrayList<String>();
    for (String cycle : cycles) {
      configuration = read(cycle);
      if (configuration != null) {
        used = cycle;
        break;
      }
      unread.add(cycle);
    }
    String retried = "the turns of '" + activity.getId() + "' are retried by the cycle '" + used
        + "'";
    // The engine's first, also one skipped while the next cycle was looked for: the refusal of the
    // scope's own cycle ends a deployment.
    boolean ownUnread = ownFirst && !unread.isEmpty();
    for (String cycle : unread.subList(ownUnread ? 1 : 0, unread.size())) {
      LOG.warn("The engine's failedJobRetryTimeCycle '{}' is not a retry cycle{}, so {}. Correct"
          + " it in the engine's configuration.", cycle, because(cycle), retried);
    }
    if (ownUnread) {
      String cycle = unread.get(0);
      String reason = whyNot(cycle);
      parsing.refuse(new BpmnParseException("Ad hoc sub process '" + activity.getId() + "': '"
          + cycle + "' is not a retry cycle for its turns" + ((reason == null)
              ? ". Use a repeating ISO 8601 interval such as '" + DEFAULT_RETRY_TIME_CYCLE + "', a"
                  + " list of durations such as 'PT10S,PT1M', or an expression."
              : ": " + reason + ". Correct the expression."), scopeElement), retried);
    }
    activity.getProperties().set(RETRY_CONFIGURATION, configuration);
  }

  /**
   * The cycle read, or {@code null} when it cannot be. An expression that cannot be read throws an
   * EL error, not a refusal, which on a parse again would block the definition (CIB7-2367). Unlike
   * the agent's task, a cycle the node cannot read only gives way to the next one; the agent stays.
   */
  protected static FailedJobRetryConfiguration read(String cycle) {
    try {
      return ParseUtil.parseRetryIntervals(cycle);
    } catch (ELException e) {
      return null;
    }
  }

  /** Why an expression cannot be read as a cycle, from its EL error; {@code null} for a text. */
  protected static String whyNot(String cycle) {
    try {
      ParseUtil.parseRetryIntervals(cycle);
      return null;
    } catch (ELException e) {
      return e.getMessage();
    }
  }

  /** The EL error behind a cycle that cannot be read, for the log, or nothing for a text. */
  protected static String because(String cycle) {
    String reason = whyNot(cycle);
    return (reason == null) ? "" : " (" + reason + ")";
  }

  /**
   * The cycles that are set, in the order they apply: the scope's own, the engine's, and the
   * default, which always ends the list. Without an engine in context, as outside a command,
   * there is no engine's cycle.
   */
  protected static List<String> cyclesOf(Element scopeElement) {
    List<String> cycles = new ArrayList<String>();
    ProcessEngineConfigurationImpl configuration = Context.getProcessEngineConfiguration();
    String engine = (configuration == null) ? null : configuration.getFailedJobRetryTimeCycle();
    for (String cycle : Arrays.asList(ownCycleOf(scopeElement), engine)) {
      if (cycle != null && !cycle.trim().isEmpty()) {
        cycles.add(cycle.trim());
      }
    }
    cycles.add(DEFAULT_RETRY_TIME_CYCLE);
    return cycles;
  }

  /** The scope's own cycle, trimmed, or {@code null}. */
  protected static String ownCycleOf(Element scopeElement) {
    Element element = BpmnParseUtil.findCamundaExtensionElement(scopeElement, FAILED_JOB_RETRY_TIME_CYCLE);
    return (element == null) ? null : element.getText().trim();
  }
}
