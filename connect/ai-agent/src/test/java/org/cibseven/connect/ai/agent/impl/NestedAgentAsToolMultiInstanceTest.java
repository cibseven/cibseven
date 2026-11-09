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
 * {@code berater} from {@link NestedAgentAsToolTest}, but modelled as a parallel
 * {@code multiInstance} tool: two instances of the same AI agent task, started by one tool call,
 * run the connector nested a second and a third time on the same thread, in the same ad hoc
 * scope, with the same calling activity id (CIB7-2155, multi-instance follow-up).
 *
 * <p>{@code multiInstance} never renames the activity — every instance shares {@code berater}'s
 * activity id, and both instances' scope is the one, single ad hoc scope they both run in. Keying
 * the fallback memory id by the calling activity id alone, as the original CIB7-2155 fix did,
 * would therefore give both instances the identical key: the second instance's write would
 * replace the first instance's conversation on the very same process variable.
 */
public class NestedAgentAsToolMultiInstanceTest {

  private static final int STUB_PORT = 8198;

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
  public void twoParallelInstancesOfTheSameToolDoNotShareOneConversation() throws IOException {
    // Step 1: the outer turn's model starts the multi-instance tool once.
    script.add(toolCall("berater"));
    // Each instance's own, single-shot connector call -- nested inside step 1's tool execution.
    script.add(answer("Einschaetzung zu Instanz 0."));
    script.add(answer("Einschaetzung zu Instanz 1."));
    // Step 2: the outer turn's next job reports on what finished.
    script.add(answer("Soweit erledigt."));

    ProcessInstance pi = start();
    TurnSteps.runToTheEnd(engine, pi.getId());

    assertThat(requestBodies).hasSize(4);
    String outerStep1 = requestBodies.get(0);
    String instance0Call = requestBodies.get(1);
    String instance1Call = requestBodies.get(2);
    String outerStep2 = requestBodies.get(3);

    // Each instance's own request carries its own message only -- not its sibling's, and not the
    // outer turn's. requestBodies.get(1) is asserted to be instance 0's call, not just "whichever
    // came first": parallel multi-instance starts its children in creation order within one
    // command, so loopCounter 0 reaches the stub before loopCounter 1 reaches it here.
    assertThat(instance0Call).contains("Instanz 0:").doesNotContain("Instanz 1:");
    assertThat(instance1Call).contains("Instanz 1:").doesNotContain("Instanz 0:");
    assertThat(outerStep1).doesNotContain("Instanz 0:").doesNotContain("Instanz 1:");
    assertThat(outerStep2).doesNotContain("Instanz 0:").doesNotContain("Instanz 1:");

    // The two instances' own conversations persisted as two distinct process variables -- not
    // one variable that the second instance's write silently overwrote. Narrowed to the nested
    // tool's own variables (":tool:berater:<loopCounter>"): the outer scope keeps its own
    // conversation under a separate name (AgenticTurnRunner.memoryId), which the plain prefix
    // would otherwise also match. Read from history, not runtime: the process instance, which
    // keeps them (CIB7-2232), has ended with the turn.
    List<HistoricVariableInstance> memoryVariables = engine.getHistoryService()
        .createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId())
        .variableNameLike(AgentConnectorConstants.AGENT_CONNECTOR_MEMORY_PREFIX + "%:tool:berater:%")
        .list();
    assertThat(memoryVariables).as("one memory variable per multi-instance tool instance").hasSize(2);

