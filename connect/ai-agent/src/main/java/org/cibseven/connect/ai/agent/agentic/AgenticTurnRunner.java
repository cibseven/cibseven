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

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.delegate.BpmnError;
import org.cibseven.bpm.engine.impl.bpmn.helper.BpmnExceptionHandler;
import org.cibseven.bpm.engine.impl.bpmn.helper.BpmnProperties;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.core.variable.mapping.InputParameter;
import org.cibseven.bpm.engine.impl.core.variable.mapping.IoMapping;
import org.cibseven.bpm.engine.impl.core.variable.mapping.OutputParameter;
import org.cibseven.bpm.engine.impl.db.sql.DbSqlSessionFactory;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.JobEntity;
import org.cibseven.bpm.engine.impl.pvm.PvmActivity;
import org.cibseven.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;
import org.cibseven.bpm.engine.impl.util.ClockUtil;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.ObjectValue;
import org.cibseven.connect.Connectors;
import org.cibseven.connect.ai.agent.AgentConnector;
import org.cibseven.connect.spi.Connector;
import org.cibseven.connect.spi.ConnectorRequest;
import org.cibseven.connect.spi.ConnectorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one step of a turn: asks the agent what to do next and acts on what it did.
 *
 * <p>The agent is reached through the connector registry, the same way a service task with
 * {@code camunda:connector} reaches it, so a turn uses the connector the engine has registered
 * and configured rather than an instance of its own.
 */
public final class AgenticTurnRunner {

  private static final Logger LOG = LoggerFactory.getLogger(AgenticTurnRunner.class);

  public static final String AGENT_CONNECTOR_ID = "cibseven-ai-agent";

  /** Where the answer goes when the agent's connector has no output parameters. */
  public static final String DEFAULT_RESULT_VARIABLE = "agentOutput";

  private AgenticTurnRunner() {
  }

  /**
   * The response parameter that says the model called tools in this step and has not answered:
   * the tools ran, their results are in the conversation, and the turn goes on in a further step.
   */
  public static final String STEP_CALLED_TOOLS = "stepCalledTools";

  /**
   * The connector's input parameter sent instead of the task on the first turn of a re-entry:
   * what changed since the agent was last here, the user's feedback in a feedback loop.
   *
   * <p>Held apart from the connector's other input parameters at parse time
   * ({@code AgenticAdHocParseListener.extractFollowUp}), so the generic per-step evaluation in
   * {@link #askTheAgent} never touches it. {@link #snapshotFollowUp} is the only thing that
   * evaluates it, once, when a re-entry begins.
   */
  public static final String FOLLOW_UP_PARAMETER = "followUp";

  /**
   * The key {@link #snapshotFollowUp} freezes the result under, in the agent state's own map
   * (CIB7-2200) -- not a variable of its own, and not of the scope execution. A value re-read
   * from the expression would evaluate it again on every step of every turn (CIB7-2148), which
   * could see the feedback change between this entry and whenever the first turn's job actually
   * runs, or between two steps of that same turn. A value frozen on the scope execution would not
   * fix that either: the scope's children are the agent's tools, they are the untrusted party,
   * and the scope execution is their ancestor, so a tool calling
   * {@code execution.setVariable(FROZEN_FOLLOW_UP, ...)} would overwrite it there and change what
   * a later step of the same turn sends. The agent state execution is their sibling instead,
   * unreachable the same way {@link AdHocAgentState}'s own fields already are.
   *
   * <p>A map entry rather than {@code setVariableLocal(FROZEN_FOLLOW_UP, ...)} directly, for the
   * same two reasons every other field of the agent state moved into that map: a value that long
   * -- a user's feedback, not a short token -- has no business in the engine's 4000-character
   * string column, and a feedback loop that re-enters many times must not grow the agent state by
   * one more variable, and one more history row at level {@code full}, per entry.
   */
  public static final String FROZEN_FOLLOW_UP = "adHocAgentFollowUp";

