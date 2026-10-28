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
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.history.HistoricProcessInstance;
import org.cibseven.bpm.engine.history.HistoricVariableInstance;
import org.cibseven.bpm.engine.runtime.Incident;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.connect.ConnectorSwap;
import org.cibseven.connect.ai.agent.AgentConnector;
import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;

/**
 * A model that calls a tool name outside the scope's catalogue must not park the process
 * instance, and must not cost the turn more than the one model call that made the name up
 * (CIB7-2150).
 *
 * <p>Driven through the real connector, like {@link AdHocRunawayTurnTest}: {@code stubCalls} is
 * the number of real HTTP round trips the stub answered, and the turn's own model-call cap is
 * set low enough that the suite would hang (via {@link TurnSteps}'s step bound) if a
 * hallucinated name ever triggered a further request inside the same job without the turn
 * runner's knowledge — the regression a strategy that merely returns a result, instead of
 * throwing, would reintroduce.
 */
public class AdHocUnknownToolNameTurnTest {

  private static final int STUB_PORT = 8098;

  /** Small enough to keep the test short, and not the default, so the property is read. */
  private static final int CALL_LIMIT = 4;

  private HttpServer stub;
  private final AtomicInteger stubCalls = new AtomicInteger();
  private final List<String> requestBodies = Collections.synchronizedList(new ArrayList<String>());
  private ProcessEngine engine;
  private IntFunction<String> responder;

  @BeforeEach
  public void startStub() throws IOException {
    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", STUB_PORT), 0);
    stub.createContext("/v1/chat/completions", this::respond);
    stub.setExecutor(null);
    stub.start();
  }

  private void respond(HttpExchange exchange) throws IOException {
    int call = stubCalls.incrementAndGet();
    // The conversation the model is about to see for this call, captured before answering it,
    // so a test can assert on the correction text a hallucination wrote into chat memory —
    // not only on how many calls were made or how the turn ended.
    requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    String body = responder.apply(call);
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

  /** Two tool calls in one answer: a real one and a hallucinated one, in that order. */
  private static String twoToolCalls(String id1, String name1, String args1,
      String id2, String name2, String args2) {
    return "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,"
        + "\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
        + "\"content\":null,\"tool_calls\":["
        + "{\"id\":\"" + id1 + "\",\"type\":\"function\","
        + "\"function\":{\"name\":\"" + name1 + "\",\"arguments\":\"" + args1 + "\"}},"
        + "{\"id\":\"" + id2 + "\",\"type\":\"function\","
        + "\"function\":{\"name\":\"" + name2 + "\",\"arguments\":\"" + args2 + "\"}}"
        + "]},"
        + "\"finish_reason\":\"tool_calls\"}],"
        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
  }

  /** Three tool calls in one answer, all of them made up. */
  private static String threeMadeUpCalls() {
    return "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,"
        + "\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
        + "\"content\":null,\"tool_calls\":["
        + "{\"id\":\"c1\",\"type\":\"function\","
        + "\"function\":{\"name\":\"alpha\",\"arguments\":\"{}\"}},"
        + "{\"id\":\"c2\",\"type\":\"function\","
        + "\"function\":{\"name\":\"beta\",\"arguments\":\"{}\"}},"
        + "{\"id\":\"c3\",\"type\":\"function\","
        + "\"function\":{\"name\":\"gamma\",\"arguments\":\"{}\"}}"
        + "]},"
        + "\"finish_reason\":\"tool_calls\"}],"
        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
  }

  private static String textAnswer(String content) {
    return "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,"
        + "\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
        + "\"content\":\"" + content + "\"},\"finish_reason\":\"stop\"}],"
        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
  }

  @AfterEach
  public void stopEverything() {
    restoreTheRealConnector();
    if (engine != null) {
      engine.close();
      engine = null;
    }
    if (stub != null) {
      stub.stop(0);
      stub = null;
    }
  }

  // ── an MCP server, without a network ────────────────────────────────────

  private final ConnectorSwap connectorSwap = new ConnectorSwap(AgentConnector.ID);

  /**
   * The real connector with its MCP transport swapped for a mock — the one seam {@code
   * AgentConnectorImpl} offers for this ({@code buildMcpClient} is {@code protected} and
   * documented as the test hook, and {@code AgentConnectorMcpTest} uses it the same way).
   * Everything else, including the hallucination path under test, is production code.
   */
  static final class McpConnector extends AgentConnectorImpl {
    private final McpClient client;

    McpConnector(McpClient client) {
      this.client = client;
    }

    @Override
    protected McpClient buildMcpClient(String url, Map<String, String> headers) {
      return client;
    }
  }

  private void installConnectorWithMcpServer(McpClient client) {
    connectorSwap.install(new McpConnector(client));
  }

  private void restoreTheRealConnector() {
    connectorSwap.restore();
  }

  private ProcessEngine engine() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName("adhoc-unknown-tool");
    configuration.setJdbcUrl("jdbc:h2:mem:adhoc-unknown-tool;DB_CLOSE_DELAY=-1");
    // Off: the turn's job is run by hand, so the outcome is a step and not a race.
    configuration.setJobExecutorActivate(false);
    configuration.setHistoryTimeToLive("P30D");
    configuration.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
    configuration.setProcessEnginePlugins(
        Collections.<ProcessEnginePlugin>singletonList(new ConnectProcessEnginePlugin()));
    engine = configuration.buildProcessEngine();
    return engine;
  }

