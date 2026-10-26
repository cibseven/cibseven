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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.cibseven.bpm.engine.OptimisticLockingException;
import org.junit.jupiter.api.Test;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ReturnBehavior;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.ToolErrorContext;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;

/**
 * What happens to an exception a tool of an agentic scope throws.
 *
 * <p>LangChain4j turns every one of them into text for the model. For a refusal that is the
 * point. For an engine failure it told the model to carry on in a transaction already marked
 * rollback-only: the commit failed at the end of the turn, the job retried three times, every
 * model call was paid three times, and the incident carried the rollback rather than the
 * cause. Measured on 59397e8cd9 — CIB7-2141.
 */
public class AdHocToolFailureTest {

  private static final ToolErrorContext NO_CONTEXT = null;

  /**
   * Mocked rather than built: {@code ToolErrorContext.builder()} insists on an
   * {@code InvocationContext}, which only LangChain4j's own tool loop can produce. The
   * handler reads one thing from it.
   */
  private static ToolErrorContext contextFor(String toolName) {
    ToolErrorContext context = mock(ToolErrorContext.class);
    when(context.toolExecutionRequest()).thenReturn(ToolExecutionRequest.builder()
        .id("call-1").name(toolName).arguments("{}").build());
    return context;
  }

  // ── what the handler does with each kind ─────────────────────────────────

  @Test
  public void aRefusalBecomesTextForTheModel() {
    AdHocToolRefusedException refusal =
        new AdHocToolRefusedException("'limit' must be a integer");

    ToolErrorHandlerResult result =
        AgentConnectorImpl.toolFailure(refusal, contextFor("suche"));

    assertThat(result.text()).isEqualTo("'limit' must be a integer");
  }

  @Test
  public void anEngineFailureIsRethrownUnchanged() {
    OptimisticLockingException engineFailure =
        new OptimisticLockingException("ENGINE-03005 concurrent modification");

    assertThatThrownBy(() -> AgentConnectorImpl.toolFailure(engineFailure, contextFor("suche")))
        .as("the incident must carry the original exception, not a wrapper of ours")
        .isSameAs(engineFailure);
  }

  /**
   * The three configuration errors an ad hoc tool can raise — no BPMN execution, the agent
   * task outside the scope, no engine — are {@link AgentConnectorException} and stay that
   * way. Their messages ask someone to move an activity or edit {@code toolClasses}, and a
   * model can do neither.
   */
  @Test
  public void aConfigurationErrorEndsTheTurnRatherThanReachingTheModel() {
    AgentConnectorException misconfigured = new AgentConnectorException(
        "The ad hoc sub process tool is configured, but activity 'x' is not inside an ad hoc "
        + "sub process.");

    assertThatThrownBy(() -> AgentConnectorImpl.toolFailure(misconfigured, contextFor("suche")))
        .isSameAs(misconfigured);
  }

  /**
   * A checked exception cannot leave {@code handle()}, so it is the one kind that must be
   * wrapped. The cause survives, and the wrapper names the tool.
   */
  @Test
  public void aCheckedExceptionIsWrappedWithItsCauseAndTheToolName() {
    IOException checked = new IOException("connection reset");

    assertThatThrownBy(() -> AgentConnectorImpl.toolFailure(checked, contextFor("suche")))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("suche")
        .hasMessageContaining("connection reset")
        .hasCause(checked);
  }

  @Test
  public void aMissingContextDoesNotHideTheFailure() {
    RuntimeException failure = new IllegalStateException("boom");

    assertThatThrownBy(() -> AgentConnectorImpl.toolFailure(failure, NO_CONTEXT))
        .isSameAs(failure);
  }

  // ── that LangChain4j honours it for our kind of tool ─────────────────────

  /** The model, scripted: call the one tool, then answer. */
  static final class TwoStepModel implements ChatModel {
    int calls;

    @Override
    public ChatResponse doChat(ChatRequest request) {
      calls++;
      AiMessage message = (calls == 1)
          ? AiMessage.from(ToolExecutionRequest.builder()
              .id("call-1").name("werkzeug").arguments("{}").build())
          : AiMessage.from("done");
      return ChatResponse.builder().aiMessage(message).build();
    }
  }

  interface Agent {
    String chat(@UserMessage String message);
  }

  interface StepAgent {
    Result<String> chat(@UserMessage String message);
  }