  /**
   * Runs one step of a turn: one model call, the tools it asks for, committed together (CIB7-2148).
   *
   * <p>A turn is as many steps as the model needs: a step whose model calls tools schedules the
   * next one as a job of its own, and the model ends the turn by answering. A failure from here on
   * replays one model call, not the turn, and the job lock has to cover one model call only.
   */
  public static void runTurn(ExecutionEntity scopeExecution, CommandContext commandContext) {
    if (!AgenticScopes.isAgentic(scopeExecution)) {
      return;
    }
    String stepId = turnId(commandContext);
    boolean continuing = AdHocAgentState.isTurnInProgress(scopeExecution);

    // A request an earlier turn left pending, because work was still running then. Acting on
    // it here rather than asking again: the agent gave its answer in that turn, and a model call
    // is the expensive part. Within the turn that asked to end, the next step is the one in which
    // the model gives that answer, so it is asked.
    if (AdHocAgentState.isCompletionRequested(scopeExecution) && !continuing) {
      endOfTurn(scopeExecution);
      return;
    }

    if (!continuing) {
      // The turn cap, before the model costs anything. Counted against the job's id, so a
      // failed turn and its retry are one turn, not two. Over the cap no model is asked again.
      //
      // The scope is not ended here. The cap bounds what the agent may still think, not work
      // it already handed out: ending over a running child would cancel it, and a child of an
      // agentic scope is a task a person may be halfway through. So the request is recorded
      // and endOfTurn ends the scope once nothing is running -- exactly what a completeScope
      // refused for live work leaves behind, and what the branch above then acts on. Every
      // further wake-up leaves through that branch: no count, no answer rewritten, no model.
      //
      // requestCompletion stages the flag in the step's state map (CIB7-2200); it is written
      // back when this command closes, unless endOfTurn below has ended the scope by then, in
      // which case there is no execution left to write it to -- and none needed, since nothing
      // will ever ask this scope's state again.
      int turn = AdHocAgentState.beginTurn(scopeExecution, stepId);
      int maxTurns = AdHocAgentState.positiveProperty(scopeExecution,
          AdHocAgentState.MAX_TURNS_PROPERTY, AdHocAgentState.DEFAULT_MAX_TURNS);
      if (turn > maxTurns) {
        writeAnswer(scopeExecution, answerOnly(scopeExecution, "The turn limit of "
            + maxTurns + " for this ad hoc sub process was reached, so the agent was not"
            + " asked again."), AgenticOutcome.TURN_LIMIT);
        AdHocAgentState.requestCompletion(scopeExecution);
        endOfTurn(scopeExecution);
        return;
      }

      // The cap across every entry of the scope (CIB7-2137), counted in the conversation holder,
      // so a feedback loop that leaves the scope and comes back is bounded as a whole and not
      // once per entry. Zero means no total cap, which is every scope that does not keep its
      // conversation: the parse listener refuses the property without perActivity.
      int turnsTotal = AdHocConversation.beginTurnTotal(scopeExecution, stepId);
      int maxTurnsTotal = AdHocAgentState.positiveProperty(scopeExecution,
          AdHocConversation.MAX_TURNS_TOTAL_PROPERTY, 0);
      if (maxTurnsTotal > 0 && turnsTotal > maxTurnsTotal) {
        writeAnswer(scopeExecution, answerOnly(scopeExecution, "The total turn limit of "
            + maxTurnsTotal + " across every entry of this ad hoc sub process was reached, so the"
            + " agent was not asked again."), AgenticOutcome.TOTAL_TURN_LIMIT);
        AdHocAgentState.requestCompletion(scopeExecution);
        endOfTurn(scopeExecution);
        return;
      }
    }

    // The model-call cap of the turn, also before the model costs anything. Committed with each
    // step, so it holds across retries: a retried step is the same call, not a further one.
    int calls = AdHocAgentState.countModelCall(scopeExecution, stepId);
    int maxCalls = AdHocAgentState.positiveProperty(scopeExecution,
        AdHocAgentState.MAX_MODEL_CALLS_PROPERTY, AdHocAgentState.DEFAULT_MAX_MODEL_CALLS);
    if (calls > maxCalls) {
      writeAnswer(scopeExecution, answerOnly(scopeExecution, "The limit of " + maxCalls
          + " model calls in one turn of this ad hoc sub process was reached, so the turn was"
          + " ended without an answer from the agent."), AgenticOutcome.CALL_LIMIT);
      AdHocAgentState.endTurn(scopeExecution);
      endOfTurn(scopeExecution);
      return;
    }

    Map<String, Object> response = askTheAgent(scopeExecution);
    if (Boolean.TRUE.equals(response.get(STEP_CALLED_TOOLS))) {
      // Nothing to write: the model has not answered. It does so in the next step, which sees
      // the tools' results in the conversation and what has finished in the state block.
      AdHocAgentState.continueTurn(scopeExecution);
      AgenticTurnScheduler.scheduleTurn(scopeExecution);
      return;
    }

    String outcome = AdHocAgentState.isCompletionRequested(scopeExecution)
        ? AgenticOutcome.COMPLETED
        : hasChildren(scopeExecution) ? AgenticOutcome.WAITING : AgenticOutcome.ENDED;
    writeAnswer(scopeExecution, response, outcome);
    AdHocAgentState.endTurn(scopeExecution);
    endOfTurn(scopeExecution);
  }

