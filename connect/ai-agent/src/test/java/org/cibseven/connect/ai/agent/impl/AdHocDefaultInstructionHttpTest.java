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
package org.cibseven.connect.ai.agent.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
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
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.connect.ai.agent.AgentConnectorConstants;
import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Which bundled prompt the model reads (CIB7-2235), through the real connector and a stub
 * OpenAI-compatible endpoint. The service task's prompt tells the agent to answer with a
 * clarifying question; in an agentic scope that answer ends the turn, and with nothing running
 * the scope, so the question reaches no one. An agentic scope's turn reads a prompt of its own;
 * a service task keeps the one it had.
 */
public class AdHocDefaultInstructionHttpTest {

  private static final int STUB_PORT = 8097;

  private static final String SERVICE_TASK_PROMPT =
      "/org/cibseven/connect/ai/agent/default-instruction.txt";
  private static final String AD_HOC_PROMPT =
      "/org/cibseven/connect/ai/agent/default-instruction-adhoc.txt";

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

  private void respond(HttpExchange exchange) throws IOException {
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

  /**
   * Without an instruction the agent reads the scope's own prompt alone. It keeps none of the
   * service task's sentences that send the answer on as if a next step read it.
   */
  @Test
  public void anAgentWithoutAnInstructionReadsTheAdHocPrompt() throws IOException {
    script.add(answer("Nothing to do."));

    runToTheEnd(start(adHocModel("")));

    assertThat(requestBodies).hasSize(1);
    String system = systemMessage(requestBodies.get(0));
    assertThat(system).as("not the service task's prompt")
        .doesNotContain("service task")
        .doesNotContain("clarifying question")
        .doesNotContain("consumed by the next BPMN step")
        .contains("completeScope");
    assertThat(system).isEqualTo(resource(AD_HOC_PROMPT));
  }

  /** The template presets append: the modeller's instruction follows the scope's own prompt. */
  @Test
  public void underAppendTheInstructionFollowsTheAdHocPrompt() throws IOException {
    script.add(answer("Nothing to do."));

    runToTheEnd(start(adHocModel(
        "          <camunda:inputParameter name='instruction'>Answer in German.</camunda:inputParameter>"
        + "          <camunda:inputParameter name='instructionMode'>append</camunda:inputParameter>")));

    String system = systemMessage(requestBodies.get(0));
    assertThat(system).as("not the service task's prompt").doesNotContain("service task");
    assertThat(system).isEqualTo(resource(AD_HOC_PROMPT)
        + AgentConnectorConstants.INSTRUCTION_MODE_SEPARATOR + "Answer in German.");
  }

  @Test
  public void aServiceTaskKeepsItsOwnPrompt() throws IOException {
    script.add(answer("Done."));

    start(serviceTaskModel());

    assertThat(requestBodies).hasSize(1);
    assertThat(systemMessage(requestBodies.get(0))).isEqualTo(resource(SERVICE_TASK_PROMPT));
  }

  // --- the models ------------------------------------------------------------------

  private static String adHocModel(String moreInputs) {
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + "             targetNamespace='http://cibseven.org/agentic-default-instruction'>"
        + "  <process id='prompt' isExecutable='true'>"
        + "    <startEvent id='start' />"
        + "    <sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + "    <adHocSubProcess id='adHoc'>"
        + "      <extensionElements><camunda:connector>"
        + "        <camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "        <camunda:inputOutput>"
        + "          <camunda:inputParameter name='message'>Handle the request.</camunda:inputParameter>"
        + connection()
        + moreInputs
        + "        </camunda:inputOutput>"
        + "      </camunda:connector></extensionElements>"
        + "      <serviceTask id='worker' camunda:expression='${\"handled\"}' camunda:resultVariable='status' />"
        + "    </adHocSubProcess>"
        + "    <sequenceFlow id='f2' sourceRef='adHoc' targetRef='end' />"
        + "    <endEvent id='end' />"
        + "  </process>"
        + "</definitions>";
  }

  private static String serviceTaskModel() {
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + "             targetNamespace='http://cibseven.org/agentic-default-instruction'>"
        + "  <process id='prompt' isExecutable='true'>"
        + "    <startEvent id='start' />"
        + "    <sequenceFlow id='f1' sourceRef='start' targetRef='agent' />"
        + "    <serviceTask id='agent'>"
        + "      <extensionElements><camunda:connector>"
        + "        <camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "        <camunda:inputOutput>"
        + "          <camunda:inputParameter name='agentName'>agent</camunda:inputParameter>"
        + "          <camunda:inputParameter name='message'>Handle the request.</camunda:inputParameter>"
        + connection()
        + "        </camunda:inputOutput>"
        + "      </camunda:connector></extensionElements>"
        + "    </serviceTask>"
        + "    <sequenceFlow id='f2' sourceRef='agent' targetRef='end' />"
        + "    <endEvent id='end' />"
        + "  </process>"
        + "</definitions>";
  }

  private static String connection() {
    return "          <camunda:inputParameter name='model'>stub</camunda:inputParameter>"
        + "          <camunda:inputParameter name='apiKey'>stub-key</camunda:inputParameter>"
        + "          <camunda:inputParameter name='baseUrl'>http://127.0.0.1:" + STUB_PORT
        + "/v1</camunda:inputParameter>";
  }

  // --- driving it ------------------------------------------------------------------

  private ProcessInstance start(String bpmn) {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName("agentic-default-instruction-http");
    configuration.setJdbcUrl("jdbc:h2:mem:agentic-default-instruction-http;DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    configuration.setHistoryTimeToLive("P30D");
    configuration.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
    configuration.setProcessEnginePlugins(
        Collections.<ProcessEnginePlugin>singletonList(new ConnectProcessEnginePlugin()));
    engine = configuration.buildProcessEngine();
    engine.getRepositoryService().createDeployment()
        .addString("default-instruction.bpmn20.xml", bpmn).deploy();
    return engine.getRuntimeService().startProcessInstanceByKey("prompt");
  }

  /** Runs every waiting step, each a job of its own (CIB7-2148). */
  private void runToTheEnd(ProcessInstance instance) {
    for (int steps = 0; steps < 60; steps++) {
      List<Job> jobs = engine.getManagementService().createJobQuery()
          .processInstanceId(instance.getId()).list();
      if (jobs.isEmpty()) {
        return;
      }
      engine.getManagementService().executeJob(jobs.get(0).getId());
    }
    throw new AssertionError("more than 60 steps, the turn does not end");
  }

  private static String answer(String text) {
    return "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,"
        + "\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
        + "\"content\":\"" + text + "\"},\"finish_reason\":\"stop\"}],"
        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
  }

  /** The text of the system message of a chat completion request. */
  private static String systemMessage(String requestBody) throws IOException {
    for (JsonNode message : new ObjectMapper().readTree(requestBody).path("messages")) {
      if ("system".equals(message.path("role").asText())) {
        return message.path("content").asText();
      }
    }
    return null;
  }

  private static String resource(String name) throws IOException {
    try (InputStream in = AdHocDefaultInstructionHttpTest.class.getResourceAsStream(name)) {
      assertThat(in).as(name + " on the classpath").isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
