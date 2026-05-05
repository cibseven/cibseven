/*
 * Copyright CIB seven GmbH and/or licensed to CIB seven GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB seven licenses this file to you under the Apache License,
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
package org.cibseven.connect.agentconnector.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.cibseven.connect.agentconnector.AgentConnector;
import org.cibseven.connect.agentconnector.AgentRequest;
import org.junit.Before;
import org.junit.Test;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatRequestParameters;

/**
 * Tests for the {@code reasoningEffort} and {@code reasoningSummary} request
 * parameters: getter/setter wiring, request-parameter map storage, and the
 * conditional model selection inside {@link AgentConnectorImpl#createChatModel}.
 *
 * <p>{@code reasoningSummary} is only available on the OpenAI Responses API,
 * so setting it must switch the connector from {@link OpenAiChatModel} to
 * {@link OpenAiResponsesChatModel}. {@code reasoningEffort} is supported on
 * both models and must be forwarded regardless.
 */
public class AgentConnectorReasoningTest {

  private AgentConnectorImpl connector;

  @Before
  public void setUp() {
    connector = new AgentConnectorImpl();
  }

  // ── Setter / getter wiring ───────────────────────────────────────────────

  @Test
  public void shouldSetAndGetReasoningEffort() {
    AgentRequest request = connector.createRequest().reasoningEffort("medium");
    assertThat(request.getReasoningEffort()).isEqualTo("medium");
  }

  @Test
  public void shouldSetAndGetReasoningSummary() {
    AgentRequest request = connector.createRequest().reasoningSummary("auto");
    assertThat(request.getReasoningSummary()).isEqualTo("auto");
  }

  @Test
  public void shouldStoreReasoningParametersInGenericMap() {
    AgentRequest request = connector.createRequest()
        .reasoningEffort("high")
        .reasoningSummary("auto");

    assertThat(request.getRequestParameters())
        .containsEntry(AgentConnector.PARAM_NAME_REASONING_EFFORT, "high")
        .containsEntry(AgentConnector.PARAM_NAME_REASONING_SUMMARY, "auto");
  }

  @Test
  public void shouldReturnNullWhenReasoningParametersNotSet() {
    AgentRequest request = connector.createRequest();
    assertThat(request.getReasoningEffort()).isNull();
    assertThat(request.getReasoningSummary()).isNull();
  }

  // ── Model selection — defaults ───────────────────────────────────────────

  @Test
  public void shouldUseStandardChatModelByDefault() {
    AgentRequest request = connector.createRequest().model("gpt-5.4-nano");

    ChatModel model = connector.createChatModel(request, "test-key", null, Map.of());

    assertThat(model).isInstanceOf(OpenAiChatModel.class);
  }

  // ── Model selection — reasoningEffort only (Chat Completions API) ────────

  @Test
  public void shouldKeepStandardChatModelWhenOnlyReasoningEffortIsSet() {
    AgentRequest request = connector.createRequest()
        .model("gpt-5.4-nano")
        .reasoningEffort("low");

    ChatModel model = connector.createChatModel(request, "test-key", null, Map.of());

    assertThat(model).isInstanceOf(OpenAiChatModel.class);
    OpenAiChatRequestParameters params = (OpenAiChatRequestParameters) model.defaultRequestParameters();
    assertThat(params.reasoningEffort()).isEqualTo("low");
  }

  // ── Model selection — reasoningSummary triggers Responses API ────────────

  @Test
  public void shouldSwitchToResponsesApiWhenReasoningSummaryIsSet() {
    AgentRequest request = connector.createRequest()
        .model("gpt-5.4-nano")
        .reasoningSummary("auto");

    ChatModel model = connector.createChatModel(request, "test-key", null, Map.of());

    assertThat(model).isInstanceOf(OpenAiResponsesChatModel.class);
    OpenAiResponsesChatRequestParameters params =
        (OpenAiResponsesChatRequestParameters) model.defaultRequestParameters();
    assertThat(params.reasoningSummary()).isEqualTo("auto");
  }

  @Test
  public void shouldForwardBothReasoningParamsToResponsesApi() {
    AgentRequest request = connector.createRequest()
        .model("gpt-5.4-nano")
        .reasoningEffort("high")
        .reasoningSummary("auto");

    ChatModel model = connector.createChatModel(request, "test-key", null, Map.of());

    assertThat(model).isInstanceOf(OpenAiResponsesChatModel.class);
    OpenAiResponsesChatRequestParameters params =
        (OpenAiResponsesChatRequestParameters) model.defaultRequestParameters();
    assertThat(params.reasoningEffort()).isEqualTo("high");
    assertThat(params.reasoningSummary()).isEqualTo("auto");
  }

  // ── Model selection — empty/blank values fall through to standard model ──

  @Test
  public void shouldNotSwitchToResponsesApiWhenReasoningSummaryIsEmpty() {
    AgentRequest request = connector.createRequest()
        .model("gpt-5.4-nano")
        .reasoningSummary("");

    ChatModel model = connector.createChatModel(request, "test-key", null, Map.of());

    assertThat(model).isInstanceOf(OpenAiChatModel.class);
  }

  // ── Custom headers + reasoningSummary: headers ignored, no exception ─────

  @Test
  public void shouldNotFailWhenReasoningSummarySetWithCustomHeaders() {
    AgentRequest request = connector.createRequest()
        .model("gpt-5.4-nano")
        .reasoningSummary("auto");

    ChatModel model = connector.createChatModel(request, "test-key", null,
        Map.of("Authorization", "Bearer test"));

    assertThat(model).isInstanceOf(OpenAiResponsesChatModel.class);
  }

}
