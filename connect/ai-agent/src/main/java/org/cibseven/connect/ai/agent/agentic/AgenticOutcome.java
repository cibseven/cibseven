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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * How a turn ended, for the process to branch on (CIB7-2151).
 *
 * <p>Every turn that ends writes one of these next to its answer: under {@link #OUTCOME} in the
 * response the output parameters read, so {@code ${outcome}} maps it, and to
 * {@link #DEFAULT_VARIABLE} when the connector has no output parameters. The answer stays the
 * text for people; this is what a gateway after the scope reads.
 *
 * <p>A limit is no failure by default: the scope ends as after any turn, once nothing runs, and
 * the process goes on. With {@link #ON_LIMIT_PROPERTY} set to {@link #ON_LIMIT_ERROR} the scope
 * ends with a BPMN error instead, its code the outcome, which a boundary event on the scope
 * catches.
 */
public final class AgenticOutcome {

  /** The response key, next to {@code output} and {@code outputAiMeta}. */
  public static final String OUTCOME = "outcome";

  /** Where the outcome goes without output parameters, as the answer goes to agentOutput. */
  public static final String DEFAULT_VARIABLE = "agentOutcome";

  /** The agent called {@code completeScope}: the scope ends now, or once what still runs ends. */
  public static final String COMPLETED = "completed";

  /** The agent answered and nothing it started is running: the scope ends without being asked. */
  public static final String ENDED = "ended";

  /** The agent answered while work it started still runs: the scope waits for the next turn. */
  public static final String WAITING = "waiting";

  /** {@code cibseven.agentic.maxTurns} was reached: the agent is not asked again. */
  public static final String TURN_LIMIT = "turnLimit";

  /** {@code cibseven.agentic.maxTurnsTotal} was reached across the scope's entries. */
  public static final String TOTAL_TURN_LIMIT = "totalTurnLimit";

  /** {@code cibseven.agentic.maxModelCalls} was reached within one turn. */
  public static final String CALL_LIMIT = "callLimit";

  /** The outcomes that are a limit, each a BPMN error code with {@link #ON_LIMIT_ERROR}. */
  public static final List<String> LIMITS =
      Collections.unmodifiableList(Arrays.asList(TURN_LIMIT, TOTAL_TURN_LIMIT, CALL_LIMIT));

  /** {@code camunda:property} on the scope: what a limit does to the scope. */
  public static final String ON_LIMIT_PROPERTY =
      AgenticAdHocParseListener.PROPERTY_PREFIX + "onLimit";

  /** The default: the scope ends as after any turn, and the outcome says which limit. */
  public static final String ON_LIMIT_CONTINUE = "continue";

  /** The scope ends with a BPMN error whose code is the outcome. */
  public static final String ON_LIMIT_ERROR = "error";

  /**
   * Where the last outcome is kept in the step's map, for a turn that ends the scope later: the
   * response key, so the outcome has one name wherever it is kept.
   */
  static final String STATE_KEY = OUTCOME;

  private AgenticOutcome() {
    // constants and checks only
  }

  public static boolean isLimit(Object outcome) {
    return outcome != null && LIMITS.contains(outcome.toString());
  }
}
