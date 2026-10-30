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

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import javax.imageio.ImageIO;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.runtime.VariableInstance;
import org.cibseven.bpm.engine.variable.VariableMap;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.connect.ai.agent.AgentConnectorConstants;
import org.cibseven.connect.ai.agent.AgentRequest;
import org.cibseven.connect.ai.agent.AgentResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * AC2 of CIB7-1843 against a real model: a PDF and an image, held in
 * {@code FileValue} variables, reach the model as attachments — on the Chat
 * Completions path and on the Responses API path ({@code reasoningSummary} set).
 *
 * <p>Never runs by default: it calls an external, possibly paid model. Enable with
 * {@code CIB_AI_LIVE_TEST=true}. Both files are generated per run with random
 * canaries, so the model can only name them if it really read the attachment.
 *
 * <p>Configuration, all environment variables:
 * <ul>
 *   <li>{@code LIVE_API_KEY} — passed as {@code apiKey}; otherwise the connector
 *       falls back to {@code OPENAI_API_KEY}. Any value for Ollama.</li>
 *   <li>{@code LIVE_BASE_URL} — e.g. {@code http://localhost:11434/v1} for Ollama or
 *       {@code https://openrouter.ai/api/v1}; empty means OpenAI.</li>
 *   <li>{@code LIVE_MODEL} — default {@value AgentConnectorConstants#DEFAULT_MODEL}.</li>
 *   <li>{@code LIVE_DOCUMENTS} — {@code pdf,image} (default), or one of them. Ollama
 *       rejects PDF parts on both paths, so use {@code image} there.</li>
 *   <li>{@code LIVE_APIS} — {@code chat-completions,responses} (default), or one.</li>
 * </ul>
 *
 * <p>Example: {@code CIB_AI_LIVE_TEST=true OPENAI_API_KEY=sk-... mvn -o test
 * -Dtest=DocumentLiveProviderTest}
 */
@EnabledIfEnvironmentVariable(named = "CIB_AI_LIVE_TEST", matches = "true")
public class DocumentLiveProviderTest {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String PROCESS_KEY = "documentLiveProviderTest";

  private static final ProcessEngine ENGINE = buildInMemoryEngine();

  private static ProcessEngine buildInMemoryEngine() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setJdbcUrl("jdbc:h2:mem:document-live-provider-test;DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    ProcessEngine engine = configuration.buildProcessEngine();
    engine.getRepositoryService().createDeployment()
        .addString(PROCESS_KEY + ".bpmn20.xml", bpmn())
        .deploy();
    return engine;
  }

  @AfterAll
  public static void closeEngine() {
    ENGINE.close();
  }

  /** The real connector: nothing overridden, so the real OpenAI client is built. */
  public static class AgentDelegate implements JavaDelegate {
    @Override
    public void execute(DelegateExecution execution) {
      AgentConnectorImpl connector = new AgentConnectorImpl();
      AgentRequest request = connector.createRequest()
          .agentName("document-live-proof")
          .model(env("LIVE_MODEL", AgentConnectorConstants.DEFAULT_MODEL))
          .message((String) execution.getVariable("_message"))
          .documents((String) execution.getVariable("_documents"))
          .persistChatLog(Boolean.TRUE);
      String apiKey = env("LIVE_API_KEY", null);
      if (apiKey != null) {
        request.apiKey(apiKey);
      }
      String baseUrl = env("LIVE_BASE_URL", null);
      if (baseUrl != null) {
        request.baseUrl(baseUrl);
      }
      String summary = (String) execution.getVariable("_reasoningSummary");
      if (summary != null) {
        request.reasoningSummary(summary);
      }
      AgentResponse response = connector.execute(request);
      execution.setVariable("answer", response.getOutput());
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"chat-completions", "responses"})
  public void shouldLetTheModelReadTheAttachedDocuments(String api) throws IOException {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        list(env("LIVE_APIS", "chat-completions,responses")).contains(api),
        api + " not selected in LIVE_APIS");
    List<String> kinds = list(env("LIVE_DOCUMENTS", "pdf,image"));
    String pdfWord = "MARLIN-" + (1000 + RANDOM.nextInt(9000));
    String imageNumber = String.valueOf(10000 + RANDOM.nextInt(90000));
    byte[] pdf = pdfWithText("Invoice 2026-0815. The code word of this document is "
        + pdfWord + ".");
    byte[] png = pngWithText(imageNumber);

    VariableMap variables = Variables.createVariables();
    List<String> documents = new ArrayList<>();
    List<String> asks = new ArrayList<>();
    if (kinds.contains("pdf")) {
      variables.putValueTyped("invoicePdf", Variables.fileValue("invoice.pdf").file(pdf)
          .mimeType("application/pdf").create());
      documents.add("invoicePdf");
      asks.add("PDF=<the code word written in the PDF document>");
    }
    if (kinds.contains("image")) {
      variables.putValueTyped("photo", Variables.fileValue("photo.png").file(png)
          .mimeType("image/png").create());
      documents.add("photo");
      asks.add("IMAGE=<the number shown in the image>");
    }
    variables.putValue("_documents", String.join(",", documents));
    variables.putValue("_message", "Read the attached files. Answer with exactly this and "
        + "nothing else: " + String.join("; ", asks));
    if ("responses".equals(api)) {
      variables.putValue("_reasoningSummary", "auto");
    }

    ProcessInstance instance = ENGINE.getRuntimeService()
        .startProcessInstanceByKey(PROCESS_KEY, variables);

    String answer = (String) ENGINE.getRuntimeService()
        .getVariable(instance.getId(), "answer");
    String chatLog = chatLog(instance.getId());
    System.out.println("[CIB7-1843 live proof] api=" + api
        + " model=" + env("LIVE_MODEL", AgentConnectorConstants.DEFAULT_MODEL)
        + " baseUrl=" + env("LIVE_BASE_URL", "(OpenAI)")
        + " expected pdf=" + pdfWord + " image=" + imageNumber
        + " answer=" + answer);

    if (kinds.contains("pdf")) {
      assertThat(answer).as("code word from the PDF").contains(pdfWord);
      assertThat(chatLog).contains("\"kind\":\"PDF\"")
          .doesNotContain(Base64.getEncoder().encodeToString(pdf));
    }
    if (kinds.contains("image")) {
      assertThat(answer).as("number from the image").contains(imageNumber);
      assertThat(chatLog).contains("\"kind\":\"IMAGE\"")
          .doesNotContain(Base64.getEncoder().encodeToString(png));
    }
  }

  // ── fixtures ────────────────────────────────────────────────────────────────

  /** A one-page text PDF, written by hand so no PDF library is involved. */
  static byte[] pdfWithText(String text) {
    String stream = "BT /F1 14 Tf 72 720 Td (" + text + ") Tj ET";
    List<String> objects = Arrays.asList(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            + "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        "<< /Length " + stream.length() + " >>\nstream\n" + stream + "\nendstream");
    StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
    List<Integer> offsets = new ArrayList<>();
    for (int i = 0; i < objects.size(); i++) {
      offsets.add(pdf.length());
      pdf.append(i + 1).append(" 0 obj\n").append(objects.get(i)).append("\nendobj\n");
    }
    int xref = pdf.length();
    pdf.append("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
    for (int offset : offsets) {
      pdf.append(String.format("%010d 00000 n \n", offset));
    }
    pdf.append("trailer\n<< /Size ").append(objects.size() + 1)
        .append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
    return pdf.toString().getBytes(StandardCharsets.ISO_8859_1);
  }

  static byte[] pngWithText(String text) throws IOException {
    System.setProperty("java.awt.headless", "true");
    BufferedImage image = new BufferedImage(640, 240, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, 640, 240);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setColor(Color.BLACK);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 120));
    g.drawString(text, 40, 170);
    g.dispose();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  private static String chatLog(String processInstanceId) {
    List<VariableInstance> logs = ENGINE.getRuntimeService().createVariableInstanceQuery()
        .processInstanceIdIn(processInstanceId)
        .variableNameLike(AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX + "%")
        .list();
    assertThat(logs).as("chat-log variable").hasSize(1);
    return (String) logs.get(0).getValue();
  }

  private static String bpmn() {
    return ""
        + "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + "             targetNamespace='http://cibseven.org/test'>"
        + "  <process id='" + PROCESS_KEY + "' isExecutable='true'"
        + "           camunda:historyTimeToLive='1'>"
        + "    <startEvent id='start'/>"
        + "    <sequenceFlow id='f1' sourceRef='start' targetRef='agent'/>"
        + "    <serviceTask id='agent' camunda:class='" + AgentDelegate.class.getName() + "'/>"
        + "    <sequenceFlow id='f2' sourceRef='agent' targetRef='wait'/>"
        + "    <userTask id='wait'/>"
        + "    <sequenceFlow id='f3' sourceRef='wait' targetRef='end'/>"
        + "    <endEvent id='end'/>"
        + "  </process>"
        + "</definitions>";
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return (value == null || value.isBlank()) ? fallback : value.trim();
  }

  private static List<String> list(String csv) {
    List<String> items = new ArrayList<>();
    for (String item : csv.split(",")) {
      if (!item.isBlank()) {
        items.add(item.trim().toLowerCase(java.util.Locale.ROOT));
      }
    }
    return items;
  }
}