  /** One startable child, "work", so the message naming available tools has something to name. */
  private static String model() {
    return model("");
  }

  /**
   * @param extraInputs further connector input parameters, verbatim, for a case that needs one
   */
  private static String model(String extraInputs) {
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + " xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + " targetNamespace='http://cibseven.org/unknowntool'>"
        + "<process id='unknownToolAgent' isExecutable='true'>"
        + "  <startEvent id='start' />"
        + "  <sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + "  <adHocSubProcess id='adHoc'>"
        + "    <extensionElements><camunda:properties>"
        + "      <camunda:property name='cibseven.agentic.maxModelCalls' value='"
        + CALL_LIMIT + "' />"
        + "    </camunda:properties>"
        + "    <camunda:connector>"
        + "      <camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "      <camunda:inputOutput>"
        + "        <camunda:inputParameter name='agentName'>UnknownTool</camunda:inputParameter>"
        + "        <camunda:inputParameter name='message'>Review the queue.</camunda:inputParameter>"
        + "        <camunda:inputParameter name='model'>stub</camunda:inputParameter>"
        + "        <camunda:inputParameter name='apiKey'>dummy</camunda:inputParameter>"
        + "        <camunda:inputParameter name='baseUrl'>http://127.0.0.1:" + STUB_PORT
        + "/v1</camunda:inputParameter>"
        + extraInputs
        + "      </camunda:inputOutput>"
        + "    </camunda:connector></extensionElements>"
        + "    <serviceTask id='work' name='Work'"
        + "        camunda:expression='${1}' camunda:resultVariable='done' />"
        + "  </adHocSubProcess>"
        + "  <sequenceFlow id='f2' sourceRef='adHoc' targetRef='end' />"
        + "  <endEvent id='end' />"
        + "</process></definitions>";
  }

  private ProcessInstance runTheTurn() {
    return runTheTurn("");
  }

  private ProcessInstance runTheTurn(String extraInputs) {
    return runTheTurn(extraInputs, null);
  }

  /**
   * @param mcpClient when set, the connector is replaced by one whose MCP transport is this
   *     mock, so a turn can have MCP tools without a network
   */
  private ProcessInstance runTheTurn(String extraInputs, McpClient mcpClient) {
    ProcessEngine engine = engine();
    engine.getRepositoryService().createDeployment()
        .addString("unknowntool.bpmn20.xml", model(extraInputs))
        .deploy();
    if (mcpClient != null) {
      // After buildProcessEngine, which rebuilds the connector registry from the classpath.
      installConnectorWithMcpServer(mcpClient);
    }

    ProcessInstance instance =
        engine.getRuntimeService().startProcessInstanceByKey("unknownToolAgent");
    List<Job> jobs = engine.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(jobs).as("the turn's job").hasSize(1);
    TurnSteps.runToTheEnd(engine, instance.getId());
    return instance;
  }

  // ── a model that never stops hallucinating ──────────────────────────────

