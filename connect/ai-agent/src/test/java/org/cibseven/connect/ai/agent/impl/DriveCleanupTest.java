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

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.ProcessEnginePersistenceException;
import org.cibseven.bpm.engine.delegate.BpmnError;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.ExecutionListener;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.runtime.VariableInstance;
import org.cibseven.connect.ScriptedAgent;
import org.cibseven.connect.ai.agent.AgentConnectorConstants;
import org.cibseven.connect.ai.agent.agentic.AdHocConversation;
import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

/**
 * The driving agent's call ends with the turn runner's call to the model ({@code
 * AdHocConversation.drive}), even where nothing else would show a value left behind on the
 * thread (CIB7-2232).
 *
 * <p>The turn writes the conversation, starts nothing, and the scope ends in the same command. Its
 * end listener then runs on the very same scope execution the turn drove, so a thread-local still
 * holding it would let the listener's call pass for the driving agent's, and read the scope's own
 * conversation from where the tools cannot reach it. The turn runs on a thread of its own, as the
 * first turn of a job executor thread would: on a thread that drove scopes before, a value left
 * behind would only ever be restored, never seen.
 */
public class DriveCleanupTest {

  private ProcessEngine engine;
  private ChatMemoryStore previousStore;

  /** What the scope's end listener got back from the store. */
  static volatile List<ChatMessage> seenByListener;

  /** An end listener on the scope reading the scope's own conversation through the store. */
  public static class ListenerReadsTheConversation implements ExecutionListener {
    @Override
    public void notify(DelegateExecution execution) {
      seenByListener = AgentChatMemoryStore.getStore()
          .getMessages(AdHocConversation.memoryId((ExecutionEntity) execution));
    }
  }

  @BeforeEach
  public void setUp() {
    seenByListener = null;
    previousStore = AgentChatMemoryStore.getStore();
    AgentChatMemoryStore.setStore(new ProcessVariableChatMemoryStore());
  }

  @AfterEach
  public void tearDown() {
    ScriptedAgent.uninstall();
    AgentChatMemoryStore.setStore(previousStore);
    if (engine != null) {
      engine.close();
      engine = null;
    }
  }

  /**
   * perActivity, so the conversation is on the holder and stays there after the scope. The end
   * listener is not the driving agent, so it reads the process level, where nothing is.
   */
  @Test
  public void anEndListenerOnTheScopeInTheTurnsCommandIsNotTheDrivingAgent() throws Throwable {
    ProcessInstance pi = start(scope -> AgentChatMemoryStore.getStore().updateMessages(
        AdHocConversation.memoryId(scope),
        Arrays.<ChatMessage>asList(UserMessage.from("My name is Alice."),
            AiMessage.from("Noted, Alice."))));
    List<Job> jobs = engine.getManagementService().createJobQuery()
        .processInstanceId(pi.getId()).list();
    assertThat(jobs).hasSize(1);
    onAFreshThread(() -> engine.getManagementService().executeJob(jobs.get(0).getId()));

    assertThat(engine.getRuntimeService().createExecutionQuery()
        .processInstanceId(pi.getId()).activityId("adHoc").count())
        .as("the scope has ended").isZero();
    String name = AgentConnectorConstants.AGENT_CONNECTOR_MEMORY_PREFIX
        + "adhoc-" + pi.getId() + "-adHoc";
    List<VariableInstance> kept = engine.getRuntimeService().createVariableInstanceQuery()
        .processInstanceIdIn(pi.getId()).variableName(name).list();
    assertThat(kept).as("the conversation is kept on the holder").hasSize(1);
    assertThat(kept.get(0).getExecutionId()).isNotEqualTo(pi.getId());

    assertThat(seenByListener).as("the end listener ran").isNotNull();
    assertThat(seenByListener).as("and was not taken for the driving agent").isEmpty();
  }

  /** A tool that fails with a BPMN error, which the boundary event on the scope catches. */
  public static class ToolThrowsBpmnError implements JavaDelegate {
    @Override
    public void execute(DelegateExecution execution) {
      throw new BpmnError("refused");
    }
  }

