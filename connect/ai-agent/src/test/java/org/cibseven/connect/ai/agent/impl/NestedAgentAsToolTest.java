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
import org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * An AI agent task used as a tool inside an agentic scope, the exact shape of CIB7-2155: the
 * outer turn's model calls two tools in one answer — {@code berater}, itself a connector-backed
 * service task with its own chat memory, and {@code pruefen}, a plain user task.
 *
 * <p>{@code berater} runs the connector a second time, synchronously, on the same thread as the
 * outer turn's own call — before that outer call has returned from its own {@code finally}
 * block. Both halves of the bug sit exactly there:
 * <ul>
 *   <li>the two conversations must not collide — each request must carry only its own
 *       messages;</li>
 *   <li>{@code pruefen}, started right after {@code berater} in the same model answer, must not
 *       find the engine context wiped out from under it.</li>
 * </ul>
 */
public class NestedAgentAsToolTest {

  private static final int STUB_PORT = 8199;

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
  public void nestedAgentToolDoesNotShareTheOuterConversationOrBreakItsTools() throws IOException {
    // Step 1 (one job, CIB7-2148): the outer turn's model calls both tools in one answer.
    script.add(twoToolCalls("berater", "pruefen"));
    // berater's own, single-shot connector call — nested inside step 1's tool execution.
    script.add(answer("Hier ist meine fachliche Einschaetzung."));
    // Step 2: the outer turn's next job reports on what finished.
    script.add(answer("Soweit erledigt."));

    ProcessInstance pi = start();
    TurnSteps.runToTheEnd(engine, pi.getId());

    assertThat(requestBodies).hasSize(3);
    // requestBodies.get(0) is the outer turn's own first request, sent before berater has run
    // at all -- it could never contain berater's messages regardless of the bug, so it is not
    // asserted on below.
    String beratersOwnCall = requestBodies.get(1);
    String outerStep2 = requestBodies.get(2);

    // The engine context survived berater's nested call: pruefen actually ran instead of
    // failing with "No ProcessEngine available in ProcessStarterToolContext" (CIB7-2155).
    Task pruefen = engine.getTaskService().createTaskQuery().taskName("Pruefen").singleResult();
    assertThat(pruefen).as("pruefen must have been started, not refused").isNotNull();

    // Each conversation carries only its own messages: berater's distinctive system prompt
    // and user message never reach the outer turn's requests, and -- the symptom actually
    // measured for CIB7-2155 -- the outer turn's own task text never reaches berater's.
    assertThat(beratersOwnCall)
        .contains("Du bist ein unabhaengiger Fachberater.")
        .contains("Berate mich zu diesem Fall.")
        .doesNotContain("Erledige die Anfrage");
    assertThat(outerStep2)
        .doesNotContain("Du bist ein unabhaengiger Fachberater.")
        .doesNotContain("Berate mich zu diesem Fall.");
  }

  // --- model -------------------------------------------------------------------

  private ProcessInstance start() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName("nested-agent-as-tool");
    configuration.setJdbcUrl("jdbc:h2:mem:nested-agent-as-tool;DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    configuration.setHistoryTimeToLive("P30D");
    configuration.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
    configuration.setProcessEnginePlugins(
        Collections.<ProcessEnginePlugin>singletonList(new ConnectProcessEnginePlugin()));
    engine = configuration.buildProcessEngine();

    engine.getRepositoryService().createDeployment()
        .addString("nested-agent-as-tool.bpmn20.xml", model())
        .deploy();
    return engine.getRuntimeService().startProcessInstanceByKey("nestedAgentAsTool");
  }

  private static String model() {
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + "             targetNamespace='http://cibseven.org/nested-agent-as-tool'>"
        + "  <process id='nestedAgentAsTool' isExecutable='true'>"
        + "    <startEvent id='start' />"
        + "    <sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + "    <adHocSubProcess id='adHoc'>"
        + "      <extensionElements><camunda:connector>"
        + "        <camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "        <camunda:inputOutput>"
        + "          <camunda:inputParameter name='message'>"
        + "Erledige die Anfrage, hol dir bei Bedarf eine Fachmeinung ein."
        + "          </camunda:inputParameter>"
        + "          <camunda:inputParameter name='model'>stub</camunda:inputParameter>"
        + "          <camunda:inputParameter name='apiKey'>stub-key-outer</camunda:inputParameter>"
        + "          <camunda:inputParameter name='baseUrl'>http://127.0.0.1:" + STUB_PORT
        + "/v1</camunda:inputParameter>"
        + "        </camunda:inputOutput>"
        + "      </camunda:connector></extensionElements>"
        + "      <serviceTask id='berater' name='Berater'>"
        + "        <extensionElements><camunda:connector>"
        + "          <camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "          <camunda:inputOutput>"
        + "            <camunda:inputParameter name='agentName'>berater</camunda:inputParameter>"
        + "            <camunda:inputParameter name='instruction'>"
        + "Du bist ein unabhaengiger Fachberater."
        + "            </camunda:inputParameter>"
        + "            <camunda:inputParameter name='message'>"
        + "Berate mich zu diesem Fall."
        + "            </camunda:inputParameter>"
        + "            <camunda:inputParameter name='useChatMemory'>true</camunda:inputParameter>"
        + "            <camunda:inputParameter name='model'>stub</camunda:inputParameter>"
        + "            <camunda:inputParameter name='apiKey'>stub-key-berater</camunda:inputParameter>"
        + "            <camunda:inputParameter name='baseUrl'>http://127.0.0.1:" + STUB_PORT
        + "/v1</camunda:inputParameter>"
        + "            <camunda:outputParameter name='beratung'>${output}</camunda:outputParameter>"
        + "          </camunda:inputOutput>"
        + "        </camunda:connector></extensionElements>"
        + "      </serviceTask>"
        + "      <userTask id='pruefen' name='Pruefen' />"
        + "    </adHocSubProcess>"
        + "    <sequenceFlow id='f2' sourceRef='adHoc' targetRef='end' />"
        + "    <endEvent id='end' />"
        + "  </process>"
        + "</definitions>";
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
