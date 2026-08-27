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
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.runtime.VariableInstance;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.variable.VariableMap;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.connect.ai.agent.AgentConnectorConstants;
import org.cibseven.connect.ai.agent.AgentRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Engine-backed counterpart to {@link ProcessContextResolverTest}, which drives
 * the resolver through a lambda and therefore proves nothing about the two
 * mechanisms the feature actually depends on: that the connector finds the
 * running {@code ExecutionEntity} on its own thread, and that
 * {@code getVariableTyped(name, false)} returns real engine types for variables
 * that went through the database.
 *
 * <p>The connector is invoked from a {@link JavaDelegate} rather than through a
 * {@code camunda:connector} element. That is deliberate and matches the design:
 * the whole point of CIB7-1843's variable half is that the connector reads the
 * execution directly instead of going through Connect's variable scope, which
 * unwraps every {@code TypedValue}. A delegate gives the same real command
 * context and the same real variables without pulling the connect-plugin into
 * this module's test classpath.
 */
public class ProcessContextEngineTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** What the delegate saw, so the assertions can inspect it. */
  static volatile String capturedSystemMessage;
  static volatile RuntimeException capturedFailure;

  /**
   * Built once for the whole class. JUnit 4 instantiates the test class per test
   * method, so a per-instance engine would try to create the schema again on the
   * same in-memory database and fail on the second test.
   */
  private static final ProcessEngine ENGINE = buildInMemoryEngine();

  @Rule
  public ProcessEngineRule engineRule = new ProcessEngineRule(ENGINE);

  private static ProcessEngine buildInMemoryEngine() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setJdbcUrl("jdbc:h2:mem:process-context-test;DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    return configuration.buildProcessEngine();
  }

  @Before
  public void setUp() {
    capturedSystemMessage = null;
    capturedFailure = null;
    deployProcess();
  }

  @After
  public void tearDown() {
    capturedSystemMessage = null;
    capturedFailure = null;
    engineRule.getRepositoryService().createDeploymentQuery().list()
        .forEach(deployment ->
            engineRule.getRepositoryService().deleteDeployment(deployment.getId(), true));
  }

  // ── the delegate under test ────────────────────────────────────────────────

  /**
   * Captures the outgoing request instead of talking to a provider, while still
   * carrying the real {@link AgentChatListener} so the audit path runs exactly
   * as it does in production — {@code ChatModel.chat} fires the listeners
   * returned by {@link #listeners()}.
   */
  static final class CapturingChatModel implements ChatModel {
    private final List<ChatModelListener> listeners;

    CapturingChatModel(ChatModelListener listener) {
      this.listeners = List.of(listener);
    }

    @Override
    public List<ChatModelListener> listeners() {
      return listeners;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
      for (ChatMessage message : request.messages()) {
        if (message instanceof SystemMessage) {
          capturedSystemMessage = ((SystemMessage) message).text();
        }
      }
      return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
    }
  }

  /**
   * Replaces only the provider call. The listener is created and published
   * exactly as {@code AgentConnectorImpl.createChatModel} does, because the
   * chat-log assertions below depend on that wiring being real rather than
   * stubbed away.
   */
  static final class CapturingConnector extends AgentConnectorImpl {
    @Override
    protected ChatModel createChatModel(AgentRequest request, String apiKey, String baseUrl,
        Map<String, String> customHeaders) {
      AgentChatListener listener =
          new AgentChatListener(request.getModel(), baseUrl, request.getPersistChatLog());
      ProcessStarterToolContext.setActiveListener(listener);
      return new CapturingChatModel(listener);
    }
  }

  /**
   * Runs the connector inside a real engine command. The declared allowlist is
   * read from process variables so each test can vary it without a separate
   * delegate class.
   */
  public static class AgentDelegate implements JavaDelegate {
    @Override
    public void execute(DelegateExecution execution) {
      CapturingConnector connector = new CapturingConnector();
      AgentRequest request = connector.createRequest()
          .agentName("context-agent")
          .message("What is the order about?")
          .instruction("be brief")
          .contextVariables((String) execution.getVariable("_declared"))
          .optionalContextVariables((String) execution.getVariable("_optional"));
      connector.execute(request);
    }
  }

  // ── the block reaches the model ────────────────────────────────────────────

  @Test
  public void shouldRenderDeclaredVariablesIntoTheSystemPromptWithTheirEngineTypes() {
    start(Variables.createVariables()
        .putValue("_declared", "orderId,totalAmount,approved,comment,notSet")
        .putValue("_optional", "notSet")
        .putValue("orderId", "4711")
        .putValue("totalAmount", 199.99d)
        .putValue("approved", Boolean.FALSE)
        .putValue("comment", ""));

    assertThat(capturedSystemMessage)
        .startsWith("be brief")
        .contains(AgentConnectorConstants.CONTEXT_BLOCK_OPEN)
        .contains(AgentConnectorConstants.CONTEXT_BLOCK_CLOSE)
        .contains("orderId (string) = \"4711\"")
        .contains("totalAmount (double) = 199.99")
        .contains("approved (boolean) = false")
        // Empty is a value and stays visibly different from both null and absent.
        .contains("comment (string) = \"\"")
        .contains("notSet = (absent)");
  }

  @Test
  public void shouldDistinguishAnExplicitNullFromAnAbsentVariable() {
    start(Variables.createVariables()
        .putValue("_declared", "nulled,neverSet")
        .putValue("_optional", "nulled,neverSet")
        .putValueTyped("nulled", Variables.stringValue(null)));

    assertThat(capturedSystemMessage)
        .contains("nulled (string) = null")
        .contains("neverSet = (absent)");
  }

  @Test
  public void shouldRenderAFileVariableAsADescriptorInsteadOfItsBytes() {
    start(Variables.createVariables()
        .putValue("_declared", "invoice")
        .putValueTyped("invoice", Variables.fileValue("invoice.pdf")
            .file("%PDF-1.7 pretend binary".getBytes())
            .mimeType("application/pdf")
            .create()));

    assertThat(capturedSystemMessage)
        .contains("invoice (file) = (file \"invoice.pdf\", application/pdf, "
            + "content not sent to the model)")
        .doesNotContain("%PDF-1.7");
  }

  @Test
  public void shouldLeaveThePromptUnchangedWhenNothingIsDeclared() {
    start(Variables.createVariables().putValue("orderId", "4711"));

    assertThat(capturedSystemMessage).isEqualTo("be brief");
  }

  // ── required variables abort the activity ──────────────────────────────────

  @Test
  public void shouldFailTheActivityWhenARequiredVariableIsMissing() {
    Throwable thrown = catchThrowable(() -> start(Variables.createVariables()
        .putValue("_declared", "orderId")));

    assertThat(rootCause(thrown))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("orderId")
        .hasMessageContaining("the agent was not invoked");

    // Not "the model answered over missing data" — the model was never asked.
    assertThat(capturedSystemMessage).isNull();
  }

  // ── audit ──────────────────────────────────────────────────────────────────

  @Test
  public void shouldRecordAContextAuditEventCarryingHashesButNoValues() throws Exception {
    start(Variables.createVariables()
        .putValue("_declared", "orderId,missing")
        .putValue("_optional", "missing")
        .putValue("orderId", "4711"));

    Map<String, Object> contextEvent = singleEventOfType("context");

    assertThat(contextEvent)
        .containsEntry("declared", 2)
        .containsEntry("sent", 1)
        .containsEntry("omitted", 0);
    // Same envelope as every other audit event — Art. 12 correlation.
    assertThat(contextEvent).containsKeys("runId", "eventSeq", "timestamp",
        "processInstanceId", "activityId");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> entries =
        (List<Map<String, Object>>) contextEvent.get("variables");
    assertThat(entries).hasSize(2);
    assertThat(entries.get(0))
        .containsEntry("name", "orderId")
        .containsEntry("present", true)
        .containsEntry("type", "string")
        .containsEntry("valueLength", 4);
    assertThat((String) entries.get(0).get("valueSha256")).startsWith("sha256:");
    // The value itself belongs in the request event's system message, not here.
    assertThat(entries.get(0).values()).doesNotContain("4711");
    assertThat(entries.get(1)).containsEntry("name", "missing").containsEntry("present", false);
  }

  /**
   * Documents a real limitation rather than a feature: the context event is
   * emitted before the required-variable check, but a failing activity rolls its
   * transaction back, and the chat-log variable goes with it. The record of the
   * aborted run is therefore the incident and the exception message, not the
   * audit variable. Anyone who needs the aborted attempt in the audit trail has
   * to route events to an external sink — the same caveat the chat-log opt-out
   * already carries.
   */
  @Test
  public void shouldRollBackTheContextEventWhenARequiredVariableAbortsTheRun() {
    catchThrowable(() -> start(Variables.createVariables()
        .putValue("_declared", "orderId")));

    assertThat(engineRule.getRuntimeService().createVariableInstanceQuery()
        .variableNameLike(AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX + "%")
        .count()).isZero();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static Throwable rootCause(Throwable thrown) {
    assertThat(thrown).as("expected the activity to fail").isNotNull();
    Throwable current = thrown;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    return current;
  }

  private void start(VariableMap variables) {
    engineRule.getRuntimeService().startProcessInstanceByKey("processContextEngineTest", variables);
  }

  private Map<String, Object> singleEventOfType(String type) throws Exception {
    List<VariableInstance> variables = engineRule.getRuntimeService()
        .createVariableInstanceQuery()
        .variableNameLike(AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX + "%")
        .list();
    assertThat(variables).as("chat-log variable").hasSize(1);

    List<Map<String, Object>> events = MAPPER.readValue(
        (String) variables.get(0).getValue(),
        new TypeReference<List<Map<String, Object>>>() {});

    List<Map<String, Object>> matching = events.stream()
        .filter(event -> type.equals(event.get("type")))
        .collect(java.util.stream.Collectors.toList());
    assertThat(matching).as("events of type " + type).hasSize(1);
    return matching.get(0);
  }

  private void deployProcess() {
    String bpmn = ""
        + "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + "             targetNamespace='http://cibseven.org/test'>"
        + "  <process id='processContextEngineTest' isExecutable='true'"
        + "           camunda:historyTimeToLive='1'>"
        + "    <startEvent id='start'/>"
        + "    <sequenceFlow id='f1' sourceRef='start' targetRef='agent'/>"
        + "    <serviceTask id='agent'"
        + "                 camunda:class='" + AgentDelegate.class.getName() + "'/>"
        + "    <sequenceFlow id='f2' sourceRef='agent' targetRef='wait'/>"
        // The instance parks here so its variables stay runtime variables. A
        // process that ends in the same transaction has all of them deleted,
        // and the chat log would only be reachable via includeDeleted() — which
        // is not how an operator sees it in Cockpit either.
        + "    <userTask id='wait'/>"
        + "    <sequenceFlow id='f3' sourceRef='wait' targetRef='end'/>"
        + "    <endEvent id='end'/>"
        + "  </process>"
        + "</definitions>";

    engineRule.getRepositoryService()
        .createDeployment()
        .addString("processContextEngineTest.bpmn20.xml", bpmn)
        .deploy();
  }
}
