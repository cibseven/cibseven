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
package org.cibseven.connect.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.cibseven.connect.ai.agent.impl.AgentConnectorImpl;
import org.cibseven.connect.ai.agent.impl.AgentResponseImpl;
import org.junit.Before;
import org.junit.Test;

/**
 * Connector-level tests for the invoice processing scenario defined in
 * {@code invoice-processing.bpmn}.
 *
 * <p>When the CIBseven engine executes the "Extract Invoice Data" service task in that
 * process, it:
 * <ol>
 *   <li>Resolves each {@code camunda:inputParameter} expression and calls
 *       {@link AgentRequest#setRequestParameter(String, Object)} for each one.</li>
 *   <li>Calls {@link AgentRequest#execute()} which delegates to this connector.</li>
 *   <li>Reads the values of each {@code camunda:outputParameter} from the returned
 *       {@link AgentResponse} and stores them as process variables.</li>
 * </ol>
 *
 * <p>These tests replicate that three-step lifecycle directly — without an engine
 * dependency — using a {@link StubbedAgentConnector} inner class that bypasses the
 * real Google ADK call and returns predictable output.
 *
 * @see <a href="src/test/resources/org/cibseven/connect/ai/agent/invoice-processing.bpmn">invoice-processing.bpmn</a>
 */
public class InvoiceProcessingConnectorTest {

  // ── Test data ────────────────────────────────────────────────────────────

  /**
   * Raw invoice text used as the {@code ${invoiceText}} process variable.
   * The BPMN engine passes this value to the connector's {@code message} parameter.
   */
  static final String INVOICE_TEXT =
      "INVOICE\n\n"
      + "From: Acme Software GmbH\n"
      + "      Hauptstrasse 42, 80331 Munich, Germany\n"
      + "      VAT ID: DE123456789\n\n"
      + "To:   CIB seven GmbH\n"
      + "      Landsberger Str. 300\n"
      + "      80687 Munich, Germany\n\n"
      + "Invoice Number: INV-2026-03-0047\n"
      + "Invoice Date:   2026-03-10\n"
      + "Due Date:       2026-04-09\n"
      + "Payment Terms:  Net 30\n\n"
      + "Line Items:\n"
      + "  1. Professional Services - BPMN Engine Integration Consulting\n"
      + "     5 days x EUR 1,200.00/day                          EUR 6,000.00\n"
      + "  2. Google ADK Agent Connector - License (annual)       EUR 1,500.00\n"
      + "  3. Setup & Configuration Support (8 hrs x EUR 150.00)  EUR 1,200.00\n\n"
      + "                                            Subtotal:   EUR 8,700.00\n"
      + "                                            VAT (19%):  EUR 1,653.00\n"
      + "                                            TOTAL DUE:  EUR 10,353.00\n\n"
      + "Bank: Deutsche Bank AG\n"
      + "IBAN: DE89 3704 0044 0532 0130 00\n"
      + "BIC:  COBADEFFXXX\n"
      + "Reference: INV-2026-03-0047";

  /**
   * Expected JSON that a real Gemini call would return for {@link #INVOICE_TEXT}.
   * The connector stores this in the {@code output} response parameter, which the
   * engine then maps to the {@code agentResult} process variable.
   */
  static final String EXPECTED_JSON_OUTPUT =
      "{\n"
      + "  \"invoiceNumber\": \"INV-2026-03-0047\",\n"
      + "  \"vendorName\": \"Acme Software GmbH\",\n"
      + "  \"invoiceDate\": \"2026-03-10\",\n"
      + "  \"dueDate\": \"2026-04-09\",\n"
      + "  \"totalAmount\": 10353.00,\n"
      + "  \"currency\": \"EUR\",\n"
      + "  \"lineItems\": [\n"
      + "    { \"description\": \"Professional Services - BPMN Engine Integration Consulting\","
      + " \"amount\": 6000.00 },\n"
      + "    { \"description\": \"Google ADK Agent Connector - License (annual)\","
      + " \"amount\": 1500.00 },\n"
      + "    { \"description\": \"Setup & Configuration Support\", \"amount\": 1200.00 }\n"
      + "  ]\n"
      + "}";

