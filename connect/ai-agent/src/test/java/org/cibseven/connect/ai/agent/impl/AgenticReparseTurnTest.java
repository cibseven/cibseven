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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.connect.ai.agent.agentic.AgenticAdHocParseListener;
import org.cibseven.connect.ai.agent.agentic.AgenticToolCatalogParser;
import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * What a model deployed while the AI agent was off does at run time once the agent is on
 * (CIB7-2367): the parse again leaves out what a deployment would refuse, and the turns run
 * without it. {@code AgenticReparseTest} pins the parse; this pins the turns.
 *
 * <p>Two engines on one database, one without the agent and one with it; the model is a stub that
 * answers from a script, on a port of its own.
 */
public class AgenticReparseTurnTest {

  /** One database per test. */
  protected static final AtomicInteger DATABASES = new AtomicInteger();

  protected final ConcurrentLinkedQueue<String> script = new ConcurrentLinkedQueue<String>();
  protected final List<String> requests = new CopyOnWriteArrayList<String>();
  protected final List<ProcessEngine> engines = new ArrayList<ProcessEngine>();
  protected HttpServer stub;
  protected String database;

  @BeforeEach
  public void startStub() throws IOException {
    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext("/v1/chat/completions", this::respond);
    stub.setExecutor(null);
    stub.start();
    database = "agentic-reparse-turn-" + DATABASES.incrementAndGet();
  }

  @AfterEach
  public void stopEverything() throws SQLException {
    for (ProcessEngine engine : engines) {
      engine.close();
    }
    try (Connection connection =
        DriverManager.getConnection("jdbc:h2:mem:" + database, "sa", "")) {
      connection.createStatement().execute("SHUTDOWN");
    }
    stub.stop(0);
  }

  /**
   * A tool a parse again leaves out, started while the agent was off, ends once it is on: the
   * scope's next turn runs, the agent is not offered the tool, and the scope ends as the agent
   * answers.
   */
  @Test
  public void aToolLeftOutThatRanWithTheAgentOffEndsWithTheAgentOn() {
    ProcessEngine off = engine("off", new AbstractProcessEnginePlugin() { });
    deploy(off, model("", "<userTask id='worker' name='Worker' />"
        + "<userTask id='gesperrt' name='Gesperrt'><extensionElements><camunda:properties>"
        + "<camunda:property name='" + AgenticToolCatalogParser.BLOCKED_WHILE_OTHERS_RUN_PROPERTY
        + "' value='ja' /></camunda:properties></extensionElements></userTask>", ""));
    ProcessInstance pi = off.getRuntimeService().startProcessInstanceByKey("agenticTurn");
    String scope = off.getRuntimeService().getActivityInstance(pi.getId())
        .getActivityInstances("adHoc")[0].getExecutionIds()[0];
    off.getRuntimeService().activateAdHocSubProcessActivities(scope,
        Collections.singletonList("gesperrt"));
    ProcessEngine on = engine("on", new ConnectProcessEnginePlugin());
    script.add(answer("Nichts mehr zu tun."));

    on.getTaskService().complete(
        on.getTaskService().createTaskQuery().taskName("Gesperrt").singleResult().getId());
    assertThat(on.getManagementService().createJobQuery().count())
        .as("the tool's end gives the scope a turn").isOne();
    TurnSteps.runToTheEnd(on, pi.getId());

    assertThat(requests).hasSize(1);
    assertThat(toolNames(requests.get(0))).as("the left out tool is not offered")
        .contains("worker").doesNotContain("gesperrt");
    assertThat(alive(on, pi)).as("the agent's answer ended the scope").isFalse();
    assertThat(on.getRuntimeService().createIncidentQuery().count()).isZero();
  }