  /**
   * The repeating half of the acceptance criterion: "Repeating unknown names ends the turn at
   * the cap with the readable answer." Mirrors {@link
   * AdHocRunawayTurnTest#theModelIsStoppedAtTheScopesOwnLimitRatherThanLangChain4jsDefault()}
   * with a hallucinated name standing in for an always-successful tool call — if the fix ever
   * regressed to a strategy that returns a result instead of throwing, {@code stubCalls} would
   * run past {@code CALL_LIMIT} before the turn runner's own cap check ever saw it.
   */
  @Test
  public void repeatingUnknownNamesEndsTheTurnAtTheCapWithoutExtraModelCalls() {
    responder = call -> toolCall("call_" + call, "reviewDocuments", "{}");

    ProcessInstance instance = runTheTurn();

    List<Incident> incidents = engine.getRuntimeService().createIncidentQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(incidents).as("incidents raised by repeated unknown tool names").isEmpty();

    HistoricProcessInstance history = engine.getHistoryService()
        .createHistoricProcessInstanceQuery().processInstanceId(instance.getId()).singleResult();
    assertThat(history.getState())
        .as("the instance, after a turn that only ever hallucinated")
        .isEqualTo(HistoricProcessInstance.STATE_COMPLETED);

    assertThat(stubCalls.get())
        .as("model calls in one turn, for a call limit of " + CALL_LIMIT
            + " — more would mean a hallucination triggered a hidden extra model call")
        .isEqualTo(CALL_LIMIT);

    HistoricVariableInstance output = engine.getHistoryService()
        .createHistoricVariableInstanceQuery()
        .processInstanceId(instance.getId()).variableName("agentOutput").singleResult();
    assertThat((String) output.getValue()).contains("limit of " + CALL_LIMIT + " model calls");
  }

  // ── a model that corrects itself ────────────────────────────────────────

  /**
   * The other half of the acceptance criterion: "its next call with a valid tool succeeds, with
   * no job failure and no incident." The model hallucinates once, reads the correction, and
   * answers on its very next call — the turn ends normally, not at the cap.
   */
  @Test
  public void aModelThatCorrectsItselfAfterOneHallucinationFinishesTheTurn() {
    responder = call -> (call == 1)
        ? toolCall("call_1", "reviewDocuments", "{}")
        : textAnswer("Done, after checking my tools.");

    ProcessInstance instance = runTheTurn();

    List<Incident> incidents = engine.getRuntimeService().createIncidentQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(incidents).as("incidents raised by a single corrected hallucination").isEmpty();

    HistoricProcessInstance history = engine.getHistoryService()
        .createHistoricProcessInstanceQuery().processInstanceId(instance.getId()).singleResult();
    assertThat(history.getState())
        .as("the instance, after the model corrected itself")
        .isEqualTo(HistoricProcessInstance.STATE_COMPLETED);

    assertThat(stubCalls.get())
        .as("exactly one model call to hallucinate and one to answer — no job failure and retry")
        .isEqualTo(2);

    HistoricVariableInstance output = engine.getHistoryService()
        .createHistoricVariableInstanceQuery()
        .processInstanceId(instance.getId()).variableName("agentOutput").singleResult();
    assertThat((String) output.getValue()).isEqualTo("Done, after checking my tools.");

    // The acceptance criterion's own wording: the model "receives a tool result naming the
    // unknown tool and the available tools". Asserted on the connector's own sentence, which
    // nothing else in the body produces — the bare names would not do: 'work' and
    // 'completeScope' are in the tools array of every request, and 'reviewDocuments' is in the
    // assistant message echoed back from memory, so a correction of "" would pass that.
    assertThat(requestBodies).as("one request per model call").hasSize(2);
    assertThat(requestBodies.get(1))
        .as("the correction the model read before its second call")
        .contains("There is no tool named 'reviewDocuments'")
        .contains("Available tools in this ad hoc sub process: work, completeScope");

    // The correction names tools from AgentConnectorImpl#availableTools; the tools array of the
    // same request is what AdHocToolProvider actually registered. Both are in this one body, so
    // a name that drifted between them — a literal reintroduced on either side — shows up here
    // as a catalogue advertising something the model was never offered.
    assertThat(requestBodies.get(1))
        .as("the names the correction advertises must be the ones the provider registered")
        .contains("\"name\" : \"work\"")
        .contains("\"name\" : \"completeScope\"");
  }

  /**
   * The acceptance criterion end to end: "its next call with a valid tool succeeds, with no job
   * failure and no incident." The two tests around this one stop at a text answer; here the
   * model does what the correction told it to — it calls a real tool — and the activity
   * genuinely starts, which is the one thing a conversation repaired out of band could break.
   */
  @Test
  public void afterTheCorrectionTheModelsNextCallWithAValidToolActuallyRuns() {
    responder = call -> {
      if (call == 1) {
        return toolCall("call_1", "reviewDocuments", "{}");
      }
      return (call == 2) ? toolCall("call_2", "work", "{}") : textAnswer("Work is done.");
    };

    ProcessInstance instance = runTheTurn();

    List<Incident> incidents = engine.getRuntimeService().createIncidentQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(incidents).as("incidents on the correct-then-call path").isEmpty();

    HistoricVariableInstance done = engine.getHistoryService()
        .createHistoricVariableInstanceQuery()
        .processInstanceId(instance.getId()).variableName("done").singleResult();
    assertThat(done)
        .as("the activity the model called after reading the correction must really have run")
        .isNotNull();

    HistoricProcessInstance history = engine.getHistoryService()
        .createHistoricProcessInstanceQuery().processInstanceId(instance.getId()).singleResult();
    assertThat(history.getState()).isEqualTo(HistoricProcessInstance.STATE_COMPLETED);

    assertThat(stubCalls.get())
        .as("hallucinate, call the real tool, answer — three calls, none of them a retry")
        .isEqualTo(3);
    assertThat(requestBodies.get(1))
        .as("the correction that made the second call possible")
        .contains("There is no tool named 'reviewDocuments'");
  }

