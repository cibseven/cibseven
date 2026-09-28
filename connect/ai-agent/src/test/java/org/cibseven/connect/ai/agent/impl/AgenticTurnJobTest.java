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

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.runtime.Execution;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The turn as a job: the agent is asked, what it starts runs, and what it does at the end of a
 * turn decides whether the scope lives on.
 *
 * <p>The model is a stub that answers from a script, so each test is a sequence of steps rather
 * than a race, and no language model is called.
 */
public class AgenticTurnJobTest {

  private static final int STUB_PORT = 8099;

  private HttpServer stub;
  private final ConcurrentLinkedQueue<String> script = new ConcurrentLinkedQueue<>();
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
  public void aTurnStartsWhatTheAgentAsksFor() {
    script.add(toolCall("startActivity", "{\\\"activityId\\\":\\\"worker\\\"}"));
    script.add(answer("Started."));
    ProcessInstance pi = start();

    runWaitingTurn();

    assertThat(engine.getTaskService().createTaskQuery().list()).hasSize(1);
    assertThat(alive(pi)).isTrue();
  }

  @Test
  public void aFurtherTurnComesAtTheEndOfTheChain() {
    script.add(toolCall("startActivity", "{\\\"activityId\\\":\\\"worker\\\"}"));
    script.add(answer("Started."));
    ProcessInstance pi = start();
    runWaitingTurn();

    // 'worker' flows on to 'second', so finishing it is not the end of the work the agent
    // started -- waking here would show it a half-finished chain.
    complete("Worker");
    assertThat(engine.getManagementService().createJobQuery().list()).isEmpty();
    assertThat(engine.getTaskService().createTaskQuery().taskName("Second").singleResult())
        .isNotNull();

    complete("Second");
    assertThat(engine.getManagementService().createJobQuery().list()).hasSize(1);
    assertThat(alive(pi)).isTrue();
  }

  protected void complete(String taskName) {
    engine.getTaskService().complete(
        engine.getTaskService().createTaskQuery().taskName(taskName).singleResult().getId());
  }

  @Test
  public void aTurnThatStartsNothingEndsTheScope() {
    script.add(answer("There is nothing to do."));
    ProcessInstance pi = start();

    runWaitingTurn();

    assertThat(alive(pi)).isFalse();
    assertThat(engine.getManagementService().createJobQuery().list()).isEmpty();
  }

  @Test
  public void theAnswerIsWrittenToTheConfiguredVariable() {
    script.add(answer("All done."));
    ProcessInstance pi = start();

    runWaitingTurn();

    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("summary").singleResult().getValue())
        .isEqualTo("All done.");
  }

  @Test
  public void theAgentIsOfferedOnlyWhatTheEngineWillStart() {
    script.add(toolCall("listAvailableActivities", "{}"));
    script.add(answer("Seen."));
    start();

    runWaitingTurn();

    // 'second' sits behind an inner sequence flow, so the engine refuses to start it directly
    // and the catalogue must not offer it.
    assertThat(lastToolResult()).contains("worker").doesNotContain("second");
  }

  protected void runWaitingTurn() {
    Job job = engine.getManagementService().createJobQuery().singleResult();
    engine.getManagementService().executeJob(job.getId());
  }

  protected boolean alive(ProcessInstance pi) {
    return engine.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(pi.getId()).singleResult() != null;
  }

  /** What the tool answered on the last call, as the stub saw it come back. */
  protected String lastToolResult() {
    return lastRequestBody;
  }

  private volatile String lastRequestBody = "";

  private void respond(HttpExchange exchange) throws IOException {
    lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
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

  private static String toolCall(String name, String arguments) {
    return "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,"
        + "\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
        + "\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
        + "\"function\":{\"name\":\"" + name + "\",\"arguments\":\"" + arguments + "\"}}]},"
        + "\"finish_reason\":\"tool_calls\"}],"
        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
  }

  private static String answer(String text) {
    return "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,"
        + "\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
        + "\"content\":\"" + text + "\"},\"finish_reason\":\"stop\"}],"
        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
  }

  protected ProcessInstance start() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName("agentic-turn");
    configuration.setJdbcUrl("jdbc:h2:mem:agentic-turn;DB_CLOSE_DELAY=-1");
    // Off: the turn is run by hand, so the outcome is a step and not a race.
    configuration.setJobExecutorActivate(false);
    configuration.setHistoryTimeToLive("P30D");
    configuration.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
    configuration.setProcessEnginePlugins(
        Collections.<ProcessEnginePlugin>singletonList(new ConnectProcessEnginePlugin()));
    engine = configuration.buildProcessEngine();

    engine.getRepositoryService().createDeployment()
        .addString("agentic-turn.bpmn20.xml", model()).deploy();
    return engine.getRuntimeService().startProcessInstanceByKey("agenticTurn");
  }

  private static String model() {
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + "             targetNamespace='http://cibseven.org/agentic-turn'>"
        + "  <process id='agenticTurn' isExecutable='true'>"
        + "    <startEvent id='start' />"
        + "    <sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + "    <adHocSubProcess id='adHoc'>"
        + "      <extensionElements><camunda:properties>"
        + "        <camunda:property name='cibseven.agentic.enabled' value='true' />"
        + "        <camunda:property name='cibseven.agentic.message' value='Do the work.' />"
        + "        <camunda:property name='cibseven.agentic.model' value='stub' />"
        + "        <camunda:property name='cibseven.agentic.apiKey' value='stub-key' />"
        + "        <camunda:property name='cibseven.agentic.baseUrl'"
        + "                          value='http://127.0.0.1:" + STUB_PORT + "/v1' />"
        + "        <camunda:property name='cibseven.agentic.resultVariable' value='summary' />"
        + "      </camunda:properties></extensionElements>"
        + "      <userTask id='worker' name='Worker' />"
        + "      <userTask id='second' name='Second' />"
        + "      <sequenceFlow id='inner' sourceRef='worker' targetRef='second' />"
        + "    </adHocSubProcess>"
        + "    <sequenceFlow id='f2' sourceRef='adHoc' targetRef='end' />"
        + "    <endEvent id='end' />"
        + "  </process>"
        + "</definitions>";
  }
}