  /**
   * Where the answer goes: the output parameters of the agent's connector decide, as on a service
   * task, applied after every turn -- when the model answers, not after a step in which it only
   * called tools; without any, the answer goes to {@code agentOutput}.
   *
   * <p>The turn's outcome goes with it (CIB7-2151): under {@code outcome} in the response, so an
   * output parameter maps {@code ${outcome}}, and to {@code agentOutcome} when there are none.
   * It is also kept in the step's map, for a later turn that ends the scope without writing an
   * answer: over a cap, or after {@code completeScope}, once what still ran has ended.
   */
  protected static void writeAnswer(ExecutionEntity scopeExecution, Map<String, Object> response,
      String outcome) {
    Map<String, Object> withOutcome = new HashMap<String, Object>(response);
    withOutcome.put(AgenticOutcome.OUTCOME, outcome);
    AdHocAgentState.updateState(scopeExecution, AgenticOutcome.STATE_KEY, outcome);
    IoMapping io = AgenticScopes.agentConnectorIo(scopeExecution);
    if (!io.getOutputParameters().isEmpty()) {
      AgentConnectorVariableScope outputs = new AgentConnectorVariableScope(scopeExecution);
      outputs.read(withOutcome);
      // One by one, as IoMapping.executeOutputParameters would, but through storable(): EL hands
      // ${output} over as a plain String, and a long one would not fit its variable (CIB7-2165).
      for (OutputParameter parameter : io.getOutputParameters()) {
        scopeExecution.setVariable(parameter.getName(),
            storable(parameter.getValueProvider().getValue(outputs)));
      }
      return;
    }
    scopeExecution.setVariable(DEFAULT_RESULT_VARIABLE, storable(response.get("output")));
    scopeExecution.setVariable(AgenticOutcome.DEFAULT_VARIABLE, outcome);
  }

  /**
   * A value a variable can hold (CIB7-2165). The connector hands an answer longer than a String
   * variable's text column over as an object value, and a String that long fails the insert of the
   * variable and of its history row. So the connector's object is unwrapped, and a String over the
   * column becomes an object, Java-serialized like the chat log and the conversation, which every
   * engine can write, Spin or not. Readers still get the String back. A shorter one stays a String.
   */
  protected static Object storable(Object value) {
    ProcessEngineConfigurationImpl configuration = Context.getProcessEngineConfiguration();
    return storable(value, (configuration == null) ? null : configuration.getDatabaseType());
  }

  /** As {@link #storable(Object)}, for the database {@code databaseType}. */
  protected static Object storable(Object value, String databaseType) {
    Object plain = value;
    if (value instanceof ObjectValue && ((ObjectValue) value).isDeserialized()
        && ((ObjectValue) value).getValue() instanceof String) {
      plain = ((ObjectValue) value).getValue();
    }
    if (plain instanceof String && !fitsAStringVariable((String) plain, databaseType)) {
      return Variables.objectValue(plain)
          .serializationDataFormat(Variables.SerializationDataFormats.JAVA).create();
    }
    return plain;
  }

