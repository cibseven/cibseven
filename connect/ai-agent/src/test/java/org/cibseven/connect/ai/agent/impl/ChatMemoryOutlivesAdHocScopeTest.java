/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.cibseven.connect.ai.agent.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.history.HistoricVariableInstance;
import org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.runtime.Execution;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.runtime.VariableInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.connect.ai.agent.AgentConnectorConstants;
import org.cibseven.connect.ai.agent.agentic.AdHocAgentState;
import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.langchain4j.store.memory.chat.ChatMemoryStore;

/**
 * An AI agent task inside an ad hoc sub process keeps its conversation where an AI agent task
 * anywhere else keeps it: at the process level, so it outlives the scope (CIB7-2232).
 *
 * <p>The human-in-the-loop pattern continues one conversation across several AI agent tasks
 * through one {@code memoryId}. A task of that conversation can sit inside an ad hoc sub process,
 * a plain one or an agentic one, where it is a tool. Only the conversation of the agent that drives
 * an agentic scope is the scope's own: it is kept out of its tools' reach and goes with the scope.
 * Every other conversation has to be there for the next task after the scope.
 *
 * <p>A tool of an agentic scope without a {@code memoryId} of its own gets one derived from the
 * scope's own key (CIB7-2155); in a plain scope such a task gets a new id on every call, as it
 * does outside any scope. The tool's conversation is kept at the process level too: with
 * {@code perEntry} under a key of the scope instance, so neither the instances of a multi-instance
 * scope nor its entries share it; with {@code perActivity} under the scope's anchor, so the
 * entries continue one.
 *
 * <p>The model is a stub answering on an ephemeral port, so these tests do not share a port with
 * the other stubs of this module.
 */
public class ChatMemoryOutlivesAdHocScopeTest {

  private static final String MEMORY_ID = "case-42";
  private static final String MEMORY_VARIABLE =
      AgentConnectorConstants.AGENT_CONNECTOR_MEMORY_PREFIX + MEMORY_ID;

  /** The agentic scope's own task, which must stay in the scope's own conversation. */
  private static final String SCOPE_TASK = "Handle the case, ask the advisor where needed.";

  /** A tool's result in a request, which a step after the tool call carries. */
  private static final Pattern TOOL_RESULT = Pattern.compile("\"role\"\\s*:\\s*\"tool\"");

  private HttpServer stub;
  private int stubPort;
  private final ConcurrentLinkedQueue<String> script = new ConcurrentLinkedQueue<>();
  /** Answers by what is asked, where the order of the requests is not fixed; else the script. */
  private volatile Function<String, String> responder;
  private final List<String> requestBodies = new CopyOnWriteArrayList<>();
  private ProcessEngine engine;
  private ChatMemoryStore previousStore;

  @BeforeEach
  public void startStub() throws IOException {
    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext("/v1/chat/completions", this::respond);
    stub.setExecutor(null);
    stub.start();
    stubPort = stub.getAddress().getPort();
    // The default store, whatever another test in this JVM left installed.
    previousStore = AgentChatMemoryStore.getStore();
    AgentChatMemoryStore.setStore(new ProcessVariableChatMemoryStore());
  }

  @AfterEach
  public void stopEverything() {
    AgentChatMemoryStore.setStore(previousStore);
    if (engine != null) {
      engine.close();
      engine = null;
    }
    if (stub != null) {
      stub.stop(0);
      stub = null;
    }
  }

  // --- a plain ad hoc sub process -----------------------------------------------

  /** The conversation starts inside a plain scope, and the task after the scope continues it. */
  @Test
  public void aConversationStartedInsideAPlainScopeContinuesAfterIt() {
    script.add(answer("Noted, Berlin."));
    script.add(answer("You live in Berlin."));

    ProcessInstance pi = start(plainModel(false));
    activate(pi, "inside");

    assertThat(requestBodies).hasSize(2);
    assertThat(requestBodies.get(1)).as("the task after the scope continues the conversation")
        .contains("I live in Berlin.")
        .contains("Noted, Berlin.");
    assertThat(memoryVariable(pi).getExecutionId()).as("kept at the process instance")
        .isEqualTo(pi.getId());
  }

