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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.history.HistoricVariableInstance;
import org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.connect.ai.agent.AgentConnectorConstants;
import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * The explicit acceptance criterion from CIB7-2155 that {@link NestedAgentAsToolTest} does not
 * cover: "Two AI agent tasks in the same ad hoc scope without {@code memoryId} do not share a
 * conversation." {@code NestedAgentAsToolTest} proves one nested agent task does not collide with
 * the <em>outer</em> turn's own conversation; this proves two nested agent tasks, called as tools
 * of the same outer turn, do not collide with <em>each other</em> -- a different pair of keys
 * ({@code adHocScopeMemoryId()} keyed by each tool's own, distinct calling activity id) than the
 * single-nested-call case already covers.
 */
public class NestedAgentAsToolTwoAgentsTest {

  private static final int STUB_PORT = 8197;

  private HttpServer stub;
  private final ConcurrentLinkedQueue<String> script = new ConcurrentLinkedQueue<>();
  private final List<String> requestBodies = new CopyOnWriteArrayList<>();
  private ProcessEngine engine;

  @BeforeEach
  public void startStub() throws IOException {
    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", STUB_PORT), 0);
    stub.createContext("/v1/chat/completions", this::respond);
    stub.setExecutor(null);
    stub.start();
  }

  @AfterEach
  public void stopEverything() {
    if (engine != null) {
      engine.close();
      engine = null;
    }
    if (stub != null) {
      stub.stop(0);
      stub = null;
    }
  }

  @Test
  public void twoNestedAgentTasksInOneScopeDoNotShareOneConversation() throws IOException {
    // Step 1: the outer turn's model calls both nested agent tasks in one answer.
    script.add(twoToolCalls("berater", "pruefer"));
    // Each tool's own, single-shot connector call -- nested inside step 1's tool execution.
    script.add(answer("Fachliche Einschaetzung von Berater."));
    script.add(answer("Pruefungsergebnis von Pruefer."));
    // Step 2: the outer turn's next job reports on what finished.
    script.add(answer("Soweit erledigt."));

    ProcessInstance pi = start();
    TurnSteps.runToTheEnd(engine, pi.getId());

    assertThat(requestBodies).hasSize(4);
    String beratersCall = requestBodies.get(1);
    String pruefersCall = requestBodies.get(2);
    String outerStep2 = requestBodies.get(3);

    // Each nested tool's own request carries only its own messages -- not its sibling's, and
    // not the outer turn's.
    assertThat(beratersCall)
        .contains("Du bist ein unabhaengiger Fachberater.")
        .doesNotContain("Du bist ein gruendlicher Pruefer.");
    assertThat(pruefersCall)
        .contains("Du bist ein gruendlicher Pruefer.")
        .doesNotContain("Du bist ein unabhaengiger Fachberater.");
    assertThat(outerStep2)
        .doesNotContain("Du bist ein unabhaengiger Fachberater.")
        .doesNotContain("Du bist ein gruendlicher Pruefer.");

    // The two tools' conversations persisted as two distinct process variables -- not one
    // variable that the second tool's write silently overwrote. Narrowed to the nested tools'
    // own variables (":tool:<activityId>"): the outer scope keeps its own conversation under a
    // separate name (AgenticTurnRunner.memoryId), which the plain prefix would also match. Read
    // from history, not runtime: the process instance, which keeps them (CIB7-2232), has ended
    // with the turn.
    List<HistoricVariableInstance> memoryVariables = engine.getHistoryService()
        .createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId())
        .variableNameLike(AgentConnectorConstants.AGENT_CONNECTOR_MEMORY_PREFIX + "%:tool:%")
        .list();
    assertThat(memoryVariables).as("one memory variable per nested agent task").hasSize(2);
    assertThat(memoryVariables).extracting(HistoricVariableInstance::getName)
        .as("each tool keyed by its own calling activity id, not a shared key")
        .doesNotHaveDuplicates();
  }

  // --- model -------------------------------------------------------------------

  private ProcessInstance start() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName("nested-agent-as-tool-two-agents");
    configuration.setJdbcUrl("jdbc:h2:mem:nested-agent-as-tool-two-agents;DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    configuration.setHistoryTimeToLive("P30D");
    configuration.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
    configuration.setProcessEnginePlugins(
        Collections.<ProcessEnginePlugin>singletonList(new ConnectProcessEnginePlugin()));
    engine = configuration.buildProcessEngine();

    engine.getRepositoryService().createDeployment()
        .addString("nested-agent-as-tool-two-agents.bpmn20.xml", model())
        .deploy();
    return engine.getRuntimeService().startProcessInstanceByKey("nestedAgentAsToolTwoAgents");
  }

  private static String model() {
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + "             targetNamespace='http://cibseven.org/nested-agent-as-tool-two-agents'>"
        + "  <process id='nestedAgentAsToolTwoAgents' isExecutable='true'>"
        + "    <startEvent id='start' />"
        + "    <sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + "    <adHocSubProcess id='adHoc'>"
        + "      <extensionElements><camunda:connector>"
        + "        <camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "        <camunda:inputOutput>"
        + "          <camunda:inputParameter name='message'>"
        + "Erledige die Anfrage, hol dir bei Bedarf zwei Fachmeinungen ein."
        + "          </camunda:inputParameter>"
        + "          <camunda:inputParameter name='model'>stub</camunda:inputParameter>"
        + "          <camunda:inputParameter name='apiKey'>stub-key-outer</camunda:inputParameter>"
        + "          <camunda:inputParameter name='baseUrl'>http://127.0.0.1:" + STUB_PORT
        + "/v1</camunda:inputParameter>"
        + "        </camunda:inputOutput>"
        + "      </camunda:connector></extensionElements>"
        + agentServiceTask("berater", "Berater", "Du bist ein unabhaengiger Fachberater.",
            "Berate mich zu diesem Fall.")
        + agentServiceTask("pruefer", "Pruefer", "Du bist ein gruendlicher Pruefer.",
            "Pruefe diesen Fall.")
        + "    </adHocSubProcess>"
        + "    <sequenceFlow id='f2' sourceRef='adHoc' targetRef='end' />"
        + "    <endEvent id='end' />"
        + "  </process>"
        + "</definitions>";
  }

  /** One nested AI-agent-connector service task, with its own instruction and message. */
  private static String agentServiceTask(String id, String name, String instruction, String message) {
    return "      <serviceTask id='" + id + "' name='" + name + "'>"
        + "        <extensionElements><camunda:connector>"
        + "          <camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "          <camunda:inputOutput>"
        + "            <camunda:inputParameter name='agentName'>" + id + "</camunda:inputParameter>"
        + "            <camunda:inputParameter name='instruction'>" + instruction
        + "            </camunda:inputParameter>"
        + "            <camunda:inputParameter name='message'>" + message
        + "            </camunda:inputParameter>"
        + "            <camunda:inputParameter name='useChatMemory'>true</camunda:inputParameter>"
        + "            <camunda:inputParameter name='model'>stub</camunda:inputParameter>"
        + "            <camunda:inputParameter name='apiKey'>stub-key-" + id + "</camunda:inputParameter>"
        + "            <camunda:inputParameter name='baseUrl'>http://127.0.0.1:" + STUB_PORT
        + "/v1</camunda:inputParameter>"
        + "            <camunda:outputParameter name='ergebnis'>${output}</camunda:outputParameter>"
        + "          </camunda:inputOutput>"
        + "        </camunda:connector></extensionElements>"
        + "      </serviceTask>";
  }

  // --- the stub model --------------------------------------------------------

  private void respond(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
    requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    String body = script.poll();
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

  /** One model answer calling both {@code first} and {@code second} as tools, in that order. */
  private static String twoToolCalls(String first, String second) {
    return "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,"
        + "\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
        + "\"content\":null,\"tool_calls\":["
        + "{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"" + first
        + "\",\"arguments\":\"{}\"}},"
        + "{\"id\":\"call_2\",\"type\":\"function\",\"function\":{\"name\":\"" + second
        + "\",\"arguments\":\"{}\"}}"
        + "]},"
        + "\"finish_reason\":\"tool_calls\"}],"
        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
  }
}