  /**
   * Whether a String variable's text column holds {@code text}: 2000 characters on Oracle,
   * NVARCHAR2(2000), and 4000 on the others; on DB2 also no more than 4000 bytes of UTF-8, since
   * its VARCHAR(4000) counts bytes, and text with umlauts reaches them before 4000 characters.
   */
  protected static boolean fitsAStringVariable(String text, String databaseType) {
    if (DbSqlSessionFactory.ORACLE.equals(databaseType)) {
      return text.length() <= 2000;
    }
    if (text.length() > 4000) {
      return false;
    }
    return !DbSqlSessionFactory.DB2.equals(databaseType)
        || text.getBytes(StandardCharsets.UTF_8).length <= 4000;
  }

  /**
   * A response that carries only an answer the loop wrote itself, for the output parameters. Its
   * meta says that no model wrote it, so a marker read from {@code outputAiMeta.aiGenerated} is
   * false rather than null.
   */
  protected static Map<String, Object> answerOnly(ExecutionEntity scopeExecution, String answer) {
    Map<String, Object> aiMeta = new LinkedHashMap<String, Object>();
    aiMeta.put("aiGenerated", Boolean.FALSE);
    aiMeta.put("generatedAt", ClockUtil.getCurrentTime().toInstant().toString());
    Map<String, Object> response = new HashMap<String, Object>();
    response.put("output", answer);
    response.put("outputAiMeta", aiMeta);
    response.put("memoryId", memoryId(scopeExecution));
    return response;
  }

  /**
   * One conversation per scope execution, kept across its turns — or one per activity, kept
   * across every entry of the scope, when it asks for that (CIB7-2137).
   */
  protected static String memoryId(ExecutionEntity scopeExecution) {
    return AdHocConversation.memoryId(scopeExecution);
  }

  /**
   * Evaluates the scope's {@code followUp} expression once, for this re-entry, and freezes the
   * result on the agent state execution -- a sibling of the scope's tools, not an ancestor, the
   * same protection {@link AdHocAgentState} already gives its own fields (CIB7-2137).
   *
   * <p>Called only when this entry is a re-entry ({@code AgenticAdHocStartListener}), in the same
   * transaction the scope is entered in -- not later, when the first turn's job happens to run,
   * and not again on a later step or turn. A scope's first entry, where the result would go
   * unused, never calls this at all: a {@code followUp} expression that reads a variable the
   * feedback step sets -- absent before the user has ever given feedback -- must not be able to
   * fail a turn that was never going to use it.
   * If evaluation fails on re-entry, a warning is logged and the task is used instead.
   */
  public static void snapshotFollowUp(ExecutionEntity scopeExecution) {
    InputParameter followUp = AgenticScopes.followUpInputParameter(scopeExecution);
    if (followUp == null) {
      return;
    }
    Object value;
    try {
      value = followUp.getValueProvider().getValue(scopeExecution);
    } catch (ProcessEngineException e) {
      LOG.warn("Ad hoc sub process '{}', process instance '{}': evaluating connector input"
          + " 'followUp' on re-entry failed; using the task ('message') instead.",
          scopeExecution.getActivityId(), scopeExecution.getProcessInstanceId(), e);
      return;
    }
    if (value != null && !value.toString().trim().isEmpty()) {
      AdHocAgentState.updateState(scopeExecution, FROZEN_FOLLOW_UP, value.toString());
    }
  }

  /** What tells one turn from the next: the turn job. Null outside one counts safely. */
  protected static String turnId(CommandContext commandContext) {
    JobEntity job = (commandContext == null) ? null : commandContext.getCurrentJob();
    return (job == null) ? null : "job:" + job.getId();
  }

