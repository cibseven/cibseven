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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.variable.Variables;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * {@link AdHocSubProcessTool} — the three tools the model calls to see, start and
 * end the activities of the ad hoc sub process it runs in.
 *
 * <h3>Why there is no language model here</h3>
 * The tool methods are ordinary Java methods that take their context from two
 * thread locals: the BPMN execution from {@code Context.getBpmnExecutionContext()}
 * and the engine from {@link ProcessStarterToolContext}. Nothing about them needs
 * a model to decide anything, so the decision a model would make is scripted in
 * {@link AgentTask} instead. A stub model would exercise LangChain4j's tool
 * dispatch — someone else's code — rather than these methods.
 *
 * <p>What that costs: these tests do not show that the tool <em>descriptions</em>
 * lead a real model to call the tools in a sensible order. That needs an
 * end-to-end run against a model and is not covered here.
 *
 * <h3>Why the agent task is a real service task</h3>
 * {@code requireAdHocScope} walks up the execution tree from the running activity
 * and identifies the scope by its behaviour. That walk only exists inside a real
 * BPMN execution, so the tools are called from a delegate inside a real parked
 * scope rather than against a fabricated execution.
 */
public class AdHocSubProcessToolTest {

  private static final ProcessEngine ENGINE = buildInMemoryEngine();

  @Rule
  public ProcessEngineRule engineRule = new ProcessEngineRule(ENGINE);

  private static ProcessEngine buildInMemoryEngine() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setJdbcUrl("jdbc:h2:mem:adhoc-tool-test;DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    configuration.setHistoryTimeToLive("P30D");
    // 'full' is what records a variable update per activity instance, which is
    // the source describeFinished asks first.
    configuration.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
    return configuration.buildProcessEngine();
  }

  // --- the scripted agent ----------------------------------------------------

  /** What the agent does in one turn. */
  interface Turn {
    Object run(AdHocSubProcessTool tool);
  }

  /**
   * Stands in for the agent's service task, and is the scope's driver, so the
   * engine re-activates it whenever another child ends. One scripted turn per
   * invocation; an invocation past the end of the script does nothing, which is
   * how a test stops the loop.
   */
  public static class AgentTask implements JavaDelegate {

    static final List<Turn> SCRIPT = new ArrayList<>();
    static final List<Object> RESULTS = new ArrayList<>();
    static final List<RuntimeException> FAILURES = new ArrayList<>();
    static int invocations;

