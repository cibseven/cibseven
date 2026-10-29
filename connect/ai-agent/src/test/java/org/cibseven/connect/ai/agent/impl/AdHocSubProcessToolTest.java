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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.history.HistoricDetail;
import org.cibseven.bpm.engine.history.HistoricVariableUpdate;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.runtime.VariableInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.type.ValueType;
import org.cibseven.bpm.engine.variable.value.TypedValue;
import org.cibseven.connect.ScriptedAgent;
import org.cibseven.connect.ai.agent.agentic.AdHocAgentState;
import org.cibseven.connect.ai.agent.agentic.AdHocConversation;
import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link AdHocSubProcessTool} — the three tools the model calls to see, start and
 * end the activities of the ad hoc sub process it runs in.
 *
 * <h3>Why there is no language model here</h3>
 * The tool methods are ordinary Java methods that take their context from two
 * thread locals: the BPMN execution from {@code Context.getBpmnExecutionContext()}
 * and the engine from {@link ProcessStarterToolContext}. Nothing about them needs
 * a model to decide anything, so the decision a model would make is scripted through
 * {@link ScriptedAgent} instead. A stub model would exercise LangChain4j's tool
 * dispatch — someone else's code — rather than these methods.
 *
 * <p>What that costs: these tests do not show that the tool <em>descriptions</em>
 * lead a real model to call the tools in a sensible order. That needs an
 * end-to-end run against a model and is not covered here.
 *
 * <h3>Why the turns are real jobs</h3>
 * The agent is configuration on the ad hoc sub process, and a turn is a job on that
 * scope's execution. {@code requireAdHocScope} walks the execution tree and identifies
 * the scope by its behaviour, so the tools are called from a real turn on a real parked
 * scope rather than against a fabricated execution. Only the model is scripted.
 */
public class AdHocSubProcessToolTest {

  private static final ProcessEngine ENGINE = buildInMemoryEngine();


  private static ProcessEngine buildInMemoryEngine() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setJdbcUrl("jdbc:h2:mem:adhoc-tool-test;DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    configuration.setHistoryTimeToLive("P30D");
    // 'full' is what records a variable update per activity instance, which is
    // the source describeFinished asks first.
    configuration.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
    // Brings the parse listener that parks an agentic scope and the handler that runs a turn.
    configuration.setProcessEnginePlugins(
        Collections.<ProcessEnginePlugin>singletonList(new ConnectProcessEnginePlugin()));
    return configuration.buildProcessEngine();
  }

  // --- the scripted agent ----------------------------------------------------

  /** What the agent does in one turn. */
  interface Turn {
    Object run(AdHocSubProcessTool tool);
  }

  /** What the scripted turns returned and what they ran into. */
  static final class Agent {

    static final List<Object> RESULTS = new ArrayList<>();
    static final List<RuntimeException> FAILURES = new ArrayList<>();

    private Agent() {
    }

    static void clear() {
      RESULTS.clear();
      FAILURES.clear();
    }
  }

  /**
   * One scripted turn.
   *
   * <p>A refusal is caught rather than propagated: several tests assert on one, and a
   * propagated exception would roll the turn back and hide the state the assertion is
   * about. {@link TurnFailure} is the exception, for the tests whose subject IS the
   * rollback.
   */
  private static ScriptedAgent.Turn agent(Turn turn) {
    return parameters -> {
      try {
        Agent.RESULTS.add(turn.run(new AdHocSubProcessTool()));
      } catch (TurnFailure e) {
        throw e;
      } catch (RuntimeException e) {
        Agent.FAILURES.add(e);
      }
      return "done";
    };
  }

  /** Thrown by a scripted turn that is meant to fail the job and roll its work back. */
  static final class TurnFailure extends RuntimeException {
    TurnFailure(String message) {
      super(message);
    }
  }

  /**
   * Writes enough long values that their total exceeds
   * {@link AdHocSubProcessTool#MAX_RESULT_BLOCK_CHARS}, so the per-turn budget has
   * to cut the block off. Each single value is over the per-value cap too, so every
   * one of them costs the truncated length.
   */
  public static class WritesManyLongTexts implements JavaDelegate {

    static final int COUNT =
        (AdHocSubProcessTool.MAX_RESULT_BLOCK_CHARS / AdHocSubProcessTool.MAX_RESULT_VALUE_CHARS) + 2;

    static String names() {
      StringBuilder names = new StringBuilder();
      for (int i = 0; i < COUNT; i++) {
        names.append(i == 0 ? "" : ",").append("block").append(i);
      }
      return names.toString();
    }

    @Override
    public void execute(DelegateExecution execution) {
      StringBuilder text = new StringBuilder();
      for (int i = 0; i < AdHocSubProcessTool.MAX_RESULT_VALUE_CHARS + 100; i++) {
        text.append('y');
      }
      for (int i = 0; i < COUNT; i++) {
        execution.setVariable("block" + i, text.toString());
      }
    }
  }

  /** Writes a string longer than {@link AdHocSubProcessTool#MAX_RESULT_VALUE_CHARS}. */
  public static class WritesLongText implements JavaDelegate {

    static final int LENGTH = AdHocSubProcessTool.MAX_RESULT_VALUE_CHARS + 500;

    @Override
    public void execute(DelegateExecution execution) {
      StringBuilder text = new StringBuilder(LENGTH);
      for (int i = 0; i < LENGTH; i++) {
        text.append('x');
      }
      execution.setVariable("report", text.toString());
    }
  }