  /** Asks the agent once, and returns the connector's response parameters. */
  protected static Map<String, Object> askTheAgent(ExecutionEntity scopeExecution) {
    Connector<ConnectorRequest<?>> connector = Connectors.getConnector(AGENT_CONNECTOR_ID);
    if (connector == null) {
      throw new ProcessEngineException("Ad hoc sub process '" + scopeExecution.getActivityId()
          + "' is agentic, but no connector '" + AGENT_CONNECTOR_ID + "' is registered.");
    }

    ConnectorRequest<?> request = connector.createRequest();
    // The connector's input parameters, evaluated now, on this turn, against the scope, as a
    // service task evaluates them when it runs. A variable changed since the last turn reaches
    // the model, and nothing evaluated is stored where a child could rewrite it.
    AgentConnectorVariableScope inputs = new AgentConnectorVariableScope(scopeExecution);
    AgenticScopes.agentConnectorIo(scopeExecution).executeInputParameters(inputs);
    inputs.writeTo(request);
    normalizeAndValidateMemoryWindow(scopeExecution, request);
    // On the first turn of a re-entry the agent already has the conversation, so what it needs
    // is what changed -- the user's feedback -- rather than the task it has read before
    // (CIB7-2137). The frozen value from entry, read off the agent state's own map so a tool
    // cannot overwrite it between two steps of this turn: every step sends it, as every step
    // sends the task (CIB7-2148), so it must stay the same value throughout. From the second
    // turn on the task leads again, as it does without a re-entry.
    Object frozenFollowUp = AdHocAgentState.stateValue(scopeExecution, FROZEN_FOLLOW_UP);
    if (AdHocAgentState.turns(scopeExecution) <= 1 && frozenFollowUp != null) {
      request.setRequestParameter("message", frozenFollowUp);
    }

    // The conversation belongs to the loop: the parse listener refuses a connector that sets it.
    request.setRequestParameter("useChatMemory", Boolean.TRUE);
    request.setRequestParameter("memoryId", memoryId(scopeExecution));
    // The connector refuses a request without a name, and an empty one as well, which is what an
    // expression for the name can evaluate to. The scope's id is one the modeller already chose,
    // and it says which scope a log line belongs to.
    Object agentName = request.getRequestParameter("agentName");
    if (agentName == null || agentName.toString().trim().isEmpty()) {
      request.setRequestParameter("agentName", scopeExecution.getActivityId());
    }

    // The tool finds its scope through the thread's BPMN execution context, which a job does not
    // set. Without this the tool refuses, saying no BPMN execution is available.
    Context.setExecutionContext(scopeExecution);
    try {
      // The conversation is the scope's own, kept out of its tools' reach: said here, by the one
      // caller that drives the scope, not guessed by the chat memory store (CIB7-2232).
      ConnectorResponse response = AdHocConversation.drive(scopeExecution, () -> request.execute());
      return response.getResponseParameters();
    } finally {
      Context.removeExecutionContext();
    }
  }

  /**
   * Applies the contextual default and validates the resolved perActivity window before the
   * connector is invoked. Other scopes retain the connector's existing global default.
   */
  private static void normalizeAndValidateMemoryWindow(ExecutionEntity scopeExecution,
      ConnectorRequest<?> request) {
    if (!AdHocConversation.isPerActivity(scopeExecution)) {
      return;
    }

    int maxModelCalls = AdHocAgentState.positiveProperty(scopeExecution,
        AdHocAgentState.MAX_MODEL_CALLS_PROPERTY, AdHocAgentState.DEFAULT_MAX_MODEL_CALLS);
    long required = AdHocConversation.requiredMemoryWindow(maxModelCalls);
    if (required > Integer.MAX_VALUE) {
      throw new ProcessEngineException("Ad hoc sub process '" + scopeExecution.getActivityId()
          + "': chatMemoryMaxMessages requires a minimum of " + required + " messages for "
          + maxModelCalls + " model calls, exceeding the supported integer window size "
          + Integer.MAX_VALUE + ".");
    }

    Object configured = request.getRequestParameter(
        AgentConnector.PARAM_NAME_CHAT_MEMORY_MAX_MESSAGES);
    if (configured == null || configured.toString().trim().isEmpty()) {
      int defaultWindow;
      try {
        defaultWindow = AdHocConversation.defaultMemoryWindow(maxModelCalls);
      } catch (IllegalArgumentException e) {
        throw new ProcessEngineException("Ad hoc sub process '" + scopeExecution.getActivityId()
            + "': " + e.getMessage(), e);
      }
      request.setRequestParameter(AgentConnector.PARAM_NAME_CHAT_MEMORY_MAX_MESSAGES,
          Integer.valueOf(defaultWindow));
      return;
    }

    int actual;
    try {
      actual = Integer.parseInt(configured.toString().trim());
    } catch (NumberFormatException e) {
      throw new ProcessEngineException("Ad hoc sub process '" + scopeExecution.getActivityId()
          + "': chatMemoryMaxMessages must be a positive integer and at least " + required
          + " messages for " + maxModelCalls + " model calls, but is '" + configured + "'.", e);
    }
    if (actual < required) {
      throw new ProcessEngineException("Ad hoc sub process '" + scopeExecution.getActivityId()
          + "': chatMemoryMaxMessages must be at least " + required + " messages for "
          + maxModelCalls + " model calls, but is " + actual + ".");
    }
    request.setRequestParameter(AgentConnector.PARAM_NAME_CHAT_MEMORY_MAX_MESSAGES,
        Integer.valueOf(actual));
  }