    @Override
    public void execute(DelegateExecution execution) {
      int index = invocations++;
      if (index >= SCRIPT.size()) {
        return;
      }
      // Caught rather than propagated: several tests assert on a refusal, and a
      // propagated exception would roll the turn back and hide the state the
      // assertion is about.
      try {
        RESULTS.add(SCRIPT.get(index).run(new AdHocSubProcessTool()));
      } catch (RuntimeException e) {
        FAILURES.add(e);
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

  @Before
  public void setUp() {
    AgentTask.SCRIPT.clear();
    AgentTask.RESULTS.clear();
    AgentTask.FAILURES.clear();
    AgentTask.invocations = 0;
    ProcessStarterToolContext.setEngine(ENGINE);
  }

  @After
  public void tearDown() {
    ProcessStarterToolContext.clear();
    AgentTask.SCRIPT.clear();
    AgentTask.RESULTS.clear();
    AgentTask.FAILURES.clear();
    AgentTask.invocations = 0;
  }

  // --- models ----------------------------------------------------------------

  private static final String AGENT = AgentTask.class.getName();
  private static final String LONG_TEXT = WritesLongText.class.getName();
  private static final String OBJECT = WritesObject.class.getName();

  /**
   * A parked scope driven by the agent task.
   *
   * <p>{@code quick} finishes inside the activation call and declares its result
   * through {@code camunda:resultVariable}; {@code waits} is a user task;
   * {@code bigText} and {@code object} name their result with
   * {@code adHocResultVariables}, because a class delegate has no result variable
   * attribute to derive from.
   */
  private static String model(String processId, String extraScopeProperties) {
    return model(processId, extraScopeProperties, false);
  }

  /**
   * @param agentAsync marks the driver {@code camunda:asyncBefore}, which is the
   *     configuration production needs and the only one in which a turn sees the
   *     history of the child that woke it — see
   *     {@link #aChildThatFinishedBetweenTurnsIsReportedWithItsValuesFromHistory}.
   */
  private static String model(String processId, String extraScopeProperties,
      boolean agentAsync) {
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + " xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + " targetNamespace='http://cibseven.org/adhoc-tool'>"
        + "<process id='" + processId + "' isExecutable='true'>"
        + "  <startEvent id='start' />"
        + "  <sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + "  <adHocSubProcess id='adHoc'>"
        + "    <extensionElements><camunda:properties>"
        + "      <camunda:property name='explicitCompletionOnly' value='true' />"
        + "      <camunda:property name='adHocDriverActivity' value='agent' />"
        + "      <camunda:property name='activeActivityIds' value='agent' />"
        + extraScopeProperties
        + "    </camunda:properties></extensionElements>"
        + "    <serviceTask id='agent' name='Agent' camunda:class='" + AGENT + "'"
        + (agentAsync ? " camunda:asyncBefore='true'" : "") + " />"
        + "    <userTask id='waits' name='Waits for a person'>"
        + "      <documentation>Someone has to look at this.</documentation>"
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
        + "    <serviceTask id='gatedBadValue' name='Gated bad value' camunda:expression='${1}'>"
        + "      <extensionElements><camunda:properties>"
        + "        <camunda:property name='adHocBlockedWhileOthersRun' value='yes' />"
        + "      </camunda:properties></extensionElements>"
        + "    </serviceTask>"
        + "    <serviceTask id='asyncChild' name='Async child' camunda:asyncBefore='true'"
        + "        camunda:expression='${1}' camunda:resultVariable='asyncDone' />"
        + "    <serviceTask id='object' name='Object' camunda:class='" + OBJECT + "'>"
        + "      <extensionElements><camunda:properties>"
        + "        <camunda:property name='adHocResultVariables' value='payload' />"
        + "      </camunda:properties></extensionElements>"
        + "    </serviceTask>"
        + "  </adHocSubProcess>"
        + "  <sequenceFlow id='f2' sourceRef='adHoc' targetRef='end' />"
        + "  <endEvent id='end' />"
        + "</process></definitions>";
  }

  /** A service task that is not inside an ad hoc sub process at all. */
  private static String modelWithoutScope() {
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + " xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + " targetNamespace='http://cibseven.org/adhoc-tool'>"
        + "<process id='noScope' isExecutable='true'>"
        + "  <startEvent id='start' />"
        + "  <sequenceFlow id='f1' sourceRef='start' targetRef='agent' />"
        + "  <serviceTask id='agent' name='Agent' camunda:class='" + AGENT + "' />"
        + "  <sequenceFlow id='f2' sourceRef='agent' targetRef='end' />"
        + "  <endEvent id='end' />"
        + "</process></definitions>";
  }

  // --- helpers ---------------------------------------------------------------

  private ProcessInstance start(String processId, String extraScopeProperties, Turn... script) {
    AgentTask.SCRIPT.addAll(Arrays.asList(script));
    ENGINE.getRepositoryService().createDeployment()
        .addString(processId + ".bpmn20.xml", model(processId, extraScopeProperties))
        .deploy();
    return ENGINE.getRuntimeService().startProcessInstanceByKey(processId);
  }

  private ProcessInstance start(String processId, Turn... script) {
    return start(processId, "", script);
  }

  /** Same, with the driver marked asyncBefore so each turn runs as its own job. */
  private ProcessInstance startAsync(String processId, Turn... script) {
    AgentTask.SCRIPT.addAll(Arrays.asList(script));
    ENGINE.getRepositoryService().createDeployment()
        .addString(processId + ".bpmn20.xml", model(processId, "", true))
        .deploy();
    return ENGINE.getRuntimeService().startProcessInstanceByKey(processId);
  }

  /** Runs the driver's pending job, which is one turn. */
  private void runPendingTurn(ProcessInstance instance) {
    List<Job> jobs = ENGINE.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).list();
    assertThat(jobs).as("a turn waiting as a job").hasSize(1);
    ENGINE.getManagementService().executeJob(jobs.get(0).getId());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> result(int index) {
    assertThat(AgentTask.RESULTS.size()).as("turns recorded").isGreaterThan(index);
    return (Map<String, Object>) AgentTask.RESULTS.get(index);
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
  public void theListingNamesTheStartableChildrenWithNameAndDocumentation() {
    start("listing", tool -> tool.listAvailableActivities());

    Map<String, Object> listing = result(0);
    assertThat(listing.get("adHocActivityId")).isEqualTo("adHoc");
    assertThat(ids(activities(listing))).containsExactlyInAnyOrder(
        "agent", "waits", "quick", "bigText", "object", "gated", "gatedBadValue", "asyncChild");

    Map<String, Object> waits = null;
    for (Map<String, Object> item : activities(listing)) {
      if ("waits".equals(item.get("id"))) {
        waits = item;
      }
    }
    assertThat(waits).isNotNull();
    assertThat(waits.get("name")).isEqualTo("Waits for a person");
    assertThat(waits.get("documentation")).isEqualTo("Someone has to look at this.");
  }

  @Test
  public void theListingCountsTheTurnAndReportsTheLimit() {
    start("counts", tool -> tool.listAvailableActivities());

    Map<String, Object> listing = result(0);
    assertThat(listing.get("turn")).isEqualTo(1);
    assertThat(listing.get("maxTurns")).isEqualTo(AdHocSubProcessTool.DEFAULT_MAX_TURNS);
  }

  /**
   * With nothing running, ending the turn without starting something that waits
   * and without completing the scope leaves the instance with nothing to wake it:
   * the agent is the driver, and a driver is not re-activated by its own end. The
   * listing says so rather than leaving the model to work it out.
   */
  @Test
  public void theListingWarnsWhenNothingItStartedIsRunning() {
    start("warns", tool -> tool.listAvailableActivities());

    assertThat(result(0)).containsKey("note");
    assertThat(String.valueOf(result(0).get("note"))).contains("nothing will wake it");
  }

  @Test
  public void theListingReportsWhatIsStillRunning() {
    start("running", tool -> {
      tool.startActivity("waits", Collections.<String, Object>emptyMap());
      return tool.listAvailableActivities();
    });

    Map<String, Object> listing = result(0);
    assertThat(list(listing.get("stillRunning"))).containsExactly("waits");
    assertThat(listing).doesNotContainKey("note");
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
   * turn in which the agent can see what it wrote: the agent is the scope's driver
   * and is still running, so that child's end gives it no further turn.
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

  // --- the value of a child that finished in an earlier turn ----------------

  /**
   * The history path, end to end: a person completes a user task with a value, the
   * engine re-activates the driver, and the next turn's listing carries what that
   * task wrote — without the model declaring anything, because the history knows
   * what the activity actually wrote.
   *
   * <p>The driver is {@code asyncBefore} here, and that is not decoration. With a
   * synchronous driver the re-activated turn runs inside the transaction that
   * completed the task, and the task's variable updates are not yet queryable from
   * history there — {@code resultsFrom} comes back as "nothing determined" and the
   * agent is told nothing could be established. The async boundary commits that
   * transaction first, which is what makes the history readable.
   */
  @Test
  public void aChildThatFinishedBetweenTurnsIsReportedWithItsValuesFromHistory() {
    ProcessInstance instance = startAsync("history",
        tool -> tool.startActivity("waits", Collections.<String, Object>emptyMap()),
        tool -> tool.listAvailableActivities());

    runPendingTurn(instance);

    ENGINE.getTaskService().complete(task(instance, "waits").getId(),
        Collections.<String, Object>singletonMap("decision", "approved"));

    runPendingTurn(instance);

    Map<String, Object> listing = result(1);
    assertThat(finished(listing)).hasSize(1);
    Map<String, Object> block = finished(listing).get(0);
    assertThat(block.get("activityId")).isEqualTo("waits");
    assertThat(block.get("resultsFrom")).isEqualTo("history");
    assertThat(results(block)).containsEntry("decision", "approved");
    assertThat(list(listing.get("stillRunning"))).isEmpty();
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

    assertThat(AgentTask.FAILURES).hasSize(1);
    assertThat(AgentTask.FAILURES.get(0)).isInstanceOf(AgentConnectorException.class);
    assertThat(AgentTask.FAILURES.get(0).getMessage())
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

    assertThat(AgentTask.FAILURES).isEmpty();
    assertThat(ENGINE.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(instance.getId()).count()).isZero();
  }

  // --- the turn cap ----------------------------------------------------------

  /**
   * Two listings in one turn push the count past a cap of one, so the following
   * start is refused. Calling the listing twice is what makes this deterministic
   * without a second driver re-activation; nothing in the tool enforces one
   * listing per turn.
   */
  @Test
  public void theTurnCapStopsFurtherActivities() {
    start("capped", "<camunda:property name='adHocMaxTurns' value='1' />", tool -> {
      tool.listAvailableActivities();
      tool.listAvailableActivities();
      return tool.startActivity("waits", Collections.<String, Object>emptyMap());
    });

    assertThat(AgentTask.FAILURES).hasSize(1);
    assertThat(AgentTask.FAILURES.get(0)).isInstanceOf(AgentConnectorException.class);
    assertThat(AgentTask.FAILURES.get(0).getMessage()).contains("turn limit of 1");
  }

  @Test
  public void theCapCanBeRaisedFromTheModel() {
    start("raised", "<camunda:property name='adHocMaxTurns' value='42' />",
        tool -> tool.listAvailableActivities());

    assertThat(result(0).get("maxTurns")).isEqualTo(42);
  }

  /**
   * An unparseable cap falls back to the default rather than failing the turn: the
   * value is a modelling mistake, and refusing to run would turn it into an
   * outage.
   */
  @Test
  public void anUnparseableCapFallsBackToTheDefault() {
    start("badCap", "<camunda:property name='adHocMaxTurns' value='soon' />",
        tool -> tool.listAvailableActivities());

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
    AgentTask.SCRIPT.add(tool -> tool.listAvailableActivities());
    ENGINE.getRepositoryService().createDeployment()
        .addString("noScope.bpmn20.xml", modelWithoutScope())
        .deploy();

    ENGINE.getRuntimeService().startProcessInstanceByKey("noScope");

    assertThat(AgentTask.FAILURES).hasSize(1);
    assertThat(AgentTask.FAILURES.get(0)).isInstanceOf(AgentConnectorException.class);
    assertThat(AgentTask.FAILURES.get(0).getMessage())
        .contains("not inside an ad hoc sub process")
        .contains("agent");
  }

  @Test
  public void theToolRefusesWithoutAnEngineOnTheThread() {
    start("noEngine", tool -> {
      ProcessStarterToolContext.clear();
      return tool.listAvailableActivities();
    });

    assertThat(AgentTask.FAILURES).hasSize(1);
    assertThat(AgentTask.FAILURES.get(0)).isInstanceOf(AgentConnectorException.class);
    assertThat(AgentTask.FAILURES.get(0).getMessage()).contains("No ProcessEngine available");
  }

  @Test
  public void startingAnUnknownActivityIsRefusedByTheEngine() {
    start("unknown",
        tool -> tool.startActivity("nosuch", Collections.<String, Object>emptyMap()));

    assertThat(AgentTask.FAILURES).hasSize(1);
    assertThat(AgentTask.FAILURES.get(0).getMessage()).contains("nosuch");
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
  @Test
  public void aQueuedAsyncBeforeChildHoldsAMarkedActivityBack() {
    start("asyncKid", tool -> {
      tool.startActivity("asyncChild", Collections.<String, Object>emptyMap());
      return tool.startActivity("gated", Collections.<String, Object>emptyMap());
    });

    assertThat(AgentTask.FAILURES).hasSize(1);
    assertThat(AgentTask.FAILURES.get(0).getMessage())
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

    assertThat(AgentTask.FAILURES).hasSize(1);
    assertThat(AgentTask.FAILURES.get(0).getMessage())
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

    assertThat(AgentTask.FAILURES).isEmpty();
    assertThat(result(0).get("status")).isEqualTo("finished");
  }

  /** An unmarked activity is unaffected: the default is unchanged behaviour. */
  @Test
  public void anUnmarkedActivityIsNotAffectedByWhatIsRunning() {
    start("unmarked", tool -> {
      tool.startActivity("waits", Collections.<String, Object>emptyMap());
      return tool.startActivity("quick", Collections.<String, Object>emptyMap());
    });

    assertThat(AgentTask.FAILURES).isEmpty();
    assertThat(result(0).get("status")).isEqualTo("finished");
  }

  /**
   * A value that is neither "true" nor "false" is treated as not marked. The
   * value is a modelling mistake, and refusing to run would turn it into an
   * outage — but it does mean a typo leaves the activity unguarded.
   */
  @Test
  public void anUnparseableMarkingIsTreatedAsNotMarked() {
    start("badMarking", tool -> {
      tool.startActivity("waits", Collections.<String, Object>emptyMap());
      return tool.startActivity("gatedBadValue", Collections.<String, Object>emptyMap());
    });

    assertThat(AgentTask.FAILURES).isEmpty();
    assertThat(result(0).get("status")).isEqualTo("finished");
  }

  @Test
  public void theListingSaysWhichActivityIsNotStartableAndWhy() {
    start("gatedListing", tool -> {
      tool.startActivity("waits", Collections.<String, Object>emptyMap());
      return tool.listAvailableActivities();
    });

    Map<String, Object> gated = null;
    for (Map<String, Object> item : activities(result(0))) {
      if ("gated".equals(item.get("id"))) {
        gated = item;
      }
    }
    assertThat(gated).isNotNull();
    assertThat(gated.get("startableNow")).isEqualTo(Boolean.FALSE);
    assertThat(String.valueOf(gated.get("blockedBecause"))).contains("waits");
  }

  /** Nothing running, nothing said — the ordinary case costs no prompt. */
  @Test
  public void theListingIsSilentAboutBlockingWhenNothingRuns() {
    start("gatedSilent", tool -> tool.listAvailableActivities());

    for (Map<String, Object> item : activities(result(0))) {
      assertThat(item).doesNotContainKey("startableNow");
      assertThat(item).doesNotContainKey("blockedBecause");
    }
  }

  /**
   * Once the other work finishes, the marked activity becomes startable in the
   * turn the engine gives the agent for it.
   */
  @Test
  public void aMarkedActivityIsStartableInTheTurnAfterTheOtherWorkFinished() {
    ProcessInstance instance = startAsync("gatedLater",
        tool -> tool.startActivity("waits", Collections.<String, Object>emptyMap()),
        tool -> tool.startActivity("gated", Collections.<String, Object>emptyMap()));

    runPendingTurn(instance);
    ENGINE.getTaskService().complete(task(instance, "waits").getId());
    runPendingTurn(instance);

    assertThat(AgentTask.FAILURES).isEmpty();
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
      return tool.listAvailableActivities();
    });

    assertThat(AgentTask.FAILURES).as("nothing may fail after completeScope").isEmpty();
    assertThat(result(0)).containsKey("activities");
  }

  /** But starting something after asking to end is refused, and says why. */
  @Test
  public void startingAnActivityAfterAskingToEndIsRefused() {
    start("afterCompleteStart", tool -> {
      tool.completeScope();
      return tool.startActivity("waits", Collections.<String, Object>emptyMap());
    });

    assertThat(AgentTask.FAILURES).hasSize(1);
    assertThat(AgentTask.FAILURES.get(0).getMessage())
        .contains("already asked")
        .doesNotContain("is not inside an ad hoc sub process");
  }

  /** And the scope really does end once the turn is over. */
  @Test
  public void theScopeEndsAfterTheTurnThatAskedForIt() {
    ProcessInstance instance = start("completesAfterTurn", tool -> tool.completeScope());

    assertThat(AgentTask.FAILURES).isEmpty();
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
   * <p>The driver is asyncBefore so the turn runs as a job: the instance then
   * exists before the turn, which is what lets the turn activate a child the way
   * an outside caller would.
   *
   * <p>This branch came in with the fix for the completion defect, so it had no
   * test until now.
   */
  @Test
  public void aPendingCompletionWaitsForWorkStartedAfterTheRequest() {
    ProcessInstance instance = startAsync("deferred", tool -> {
      Map<String, Object> ended = tool.completeScope();
      // Someone else activates a child, the way a human client would.
      ENGINE.getRuntimeService().triggerAdHocActivities(
          deferredScopeExecutionId, Collections.singletonList("waits"), null);
      return ended;
    });
    deferredScopeExecutionId = ENGINE.getRuntimeService().createExecutionQuery()
        .processInstanceId(instance.getId()).activityId("adHoc").list().get(0).getId();

    runPendingTurn(instance);

    assertThat(AgentTask.FAILURES).isEmpty();
    Task waiting = task(instance, "waits");
    assertThat(waiting).as("the task started after the request must survive").isNotNull();
    assertThat(ENGINE.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(instance.getId()).count())
        .as("the scope must not have completed over a live task").isEqualTo(1);

    ENGINE.getTaskService().complete(waiting.getId());

    assertThat(ENGINE.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(instance.getId()).count())
        .as("once that task ends, the pending request takes effect").isZero();
  }
}
