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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.Map;

import org.cibseven.connect.ai.agent.AgentConnector;
import org.cibseven.connect.ai.agent.AgentConnectorConstants;
import org.cibseven.connect.ai.agent.AgentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Connector-level behaviour of the {@code contextVariables} allowlist that does
 * not need a running engine: the default (no context configured) must stay
 * byte-for-byte what it was before this feature, the block must land after the
 * instruction, and configuring the parameter outside a BPMN execution must fail
 * loudly rather than silently drop the declared context.
 *
 * <p>The engine-facing half — reading real typed variables off a real execution
 * — lives in {@link ProcessContextEngineTest}.
 */
public class AgentConnectorImplContextTest {

  /** Captures the outgoing request so the system message can be inspected. */
  static final class CapturingChatModel implements ChatModel {
    volatile ChatRequest captured;

    @Override
    public ChatResponse doChat(ChatRequest request) {
      this.captured = request;
      return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
    }
  }

  static final class CapturingConnector extends AgentConnectorImpl {
    final CapturingChatModel model = new CapturingChatModel();

    @Override
    protected ChatModel createChatModel(AgentRequest request, String apiKey, String baseUrl,
        Map<String, String> customHeaders) {
      return model;
    }
  }

  private CapturingConnector connector;

  @BeforeEach
  public void setUp() {
    connector = new CapturingConnector();
  }

  private String systemMessage() {
    for (ChatMessage message : connector.model.captured.messages()) {
      if (message instanceof SystemMessage) {
        return ((SystemMessage) message).text();
      }
    }
    return null;
  }

  // ── the default must not change ────────────────────────────────────────────

  @Test
  public void shouldNotEmitAContextBlockWhenNoContextIsDeclared() {
    AgentRequest request = connector.createRequest()
        .agentName("agent")
        .message("hello")
        .instruction("be brief");

    assertThat(connector.execute(request).getOutput()).isEqualTo("ok");
    assertThat(systemMessage()).isEqualTo("be brief");
  }

  @Test
  public void shouldNotEmitAContextBlockForBlankParameters() {
    AgentRequest request = connector.createRequest()
        .agentName("agent")
        .message("hello")
        .instruction("be brief")
        .contextVariables("   ")
        .optionalContextVariables("");

    assertThat(connector.execute(request).getOutput()).isEqualTo("ok");
    assertThat(systemMessage()).doesNotContain(AgentConnectorConstants.CONTEXT_BLOCK_OPEN);
  }

  // ── misconfiguration is loud ───────────────────────────────────────────────

  @Test
  public void shouldFailWhenContextIsDeclaredOutsideABpmnExecution() {
    AgentRequest request = connector.createRequest()
        .agentName("agent")
        .message("hello")
        .contextVariables("orderId");

    // Silently dropping declared context is the failure mode this feature
    // exists to remove, so it must not degrade to "no context".
    assertThatThrownBy(() -> connector.execute(request))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("no BPMN execution")
        .hasMessageContaining("contextVariables");
  }

  /**
   * Writing {@code ${invoice}} instead of {@code invoice} hands the connector
   * the variable's value — an {@code InputStream} for a file. That used to fail
   * with a bare {@code ClassCastException} naming neither parameter nor cause.
   */
  @Test
  public void shouldExplainThatDocumentsTakeNamesWhenGivenAValue() {
    AgentRequest request = connector.createRequest()
        .agentName("agent")
        .message("hello");
    request.setRequestParameter(AgentConnector.PARAM_NAME_DOCUMENTS,
        new ByteArrayInputStream(new byte[] {1}));

    assertThatThrownBy(() -> connector.execute(request))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("'documents' must list variable names")
        .hasMessageContaining("ByteArrayInputStream");
  }

  /** The same unchecked cast sat behind the other two getters. */
  @Test
  public void shouldNameTheParameterWhenMimeTypesOrDetailLevelAreNotText() {
    AgentRequest request = connector.createRequest();
    request.setRequestParameter(AgentConnector.PARAM_NAME_DOCUMENT_MIME_TYPES,
        Map.of("scan", "image/png"));
    request.setRequestParameter(AgentConnector.PARAM_NAME_DOCUMENT_DETAIL_LEVEL, 2);

    assertThatThrownBy(request::getDocumentMimeTypes)
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("'documentMimeTypes' must be a JSON object as text");
    assertThatThrownBy(request::getDocumentDetailLevel)
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("'documentDetailLevel' must be one of AUTO, LOW, HIGH")
        .hasMessageContaining("java.lang.Integer");
  }