  /**
   * The same listener, but the scope ends within the turn runner's call: the tool the model
   * called throws a BPMN error, the boundary event on the scope catches it, and the scope is
   * cancelled while the tool still runs, before the model call returns. The listener runs on the
   * scope execution the turn drives, and is still not the driving agent: tools run outside the
   * driving agent's call (CIB7-2232). Real connector and a stub model, so the tool runs the way
   * the model's tool calls do.
   */
  @Test
  public void anEndListenerRunWhileAToolRunsIsNotTheDrivingAgent() throws Throwable {
    ConcurrentLinkedQueue<String> answers = new ConcurrentLinkedQueue<>(
        Arrays.asList(toolCall("kaputt"), answer("Done.")));
    HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext("/v1/chat/completions", exchange -> {
      exchange.getRequestBody().readAllBytes();
      String body = answers.poll();
      byte[] payload = (body == null ? answer("No script left.") : body).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, payload.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(payload);
      }
    });
    stub.start();
    try {
      ProcessInstance pi = deploy(modelWithAFailingTool(stub.getAddress().getPort()));
      List<Job> jobs = engine.getManagementService().createJobQuery()
          .processInstanceId(pi.getId()).list();
      assertThat(jobs).hasSize(1);
      try {
        onAFreshThread(() -> engine.getManagementService().executeJob(jobs.get(0).getId()));
      } catch (ProcessEnginePersistenceException knownAfterwards) {
        // On an engine without the fix of CIB7-2264 the step then fails on writing what the
        // cancellation left behind, and is rolled back: a tool's BPMN error caught on the agentic
        // scope fails into an incident there. That is the engine's, not this test's; any other
        // failure after the tool is, and fails it.
      }
      assertThat(seenByListener).as("the end listener ran, while the tool ran").isNotNull();
      assertThat(seenByListener).as("and was not taken for the driving agent").isEmpty();
    } finally {
      stub.stop(0);
    }
  }

  interface Turn {
    void run(ExecutionEntity scope);
  }

  /** Runs {@code body} on a new thread, whose thread-locals start empty, and rethrows. */
  private static void onAFreshThread(Runnable body) throws Throwable {
    Throwable[] failure = new Throwable[1];
    Thread thread = new Thread(() -> {
      try {
        body.run();
      } catch (Throwable t) {
        failure[0] = t;
      }
    }, "drive-cleanup");
    thread.start();
    thread.join();
    if (failure[0] != null) {
      throw failure[0];
    }
  }

  private ProcessInstance start(Turn turn) {
    buildEngine();
    ScriptedAgent.install(parameters -> {
      turn.run(Context.getExecutionContext().getExecution());
      return "done";
    });
    return deployAndStart(model());
  }

  private ProcessInstance deploy(String model) {
    buildEngine();
    return deployAndStart(model);
  }

  private void buildEngine() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName("drive-cleanup");
    configuration.setJdbcUrl("jdbc:h2:mem:drive-cleanup;DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    configuration.setHistoryTimeToLive("P30D");
    configuration.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
    configuration.setProcessEnginePlugins(
        Collections.<ProcessEnginePlugin>singletonList(new ConnectProcessEnginePlugin()));
    engine = configuration.buildProcessEngine();
  }

  private ProcessInstance deployAndStart(String model) {
    engine.getRepositoryService().createDeployment()
        .addString("drive-cleanup.bpmn20.xml", model).deploy();
    return engine.getRuntimeService().startProcessInstanceByKey("driveCleanup");
  }

  /** A perActivity agentic scope with an end listener and a child it never starts. */
  private static String model() {
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + " xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + " targetNamespace='http://cibseven.org/drive-cleanup'>"
        + "<process id='driveCleanup' isExecutable='true'>"
        + "  <startEvent id='start' />"
        + "  <sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + "  <adHocSubProcess id='adHoc'>"
        + "    <extensionElements>"
        + "      <camunda:properties><camunda:property name='cibseven.agentic.conversation'"
        + " value='perActivity' /></camunda:properties>"
        + "      <camunda:executionListener event='end' class='"
        + ListenerReadsTheConversation.class.getName() + "' />"
        + "      <camunda:connector>"
        + "      <camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "      <camunda:inputOutput>"
        + "        <camunda:inputParameter name='message'>Do the work.</camunda:inputParameter>"
        + "      </camunda:inputOutput>"
        + "    </camunda:connector></extensionElements>"
        + "    <userTask id='waits' />"
        + "  </adHocSubProcess>"
        + "  <sequenceFlow id='f2' sourceRef='adHoc' targetRef='wait' />"
        + "  <userTask id='wait' />"
        + "  <sequenceFlow id='f3' sourceRef='wait' targetRef='end' />"
        + "  <endEvent id='end' />"
        + "</process></definitions>";
  }

  /**
   * {@link #model()} with the real connector against the stub model, a tool that throws a BPMN
   * error, and an error boundary event on the scope that catches it.
   */
  private static String modelWithAFailingTool(int stubPort) {
    return model()
        .replace("        <camunda:inputParameter name='message'>Do the work.</camunda:inputParameter>",
            "        <camunda:inputParameter name='message'>Do the work.</camunda:inputParameter>"
            + "        <camunda:inputParameter name='model'>stub</camunda:inputParameter>"
            + "        <camunda:inputParameter name='apiKey'>stub-key</camunda:inputParameter>"
            + "        <camunda:inputParameter name='baseUrl'>http://127.0.0.1:" + stubPort
            + "/v1</camunda:inputParameter>")
        .replace("    <userTask id='waits' />",
            "    <serviceTask id='kaputt' name='Kaputt' camunda:class='"
            + ToolThrowsBpmnError.class.getName() + "' />")
        .replace("  <sequenceFlow id='f2' sourceRef='adHoc' targetRef='wait' />",
            "  <boundaryEvent id='refused' attachedToRef='adHoc'><errorEventDefinition /></boundaryEvent>"
            + "  <sequenceFlow id='f2' sourceRef='adHoc' targetRef='wait' />"
            + "  <sequenceFlow id='f4' sourceRef='refused' targetRef='wait' />");
  }

  private static String answer(String text) {
    return "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,"
        + "\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
        + "\"content\":\"" + text + "\"},\"finish_reason\":\"stop\"}],"
        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
  }

  /** One model answer calling {@code tool}, with no arguments. */
  private static String toolCall(String tool) {
    return "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,"
        + "\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
        + "\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
        + "\"function\":{\"name\":\"" + tool + "\",\"arguments\":\"{}\"}}]},"
        + "\"finish_reason\":\"tool_calls\"}],"
        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
  }
}