    String firstValue = String.valueOf(memoryVariables.get(0).getValue());
    String secondValue = String.valueOf(memoryVariables.get(1).getValue());
    assertThat(firstValue.contains("Instanz 0:") != secondValue.contains("Instanz 0:"))
        .as("exactly one of the two variables holds instance 0's message").isTrue();
    assertThat(firstValue.contains("Instanz 1:") != secondValue.contains("Instanz 1:"))
        .as("exactly one of the two variables holds instance 1's message").isTrue();
  }

  /**
   * As above, but {@code berater} sits inside an embedded sub process that is itself the
   * {@code multiInstance} tool (two parallel instances of the whole sub process), rather than
   * being {@code multiInstance} directly.
   *
   * <p>A boundary event on a multi-instance activity does <em>not</em> make that activity its
   * own scope: the parser gives a multi-instance activity its own scope only when nothing else
   * already makes it one, and for a boundary event that scope is the multi-instance body itself
   * ({@code BpmnParse.parseBoundaryEvent}, {@code getMultiInstanceScope}) — so
   * {@code loopCounter} and the connector's own execution stay at the same level there, and the
   * walk-up fix would not be exercised by that structure. A sub process, by contrast, is always
   * its own scope regardless of multi-instance ({@code BpmnParse.parseSubProcess}): the agent
   * task inside it runs one level further down from the concurrent execution multi-instance
   * creates per sub-process instance, where {@code loopCounter} is local, which is exactly the
   * gap the review found (CIB7-2155 review).
   */
  @Test
  public void twoParallelInstancesDoNotShareOneConversationWhenTheToolIsAMultiInstanceSubProcess()
      throws IOException {
    script.add(toolCall("beraterSub"));
    script.add(answer("Einschaetzung zu Instanz 0."));
    script.add(answer("Einschaetzung zu Instanz 1."));
    script.add(answer("Soweit erledigt."));

    ProcessInstance pi = startWithMultiInstanceSubProcess();
    TurnSteps.runToTheEnd(engine, pi.getId());

    assertThat(requestBodies).hasSize(4);
    assertThat(requestBodies.get(1)).contains("Instanz 0:").doesNotContain("Instanz 1:");
    assertThat(requestBodies.get(2)).contains("Instanz 1:").doesNotContain("Instanz 0:");

    List<HistoricVariableInstance> memoryVariables = engine.getHistoryService()
        .createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId())
        .variableNameLike(AgentConnectorConstants.AGENT_CONNECTOR_MEMORY_PREFIX + "%:tool:berater:%")
        .list();
    assertThat(memoryVariables)
        .as("one memory variable per multi-instance tool instance, even though the tool is a sub "
            + "process and loopCounter sits one level above the execution the connector sees")
        .hasSize(2);
  }

  /**
   * Both at once: {@code berater} is {@code multiInstance} itself and sits inside a
   * {@code multiInstance} sub process tool, two instances each, four calls. Each level has its own
   * {@code loopCounter}, and the instances of {@code berater} in the second sub process instance
   * count from 0 again: keyed by the nearest {@code loopCounter} alone, the call (1, 0) would have
   * continued the conversation of the call (0, 0) (CIB7-2232).
   */
  @Test
  public void fourNestedInstancesDoNotShareOneConversationWhenBothLevelsAreMultiInstance()
      throws IOException {
    script.add(toolCall("beraterSub"));
    for (int i = 0; i < 4; i++) {
      script.add(answer("Einschaetzung " + i + "."));
    }
    script.add(answer("Soweit erledigt."));

    ProcessInstance pi = startWithNestedMultiInstance();
    TurnSteps.runToTheEnd(engine, pi.getId());

    assertThat(requestBodies).hasSize(6);
    // The outer instances run one after the other within the tool call, each its inner ones in
    // order: (0, 0), (0, 1), (1, 0), (1, 1).
    String[] calls = {"Aussen 0, innen 0:", "Aussen 0, innen 1:", "Aussen 1, innen 0:", "Aussen 1, innen 1:"};
    for (int i = 0; i < calls.length; i++) {
      String request = requestBodies.get(i + 1);
      assertThat(request).as("call %d carries its own message", i).contains(calls[i]);
      for (int other = 0; other < calls.length; other++) {
        if (other != i) {
          assertThat(request).as("call %d carries nothing of call %d", i, other)
              .doesNotContain(calls[other]);
        }
      }
    }

    List<HistoricVariableInstance> memoryVariables = engine.getHistoryService()
        .createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId())
        .variableNameLike(AgentConnectorConstants.AGENT_CONNECTOR_MEMORY_PREFIX + "%:tool:berater:%")
        .list();
    assertThat(memoryVariables).as("one memory variable per call").hasSize(4);
  }

  // --- model -------------------------------------------------------------------

  private ProcessInstance start() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName("nested-agent-as-tool-mi");
    configuration.setJdbcUrl("jdbc:h2:mem:nested-agent-as-tool-mi;DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    configuration.setHistoryTimeToLive("P30D");
    configuration.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
    configuration.setProcessEnginePlugins(
        Collections.<ProcessEnginePlugin>singletonList(new ConnectProcessEnginePlugin()));
    engine = configuration.buildProcessEngine();

    engine.getRepositoryService().createDeployment()
        .addString("nested-agent-as-tool-mi.bpmn20.xml", model())
        .deploy();
    return engine.getRuntimeService().startProcessInstanceByKey("nestedAgentAsToolMi");
  }

  private static String model() {
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + "             targetNamespace='http://cibseven.org/nested-agent-as-tool-mi'>"
        + "  <process id='nestedAgentAsToolMi' isExecutable='true'>"
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
        + "      <serviceTask id='berater' name='Berater'>"
        + "        <extensionElements><camunda:connector>"
        + "          <camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "          <camunda:inputOutput>"
        + "            <camunda:inputParameter name='agentName'>berater</camunda:inputParameter>"
        + "            <camunda:inputParameter name='instruction'>"
        + "Du bist ein unabhaengiger Fachberater."
        + "            </camunda:inputParameter>"
        + "            <camunda:inputParameter name='message'>"
        + "Instanz ${loopCounter}: Berate mich zu diesem Fall."
        + "            </camunda:inputParameter>"
        + "            <camunda:inputParameter name='useChatMemory'>true</camunda:inputParameter>"
        + "            <camunda:inputParameter name='model'>stub</camunda:inputParameter>"
        + "            <camunda:inputParameter name='apiKey'>stub-key-berater</camunda:inputParameter>"
        + "            <camunda:inputParameter name='baseUrl'>http://127.0.0.1:" + STUB_PORT
        + "/v1</camunda:inputParameter>"
        + "            <camunda:outputParameter name='beratung'>${output}</camunda:outputParameter>"
        + "          </camunda:inputOutput>"
        + "        </camunda:connector></extensionElements>"
        + "        <multiInstanceLoopCharacteristics isSequential='false'>"
        + "          <loopCardinality>2</loopCardinality>"
        + "        </multiInstanceLoopCharacteristics>"
        + "      </serviceTask>"
        + "    </adHocSubProcess>"
        + "    <sequenceFlow id='f2' sourceRef='adHoc' targetRef='end' />"
        + "    <endEvent id='end' />"
        + "  </process>"
        + "</definitions>";
  }

  private ProcessInstance startWithMultiInstanceSubProcess() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName("nested-agent-as-tool-mi-subprocess");
    configuration.setJdbcUrl("jdbc:h2:mem:nested-agent-as-tool-mi-subprocess;DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    configuration.setHistoryTimeToLive("P30D");
    configuration.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
    configuration.setProcessEnginePlugins(
        Collections.<ProcessEnginePlugin>singletonList(new ConnectProcessEnginePlugin()));
    engine = configuration.buildProcessEngine();

    engine.getRepositoryService().createDeployment()
        .addString("nested-agent-as-tool-mi-subprocess.bpmn20.xml", modelWithMultiInstanceSubProcess())
        .deploy();
    return engine.getRuntimeService().startProcessInstanceByKey("nestedAgentAsToolMiSubProcess");
  }

  /**
   * {@link #model()}, but the ad hoc scope's tool is an embedded sub process —
   * {@code multiInstance} on the sub process, not on {@code berater} inside it — so each
   * instance's {@code berater} runs one level below the concurrent execution multi-instance
   * creates per instance, where {@code loopCounter} is local.
   */
  private static String modelWithMultiInstanceSubProcess() {
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + "             targetNamespace='http://cibseven.org/nested-agent-as-tool-mi-subprocess'>"
        + "  <process id='nestedAgentAsToolMiSubProcess' isExecutable='true'>"
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
        + "      <subProcess id='beraterSub' name='Berater (parallel)'>"
        + "        <multiInstanceLoopCharacteristics isSequential='false'>"
        + "          <loopCardinality>2</loopCardinality>"
        + "        </multiInstanceLoopCharacteristics>"
        + "        <startEvent id='beraterSubStart' />"
        + "        <sequenceFlow id='fBeraterSub1' sourceRef='beraterSubStart' targetRef='berater' />"
        + "        <serviceTask id='berater' name='Berater'>"
        + "          <extensionElements><camunda:connector>"
        + "            <camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "            <camunda:inputOutput>"
        + "              <camunda:inputParameter name='agentName'>berater</camunda:inputParameter>"
        + "              <camunda:inputParameter name='instruction'>"
        + "Du bist ein unabhaengiger Fachberater."
        + "              </camunda:inputParameter>"
        + "              <camunda:inputParameter name='message'>"
        + "Instanz ${loopCounter}: Berate mich zu diesem Fall."
        + "              </camunda:inputParameter>"
        + "              <camunda:inputParameter name='useChatMemory'>true</camunda:inputParameter>"
        + "              <camunda:inputParameter name='model'>stub</camunda:inputParameter>"
        + "              <camunda:inputParameter name='apiKey'>stub-key-berater</camunda:inputParameter>"
        + "              <camunda:inputParameter name='baseUrl'>http://127.0.0.1:" + STUB_PORT
        + "/v1</camunda:inputParameter>"
        + "              <camunda:outputParameter name='beratung'>${output}</camunda:outputParameter>"
        + "            </camunda:inputOutput>"
        + "          </camunda:connector></extensionElements>"
        + "        </serviceTask>"
        + "        <sequenceFlow id='fBeraterSub2' sourceRef='berater' targetRef='beraterSubEnd' />"
        + "        <endEvent id='beraterSubEnd' />"
        + "      </subProcess>"
        + "    </adHocSubProcess>"
        + "    <sequenceFlow id='f2' sourceRef='adHoc' targetRef='end' />"
        + "    <endEvent id='end' />"
        + "  </process>"
        + "</definitions>";
  }

  private ProcessInstance startWithNestedMultiInstance() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName("nested-agent-as-tool-mi-nested");
    configuration.setJdbcUrl("jdbc:h2:mem:nested-agent-as-tool-mi-nested;DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    configuration.setHistoryTimeToLive("P30D");
    configuration.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
    configuration.setProcessEnginePlugins(
        Collections.<ProcessEnginePlugin>singletonList(new ConnectProcessEnginePlugin()));
    engine = configuration.buildProcessEngine();

    String model = modelWithMultiInstanceSubProcess()
        // the outer instance's number, kept in the sub process before berater's own loopCounter hides it
        .replace("      <subProcess id='beraterSub' name='Berater (parallel)'>",
            "      <subProcess id='beraterSub' name='Berater (parallel)'>"
            + "        <extensionElements><camunda:inputOutput>"
            + "<camunda:inputParameter name='aussen'>${loopCounter}</camunda:inputParameter>"
            + "</camunda:inputOutput></extensionElements>")
        .replace("Instanz ${loopCounter}: Berate mich", "Aussen ${aussen}, innen ${loopCounter}: Berate mich")
        .replace("          </camunda:connector></extensionElements>"
            + "        </serviceTask>",
            "          </camunda:connector></extensionElements>"
            + "          <multiInstanceLoopCharacteristics isSequential='false'>"
            + "            <loopCardinality>2</loopCardinality>"
            + "          </multiInstanceLoopCharacteristics>"
            + "        </serviceTask>");
    engine.getRepositoryService().createDeployment()
        .addString("nested-agent-as-tool-mi-nested.bpmn20.xml", model)
        .deploy();
    return engine.getRuntimeService().startProcessInstanceByKey("nestedAgentAsToolMiSubProcess");
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