  // ── Stub connector ───────────────────────────────────────────────────────

  /**
   * Test double that bypasses the Google ADK call entirely.
   *
   * <p>Captures the full request parameter map so individual tests can assert
   * that parameters were wired up correctly, and returns configurable output
   * that simulates a successful Gemini invocation.
   */
  static class StubbedAgentConnector extends AgentConnectorImpl {

    Map<String, Object> lastRequestParameters;
    String stubbedOutput = EXPECTED_JSON_OUTPUT;

    @Override
    public AgentResponse execute(AgentRequest request) {
      lastRequestParameters = new HashMap<>(request.getRequestParameters());
      return new AgentResponseImpl(stubbedOutput);
    }
  }

  private StubbedAgentConnector connector;

  @Before
  public void setUp() {
    connector = new StubbedAgentConnector();
  }

  // ── BPMN input parameter mapping ─────────────────────────────────────────

  /**
   * Verifies that the static {@code camunda:inputParameter} values defined in
   * {@code invoice-processing.bpmn} reach the connector with the correct names.
   */
  @Test
  public void shouldAcceptAllBpmnInputParameters() {
    AgentRequest request = connector.createRequest()
        .agentName("invoice-agent")
        .agentDescription("Invoice processing assistant that extracts structured data from invoice text")
        .instruction("You are an invoice processing assistant.")
        .model("gemini-2.5-flash")
        .message(INVOICE_TEXT)
        .apiKey("AIzaSy-test-key");

    AgentResponse response = request.execute();

    assertThat(response).isNotNull();
    assertThat(connector.lastRequestParameters)
        .containsEntry(AgentConnector.PARAM_NAME_AGENT_NAME, "invoice-agent")
        .containsEntry(AgentConnector.PARAM_NAME_MESSAGE, INVOICE_TEXT)
        .containsEntry(AgentConnector.PARAM_NAME_MODEL, "gemini-2.5-flash")
        .containsEntry(AgentConnector.PARAM_NAME_API_KEY, "AIzaSy-test-key");
  }

  /**
   * Simulates the engine resolving {@code ${invoiceText}} and setting it as the
   * {@code message} input parameter — the core data path of the BPMN process.
   */
  @Test
  public void shouldReceiveInvoiceTextFromProcessVariable() {
    AgentRequest request = connector.createRequest()
        .agentName("invoice-agent")
        .instruction("Extract invoice fields as JSON.")
        .message(INVOICE_TEXT); // engine resolves ${invoiceText} → this value

    request.execute();

    assertThat((String) connector.lastRequestParameters.get(AgentConnector.PARAM_NAME_MESSAGE))
        .contains("INV-2026-03-0047")
        .contains("Acme Software GmbH")
        .contains("EUR 10,353.00");
  }

  // ── BPMN output parameter mapping ────────────────────────────────────────

  /**
   * Verifies that the connector response contains the {@code output} parameter referenced
   * in the {@code camunda:outputParameter} section of {@code invoice-processing.bpmn}.
   * The chat log is no longer exposed via the response — it is written directly to the
   * process variable named {@code AGENT_CONNECTOR_LOG_PREFIX + <activityId>}.
   */
  @Test
  public void shouldExposeOutputParamKeysMatchingBpmnOutputMapping() {
    AgentRequest request = connector.createRequest()
        .agentName("invoice-agent")
        .instruction("Extract invoice fields as JSON.")
        .message(INVOICE_TEXT);

    AgentResponse response = request.execute();

    // The BPMN uses ${output} → agentResult
    String agentResult = (String) response.getResponseParameter(AgentConnector.PARAM_NAME_OUTPUT);
    assertThat(agentResult).isEqualTo(EXPECTED_JSON_OUTPUT);
  }

  // ── Response content ─────────────────────────────────────────────────────

