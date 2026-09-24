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
import java.util.concurrent.atomic.AtomicInteger;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.history.HistoricProcessInstance;
import org.cibseven.bpm.engine.history.HistoricVariableInstance;
import org.cibseven.bpm.engine.runtime.Incident;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A model that never stops calling tools must not park the process instance.
 *
 * <p>Seen in a running distribution: the per-turn call cap refused every further
 * activation from call 26 on, but the stub went on asking for tools, and
 * LangChain4j's own brake at 100 round trips ended the turn by throwing. The job
 * failed, was retried, and the instance was left standing — the opposite of what the
 * cap exists for.
 *
 * <p>Driven through the real connector service task, like
 * {@link AdHocConnectorReproductionTest}: the cap lives in the tool, the bound on the
 * loop lives in the connector, and only the two together produce the behaviour. A test
 * calling the tool methods from Java would pass without the fix.
 */
public class AdHocRunawayTurnTest {

  private static final int STUB_PORT = 8097;

  /** Small enough to keep the test short, and not the default, so the property is read. */
  private static final int CALL_LIMIT = 6;

  private HttpServer stub;
  private final AtomicInteger stubCalls = new AtomicInteger();
  private ProcessEngine engine;

  /**
   * A model that never answers: it alternates between the two tools forever, which is
   * what the suite's case 09 does and what its instruction asks for.
   */
  @BeforeEach
  public void startStub() throws IOException {
    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", STUB_PORT), 0);
    stub.createContext("/v1/chat/completions", this::respond);
    stub.setExecutor(null);
    stub.start();
  }