  /**
   * The conversation starts before the scope: the task inside continues it, and so does the task
   * after the scope, with everything both earlier tasks said.
   */
  @Test
  public void aConversationStartedBeforeAPlainScopeContinuesInsideAndAfterIt() {
    script.add(answer("Noted, Alice."));
    script.add(answer("Noted, Berlin."));
    script.add(answer("You are Alice and live in Berlin."));

    ProcessInstance pi = start(plainModel(true));
    activate(pi, "inside");

    assertThat(requestBodies).hasSize(3);
    assertThat(requestBodies.get(1)).as("the task inside the scope continues the conversation")
        .contains("My name is Alice.")
        .contains("Noted, Alice.");
    assertThat(requestBodies.get(2)).as("and so does the task after the scope")
        .contains("My name is Alice.")
        .contains("Noted, Alice.")
        .contains("I live in Berlin.")
        .contains("Noted, Berlin.");
    assertThat(memoryVariable(pi).getExecutionId()).as("kept at the process instance")
        .isEqualTo(pi.getId());
  }

  /**
   * No agent drives a plain scope, so it gets no agent state: no further execution and no further
   * variable, while the scope runs and at any time before. Its conversation sits at the process
   * instance meanwhile.
   */
  @Test
  public void aPlainScopeWithAnAgentTaskInsideHasNoAgentState() {
    script.add(answer("Noted, Berlin."));

    ProcessInstance pi = start(plainModel(false));
    // A waiting task holds the scope open, so what it carries can be looked at.
    activate(pi, "inside", "waits");

    assertThat(requestBodies).hasSize(1);
    assertThat(engine.getRuntimeService().createExecutionQuery()
        .processInstanceId(pi.getId()).activityId("adHoc").count())
        .as("the scope still runs").isEqualTo(1);
    assertThat(engine.getRuntimeService().createVariableInstanceQuery()
        .processInstanceIdIn(pi.getId()).variableName(AdHocAgentState.STATE_MARKER).list())
        .as("no agent state under a plain scope").isEmpty();
    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName(AdHocAgentState.STATE_MARKER)
        .includeDeleted().list())
        .as("and there never was one").isEmpty();
    assertThat(memoryVariable(pi).getExecutionId()).as("the conversation at the process instance")
        .isEqualTo(pi.getId());
  }

  // --- an AI agent task used as a tool of an agentic scope ------------------------

  /**
   * An AI agent task with its own {@code memoryId}, used as a tool: the task after the scope
   * continues the tool's conversation. The scope's own conversation stays the scope's: out of the
   * tools' reach while the scope runs, and in neither the tool's conversation nor the next task's.
   */
  @Test
  public void anAgentTaskUsedAsAToolContinuesItsConversationAfterTheScope() {
    // Step 1 of the scope's turn calls the tool; the tool's own model call runs nested in it.
    script.add(toolCall("berater"));
    script.add(answer("Noted, Berlin."));
    // Step 2 answers; nothing runs any more, so the scope ends and the task after it runs.
    script.add(answer("Done."));
    script.add(answer("You live in Berlin."));

    ProcessInstance pi = start(agenticModel(true));
    String scope = scopeExecutionId(pi);
    TurnSteps.runToTheEnd(engine, pi.getId());

    assertThat(requestBodies).hasSize(4);
    assertThat(requestBodies.get(1)).as("the tool's own conversation")
        .contains("I live in Berlin.")
        .doesNotContain(SCOPE_TASK);
    assertThat(requestBodies.get(3)).as("the task after the scope continues the tool's one")
        .contains("I live in Berlin.")
        .contains("Noted, Berlin.")
        .doesNotContain(SCOPE_TASK);
    assertThat(memoryVariable(pi).getExecutionId()).as("kept at the process instance")
        .isEqualTo(pi.getId());

    List<HistoricVariableInstance> own = engine.getHistoryService()
        .createHistoricVariableInstanceQuery().processInstanceId(pi.getId())
        .variableName(AgentConnectorConstants.AGENT_CONNECTOR_MEMORY_PREFIX + "adhoc-" + scope)
        .includeDeleted().list();
    assertThat(own).as("the scope's own conversation").isNotEmpty();
    assertThat(own).extracting(HistoricVariableInstance::getExecutionId)
        .as("kept on the agent state, never on the scope or the process instance")
        .doesNotContain(scope, pi.getId());
  }

  /**
   * An AI agent task used as a tool without a {@code memoryId} of its own keeps the id CIB7-2155
   * derives for it from the scope's own key, and its conversation is kept at the process instance
   * under that id.
   */
  @Test
  public void theFallbackIdOfAToolNamesAConversationAtTheProcessInstance() {
    script.add(toolCall("berater"));
    script.add(answer("Noted, Berlin."));
    script.add(answer("Done."));
    script.add(answer("Nothing to continue."));

    ProcessInstance pi = start(agenticModel(false));
    String scope = scopeExecutionId(pi);
    TurnSteps.runToTheEnd(engine, pi.getId());

    assertThat(requestBodies).hasSize(4);
    String fallback = AgentConnectorConstants.AGENT_CONNECTOR_MEMORY_PREFIX
        + "adhoc-" + scope + ":tool:berater";
    List<VariableInstance> tool = engine.getRuntimeService().createVariableInstanceQuery()
        .processInstanceIdIn(pi.getId()).variableName(fallback).list();
    assertThat(tool).as("the tool's conversation under its fallback id, after the scope")
        .hasSize(1);
    assertThat(tool.get(0).getExecutionId()).as("kept at the process instance")
        .isEqualTo(pi.getId());
    assertThat(String.valueOf(tool.get(0).getValue())).contains("Noted, Berlin.");
  }

  /**
   * A tool's derived id never names another scope's own conversation, whatever the activity ids:
   * the tool {@code review} of the perActivity scope {@code case}, and a second perActivity scope
   * whose id is {@code case-tool-review}, in the same process instance. Both keys are built from the
   * same enclosing execution, so only the separator before the tool's id can keep them apart.
   */
  @Test
  public void theDerivedIdOfAToolNeverNamesAnotherScopesOwnConversation() {
    script.add(toolCall("review"));
    script.add(answer("Reviewed, all fine."));
    script.add(answer("Done with the first step."));
    script.add(answer("Done with the second step."));

    ProcessInstance pi = start(twoPerActivityScopesModel());
    TurnSteps.runToTheEnd(engine, pi.getId());

    assertThat(requestBodies).hasSize(4);
    assertThat(requestBodies.get(3)).as("the second scope's agent starts a conversation of its own")
        .contains("Handle the second step.")
        .doesNotContain("Review the case.")
        .doesNotContain("Reviewed, all fine.");
    List<VariableInstance> toolConversations = new ArrayList<>();
    for (VariableInstance variable : engine.getRuntimeService().createVariableInstanceQuery()
        .processInstanceIdIn(pi.getId()).list()) {
      if (variable.getName().startsWith(AgentConnectorConstants.AGENT_CONNECTOR_MEMORY_PREFIX)
          && String.valueOf(variable.getValue()).contains("Reviewed, all fine.")) {
        toolConversations.add(variable);
      }
    }
    assertThat(toolConversations).as("the tool's conversation, left where it was written")
        .hasSize(1);
    assertThat(toolConversations.get(0).getExecutionId()).as("at the process instance")
        .isEqualTo(pi.getId());
  }

  /**
   * With {@code perActivity} a tool without a {@code memoryId} of its own continues its
   * conversation when the scope is entered again (CIB7-2155): its derived id is built on the
   * scope's anchor, the same on every entry, never on the scope execution's own id, which a
   * re-entry recreates. One conversation, under one name, for both entries, at the process
   * instance: the scope's holder survives a re-entry too, but is not where a tool's goes.
   */
  @Test
  public void aToolOfAPerActivityScopeContinuesItsConversationWhenTheScopeIsEnteredAgain() {
    ProcessInstance pi = start(perActivityLoopModel());
    script.add(toolCall("berater"));
    script.add(answer("Noted entry one."));
    script.add(answer("Done with entry one."));
    TurnSteps.runToTheEnd(engine, pi.getId());
    completeFeedback(pi, false, "second round");
    script.add(toolCall("berater"));
    script.add(answer("Noted entry two."));
    script.add(answer("Done with entry two."));
    TurnSteps.runToTheEnd(engine, pi.getId());
    completeFeedback(pi, true, "fine");

    assertThat(requestBodies).as("the scope, the tool, the scope again, in each entry").hasSize(6);
    assertThat(requestBodies.get(4)).as("entry 2's tool request continues entry 1's")
        .contains("Advise on the case.")
        .contains("Noted entry one.");
    List<HistoricVariableInstance> toolConversations = new ArrayList<>();
    for (HistoricVariableInstance variable : engine.getHistoryService()
        .createHistoricVariableInstanceQuery().processInstanceId(pi.getId())
        .includeDeleted().list()) {
      if (variable.getName().endsWith(":tool:berater")) {
        toolConversations.add(variable);
      }
    }
    assertThat(toolConversations).as("one tool conversation for both entries, ever")
        .extracting(HistoricVariableInstance::getName)
        .containsExactly(AgentConnectorConstants.AGENT_CONNECTOR_MEMORY_PREFIX
            + "adhoc-" + pi.getId() + "-adHoc:tool:berater");
    assertThat(toolConversations.get(0).getExecutionId()).as("at the process instance")
        .isEqualTo(pi.getId());
  }

  // --- a task without a memoryId in a multi-instance scope ---------------------------

  /**
   * The instances of a parallel multi-instance agentic scope each call the same AI agent tool,
   * which has no {@code memoryId}: each call sees its own instance's conversation only. Kept at
   * the process level under a key every instance shares, one instance would read the other's.
   */
  @Test
  public void theInstancesOfAParallelScopeDoNotShareTheConversationOfATool() {
    responder = request -> request.contains("Handle case")
        ? (TOOL_RESULT.matcher(request).find() ? answer("Done.") : toolCall("berater"))
        : answer("Noted.");

    ProcessInstance pi = start(agenticMultiInstanceModel());
    TurnSteps.runToTheEnd(engine, pi.getId());

    List<String> calls = requestsOf("advise me");
    assertThat(calls).hasSize(2);
    assertThat(calls).as("one tool call carries instance 0's message, and not instance 1's")
        .filteredOn(call -> call.contains("Case 0: advise me.")).hasSize(1)
        .noneMatch(call -> call.contains("Case 1: advise me."));
    assertThat(calls).as("the other carries instance 1's message, and not instance 0's")
        .filteredOn(call -> call.contains("Case 1: advise me.")).hasSize(1)
        .noneMatch(call -> call.contains("Case 0: advise me."));
  }

  /** The same for a plain scope: the instances do not share the task's conversation. */
  @Test
  public void theInstancesOfAParallelPlainScopeDoNotShareTheConversationOfATask() {
    script.add(answer("Noted."));
    script.add(answer("Noted."));

    ProcessInstance pi = start(plainMultiInstanceModel(false));
    activateInInstance(pi, 0);
    activateInInstance(pi, 1);

    assertThat(requestBodies).hasSize(2);
    assertThat(requestBodies.get(1)).as("instance 1 does not see instance 0's conversation")
        .contains("Case 1: remember this.")
        .doesNotContain("Case 0: remember this.");
  }

  /**
   * A task without {@code memoryId} in a plain scope starts a new conversation on every call, as it
   * does outside any scope: a plain scope has no agent whose tools need an id derived from it.
   */
  @Test
  public void aTaskWithoutMemoryIdInAPlainScopeStartsANewConversationOnEveryCall() {
    script.add(answer("Noted, Berlin."));
    script.add(answer("Noted."));

    ProcessInstance pi = start(plainModelWithoutMemoryId());
    // The waiting user task keeps the scope open for the second call.
    activate(pi, "inside", "waits");
    activate(pi, "inside");

    assertThat(requestBodies).hasSize(2);
    assertThat(requestBodies.get(1)).as("the second call does not continue the first")
        .doesNotContain("Noted, Berlin.");
    assertThat(engine.getRuntimeService().createVariableInstanceQuery()
        .processInstanceIdIn(pi.getId())
        .variableNameLike(AgentConnectorConstants.AGENT_CONNECTOR_MEMORY_PREFIX + "%").list())
        .as("one conversation per call, each under a generated id, none derived from the scope")
        .hasSize(2)
        .extracting(VariableInstance::getName)
        .noneMatch(name -> name.contains("adhoc-"));
  }

  /** Each entry of a scope starts the conversation of a task without {@code memoryId} anew. */
  @Test
  public void eachEntryOfAScopeStartsTheConversationOfATaskWithoutMemoryIdAnew() {
    script.add(answer("Noted."));
    script.add(answer("Noted."));

    ProcessInstance pi = start(plainMultiInstanceModel(true));
    activate(pi, "inside");
    activate(pi, "inside");

    assertThat(requestBodies).hasSize(2);
    assertThat(requestBodies.get(1)).as("entry 1 does not see entry 0's conversation")
        .contains("Case 1: remember this.")
        .doesNotContain("Case 0: remember this.");
  }

  // --- running it -----------------------------------------------------------------

  private ProcessInstance start(String model) {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName("chat-memory-outlives-scope");
    configuration.setJdbcUrl("jdbc:h2:mem:chat-memory-outlives-scope;DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    configuration.setHistoryTimeToLive("P30D");
    configuration.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
    configuration.setProcessEnginePlugins(
        Collections.<ProcessEnginePlugin>singletonList(new ConnectProcessEnginePlugin()));
    engine = configuration.buildProcessEngine();

    engine.getRepositoryService().createDeployment()
        .addString("chat-memory-outlives-scope.bpmn20.xml", model)
        .deploy();
    return engine.getRuntimeService().startProcessInstanceByKey("memoryOutlivesScope",
        Variables.createVariables().putValue("memoryId", MEMORY_ID));
  }

  /** Starts {@code activityIds} in the plain scope, the way an application drives one. */
  private void activate(ProcessInstance pi, String... activityIds) {
    engine.getRuntimeService().activateAdHocSubProcessActivities(scopeExecutionId(pi),
        Arrays.asList(activityIds));
  }

  /**
   * Starts {@code inside} in one instance of a parallel multi-instance scope, named by the
   * execution that carries its {@code loopCounter}.
   */
  private void activateInInstance(ProcessInstance pi, int loopCounter) {
    Execution instance = engine.getRuntimeService().createExecutionQuery()
        .processInstanceId(pi.getId()).variableValueEquals("loopCounter", loopCounter)
        .singleResult();
    engine.getRuntimeService().activateAdHocSubProcessActivities(instance.getId(),
        Collections.singletonList("inside"));
  }

  /** Completes the feedback task: back into the scope, or on when {@code satisfied}. */
  private void completeFeedback(ProcessInstance pi, boolean satisfied, String feedback) {
    Task task = engine.getTaskService().createTaskQuery()
        .processInstanceId(pi.getId()).taskDefinitionKey("feedback").singleResult();
    assertThat(task).as("the feedback task is waiting").isNotNull();
    engine.getTaskService().complete(task.getId(), Variables.createVariables()
        .putValue("satisfied", satisfied).putValue("feedback", feedback));
  }

  /** The requests carrying {@code text}, other than the agentic scope's own. */
  private List<String> requestsOf(String text) {
    List<String> requests = new ArrayList<>();
    for (String body : requestBodies) {
      if (body.contains(text) && !body.contains("Handle case")) {
        requests.add(body);
      }
    }
    return requests;
  }

  private String scopeExecutionId(ProcessInstance pi) {
    return engine.getRuntimeService().createExecutionQuery()
        .processInstanceId(pi.getId()).activityId("adHoc").singleResult().getId();
  }

  /** The one conversation under {@link #MEMORY_ID}, wherever it is kept. */
  private VariableInstance memoryVariable(ProcessInstance pi) {
    List<VariableInstance> variables = engine.getRuntimeService().createVariableInstanceQuery()
        .processInstanceIdIn(pi.getId()).variableName(MEMORY_VARIABLE).list();
    assertThat(variables).as("one conversation under '" + MEMORY_ID + "'").hasSize(1);
    return variables.get(0);
  }

  // --- the models -----------------------------------------------------------------

  /** An AI agent task with the shared {@code memoryId}. */
  private String agentTask(String id, String message) {
    return agentTask(id, message, true);
  }

  /**
   * An AI agent task with chat memory, and with the shared {@code memoryId} as the documented
   * human-in-the-loop pattern writes it, or with none.
   */
  private String agentTask(String id, String message, boolean sharedMemoryId) {
    return "<serviceTask id='" + id + "' name='" + id + "'>"
        + "  <extensionElements><camunda:connector>"
        + "    <camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "    <camunda:inputOutput>"
        + "      <camunda:inputParameter name='agentName'>" + id + "</camunda:inputParameter>"
        + "      <camunda:inputParameter name='instruction'>You are a helpful assistant."
        + "</camunda:inputParameter>"
        + "      <camunda:inputParameter name='message'>" + message + "</camunda:inputParameter>"
        + "      <camunda:inputParameter name='useChatMemory'>true</camunda:inputParameter>"
        + (sharedMemoryId
            ? "      <camunda:inputParameter name='memoryId'>${execution.getVariable('memoryId')}"
                + "</camunda:inputParameter>"
            : "")
        + model()
        + "    </camunda:inputOutput>"
        + "  </camunda:connector></extensionElements>"
        + "</serviceTask>";
  }

  private String model() {
    return "<camunda:inputParameter name='model'>stub</camunda:inputParameter>"
        + "<camunda:inputParameter name='apiKey'>stub-key</camunda:inputParameter>"
        + "<camunda:inputParameter name='baseUrl'>http://127.0.0.1:" + stubPort
        + "/v1</camunda:inputParameter>";
  }

  /**
   * {@code before} (optional) → a plain ad hoc sub process holding {@code inside} and a user task
   * → {@code after} → a user task that keeps the instance and its variables for the assertions.
   */
  private String plainModel(boolean withBefore) {
    String first = withBefore ? "before" : "adHoc";
    return definitions(
        "<startEvent id='start' />"
        + "<sequenceFlow id='f1' sourceRef='start' targetRef='" + first + "' />"
        + (withBefore
            ? agentTask("before", "My name is Alice.")
                + "<sequenceFlow id='f0' sourceRef='before' targetRef='adHoc' />"
            : "")
        + "<adHocSubProcess id='adHoc'>"
        + agentTask("inside", "I live in Berlin.")
        + "  <userTask id='waits' />"
        + "</adHocSubProcess>"
        + "<sequenceFlow id='f2' sourceRef='adHoc' targetRef='after' />"
        + agentTask("after", "What do you know about me?")
        + "<sequenceFlow id='f3' sourceRef='after' targetRef='wait' />"
        + "<userTask id='wait' />"
        + "<sequenceFlow id='f4' sourceRef='wait' targetRef='end' />"
        + "<endEvent id='end' />");
  }

  /** A plain ad hoc sub process holding {@code inside}, without {@code memoryId}, and a user task. */
  private String plainModelWithoutMemoryId() {
    return definitions(
        "<startEvent id='start' />"
        + "<sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + "<adHocSubProcess id='adHoc'>"
        + agentTask("inside", "I live in Berlin.", false)
        + "  <userTask id='waits' />"
        + "</adHocSubProcess>"
        + "<sequenceFlow id='f2' sourceRef='adHoc' targetRef='wait' />"
        + "<userTask id='wait' />"
        + "<sequenceFlow id='f3' sourceRef='wait' targetRef='end' />"
        + "<endEvent id='end' />");
  }

  /**
   * An agentic scope whose one tool, {@code berater}, is an AI agent task with or without the
   * shared {@code memoryId}, then {@code after} with it.
   */
  private String agenticModel(boolean toolHasMemoryId) {
    return definitions(
        "<startEvent id='start' />"
        + "<sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + "<adHocSubProcess id='adHoc'>"
        + "  <extensionElements><camunda:connector>"
        + "    <camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "    <camunda:inputOutput>"
        + "      <camunda:inputParameter name='message'>" + SCOPE_TASK + "</camunda:inputParameter>"
        + model()
        + "    </camunda:inputOutput>"
        + "  </camunda:connector></extensionElements>"
        + agentTask("berater", "I live in Berlin.", toolHasMemoryId)
        + "</adHocSubProcess>"
        + "<sequenceFlow id='f2' sourceRef='adHoc' targetRef='after' />"
        + agentTask("after", "Where do I live?")
        + "<sequenceFlow id='f3' sourceRef='after' targetRef='wait' />"
        + "<userTask id='wait' />"
        + "<sequenceFlow id='f4' sourceRef='wait' targetRef='end' />"
        + "<endEvent id='end' />");
  }

  /**
   * A perActivity agentic scope whose one tool, {@code berater}, is an AI agent task without
   * {@code memoryId}, then a feedback task that leads back into the scope until {@code satisfied}.
   */
  private String perActivityLoopModel() {
    return definitions(
        "<startEvent id='start' />"
        + "<sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + perActivityScope("adHoc", "Handle the case.",
            agentTask("berater", "Advise on the case.", false))
        + "<sequenceFlow id='f2' sourceRef='adHoc' targetRef='feedback' />"
        + "<userTask id='feedback' />"
        + "<sequenceFlow id='f3' sourceRef='feedback' targetRef='gw' />"
        + "<exclusiveGateway id='gw' />"
        + "<sequenceFlow id='again' sourceRef='gw' targetRef='adHoc'>"
        + "  <conditionExpression xsi:type='tFormalExpression'>${!satisfied}</conditionExpression>"
        + "</sequenceFlow>"
        + "<sequenceFlow id='done' sourceRef='gw' targetRef='wait'>"
        + "  <conditionExpression xsi:type='tFormalExpression'>${satisfied}</conditionExpression>"
        + "</sequenceFlow>"
        + "<userTask id='wait' />"
        + "<sequenceFlow id='f4' sourceRef='wait' targetRef='end' />"
        + "<endEvent id='end' />");
  }

  /**
   * Two perActivity agentic scopes one after the other: {@code case}, whose one tool is
   * {@code review}, an AI agent task without {@code memoryId}, then {@code case-tool-review}.
   */
  private String twoPerActivityScopesModel() {
    return definitions(
        "<startEvent id='start' />"
        + "<sequenceFlow id='f1' sourceRef='start' targetRef='case' />"
        + perActivityScope("case", "Handle the first step.",
            agentTask("review", "Review the case.", false))
        + "<sequenceFlow id='f2' sourceRef='case' targetRef='case-tool-review' />"
        + perActivityScope("case-tool-review", "Handle the second step.", "<userTask id='ask' />")
        + "<sequenceFlow id='f3' sourceRef='case-tool-review' targetRef='wait' />"
        + "<userTask id='wait' />"
        + "<sequenceFlow id='f4' sourceRef='wait' targetRef='end' />"
        + "<endEvent id='end' />");
  }

  /**
   * An agentic scope keeping its conversation across entries, with {@code children}; the
   * feedback is its follow-up message when it is entered again.
   */
  private String perActivityScope(String id, String message, String children) {
    return "<adHocSubProcess id='" + id + "'>"
        + "  <extensionElements>"
        + "<camunda:properties><camunda:property name='cibseven.agentic.conversation'"
        + " value='perActivity' /></camunda:properties>"
        + "<camunda:connector>"
        + "    <camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "    <camunda:inputOutput>"
        + "      <camunda:inputParameter name='message'>" + message + "</camunda:inputParameter>"
        + "      <camunda:inputParameter name='followUp'>User feedback: ${feedback}"
        + "</camunda:inputParameter>"
        + model()
        + "    </camunda:inputOutput>"
        + "  </camunda:connector></extensionElements>"
        + children
        + "</adHocSubProcess>";
  }

  /**
   * A plain ad hoc sub process with two multi-instance instances, parallel or sequential, each
   * holding {@code inside}, an AI agent task without {@code memoryId}, and a user task.
   */
  private String plainMultiInstanceModel(boolean sequential) {
    return definitions(
        "<startEvent id='start' />"
        + "<sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + "<adHocSubProcess id='adHoc'>"
        + "  <multiInstanceLoopCharacteristics isSequential='" + sequential + "'>"
        + "<loopCardinality>2</loopCardinality></multiInstanceLoopCharacteristics>"
        + agentTask("inside", "Case ${loopCounter}: remember this.", false)
        + "  <userTask id='waits' />"
        + "</adHocSubProcess>"
        + "<sequenceFlow id='f2' sourceRef='adHoc' targetRef='wait' />"
        + "<userTask id='wait' />"
        + "<sequenceFlow id='f3' sourceRef='wait' targetRef='end' />"
        + "<endEvent id='end' />");
  }

  /**
   * An agentic scope with two parallel multi-instance instances, whose one tool, {@code berater},
   * is an AI agent task without {@code memoryId}.
   */
  private String agenticMultiInstanceModel() {
    return definitions(
        "<startEvent id='start' />"
        + "<sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + "<adHocSubProcess id='adHoc'>"
        + "  <extensionElements><camunda:connector>"
        + "    <camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "    <camunda:inputOutput>"
        + "      <camunda:inputParameter name='message'>Handle case ${loopCounter}."
        + "</camunda:inputParameter>"
        + model()
        + "    </camunda:inputOutput>"
        + "  </camunda:connector></extensionElements>"
        + "  <multiInstanceLoopCharacteristics isSequential='false'>"
        + "<loopCardinality>2</loopCardinality></multiInstanceLoopCharacteristics>"
        + agentTask("berater", "Case ${loopCounter}: advise me.", false)
        + "</adHocSubProcess>"
        + "<sequenceFlow id='f2' sourceRef='adHoc' targetRef='wait' />"
        + "<userTask id='wait' />"
        + "<sequenceFlow id='f3' sourceRef='wait' targetRef='end' />"
        + "<endEvent id='end' />");
  }

  private static String definitions(String process) {
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + "             xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'"
        + "             targetNamespace='http://cibseven.org/chat-memory-outlives-scope'>"
        + "  <process id='memoryOutlivesScope' isExecutable='true'>"
        + process
        + "  </process>"
        + "</definitions>";
  }

  // --- the stub model ---------------------------------------------------------------

  private void respond(HttpExchange exchange) throws IOException {
    String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    requestBodies.add(request);
    String body = (responder != null) ? responder.apply(request) : script.poll();
    if (body == null) {
      body = answer("No script left.");
    }
    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, payload.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(payload);
    }
  }

  private static String answer(String text) {
    return "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,"
        + "\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
        + "\"content\":\"" + text + "\"},\"finish_reason\":\"stop\"}],"
        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
  }

  /** One model answer calling {@code tool}, with no arguments, as a tool. */
  private static String toolCall(String tool) {
    return "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,"
        + "\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
        + "\"content\":null,\"tool_calls\":["
        + "{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"" + tool
        + "\",\"arguments\":\"{}\"}}"
        + "]},"
        + "\"finish_reason\":\"tool_calls\"}],"
        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
  }
}