  /**
   * A limit reached once a parse again has left onLimit = error out ends the scope as any turn
   * would, on its normal path: the outcome names the limit, and no error is thrown that nothing
   * catches.
   */
  @Test
  public void aLimitReachedAfterOnLimitWasLeftOutEndsTheScopeAsAnyTurn() {
    ProcessEngine off = engine("off", new AbstractProcessEnginePlugin() { });
    deploy(off, model("<camunda:properties>"
        + "<camunda:property name='" + AgenticAdHocParseListener.MAX_TURNS + "' value='1' />"
        + "<camunda:property name='" + AgenticAdHocParseListener.ON_LIMIT + "' value='error' />"
        + "</camunda:properties>", "<userTask id='worker' name='Worker' />",
        "<userTask id='danach' name='Danach' />"));
    ProcessEngine on = engine("on", new ConnectProcessEnginePlugin());
    script.add(toolCall("worker"));
    script.add(answer("Gestartet."));
    ProcessInstance pi = on.getRuntimeService().startProcessInstanceByKey("agenticTurn");

    TurnSteps.runToTheEnd(on, pi.getId());
    on.getTaskService().complete(
        on.getTaskService().createTaskQuery().taskName("Worker").singleResult().getId());
    TurnSteps.runToTheEnd(on, pi.getId());

    assertThat(on.getTaskService().createTaskQuery().singleResult().getName())
        .as("the scope ended on its normal path").isEqualTo("Danach");
    assertThat(on.getRuntimeService().getVariable(pi.getId(), "agentOutcome"))
        .isEqualTo("turnLimit");
    assertThat(on.getRuntimeService().createIncidentQuery().count()).isZero();
  }

  /**
   * An agentic scope with the stub as its model, the scope's own extension elements first, its
   * children, and what follows the scope before the end.
   */
  protected String model(String scopeExtensions, String children, String afterScope) {
    String next = afterScope.isEmpty() ? "end" : "danach";
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + "             targetNamespace='http://cibseven.org/agentic-reparse-turn'>"
        + "  <process id='agenticTurn' isExecutable='true'>"
        + "    <startEvent id='start' />"
        + "    <sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + "    <adHocSubProcess id='adHoc'>"
        + "      <extensionElements>" + scopeExtensions + "<camunda:connector>"
        + "        <camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "        <camunda:inputOutput>"
        + "          <camunda:inputParameter name='message'>Do the work.</camunda:inputParameter>"
        + "          <camunda:inputParameter name='model'>stub</camunda:inputParameter>"
        + "          <camunda:inputParameter name='apiKey'>stub-key</camunda:inputParameter>"
        + "          <camunda:inputParameter name='baseUrl'>http://127.0.0.1:"
        + stub.getAddress().getPort() + "/v1</camunda:inputParameter>"
        + "        </camunda:inputOutput>"
        + "      </camunda:connector></extensionElements>"
        + children
        + "    </adHocSubProcess>"
        + "    <sequenceFlow id='f2' sourceRef='adHoc' targetRef='" + next + "' />"
        + afterScope
        + (afterScope.isEmpty() ? "" : "<sequenceFlow id='f3' sourceRef='danach' targetRef='end' />")
        + "    <endEvent id='end' />"
        + "  </process>"
        + "</definitions>";
  }

  /** An engine on this test's database, which the first one creates and the other shares. */
  protected ProcessEngine engine(String name, ProcessEnginePlugin plugin) {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName(database + "-" + name);
    configuration.setJdbcUrl("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
    configuration.setDatabaseSchemaUpdate(ProcessEngineConfigurationImpl.DB_SCHEMA_UPDATE_TRUE);
    // Off: the turn is run by hand, so the outcome is a step and not a race.
    configuration.setJobExecutorActivate(false);
    configuration.setEnforceHistoryTimeToLive(false);
    configuration.setProcessEnginePlugins(Collections.singletonList(plugin));
    ProcessEngine engine = configuration.buildProcessEngine();
    engines.add(engine);
    return engine;
  }

  protected static void deploy(ProcessEngine engine, String model) {
    engine.getRepositoryService().createDeployment()
        .addString("agentic-reparse-turn.bpmn20.xml", model).deploy();
  }

  protected static boolean alive(ProcessEngine engine, ProcessInstance pi) {
    return engine.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(pi.getId()).singleResult() != null;
  }

  /** The names of the tools a chat completion request offers. */
  protected static List<String> toolNames(String requestBody) {
    List<String> names = new ArrayList<String>();
    try {
      for (JsonNode tool : new ObjectMapper().readTree(requestBody).path("tools")) {
        names.add(tool.path("function").path("name").asText());
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return names;
  }

  protected void respond(HttpExchange exchange) throws IOException {
    requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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

  protected static String toolCall(String name) {
    return "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,"
        + "\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
        + "\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
        + "\"function\":{\"name\":\"" + name + "\",\"arguments\":\"{}\"}}]},"
        + "\"finish_reason\":\"tool_calls\"}],"
        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
  }

  protected static String answer(String text) {
    return "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,"
        + "\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
        + "\"content\":\"" + text + "\"},\"finish_reason\":\"stop\"}],"
        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
  }
}
