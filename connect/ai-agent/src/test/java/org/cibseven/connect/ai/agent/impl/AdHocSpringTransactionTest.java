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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.OptimisticLockingException;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.spring.SpringProcessEngineConfiguration;
import org.cibseven.bpm.engine.runtime.Incident;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The agent's turn under <em>Spring's</em> transaction management.
 *
 * <p>Every other test of this ticket builds a
 * {@code StandaloneInMemProcessEngineConfiguration}, which uses the plain command
 * interceptor. A distribution runs Spring Boot, and the difference is not cosmetic:
 * only a Spring-managed transaction has a rollback-only flag, so the same defect
 * reports itself differently. When {@code completeScope} still deleted the execution
 * it was running on, the distribution raised
 * {@code UnexpectedRollbackException: Transaction rolled back because it has been
 * marked as rollback-only} while the standalone harness raised an optimistic locking
 * failure. Both were the same bug; only one of the two messages pointed at it.
 *
 * <p>This closes the gap named as open throughout the ticket. It is Spring's
 * transaction management, not the whole Spring Boot chain of the run4 distribution —
 * the layer where that difference lives, and no further.
 *
 * <p>The model is stubbed by a local HTTP server, so the run needs no network and no
 * key, and the turn's job is executed by hand so a failure is a step rather than a
 * race.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration("classpath:adhoc-spring-context.xml")
public class AdHocSpringTransactionTest {

  /** Its own port, so it cannot collide with AdHocConnectorReproductionTest's. */
  private static final int STUB_PORT = 8098;

  public static class ConcurrentModificationTool implements JavaDelegate {
    static final OptimisticLockingException FAILURE = new OptimisticLockingException(
        "Concurrent modification in calculatePrice", new IOException("storage diagnostic"));

    @Override
    public void execute(DelegateExecution execution) {
      throw FAILURE;
    }
  }

  @Autowired
  private ProcessEngine processEngine;

  private HttpServer stub;
  private final AtomicInteger stubCalls = new AtomicInteger();
  /** Set only by the broken-tool test: the model is asked the same thing on every attempt. */
  private boolean repeatBrokenTool = false;

  @BeforeEach
  public void startStub() throws IOException {
    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", STUB_PORT), 0);
    stub.createContext("/v1/chat/completions", this::respond);
    stub.setExecutor(null);
    stub.start();
    ProcessStarterToolContext.setEngine(processEngine);
  }

  @AfterEach
  public void stopStub() {
    ProcessStarterToolContext.clear();
    if (stub != null) {
      stub.stop(0);
      stub = null;
    }
  }