  /**
   * Two exits, decided by one question: is anything still running inside the scope?
   *
   * <p>Nothing is. Then the scope ends -- either the agent asked for that, or it did not but
   * started nothing either, and no further turn would ever be scheduled.
   *
   * <p>Something is. Then the scope stands and that child's end brings the next turn. This
   * holds even when the agent asked to end: ending here would cancel live work, which is the
   * very thing {@code completeScope} refuses for, and a child can still be started from
   * outside after the request was made. The request keeps, and the turn that child's end
   * schedules acts on it -- see {@link #runTurn}.
   *
   * <p>Where the scope would end after a limit, and the scope asks for it with
   * {@code cibseven.agentic.onLimit = error}, it ends with a BPMN error instead, its code the
   * limit (CIB7-2151).
   */
  protected static void endOfTurn(ExecutionEntity scopeExecution) {
    if (hasChildren(scopeExecution)) {
      return;
    }
    Object outcome = AdHocAgentState.stateValue(scopeExecution, AgenticOutcome.STATE_KEY);
    if (AgenticOutcome.isLimit(outcome) && throwsOnLimit(scopeExecution)) {
      throwTheLimit(scopeExecution, outcome.toString());
      return;
    }
    Context.getProcessEngineConfiguration().getRuntimeService()
        .completeAdHocSubProcess(scopeExecution.getId());
  }

  /** Whether a limit ends the scope with a BPMN error: {@code cibseven.agentic.onLimit = error}. */
  protected static boolean throwsOnLimit(ExecutionEntity scopeExecution) {
    PvmActivity activity = scopeExecution.getActivity();
    Object raw = (activity == null)
        ? null
        : activity.getProperty(BpmnProperties.EXTENSION_PROPERTIES.getName());
    return raw instanceof Map && AgenticOutcome.ON_LIMIT_ERROR.equals(
        String.valueOf(((Map<?, ?>) raw).get(AgenticOutcome.ON_LIMIT_PROPERTY)).trim());
  }

  /**
   * Ends the scope with a BPMN error whose code is the limit, where it would otherwise complete:
   * the same moment, once nothing runs, so a limit still cancels no work (CIB7-2144). The parse
   * listener has made sure a boundary event on the scope catches every limit it can reach, so
   * the engine never ends the scope silently for want of a catch (CIB7-2151).
   */
  protected static void throwTheLimit(ExecutionEntity scopeExecution, String limit) {
    String message = "The agent of ad hoc sub process '" + scopeExecution.getActivityId()
        + "' stopped at a limit: " + limit + ".";
    try {
      BpmnExceptionHandler.propagateBpmnError(new BpmnError(limit, message), scopeExecution);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new ProcessEngineException(message, e);
    }
  }

  protected static boolean hasChildren(ExecutionEntity scopeExecution) {
    for (PvmExecutionImpl child : scopeExecution.getNonEventScopeExecutions()) {
      if (child != null) {
        return true;
      }
    }
    return false;
  }
}
