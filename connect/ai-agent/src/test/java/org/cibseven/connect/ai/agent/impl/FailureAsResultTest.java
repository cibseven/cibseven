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

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.exception.ToolArgumentsException;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;

/**
 * A tool of an agentic step returns at once. A refusal is its result, so the model reads it in
 * the next step; a genuine failure is rethrown instead, so it reaches {@code toolFailure} and
 * ends the step the way a non-immediate tool's failure would (CIB7-2141).
 */
public class FailureAsResultTest {

  private static final ToolExecutionRequest CALL = ToolExecutionRequest.builder()
      .id("call_1").name("mcp__lookup").arguments("{not json").build();

  /** As an MCP client reports arguments it cannot read. */
  private static final ToolExecutor UNREADABLE_ARGUMENTS = (request, memoryId) -> {
    throw new ToolArgumentsException("Arguments of 'lookup' are not a JSON object");
  };

  /**
   * {@code AgentConnectorImpl.MCP_APPLICATION_ERROR} is thread-bound state that
   * {@code MCP_ERROR_CLASSIFIER} would normally set from inside a real {@code
   * DefaultMcpClient} call, before {@link AgentConnectorImpl.FailureAsResult} ever sees the
   * exception. There is no real MCP client here, so the tests below that need it set it
   * themselves — exactly what that listener would have recorded — and every test starts and
   * ends with it cleared, so one test's classification cannot leak into the next on the same
   * thread.
   */
  @BeforeEach
  @AfterEach
  public void clearMcpApplicationErrorFlag() {
    AgentConnectorImpl.MCP_APPLICATION_ERROR.remove();
  }

  @Test
  public void argumentsThatCannotBeReadAreTheToolsResultAndNoError() {
    ToolExecutionResult result = new AgentConnectorImpl.FailureAsResult(UNREADABLE_ARGUMENTS)
        .executeWithContext(CALL, InvocationContext.builder().build());

    assertThat(result.isError())
        .as("an error would make LangChain4j ask the model again in the same job").isFalse();
    assertThat(result.resultText()).contains("are not a JSON object");
  }

  @Test
  public void aToolThatReportsAnErrorItselfIsAResultToo() {
    ToolExecutor reportsAnError = new ToolExecutor() {
      @Override
      public String execute(ToolExecutionRequest request, Object memoryId) {
        return "unused";
      }

      @Override
      public ToolExecutionResult executeWithContext(ToolExecutionRequest request,
          InvocationContext context) {
        return ToolExecutionResult.builder().isError(true).resultText("lookup failed").build();
      }
    };

    ToolExecutionResult result = new AgentConnectorImpl.FailureAsResult(reportsAnError)
        .executeWithContext(CALL, InvocationContext.builder().build());

    assertThat(result.isError()).isFalse();
    assertThat(result.resultText()).isEqualTo("lookup failed");
  }

  /** As an ad hoc tool refuses a call the model may read and act on. */
  @Test
  public void anAdHocRefusalIsTheToolsResultAndNoError() {
    ToolExecutor refuses = (request, memoryId) -> {
      throw new AdHocToolRefusedException("Cannot start 'x' while 'y' is still running.");
    };

    ToolExecutionResult result = new AgentConnectorImpl.FailureAsResult(refuses)
        .executeWithContext(CALL, InvocationContext.builder().build());

    assertThat(result.isError()).isFalse();
    assertThat(result.resultText()).contains("still running");
  }

  /**
   * As DefaultMcpClient reports an MCP server's own {@code isError} answer: the same {@code
   * ToolExecutionException} a transport failure throws, distinguished only by {@code
   * MCP_ERROR_CLASSIFIER} having recorded, a moment earlier on this thread, that {@code
   * afterExecuteTool} fired rather than {@code onExecuteToolError} — set here by hand, since
   * there is no real MCP client in this test to fire it.
   */
  @Test
  public void anMcpApplicationErrorIsTheToolsResultAndNoError() {
    AgentConnectorImpl.MCP_APPLICATION_ERROR.set(Boolean.TRUE);
    ToolExecutor reportsIsError = (request, memoryId) -> {
      throw new ToolExecutionException("No task with id 42");
    };

    ToolExecutionResult result = new AgentConnectorImpl.FailureAsResult(reportsIsError)
        .executeWithContext(CALL, InvocationContext.builder().build());

    assertThat(result.isError()).isFalse();
    assertThat(result.resultText()).contains("No task with id 42");
  }