  /**
   * Two made-up names in one answer, which is an ordinary way for a model to fail. Only the
   * first ever reaches the strategy — {@code ToolService} abandons the batch there — so the
   * second is corrected by {@link AgentConnectorImpl#resolveHallucinatedTool} on the strength of
   * its name, not of the exception's id. Told it "may already have started" instead, the model
   * would be waiting for turn state that can never appear for a tool that does not exist, and
   * would never learn which names it may use.
   */
  @Test
  public void bothMadeUpNamesInOneAnswerAreCorrected() {
    responder = call -> (call == 1)
        ? twoToolCalls("call_1a", "reviewDocuments", "{}", "call_1b", "summarizeDocs", "{}")
        : textAnswer("Understood.");

    ProcessInstance instance = runTheTurn();

    List<Incident> incidents = engine.getRuntimeService().createIncidentQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(incidents).as("incidents from two unknown names in one answer").isEmpty();

    assertThat(requestBodies).as("one request per model call").hasSize(2);
    assertThat(requestBodies.get(1))
        .as("each made-up name gets its own correction, not a sibling note — neither ran and"
            + " neither exists, so the catalogue is what both need")
        .contains("There is no tool named 'reviewDocuments'")
        .contains("There is no tool named 'summarizeDocs'")
        .doesNotContain("ran before that happened")
        .doesNotContain("This call did not run");
  }

  // ── a hallucination after a step that already called a real tool ────────

  /**
   * The direction {@link AgentConnectorImpl#lastAiMessageWithToolCalls} searches in, which only
   * a turn of more than one step can tell apart. By the second step the conversation already
   * holds the first step's {@code AiMessage} with its answered call; answering <em>that</em> one
   * again would write a duplicate {@code tool_call_id} and leave this step's call open — the
   * permanently invalid conversation the repair exists to prevent. Every other test in this
   * class hallucinates on the first call, where the two directions cannot be told apart.
   */
  @Test
  public void aHallucinationAfterARealToolStepAnswersTheNewCallAndNotTheOldOne() {
    responder = call -> {
      if (call == 1) {
        return toolCall("call_1", "work", "{}");
      }
      return (call == 2) ? toolCall("call_2", "reviewDocuments", "{}") : textAnswer("Understood.");
    };

    ProcessInstance instance = runTheTurn();

    List<Incident> incidents = engine.getRuntimeService().createIncidentQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(incidents).as("incidents from a hallucination in the second step").isEmpty();

    assertThat(requestBodies).as("work, then the made-up name, then the answer").hasSize(3);
    String afterTheCorrection = requestBodies.get(2);

    assertThat(afterTheCorrection)
        .as("this step's call is the one that gets the correction")
        .contains("There is no tool named 'reviewDocuments'");
    assertThat(countOf(afterTheCorrection, "\"tool_call_id\" : \"call_2\""))
        .as("the new call is answered exactly once")
        .isEqualTo(1);
    assertThat(countOf(afterTheCorrection, "\"tool_call_id\" : \"call_1\""))
        .as("the earlier step's call keeps its one original answer — a second would be a"
            + " duplicate tool_call_id, which every provider rejects")
        .isEqualTo(1);
    assertThat(afterTheCorrection)
        .as("the real tool of the first step is not retroactively told a sibling broke it")
        .doesNotContain("ran before that happened")
        .doesNotContain("This call did not run");
  }