  /** Documents, like context, must not silently degrade outside the engine. */
  @Test
  public void shouldFailWhenDocumentsAreDeclaredOutsideABpmnExecution() {
    AgentRequest request = connector.createRequest()
        .agentName("agent")
        .message("hello")
        .documents("invoice");

    assertThatThrownBy(() -> connector.execute(request))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("no BPMN execution")
        .hasMessageContaining("'documents'");
    assertThat(connector.model.captured).isNull();
  }

  /**
   * The empty-message check now runs before any document is
   * read, and treats a blank message as empty. Outside an execution the document
   * read would fail with its own message, so seeing the message error proves the
   * order.
   */
  @Test
  public void shouldRefuseABlankMessageWithDocumentsBeforeReadingThem() {
    AgentRequest request = connector.createRequest()
        .agentName("agent")
        .message("   ")
        .documents("invoice");

    assertThatThrownBy(() -> connector.execute(request))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("'message' is empty");
  }

  /**
   * Regression: context resolution used to sit between the
   * {@link ProcessStarterToolContext} writes and the {@code try}, so throwing
   * there left the engine reference and the caller {@code Authentication} on the
   * thread. On a pooled JobExecutor or Tomcat thread the next, unrelated task
   * would then run under someone else's identity.
   */
  @Test
  public void shouldClearTheToolContextEvenWhenContextResolutionFails() {
    AgentRequest request = connector.createRequest()
        .agentName("agent")
        .message("hello")
        .contextVariables("orderId");

    assertThatThrownBy(() -> connector.execute(request))
        .isInstanceOf(AgentConnectorException.class);

    assertThat(ProcessStarterToolContext.getEngine()).isNull();
    assertThat(ProcessStarterToolContext.getAuthentication()).isNull();
  }

  /**
   * The optional list only modifies names from the allowlist. On its own it has
   * nothing to modify, so it must not activate the feature — and in particular
   * must not fail for a missing BPMN execution it never needed.
   */
  @Test
  public void shouldNotActivateContextWhenOnlyTheOptionalListIsSet() {
    AgentRequest request = connector.createRequest()
        .agentName("agent")
        .message("hello")
        .instruction("be brief")
        .optionalContextVariables("orderId");

    assertThat(connector.execute(request).getOutput()).isEqualTo("ok");
    assertThat(systemMessage()).isEqualTo("be brief");
  }

  // ── placement inside the system prompt ─────────────────────────────────────

  @Test
  public void shouldAppendTheContextBlockAfterTheInstruction() throws Exception {
    AgentRequest request = connector.createRequest().instruction("be brief");

    String prompt = invokeBuildSystemPrompt(request, "<process-context>\nx = 1\n</process-context>");

    assertThat(prompt).isEqualTo("be brief"
        + AgentConnectorConstants.INSTRUCTION_MODE_SEPARATOR
        + "<process-context>\nx = 1\n</process-context>");
  }

  @Test
  public void shouldUseTheContextBlockAloneWhenThereIsNoInstructionOrDescription() throws Exception {
    // instructionMode 'replace' with an explicitly empty instruction still
    // resolves to the bundled default, so blank both out via a description-only
    // request is not possible — assert the helper's own null-prompt branch.
    Method m = AgentConnectorImpl.class.getDeclaredMethod("appendContextBlock",
        String.class, String.class);
    m.setAccessible(true);

    assertThat(m.invoke(null, null, "BLOCK")).isEqualTo("BLOCK");
    assertThat(m.invoke(null, "", "BLOCK")).isEqualTo("BLOCK");
    assertThat(m.invoke(null, "prompt", null)).isEqualTo("prompt");
    assertThat(m.invoke(null, "prompt", "")).isEqualTo("prompt");
  }

  /** The same composition the systemMessageTransformer applies in execute(). */
  private String invokeBuildSystemPrompt(AgentRequest request, String contextBlock)
      throws Exception {
    Method build = AgentConnectorImpl.class.getDeclaredMethod("buildSystemPrompt",
        AgentRequest.class);
    build.setAccessible(true);
    Method append = AgentConnectorImpl.class.getDeclaredMethod("appendContextBlock",
        String.class, String.class);
    append.setAccessible(true);
    return (String) append.invoke(null, build.invoke(connector, request), contextBlock);
  }
}