  private static ToolProvider throwing(RuntimeException error) {
    return request -> {
      Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
      tools.put(ToolSpecification.builder().name("werkzeug").description("throws").build(),
          (ToolExecutionRequest toolRequest, Object memoryId) -> {
            throw error;
          });
      return new ToolProviderResult(tools);
    };
  }

  private static Agent agentWith(RuntimeException error, TwoStepModel model) {
    return AiServices.builder(Agent.class)
        .chatModel(model)
        .toolProvider(throwing(error))
        .toolExecutionErrorHandler(AgentConnectorImpl::toolFailure)
        .build();
  }

  private static StepAgent immediateAgentWith(RuntimeException error, TwoStepModel model) {
    ToolProvider provider = request -> ToolProviderResult.builder()
        .add(ToolSpecification.builder().name("werkzeug").description("throws").build(),
            new AgentConnectorImpl.FailureAsResult((toolRequest, memoryId) -> {
              throw error;
            }), ReturnBehavior.IMMEDIATE)
        .build();
    return AiServices.builder(StepAgent.class)
        .chatModel(model)
        .toolProvider(provider)
        .toolExecutionErrorHandler(AgentConnectorImpl::toolFailure)
        .build();
  }

  @Test
  public void anImmediateEngineFailureKeepsItsTypeMessageAndCauseWithoutAnotherModelCall() {
    IOException cause = new IOException("storage unavailable");
    OptimisticLockingException failure =
        new OptimisticLockingException("ENGINE-03005 concurrent modification", cause);
    TwoStepModel model = new TwoStepModel();

    assertThatThrownBy(() -> immediateAgentWith(failure, model).chat("go"))
        .isSameAs(failure)
        .hasMessage("ENGINE-03005 concurrent modification")
        .hasCause(cause);
    assertThat(model.calls).isOne();
  }

  @Test
  public void anImmediateRefusalEndsTheStepWithoutAnotherModelCall() {
    TwoStepModel model = new TwoStepModel();

    Result<String> result = immediateAgentWith(
        new AdHocToolRefusedException("wait, something is still running"), model).chat("go");

    assertThat(result.content()).isNull();
    assertThat(result.toolExecutions()).hasSize(1);
    assertThat(result.toolExecutions().get(0).result())
        .isEqualTo("wait, something is still running");
    assertThat(model.calls).isOne();
  }

  @Test
  public void aCheckedExceptionWithoutAMessageUsesItsClassName() {
    IOException failure = new IOException();

    assertThatThrownBy(() -> AgentConnectorImpl.toolFailure(failure, contextFor("suche")))
        .hasMessage("Tool 'suche' failed: java.io.IOException")
        .hasCause(failure);
  }

  /**
   * The acceptance criterion of CIB7-2141: the turn aborts and the model is not asked once
   * more. One model call, not two — the second is what used to be paid for while the
   * transaction was already lost.
   *
   * <p>It also settles that the handler reaches tools from a {@link ToolProvider} at all.
   * The ad hoc tools are built there rather than from {@code @Tool} methods, and nothing in
   * LangChain4j's documentation says the handler covers both.
   */
  @Test
  public void anEngineFailureEndsTheRunWithoutAskingTheModelAgain() {
    OptimisticLockingException engineFailure =
        new OptimisticLockingException("ENGINE-03005 concurrent modification");
    TwoStepModel model = new TwoStepModel();

    // Not merely a cause: LangChain4j adds no wrapper of its own on this path, so what
    // the job sees -- and what the incident will carry -- is the engine's own exception.
    assertThatThrownBy(() -> agentWith(engineFailure, model).chat("go"))
        .as("the failure leaves the run rather than becoming a tool result")
        .isSameAs(engineFailure);

    assertThat(model.calls).as("the model must not be asked again").isOne();
  }

  /**
   * The other half, and the guard against cutting too much: a refusal still reaches the
   * model, and the turn carries on to an answer.
   */
  @Test
  public void aRefusalLetsTheRunCarryOn() {
    TwoStepModel model = new TwoStepModel();

    String answer = agentWith(
        new AdHocToolRefusedException("wait, something is still running"), model).chat("go");

    assertThat(answer).isEqualTo("done");
    assertThat(model.calls).as("the model read the refusal and answered").isEqualTo(2);
  }
}