  @Test
  public void shouldReturnExtractedInvoiceDataAsOutput() {
    AgentRequest request = connector.createRequest()
        .agentName("invoice-agent")
        .instruction("Extract invoice fields as JSON.")
        .message(INVOICE_TEXT);

    AgentResponse response = request.execute();

    assertThat(response.getOutput()).contains("INV-2026-03-0047");
    assertThat(response.getOutput()).contains("Acme Software GmbH");
    assertThat(response.getOutput()).contains("10353.00");
  }

  // ── Validation — required BPMN parameter guards ──────────────────────────

  @Test
  public void shouldFailWhenRequiredBpmnParameterMissing_agentName() {
    AgentRequest request = connector.createRequest()
        .instruction("Extract invoice fields.")
        .message(INVOICE_TEXT); // agentName omitted

    assertThatThrownBy(() -> request.execute())
        .isInstanceOf(RuntimeException.class);
  }

  /**
   * {@code instruction} is intentionally optional: when omitted, the connector
   * falls back to the bundled default system prompt
   * ({@code /org/cibseven/connect/ai/agent/default-instruction.txt}).
   * Validation must therefore accept a request that supplies only the two
   * required BPMN parameters, {@code agentName} and {@code message}.
   */
  @Test
  public void shouldTreatInstructionAsOptionalAndPassValidation() {
    AgentRequest request = connector.createRequest()
        .agentName("invoice-agent")
        .message(INVOICE_TEXT); // instruction omitted on purpose

    AgentResponse response = request.execute();
    assertThat(response).isNotNull();
  }

  @Test
  public void shouldFailWhenRequiredBpmnParameterMissing_message() {
    AgentRequest request = connector.createRequest()
        .agentName("invoice-agent")
        .instruction("Extract invoice fields."); // message omitted

    assertThatThrownBy(() -> request.execute())
        .isInstanceOf(RuntimeException.class);
  }

  // ── Default values ────────────────────────────────────────────────────────

  /**
   * When the BPMN author omits the {@code model} input parameter, the connector
   * must fall back to the documented default model so the process still works.
   */
  @Test
  public void shouldUseDefaultModelWhenNotSetInBpmn() {
    AgentRequest request = connector.createRequest()
        .agentName("invoice-agent")
        .instruction("Extract invoice fields.")
        .message(INVOICE_TEXT); // model not set

    assertThat(request.getModel()).isEqualTo(AgentConnectorConstants.DEFAULT_MODEL);
  }

  /**
   * The BPMN maps {@code ${langchain4jApiKey}} to the {@code apiKey} parameter.
   * When a value is present it must reach the connector so it can override
   * the {@code OPENAI_API_KEY} environment variable.
   */
  @Test
  public void shouldAcceptApiKeyOverrideFromBpmnVariable() {
    AgentRequest request = connector.createRequest()
        .agentName("invoice-agent")
        .instruction("Extract invoice fields.")
        .message(INVOICE_TEXT)
        .apiKey("AIzaSy-bpmn-override-key"); // engine resolves ${langchain4jApiKey}

    request.execute();

    assertThat((String) connector.lastRequestParameters.get(AgentConnector.PARAM_NAME_API_KEY))
        .isEqualTo("AIzaSy-bpmn-override-key");
  }

  /**
   * The BPMN maps {@code ${langchain4jBaseUrl}} to the {@code baseUrl} parameter.
   * When a value is present it must reach the connector so it can override
   * the {@code OPENAI_BASE_URL} environment variable — e.g. to route through OpenRouter.
   */
  @Test
  public void shouldAcceptBaseUrlOverrideFromBpmnVariable() {
    AgentRequest request = connector.createRequest()
        .agentName("invoice-agent")
        .instruction("Extract invoice fields.")
        .message(INVOICE_TEXT)
        .baseUrl("https://openrouter.ai/api/v1"); // engine resolves ${langchain4jBaseUrl}

    request.execute();

    assertThat((String) connector.lastRequestParameters.get(AgentConnector.PARAM_NAME_BASE_URL))
        .isEqualTo("https://openrouter.ai/api/v1");
  }
}
