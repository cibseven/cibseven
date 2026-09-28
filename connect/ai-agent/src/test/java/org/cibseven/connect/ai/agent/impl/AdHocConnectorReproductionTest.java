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
 * Reproduces, in one process, the failure the ad-hoc agent suite hit in a running
 * distribution.
 *
 * <p>Every other test of this ticket scripts the model. Here the real connector and the
 * real LangChain4j loop run, and that is the only configuration in which the failure
 * appears. Two rounds of guessing were refuted by measurement before this test existed —
 * the classloaders are identical, and the ad hoc scope is not destroyed.
 *
 * <p>The language model is a local stub started by the test, so the run needs no
 * network and no key. {@code baseUrl} and {@code apiKey} are set on the scope rather than
 * as environment variables, because the connector reads the environment only as a fallback
 * and a test cannot set it.
 *
 * <p>In the distribution the original exception is lost: the engine's error
 * propagation walks the execution tree, hits a null parent and throws a
 * {@code NullPointerException} from {@code getFlowScopeExecution}, which replaces
 * whatever the connector actually threw. Here the incident message is asserted, so
 * a regression names the cause instead of the symptom.
 */
public class AdHocConnectorReproductionTest {

  private static final int STUB_PORT = 8099;

  private HttpServer stub;
  private final AtomicInteger stubCalls = new AtomicInteger();
  private ProcessEngine engine;

  /**
   * A minimal OpenAI-compatible endpoint that asks for one tool call and then
   * stops. Enough to get the connector as far as executing a tool.
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
    String body;
    if (call == 1) {
      // The activity is its own tool now; there is no generic start call to name it in.
      body = toolCall("call_1", "calculatePrice", "{}");
    } else if (call == 2) {
      body = toolCall("call_2", "completeScope", "{}");
    } else {
      // The turn the failure lived in: the model is asked again after
      // completeScope returned, exactly as LangChain4j does.
      body = finalText("done");
    }
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

  private static String finalText(String text) {
    return "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,"
        + "\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
        + "\"content\":\"" + text + "\"},\"finish_reason\":\"stop\"}],"
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
    configuration.setProcessEngineName("adhoc-connector-repro");
    configuration.setJdbcUrl("jdbc:h2:mem:adhoc-connector-repro;DB_CLOSE_DELAY=-1");
    // Off: the turn's job is executed by hand so the failure is a step, not a race.
    configuration.setJobExecutorActivate(false);
    configuration.setHistoryTimeToLive("P30D");
    configuration.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
    // Nothing recognises cibseven.agentic.enabled without it.
    configuration.setProcessEnginePlugins(
        Collections.<ProcessEnginePlugin>singletonList(new ConnectProcessEnginePlugin()));
    engine = configuration.buildProcessEngine();
    return engine;
  }

  /** The distribution's shape: the agent is configuration on the scope. */
  private static String model() {
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + " xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + " targetNamespace='http://cibseven.org/repro'>"
        + "<process id='reproAgent' isExecutable='true'>"
        + "  <startEvent id='start' />"
        + "  <sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + "  <adHocSubProcess id='adHoc'>"
        + "    <extensionElements><camunda:properties>"
        + "      <camunda:property name='cibseven.agentic.enabled' value='true' />"
        + "      <camunda:property name='cibseven.agentic.agentName' value='Repro' />"
        + "      <camunda:property name='cibseven.agentic.message' value='Do something.' />"
        + "      <camunda:property name='cibseven.agentic.model' value='stub' />"
        + "      <camunda:property name='cibseven.agentic.apiKey' value='dummy' />"
        + "      <camunda:property name='cibseven.agentic.baseUrl'"
        + "                        value='http://127.0.0.1:" + STUB_PORT + "/v1' />"
        + "    </camunda:properties></extensionElements>"
        + "    <serviceTask id='calculatePrice' name='Calculate price'"
        + "        camunda:expression='${1200}' camunda:resultVariable='price' />"
        + "  </adHocSubProcess>"
        + "  <sequenceFlow id='f2' sourceRef='adHoc' targetRef='end' />"
        + "  <endEvent id='end' />"
        + "</process></definitions>";
  }

  /**
   * The turn must run without an incident, and the model must have been asked at least
   * once. A failure here prints the incident the distribution shows.
   */
  @Test
  public void theAgentTurnRunsThroughTheRealConnector() {
    ProcessEngine engine = engine();
    engine.getRepositoryService().createDeployment()
        .addString("repro.bpmn20.xml", model())
        .deploy();

    ProcessInstance instance =
        engine.getRuntimeService().startProcessInstanceByKey("reproAgent");

    List<Job> jobs = engine.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(jobs).as("the turn's job").hasSize(1);

    RuntimeException failure = null;
    try {
      engine.getManagementService().executeJob(jobs.get(0).getId());
    } catch (RuntimeException e) {
      failure = e;
    }

    List<Incident> incidents = engine.getRuntimeService().createIncidentQuery()
        .processInstanceId(instance.getId()).list();
    String reported = incidents.isEmpty() ? null : incidents.get(0).getIncidentMessage();

    assertThat(failure)
        .as("the turn's job failed; incident says: " + reported
            + "; the model was asked " + stubCalls.get() + " time(s)")
        .isNull();
    assertThat(stubCalls.get()).as("the model was never asked").isGreaterThan(0);
  }

  /**
   * The whole sequence the distribution ran, through the real connector and the
   * real LangChain4j loop: list, start, complete, then the model is asked once
   * more. That last step is where every model of the suite failed, because
   * completing the scope used to delete the execution the turn was running on.
   */
  @Test
  public void theFullTurnEndsTheScopeAndTheProcessContinues() {
    ProcessEngine engine = engine();
    engine.getRepositoryService().createDeployment()
        .addString("repro.bpmn20.xml", model())
        .deploy();

    ProcessInstance instance =
        engine.getRuntimeService().startProcessInstanceByKey("reproAgent");
    List<Job> jobs = engine.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(jobs).hasSize(1);

    RuntimeException failure = null;
    try {
      engine.getManagementService().executeJob(jobs.get(0).getId());
    } catch (RuntimeException e) {
      failure = e;
    }

    List<Incident> incidents = engine.getRuntimeService().createIncidentQuery()
        .processInstanceId(instance.getId()).list();
    String reported = incidents.isEmpty() ? null : incidents.get(0).getIncidentMessage();

    assertThat(failure).as("turn failed; incident: " + reported).isNull();
    assertThat(stubCalls.get()).as("the model must have been asked after completeScope")
        .isGreaterThanOrEqualTo(3);
    assertThat(engine.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(instance.getId()).count())
        .as("the process should have continued past the scope").isZero();
  }
}