  /** Writes a value whose type is not primitive, so it must not reach the prompt. */
  public static class WritesObject implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
      execution.setVariable("payload",
          Variables.objectValue(new ArrayList<>(Arrays.asList("a", "b"))).create());
    }
  }

  /**
   * Writes the values the engine counts as primitive that must still not reach a prompt
   * (CIB7-2233): a byte array large enough that its Base64 alone would exceed the per-turn
   * budget, a file, and a date. Next to them a string, which is to pass unchanged.
   */
  public static class WritesDocument implements JavaDelegate {

    static final String NAMES = "title,doc,scan,issuedOn";
    static final byte[] BYTES = new byte[AdHocSubProcessTool.MAX_RESULT_BLOCK_CHARS * 2];

    static {
      Arrays.fill(BYTES, (byte) 'p');
    }

    @Override
    public void execute(DelegateExecution execution) {
      execution.setVariable("title", "Rechnung 7");
      execution.setVariable("doc", Variables.byteArrayValue(BYTES));
      execution.setVariable("scan", Variables.fileValue("rechnung-7.pdf")
          .file(BYTES).mimeType("application/pdf").create());
      execution.setVariable("issuedOn", new Date(0L));
    }
  }

  /**
   * Writes more values than fit the per-turn budget, each of them quotes only, so that each
   * takes twice its length in the tool result as written: JSON escapes every quote.
   */
  public static class WritesManyQuotedTexts implements JavaDelegate {

    static final int COUNT =
        (AdHocSubProcessTool.MAX_RESULT_BLOCK_CHARS / AdHocSubProcessTool.MAX_RESULT_VALUE_CHARS) + 2;

    static String names() {
      StringBuilder names = new StringBuilder();
      for (int i = 0; i < COUNT; i++) {
        names.append(i == 0 ? "" : ",").append("quoted").append(i);
      }
      return names.toString();
    }

    @Override
    public void execute(DelegateExecution execution) {
      char[] quotes = new char[AdHocSubProcessTool.MAX_RESULT_VALUE_CHARS];
      Arrays.fill(quotes, '"');
      for (int i = 0; i < COUNT; i++) {
        execution.setVariable("quoted" + i, new String(quotes));
      }
    }
  }

  /**
   * Writes three values of quotes, each as long as one value may be, for the state block's
   * budget: one entry takes about 6000 characters as text and twice that as written.
   */
  public static class WritesThreeQuotedTexts implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
      char[] quotes = new char[AdHocSubProcessTool.MAX_RESULT_VALUE_CHARS];
      Arrays.fill(quotes, '"');
      for (int i = 0; i < 3; i++) {
        execution.setVariable("q" + i, new String(quotes));
      }
    }
  }

  @BeforeEach
  public void setUp() {
    Agent.clear();
    ScriptedAgent.reset();
    ProcessStarterToolContext.setEngine(ENGINE);
  }

  @AfterEach
  public void tearDown() {
    ProcessStarterToolContext.clear();
    ScriptedAgent.uninstall();
    Agent.clear();
  }

  // --- models ----------------------------------------------------------------

  private static final String LONG_TEXT = WritesLongText.class.getName();
  private static final String OBJECT = WritesObject.class.getName();
  private static final String DOCUMENT = WritesDocument.class.getName();

  /**
   * An agentic scope. The agent is configuration on the element, so the children below are
   * the tools and nothing else; the parse listener parks the scope and hangs the first turn
   * on entering it.
   *
   * <p>{@code quick} finishes inside the activation call and declares its result
   * through {@code camunda:resultVariable}; {@code waits} is a user task;
   * {@code bigText} and {@code object} name their result with
   * {@code adHocResultVariables}, because a class delegate has no result variable
   * attribute to derive from.
   */
  private static String model(String processId, String extraScopeProperties) {
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + " xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + " targetNamespace='http://cibseven.org/adhoc-tool'>"
        + "<process id='" + processId + "' isExecutable='true'>"
        + "  <startEvent id='start' />"
        + "  <sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + "  <adHocSubProcess id='adHoc'>"
        + "    <extensionElements><camunda:properties>"
        + extraScopeProperties
        + "    </camunda:properties>"
        + "    <camunda:connector>"
        + "      <camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "      <camunda:inputOutput>"
        + "        <camunda:inputParameter name='message'>Do the work.</camunda:inputParameter>"
        + "      </camunda:inputOutput>"
        + "    </camunda:connector></extensionElements>"
        + "    <userTask id='waits' name='Waits for a person'>"
        + "      <documentation>Someone has to look at this.</documentation>"
        + "      <extensionElements><camunda:formData>"
        // Declares 'decision' and nothing else. A person completing the task can set
        // any variable they like, which is what aDeclaringActivityReportsOnlyWhatItDeclares
        // is about.
        + "        <camunda:formField id='decision' label='Decision' type='string' />"
        + "      </camunda:formData></extensionElements>"
        + "    </userTask>"
        + "    <serviceTask id='quick' name='Quick'"
        + "        camunda:expression='${200}' camunda:resultVariable='amount' />"
        + "    <serviceTask id='bigText' name='Big text' camunda:class='" + LONG_TEXT + "'>"
        + "      <extensionElements><camunda:properties>"
        + "        <camunda:property name='adHocResultVariables' value='report' />"
        + "      </camunda:properties></extensionElements>"
        + "    </serviceTask>"
        + "    <serviceTask id='gated' name='Gated' camunda:expression='${1}'>"
        + "      <extensionElements><camunda:properties>"
        + "        <camunda:property name='adHocBlockedWhileOthersRun' value='true' />"
        + "      </camunda:properties></extensionElements>"
        + "    </serviceTask>"
        + "    <serviceTask id='asyncChild' name='Async child' camunda:asyncBefore='true'"
        + "        camunda:expression='${1}' camunda:resultVariable='asyncDone' />"
        + "    <userTask id='multi' name='Multi'>"
        + "      <multiInstanceLoopCharacteristics isSequential='false'>"
        + "        <loopCardinality>3</loopCardinality>"
        + "      </multiInstanceLoopCharacteristics>"
        + "    </userTask>"
        + "    <userTask id='chatty' name='" + longText(200) + "'>"
        + "      <documentation>" + longText(1200) + "</documentation>"
        + "    </userTask>"
        + "    <serviceTask id='bigBlock' name='Big block' camunda:class='"
        + WritesManyLongTexts.class.getName() + "'>"
        + "      <extensionElements><camunda:properties>"
        + "        <camunda:property name='adHocResultVariables' value='"
        + WritesManyLongTexts.names() + "' />"
        + "      </camunda:properties></extensionElements>"
        + "    </serviceTask>"
        + "    <serviceTask id='object' name='Object' camunda:class='" + OBJECT + "'>"
        + "      <extensionElements><camunda:properties>"
        + "        <camunda:property name='adHocResultVariables' value='payload' />"
        + "      </camunda:properties></extensionElements>"
        + "    </serviceTask>"
        + "    <serviceTask id='document' name='Document' camunda:class='" + DOCUMENT + "'>"
        + "      <extensionElements><camunda:properties>"
        + "        <camunda:property name='adHocResultVariables' value='" + WritesDocument.NAMES + "' />"
        + "      </camunda:properties></extensionElements>"
        + "    </serviceTask>"
        + "    <serviceTask id='documentLater' name='Document later' camunda:asyncBefore='true'"
        + "        camunda:class='" + DOCUMENT + "'>"
        + "      <extensionElements><camunda:properties>"
        + "        <camunda:property name='adHocResultVariables' value='" + WritesDocument.NAMES + "' />"
        + "      </camunda:properties></extensionElements>"
        + "    </serviceTask>"
        + "    <serviceTask id='quotedLater' name='Quoted later' camunda:asyncBefore='true'"
        + "        camunda:class='" + WritesThreeQuotedTexts.class.getName() + "'>"
        + "      <extensionElements><camunda:properties>"
        + "        <camunda:property name='adHocResultVariables' value='q0,q1,q2' />"
        + "      </camunda:properties></extensionElements>"
        + "    </serviceTask>"
        + "    <serviceTask id='quotedBlock' name='Quoted block' camunda:class='"
        + WritesManyQuotedTexts.class.getName() + "'>"
        + "      <extensionElements><camunda:properties>"
        + "        <camunda:property name='adHocResultVariables' value='"
        + WritesManyQuotedTexts.names() + "' />"
        + "      </camunda:properties></extensionElements>"
        + "    </serviceTask>"
        + "  </adHocSubProcess>"
        + "  <sequenceFlow id='f2' sourceRef='adHoc' targetRef='end' />"
        + "  <endEvent id='end' />"
        + "</process></definitions>";
  }

  /**
   * A service task outside any ad hoc sub process, for the refusal that says so.
   *
   * <p>The agent itself is never modelled this way any more, but nothing stops someone
   * naming {@link AdHocSubProcessTool} among the tool classes of an ordinary agent task,
   * and that mistake has to produce a sentence rather than a silent no-op.
   */
  private static String modelWithoutScope() {
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + " xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + " targetNamespace='http://cibseven.org/adhoc-tool'>"
        + "<process id='noScope' isExecutable='true'>"
        + "  <startEvent id='start' />"
        + "  <sequenceFlow id='f1' sourceRef='start' targetRef='agent' />"
        + "  <serviceTask id='agent' name='Agent' camunda:class='"
        + CallsTheToolOutsideAScope.class.getName() + "' />"
        + "  <sequenceFlow id='f2' sourceRef='agent' targetRef='end' />"
        + "  <endEvent id='end' />"
        + "</process></definitions>";
  }

  /** Calls the listing from a plain service task and records the refusal. */
  public static class CallsTheToolOutsideAScope implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
      try {
        Agent.RESULTS.add(new AdHocSubProcessTool().turnReport());
      } catch (RuntimeException e) {
        Agent.FAILURES.add(e);
      }
    }
  }

  // --- helpers ---------------------------------------------------------------

  /** Deploys, starts, and takes the first turn. */
  private ProcessInstance start(String processId, String extraScopeProperties, Turn... script) {
    ProcessInstance instance = startWithoutTurn(processId, extraScopeProperties, script);
    runPendingTurn(instance);
    return instance;
  }

  private ProcessInstance start(String processId, Turn... script) {
    return start(processId, "", script);
  }

  /** Filler for the over-long name and documentation of the 'chatty' child. */
  private static String longText(int length) {
    StringBuilder text = new StringBuilder(length);
    while (text.length() < length) {
      text.append("lorem ipsum ");
    }
    return text.substring(0, length);
  }

  /**
   * Same, but leaves the first turn waiting as a job.
   *
   * <p>For the tests that are about the job itself — what it does when it fails, how often
   * it is retried — and for those that have to look at the instance before the agent has
   * seen it.
   */
  private ProcessInstance startWithoutTurn(String processId, Turn... script) {
    return startWithoutTurn(processId, "", script);
  }

  private ProcessInstance startWithoutTurn(String processId, String extraScopeProperties,
                                           Turn... script) {
    ScriptedAgent.Turn[] turns = new ScriptedAgent.Turn[script.length];
    for (int i = 0; i < script.length; i++) {
      turns[i] = agent(script[i]);
    }
    ScriptedAgent.install(turns);
    ENGINE.getRepositoryService().createDeployment()
        .addString(processId + ".bpmn20.xml", model(processId, extraScopeProperties))
        .deploy();
    return ENGINE.getRuntimeService().startProcessInstanceByKey(processId);
  }

  /** Runs the pending turn job, which is one turn. */
  private void runPendingTurn(ProcessInstance instance) {
    List<Job> jobs = ENGINE.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(jobs).as("a turn waiting as a job").hasSize(1);
    ENGINE.getManagementService().executeJob(jobs.get(0).getId());
  }

  /**
   * Runs the turn job on the scope execution itself, leaving any other job -- a queued
   * {@code asyncBefore} child's own, in particular -- untouched. {@link #runPendingTurn}
   * cannot be used here: it demands exactly one job in the whole instance, and the turn
   * job coexists with such a child's own job between the child's activation and its own
   * execution, independent of the turn.
   */
  private void runPendingAgenticTurn(ProcessInstance instance) {
    String scopeExecutionId = ENGINE.getRuntimeService().createExecutionQuery()
        .processInstanceId(instance.getId()).activityId("adHoc").singleResult().getId();
    List<Job> jobs = ENGINE.getManagementService().createJobQuery()
        .executionId(scopeExecutionId).list();
    assertThat(jobs).as("a turn waiting as a job on the scope execution").hasSize(1);
    ENGINE.getManagementService().executeJob(jobs.get(0).getId());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> result(int index) {
    assertThat(Agent.RESULTS.size()).as("turns recorded").isGreaterThan(index);
    return (Map<String, Object>) Agent.RESULTS.get(index);
  }

  /**
   * A tool result's list value as {@code List<Object>}. The raw value is a
   * wildcard collection, and AssertJ's varargs assertions cannot be applied to
   * one.
   */
  private static List<Object> list(Object raw) {
    return new ArrayList<Object>((Collection<?>) raw);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> activities(Map<String, Object> listing) {
    return (List<Map<String, Object>>) listing.get("activities");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> finished(Map<String, Object> listing) {
    return (List<Map<String, Object>>) listing.get("finishedSinceLastTurn");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> results(Map<String, Object> block) {
    return (Map<String, Object>) block.get("results");
  }

  private static List<String> ids(List<Map<String, Object>> items) {
    List<String> ids = new ArrayList<>();
    for (Map<String, Object> item : items) {
      ids.add(String.valueOf(item.get("id")));
    }
    return ids;
  }

  private Task task(ProcessInstance instance, String key) {
    return ENGINE.getTaskService().createTaskQuery()
        .processInstanceId(instance.getId()).taskDefinitionKey(key).singleResult();
  }

  private boolean scopeStillThere(ProcessInstance instance) {
    return ENGINE.getRuntimeService().createExecutionQuery()
        .processInstanceId(instance.getId()).activityId("adHoc").count() > 0
        || ENGINE.getRuntimeService().createProcessInstanceQuery()
            .processInstanceId(instance.getId()).count() > 0;
  }

  // --- listAvailableActivities ----------------------------------------------

  @Test
  public void theReportCarriesNoCatalogue() {
    start("listing", tool -> tool.turnReport());

    Map<String, Object> listing = result(0);
    assertThat(listing.get("adHocActivityId")).isEqualTo("adHoc");
    // The activities are the tools themselves now; repeating them in the report
    // would be the same model data twice in one prompt.
    assertThat(listing).doesNotContainKey("activities");
    assertThat(listing).containsKeys("finishedSinceLastTurn", "toolResultsSoFar", "stillRunning",
        "turn");
  }

  @Test
  public void theListingCountsTheTurnAndReportsTheLimit() {
    start("counts", tool -> tool.turnReport());

    Map<String, Object> listing = result(0);
    assertThat(listing.get("turn")).isEqualTo(1);
    assertThat(listing.get("maxTurns")).isEqualTo(AdHocSubProcessTool.DEFAULT_MAX_TURNS);
  }

  /**
   * With nothing running, ending the turn without starting something that waits
   * and without completing the scope ends the scope: a further turn is only ever
   * scheduled by a child ending, so there would be nothing to bring one. The listing
   * says so rather than leaving the model to work it out.
   */
  @Test
  public void theListingWarnsWhenNothingItStartedIsRunning() {
    start("warns", tool -> tool.turnReport());

    assertThat(result(0)).containsKey("note");
    assertThat(String.valueOf(result(0).get("note")))
        .contains("the ad hoc sub process ends here");
  }

  @Test
  public void theListingReportsWhatIsStillRunning() {
    start("running", tool -> {
      tool.startActivity("waits", Collections.<String, Object>emptyMap());
      return tool.turnReport();
    });

    Map<String, Object> listing = result(0);
    assertThat(list(listing.get("stillRunning"))).containsExactly("waits");
    // The note itself is always there — it carries the statement that names and
    // documentation are model data. What must not appear while something is running
    // is the idle warning.
    assertThat(String.valueOf(listing.get("note")))
        .doesNotContain("nothing will wake it");
  }

  // --- startActivity ---------------------------------------------------------

  @Test
  public void startingAChildThatWaitsReportsWaitingAndNoValues() {
    start("waiting", tool -> tool.startActivity("waits", Collections.<String, Object>emptyMap()));

    Map<String, Object> started = result(0);
    assertThat(started.get("activityId")).isEqualTo("waits");
    assertThat(started.get("activityInstanceId")).isNotNull();
    assertThat(started.get("status")).isEqualTo("waiting");
    assertThat(results(started)).isEmpty();
    assertThat(String.valueOf(started.get("resultsNote"))).contains("another turn");
  }

  /**
   * The case that made this method report values at all. A child that runs without
   * waiting has already finished inside the activation call, and this is the only
   * turn in which the agent can see what it wrote: a child that ends inside the turn
   * that started it schedules no further one.
   */
  @Test
  public void startingAChildThatFinishesReportsItsValuesImmediately() {
    start("finishes", tool -> tool.startActivity("quick", Collections.<String, Object>emptyMap()));

    Map<String, Object> started = result(0);
    assertThat(started.get("status")).isEqualTo("finished");
    assertThat(started.get("resultsFrom")).isEqualTo("model declaration");
    // Long, not Integer: a JUEL expression yields the widest numeric type, so
    // ${200} is 200L. What matters here is that the value arrives at all and is
    // passed through as a primitive rather than described.
    assertThat(results(started)).containsEntry("amount", Long.valueOf(200));
  }

  @Test
  public void aVariablePassedToTheChildIsSeenByIt() {
    start("perActivity", tool -> tool.startActivity("waits",
        Collections.<String, Object>singletonMap("case", "invoice-7")));

    ProcessInstance instance = ENGINE.getRuntimeService().createProcessInstanceQuery()
        .processDefinitionKey("perActivity").singleResult();
    Task waiting = task(instance, "waits");
    assertThat(ENGINE.getTaskService().getVariable(waiting.getId(), "case"))
        .isEqualTo("invoice-7");
  }

  @Test
  public void aLongValueIsTruncatedAndSaysSo() {
    start("longValue",
        tool -> tool.startActivity("bigText", Collections.<String, Object>emptyMap()));

    String report = String.valueOf(results(result(0)).get("report"));
    assertThat(report.length()).isLessThan(WritesLongText.LENGTH);
    assertThat(report).contains("(truncated, " + WritesLongText.LENGTH + " characters total)");
  }

  /**
   * The per-turn budget cuts the block off and says so.
   *
   * <p>The per-value cap is not enough on its own: an activity declaring many long
   * variables stays under it for each one and still sends an unbounded prompt. This
   * is the second limit, and until now no test touched it.
   */
  @Test
  public void tooManyValuesInOneTurnAreCutOffWithANote() {
    start("blockBudget",
        tool -> tool.startActivity("bigBlock", Collections.<String, Object>emptyMap()));

    assertThat(Agent.FAILURES).isEmpty();
    Map<String, Object> answer = result(0);
    assertThat(answer.get("status")).isEqualTo("finished");

    @SuppressWarnings("unchecked")
    Map<String, Object> values = (Map<String, Object>) answer.get("results");
    assertThat(values.size())
        .as("fewer than the " + WritesManyLongTexts.COUNT + " declared values fit the budget")
        .isLessThan(WritesManyLongTexts.COUNT);
    // A lower bound as well, because "fewer than declared" alone would also hold if
    // the block collapsed after one value — which is a different defect with the
    // same symptom. What is asserted is that the budget was spent before the cut.
    assertThat(values.size()).as("the block must not collapse early").isGreaterThan(1);
    int chars = 0;
    for (Object value : values.values()) {
      chars += String.valueOf(value).length();
    }
    assertThat(chars)
        .as("most of the " + AdHocSubProcessTool.MAX_RESULT_BLOCK_CHARS + " character budget is used")
        .isGreaterThan(AdHocSubProcessTool.MAX_RESULT_BLOCK_CHARS / 2);
    assertThat(String.valueOf(answer.get("resultsNote")))
        .contains("size limit of " + AdHocSubProcessTool.MAX_RESULT_BLOCK_CHARS);
  }

  /**
   * A non-primitive value is replaced by a descriptor naming its type. Read
   * without deserializing, so a customer class absent from this classloader does
   * not fail the turn, and so a file or a serialized object never reaches the
   * prompt.
   */
  @Test
  public void aNonPrimitiveValueIsDescribedRatherThanSerialized() {
    start("objectValue",
        tool -> tool.startActivity("object", Collections.<String, Object>emptyMap()));

    Object payload = results(result(0)).get("payload");
    assertThat(String.valueOf(payload)).isEqualTo("<object value, not shown>");
    // The list's own contents, not the letters "a" and "b" — the descriptor
    // contains an 'a' in "value", so a substring check on single letters would
    // assert nothing.
    assertThat(String.valueOf(payload)).doesNotContain("[a, b]");
  }

  /**
   * A declared bytes, file or date result of a tool that ends within its call reads as a
   * placeholder named by its type (CIB7-2233). Reported from the entry the observer gathered
   * for the call; the fallback from the scope is
   * {@link #theFallbackFromTheScopeShowsAFileBytesOrDateAsAPlaceholder}.
   */
  @Test
  public void aFileBytesOrDateResultWithinTheCallIsAPlaceholder() {
    start("documentInCall",
        tool -> tool.startActivity("document", Collections.<String, Object>emptyMap()));

    assertThat(Agent.FAILURES).isEmpty();
    Map<String, Object> answer = result(0);
    assertThat(answer.get("status")).isEqualTo("finished");
    assertDocumentPlaceholders(results(answer));
    // The tool result as the model gets it: written at all, and without the document.
    assertThat(AdHocToolProvider.toJson(answer))
        .doesNotContain(Base64.getEncoder().encodeToString(Arrays.copyOf(WritesDocument.BYTES, 30)));
  }

  /** The same values from a tool that waits read the same: one function for both paths. */
  @Test
  public void aFileBytesOrDateResultOfAToolThatWaitedIsTheSamePlaceholder() {
    ProcessInstance instance = start("documentWaits",
        tool -> tool.startActivity("documentLater", Collections.<String, Object>emptyMap()),
        tool -> tool.turnReport());
    assertThat(result(0).get("status")).isEqualTo("waiting");

    // The child's own job, then the turn its end scheduled.
    runPendingTurn(instance);
    runPendingTurn(instance);

    assertThat(Agent.FAILURES).isEmpty();
    Map<String, Object> listing = result(1);
    assertThat(finished(listing)).hasSize(1);
    assertDocumentPlaceholders(results(finished(listing).get(0)));
    assertThat(AdHocToolProvider.toJson(listing))
        .doesNotContain(Base64.getEncoder().encodeToString(Arrays.copyOf(WritesDocument.BYTES, 30)));
  }

  private static void assertDocumentPlaceholders(Map<String, Object> values) {
    assertThat(values)
        .containsEntry("title", "Rechnung 7")
        .containsEntry("doc", "<bytes value, not shown>")
        .containsEntry("scan", "<file value, not shown>")
        .containsEntry("issuedOn", "<date value, not shown>");
  }

  /**
   * The fallback of a tool result: the values read from the scope when the observer gathered
   * nothing for the call (CIB7-2233). No model was found that reaches it within a call: a chain
   * that goes on to an activity that is no tool is gathered too, under the tool the agent called.
   * So it is read here directly, in a command on a parked scope, from the typed values the
   * engine stored. Read the old way, the bytes went into the tool result as Base64 and the
   * file's stream failed {@code toJson}.
   */
  @Test
  public void theFallbackFromTheScopeShowsAFileBytesOrDateAsAPlaceholder() {
    ProcessInstance instance = startWithoutTurn("fallbackRead");
    String scopeId = ENGINE.getRuntimeService().createExecutionQuery()
        .processInstanceId(instance.getId()).activityId("adHoc").singleResult().getId();
    Map<String, Object> stored = new LinkedHashMap<>();
    stored.put("title", "Rechnung 7");
    stored.put("doc", Variables.byteArrayValue(WritesDocument.BYTES));
    stored.put("scan", Variables.fileValue("rechnung-7.pdf")
        .file(WritesDocument.BYTES).mimeType("application/pdf").create());
    stored.put("issuedOn", new Date(0L));
    ENGINE.getRuntimeService().setVariablesLocal(scopeId, stored);

    Map<String, Object> read = ((ProcessEngineConfigurationImpl) ENGINE.getProcessEngineConfiguration())
        .getCommandExecutorTxRequired().execute(commandContext -> {
          ExecutionEntity scope = commandContext.getExecutionManager().findExecutionById(scopeId);
          Map<String, Object> values = new LinkedHashMap<>();
          for (String name : WritesDocument.NAMES.split(",")) {
            values.put(name, AdHocSubProcessTool.readResult(scope, name));
          }
          return values;
        });

    // First what the step depends on: the tool result can be written at all.
    String written = AdHocToolProvider.toJson(Collections.<String, Object>singletonMap("results", read));
    assertThat(written)
        .doesNotContain(Base64.getEncoder().encodeToString(Arrays.copyOf(WritesDocument.BYTES, 30)));
    assertDocumentPlaceholders(read);
  }

  /**
   * The function both paths use, with typed values straight from the engine: which types are
   * shown and how the others are named.
   */
  @Test
  public void onlyAStringANumberOrABooleanIsShown() {
    assertThat(AdHocResults.safe(Variables.byteArrayValue(new byte[] {1, 2, 3})))
        .isEqualTo("<bytes value, not shown>");
    assertThat(AdHocResults.safe(Variables.fileValue("a.pdf").file(new byte[] {1}).create()))
        .isEqualTo("<file value, not shown>");
    assertThat(AdHocResults.safe(Variables.dateValue(new Date(0L))))
        .isEqualTo("<date value, not shown>");
    assertThat(AdHocResults.safe(Variables.objectValue(new ArrayList<String>()).create()))
        .isEqualTo("<object value, not shown>");

    assertThat(AdHocResults.safe(Variables.stringValue("ja"))).isEqualTo("ja");
    assertThat(AdHocResults.safe(Variables.integerValue(7))).isEqualTo(7);
    assertThat(AdHocResults.safe(Variables.longValue(7L))).isEqualTo(7L);
    assertThat(AdHocResults.safe(Variables.doubleValue(1.5))).isEqualTo(1.5);
    assertThat(AdHocResults.safe(Variables.booleanValue(true))).isEqualTo(true);
    assertThat(AdHocResults.safe(Variables.untypedNullValue())).isNull();
    assertThat(AdHocResults.safe(null)).isNull();
  }

  /**
   * A typed value is decided by its type: the value of a file or bytes is never taken, so it is
   * not turned into a stream or an array only to be replaced.
   */
  @Test
  public void aHiddenTypeIsDecidedWithoutTakingItsValue() {
    for (ValueType type : Arrays.asList(ValueType.FILE, ValueType.BYTES, ValueType.DATE)) {
      TypedValue untouchable = new TypedValue() {
        @Override
        public Object getValue() {
          throw new AssertionError("the value of a " + type.getName() + " was taken");
        }

        @Override
        public ValueType getType() {
          return type;
        }

        @Override
        public boolean isTransient() {
          return false;
        }
      };
      assertThat(AdHocResults.safe(untouchable))
          .isEqualTo("<" + type.getName() + " value, not shown>");
    }
  }

  /**
   * The per-turn budget counts what a value takes in the tool result as written, not its
   * {@code toString} (CIB7-2233): a value of quotes takes twice its length once JSON escapes
   * them, and counting the shorter form let twice the budget through.
   */
  @Test
  public void theBudgetCountsTheValuesAsWritten() throws Exception {
    start("quotedBudget",
        tool -> tool.startActivity("quotedBlock", Collections.<String, Object>emptyMap()));

    assertThat(Agent.FAILURES).isEmpty();
    Map<String, Object> answer = result(0);
    Map<String, Object> values = results(answer);
    assertThat(values.size()).as("the budget cut the block").isLessThan(WritesManyQuotedTexts.COUNT);
    ObjectMapper json = new ObjectMapper();
    int written = 0;
    int last = 0;
    for (Object value : values.values()) {
      last = json.writeValueAsString(value).length();
      written += last;
    }
    assertThat(written - last)
        .as("what was written before the last value stays within the budget")
        .isLessThan(AdHocSubProcessTool.MAX_RESULT_BLOCK_CHARS);
    assertThat(String.valueOf(answer.get("resultsNote")))
        .contains("size limit of " + AdHocSubProcessTool.MAX_RESULT_BLOCK_CHARS);
  }

  /**
   * The state block's budget counts the entries as written too (CIB7-2233). Five performances
   * of a tool that waits, each about 6000 characters as text and twice that as written: counted
   * as text, four of them passed a budget that two fill.
   */
  @Test
  public void theStateBlockBudgetCountsTheValuesAsWritten() throws Exception {
    ProcessInstance instance = start("quotedStateBudget", tool -> {
      for (int i = 0; i < 5; i++) {
        tool.startActivity("quotedLater", Collections.<String, Object>emptyMap());
      }
      return null;
    }, tool -> tool.turnReport());
    String scopeId = ENGINE.getRuntimeService().createExecutionQuery()
        .processInstanceId(instance.getId()).activityId("adHoc").singleResult().getId();
    // Each child's own job; the turn the first end schedules waits on the scope execution.
    for (Job job : ENGINE.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).list()) {
      if (!scopeId.equals(job.getExecutionId())) {
        ENGINE.getManagementService().executeJob(job.getId());
      }
    }
    runPendingAgenticTurn(instance);

    assertThat(Agent.FAILURES).isEmpty();
    List<Map<String, Object>> finished = finished(result(1));
    ObjectMapper json = new ObjectMapper();
    int entries = 0;
    int written = 0;
    int last = 0;
    String note = null;
    for (Map<String, Object> entry : finished) {
      if (entry.containsKey("note")) {
        note = String.valueOf(entry.get("note"));
        continue;
      }
      entries++;
      last = json.writeValueAsString(entry.get("results")).length();
      written += last;
    }
    // Two: the first takes about 12000 of the 20000 as written, the second the rest and more.
    // Counted as text, four passed.
    assertThat(entries).as("the budget cut the block after the second entry").isEqualTo(2);
    assertThat(written - last)
        .as("what was written before the last entry stays within the budget")
        .isLessThan(AdHocSubProcessTool.MAX_RESULT_BLOCK_CHARS);
    assertThat(note).contains("size limit of " + AdHocSubProcessTool.MAX_RESULT_BLOCK_CHARS);
  }

  // --- the value of a child that finished in an earlier turn ----------------

  /**
   * End to end across a turn boundary: a person completes a user task with a value,
   * that end schedules the next turn, and its listing carries the value.
   *
   * <p>The value arrives because the task's form declares the field. This used to
   * work without any declaration, from the history — see
   * {@link #anActivityReportsOnlyWhatTheModelDeclares} for why that was wrong and was
   * turned around.
   *
   * <p>The next turn is a job, so it runs in its own transaction rather than inside the
   * one that completed the task — which is what makes the value readable from history.
   */
  @Test
  public void aChildThatFinishedBetweenTurnsIsReportedWithItsValues() {
    ProcessInstance instance = startWithoutTurn("history",
        tool -> tool.startActivity("waits", Collections.<String, Object>emptyMap()),
        tool -> tool.turnReport());

    runPendingTurn(instance);

    ENGINE.getTaskService().complete(task(instance, "waits").getId(),
        Collections.<String, Object>singletonMap("decision", "approved"));

    runPendingTurn(instance);

    Map<String, Object> listing = result(1);
    assertThat(finished(listing)).hasSize(1);
    Map<String, Object> block = finished(listing).get(0);
    assertThat(block.get("activityId")).isEqualTo("waits");
    assertThat(block.get("resultsFrom")).isEqualTo("model declaration");
    assertThat(results(block)).containsEntry("decision", "approved");
    assertThat(list(listing.get("stillRunning"))).isEmpty();
  }

  // --- toolResultsSoFar (CIB7-2445) -------------------------------------------

  /** No entry of finishedSinceLastTurn, for a call of soFar outside a state block. */
  private static final Set<String> NONE_ABOVE = Collections.<String>emptySet();

  /** No entry waiting for the next step, for a call of soFar outside a state block. */
  private static final int ALL_READ = Integer.MAX_VALUE;

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> soFar(Map<String, Object> listing) {
    return (List<Map<String, Object>>) listing.get("toolResultsSoFar");
  }

  /**
   * A tool called twice is two entries, oldest first, each with its own results and turn: the
   * latest value per variable would have replaced the first answer of a person with the second,
   * as in claims-01, where every answer of the customer comes back as one variable. The second
   * is new in the last block, and is shown with its results in both sections.
   */
  @Test
  public void toolResultsSoFarKeepsEveryCallOfATool() {
    ProcessInstance instance = startWithoutTurn("soFarTwice",
        tool -> tool.startActivity("waits", Collections.<String, Object>emptyMap()),
        tool -> {
          tool.turnReport();
          return tool.startActivity("waits", Collections.<String, Object>emptyMap());
        },
        tool -> tool.turnReport());

    runPendingTurn(instance);
    ENGINE.getTaskService().complete(task(instance, "waits").getId(),
        Collections.<String, Object>singletonMap("decision", "quote 480"));
    runPendingTurn(instance);
    ENGINE.getTaskService().complete(task(instance, "waits").getId(),
        Collections.<String, Object>singletonMap("decision", "somewhere around that"));
    runPendingTurn(instance);

    assertThat(Agent.FAILURES).isEmpty();
    Map<String, Object> listing = result(2);
    List<Map<String, Object>> soFar = soFar(listing);
    assertThat(soFar).hasSize(2);
    assertThat(ids2(soFar, "activityId")).containsExactly("waits", "waits");
    assertThat(results(soFar.get(0))).containsEntry("decision", "quote 480");
    assertThat(results(soFar.get(1))).containsEntry("decision", "somewhere around that");
    assertThat(ids2(soFar, "turn")).containsExactly(1, 2);
    assertThat(soFar.get(0).get("activityInstanceId"))
        .isNotEqualTo(soFar.get(1).get("activityInstanceId"));
    assertThat(finished(listing)).hasSize(1);
    assertThat(finished(listing).get(0).get("activityInstanceId"))
        .isEqualTo(soFar.get(1).get("activityInstanceId"));
    assertThat(results(finished(listing).get(0)))
        .containsEntry("decision", "somewhere around that");
    assertThat(String.valueOf(listing.get("note"))).contains(AdHocSubProcessTool.SO_FAR_NOTE);
  }

  /**
   * Building the section moves nothing: read before {@code finishedSinceLastTurn}, twice, it
   * leaves that showing what it would have, and a result a tool result already showed is in
   * the section but not reported again as finished.
   */
  @Test
  public void toolResultsSoFarLeavesTheReadPositionAlone() {
    List<Object> positions = new ArrayList<>();
    List<Object> reported = new ArrayList<>();
    ProcessInstance instance = startWithoutTurn("soFarRead",
        tool -> {
          tool.startActivity("quick", Collections.<String, Object>emptyMap());
          Map<String, Object> started =
              tool.startActivity("waits", Collections.<String, Object>emptyMap());
          // Moves the read position past 'quick', so there is one to keep.
          tool.turnReport();
          return started;
        },
        tool -> {
          ExecutionEntity scope = scopeOf(Context.getBpmnExecutionContext().getExecution());
          positions.add(AdHocAgentState.stateValue(scope, "resultsRead"));
          reported.add(AdHocAgentState.stateValue(scope, "resultsReported"));
          AdHocResults.soFar(scope, AdHocSubProcessTool.MAX_RESULT_BLOCK_CHARS, NONE_ABOVE, ALL_READ);
          AdHocResults.soFar(scope, AdHocSubProcessTool.MAX_RESULT_BLOCK_CHARS, NONE_ABOVE, ALL_READ);
          positions.add(AdHocAgentState.stateValue(scope, "resultsRead"));
          reported.add(AdHocAgentState.stateValue(scope, "resultsReported"));
          return tool.turnReport();
        });

    runPendingTurn(instance);
    ENGINE.getTaskService().complete(task(instance, "waits").getId(),
        Collections.<String, Object>singletonMap("decision", "approved"));
    runPendingTurn(instance);

    assertThat(Agent.FAILURES).isEmpty();
    Map<String, Object> listing = result(1);
    // 'quick' ended within its call, and its tool result showed it.
    assertThat(ids2(finished(listing), "activityId")).containsExactly("waits");
    assertThat(results(finished(listing).get(0))).containsEntry("decision", "approved");
    assertThat(ids2(soFar(listing), "activityId")).containsExactly("quick", "waits");
    assertThat(results(soFar(listing).get(0))).containsEntry("amount", Long.valueOf(200));
    assertThat(results(soFar(listing).get(1))).containsEntry("decision", "approved");
    assertThat(positions).as("the stored read position, before and after").hasSize(2);
    assertThat(positions.get(0)).as("a position the first step moved").isEqualTo(1);
    assertThat(positions.get(1)).isEqualTo(positions.get(0));
    assertThat(reported).as("what a tool result showed, before and after").hasSize(2);
    assertThat(reported.get(1)).isEqualTo(reported.get(0));
    // 'quick' was never pending: it carries the turn running as it ended, the one it was called in.
    assertThat(soFar(listing).get(0)).containsEntry("turn", 1);
  }

  /**
   * The turn is the one the agent called the tool in, not the one its result came back in: a
   * person who answers after two more turns still answers the question of the first.
   */
  @Test
  public void toolResultsSoFarCarriesTheTurnOfTheCall() {
    ProcessInstance instance = startWithoutTurn("soFarTurn",
        tool -> {
          tool.startActivity("waits", Collections.<String, Object>emptyMap());
          return tool.startActivity("asyncChild", Collections.<String, Object>emptyMap());
        },
        tool -> tool.turnReport(),
        tool -> tool.turnReport());

    runPendingTurn(instance);
    // The async child's own job; its end brings turn 2, while 'waits' still waits.
    runPendingTurn(instance);
    runPendingTurn(instance);
    ENGINE.getTaskService().complete(task(instance, "waits").getId(),
        Collections.<String, Object>singletonMap("decision", "approved"));
    runPendingTurn(instance);

    assertThat(Agent.FAILURES).isEmpty();
    List<Map<String, Object>> soFar = soFar(result(2));
    assertThat(ids2(soFar, "activityId")).containsExactly("asyncChild", "waits");
    assertThat(ids2(soFar, "turn")).as("both were called in turn 1").containsExactly(1, 1);
    assertThat(result(2).get("turn")).isEqualTo(3);
  }

  /**
   * Over the cap, with nothing long to cut, the middle is left out: the oldest and the newest
   * stay, and each entry left out is named with its tool, turn and the names of its results.
   * The whole entry counts against the budget, not its values only, and the first item counts
   * too.
   */
  @Test
  public void toolResultsSoFarLeavesOutTheMiddleAndNamesIt() {
    int[] budget = new int[1];
    List<List<Map<String, Object>>> whole = new ArrayList<>();
    start("soFarEmpty", tool -> {
      for (int i = 0; i < 20; i++) {
        tool.startActivity("gated", Collections.<String, Object>emptyMap());
      }
      ExecutionEntity scope = scopeOf(Context.getBpmnExecutionContext().getExecution());
      whole.add(AdHocResults.soFar(scope, AdHocSubProcessTool.MAX_RESULT_BLOCK_CHARS, NONE_ABOVE, ALL_READ));
      // One character short of all twenty as written: counted by their values only, all of
      // them would pass.
      budget[0] = AdHocToolProvider.writtenLength(whole.get(0)) - 1;
      return AdHocResults.soFar(scope, budget[0], NONE_ABOVE, ALL_READ);
    });

    assertThat(Agent.FAILURES).isEmpty();
    List<Map<String, Object>> all = whole.get(0);
    assertThat(all).hasSize(20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> soFar = (List<Map<String, Object>>) Agent.RESULTS.get(0);
    Map<String, Object> marker = soFar.get(0);
    int leftOut = (Integer) marker.get("leftOut");
    assertThat(leftOut).as("the budget does not hold all twenty").isPositive();
    List<Map<String, Object>> shown = soFar.subList(1, soFar.size());
    assertThat(shown).hasSize(20 - leftOut).isNotEmpty();
    assertThat(shown.get(0).get("activityInstanceId")).as("the oldest stays")
        .isEqualTo(all.get(0).get("activityInstanceId"));
    assertThat(shown.get(shown.size() - 1).get("activityInstanceId")).as("the newest stays")
        .isEqualTo(all.get(19).get("activityInstanceId"));
    List<Map<String, Object>> named = list2(marker.get("leftOutEntries"));
    assertThat(named).hasSize(leftOut);
    assertThat(named.get(0)).containsEntry("activityId", "gated").containsEntry("turn", 1)
        .containsKey("activityInstanceId").containsEntry("resultNames", Collections.emptyList())
        .doesNotContainKey("results");
    assertThat(String.valueOf(marker.get("note")))
        .contains("left out for the size limit of " + AdHocSubProcessTool.MAX_RESULT_BLOCK_CHARS)
        .contains("call a tool again only if you need one of these values")
        .doesNotContain("not repeated").doesNotContain("cut to");
    assertThat(AdHocToolProvider.writtenLength(soFar)).as("the first item included")
        .isLessThanOrEqualTo(budget[0]);
    // The ones left out are one run in the middle; those next to the oldest and the newest stay.
    List<Object> ids = ids2(all, "activityInstanceId");
    int first = ids.indexOf(named.get(0).get("activityInstanceId"));
    assertThat(first).isGreaterThan(1);
    assertThat(ids2(named, "activityInstanceId")).as("a run without gaps")
        .isEqualTo(ids.subList(first, first + leftOut));
    assertThat(first + leftOut).as("the one before the newest stays").isLessThan(19);
  }

  /**
   * Many entries left out are listed per tool, how many and in which turns, so the first item
   * does not grow with every one of them.
   */
  @Test
  public void toolResultsSoFarListsManyLeftOutPerTool() {
    start("soFarPerTool",
        "<camunda:property name='cibseven.agentic.maxModelCalls' value='50' />",
        tool -> {
          for (int i = 0; i < 40; i++) {
            tool.startActivity("gated", Collections.<String, Object>emptyMap());
          }
          ExecutionEntity scope = scopeOf(Context.getBpmnExecutionContext().getExecution());
          return AdHocResults.soFar(scope, 0, NONE_ABOVE, ALL_READ);
        });

    assertThat(Agent.FAILURES).isEmpty();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> soFar = (List<Map<String, Object>>) Agent.RESULTS.get(0);
    assertThat(soFar).as("no room: only the first item").hasSize(1);
    assertThat(soFar.get(0)).containsEntry("leftOut", 40).doesNotContainKey("leftOutEntries");
    List<Map<String, Object>> perTool = list2(soFar.get(0).get("leftOutPerTool"));
    assertThat(perTool).hasSize(1);
    assertThat(perTool.get(0)).containsEntry("activityId", "gated").containsEntry("count", 40)
        .containsEntry("turns", Collections.singletonList(1))
        .containsEntry("resultNames", Collections.emptyList());
    assertThat(String.valueOf(soFar.get(0).get("note"))).contains("in leftOutPerTool are left out");
  }

  /**
   * Over the cap long values are cut before an entry is left out: the facts are short, and a cut
   * value says where it was cut. Only an entry too large even with its values cut is left out,
   * and it is named like any other.
   */
  @Test
  public void toolResultsSoFarCutsLongValuesBeforeLeavingAnEntryOut() {
    List<List<Map<String, Object>>> tight = new ArrayList<>();
    List<List<Map<String, Object>>> noRoom = new ArrayList<>();
    start("soFarCut", tool -> {
      tool.startActivity("quick", Collections.<String, Object>emptyMap());
      tool.startActivity("quotedBlock", Collections.<String, Object>emptyMap());
      tool.startActivity("bigText", Collections.<String, Object>emptyMap());
      ExecutionEntity scope = scopeOf(Context.getBpmnExecutionContext().getExecution());
      tight.add(AdHocResults.soFar(scope, 3000, NONE_ABOVE, ALL_READ));
      noRoom.add(AdHocResults.soFar(scope, 0, NONE_ABOVE, ALL_READ));
      return AdHocResults.soFar(scope, AdHocSubProcessTool.MAX_RESULT_BLOCK_CHARS, NONE_ABOVE, ALL_READ);
    });

    assertThat(Agent.FAILURES).isEmpty();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> soFar = (List<Map<String, Object>>) Agent.RESULTS.get(0);
    // Twelve values of 2000 quotes take about 48000 as written, 24500 cut to 1000: cut to 500,
    // all three fit.
    assertThat(soFar).hasSize(4);
    Map<String, Object> marker = soFar.get(0);
    assertThat(marker).containsEntry("shortened", 2).containsEntry("valuesCutTo", 500)
        .doesNotContainKey("leftOut");
    int cutTo = (Integer) marker.get("valuesCutTo");
    assertThat(cutTo).isLessThan(AdHocSubProcessTool.MAX_RESULT_VALUE_CHARS);
    assertThat(String.valueOf(marker.get("note")))
        .contains("Long values of 2 entries are cut to " + cutTo + " characters");
    // 'report' was 2500 long and cut to 2000 as it was gathered: cut again, it still says 2500.
    assertThat(String.valueOf(results(soFar.get(3)).get("report")))
        .hasSizeLessThan(cutTo + 50).endsWith("(truncated, 2500 characters total)");
    assertThat(results(soFar.get(1))).as("short values stay whole")
        .containsEntry("amount", Long.valueOf(200));
    assertThat(soFar.get(2)).containsEntry("activityId", "quotedBlock");
    assertThat(String.valueOf(results(soFar.get(2)).get("quoted0")))
        .hasSizeLessThan(cutTo + 50).contains("(truncated, 2000 characters total)");
    assertThat(AdHocToolProvider.writtenLength(soFar))
        .isLessThanOrEqualTo(AdHocSubProcessTool.MAX_RESULT_BLOCK_CHARS);

    // 3000: too little for 'quotedBlock' even with every value cut to the shortest; it is named,
    // and the ones on either side of it stay.
    List<Map<String, Object>> small = tight.get(0);
    assertThat(ids2(small.subList(1, small.size()), "activityId"))
        .containsExactly("quick", "bigText");
    List<Map<String, Object>> named = list2(small.get(0).get("leftOutEntries"));
    assertThat(ids2(named, "activityId")).containsExactly("quotedBlock");
    assertThat(named.get(0).get("resultNames")).asList().contains("quoted0", "quoted1");
    assertThat(small.get(0)).as("cut and left out: the item says both")
        .containsEntry("shortened", 1).containsEntry("valuesCutTo", AdHocResults.SHORTEST_VALUE);
    assertThat(String.valueOf(small.get(0).get("note")))
        .contains("cut to " + AdHocResults.SHORTEST_VALUE + " characters")
        .contains("left out for the size limit");
    assertThat(AdHocToolProvider.writtenLength(small)).isLessThanOrEqualTo(3000);

    // No room at all: all named, nothing shown.
    assertThat(noRoom.get(0)).hasSize(1);
    assertThat(ids2(list2(noRoom.get(0).get(0).get("leftOutEntries")), "activityId"))
        .containsExactly("quick", "quotedBlock", "bigText");
  }

  /**
   * The newest and the oldest stay whatever their size, as long as each fits at all: the newest
   * here takes more than half the room, and the entry between them is left out instead.
   */
  @Test
  public void toolResultsSoFarKeepsTheNewestAndTheOldestOfUnevenSize() {
    start("soFarUneven", tool -> {
      tool.startActivity("quick", Collections.<String, Object>emptyMap());
      tool.startActivity("quotedBlock", Collections.<String, Object>emptyMap());
      tool.startActivity("quotedBlock", Collections.<String, Object>emptyMap());
      ExecutionEntity scope = scopeOf(Context.getBpmnExecutionContext().getExecution());
      return AdHocResults.soFar(scope, 9000, NONE_ABOVE, ALL_READ);
    });

    assertThat(Agent.FAILURES).isEmpty();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> soFar = (List<Map<String, Object>>) Agent.RESULTS.get(0);
    List<Map<String, Object>> shown = soFar.subList(1, soFar.size());
    assertThat(ids2(shown, "activityId")).containsExactly("quick", "quotedBlock");
    List<Map<String, Object>> named = list2(soFar.get(0).get("leftOutEntries"));
    assertThat(ids2(named, "activityId")).containsExactly("quotedBlock");
    assertThat(named.get(0).get("activityInstanceId")).as("the older quoted block is left out")
        .isNotEqualTo(shown.get(1).get("activityInstanceId"));
    assertThat(AdHocToolProvider.writtenLength(soFar)).isLessThanOrEqualTo(9000);
  }

  /**
   * What finishedSinceLastTurn had no room for, and says comes in the next step, is not offered
   * here as left out: the agent would be told both to wait for it and to call for it.
   */
  @Test
  public void toolResultsSoFarDoesNotListWhatWaitsForTheNextStep() throws Exception {
    ProcessInstance instance = start("soFarWaiting", tool -> {
      for (int i = 0; i < 3; i++) {
        tool.startActivity("quotedLater", Collections.<String, Object>emptyMap());
      }
      return null;
    }, tool -> tool.turnReport());
    String scopeId = ENGINE.getRuntimeService().createExecutionQuery()
        .processInstanceId(instance.getId()).activityId("adHoc").singleResult().getId();
    for (Job job : ENGINE.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).list()) {
      if (!scopeId.equals(job.getExecutionId())) {
        ENGINE.getManagementService().executeJob(job.getId());
      }
    }
    runPendingAgenticTurn(instance);

    assertThat(Agent.FAILURES).isEmpty();
    Map<String, Object> listing = result(1);
    List<Map<String, Object>> finished = finished(listing);
    // Two of about 12000 each take the cap; the third waits.
    assertThat(String.valueOf(finished.get(finished.size() - 1).get("note")))
        .contains("You will be shown them in your next step");
    List<Map<String, Object>> soFar = soFar(listing);
    assertThat(soFar).hasSize(1);
    assertThat(soFar.get(0)).containsEntry("notRepeated", 2).containsEntry("waiting", 1)
        .doesNotContainKey("leftOut").doesNotContainKey("leftOutEntries");
    assertThat(String.valueOf(soFar.get(0).get("note")))
        .contains("The tool result that finishedSinceLastTurn says is waiting is not listed here")
        .contains("it comes in a later step. Do not call a tool again for it.");
  }

  /**
   * The turn of a multi-instance tool is the one it was called in, also when its last instance
   * ends turns later.
   */
  @Test
  public void toolResultsSoFarCarriesTheTurnOfAMultiInstanceToolThatEndsLater() {
    ProcessInstance instance = startWithoutTurn("soFarMultiLater",
        tool -> {
          tool.startActivity("multi", Collections.<String, Object>emptyMap());
          return tool.startActivity("asyncChild", Collections.<String, Object>emptyMap());
        },
        tool -> tool.turnReport(),
        tool -> tool.turnReport());

    runPendingTurn(instance);
    // The async child's own job; its end brings turn 2, while the instances still wait.
    runPendingTurn(instance);
    runPendingTurn(instance);
    for (Task task : ENGINE.getTaskService().createTaskQuery()
        .processInstanceId(instance.getId()).taskDefinitionKey("multi").list()) {
      ENGINE.getTaskService().complete(task.getId());
    }
    runPendingTurn(instance);

    assertThat(Agent.FAILURES).isEmpty();
    List<Map<String, Object>> soFar = soFar(result(2));
    assertThat(ids2(soFar, "activityId")).containsExactly("asyncChild", "multi");
    assertThat(soFar.get(1)).containsEntry("multiInstance", Boolean.TRUE)
        .as("called in turn 1, ended in turn 3").containsEntry("turn", 1);
    assertThat(result(2).get("turn")).isEqualTo(3);
  }

  /**
   * A call cancelled from outside keeps how it ended when it is left out, one by one and per
   * tool: whether its work took effect is what the agent needs before it calls the tool again.
   */
  @Test
  public void toolResultsSoFarNamesHowALeftOutCallEnded() {
    List<List<Map<String, Object>>> perTool = new ArrayList<>();
    ProcessInstance instance = startWithoutTurn("soFarEnded",
        "<camunda:property name='cibseven.agentic.maxModelCalls' value='50' />",
        tool -> {
          for (int i = 0; i < 25; i++) {
            tool.startActivity("gated", Collections.<String, Object>emptyMap());
          }
          tool.startActivity("chatty", Collections.<String, Object>emptyMap());
          return tool.startActivity("waits", Collections.<String, Object>emptyMap());
        },
        tool -> {
          ExecutionEntity scope = scopeOf(Context.getBpmnExecutionContext().getExecution());
          perTool.add(AdHocResults.soFar(scope, 0, NONE_ABOVE, ALL_READ));
          return tool.turnReport();
        });

    runPendingTurn(instance);
    // 'chatty' still runs, so cancelling 'waits' does not end the scope.
    ENGINE.getRuntimeService().createProcessInstanceModification(instance.getId())
        .cancelAllForActivity("waits").execute();
    runPendingTurn(instance);

    assertThat(Agent.FAILURES).isEmpty();
    // Per tool: 26 named entries take more than 2000 characters.
    List<Map<String, Object>> grouped = list2(perTool.get(0).get(0).get("leftOutPerTool"));
    Map<String, Object> waits = grouped.get(ids2(grouped, "activityId").indexOf("waits"));
    List<Map<String, Object>> ended = list2(waits.get("endedOtherwise"));
    assertThat(ended).hasSize(1);
    assertThat(ended.get(0)).containsEntry("status", "cancelled").containsKey("activityInstanceId")
        .containsEntry("turn", 1);
    assertThat(String.valueOf(ended.get(0).get("resultsNote")))
        .contains("cancelled from outside the agent");
    assertThat(grouped.get(ids2(grouped, "activityId").indexOf("gated")))
        .doesNotContainKey("endedOtherwise");
    assertThat(String.valueOf(perTool.get(0).get(0).get("note")))
        .contains("each that did not end normally is in endedOtherwise with how it ended");
  }

  /** The same one by one, where the names fit. */
  @Test
  public void toolResultsSoFarNamesHowALeftOutCallEndedOneByOne() {
    List<List<Map<String, Object>>> soFar = new ArrayList<>();
    ProcessInstance instance = startWithoutTurn("soFarEndedOne",
        tool -> {
          tool.startActivity("chatty", Collections.<String, Object>emptyMap());
          return tool.startActivity("waits", Collections.<String, Object>emptyMap());
        },
        tool -> {
          ExecutionEntity scope = scopeOf(Context.getBpmnExecutionContext().getExecution());
          soFar.add(AdHocResults.soFar(scope, 0, NONE_ABOVE, ALL_READ));
          return tool.turnReport();
        });

    runPendingTurn(instance);
    ENGINE.getRuntimeService().createProcessInstanceModification(instance.getId())
        .cancelAllForActivity("waits").execute();
    runPendingTurn(instance);

    assertThat(Agent.FAILURES).isEmpty();
    List<Map<String, Object>> named = list2(soFar.get(0).get(0).get("leftOutEntries"));
    assertThat(ids2(named, "activityId")).containsExactly("waits");
    assertThat(named.get(0)).containsEntry("status", "cancelled");
    assertThat(String.valueOf(named.get(0).get("resultsNote")))
        .contains("cancelled from outside the agent");
  }

  /**
   * A value that only looks like one cut as it was gathered is cut as it is: a length that
   * cannot be read, or that does not fit, must not stop the step.
   */
  @Test
  public void aValueThatOnlyLooksCutIsCutAsItIs() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 600; i++) {
      text.append('x');
    }
    String huge = text + AdHocSubProcessTool.TRUNCATED_BEFORE + "99999999999"
        + AdHocSubProcessTool.TRUNCATED_AFTER;
    String small = text + AdHocSubProcessTool.TRUNCATED_BEFORE + "10"
        + AdHocSubProcessTool.TRUNCATED_AFTER;
    String real = AdHocSubProcessTool.truncate(text.toString() + text, 600);

    assertThat(AdHocResults.cut(huge, 200)).as("unreadable length: cut as it is")
        .startsWith(text.substring(0, 200))
        .endsWith(AdHocSubProcessTool.TRUNCATED_BEFORE + huge.length()
            + AdHocSubProcessTool.TRUNCATED_AFTER);
    String letters = text + AdHocSubProcessTool.TRUNCATED_BEFORE + "abc"
        + AdHocSubProcessTool.TRUNCATED_AFTER;
    assertThat(AdHocResults.cut(letters, 200)).as("no number: cut as it is")
        .endsWith(AdHocSubProcessTool.TRUNCATED_BEFORE + letters.length()
            + AdHocSubProcessTool.TRUNCATED_AFTER);
    String overlap = text + "… (truncated, characters total)";
    assertThat(AdHocResults.cut(overlap, 200)).as("no number, the two parts overlap: cut as it is")
        .startsWith(text.substring(0, 200))
        .endsWith(AdHocSubProcessTool.TRUNCATED_BEFORE + overlap.length()
            + AdHocSubProcessTool.TRUNCATED_AFTER);
    String shortCut = text.substring(0, 100) + AdHocSubProcessTool.TRUNCATED_BEFORE + "5000"
        + AdHocSubProcessTool.TRUNCATED_AFTER;
    assertThat(AdHocResults.cut(shortCut, 200)).as("cut once, short enough: kept as it is")
        .isEqualTo(shortCut);
    assertThat(AdHocResults.cut(small, 200)).as("a length shorter than the text: cut as it is")
        .endsWith(AdHocSubProcessTool.TRUNCATED_BEFORE + small.length()
            + AdHocSubProcessTool.TRUNCATED_AFTER);
    assertThat(AdHocResults.cut(real, 200)).as("cut as it was gathered: keeps the whole length")
        .endsWith(AdHocSubProcessTool.TRUNCATED_BEFORE + 1200
            + AdHocSubProcessTool.TRUNCATED_AFTER);
  }

  /** A character made of two chars is not split where a value is cut. */
  @Test
  public void truncateDoesNotSplitACharacterOfTwoChars() {
    String face = new String(Character.toChars(0x1F600));
    String text = "ab" + face + "cd";

    String cut = AdHocSubProcessTool.truncate(text, 3);

    assertThat(cut).startsWith("ab" + AdHocSubProcessTool.TRUNCATED_BEFORE)
        .endsWith(text.length() + AdHocSubProcessTool.TRUNCATED_AFTER);
    assertThat(AdHocSubProcessTool.truncate(text, 4)).startsWith("ab" + face);
  }

  /**
   * The newest that is too large even with its values cut does not hide the older ones: it is
   * named, and the newest that fits stays.
   */
  @Test
  public void toolResultsSoFarNamesANewestTooLargeAndKeepsTheOlder() {
    start("soFarNewestTooLarge", tool -> {
      tool.startActivity("quick", Collections.<String, Object>emptyMap());
      tool.startActivity("quotedBlock", Collections.<String, Object>emptyMap());
      ExecutionEntity scope = scopeOf(Context.getBpmnExecutionContext().getExecution());
      return AdHocResults.soFar(scope, 3000, NONE_ABOVE, ALL_READ);
    });

    assertThat(Agent.FAILURES).isEmpty();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> soFar = (List<Map<String, Object>>) Agent.RESULTS.get(0);
    assertThat(ids2(soFar.subList(1, soFar.size()), "activityId")).containsExactly("quick");
    assertThat(ids2(list2(soFar.get(0).get("leftOutEntries")), "activityId"))
        .containsExactly("quotedBlock");
  }

  /**
   * A result its tool result already showed is not waiting, even past where
   * finishedSinceLastTurn stopped: finishedSinceLastTurn skips it, so it would never come. It is
   * named like any left-out one instead.
   */
  @Test
  public void toolResultsSoFarDoesNotCountAReportedResultAsWaiting() throws Exception {
    ProcessInstance instance = start("soFarReportedLate", tool -> {
      for (int i = 0; i < 3; i++) {
        tool.startActivity("quotedLater", Collections.<String, Object>emptyMap());
      }
      return null;
    }, tool -> {
      // Ends within its call, after the three quoted ones: its tool result shows it.
      tool.startActivity("quick", Collections.<String, Object>emptyMap());
      return tool.turnReport();
    });
    String scopeId = ENGINE.getRuntimeService().createExecutionQuery()
        .processInstanceId(instance.getId()).activityId("adHoc").singleResult().getId();
    for (Job job : ENGINE.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).list()) {
      if (!scopeId.equals(job.getExecutionId())) {
        ENGINE.getManagementService().executeJob(job.getId());
      }
    }
    runPendingAgenticTurn(instance);

    assertThat(Agent.FAILURES).isEmpty();
    List<Map<String, Object>> soFar = soFar(result(1));
    assertThat(soFar.get(0)).containsEntry("notRepeated", 2).containsEntry("waiting", 1);
    assertThat(ids2(list2(soFar.get(0).get("leftOutEntries")), "activityId"))
        .as("named, not counted as waiting").containsExactly("quick");
  }

  /**
   * Past the cap of finishedSinceLastTurn only results a tool result already showed: they are
   * skipped, so nothing is said to be waiting, and toolResultsSoFar names them as left out.
   */
  @Test
  public void finishedSinceLastTurnSaysNothingWaitsWhenOnlyReportedResultsAreLeft() throws Exception {
    ProcessInstance instance = start("sinceOnlyReported", tool -> {
      for (int i = 0; i < 2; i++) {
        tool.startActivity("quotedLater", Collections.<String, Object>emptyMap());
      }
      return null;
    }, tool -> {
      tool.startActivity("quick", Collections.<String, Object>emptyMap());
      return tool.turnReport();
    });
    String scopeId = ENGINE.getRuntimeService().createExecutionQuery()
        .processInstanceId(instance.getId()).activityId("adHoc").singleResult().getId();
    for (Job job : ENGINE.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).list()) {
      if (!scopeId.equals(job.getExecutionId())) {
        ENGINE.getManagementService().executeJob(job.getId());
      }
    }
    runPendingAgenticTurn(instance);

    assertThat(Agent.FAILURES).isEmpty();
    Map<String, Object> listing = result(1);
    List<Map<String, Object>> finished = finished(listing);
    assertThat(ids2(finished, "activityId")).containsExactly("quotedLater", "quotedLater");
    for (Map<String, Object> entry : finished) {
      assertThat(entry).as("no note that something waits").doesNotContainKey("note");
    }
    List<Map<String, Object>> soFar = soFar(listing);
    assertThat(soFar.get(0)).containsEntry("notRepeated", 2).doesNotContainKey("waiting");
    assertThat(ids2(list2(soFar.get(0).get("leftOutEntries")), "activityId"))
        .containsExactly("quick");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> list2(Object raw) {
    return (List<Map<String, Object>>) raw;
  }

  /**
   * finishedSinceLastTurn counts its entries whole too (CIB7-2445): many results that declare
   * next to nothing are cut by the cap, though their values alone would all pass.
   */
  @Test
  public void finishedSinceLastTurnCountsWholeEntries() {
    int count = 300;
    ProcessInstance instance = start("sinceWhole",
        "<camunda:property name='cibseven.agentic.maxModelCalls' value='" + (count + 10) + "' />",
        tool -> {
          for (int i = 0; i < count; i++) {
            tool.startActivity("asyncChild", Collections.<String, Object>emptyMap());
          }
          return null;
        }, tool -> tool.turnReport());
    String scopeId = ENGINE.getRuntimeService().createExecutionQuery()
        .processInstanceId(instance.getId()).activityId("adHoc").singleResult().getId();
    for (Job job : ENGINE.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).list()) {
      if (!scopeId.equals(job.getExecutionId())) {
        ENGINE.getManagementService().executeJob(job.getId());
      }
    }
    runPendingAgenticTurn(instance);

    assertThat(Agent.FAILURES).isEmpty();
    List<Map<String, Object>> finished = finished(result(1));
    int values = 0;
    for (Map<String, Object> entry : finished) {
      if (!entry.containsKey("note")) {
        values += AdHocToolProvider.writtenLength(entry.get("results"));
      }
    }
    assertThat(values).as("their values alone would all pass")
        .isLessThan(AdHocSubProcessTool.MAX_RESULT_BLOCK_CHARS);
    assertThat(finished.size()).as("the cap cut the section").isLessThan(count);
    assertThat(String.valueOf(finished.get(finished.size() - 1).get("note")))
        .contains("size limit of " + AdHocSubProcessTool.MAX_RESULT_BLOCK_CHARS);
  }

  /** The scope execution {@code execution} runs in. */
  private static ExecutionEntity scopeOf(ExecutionEntity execution) {
    ExecutionEntity current = execution;
    while (current != null && !"adHoc".equals(current.getActivityId())) {
      current = current.getParent();
    }
    return current;
  }

  /**
   * One cap for both sections. Over it, what finishedSinceLastTurn shows in the same block is
   * not repeated first, then long values are cut, and only then is the middle left out: the
   * oldest and the newest stay.
   */
  @Test
  public void toolResultsSoFarKeepsTheOldestAndTheNewestOverTheCap() throws Exception {
    List<List<Map<String, Object>>> tight = new ArrayList<>();
    ProcessInstance instance = start("soFarCap", tool -> {
      for (int i = 0; i < 2; i++) {
        tool.startActivity("quotedLater", Collections.<String, Object>emptyMap());
      }
      return null;
    }, tool -> {
      Map<String, Object> report = tool.turnReport();
      tool.startActivity("waits", Collections.<String, Object>emptyMap());
      return report;
    }, tool -> {
      ExecutionEntity scope = scopeOf(Context.getBpmnExecutionContext().getExecution());
      tight.add(AdHocResults.soFar(scope, 2500, NONE_ABOVE, ALL_READ));
      return tool.turnReport();
    });
    String scopeId = ENGINE.getRuntimeService().createExecutionQuery()
        .processInstanceId(instance.getId()).activityId("adHoc").singleResult().getId();
    for (Job job : ENGINE.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).list()) {
      if (!scopeId.equals(job.getExecutionId())) {
        ENGINE.getManagementService().executeJob(job.getId());
      }
    }
    runPendingAgenticTurn(instance);
    ENGINE.getTaskService().complete(task(instance, "waits").getId(),
        Collections.<String, Object>singletonMap("decision", "approved"));
    runPendingAgenticTurn(instance);

    assertThat(Agent.FAILURES).isEmpty();
    // Turn 2: both quoted entries are new and finishedSinceLastTurn takes the whole cap. They
    // are not repeated: finishedSinceLastTurn shows them, nothing is lost.
    List<Map<String, Object>> crowded = soFar(result(1));
    assertThat(ids2(finished(result(1)), "activityId")).contains("quotedLater", "quotedLater");
    assertThat(crowded).hasSize(1);
    assertThat(crowded.get(0)).containsEntry("notRepeated", 2).doesNotContainKey("leftOut");
    assertThat(String.valueOf(crowded.get(0).get("note")))
        .contains("The 2 tool results finishedSinceLastTurn shows above are not repeated here");

    // Turn 3: 'waits' is new and not repeated; the two quoted entries, about 12000 each as
    // written, fit with their long values cut.
    Map<String, Object> listing = result(2);
    List<Map<String, Object>> soFar = soFar(listing);
    assertThat(soFar).hasSize(3);
    assertThat(soFar.get(0)).containsEntry("notRepeated", 1).containsEntry("shortened", 2)
        .containsEntry("valuesCutTo", 1000)
        .doesNotContainKey("leftOut");
    assertThat(ids2(soFar.subList(1, 3), "activityId"))
        .containsExactly("quotedLater", "quotedLater");
    assertThat(String.valueOf(results(soFar.get(1)).get("q0")))
        .contains("(truncated, 2000 characters total)");
    assertThat(ids2(finished(listing), "activityId")).containsExactly("waits");
    int written = AdHocToolProvider.writtenLength(finished(listing))
        + AdHocToolProvider.writtenLength(soFar);
    assertThat(written).as("both sections together, the first item included, stay within the cap")
        .isLessThanOrEqualTo(AdHocSubProcessTool.MAX_RESULT_BLOCK_CHARS);

    // 2500, nothing shown above: even cut, not all three fit. The oldest quoted entry and the
    // newest, 'waits', stay; the one between is left out and named.
    List<Map<String, Object>> small = tight.get(0);
    List<Map<String, Object>> shown = small.subList(1, small.size());
    assertThat(ids2(shown, "activityId")).containsExactly("quotedLater", "waits");
    assertThat(shown.get(0).get("activityInstanceId")).as("the older of the two quoted stays")
        .isEqualTo(soFar.get(1).get("activityInstanceId"));
    List<Map<String, Object>> named = list2(small.get(0).get("leftOutEntries"));
    assertThat(named).hasSize(1);
    assertThat(named.get(0).get("activityInstanceId")).as("the newer of the two is left out")
        .isEqualTo(soFar.get(2).get("activityInstanceId"));
    assertThat(named.get(0)).containsEntry("activityId", "quotedLater").containsEntry("turn", 1)
        .containsEntry("resultNames", Arrays.asList("q0", "q1", "q2"));
    assertThat(AdHocToolProvider.writtenLength(small)).isLessThanOrEqualTo(2500);
  }

  /**
   * What an activity declares is what the agent sees — nothing else.
   *
   * <p>A person completing a task sets whatever the form and the client send, and a
   * delegate writes whatever it likes. Deriving the report from the HISTORY meant
   * every one of those values reached the prompt: the salary next to the decision,
   * the correlation id next to the amount. That silently defeated
   * {@code adHocResultVariables}, whose whole purpose is to say what the agent may
   * see, because history was asked first and the declaration only used as a fallback.
   *
   * <p>The declaration now leads. History is no longer a source of NAMES.
   */
  @Test
  public void anActivityReportsOnlyWhatTheModelDeclares() {
    ProcessInstance instance = startWithoutTurn("declaredOnly",
        tool -> tool.startActivity("waits", Collections.<String, Object>emptyMap()),
        tool -> tool.turnReport());

    runPendingTurn(instance);

    Map<String, Object> entered = new java.util.LinkedHashMap<>();
    entered.put("decision", "approved");
    entered.put("salary", 125000);
    entered.put("ldapToken", "s3cr3t");
    ENGINE.getTaskService().complete(task(instance, "waits").getId(), entered);

    runPendingTurn(instance);

    Map<String, Object> block = finished(result(1)).get(0);
    assertThat(results(block))
        .as("the declared field is reported")
        .containsEntry("decision", "approved");
    assertThat(results(block).keySet())
        .as("nothing the model did not declare may reach the prompt")
        .containsExactly("decision");
  }

  // --- completeScope ---------------------------------------------------------

  /**
   * The refusal that keeps the agent from cancelling a task a person is working
   * on: ending the scope cancels everything inside it.
   */
  @Test
  public void endingTheScopeIsRefusedWhileSomethingItStartedIsRunning() {
    ProcessInstance instance = start("refused", tool -> {
      tool.startActivity("waits", Collections.<String, Object>emptyMap());
      return tool.completeScope();
    });

    assertThat(Agent.FAILURES).hasSize(1);
    assertThat(Agent.FAILURES.get(0)).isInstanceOf(AgentConnectorException.class);
    assertThat(Agent.FAILURES.get(0).getMessage())
        .contains("waits")
        .contains("a person may be working on");
    assertThat(task(instance, "waits")).isNotNull();
    assertThat(scopeStillThere(instance)).isTrue();
  }

  /**
   * The call records the request; the engine ends the scope once the turn is over.
   * {@code completionRequested} rather than {@code completed}, because the scope is
   * still there when the call returns and a field called "completed" would say
   * something untrue.
   */
  @Test
  public void endingTheScopeLetsTheProcessContinue() {
    ProcessInstance instance = start("ends", tool -> tool.completeScope());

    assertThat(result(0).get("completionRequested")).isEqualTo(Boolean.TRUE);
    assertThat(ENGINE.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(instance.getId()).count()).isZero();
  }

  /**
   * An entry whose child finished during this same turn does not block the scope:
   * completeScope reconciles before it refuses.
   */
  @Test
  public void endingTheScopeIsAllowedAfterASynchronousChildFinished() {
    ProcessInstance instance = start("syncThenEnd", tool -> {
      tool.startActivity("quick", Collections.<String, Object>emptyMap());
      return tool.completeScope();
    });

    assertThat(Agent.FAILURES).isEmpty();
    assertThat(ENGINE.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(instance.getId()).count()).isZero();
  }

  /**
   * A name and a documentation from the model are capped before they reach the model.
   *
   * <p>These strings are process data: a modeller, an import or a deployment supplies
   * them, and they land verbatim in the prompt. Two things follow. Their size is
   * unbounded, so one element can dominate the context and its cost — which is the
   * same argument that capped result values, only this side was overlooked. And they
   * are a place to attempt prompt injection through tool metadata, which a cap makes
   * smaller but does not close; the listing therefore also says plainly that these
   * fields are data, not instructions.
   */
  @Test
  public void aLongNameAndDocumentationAreCappedBeforeTheyReachTheModel() {
    // Without the turn: a turn that starts nothing ends the scope, and the scope
    // execution is what the provider reads the description through.
    ProcessInstance instance = startWithoutTurn("chattyModel", tool -> tool.turnReport());

    // Name and documentation reach the model as the tool's description now.
    org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity scope =
        (org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity)
            ENGINE.getRuntimeService().createExecutionQuery()
                .processInstanceId(instance.getId()).activityId("adHoc").list().get(0);
    String description = null;
    for (org.cibseven.connect.ai.agent.agentic.AdHocToolDescriptor descriptor
        : catalogOf(instance)) {
      if ("chatty".equals(descriptor.getActivityId())) {
        description = new AdHocToolProvider(scope).description(descriptor);
      }
    }
    assertThat(description).isNotNull();
    assertThat(description.length())
        .as("one element must not dominate the prompt")
        .isLessThanOrEqualTo(AdHocSubProcessTool.MAX_NAME_CHARS
            + AdHocSubProcessTool.MAX_DOCUMENTATION_CHARS + 500);
    assertThat(description).contains("truncated");

    runPendingTurn(instance);
    assertThat(String.valueOf(result(0).get("note")))
        .as("the model is told these fields are data, not instructions")
        .contains("not instructions");
  }

  /** The parse-time catalogue of the deployed scope, read the way the provider does. */
  private java.util.List<org.cibseven.connect.ai.agent.agentic.AdHocToolDescriptor>
      catalogOf(ProcessInstance instance) {
    final String definitionId = ENGINE.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(instance.getId()).singleResult().getProcessDefinitionId();
    return ((org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl)
        ENGINE.getProcessEngineConfiguration()).getCommandExecutorTxRequired().execute(
            commandContext -> commandContext.getProcessEngineConfiguration()
                .getDeploymentCache().findDeployedProcessDefinitionById(definitionId)
                .findActivity("adHoc").getProperties()
                .get(org.cibseven.connect.ai.agent.agentic.AdHocToolDescriptor.CATALOG));
  }

  // --- a multi-instance child ------------------------------------------------

  /**
   * A parallel multi-instance child is one entry in the pending list, and the scope
   * cannot end until every one of its instances has finished.
   *
   * <p>The review asked whether tracking only {@code activityInstanceIds.get(0)}
   * loses the other instances. It does not, and the reason is worth pinning: the
   * activation command returns one id per <em>requested activity</em>, not per
   * instance, and for a multi-instance child that id is the loop body. The body is
   * alive as long as any instance is, so one handle covers all of them — which is
   * exactly what the pending list needs.
   *
   * <p>If that ever changes, this test fails rather than the agent silently ending a
   * scope over unfinished work.
   */
  /**
   * A parallel multi-instance child is one entry in the pending list, and the scope
   * cannot end until every one of its instances has finished.
   *
   * <p>The review asked whether tracking only {@code activityInstanceIds.get(0)}
   * loses the other instances. It does not, and the reason is worth pinning: the
   * activation command returns one id per <em>requested activity</em>, not per
   * instance, and for a multi-instance child that id is the loop body. The body is
   * alive as long as any instance is, so one handle covers all of them — which is
   * exactly what the pending list needs.
   *
   * <p>If that ever changes, this test fails rather than the agent silently ending a
   * scope over unfinished work.
   */
  @Test
  public void aMultiInstanceChildHoldsTheScopeUntilEveryInstanceIsDone() {
    ProcessInstance instance = startWithoutTurn("multiInstance",
        tool -> tool.startActivity("multi", Collections.<String, Object>emptyMap()),
        tool -> tool.completeScope());

    runPendingTurn(instance);

    assertThat(result(0).get("status")).isEqualTo("waiting");
    List<Task> open = ENGINE.getTaskService().createTaskQuery()
        .processInstanceId(instance.getId()).taskDefinitionKey("multi").list();
    assertThat(open).as("three instances were created").hasSize(3);

    // Two of three done. The scope's child is the loop body, and that has not ended,
    // so there is no further turn yet — and above all the instances are still there.
    ENGINE.getTaskService().complete(open.get(0).getId());
    ENGINE.getTaskService().complete(open.get(1).getId());

    assertThat(ENGINE.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).count())
        .as("a partly finished loop schedules no turn").isZero();
    assertThat(ENGINE.getTaskService().createTaskQuery()
        .processInstanceId(instance.getId()).taskDefinitionKey("multi").count())
        .as("the remaining instance must still be there — this is what was cancelled before")
        .isOne();
    assertThat(ENGINE.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(instance.getId()).count()).isOne();

    // The last instance ends the body, which is the scope's child, so a turn is scheduled.
    ENGINE.getTaskService().complete(ENGINE.getTaskService().createTaskQuery()
        .processInstanceId(instance.getId()).taskDefinitionKey("multi").singleResult().getId());
    runPendingTurn(instance);

    assertThat(Agent.FAILURES).isEmpty();
    assertThat(ENGINE.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(instance.getId()).count())
        .as("the scope ends once every instance is done").isZero();
  }

  /**
   * A multi-instance tool is one entry of toolResultsSoFar and says so, and an entry that
   * declares nothing says that too: its empty results are not a tool that returned nothing.
   */
  @Test
  public void toolResultsSoFarMarksAMultiInstanceToolThatDeclaresNothing() {
    ProcessInstance instance = startWithoutTurn("soFarMulti",
        tool -> tool.startActivity("multi", Collections.<String, Object>emptyMap()),
        tool -> tool.turnReport());

    runPendingTurn(instance);
    for (Task task : ENGINE.getTaskService().createTaskQuery()
        .processInstanceId(instance.getId()).taskDefinitionKey("multi").list()) {
      ENGINE.getTaskService().complete(task.getId());
    }
    runPendingTurn(instance);

    assertThat(Agent.FAILURES).isEmpty();
    List<Map<String, Object>> soFar = soFar(result(1));
    assertThat(soFar).hasSize(1);
    assertThat(soFar.get(0)).containsEntry("activityId", "multi")
        .containsEntry("multiInstance", Boolean.TRUE)
        .containsEntry("resultsFrom", "nothing declared")
        .as("the turn it was called in, not the one it ended in").containsEntry("turn", 1);
    assertThat(results(soFar.get(0))).isEmpty();
  }

  // --- a turn that fails, and its retry --------------------------------------

  /**
   * A turn that fails after asking to end the scope leaves no trace, and the retry
   * decides again.
   *
   * <p>The completion is a recorded request, honoured at the end of the turn. That is a
   * chain — tool, state flag, end of turn, completion — and the question this pins is
   * whether the flag is part of the turn's transaction or a side channel around it. If it survived a rollback,
   * a turn that crashed after calling completeScope would leave a scope that ends
   * itself on the next child that finishes, with no agent having decided so.
   *
   * <p>It is also the honest form of the optimistic-locking worry: a lock conflict is
   * one of several ways a turn is rolled back and retried, and what matters is that
   * nothing of the failed attempt is left behind.
   */
  @Test
  public void aTurnThatFailsAfterAskingToEndLeavesNothingBehind() {
    ProcessInstance instance = startWithoutTurn("retried",
        tool -> {
          tool.completeScope();
          throw new TurnFailure("the model call died after completeScope");
        },
        tool -> tool.completeScope());

    List<Job> jobs = ENGINE.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(jobs).hasSize(1);
    String jobId = jobs.get(0).getId();

    RuntimeException failure = null;
    try {
      ENGINE.getManagementService().executeJob(jobId);
    } catch (RuntimeException e) {
      failure = e;
    }
    assertThat(failure).as("the turn must fail, or this test proves nothing").isNotNull();

    assertThat(completionRequested(instance))
        .as("the request must have rolled back with the turn")
        .isNull();
    assertThat(ENGINE.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(instance.getId()).count())
        .as("and the scope must still be there").isOne();
    assertThat(ENGINE.getManagementService().createJobQuery().jobId(jobId).singleResult()
        .getRetries()).as("one retry was spent").isEqualTo(2);

    // The retry runs the next scripted turn, which decides the same thing again.
    ENGINE.getManagementService().executeJob(jobId);

    assertThat(ENGINE.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(instance.getId()).count())
        .as("the retry ended the scope").isZero();
  }

  /** The failed attempt and its retry are one turn, not two. */
  @Test
  public void aRetriedTurnIsCountedOnce() {
    ProcessInstance instance = startWithoutTurn("retriedCount",
        tool -> {
          tool.turnReport();
          throw new TurnFailure("died mid-turn");
        },
        tool -> tool.turnReport());

    String jobId = ENGINE.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).singleResult().getId();
    try {
      ENGINE.getManagementService().executeJob(jobId);
    } catch (RuntimeException expected) {
      // the turn was meant to fail
    }
    ENGINE.getManagementService().executeJob(jobId);

    assertThat(Agent.FAILURES).isEmpty();
    assertThat(result(0).get("turn"))
        .as("the same job ran twice; that is one turn")
        .isEqualTo(1);
  }

  /** The completion flag of {@code instance}, or null when it was never recorded. */
  private Object completionRequested(ProcessInstance instance) {
    VariableInstance variable = ENGINE.getRuntimeService().createVariableInstanceQuery()
        .processInstanceIdIn(instance.getId())
        .variableName(AdHocAgentState.STATE_MARKER)
        .singleResult();
    return (variable == null) ? null : ((Map<?, ?>) variable.getValue()).get("completionRequested");
  }

  // --- the turn cap ----------------------------------------------------------

  /**
   * A turn is one job, not one tool call.
   *
   * <p>The count used to be incremented inside {@code listAvailableActivities}, so a
   * model that looked at the catalogue twice — entirely legitimate, and the tool
   * description even invites a look before deciding — spent two of its turns on one.
   * With {@code cibseven.agentic.maxTurns} at its default of ten that quietly halves the budget,
   * and how far it goes depends on the model, on tool retries and on whatever
   * middleware sits in between.
   */
  @Test
  public void severalToolCallsInOneTurnCountAsOneTurn() {
    start("oneTurn", tool -> {
      List<Object> seen = new ArrayList<>();
      seen.add(tool.turnReport().get("turn"));
      seen.add(tool.turnReport().get("turn"));
      seen.add(tool.startActivity("quick", Collections.<String, Object>emptyMap()));
      seen.add(tool.turnReport().get("turn"));
      return seen;
    });

    assertThat(Agent.FAILURES).isEmpty();
    @SuppressWarnings("unchecked")
    List<Object> seen = (List<Object>) Agent.RESULTS.get(0);
    assertThat(seen.get(0)).as("first listing of the turn").isEqualTo(1);
    assertThat(seen.get(1)).as("a second look is the same turn").isEqualTo(1);
    assertThat(seen.get(3)).as("and so is a look after starting something").isEqualTo(1);
  }

  /** The next job is the next turn. */
  @Test
  public void theNextJobIsTheNextTurn() {
    ProcessInstance instance = startWithoutTurn("twoTurns",
        tool -> tool.startActivity("waits", Collections.<String, Object>emptyMap()),
        tool -> tool.turnReport());

    runPendingTurn(instance);
    ENGINE.getTaskService().complete(task(instance, "waits").getId());
    runPendingTurn(instance);

    assertThat(Agent.FAILURES).isEmpty();
    assertThat(result(1).get("turn")).as("the turn a child's end scheduled is turn 2")
        .isEqualTo(2);
  }

  /**
   * A cap of one allows the first turn; the second ends the scope without a model call.
   *
   * <p>The runner checks the cap before the agent costs anything: a loop that plainly
   * is not converging gets a readable answer written and the scope completed, rather
   * than another model call whose refusal it may ignore.
   */
  @Test
  public void theTurnCapStopsFurtherActivities() {
    ProcessInstance instance = startWithoutTurn("capped",
        "<camunda:property name='cibseven.agentic.maxTurns' value='1' />",
        tool -> tool.startActivity("waits", Collections.<String, Object>emptyMap()),
        tool -> tool.startActivity("quick", Collections.<String, Object>emptyMap()));

    runPendingTurn(instance);
    assertThat(Agent.FAILURES).as("the first turn is within the cap").isEmpty();

    ENGINE.getTaskService().complete(task(instance, "waits").getId());
    runPendingTurn(instance);

    assertThat(ScriptedAgent.turnsTaken())
        .as("the second turn must not reach the model").isOne();
    assertThat(ENGINE.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(instance.getId()).count())
        .as("the scope was ended instead").isZero();
    assertThat(String.valueOf(ENGINE.getHistoryService()
        .createHistoricVariableInstanceQuery().processInstanceId(instance.getId())
        .variableName("agentOutput").singleResult().getValue()))
        .contains("turn limit of 1");
  }

  /**
   * The cap stops the agent from thinking further. It does not throw away work already
   * handed out.
   *
   * <p>A child of an agentic scope is a tool, and a tool can be a task a person is
   * halfway through. Ending the scope over one cancels it — which is why
   * {@code completeScope} refuses while something runs and why {@code endOfTurn} waits.
   * The cap is the third exit of a turn and has to keep the same rule.
   *
   * <p>So over the cap the request is recorded rather than acted on, and every further
   * wake-up leaves through the pending-request branch: no model call, no answer
   * rewritten. The scope ends when the last child is done.
   */
  @Test
  public void theTurnCapWaitsForWorkThatIsStillRunning() {
    theTurnCapWaitsForWorkThatIsStillRunning(false);
  }

  @Test
  public void theTotalTurnCapWaitsForWorkThatIsStillRunning() {
    theTurnCapWaitsForWorkThatIsStillRunning(true);
  }

  private String turnCapProperties(boolean total) {
    return total
        ? "<camunda:property name='cibseven.agentic.conversation' value='perActivity' />"
            + "<camunda:property name='cibseven.agentic.maxTurnsTotal' value='1' />"
        : "<camunda:property name='cibseven.agentic.maxTurns' value='1' />";
  }

  private void assertCappedCounts(ProcessInstance instance, boolean total) {
    Map<?, ?> state = (Map<?, ?>) ENGINE.getRuntimeService().createVariableInstanceQuery()
        .processInstanceIdIn(instance.getId()).variableName(AdHocAgentState.STATE_MARKER)
        .singleResult().getValue();
    assertThat(state.get("turns")).isEqualTo(2);
    assertThat(state.get("modelCalls")).isEqualTo(0);
    if (total) {
      Map<?, ?> holder = (Map<?, ?>) ENGINE.getRuntimeService().createVariableInstanceQuery()
          .processInstanceIdIn(instance.getId()).variableName(AdHocConversation.HOLDER_MARKER)
          .singleResult().getValue();
      assertThat(holder.get(AdHocConversation.TURNS_TOTAL)).isEqualTo(2);
    }
  }

  private void theTurnCapWaitsForWorkThatIsStillRunning(boolean total) {
    ProcessInstance instance = startWithoutTurn("cappedWithWork",
        turnCapProperties(total),
        tool -> {
          tool.startActivity("waits", Collections.<String, Object>emptyMap());
          tool.startActivity("multi", Collections.<String, Object>emptyMap());
          return tool.turnReport();
        });

    runPendingTurn(instance);
    assertThat(Agent.FAILURES).as("the first turn is within the cap").isEmpty();

    // The turn this schedules is over the cap, and three multi instances are still open.
    ENGINE.getTaskService().complete(task(instance, "waits").getId());
    runPendingTurn(instance);

    assertThat(ScriptedAgent.turnsTaken())
        .as("the turn over the cap must not reach the model").isOne();
    assertThat(scopeStillThere(instance))
        .as("and must not end the scope over running work").isTrue();
    assertThat(ENGINE.getTaskService().createTaskQuery()
        .processInstanceId(instance.getId()).taskDefinitionKey("multi").count())
        .as("the three open tasks are untouched").isEqualTo(3);
    assertCappedCounts(instance, total);

    String scopeId = ENGINE.getRuntimeService().createExecutionQuery()
        .processInstanceId(instance.getId()).activityId("adHoc").singleResult().getId();
    ENGINE.getRuntimeService().createAdHocSubProcessActivation(scopeId).startActivity("quick")
        .execute();
    runPendingTurn(instance);
    assertCappedCounts(instance, total);
    assertThat(ScriptedAgent.turnsTaken()).isOne();
    assertThat(writesContaining(instance, "agentOutput", "turn limit of")).isOne();

    for (Task open : ENGINE.getTaskService().createTaskQuery()
        .processInstanceId(instance.getId()).taskDefinitionKey("multi").list()) {
      ENGINE.getTaskService().complete(open.getId());
    }
    runPendingTurn(instance);

    assertThat(ScriptedAgent.turnsTaken())
        .as("still no model call: the scope was already asked to end").isOne();
    assertThat(scopeStillThere(instance))
        .as("with nothing left running the scope ends").isFalse();
    assertThat(String.valueOf(ENGINE.getHistoryService()
        .createHistoricVariableInstanceQuery().processInstanceId(instance.getId())
        .variableName("agentOutput").singleResult().getValue()))
        .contains("turn limit of 1");

    // The cap is a state of the agent, not something that happens again at every child's
    // end. After the cap there are two wake-ups: the capped turn, and one when the
    // multi-instance body ends -- the three completions above dedupe into that single
    // job, asserted a few lines up. The answer was written in exactly one of the two: the
    // request is recorded in the first, and the branch that reads it sits above both the
    // counter and the cap check, so the second never reaches either.
    assertThat(writesContaining(instance, "agentOutput", "turn limit of"))
        .as("the limit answer is written once, not once per remaining child").isOne();
  }

  /**
   * How often a variable was set to a value containing {@code marker}.
   *
   * <p>Counting every write of the variable would count the answer of the turn before the
   * cap as well, which is a normal one. What must not repeat is the cap's own message.
   */
  private long writesContaining(ProcessInstance instance, String name, String marker) {
    long writes = 0;
    for (HistoricDetail detail : ENGINE.getHistoryService().createHistoricDetailQuery()
        .processInstanceId(instance.getId()).variableUpdates().list()) {
      HistoricVariableUpdate update = (HistoricVariableUpdate) detail;
      if (name.equals(update.getVariableName())
          && String.valueOf(update.getValue()).contains(marker)) {
        writes++;
      }
    }
    return writes;
  }

  /**
   * The cap waits for a queued job as it waits for a person.
   *
   * <p>A synchronous service task is never "still running" — it finishes inside the tool
   * call. What can outlive a turn besides a task is a job, so the mixed shape is the one
   * worth pinning: a user task and an {@code asyncBefore} child, ending in either order.
   *
   * <p>It also shows what the fix now leans on. The scope ends only because each child's
   * end schedules a turn. A child whose end does not — CIB7-2139, CIB7-2140 — would leave
   * the scope standing. That dependency is not new, {@code endOfTurn} and
   * {@code completeScope} have always had it; the cap used to be the one exit without it,
   * by cancelling the work instead.
   */
  @Test
  public void theTurnCapWaitsForAQueuedJobToo() {
    theTurnCapWaitsForAQueuedJobToo(false);
  }

  @Test
  public void theTotalTurnCapWaitsForAQueuedJobToo() {
    theTurnCapWaitsForAQueuedJobToo(true);
  }

  private void theTurnCapWaitsForAQueuedJobToo(boolean total) {
    ProcessInstance instance = startWithoutTurn("cappedWithJob",
        turnCapProperties(total),
        tool -> {
          tool.startActivity("waits", Collections.<String, Object>emptyMap());
          tool.startActivity("asyncChild", Collections.<String, Object>emptyMap());
          return tool.turnReport();
        });

    runPendingTurn(instance);
    assertThat(Agent.FAILURES).as("the first turn is within the cap").isEmpty();

    // The child's own job first; its end schedules the turn that is over the cap.
    runPendingTurn(instance);
    runPendingTurn(instance);

    assertThat(ScriptedAgent.turnsTaken())
        .as("the turn over the cap must not reach the model").isOne();
    assertThat(scopeStillThere(instance))
        .as("the user task is still open, so the scope stands").isTrue();

    ENGINE.getTaskService().complete(task(instance, "waits").getId());
    runPendingTurn(instance);

    assertThat(ScriptedAgent.turnsTaken()).as("and still no model call").isOne();
    assertThat(scopeStillThere(instance))
        .as("the last child is done, so the scope ends").isFalse();
    assertThat(writesContaining(instance, "agentOutput", "turn limit of"))
        .as("one limit answer, whichever order the children end in").isOne();
  }

  /**
   * The reverse order of {@link #theTurnCapWaitsForAQueuedJobToo}: the user task ends
   * first, while the {@code asyncBefore} child's own continuation job is still queued,
   * unexecuted. {@code hasChildren} has to count that queued execution as still running
   * on its own -- it carries a job but no activity instance (the suite's case 15 is the
   * same shape) -- not only once a job of its own has run at least once.
   */
  @Test
  public void theTurnCapWaitsForAQueuedJobEvenWhenTheUserTaskEndsFirst() {
    theTurnCapWaitsForAQueuedJobEvenWhenTheUserTaskEndsFirst(false);
  }

  @Test
  public void theTotalTurnCapWaitsForAQueuedJobEvenWhenTheUserTaskEndsFirst() {
    theTurnCapWaitsForAQueuedJobEvenWhenTheUserTaskEndsFirst(true);
  }

  private void theTurnCapWaitsForAQueuedJobEvenWhenTheUserTaskEndsFirst(boolean total) {
    ProcessInstance instance = startWithoutTurn("cappedWithJobReverse",
        turnCapProperties(total),
        tool -> {
          tool.startActivity("waits", Collections.<String, Object>emptyMap());
          tool.startActivity("asyncChild", Collections.<String, Object>emptyMap());
          return tool.turnReport();
        });

    runPendingTurn(instance);
    assertThat(Agent.FAILURES).as("the first turn is within the cap").isEmpty();

    // "waits" ends first; asyncChild's own job is still queued, never run. Both jobs
    // coexist now, so only the scope's own turn job may be run here.
    ENGINE.getTaskService().complete(task(instance, "waits").getId());
    runPendingAgenticTurn(instance);

    assertThat(ScriptedAgent.turnsTaken())
        .as("the turn over the cap must not reach the model").isOne();
    assertThat(scopeStillThere(instance))
        .as("the queued job is still running, so the scope stands").isTrue();

    // asyncChild's own jobs run now -- the same two steps the forward-order test takes to
    // finish it, see the comment there -- and it is the last child to end.
    runPendingTurn(instance);
    runPendingTurn(instance);

    assertThat(ScriptedAgent.turnsTaken()).as("and still no model call").isOne();
    assertThat(scopeStillThere(instance))
        .as("the last child is done, so the scope ends").isFalse();
    assertThat(writesContaining(instance, "agentOutput", "turn limit of"))
        .as("one limit answer here too").isOne();
  }

  @Test
  public void theCapCanBeRaisedFromTheModel() {
    start("raised", "<camunda:property name='cibseven.agentic.maxTurns' value='42' />",
        tool -> tool.turnReport());

    assertThat(result(0).get("maxTurns")).isEqualTo(42);
  }

  /**
   * An unparseable cap falls back to the default rather than failing the turn: the
   * value is a modelling mistake, and refusing to run would turn it into an
   * outage.
   */
  /**
   * A loop inside one turn is stopped too, and the process continues.
   *
   * <p>The turn cap cannot see this one: starting synchronous children never ends the
   * turn, so no further turn is ever counted. Case 09 of the suite is exactly that
   * loop, and once turns were counted per turn rather than per call it ran 300 model
   * calls in a distribution until LangChain4j's own brake at 100 round trips failed the
   * job and left an incident — the very thing the turn cap was made to avoid.
   */
  @Test
  public void aLoopInsideOneTurnIsStoppedAndTheProcessContinues() {
    ProcessInstance instance = start("callLoop",
        "<camunda:property name='cibseven.agentic.maxModelCalls' value='3' />",
        tool -> {
          for (int i = 0; i < 10; i++) {
            tool.startActivity("quick", Collections.<String, Object>emptyMap());
          }
          return "never reached";
        });

    assertThat(Agent.FAILURES).hasSize(1);
    assertThat(Agent.FAILURES.get(0).getMessage())
        .contains("over the limit of 3")
        .contains("Ask for fewer at once");
    assertThat(ENGINE.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(instance.getId()).count())
        .as("refused, not failed: the scope ends and the process runs on").isZero();
  }

  /** The next turn starts with a fresh call budget. */
  @Test
  public void theCallBudgetIsPerTurn() {
    ProcessInstance instance = startWithoutTurn("callBudget",
        "<camunda:property name='cibseven.agentic.maxModelCalls' value='3' />",
        tool -> tool.startActivity("waits", Collections.<String, Object>emptyMap()),
        tool -> tool.startActivity("quick", Collections.<String, Object>emptyMap()));

    runPendingTurn(instance);
    ENGINE.getTaskService().complete(task(instance, "waits").getId());
    runPendingTurn(instance);

    assertThat(Agent.FAILURES).as("the second turn starts counting again").isEmpty();
    assertThat(results(result(1))).containsKey("amount");
  }

  /**
   * A cap of zero or less is not a cap, and falls back like any unusable value.
   *
   * <p>Both parse fine, so the NumberFormatException guard never saw them. Zero refused
   * the first startActivity of the first turn — the agent looked broken rather than the
   * model wrong. Not a deployment error, for the same reason the property is not
   * validated at parse time: a wrong cap still ends the loop.
   */
  @Test
  public void aCapOfZeroOrLessFallsBackToTheDefault() {
    start("zeroCap", "<camunda:property name='cibseven.agentic.maxTurns' value='0' />",
        tool -> tool.turnReport());
    assertThat(result(0).get("maxTurns")).isEqualTo(AdHocSubProcessTool.DEFAULT_MAX_TURNS);

    Agent.clear();

    start("negativeCap", "<camunda:property name='cibseven.agentic.maxTurns' value='-10' />",
        tool -> tool.turnReport());
    assertThat(result(0).get("maxTurns")).isEqualTo(AdHocSubProcessTool.DEFAULT_MAX_TURNS);
  }

  @Test
  public void anUnparseableCapFallsBackToTheDefault() {
    start("badCap", "<camunda:property name='cibseven.agentic.maxTurns' value='soon' />",
        tool -> tool.turnReport());

    assertThat(result(0).get("maxTurns")).isEqualTo(AdHocSubProcessTool.DEFAULT_MAX_TURNS);
  }

  // --- configuration errors --------------------------------------------------

  /**
   * Outside an ad hoc sub process the tool refuses instead of doing nothing, so
   * the mistake surfaces as a message naming the activity rather than as a model
   * inventing an explanation for a silent no-op.
   */
  @Test
  public void theToolRefusesWhenTheAgentIsNotInsideAnAdHocSubProcess() {
    ENGINE.getRepositoryService().createDeployment()
        .addString("noScope.bpmn20.xml", modelWithoutScope())
        .deploy();

    ENGINE.getRuntimeService().startProcessInstanceByKey("noScope");

    assertThat(Agent.FAILURES).hasSize(1);
    assertThat(Agent.FAILURES.get(0)).isInstanceOf(AgentConnectorException.class);
    assertThat(Agent.FAILURES.get(0).getMessage())
        .contains("not inside an ad hoc sub process")
        .contains("agent");
  }

  @Test
  public void theToolRefusesWithoutAnEngineOnTheThread() {
    start("noEngine", tool -> {
      ProcessStarterToolContext.clear();
      return tool.turnReport();
    });

    assertThat(Agent.FAILURES).hasSize(1);
    assertThat(Agent.FAILURES.get(0)).isInstanceOf(AgentConnectorException.class);
    assertThat(Agent.FAILURES.get(0).getMessage()).contains("No ProcessEngine available");
  }

  @Test
  public void startingAnUnknownActivityIsRefusedByTheEngine() {
    start("unknown",
        tool -> tool.startActivity("nosuch", Collections.<String, Object>emptyMap()));

    assertThat(Agent.FAILURES).hasSize(1);
    assertThat(Agent.FAILURES.get(0).getMessage()).contains("nosuch");
  }

  // --- an asyncBefore child, the groundwork the blocking rule stands on -----

  /**
   * A queued {@code asyncBefore} child holds a marked activity back.
   *
   * <p>The premise of the blocking rule, and the reason it reads the execution
   * tree: with {@code asyncBefore} the child's work has not started yet, a job is
   * waiting to be picked up, and it has no activity instance. Asked of the
   * activity instance tree the child looks absent, and dependent work would start
   * while queued work had not run.
   */
  /**
   * What {@code startActivity} tells the model about a child whose asyncBefore job
   * is still queued.
   *
   * <p>The status is decided against the activity instance tree, where a queued
   * asyncBefore child does not appear — the same fact the blocking rule two methods
   * away reads the execution tree to avoid. If that decides "not running", the model
   * is told the work is <em>finished</em> before it has started, with whatever its
   * declared result variables happen to hold, and the child never enters the pending
   * list either, so no later turn reports it.
   *
   * <p>The existing {@link #aQueuedAsyncBeforeChildHoldsAMarkedActivityBack} starts
   * the same child but only asserts what happens next, which is how this slipped
   * through: nothing pinned the answer itself.
   */
  @Test
  public void aQueuedAsyncBeforeChildIsReportedAsWaitingNotFinished() {
    start("asyncStatus",
        tool -> tool.startActivity("asyncChild", Collections.<String, Object>emptyMap()));

    assertThat(Agent.FAILURES).isEmpty();
    Map<String, Object> answer = result(0);
    assertThat(answer.get("status"))
        .as("a queued job has not run, so the model must not be told it finished")
        .isEqualTo("waiting");
    assertThat(answer.get("results")).as("nothing can be known yet")
        .isEqualTo(Collections.emptyMap());
  }

  /**
   * Two performances of one tool are two entries, not one.
   *
   * <p>This is what the result collection is for. The report used to read the declared
   * process variables at the start of a turn, and two runs of one activity leave one
   * set of them — the agent saw the second twice and the first never. The engine now
   * gathers each performance as its child ends, while its values are still the
   * current ones, and the report is fed from that.
   */
  @Test
  public void twoPerformancesOfOneToolAreReportedSeparately() {
    start("twoRuns", tool -> {
      tool.startActivity("waits", Collections.<String, Object>emptyMap());
      tool.startActivity("waits", Collections.<String, Object>emptyMap());
      return null;
    });

    for (Task task : ENGINE.getTaskService().createTaskQuery()
        .taskDefinitionKey("waits").list()) {
      ENGINE.getTaskService().complete(task.getId(),
          Collections.<String, Object>singletonMap("decision", "for-" + task.getId()));
    }

    // The end of the second one schedules the turn that reports both.
    ScriptedAgent.install(agent(tool -> tool.turnReport()));
    runPendingTurn(instance("twoRuns"));

    List<Map<String, Object>> finished = finished(result(1));
    assertThat(finished).as("both performances, not one").hasSize(2);
    assertThat(ids2(finished, "activityId")).containsExactly("waits", "waits");
    assertThat(ids2(finished, "activityInstanceId"))
        .as("each entry names the performance it came from").doesNotHaveDuplicates();
    List<Object> decisions = new ArrayList<>();
    for (Map<String, Object> entry : finished) {
      decisions.add(results(entry).get("decision"));
    }
    assertThat(decisions).as("each carries its own value").doesNotHaveDuplicates();
  }

  /** The running instance of {@code processId}. */
  private ProcessInstance instance(String processId) {
    return ENGINE.getRuntimeService().createProcessInstanceQuery()
        .processDefinitionKey(processId).singleResult();
  }

  private static List<Object> ids2(List<Map<String, Object>> items, String key) {
    List<Object> values = new ArrayList<>();
    for (Map<String, Object> item : items) {
      values.add(item.get(key));
    }
    return values;
  }

  /**
   * The whole way for a queued asyncBefore child: waiting now, reported with its
   * value in the turn after the job ran.
   *
   * <p>Both halves matter and the first alone would have been a half fix. Reporting
   * "waiting" without tracking the child would leave the agent waiting for a result
   * that never arrives, and would let completeScope end the scope over it.
   */
  @Test
  public void aQueuedAsyncBeforeChildIsReportedWhenItsJobHasRun() {
    ProcessInstance instance = start("asyncWholeWay",
        tool -> tool.startActivity("asyncChild", Collections.<String, Object>emptyMap()),
        tool -> tool.turnReport());

    assertThat(Agent.FAILURES).isEmpty();
    assertThat(result(0).get("status")).as("the job is still queued").isEqualTo("waiting");

    // The child's own job, then the turn its end scheduled.
    runPendingTurn(instance);
    runPendingTurn(instance);

    Map<String, Object> listing = result(1);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> finished =
        (List<Map<String, Object>>) listing.get("finishedSinceLastTurn");
    // 'activityId', not 'id': a finished entry names the activity it belongs to.
    List<String> reported = new ArrayList<>();
    for (Map<String, Object> candidate : finished) {
      reported.add(String.valueOf(candidate.get("activityId")));
    }
    assertThat(reported).as("the child must be reported once its job has run")
        .contains("asyncChild");

    Map<String, Object> entry = null;
    for (Map<String, Object> candidate : finished) {
      if ("asyncChild".equals(candidate.get("activityId"))) {
        entry = candidate;
      }
    }
    assertThat(entry).isNotNull();
    @SuppressWarnings("unchecked")
    Map<String, Object> values = (Map<String, Object>) entry.get("results");
    // Long, not Integer: the expression ${1} is evaluated by JUEL.
    assertThat(values).as("its declared result variable carries the value")
        .containsEntry("asyncDone", Long.valueOf(1));
  }

  @Test
  public void aQueuedAsyncBeforeChildHoldsAMarkedActivityBack() {
    start("asyncKid", tool -> {
      tool.startActivity("asyncChild", Collections.<String, Object>emptyMap());
      return tool.startActivity("gated", Collections.<String, Object>emptyMap());
    });

    assertThat(Agent.FAILURES).hasSize(1);
    assertThat(Agent.FAILURES.get(0).getMessage())
        .contains("Cannot start 'gated'")
        .contains("asyncChild");
  }

  // --- the blocking marking -------------------------------------------------

  /**
   * A marked activity is refused while anything else in the scope is running.
   * This is the guarantee: work that depends on a decision someone else has to
   * make does not start before that decision is in.
   */
  @Test
  public void aMarkedActivityIsRefusedWhileSomethingElseRuns() {
    start("gatedRefused", tool -> {
      tool.startActivity("waits", Collections.<String, Object>emptyMap());
      return tool.startActivity("gated", Collections.<String, Object>emptyMap());
    });

    assertThat(Agent.FAILURES).hasSize(1);
    assertThat(Agent.FAILURES.get(0).getMessage())
        .contains("Cannot start 'gated'")
        .contains("waits")
        .contains("end your turn");
  }

  /**
   * And is startable when nothing else runs — including while the agent itself is
   * running, which it always is when it asks. Without excluding the caller a
   * marked activity would be blocked in every turn for ever.
   */
  @Test
  public void aMarkedActivityIsStartableWhenNothingElseRuns() {
    start("gatedAllowed",
        tool -> tool.startActivity("gated", Collections.<String, Object>emptyMap()));

    assertThat(Agent.FAILURES).isEmpty();
    assertThat(result(0).get("status")).isEqualTo("finished");
  }

  /** An unmarked activity is unaffected: the default is unchanged behaviour. */
  @Test
  public void anUnmarkedActivityIsNotAffectedByWhatIsRunning() {
    start("unmarked", tool -> {
      tool.startActivity("waits", Collections.<String, Object>emptyMap());
      return tool.startActivity("quick", Collections.<String, Object>emptyMap());
    });

    assertThat(Agent.FAILURES).isEmpty();
    assertThat(result(0).get("status")).isEqualTo("finished");
  }

  @Test
  public void theReportSaysWhichActivityIsNotStartableAndWhy() {
    start("gatedListing", tool -> {
      tool.startActivity("waits", Collections.<String, Object>emptyMap());
      return tool.turnReport();
    });

    assertThat(list(result(0).get("blockedNow"))).containsExactly("gated");
    assertThat(String.valueOf(result(0).get("blockedBecause"))).contains("waits");
  }

  /** Nothing running, nothing said — the ordinary case costs no prompt. */
  @Test
  public void theReportIsSilentAboutBlockingWhenNothingRuns() {
    start("gatedSilent", tool -> tool.turnReport());

    assertThat(result(0)).doesNotContainKey("blockedNow");
    assertThat(result(0)).doesNotContainKey("blockedBecause");
  }

  /**
   * Once the other work finishes, the marked activity becomes startable in the
   * turn the engine gives the agent for it.
   */
  @Test
  public void aMarkedActivityIsStartableInTheTurnAfterTheOtherWorkFinished() {
    ProcessInstance instance = startWithoutTurn("gatedLater",
        tool -> tool.startActivity("waits", Collections.<String, Object>emptyMap()),
        tool -> tool.startActivity("gated", Collections.<String, Object>emptyMap()));

    runPendingTurn(instance);
    ENGINE.getTaskService().complete(task(instance, "waits").getId());
    runPendingTurn(instance);

    assertThat(Agent.FAILURES).isEmpty();
    assertThat(result(1).get("status")).isEqualTo("finished");
  }

  // --- what happens after the scope has been ended --------------------------

  /**
   * Ending the scope is a request, and the turn carries on.
   *
   * <p>This is the failure a running distribution showed on every model. The agent
   * is a child of the scope, so completing it inside the turn deleted the very
   * execution the call was running on: the next tool call could not find its scope,
   * and the engine could not finish its bookkeeping for the activity — an
   * optimistic-locking failure here, a null parent in the error-propagation walk
   * there. No test saw it because every test returned from its turn immediately
   * after completing. A real conversation does not: LangChain4j asks the model
   * again after every tool result.
   */
  @Test
  public void endingTheScopeIsRecordedAndTheTurnCarriesOn() {
    start("afterComplete", tool -> {
      Map<String, Object> ended = tool.completeScope();
      assertThat(ended.get("completionRequested")).isEqualTo(Boolean.TRUE);
      // The scope is still there, so this must simply work.
      return tool.turnReport();
    });

    assertThat(Agent.FAILURES).as("nothing may fail after completeScope").isEmpty();
    assertThat(result(0)).containsKey("finishedSinceLastTurn");
  }

  /** But starting something after asking to end is refused, and says why. */
  @Test
  public void startingAnActivityAfterAskingToEndIsRefused() {
    start("afterCompleteStart", tool -> {
      tool.completeScope();
      return tool.startActivity("waits", Collections.<String, Object>emptyMap());
    });

    assertThat(Agent.FAILURES).hasSize(1);
    assertThat(Agent.FAILURES.get(0).getMessage())
        .contains("already asked")
        .doesNotContain("is not inside an ad hoc sub process");
  }

  /** And the scope really does end once the turn is over. */
  @Test
  public void theScopeEndsAfterTheTurnThatAskedForIt() {
    ProcessInstance instance = start("completesAfterTurn", tool -> tool.completeScope());

    assertThat(Agent.FAILURES).isEmpty();
    assertThat(ENGINE.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(instance.getId()).count())
        .as("the process should have continued past the scope").isZero();
  }

  /** Set before the turn runs, so a turn can act on the instance it is part of. */
  static volatile String deferredScopeExecutionId;

  /**
   * A completion request waits for work that started after it was made.
   *
   * <p>The tool refuses to start anything once completion is asked for, but a
   * person can still activate a child over the REST API — and cancelling a task
   * someone is working on is exactly what the refusal in {@code completeScope}
   * exists to prevent. The engine therefore keeps the request pending and acts on
   * it when that child ends, rather than completing over the top of it.
   *
   * <p>The first turn is left waiting so the instance exists before it runs, which is what
   * lets the turn activate a child the way an outside caller would.
   *
   * <p>This branch came in with the fix for the completion defect, so it had no
   * test until now.
   */
  @Test
  public void aPendingCompletionWaitsForWorkStartedAfterTheRequest() {
    ProcessInstance instance = startWithoutTurn("deferred", tool -> {
      Map<String, Object> ended = tool.completeScope();
      // Someone else activates a child, the way a human client would.
      ENGINE.getRuntimeService().activateAdHocSubProcessActivities(
          deferredScopeExecutionId, Collections.singletonList("waits"), null);
      return ended;
    });
    deferredScopeExecutionId = ENGINE.getRuntimeService().createExecutionQuery()
        .processInstanceId(instance.getId()).activityId("adHoc").list().get(0).getId();

    runPendingTurn(instance);

    assertThat(Agent.FAILURES).isEmpty();
    Task waiting = task(instance, "waits");
    assertThat(waiting).as("the task started after the request must survive").isNotNull();
    assertThat(ENGINE.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(instance.getId()).count())
        .as("the scope must not have completed over a live task").isEqualTo(1);

    ENGINE.getTaskService().complete(waiting.getId());
    // The end of that task schedules a turn, and that turn acts on the pending request
    // without asking the model again.
    runPendingTurn(instance);

    assertThat(ScriptedAgent.turnsTaken()).as("no second model call was needed").isOne();
    assertThat(ENGINE.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(instance.getId()).count())
        .as("once that task ends, the pending request takes effect").isZero();
  }
}