  /**
   * A genuine failure — an ad hoc tool reaching into a broken engine, an MCP protocol error, a
   * malformed response — is rethrown, not turned into a result: it must still reach {@code
   * toolFailure}, the handler installed on the whole AiService, so the step ends instead of the
   * model working on with nothing it can act on. Wrapped once on the way out (see {@code
   * AgentConnectorImpl.carryOriginal}), so what escapes this executor is not the original
   * exception itself — the next two tests are what proves that wrapping harmless.
   */
  @Test
  public void aGenuineFailureIsRethrownNotTurnedIntoAResult() {
    ToolExecutor engineFails = (request, memoryId) -> {
      throw new AgentConnectorException("Engine unreachable");
    };
    ToolExecutor mcpProtocolFails = (request, memoryId) -> {
      throw new ToolExecutionException("Invalid params", -32602);
    };

    assertThat(catchThrowable(() -> new AgentConnectorImpl.FailureAsResult(engineFails)
        .executeWithContext(CALL, InvocationContext.builder().build())))
        .as("rethrown, not a result").hasCauseInstanceOf(AgentConnectorException.class);
    assertThat(catchThrowable(() -> new AgentConnectorImpl.FailureAsResult(mcpProtocolFails)
        .executeWithContext(CALL, InvocationContext.builder().build())))
        .as("an error code means the MCP protocol itself failed, not the tool's own answer")
        .hasCauseInstanceOf(ToolExecutionException.class);
  }

  /**
   * DefaultMcpClient's {@code catch (ExecutionException e)} raises the call's own transport
   * failure as {@code ToolExecutionException(e.getCause())} — the same exception type an MCP
   * server's {@code isError} answer has, {@code errorCode() == null} either way (an HTTP
   * transport failing with an unexpected status code throws a cause-less {@code
   * RuntimeException} too, so even that shape is not a reliable tell — see the second review
   * round of CIB7-2141). What tells them apart is {@code MCP_ERROR_CLASSIFIER}: a transport
   * failure fires {@code onExecuteToolError}, never {@code afterExecuteTool}, so the flag
   * stays cleared, which is the state {@link #clearMcpApplicationErrorFlag} already leaves it
   * in — nothing to set here. Without that distinction, a dropped connection would read to
   * the model as if the server had answered with that message, and the model would call again
   * instead of the step ending.
   */
  @Test
  public void anMcpTransportFailureIsRethrownNotTurnedIntoAResult() {
    ToolExecutor transportFails = (request, memoryId) -> {
      throw new ToolExecutionException(new IOException("connection reset by peer"));
    };

    Throwable thrown = catchThrowable(() -> new AgentConnectorImpl.FailureAsResult(transportFails)
        .executeWithContext(CALL, InvocationContext.builder().build()));

    assertThat(thrown).as("not read by the model as the server's own answer")
        .hasCauseInstanceOf(ToolExecutionException.class);
    assertThat(thrown.getCause()).hasCauseInstanceOf(IOException.class);
  }

  /**
   * The exact proof of the wrap: {@code ToolService.executeWithErrorHandling} computes {@code
   * cause != null ? cause : e} on whatever this executor throws before handing it to {@code
   * toolFailure}. Applying that same rule here must land back on the original exception —
   * whole, including its own nested cause — not on that nested cause directly. A failure with
   * no cause of its own is the trivial case; this is the one finding 2 of the review was about.
   */
  @Test
  public void theOriginalFailureSurvivesLangChain4jsOwnUnwrap() {
    RuntimeException innerCause = new IllegalStateException("inner diagnostic");
    RuntimeException original = new AgentConnectorException("outer diagnostic", innerCause);
    ToolExecutor fails = (request, memoryId) -> {
      throw original;
    };

    Throwable thrown = catchThrowable(() -> new AgentConnectorImpl.FailureAsResult(fails)
        .executeWithContext(CALL, InvocationContext.builder().build()));

    Throwable afterLangChain4jsUnwrap = (thrown.getCause() != null) ? thrown.getCause() : thrown;
    assertThat(afterLangChain4jsUnwrap).as("the exact object the tool threw, type included")
        .isSameAs(original);
    assertThat(afterLangChain4jsUnwrap.getCause()).as("its own cause chain, untouched")
        .isSameAs(innerCause);
  }

  /**
   * {@code MCP_ERROR_CLASSIFIER} itself, called the way {@code DefaultMcpClient.executeTool}
   * calls it: {@code afterExecuteTool} for a successful call too, not only an {@code isError}
   * one — its own result still tells the two apart, which is why the flag is set from {@code
   * result.isError()} rather than unconditionally on that callback alone.
   */
  @Test
  public void theClassifierReadsWhichCallbackFiredTheWayDefaultMcpClientFiresIt() {
    McpCallContext context = new McpCallContext(null, null);

    AgentConnectorImpl.MCP_ERROR_CLASSIFIER.afterExecuteTool(context,
        ToolExecutionResult.builder().isError(true).resultText("no task with id 42").build(),
        null);
    assertThat(AgentConnectorImpl.MCP_APPLICATION_ERROR.get()).as("the server's own answer")
        .isTrue();

    AgentConnectorImpl.MCP_ERROR_CLASSIFIER.afterExecuteTool(context,
        ToolExecutionResult.builder().isError(false).resultText("42 widgets found").build(), null);
    assertThat(AgentConnectorImpl.MCP_APPLICATION_ERROR.get()).as("a plain success, not an error")
        .isFalse();

    AgentConnectorImpl.MCP_ERROR_CLASSIFIER.onExecuteToolError(context,
        new ToolExecutionException(new IOException("connection reset by peer")));
    assertThat(AgentConnectorImpl.MCP_APPLICATION_ERROR.get()).as("a transport or protocol failure")
        .isFalse();
  }
}