  private void respond(HttpExchange exchange) throws IOException {
    int call = stubCalls.incrementAndGet();
    String body = (call % 2 == 1)
        ? toolCall("call_" + call, "listAvailableActivities", "{}")
        : toolCall("call_" + call, "startActivity",
            "{\\\"activityId\\\":\\\"work\\\",\\\"variables\\\":{}}");
    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, payload.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(payload);
    }
  }

  private static String toolCall(String id, String name, String arguments) {
    return "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,"
        + "\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
        + "\"content\":null,\"tool_calls\":[{\"id\":\"" + id + "\",\"type\":\"function\","
        + "\"function\":{\"name\":\"" + name + "\",\"arguments\":\"" + arguments + "\"}}]},"
        + "\"finish_reason\":\"tool_calls\"}],"
        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
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

  private ProcessEngine engine() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName("adhoc-runaway");
    configuration.setJdbcUrl("jdbc:h2:mem:adhoc-runaway;DB_CLOSE_DELAY=-1");
    // Off: the driver's job is run by hand, so the outcome is a step and not a race.
    configuration.setJobExecutorActivate(false);
    configuration.setHistoryTimeToLive("P30D");
    configuration.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
    configuration.setProcessEnginePlugins(
        Collections.<ProcessEnginePlugin>singletonList(new ConnectProcessEnginePlugin()));
    engine = configuration.buildProcessEngine();
    return engine;
  }

  /**
   * The child runs without waiting, so nothing ever holds the scope and the turn is the
   * only thing that can end. {@code adHocMaxCallsPerTurn} is set on the scope, which is
   * also what the connector must read to bound the loop.
   */
  private static String model() {
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + " xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + " targetNamespace='http://cibseven.org/runaway'>"
        + "<process id='runawayAgent' isExecutable='true'>"
        + "  <startEvent id='start' />"
        + "  <sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + "  <adHocSubProcess id='adHoc'>"
        + "    <extensionElements><camunda:properties>"
        + "      <camunda:property name='explicitCompletionOnly' value='true' />"
        + "      <camunda:property name='adHocDriverActivity' value='agent' />"
        + "      <camunda:property name='activeElementsCollection' value='agent' />"
        + "      <camunda:property name='adHocMaxCallsPerTurn' value='" + CALL_LIMIT + "' />"
        + "    </camunda:properties></extensionElements>"
        + "    <serviceTask id='agent' name='Agent' camunda:asyncBefore='true'>"
        + "      <extensionElements>"
        + "        <camunda:connector>"
        + "          <camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "          <camunda:inputOutput>"
        + "            <camunda:inputParameter name='agentName'>Runaway</camunda:inputParameter>"
        + "            <camunda:inputParameter name='message'>Keep going.</camunda:inputParameter>"
        + "            <camunda:inputParameter name='baseUrl'>"
        + "http://127.0.0.1:" + STUB_PORT + "/v1</camunda:inputParameter>"
        + "            <camunda:inputParameter name='apiKey'>dummy</camunda:inputParameter>"
        + "            <camunda:inputParameter name='model'>stub</camunda:inputParameter>"
        + "            <camunda:inputParameter name='toolClasses'>"
        + AdHocSubProcessTool.class.getName() + "</camunda:inputParameter>"
        + "            <camunda:outputParameter name='agentOutput'>${output}"
        + "</camunda:outputParameter>"
        + "          </camunda:inputOutput>"
        + "        </camunda:connector>"
        + "      </extensionElements>"
        + "    </serviceTask>"
        + "    <serviceTask id='work' name='Work'"
        + "        camunda:expression='${1}' camunda:resultVariable='done' />"
        + "  </adHocSubProcess>"
        + "  <sequenceFlow id='f2' sourceRef='adHoc' targetRef='end' />"
        + "  <endEvent id='end' />"
        + "</process></definitions>";
  }

  private ProcessInstance runTheDriversTurn() {
    ProcessEngine engine = engine();
    engine.getRepositoryService().createDeployment()
        .addString("runaway.bpmn20.xml", model())
        .deploy();

    ProcessInstance instance =
        engine.getRuntimeService().startProcessInstanceByKey("runawayAgent");
    List<Job> jobs = engine.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(jobs).as("the driver's job").hasSize(1);
    engine.getManagementService().executeJob(jobs.get(0).getId());
    return instance;
  }

  /**
   * The turn ends on its own and the process runs to its end event. Before the fix
   * this threw out of {@code executeJob}, and the instance stayed put with an incident.
   */
  @Test
  public void aModelThatNeverStopsCallingToolsDoesNotParkTheProcess() {
    ProcessInstance instance = runTheDriversTurn();

    List<Incident> incidents = engine.getRuntimeService().createIncidentQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(incidents).as("incidents raised by the runaway turn").isEmpty();

    HistoricProcessInstance history = engine.getHistoryService()
        .createHistoricProcessInstanceQuery().processInstanceId(instance.getId()).singleResult();
    assertThat(history.getState())
        .as("the instance, after a turn that never answered")
        .isEqualTo(HistoricProcessInstance.STATE_COMPLETED);
  }

  /**
   * The bound is ours. Left to LangChain4j the model would have been asked a hundred
   * times; asserting the number and not just the absence of a failure is what tells the
   * two apart.
   */
  @Test
  public void theModelIsStoppedAtTheScopesOwnLimitRatherThanLangChain4jsDefault() {
    runTheDriversTurn();

    assertThat(stubCalls.get())
        .as("model calls in one turn, for a call limit of " + CALL_LIMIT)
        .isLessThanOrEqualTo(CALL_LIMIT + 6)
        .isGreaterThan(CALL_LIMIT);
  }

  /**
   * The process gets an answer to read rather than an empty variable: a turn ended this
   * way still has to say what happened, or the model that follows sees nothing.
   */
  @Test
  public void theEndedTurnStillWritesItsOutput() {
    ProcessInstance instance = runTheDriversTurn();

    HistoricVariableInstance output = engine.getHistoryService()
        .createHistoricVariableInstanceQuery()
        .processInstanceId(instance.getId()).variableName("agentOutput").singleResult();

    assertThat(output).as("agentOutput of the ended turn").isNotNull();
    assertThat(output.getValue()).isEqualTo(AgentConnectorImpl.TURN_ENDED_WITHOUT_ANSWER);
  }
}