  /**
   * List, start, complete, and then one more exchange — the turn LangChain4j really
   * runs, because it asks the model again after every tool result. That last step is
   * where the rollback-only failure appeared.
   */
  private void respond(HttpExchange exchange) throws IOException {
    int call = stubCalls.incrementAndGet();
    String body;
    if (repeatBrokenTool) {
      // Every attempt is the same first step replayed: the broken tool fails the same
      // way each time, not a later step of a turn that got further.
      body = toolCall("c" + call, "calculatePrice", "{}");
    } else if (call == 1) {
      body = toolCall("c1", "calculatePrice", "{}");
    } else if (call == 2) {
      body = toolCall("c2", "completeScope", "{}");
    } else {
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

  /**
   * Like {@link #model()}, but the tool's expression reads a variable that does not
   * exist — Dmitry's reproduction of CIB7-2141, in the one place where Spring's
   * rollback-only flag exists.
   */
  private static String modelWithABrokenTool() {
    return model()
        .replace("id='adHocSpring'", "id='adHocSpringBroken'")
        .replace("camunda:expression='${1200}' camunda:resultVariable='price'",
            "camunda:expression='${nichtDa.methode()}' camunda:resultVariable='price'");
  }

  /** An agentic scope, the way a distribution models one. */
  private static String model() {
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + " xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + " targetNamespace='http://cibseven.org/adhoc-spring'>"
        + "<process id='adHocSpring' isExecutable='true'>"
        + "  <startEvent id='start' />"
        + "  <sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + "  <adHocSubProcess id='adHoc'>"
        + "    <extensionElements><camunda:connector>"
        + "      <camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "      <camunda:inputOutput>"
        + "        <camunda:inputParameter name='agentName'>Spring</camunda:inputParameter>"
        + "        <camunda:inputParameter name='message'>Do something.</camunda:inputParameter>"
        + "        <camunda:inputParameter name='model'>stub</camunda:inputParameter>"
        + "        <camunda:inputParameter name='apiKey'>dummy</camunda:inputParameter>"
        + "        <camunda:inputParameter name='baseUrl'>http://127.0.0.1:" + STUB_PORT
        + "/v1</camunda:inputParameter>"
        + "      </camunda:inputOutput>"
        + "    </camunda:connector></extensionElements>"
        + "    <serviceTask id='calculatePrice' name='Calculate price'"
        + "        camunda:expression='${1200}' camunda:resultVariable='price' />"
        + "  </adHocSubProcess>"
        + "  <sequenceFlow id='f2' sourceRef='adHoc' targetRef='end' />"
        + "  <endEvent id='end' />"
        + "</process></definitions>";
  }

  /**
   * The whole turn under a Spring transaction: the scope ends, the process runs on,
   * and nothing is marked rollback-only.
   *
   * <p>A failure prints the incident message, because the engine's error propagation
   * replaces the original exception while walking a tree whose parent has gone — which
   * is how this defect hid the first time.
   */
  /**
   * The premise of the other test: this engine really is under Spring.
   *
   * <p>Without it the class could pass while proving nothing — a context file that
   * silently lost its transaction manager would leave an engine behaving like the
   * standalone ones, and every assertion below would still hold. That is exactly how
   * two defects of this ticket survived: a test that asserts the outcome but not the
   * setup it claims to exercise.
   */
  @Test
  public void theEngineIsActuallyUnderSpringTransactionManagement() {
    ProcessEngineConfigurationImpl configuration =
        (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();

    assertThat(configuration).isInstanceOf(SpringProcessEngineConfiguration.class);
    assertThat(((SpringProcessEngineConfiguration) configuration).getTransactionManager())
        .as("no transaction manager means no rollback-only flag, and the test is vacuous")
        .isNotNull();
    assertThat(configuration.getTransactionContextFactory().getClass().getName())
        .as("the engine must join Spring's transaction, not open its own")
        .contains("Spring");
  }

  @Test
  public void theTurnCompletesUnderASpringTransaction() {
    processEngine.getRepositoryService().createDeployment()
        .addString("adHocSpring.bpmn20.xml", model())
        .deploy();

    ProcessInstance instance =
        processEngine.getRuntimeService().startProcessInstanceByKey("adHocSpring");

    List<Job> jobs = processEngine.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(jobs).as("the first turn waits as a job").hasSize(1);

    RuntimeException failure = null;
    try {
      TurnSteps.runToTheEnd(processEngine, instance.getId());
    } catch (RuntimeException e) {
      failure = e;
    }

    List<Incident> incidents = processEngine.getRuntimeService().createIncidentQuery()
        .processInstanceId(instance.getId()).list();
    String reported = incidents.isEmpty() ? null : incidents.get(0).getIncidentMessage();

    assertThat(failure)
        .as("the turn failed under Spring; incident says: " + reported
            + "; the model was asked " + stubCalls.get() + " time(s)")
        .isNull();
    assertThat(stubCalls.get())
        .as("the model must have been asked again after completeScope")
        .isGreaterThanOrEqualTo(3);
    assertThat(processEngine.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(instance.getId()).count())
        .as("the process should have continued past the scope").isZero();
    assertThat(processEngine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(instance.getId()).variableName("price").singleResult().getValue())
        .as("the activity's value survived the transaction").isEqualTo(1200L);
  }

  /**
   * An engine failure inside a tool ends the turn, and the incident says why.
   *
   * <p>This is the half of CIB7-2141 that only shows here. {@code AdHocToolFailureTest}
   * proves the exception leaves LangChain4j's loop unchanged; between that and an
   * operator reading an incident lie the job handler, Spring's transaction and the retry
   * mechanism, and it was exactly there that the cause used to disappear.
   *
   * <p>What it looked like before, measured on 59397e8cd9: the model received
   * "Cannot resolve identifier 'nichtDa'" as a tool result and carried on, the commit
   * failed at the end of the turn, the job ran three times with two model calls each,
   * and the incident said "rolled back because it has been marked as rollback-only"
   * while the cause appeared nowhere but in the conversation.
   *
   * <p>Asserted here: one model call per attempt rather than two, the original message
   * on the job, and the same message on the incident once the retries are spent.
   */
  @Test
  public void anOptimisticLockingFailureTraversesTheConnectorsActualToolProvider() {
    repeatBrokenTool = true;
    String bpmn = model()
        .replace("id='adHocSpring'", "id='adHocSpringConcurrent'")
        .replace("camunda:expression='${1200}' camunda:resultVariable='price'",
            "camunda:class='" + ConcurrentModificationTool.class.getName() + "'");
    processEngine.getRepositoryService().createDeployment()
        .addString("adHocSpringConcurrent.bpmn20.xml", bpmn).deploy();
    ProcessInstance instance =
        processEngine.getRuntimeService().startProcessInstanceByKey("adHocSpringConcurrent");
    String jobId = processEngine.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).singleResult().getId();

    RuntimeException failure = null;
    try {
      processEngine.getManagementService().executeJob(jobId);
    } catch (RuntimeException e) {
      failure = e;
    }

    assertThat(failure).isSameAs(ConcurrentModificationTool.FAILURE);
    assertThat(failure.getCause()).isSameAs(ConcurrentModificationTool.FAILURE.getCause());
    assertThat(stubCalls.get()).as("no model call after the failing tool").isOne();
    Job job = processEngine.getManagementService().createJobQuery().jobId(jobId).singleResult();
    assertThat(job.getExceptionMessage()).isEqualTo(ConcurrentModificationTool.FAILURE.getMessage());
    assertThat(job.getRetries()).as("optimistic locking must retain the engine's retry policy")
        .isEqualTo(3);
  }

  @Test
  public void anEngineFailureInAToolReachesTheIncidentWithItsCause() {
    repeatBrokenTool = true;
    processEngine.getRepositoryService().createDeployment()
        .addString("adHocSpringBroken.bpmn20.xml", modelWithABrokenTool())
        .deploy();

    ProcessInstance instance =
        processEngine.getRuntimeService().startProcessInstanceByKey("adHocSpringBroken");
    String jobId = processEngine.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).singleResult().getId();

    RuntimeException firstAttempt = null;
    try {
      processEngine.getManagementService().executeJob(jobId);
    } catch (RuntimeException e) {
      firstAttempt = e;
    }

    assertThat(firstAttempt).as("the engine wrapper must survive LangChain4j's unwrap")
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Unknown property used in expression: ${nichtDa.methode()}")
        .hasCauseInstanceOf(jakarta.el.PropertyNotFoundException.class);
    assertThat(stubCalls.get())
        .as("the model is asked once and not again after the failure")
        .isOne();

    Job failed = processEngine.getManagementService().createJobQuery()
        .jobId(jobId).singleResult();
    assertThat(failed.getRetries()).as("the attempt was counted").isEqualTo(2);
    assertThat(failed.getExceptionMessage())
        .as("the job carries the cause, not a rollback message")
        .contains("Unknown property used in expression: ${nichtDa.methode()}", "nichtDa");

    // A real retry, replaying the same step rather than a later one: the stub keeps
    // returning the broken tool call, so the second attempt fails the same way and pays
    // exactly one more model call, not two.
    RuntimeException secondAttempt = null;
    try {
      processEngine.getManagementService().executeJob(jobId);
    } catch (RuntimeException e) {
      secondAttempt = e;
    }
    assertThat(secondAttempt).as("the retry preserves the same engine diagnostic")
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Unknown property used in expression: ${nichtDa.methode()}")
        .hasCauseInstanceOf(jakarta.el.PropertyNotFoundException.class);
    assertThat(stubCalls.get()).as("a retry pays one model call, not two").isEqualTo(2);
    assertThat(processEngine.getManagementService().createJobQuery().jobId(jobId).singleResult()
        .getRetries()).as("the second attempt was counted too").isEqualTo(1);

    // The last retry is spent rather than replayed again: it would fail the same way,
    // and the incident is created from the message the job already carries.
    processEngine.getManagementService().setJobRetries(jobId, 0);

    List<Incident> incidents = processEngine.getRuntimeService().createIncidentQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(incidents).as("the retries are spent, so an incident exists").hasSize(1);
    assertThat(incidents.get(0).getIncidentMessage())
        .as("an operator must be able to read the cause off the incident")
        .contains("Unknown property used in expression: ${nichtDa.methode()}", "nichtDa");
    assertThat(incidents.get(0).getIncidentMessage())
        .as("and must not be told only that something was rolled back")
        .doesNotContain("rollback-only");

    assertThat(stubCalls.get())
        .as("still one model call per attempt: the failure ended the turn where it happened "
            + "each time, and nothing was paid for after the transaction was already lost")
        .isEqualTo(2);
  }
}
