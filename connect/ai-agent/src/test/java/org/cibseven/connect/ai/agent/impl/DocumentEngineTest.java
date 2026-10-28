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

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.runtime.VariableInstance;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.variable.VariableMap;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.connect.ai.agent.AgentConnectorConstants;
import org.cibseven.connect.ai.agent.AgentRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;

/**
 * Engine-backed test for the documents half of CIB7-1843: real {@code FileValue}
 * variables, read off a real execution, turned into native attachments on the
 * outgoing message.
 *
 * <p>Three of these guard interactions that are invisible in a unit test and
 * were the actual design constraints — RAG augmentation running before content
 * arguments are appended, chat memory storing the augmented message by default,
 * and the audit path serialising a multi-content message.
 */
public class DocumentEngineTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final byte[] PDF_BYTES = "%PDF-1.7 invoice body".getBytes();
  private static final byte[] PNG_BYTES = new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

  /** The outgoing user message of the last model call. */
  static volatile UserMessage capturedUserMessage;
  static volatile String capturedSystemMessage;
  /** Every message of the last model call, in order — memory replay included. */
  static volatile List<ChatMessage> capturedRequestMessages;

  private static final ProcessEngine ENGINE = buildInMemoryEngine();

  @RegisterExtension
  public ProcessEngineRule engineRule = new ProcessEngineRule(ENGINE);

  private ChatMemoryStore originalStore;

  private static ProcessEngine buildInMemoryEngine() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setJdbcUrl("jdbc:h2:mem:document-engine-test;DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    return configuration.buildProcessEngine();
  }

  @BeforeEach
  public void setUp() {
    capturedUserMessage = null;
    capturedSystemMessage = null;
    capturedRequestMessages = null;
    originalStore = AgentChatMemoryStore.getStore();
    AgentChatMemoryStore.setStore(new InMemoryChatMemoryStore());
    deployProcess();
  }

  @AfterEach
  public void tearDown() {
    capturedUserMessage = null;
    capturedSystemMessage = null;
    capturedRequestMessages = null;
    AgentChatMemoryStore.setStore(originalStore);
    engineRule.getRepositoryService().createDeploymentQuery().list()
        .forEach(d -> engineRule.getRepositoryService().deleteDeployment(d.getId(), true));
  }

  // ── stubs ──────────────────────────────────────────────────────────────────

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
      capturedRequestMessages = new ArrayList<>(request.messages());
      for (ChatMessage message : request.messages()) {
        if (message instanceof UserMessage) {
          capturedUserMessage = (UserMessage) message;
        } else if (message instanceof dev.langchain4j.data.message.SystemMessage) {
          capturedSystemMessage =
              ((dev.langchain4j.data.message.SystemMessage) message).text();
        }
      }
      return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
    }
  }

  /** Keeps the real listener wiring so the audit assertions mean something. */
  static class CapturingConnector extends AgentConnectorImpl {
    @Override
    protected ChatModel createChatModel(AgentRequest request, String apiKey, String baseUrl,
        Map<String, String> customHeaders) {
      AgentChatListener listener =
          new AgentChatListener(request.getModel(), baseUrl, request.getPersistChatLog());
      ProcessStarterToolContext.setActiveListener(listener);
      return new CapturingChatModel(listener);
    }
  }

  /** Adds a canned retriever so RAG runs without pgvector. */
  static final class RagConnector extends CapturingConnector {
    @Override
    protected ContentRetriever createContentRetriever(AgentRequest request) {
      return query -> List.of(
          dev.langchain4j.rag.content.Content.from(TextSegment.from("retrieved background")));
    }
  }

  /** Runs the connector inside a real engine command. */
  public static class AgentDelegate implements JavaDelegate {
    @Override
    public void execute(DelegateExecution execution) {
      boolean withRag = Boolean.TRUE.equals(execution.getVariable("_rag"));
      AgentConnectorImpl connector = withRag ? new RagConnector() : new CapturingConnector();
      String message = execution.hasVariable("_message")
          ? (String) execution.getVariable("_message")
          : "Read the attached invoice.";
      AgentRequest request = connector.createRequest()
          .agentName("doc-agent")
          .message(message)
          .instruction("be brief")
          .documents((String) execution.getVariable("_documents"))
          .documentMimeTypes((String) execution.getVariable("_mimeTypes"))
          .documentDetailLevel((String) execution.getVariable("_detailLevel"))
          .contextVariables((String) execution.getVariable("_context"));
      String memoryId = (String) execution.getVariable("_memoryId");
      if (memoryId != null) {
        request.useChatMemory(true).memoryId(memoryId);
      }
      connector.execute(request);
    }
  }

  // ── the attachments reach the model ────────────────────────────────────────

  @Test
  public void shouldSendFileVariablesAsNativeAttachmentsAfterTheMessage() {
    start(Variables.createVariables()
        .putValue("_documents", "invoice,scan")
        .putValueTyped("invoice", Variables.fileValue("invoice.pdf")
            .file(PDF_BYTES).mimeType("application/pdf").create())
        .putValueTyped("scan", Variables.fileValue("scan.png")
            .file(PNG_BYTES).mimeType("image/png").create()));

    List<Content> contents = capturedUserMessage.contents();
    assertThat(contents).hasSize(3);
    // The prompt text stays first; attachments follow in declaration order.
    assertThat(contents.get(0)).isInstanceOf(TextContent.class);
    assertThat(((TextContent) contents.get(0)).text()).contains("Read the attached invoice.");
    assertThat(contents.get(1)).isInstanceOf(PdfFileContent.class);
    assertThat(contents.get(2)).isInstanceOf(ImageContent.class);
    assertThat(((PdfFileContent) contents.get(1)).pdfFile().base64Data())
        .isEqualTo(Base64.getEncoder().encodeToString(PDF_BYTES));
  }

  @Test
  public void shouldLeaveTheMessageSingleTextWhenNoDocumentsAreDeclared() {
    start(Variables.createVariables().putValue("orderId", "4711"));

    assertThat(capturedUserMessage.hasSingleText()).isTrue();
  }

  @Test
  public void shouldFailTheActivityWhenADeclaredDocumentIsMissing() {
    Throwable thrown = catchThrowable(() ->
        start(Variables.createVariables().putValue("_documents", "invoice")));

    assertThat(rootCause(thrown))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("invoice");
    assertThat(capturedUserMessage).isNull();
  }

  /**
   * {@code documentMimeTypes} and the detail level, end to end through the
   * connector — a bytes variable gets its type from the override, and the
   * requested detail level reaches the image content.
   */
  @Test
  public void shouldApplyTheMimeTypeOverrideAndDetailLevelThroughTheConnector() {
    start(Variables.createVariables()
        .putValue("_documents", "scan")
        .putValue("_mimeTypes", "{\"scan\": \"image/png\"}")
        .putValue("_detailLevel", "high")
        .putValueTyped("scan", Variables.byteArrayValue(PNG_BYTES)));

    List<Content> images = capturedUserMessage.contents().stream()
        .filter(ImageContent.class::isInstance)
        .collect(Collectors.toList());
    assertThat(images).hasSize(1);
    ImageContent image = (ImageContent) images.get(0);
    assertThat(image.image().mimeType()).isEqualTo("image/png");
    assertThat(image.detailLevel()).isEqualTo(ImageContent.DetailLevel.HIGH);
  }

  /** A level the OpenAI mapping throws on fails before any model call. */
  @Test
  public void shouldRefuseADetailLevelOpenAiCannotTakeBeforeCallingTheModel() {
    Throwable thrown = catchThrowable(() ->
        start(Variables.createVariables()
            .putValue("_documents", "scan")
            .putValue("_detailLevel", "MEDIUM")
            .putValueTyped("scan", Variables.fileValue("scan.png")
                .file(PNG_BYTES).mimeType("image/png").create())));

    assertThat(rootCause(thrown))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("MEDIUM");
    assertThat(capturedUserMessage).isNull();
  }

  /**
   * A variable declared both as context and as a document is described in the
   * context block as attached — which only holds if AgentConnectorImpl passes
   * the document names on to the context resolver.
   */
  @Test
  public void shouldDescribeAVariableThatIsAlsoAttachedAsAttachedInTheContextBlock() {
    start(Variables.createVariables()
        .putValue("_documents", "invoice")
        .putValue("_context", "invoice")
        .putValueTyped("invoice", Variables.fileValue("invoice.pdf")
            .file(PDF_BYTES).mimeType("application/pdf").create()));

    assertThat(capturedSystemMessage)
        .contains("invoice (file) = (file \"invoice.pdf\", \"application/pdf\", "
            + "attached as a document)")
        .doesNotContain("content not sent to the model");
    assertThat(capturedUserMessage.contents()).anyMatch(PdfFileContent.class::isInstance);
  }

  /**
   * With an empty message LangChain4j builds the user message from the content
   * arguments and then appends them a second time, so every attachment would be
   * sent twice. The connector refuses instead of paying double.
   */
  @Test
  public void shouldFailTheActivityWhenDocumentsComeWithAnEmptyMessage() {
    Throwable thrown = catchThrowable(() ->
        start(Variables.createVariables()
            .putValue("_documents", "invoice")
            .putValue("_message", "")
            .putValueTyped("invoice", Variables.fileValue("invoice.pdf")
                .file(PDF_BYTES).mimeType("application/pdf").create())));

    assertThat(rootCause(thrown))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("'message' is empty");
    assertThat(capturedUserMessage).isNull();
  }

  // ── the two interaction traps ──────────────────────────────────────────────

  /**
   * The design constraint behind the two-parameter agent interface. LangChain4j
   * augments the user message for RAG before it appends content arguments, and
   * the augmentor calls {@code UserMessage.singleText()}. Folding the prompt
   * text into the content list would make that throw.
   */
  @Test
  public void shouldCombineRagAndDocumentsWithoutBreaking() {
    start(Variables.createVariables()
        .putValue("_rag", Boolean.TRUE)
        .putValue("_documents", "invoice")
        .putValueTyped("invoice", Variables.fileValue("invoice.pdf")
            .file(PDF_BYTES).mimeType("application/pdf").create()));

    List<Content> contents = capturedUserMessage.contents();
    assertThat(contents).hasSize(2);
    // RAG text was injected into the leading text part, the attachment survived.
    assertThat(((TextContent) contents.get(0)).text())
        .contains("Read the attached invoice.")
        .contains("retrieved background");
    assertThat(contents.get(1)).isInstanceOf(PdfFileContent.class);
  }

  /**
   * Chat memory stores the augmented user message by default, which with
   * documents attached carries their whole Base64 payload — and the memory is a
   * process variable.
   */
  @Test
  public void shouldNotPutDocumentPayloadsIntoChatMemory() {
    start(Variables.createVariables()
        .putValue("_documents", "invoice")
        .putValue("_memoryId", "mem-1")
        .putValueTyped("invoice", Variables.fileValue("invoice.pdf")
            .file(PDF_BYTES).mimeType("application/pdf").create()));

    List<ChatMessage> remembered = AgentChatMemoryStore.getStore().getMessages("mem-1");
    String encoded = Base64.getEncoder().encodeToString(PDF_BYTES);
    assertThat(remembered).isNotEmpty();
    for (ChatMessage message : remembered) {
      assertThat(message.toString()).doesNotContain(encoded);
    }
  }

  /**
   * Pins the mechanism the previous test only observes the result of, because
   * that mechanism is an implementation detail of LangChain4j rather than a
   * documented contract, and it is easy to rely on something the option does
   * not promise.
   *
   * <p>In {@code DefaultAiServices}, {@code originalUserMessage} is built from
   * the {@code @UserMessage String} parameter, and only afterwards does
   * {@code addContentsToUserMessage} fold in the {@code List<Content>}. Chat
   * memory then receives whichever of the two {@code storeRetrievedContentInChatMemory}
   * selects, while the model always receives the one with the attachments. Our
   * {@code false} therefore keeps documents out of memory — but only for as long
   * as that ordering holds. If an upgrade appends the contents earlier, Base64
   * starts flowing into a process variable, silently. This test is the alarm.
   *
   * <p>It also states the behaviour that follows for the modeler: turn two does
   * not see turn one's document.
   */
  @Test
  public void shouldNotReplayDocumentsOnASecondTurnOfTheSameMemory() {
    String encoded = Base64.getEncoder().encodeToString(PDF_BYTES);

    // Turn 1 — with the document.
    start(Variables.createVariables()
        .putValue("_documents", "invoice")
        .putValue("_memoryId", "mem-multi")
        .putValueTyped("invoice", Variables.fileValue("invoice.pdf")
            .file(PDF_BYTES).mimeType("application/pdf").create()));
    assertThat(capturedUserMessage.contents()).hasSize(2);   // text + attachment

    // Turn 2 — same memory, no document declared.
    start(Variables.createVariables().putValue("_memoryId", "mem-multi"));

    // What the model got on turn 2: the replayed turn-1 text, the answer, and
    // turn 2's own message — and not one byte of the attachment.
    assertThat(capturedRequestMessages.size()).isGreaterThanOrEqualTo(3);
    for (ChatMessage message : capturedRequestMessages) {
      assertThat(message.toString()).doesNotContain(encoded);
    }
    assertThat(capturedUserMessage.hasSingleText()).isTrue();

    // The conversation itself did survive — this is not "memory was empty".
    List<ChatMessage> remembered = AgentChatMemoryStore.getStore().getMessages("mem-multi");
    assertThat(remembered.size()).isGreaterThanOrEqualTo(4);
    for (ChatMessage message : remembered) {
      assertThat(message.toString()).doesNotContain(encoded);
    }
    assertThat(remembered.toString()).contains("Read the attached invoice.");
  }

  // ── audit ──────────────────────────────────────────────────────────────────

  @Test
  public void shouldRecordDocumentDescriptorsAndNeverBase64() throws Exception {
    start(Variables.createVariables()
        .putValue("_documents", "invoice")
        .putValueTyped("invoice", Variables.fileValue("invoice.pdf")
            .file(PDF_BYTES).mimeType("application/pdf").create()));

    String rawLog = chatLog();
    String encoded = Base64.getEncoder().encodeToString(PDF_BYTES);
    // The whole point of the extractPlainContent fix: the chat-log variable is a
    // process variable, and toString() on a multi-content message prints base64.
    assertThat(rawLog).doesNotContain(encoded);

    List<Map<String, Object>> events =
        MAPPER.readValue(rawLog, new TypeReference<List<Map<String, Object>>>() {});
    Map<String, Object> documentsEvent = single(events, "documents");
    assertThat(documentsEvent).containsEntry("count", 1)
        .containsEntry("totalBytes", PDF_BYTES.length);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> described =
        (List<Map<String, Object>>) documentsEvent.get("documents");
    assertThat(described.get(0))
        .containsEntry("variable", "invoice")
        .containsEntry("filename", "invoice.pdf")
        .containsEntry("mimeType", "application/pdf")
        .containsEntry("kind", "PDF");

    // And the request event renders the attachment as a descriptor, not a blob.
    Map<String, Object> requestEvent = single(events, "request");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> messages =
        (List<Map<String, Object>>) requestEvent.get("messages");
    String userContent = messages.stream()
        .filter(m -> "USER".equals(m.get("role")))
        .map(m -> String.valueOf(m.get("content")))
        .findFirst().orElseThrow();
    assertThat(userContent).contains("\"kind\":\"PDF\"").contains("invoice.pdf");
  }

  /**
   * {@code renderMultiContent} used to test
   * {@code instanceof TextContent} before consulting the descriptor map, and a
   * text document <em>is</em> a {@code TextContent} — so it never reached the
   * descriptor branch and its whole body was written into the chat-log process
   * variable on every turn. Up to 5 MB per model call, for exactly the content
   * the map exists to keep out.
   *
   * <p>It survived the existing tests because they attach only PDFs and images,
   * and it survived a manual run because the base64 check does not fire on text.
   * This test attaches a text document and asserts the body is absent, so the
   * regression cannot come back quietly.
   */
  @Test
  public void shouldNotWriteTextDocumentBodiesIntoTheChatLog() {
    String body = "VERTRAULICH Zahlungsziel 30 Tage Rabatt 3 Prozent";
    start(Variables.createVariables()
        .putValue("_documents", "terms")
        .putValueTyped("terms", Variables.fileValue("terms.txt")
            .file(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .mimeType("text/plain")
            .create()));

    String rawLog = chatLog();

    // The document body must not be in the chat log at all.
    assertThat(rawLog).doesNotContain(body);
    // What must be there instead is its descriptor.
    assertThat(rawLog).contains("\"kind\":\"TEXT\"").contains("terms.txt");
    // And the prompt text itself still is - it has no descriptor, so it is not
    // a document and the TEXT branch is still the right place for it.
    assertThat(rawLog).contains("Read the attached invoice.");
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private void start(VariableMap variables) {
    engineRule.getRuntimeService().startProcessInstanceByKey("documentEngineTest", variables);
  }

  private String chatLog() {
    List<VariableInstance> variables = engineRule.getRuntimeService()
        .createVariableInstanceQuery()
        .variableNameLike(AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX + "%")
        .list();
    assertThat(variables).as("chat-log variable").hasSize(1);
    return (String) variables.get(0).getValue();
  }

  private static Map<String, Object> single(List<Map<String, Object>> events, String type) {
    List<Map<String, Object>> matching = new ArrayList<>();
    for (Map<String, Object> event : events) {
      if (type.equals(event.get("type"))) {
        matching.add(event);
      }
    }
    assertThat(matching).as("events of type " + type).hasSize(1);
    return matching.get(0);
  }

  private static Throwable rootCause(Throwable thrown) {
    assertThat(thrown).as("expected the activity to fail").isNotNull();
    Throwable current = thrown;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    return current;
  }

  private void deployProcess() {
    String bpmn = ""
        + "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + "             targetNamespace='http://cibseven.org/test'>"
        + "  <process id='documentEngineTest' isExecutable='true'"
        + "           camunda:historyTimeToLive='1'>"
        + "    <startEvent id='start'/>"
        + "    <sequenceFlow id='f1' sourceRef='start' targetRef='agent'/>"
        + "    <serviceTask id='agent'"
        + "                 camunda:class='" + AgentDelegate.class.getName() + "'/>"
        + "    <sequenceFlow id='f2' sourceRef='agent' targetRef='wait'/>"
        + "    <userTask id='wait'/>"
        + "    <sequenceFlow id='f3' sourceRef='wait' targetRef='end'/>"
        + "    <endEvent id='end'/>"
        + "  </process>"
        + "</definitions>";

    engineRule.getRepositoryService()
        .createDeployment()
        .addString("documentEngineTest.bpmn20.xml", bpmn)
        .deploy();
  }
}
