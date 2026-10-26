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

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;

/**
 * The two halves of an unknown tool name that do not need an engine (CIB7-2150): what the model
 * is told, and that being told it costs one model call rather than two.
 *
 * <p>What the connector tells the model is {@link AgentConnectorImpl#hallucinatedToolMessage}, and
 * it is called here directly — the end-to-end path that carries it into the conversation is
 * {@code AdHocUnknownToolNameTurnTest}. The MCP half of the catalogue is only reachable this way:
 * {@link AgentConnectorImpl#availableTools} asks each {@link McpClient} for its tools, which a
 * turn test with no MCP server never exercises.
 *
 * <p>The cost half is a property of LangChain4j rather than of this module, which is why it is
 * pinned rather than assumed: a strategy that <em>throws</em> ends the AI service call, whereas
 * one that returns a result would leave {@code TO_LLM} — the default {@link
 * dev.langchain4j.agent.tool.ReturnBehavior} for a name this module never registered, and not
 * markable {@code IMMEDIATE} the way a real tool is — to ask the model again inside the same job.
 * That second call is one the turn's model-call cap never counts.
 */
public class AdHocUnknownToolNameTest {

  // ── what the model is told ───────────────────────────────────────────────

  private static ToolExecutionRequest call(String name) {
    return ToolExecutionRequest.builder().id("call-1").name(name).arguments("{}").build();
  }

  private static AgentConnectorImpl.AvailableTools tools(String... names) {
    return new AgentConnectorImpl.AvailableTools(Arrays.asList(names), true);
  }

  @Test
  public void theMessageNamesTheUnknownToolAndEveryAvailableOne() {
    String message = AgentConnectorImpl.hallucinatedToolMessage(
        call("reviewDocuments"), tools("checkInvoice", "askHuman", "completeScope"));

    assertThat(message)
        .isEqualTo("There is no tool named 'reviewDocuments'. Available tools in this ad hoc"
            + " sub process: checkInvoice, askHuman, completeScope."
            + " Call one of these instead.");
  }

  /**
   * A name long enough to dominate the prompt is cut, like every other model- or model-supplied
   * string this module puts in front of a model: the correction is written into chat memory and
   * resent on every later request of the conversation.
   */
  @Test
  public void anOverlongNameIsCutRatherThanEchoedWhole() {
    String huge = repeat("x", AdHocSubProcessTool.MAX_NAME_CHARS + 500);

    String message =
        AgentConnectorImpl.hallucinatedToolMessage(call(huge), tools("work", "completeScope"));

    assertThat(message.length())
        .as("the correction must not grow with whatever the model made up")
        .isLessThan(huge.length());
    assertThat(message).contains("work", "completeScope");
  }