  private static int countOf(String haystack, String needle) {
    int count = 0;
    for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
      count++;
    }
    return count;
  }

  // ── a memory window too small to hold the step ──────────────────────────

  /**
   * The corrections are appended in one write rather than one {@code add} per answer, because
   * {@link dev.langchain4j.memory.chat.MessageWindowChatMemory} trims to its window on every
   * {@code add} and evicting an {@code AiMessage} takes its already-written results with it. Per
   * answer, a batch larger than the window leaves the last correction behind as a tool message
   * whose tool call is gone — which every OpenAI-compatible provider rejects, on this and on
   * every later request of the conversation.
   *
   * <p>A window this small is reachable: {@code AgenticAdHocParseListener} only enforces a
   * minimum for a {@code perActivity} conversation, and this scope does not ask for one.
   */
  @Test
  public void aBatchLargerThanTheMemoryWindowLeavesNoOrphanedToolResult() {
    responder = call -> (call == 1) ? threeMadeUpCalls() : textAnswer("Understood.");

    ProcessInstance instance = runTheTurn(
        "<camunda:inputParameter name='chatMemoryMaxMessages'>3</camunda:inputParameter>");

    List<Incident> incidents = engine.getRuntimeService().createIncidentQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(incidents).as("incidents from a batch larger than the window").isEmpty();

    assertThat(requestBodies).as("one request per model call").hasSize(2);
    String next = requestBodies.get(1);

    // Asserted outright rather than under an "if a tool message survived": at this window the
    // eviction cascade takes the AiMessage and all three corrections together, so what must be
    // observed is that neither is left behind. Guarded, the assertion would never run and the
    // test would pass with the repair deleted.
    assertThat(next)
        .as("the window cannot hold the step, so the call and its answers go together —"
            + " a tool message without its tool_calls would be permanently invalid")
        .doesNotContain("\"role\" : \"tool\"")
        .doesNotContain("tool_calls");
  }

  /**
   * The same batch at a window that can hold it: the corrections do reach the model. Without
   * this, the window pair would only ever show the degraded outcome and a repair that wrote
   * nothing at all would look identical.
   */
  @Test
  public void aBatchThatFitsTheMemoryWindowReachesTheModel() {
    responder = call -> (call == 1) ? threeMadeUpCalls() : textAnswer("Understood.");

    ProcessInstance instance = runTheTurn(
        "<camunda:inputParameter name='chatMemoryMaxMessages'>12</camunda:inputParameter>");

    List<Incident> incidents = engine.getRuntimeService().createIncidentQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(incidents).as("incidents from a batch the window can hold").isEmpty();

    assertThat(requestBodies).as("one request per model call").hasSize(2);
    assertThat(requestBodies.get(1))
        .as("every made-up name of the batch is corrected, and each call is answered")
        .contains("There is no tool named 'alpha'")
        .contains("There is no tool named 'beta'")
        .contains("There is no tool named 'gamma'")
        .containsPattern("\"tool_call_id\"\\s*:\\s*\"c1\"")
        .containsPattern("\"tool_call_id\"\\s*:\\s*\"c2\"")
        .containsPattern("\"tool_call_id\"\\s*:\\s*\"c3\"");
  }

  /**
   * A window of one drops the model's call as LangChain4j adds it, so the repair finds nothing
   * to answer. Nothing is wrong with the conversation then — no call means no orphan — and the
   * step must end quietly rather than raise the incident this mechanism exists to avoid.
   */
  @Test
  public void aWindowTooSmallToHoldTheCallEndsTheStepWithoutAnIncident() {
    responder = call -> (call == 1)
        ? toolCall("call_1", "reviewDocuments", "{}")
        : textAnswer("Understood.");

    ProcessInstance instance = runTheTurn(
        "<camunda:inputParameter name='chatMemoryMaxMessages'>1</camunda:inputParameter>");

    List<Incident> incidents = engine.getRuntimeService().createIncidentQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(incidents)
        .as("a window too small to repair must not become an incident")
        .isEmpty();

    HistoricProcessInstance history = engine.getHistoryService()
        .createHistoricProcessInstanceQuery().processInstanceId(instance.getId()).singleResult();
    assertThat(history.getState())
        .as("the turn still ends, degraded but clean")
        .isEqualTo(HistoricProcessInstance.STATE_COMPLETED);

    assertThat(requestBodies.get(requestBodies.size() - 1))
        .as("no orphaned tool message may be left behind")
        .doesNotContain("\"role\" : \"tool\"");
  }

  /**
   * The gap round 5 of the CIB7-2150 review confirmed by mutation: the one signal an operator has
   * that {@code chatMemoryMaxMessages} is too small to hold a step's correction — inverting or
   * deleting the check in {@code resolveHallucinatedTool} left the whole suite green, because
   * nothing before this test read the log it writes to. Same scenario as {@link
   * #aBatchLargerThanTheMemoryWindowLeavesNoOrphanedToolResult}, with the WARN itself pinned
   * rather than only the conversation it describes.
   */
  @Test
  public void aBatchLargerThanTheMemoryWindowLogsThatTheCorrectionDidNotSurvive() {
    responder = call -> (call == 1) ? threeMadeUpCalls() : textAnswer("Understood.");
    Logger logger = (Logger) LoggerFactory.getLogger(AgentConnectorImpl.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      runTheTurn("<camunda:inputParameter name='chatMemoryMaxMessages'>3</camunda:inputParameter>");

      List<ILoggingEvent> survivalWarnings = new ArrayList<ILoggingEvent>();
      for (ILoggingEvent event : appender.list) {
        if (event.getFormattedMessage().contains("did not survive the memory window")) {
          survivalWarnings.add(event);
        }
      }
      assertThat(survivalWarnings)
          .as("the window dropped all three corrections together with the call — an operator"
              + " watching the log must see this rather than infer it from a silent conversation")
          .hasSize(1);
      assertThat(survivalWarnings.get(0).getLevel()).isEqualTo(Level.WARN);
      assertThat(survivalWarnings.get(0).getFormattedMessage())
          .contains("3 tool call(s)")
          .contains("Raise it to at least 6 messages");
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  /**
   * The mirror of the test above: a window that can hold the step must not log the same warning
   * — pinning only the positive case would let a strategy report "did not survive" unconditionally
   * and still pass.
   */
  @Test
  public void aBatchThatFitsTheMemoryWindowLogsNoSurvivalWarning() {
    responder = call -> (call == 1) ? threeMadeUpCalls() : textAnswer("Understood.");
    Logger logger = (Logger) LoggerFactory.getLogger(AgentConnectorImpl.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      runTheTurn("<camunda:inputParameter name='chatMemoryMaxMessages'>12</camunda:inputParameter>");

      for (ILoggingEvent event : appender.list) {
        assertThat(event.getFormattedMessage())
            .as("the window held the correction, so nothing here should claim it did not survive")
            .doesNotContain("did not survive the memory window");
      }
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  // ── a real call and a hallucinated one in the same answer ───────────────

  /**
   * The gap an earlier version of this fix had: {@code ToolService.execute} processes a
   * message's tool calls in a plain loop and abandons the whole batch, discarding whatever it
   * already computed, the moment one call's name turns out unknown. A strategy that resolves
   * only the hallucinated call's own id leaves the real sibling call's id without a matching
   * tool message — invalid for every OpenAI-compatible provider on every later request on this
   * memory id, not merely the next one. {@link AgentConnectorImpl#resolveHallucinatedTool}
   * guards against this by resolving every pending call of the step, not only the one named in
   * the exception.
   */
  @Test
  public void aRealToolAlongsideAHallucinatedOneInTheSameAnswerStillGetsAResult() {
    responder = call -> (call == 1)
        ? twoToolCalls("call_1a", "work", "{}", "call_1b", "reviewDocuments", "{}")
        : textAnswer("Done.");

    ProcessInstance instance = runTheTurn();

    List<Incident> incidents = engine.getRuntimeService().createIncidentQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(incidents).as("incidents from a mixed real+hallucinated batch").isEmpty();

    HistoricProcessInstance history = engine.getHistoryService()
        .createHistoricProcessInstanceQuery().processInstanceId(instance.getId()).singleResult();
    assertThat(history.getState())
        .as("the instance, after a mixed batch resolved cleanly")
        .isEqualTo(HistoricProcessInstance.STATE_COMPLETED);

    assertThat(requestBodies).as("one request per model call").hasSize(2);
    assertThat(requestBodies.get(1))
        .as("both tool_call ids from the mixed batch must have a matching tool-role response,"
            + " or the real provider would reject this very request")
        .containsPattern("\"tool_call_id\"\\s*:\\s*\"call_1a\"")
        .containsPattern("\"tool_call_id\"\\s*:\\s*\"call_1b\"");
    assertThat(requestBodies.get(1))
        .as("the real sibling is told it ran before the batch stopped; the made-up one gets"
            + " the catalogue")
        .contains("'work' ran before that happened")
        .contains("There is no tool named 'reviewDocuments'")
        .doesNotContain("This call did not run");

    // The premise of this branch: a sibling before the invented name genuinely started, and
    // nothing rolls that back once this path returns normally. If it never ran, telling the
    // model not to repeat it would silently drop the work.
    HistoricVariableInstance done = engine.getHistoryService()
        .createHistoricVariableInstanceQuery()
        .processInstanceId(instance.getId()).variableName("done").singleResult();
    assertThat(done)
        .as("the real tool of the mixed batch ran before the batch was abandoned")
        .isNotNull();
  }

  /**
   * The mirror of the test above, and the case that matters more: the real tool sits
   * <em>after</em> the invented name. {@code ToolService} works an answer's calls in order and
   * abandons the rest at the first name with no executor, so this one never ran at all. Told it
   * "may already have taken effect", the model would drop an activity it asked for and never
   * notice — the scope would finish with work the model believes it started.
   */
  @Test
  public void aRealToolAfterTheHallucinatedOneIsToldItNeverRan() {
    responder = call -> (call == 1)
        ? twoToolCalls("call_1a", "reviewDocuments", "{}", "call_1b", "work", "{}")
        : textAnswer("Done.");

    ProcessInstance instance = runTheTurn();

    List<Incident> incidents = engine.getRuntimeService().createIncidentQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(incidents).as("incidents from an invented-first batch").isEmpty();

    // The premise, asserted rather than assumed: the batch stopped at the invented name, so
    // the activity behind 'work' never started and there is no result variable for it.
    HistoricVariableInstance done = engine.getHistoryService()
        .createHistoricVariableInstanceQuery()
        .processInstanceId(instance.getId()).variableName("done").singleResult();
    assertThat(done)
        .as("a call after the invented name is never reached, so the activity did not run")
        .isNull();

    assertThat(requestBodies).as("one request per model call").hasSize(2);
    assertThat(requestBodies.get(1))
        .as("both calls are answered, or the provider would reject this request")
        .containsPattern("\"tool_call_id\"\\s*:\\s*\"call_1a\"")
        .containsPattern("\"tool_call_id\"\\s*:\\s*\"call_1b\"");
    assertThat(requestBodies.get(1))
        .as("the call that never ran must be told so, and invited to ask again — not warned"
            + " off repeating an effect that does not exist")
        .contains("This call did not run")
        .contains("Nothing of it took effect")
        .contains("There is no tool named 'reviewDocuments'")
        .doesNotContain("'work' ran before that happened");
  }

  /**
   * The gap round 5 of the CIB7-2150 review found: a sibling before the invented name that was
   * <em>refused</em>, not run. {@link AgentConnectorImpl.FailureAsResult} turns {@link
   * AdHocToolRefusedException} into an ordinary return value, so {@code ToolService} treats the
   * refused call exactly like a successful one and keeps going to the next call in the batch —
   * which is why it sits before the boundary here the same way a genuine success would. 'work'
   * declares no parameters, so passing it one is refused by {@link
   * AdHocToolProvider#arguments} before the activity's own expression ever runs; the test's
   * oracle for "nothing happened" is the same {@code done} variable the mirror test below uses
   * for "it genuinely ran".
   */
  @Test
  public void aRefusedToolBeforeTheHallucinatedOneIsToldBothPastsArePossible() {
    responder = call -> (call == 1)
        ? twoToolCalls("call_1a", "work", "{\\\"amount\\\":5}", "call_1b", "reviewDocuments", "{}")
        : textAnswer("Understood.");

    ProcessInstance instance = runTheTurn();

    List<Incident> incidents = engine.getRuntimeService().createIncidentQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(incidents).as("incidents from a refused call ahead of a hallucinated one").isEmpty();

    HistoricVariableInstance done = engine.getHistoryService()
        .createHistoricVariableInstanceQuery()
        .processInstanceId(instance.getId()).variableName("done").singleResult();
    assertThat(done)
        .as("the refusal happened before startActivity, so the activity's own expression never ran")
        .isNull();

    assertThat(requestBodies).as("one request per model call").hasSize(2);
    assertThat(requestBodies.get(1))
        .as("the refused call had an executor invoked for it — same as a real success, from"
            + " ToolService's own view — but its outcome was a refusal the batch then discarded;"
            + " the model must not be assured an effect that a refusal never had")
        .contains("'work' ran before that happened")
        .contains("if it was refused instead")
        .doesNotContain("so its effect stands")
        .contains("There is no tool named 'reviewDocuments'");
  }

  // ── with an MCP server on the scope ─────────────────────────────────────

  private static final String MCP_SERVER_INPUT =
      "<camunda:inputParameter name='mcpServers'>"
          + "[{\"url\":\"http://127.0.0.1:1/mcp\",\"name\":\"engine\"}]"
          + "</camunda:inputParameter>";

  /**
   * The correction's MCP half, end to end. Every other test here runs without an MCP server, so
   * the wiring from the connector's live clients into {@code availableTools} is exercised
   * nowhere: replacing {@code mcpClients} with an empty list at that call site used to pass the
   * whole suite while a real deployment's model was told its MCP tools do not exist.
   */
  @Test
  public void theCorrectionListsTheScopesMcpToolsTheWayTheModelWasOfferedThem() {
    McpClient engine = mock(McpClient.class);
    when(engine.listTools()).thenReturn(Arrays.asList(
        ToolSpecification.builder().name("add_comment").description("comments").build(),
        ToolSpecification.builder().name("find_task").description("searches").build()));
    responder = call -> (call == 1)
        ? toolCall("call_1", "reviewDocuments", "{}")
        : textAnswer("Understood.");

    ProcessInstance instance = runTheTurn(MCP_SERVER_INPUT, engine);

    List<Incident> incidents = incidentsOf(instance);
    assertThat(incidents).as("incidents on a scope with an MCP server").isEmpty();

    assertThat(requestBodies).as("one request per model call").hasSize(2);
    assertThat(requestBodies.get(1))
        .as("the MCP tools belong in the catalogue the correction names, under the very"
            + " names the toolNameMapper offered them by")
        .contains(AgentConnectorImpl.mcpToolName("engine", "add_comment"))
        .contains(AgentConnectorImpl.mcpToolName("engine", "find_task"))
        .contains("There is no tool named 'reviewDocuments'");
  }

  /**
   * An MCP server that stops answering between the call and the repair. Its tool is missing from
   * the freshly listed catalogue, but it is not invented — only the name the strategy threw for
   * is known to be that. Told "there is no tool named 'engine__add_comment'", the model would be
   * denied a tool it genuinely has, which is the failure the shared naming exists to prevent.
   */
  @Test
  public void anUnreachableMcpServerDoesNotMakeItsToolLookInvented() {
    List<ToolSpecification> served = Collections.singletonList(
        ToolSpecification.builder().name("add_comment").description("comments").build());
    AtomicInteger listings = new AtomicInteger();
    McpClient flaky = mock(McpClient.class);
    // The server answers while the turn is being set up — the provenance build and the tool
    // provider each ask it once, so the model really is offered the tool — and is gone by the
    // time the repair asks a third time. Counting the calls is what makes "it went away in
    // between" expressible against a mock; if the setup ever stops asking twice, this stops
    // reproducing the case rather than silently testing something else, because the assertion
    // below requires the tool to have been offered.
    when(flaky.listTools()).thenAnswer(invocation -> {
      if (listings.incrementAndGet() > 2) {
        throw new IllegalStateException("connection refused");
      }
      return served;
    });
    // The call itself succeeds — that is the point: the comment is posted, and only then does
    // the server stop answering. A mock returns null here by default, which a real client never
    // does, and FailureAsResult would NPE on it.
    when(flaky.executeTool(any(ToolExecutionRequest.class)))
        .thenReturn(ToolExecutionResult.builder().resultText("posted").build());
    when(flaky.executeTool(any(ToolExecutionRequest.class), any(InvocationContext.class)))
        .thenReturn(ToolExecutionResult.builder().resultText("posted").build());
    String mcpTool = AgentConnectorImpl.mcpToolName("engine", "add_comment");
    responder = call -> (call == 1)
        ? twoToolCalls("call_1a", mcpTool, "{}", "call_1b", "reviewDocuments", "{}")
        : textAnswer("Understood.");

    ProcessInstance instance = runTheTurn(MCP_SERVER_INPUT, flaky);

    assertThat(incidentsOf(instance)).as("incidents from a server that went away").isEmpty();

    assertThat(requestBodies).as("one request per model call").hasSize(2);
    assertThat(requestBodies.get(0))
        .as("the premise: the model really was offered the MCP tool before the server went away")
        .contains(mcpTool);
    assertThat(listings.get())
        .as("the repair asked the server again, and that is the ask that failed")
        .isGreaterThan(2);
    assertThat(requestBodies.get(1))
        .as("the made-up name is the only one told it does not exist")
        .contains("There is no tool named 'reviewDocuments'")
        .doesNotContain("There is no tool named '" + mcpTool + "'");
    assertThat(requestBodies.get(1))
        .as("the MCP call ran before the batch was abandoned, so the model is warned off"
            + " repeating it rather than told it never existed")
        .contains("'" + mcpTool + "' ran before that happened");
  }

  private List<Incident> incidentsOf(ProcessInstance instance) {
    return engine.getRuntimeService().createIncidentQuery()
        .processInstanceId(instance.getId()).list();
  }
}