  private static String repeat(String unit, int times) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < times; i++) {
      text.append(unit);
    }
    return text.toString();
  }

  /**
   * A null name must read the same way {@link AgentConnectorImpl.HallucinatedToolException}
   * already renders it — "?" — rather than {@code String.valueOf(null)}'s literal word "null",
   * which looks like a name the model could have typed and did not.
   */
  @Test
  public void aNullNameIsShownAsAQuestionMarkNotTheLiteralWordNull() {
    String message = AgentConnectorImpl.hallucinatedToolMessage(call(null), tools("work"));

    assertThat(message)
        .contains("There is no tool named '?'")
        .doesNotContain("named 'null'");
  }

  /**
   * A provider that sends {@code function.name: ""} rather than omitting it entirely is exactly
   * as unreadable as one that omits it — {@code shownName} must treat both the same way
   * {@code mcpToolName} already treats a null-or-empty tool name, not only the null half of it.
   */
  @Test
  public void anEmptyNameIsShownAsAQuestionMarkTooNotAnEmptyQuote() {
    String message = AgentConnectorImpl.hallucinatedToolMessage(call(""), tools("work"));

    assertThat(message)
        .contains("There is no tool named '?'")
        .doesNotContain("named ''");
  }

  /**
   * Two or more unreachable servers must not read as if only one were down — "one"/"an" both
   * claim exactly one, which understates the gap the moment a second server is also unreachable.
   */
  @Test
  public void theIncompleteCaveatDoesNotClaimExactlyOneServerIsDown() {
    AgentConnectorImpl.AvailableTools shortOfServers =
        new AgentConnectorImpl.AvailableTools(Arrays.asList("work"), false);

    String message = AgentConnectorImpl.hallucinatedToolMessage(call("ghost"), shortOfServers);

    assertThat(message)
        .as("true whether one server or several are unreachable, unlike 'one'/'an'")
        .contains("at least one MCP server could not be reached");
  }

  // ── the catalogue the message is built from ──────────────────────────────

  /**
   * An MCP tool is as callable as an activity of the scope, so a correction that left it out
   * would tell the model a tool it has does not exist. The names must match {@code execute}'s
   * own {@code toolNameMapper} exactly, prefix included.
   */
  @Test
  public void theCatalogueCarriesMcpToolsUnderTheirPrefixedNames() {
    McpClient engine = mock(McpClient.class);
    when(engine.listTools()).thenReturn(Arrays.asList(
        ToolSpecification.builder().name("add_comment").build(),
        ToolSpecification.builder().name("find_task").build()));
    Map<McpClient, String> prefixes = new LinkedHashMap<>();
    prefixes.put(engine, "engine");

    AgentConnectorImpl.AvailableTools available = AgentConnectorImpl.availableTools(
        null, Collections.singletonList(engine), prefixes);

    assertThat(available.names())
        .as("an agentic scope with no catalogue still offers completeScope and the MCP tools")
        .contains("engine__add_comment", "engine__find_task",
            AgenticToolCatalogParserNames.COMPLETE_SCOPE);
    assertThat(available.contains("engine__add_comment")).isTrue();
    assertThat(available.isComplete()).isTrue();
  }

  /**
   * A spec without a name is still a real tool: {@code toolNameMapper} (in {@code execute()})
   * cannot decline to register it either, only name it, so it becomes callable under whatever
   * {@link AgentConnectorImpl#mcpToolName} returns. An earlier version of this fix dropped it
   * from the catalogue instead — which would have told the model a tool it genuinely has does
   * not exist, the opposite of what this correction is for. This pins that the catalogue lists
   * it under the exact same name {@code mcpToolName} (and so {@code toolNameMapper}) produces.
   */
  @Test
  public void theCatalogueNormalisesRatherThanDropsAnMcpToolSpecWithoutAName() {
    ToolSpecification nameless = mock(ToolSpecification.class);
    when(nameless.name()).thenReturn(null);
    McpClient engine = mock(McpClient.class);
    when(engine.listTools()).thenReturn(Arrays.asList(
        nameless, ToolSpecification.builder().name("add_comment").build()));
    Map<McpClient, String> prefixes = new LinkedHashMap<>();
    prefixes.put(engine, "engine");

    AgentConnectorImpl.AvailableTools available = AgentConnectorImpl.availableTools(
        null, Collections.singletonList(engine), prefixes);

    assertThat(available.names())
        .as("the exact name toolNameMapper would register this spec's tool under")
        .contains(AgentConnectorImpl.mcpToolName("engine", null))
        .contains("engine__add_comment");
  }

  /**
   * The formula itself, pinned directly: a null or blank name must not surface as the bare word
   * "null" or a dangling trailing separator, in whichever of the three places calls it.
   */
  @Test
  public void mcpToolNameNormalisesANullOrBlankToolNameRatherThanEmbeddingItLiterally() {
    assertThat(AgentConnectorImpl.mcpToolName("engine", null)).isEqualTo("engine__(unnamed)");
    assertThat(AgentConnectorImpl.mcpToolName("engine", "")).isEqualTo("engine__(unnamed)");
    // Whitespace-only, not only the exact empty string: "blank" is the word the class next to
    // this one uses, and a name of only spaces is exactly as uncallable as an absent one.
    assertThat(AgentConnectorImpl.mcpToolName("engine", "   ")).isEqualTo("engine__(unnamed)");
    assertThat(AgentConnectorImpl.mcpToolName("engine", "add_comment"))
        .isEqualTo("engine__add_comment");
  }

  /**
   * The third of the three places {@code mcpToolName}'s own javadoc says must agree: the audit
   * trail must cover a null-named spec's tool under the same key the catalogue and {@code
   * toolNameMapper} use for it, not silently omit it — leaving it out would mean a call to a
   * tool the model was genuinely offered goes unattributed in the Art. 26 audit log.
   */
  @Test
  public void provenanceCoversAnMcpToolSpecWithoutANameUnderTheSameKeyAsTheCatalogue() {
    ToolSpecification nameless = mock(ToolSpecification.class);
    when(nameless.name()).thenReturn(null);
    McpClient engine = mock(McpClient.class);
    when(engine.listTools()).thenReturn(Collections.singletonList(nameless));
    Map<McpClient, String> prefixes = new LinkedHashMap<>();
    prefixes.put(engine, "engine");

    Map<String, Map<String, Object>> provenance = AgentConnectorImpl.buildToolProvenance(
        Collections.emptyList(), Collections.singletonList(engine),
        Collections.emptyList(), prefixes);

    assertThat(provenance).containsKey(AgentConnectorImpl.mcpToolName("engine", null));
  }

  /**
   * The structurally identical {@code if (specs == null) continue} this method has next to
   * {@code availableTools}'s own — fixed to log in the round before this one — used to move on
   * silently. An operator reading only the audit log had no sign at all that a server's tools
   * were missing from it, the same gap the sibling fix in the catalogue exists to avoid.
   */
  @Test
  public void aClientThatReturnsNullToolsLogsDuringProvenanceBuildToo() {
    McpClient returnsNull = mock(McpClient.class);
    when(returnsNull.listTools()).thenReturn(null);
    Map<McpClient, String> prefixes = new LinkedHashMap<>();
    prefixes.put(returnsNull, "flaky");

    Logger logger = (Logger) LoggerFactory.getLogger(AgentConnectorImpl.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    Map<String, Map<String, Object>> provenance;
    try {
      provenance = AgentConnectorImpl.buildToolProvenance(
          Collections.emptyList(), Collections.singletonList(returnsNull),
          Collections.emptyList(), prefixes);
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }

    assertThat(provenance).as("no entries from the broken client, but no exception either").isEmpty();
    assertThat(appender.list).hasSize(1);
    ILoggingEvent logged = appender.list.get(0);
    assertThat(logged.getLevel()).isEqualTo(Level.WARN);
    assertThat(logged.getFormattedMessage())
        .as("an operator reading only the audit log must still see that a server's tools"
            + " are missing from it")
        .contains("returned no tools");
  }

  /**
   * A server that cannot answer must not fail the correction — but the model must not be handed
   * the shortened list as if it were the whole truth either, or it concludes a tool it really
   * has is gone.
   */
  @Test
  public void anUnreachableMcpServerShortensTheListAndTheMessageSaysSo() {
    McpClient broken = mock(McpClient.class);
    when(broken.listTools()).thenThrow(new IllegalStateException("connection refused"));
    Map<McpClient, String> prefixes = new LinkedHashMap<>();
    prefixes.put(broken, "krank");

    AgentConnectorImpl.AvailableTools available = AgentConnectorImpl.availableTools(
        null, Collections.singletonList(broken), prefixes);

    assertThat(available.isComplete())
        .as("the list is short of a whole server's tools")
        .isFalse();

    String message =
        AgentConnectorImpl.hallucinatedToolMessage(call("reviewDocuments"), available);
    assertThat(message)
        .as("the model must be told the list may be short, not left to infer the tool is gone")
        .contains("may be short");
  }

  /**
   * A client is free to return {@code null} from {@code listTools()} rather than throw —
   * iterating it directly would NPE into the surrounding catch and log "could not list... failed"
   * for a call that in fact succeeded, the wrong diagnosis for an operator to chase. {@link
   * AgentConnectorImpl#buildToolProvenance} already guards its own identical loop the same way;
   * this pins the sibling guard in the catalogue.
   */
  @Test
  public void aClientThatReturnsNullToolsShortensTheListWithoutThrowing() {
    McpClient returnsNull = mock(McpClient.class);
    when(returnsNull.listTools()).thenReturn(null);
    Map<McpClient, String> prefixes = new LinkedHashMap<>();
    prefixes.put(returnsNull, "flaky");

    Logger logger = (Logger) LoggerFactory.getLogger(AgentConnectorImpl.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    AgentConnectorImpl.AvailableTools available;
    try {
      available = AgentConnectorImpl.availableTools(
          null, Collections.singletonList(returnsNull), prefixes);
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }

    assertThat(available.isComplete())
        .as("a null tool list is still a list this catalogue is short of")
        .isFalse();
    assertThat(available.names())
        .as("the scope's own tools must still be offered despite the one broken client")
        .contains(AgenticToolCatalogParserNames.COMPLETE_SCOPE);

    // The behaviour above is identical whether this is handled explicitly or falls through to
    // the generic catch (Exception) block via an NPE — only the log line tells them apart, and
    // only the explicit guard gives an operator the right diagnosis: the call succeeded and
    // returned nothing, not a failure worth attaching a stack trace to.
    assertThat(appender.list).hasSize(1);
    ILoggingEvent logged = appender.list.get(0);
    assertThat(logged.getLevel()).isEqualTo(Level.WARN);
    assertThat(logged.getFormattedMessage())
        .as("the message must say the call returned nothing, not that it failed")
        .contains("returned no tools")
        .doesNotContain("Could not list");
    assertThat(logged.getThrowableProxy())
        .as("nothing was thrown — an NPE here would mean the explicit guard was skipped")
        .isNull();
  }

  /** Names it so the catalogue test does not restate the literal under test. */
  private static final class AgenticToolCatalogParserNames {
    static final String COMPLETE_SCOPE =
        org.cibseven.connect.ai.agent.agentic.AgenticToolCatalogParser.COMPLETE_SCOPE_TOOL;
  }

  // ── the catalogue must name what the model was actually offered ──────────

  // That the correction's completeScope is the one AdHocToolProvider registers cannot be shown
  // here without a deployed scope — comparing the constant with itself would show nothing. It
  // is pinned against the real provider in AdHocUnknownToolNameTurnTest, where the registered
  // specification and the correction travel in the same request.

  /**
   * The three places that build an MCP tool name — the {@code toolNameMapper} the model is
   * offered, the provenance entry, and the correction's catalogue — must agree. They now share
   * {@link AgentConnectorImpl#mcpToolName}; this pins that the catalogue uses it rather than a
   * copy that could drift from the mapper.
   */
  @Test
  public void theCatalogueBuildsMcpNamesWithTheSharedFormula() {
    McpClient engine = mock(McpClient.class);
    when(engine.listTools()).thenReturn(Collections.singletonList(
        ToolSpecification.builder().name("add_comment").build()));
    Map<McpClient, String> prefixes = new LinkedHashMap<>();
    prefixes.put(engine, "engine");

    AgentConnectorImpl.AvailableTools available = AgentConnectorImpl.availableTools(
        null, Collections.singletonList(engine), prefixes);

    assertThat(available.names())
        .contains(AgentConnectorImpl.mcpToolName("engine", "add_comment"));
  }

  /** A client with no prefix of its own still gets the one the mapper would give it. */
  @Test
  public void anUnmappedMcpClientFallsBackToTheSamePrefixTheMapperUses() {
    McpClient nameless = mock(McpClient.class);
    when(nameless.listTools()).thenReturn(Collections.singletonList(
        ToolSpecification.builder().name("ping").build()));

    AgentConnectorImpl.AvailableTools available = AgentConnectorImpl.availableTools(
        null, Collections.singletonList(nameless), new LinkedHashMap<>());

    assertThat(available.names()).contains(
        AgentConnectorImpl.mcpToolName(AgentConnectorImpl.DEFAULT_MCP_PREFIX, "ping"));
  }

  /**
   * The third place that builds an MCP tool name: the audit entry. Its fallback prefix must be
   * the same one the catalogue and the {@code toolNameMapper} fall back to, or a client that
   * never made it into the prefix map is attributed in the audit log under a name the model was
   * never offered — and the audit trail stops lining up with the conversation.
   *
   * <p>Only reachable with a client absent from the map, which is why the turn tests (where the
   * map always has an entry) leave it open.
   */
  @Test
  public void provenanceFallsBackToTheSamePrefixAsTheCatalogue() {
    McpClient nameless = mock(McpClient.class);
    when(nameless.listTools()).thenReturn(Collections.singletonList(
        ToolSpecification.builder().name("ping").build()));

    Map<String, Map<String, Object>> provenance = AgentConnectorImpl.buildToolProvenance(
        Collections.emptyList(),
        Collections.singletonList(nameless),
        Collections.emptyList(),
        new LinkedHashMap<>());

    String expected = AgentConnectorImpl.mcpToolName(AgentConnectorImpl.DEFAULT_MCP_PREFIX,
        "ping");
    assertThat(provenance).containsKey(expected);
    assertThat(provenance.get(expected))
        .containsEntry("server", AgentConnectorImpl.DEFAULT_MCP_PREFIX);
  }

  // ── the message that has to be answered ──────────────────────────────────

  /**
   * The search runs from the end: an earlier step's answered call must not be picked up and
   * answered a second time. Unit-level here because it is the direction, not the engine, that
   * is under test.
   */
  @Test
  public void theMessageToAnswerIsTheNewestOneWithOpenCalls() {
    AiMessage older = AiMessage.from(ToolExecutionRequest.builder()
        .id("old").name("work").arguments("{}").build());
    AiMessage newer = AiMessage.from(ToolExecutionRequest.builder()
        .id("new").name("reviewDocuments").arguments("{}").build());

    AiMessage found = AgentConnectorImpl.lastAiMessageWithToolCalls(Arrays.<ChatMessage>asList(
        dev.langchain4j.data.message.UserMessage.from("first"),
        older,
        ToolExecutionResultMessage.from(older.toolExecutionRequests().get(0), "done"),
        dev.langchain4j.data.message.UserMessage.from("second"),
        newer));

    assertThat(found).isSameAs(newer);
    assertThat(found.toolExecutionRequests().get(0).id()).isEqualTo("new");
  }

  /** No open call at all — null, not an exception: the caller ends the step on it. */
  @Test
  public void noMessageWithToolCallsYieldsNullRatherThanThrowing() {
    assertThat(AgentConnectorImpl.lastAiMessageWithToolCalls(
        Collections.<ChatMessage>emptyList())).isNull();
    assertThat(AgentConnectorImpl.lastAiMessageWithToolCalls(Arrays.<ChatMessage>asList(
        dev.langchain4j.data.message.UserMessage.from("only a user message"),
        AiMessage.from("and a plain answer")))).isNull();
  }

  // ── position decides what a sibling call is told ─────────────────────────

  /**
   * {@code indexOfName} is what locates the invented call among its siblings so position can be
   * compared against it. A nameless call must be exactly as locatable as a named one: some
   * OpenAI-compatible providers omit {@code function.name}, and the strategy still has to end
   * the step cleanly for that call — see {@link #aRequestWithoutANameIsStillDescribable}. Before
   * round 5 of the CIB7-2150 review, a null name short-circuited to {@code -1} unconditionally,
   * which made every sibling of a nameless invented call look as if the batch had never stopped.
   */
  @Test
  public void indexOfNameLocatesANamelessCallInsteadOfGivingUpOnIt() {
    List<ToolExecutionRequest> calls =
        Arrays.asList(call("work"), call(null), call("reviewDocuments"));

    assertThat(AgentConnectorImpl.indexOfName(calls, null)).isEqualTo(1);
    assertThat(AgentConnectorImpl.indexOfName(calls, "reviewDocuments")).isEqualTo(2);
    assertThat(AgentConnectorImpl.indexOfName(calls, "neverThere")).isEqualTo(-1);
  }

  /**
   * The fix for the other gap round 5 confirmed by mutation: a call before the invented name is
   * classified by its position, not by whether the catalogue still lists it. {@code ToolService}
   * only reaches call {@code index} by having already found and invoked an executor for the one
   * before it, so the catalogue going stale afterwards — an MCP server delisting a tool between
   * serving it and this repair's fresh {@code listTools()} — must not override that proof.
   */
  @Test
  public void aCallBeforeTheBoundaryIsNotSecondGuessedByAShrunkenCatalogue() {
    ToolExecutionRequest ranAlready = call("engine__add_comment");
    AgentConnectorImpl.AvailableTools shrunkenCatalogue = tools("work", "completeScope");

    String message = AgentConnectorImpl.pendingCallMessage(ranAlready, 0, 1, shrunkenCatalogue);

    assertThat(message)
        .as("position proves an executor ran this one; the catalogue no longer listing it must"
            + " not override that proof")
        .contains("'engine__add_comment' ran before that happened")
        .doesNotContain("There is no tool named 'engine__add_comment'");
  }

  /** The call the strategy actually threw for: the catalogue, regardless of position. */
  @Test
  public void theInventedCallItselfAlwaysGetsTheCatalogue() {
    ToolExecutionRequest invented = call("reviewDocuments");
    AgentConnectorImpl.AvailableTools available = tools("work");

    assertThat(AgentConnectorImpl.pendingCallMessage(invented, 1, 1, available))
        .isEqualTo(AgentConnectorImpl.hallucinatedToolMessage(invented, available));
  }

  /**
   * After the boundary nothing proves whether a call ran, so a name the catalogue does not
   * recognise either is told so outright — costing the model nothing further to learn what this
   * repair already knows — rather than sent back to find out the same way the first one did.
   */
  @Test
  public void aSecondInventedNameAfterTheBoundaryGetsTheCatalogueNotJustRetry() {
    ToolExecutionRequest alsoInvented = call("summarizeDocs");

    String message = AgentConnectorImpl.pendingCallMessage(alsoInvented, 2, 0, tools("work"));

    assertThat(message)
        .contains("There is no tool named 'summarizeDocs'")
        .doesNotContain("Call it again if you still need it");
  }

  /** After the boundary, a genuinely real tool that never got a chance to run is told to retry. */
  @Test
  public void aRealToolAfterTheBoundaryIsToldItNeverRan() {
    ToolExecutionRequest neverReached = call("work");

    String message = AgentConnectorImpl.pendingCallMessage(neverReached, 2, 0, tools("work"));

    assertThat(message).contains("This call did not run", "Call it again if you still need it");
  }

  /**
   * The after-the-boundary branch must check {@code isComplete()} too, not only {@code
   * contains()}: both of the two tests above use a complete catalogue, which cannot tell apart
   * "the catalogue says no" from "the catalogue is not trustworthy right now" — a mutant that
   * dropped the {@code isComplete()} half of that condition would pass both of them unnoticed.
   * An unreachable MCP server's tool, never reached because the batch stopped before it, must be
   * told to retry rather than told it does not exist.
   */
  @Test
  public void anIncompleteCatalogueDoesNotMarkAnUnrecognisedNameAsInventedAfterTheBoundary() {
    AgentConnectorImpl.AvailableTools shortOfAServer =
        new AgentConnectorImpl.AvailableTools(Arrays.asList("work"), false);
    ToolExecutionRequest neverReached = call("engine__add_comment");

    String message =
        AgentConnectorImpl.pendingCallMessage(neverReached, 2, 0, shortOfAServer);

    assertThat(message)
        .as("not listed by a catalogue that is known to be short proves nothing — only the"
            + " position does, and this call never ran")
        .contains("This call did not run")
        .doesNotContain("There is no tool named");
  }

  /**
   * {@code stoppedAt < 0} — the invented name not located among these calls at all — should not
   * happen; classification then folds into the same catalogue-only branch the after-boundary
   * case uses, rather than guessing an order that was never observed.
   */
  @Test
  public void anUnlocatableBoundaryFallsBackToTheCatalogueAlone() {
    AgentConnectorImpl.AvailableTools available = tools("work");

    assertThat(AgentConnectorImpl.pendingCallMessage(call("work"), 0, -1, available))
        .as("the catalogue recognises this one, so it is told to retry rather than told it does"
            + " not exist")
        .contains("This call did not run");
    assertThat(AgentConnectorImpl.pendingCallMessage(call("ghost"), 0, -1, available))
        .as("the catalogue does not recognise this one, so the correction is what it needs")
        .contains("There is no tool named 'ghost'");
  }

  /**
   * The message a before-boundary call gets must leave room for both of its possible pasts: a
   * refusal looks exactly like a successful call from here, because {@link
   * AgentConnectorImpl.FailureAsResult} turns a refusal into an ordinary return value rather than
   * a failure. Asserting "its effect stands" outright — the wording round 5 of the review flagged
   * — would be false for a refused call; this pins the replacement text instead.
   */
  @Test
  public void theRanBeforeMessageLeavesRoomForARefusalNotJustSuccess() {
    String message = AgentConnectorImpl.pendingCallMessage(call("work"), 0, 1, tools("work"));

    assertThat(message)
        .as("both pasts must be legible to the model, since this method cannot tell them apart")
        .contains("check the next turn's state")
        .contains("if it was refused instead")
        .doesNotContain("so its effect stands");
  }

  /**
   * A provider that omits {@code function.name} is not only the invented call's problem — any
   * sibling of the same abandoned batch can be nameless too, before or after the boundary.
   * {@code pending.name()} embedded raw would write the literal word "null" into a sentence the
   * model reads as a name; {@code hallucinatedToolMessage} already renders this as "?", and both
   * sibling messages must match it rather than having their own, different answer to the same
   * situation.
   */
  @Test
  public void aNamelessSiblingIsShownAsAQuestionMarkInBothSiblingMessages() {
    ToolExecutionRequest nameless = call(null);

    String ranBefore = AgentConnectorImpl.pendingCallMessage(nameless, 0, 1, tools("work"));
    assertThat(ranBefore).contains("'?' ran before that happened").doesNotContain("'null'");

    // A complete catalogue never contains a null name, so a nameless call after the boundary
    // would otherwise resolve as "invented" (correctly) rather than reach neverRanMessage at
    // all; an incomplete one is what makes this specific branch reachable with a null name.
    AgentConnectorImpl.AvailableTools incomplete =
        new AgentConnectorImpl.AvailableTools(Arrays.asList("work"), false);
    String neverRan = AgentConnectorImpl.pendingCallMessage(nameless, 2, 0, incomplete);
    assertThat(neverRan).contains("before '?' was reached").doesNotContain("'null'");
  }

  // ── that it costs one model call, not two ────────────────────────────────

  /** The model, scripted: hallucinate a tool once, then answer. */
  static final class HallucinatingModel implements ChatModel {
    int calls;

    @Override
    public ChatResponse doChat(ChatRequest request) {
      calls++;
      AiMessage message = (calls == 1)
          ? AiMessage.from(ToolExecutionRequest.builder()
              .id("call-1").name("reviewDocuments").arguments("{}").build())
          : AiMessage.from("done");
      return ChatResponse.builder().aiMessage(message).build();
    }
  }

  interface StepAgent {
    Result<String> chat(@UserMessage String message);
  }

  private static ToolProvider oneRealTool() {
    return request -> {
      Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
      tools.put(ToolSpecification.builder().name("work").description("does work").build(),
          (ToolExecutionRequest toolRequest, Object memoryId) -> "{}");
      return new ToolProviderResult(tools);
    };
  }

  private static StepAgent agentWith(HallucinatingModel model) {
    return AiServices.builder(StepAgent.class)
        .chatModel(model)
        .toolProvider(oneRealTool())
        // The same shape execute() installs: the request is carried out, nothing else.
        .hallucinatedToolNameStrategy(toolRequest -> {
          throw new AgentConnectorImpl.HallucinatedToolException(toolRequest);
        })
        .build();
  }

  /**
   * The cap requirement of CIB7-2150: a hallucinated name costs exactly the one model call that
   * made it up, as an immediate tool's refusal does for CIB7-2141 — otherwise a model that keeps
   * hallucinating burns calls the turn's cap never sees, inside what looks like a single step.
   */
  @Test
  public void aHallucinatedToolNameEndsTheStepWithoutAskingTheModelAgain() {
    HallucinatingModel model = new HallucinatingModel();

    assertThatThrownBy(() -> agentWith(model).chat("go"))
        .isInstanceOf(AgentConnectorImpl.HallucinatedToolException.class);

    assertThat(model.calls)
        .as("the model must not be asked again within the same step")
        .isOne();
  }

  /**
   * The invented name has to survive the throw: it marks where the batch stopped, which is what
   * tells a sibling that ran from one that never did.
   */
  @Test
  public void theExceptionCarriesTheInventedNameThatMarksWhereTheBatchStopped() {
    ToolExecutionRequest original = ToolExecutionRequest.builder()
        .id("call-7").name("reviewDocuments").arguments("{}").build();

    AgentConnectorImpl.HallucinatedToolException exception =
        new AgentConnectorImpl.HallucinatedToolException(original);

    assertThat(exception.invented()).isEqualTo("reviewDocuments");
    assertThat(exception.getMessage()).contains("reviewDocuments");
  }

  /** A request this handler cannot read must not become a failure of its own. */
  @Test
  public void aRequestWithoutANameIsStillDescribable() {
    assertThat(new AgentConnectorImpl.HallucinatedToolException((ToolExecutionRequest) null)
        .invented()).isNull();
    assertThat(new AgentConnectorImpl.HallucinatedToolException((ToolExecutionRequest) null)
        .getMessage()).contains("?");
  }

  /** A provider that omits tool-call ids must not turn into a job failure of its own. */
  @Test
  public void aRequestWithoutAnIdIsStillDescribable() {
    ToolExecutionRequest withoutId =
        ToolExecutionRequest.builder().name("reviewDocuments").arguments("{}").build();

    assertThat(withoutId.id()).isNull();
    assertThat(new AgentConnectorImpl.HallucinatedToolException(withoutId).getMessage())
        .contains("reviewDocuments");
    assertThat(AgentConnectorImpl.hallucinatedToolMessage(withoutId, tools("work")))
        .contains("reviewDocuments");
  }

  /** Guards the one call site that would NPE on a null catalogue rather than listing nothing. */
  @Test
  public void aScopeWithoutMcpServersStillYieldsItsOwnTools() {
    List<McpClient> none = Collections.emptyList();

    AgentConnectorImpl.AvailableTools available =
        AgentConnectorImpl.availableTools(null, none, new LinkedHashMap<>());

    assertThat(available.names()).containsExactly(AgenticToolCatalogParserNames.COMPLETE_SCOPE);
    assertThat(available.isComplete()).isTrue();
  }
}
