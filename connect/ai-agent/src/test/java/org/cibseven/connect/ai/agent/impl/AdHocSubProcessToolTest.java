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
import org.cibseven.bpm.engine.runtime.VariableInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.junit5.ProcessEngineExtension;
import org.cibseven.bpm.engine.variable.Variables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;

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

  @RegisterExtension
  public ProcessEngineExtension engineRule = ProcessEngineExtension.builder().useProcessEngine(ENGINE).build();

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
      // assertion is about. TurnFailure is the exception, for the tests whose
      // subject IS the rollback.
      try {
        RESULTS.add(SCRIPT.get(index).run(new AdHocSubProcessTool()));
      } catch (TurnFailure e) {
        throw e;
      } catch (RuntimeException e) {
        FAILURES.add(e);
      }
    }
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

  @BeforeEach
  public void setUp() {
    AgentTask.SCRIPT.clear();
    AgentTask.RESULTS.clear();
    AgentTask.FAILURES.clear();
    AgentTask.invocations = 0;
    ProcessStarterToolContext.setEngine(ENGINE);
  }

  @AfterEach
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
        + "      <camunda:property name='activeElementsCollection' value='agent' />"
        + extraScopeProperties
        + "    </camunda:properties></extensionElements>"
        + "    <serviceTask id='agent' name='Agent' camunda:class='" + AGENT + "'"
        + (agentAsync ? " camunda:asyncBefore='true'" : "") + " />"
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
        + "    <serviceTask id='gatedBadValue' name='Gated bad value' camunda:expression='${1}'>"
        + "      <extensionElements><camunda:properties>"
        + "        <camunda:property name='adHocBlockedWhileOthersRun' value='yes' />"
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
  /** Filler for the over-long name and documentation of the 'chatty' child. */
  private static String longText(int length) {
    StringBuilder text = new StringBuilder(length);
    while (text.length() < length) {
      text.append("lorem ipsum ");
    }
    return text.substring(0, length);
  }

  private ProcessInstance startAsync(String processId, Turn... script) {
    return startAsync(processId, "", script);
  }

  private ProcessInstance startAsync(String processId, String extraScopeProperties,
                                     Turn... script) {
    AgentTask.SCRIPT.addAll(Arrays.asList(script));
    ENGINE.getRepositoryService().createDeployment()
        .addString(processId + ".bpmn20.xml", model(processId, extraScopeProperties, true))
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
    // Every child except 'agent', which is the driver: see theAgentIsNotOfferedToItself.
    assertThat(ids(activities(listing))).containsExactlyInAnyOrder(
        "waits", "quick", "bigText", "bigBlock", "object", "gated", "gatedBadValue", "asyncChild",
        "chatty", "multi");

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

    assertThat(AgentTask.FAILURES).isEmpty();
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

  // --- the value of a child that finished in an earlier turn ----------------

  /**
   * End to end across a turn boundary: a person completes a user task with a value,
   * the engine re-activates the driver, and the next turn's listing carries it.
   *
   * <p>The value arrives because the task's form declares the field. This used to
   * work without any declaration, from the history — see
   * {@link #anActivityReportsOnlyWhatTheModelDeclares} for why that was wrong and was
   * turned around.
   *
   * <p>The driver is {@code asyncBefore} here, and that is not decoration: a
   * synchronous driver would run the re-activated turn inside the transaction that
   * completed the task, which is a different timing question and covered elsewhere.
   */
  @Test
  public void aChildThatFinishedBetweenTurnsIsReportedWithItsValues() {
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
    assertThat(block.get("resultsFrom")).isEqualTo("model declaration");
    assertThat(results(block)).containsEntry("decision", "approved");
    assertThat(list(listing.get("stillRunning"))).isEmpty();
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
    ProcessInstance instance = startAsync("declaredOnly",
        tool -> tool.startActivity("waits", Collections.<String, Object>emptyMap()),
        tool -> tool.listAvailableActivities());

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
    start("chattyModel", tool -> tool.listAvailableActivities());

    Map<String, Object> listing = result(0);
    Map<String, Object> chatty = null;
    for (Map<String, Object> item : activities(listing)) {
      if ("chatty".equals(item.get("id"))) {
        chatty = item;
      }
    }
    assertThat(chatty).isNotNull();

    String documentation = String.valueOf(chatty.get("documentation"));
    assertThat(documentation.length())
        .isLessThanOrEqualTo(AdHocSubProcessTool.MAX_DOCUMENTATION_CHARS + 60);
    assertThat(documentation).contains("truncated");

    String name = String.valueOf(chatty.get("name"));
    assertThat(name.length()).isLessThanOrEqualTo(AdHocSubProcessTool.MAX_NAME_CHARS + 60);

    assertThat(String.valueOf(listing.get("note")))
        .as("the model is told these fields are data, not instructions")
        .contains("not instructions");
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
    ProcessInstance instance = startAsync("multiInstance",
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
        .as("a partly finished loop gives the driver no turn").isZero();
    assertThat(ENGINE.getTaskService().createTaskQuery()
        .processInstanceId(instance.getId()).taskDefinitionKey("multi").count())
        .as("the remaining instance must still be there — this is what was cancelled before")
        .isOne();
    assertThat(ENGINE.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(instance.getId()).count()).isOne();

    // The last instance ends the body, which is the scope's child, so the driver runs.
    ENGINE.getTaskService().complete(ENGINE.getTaskService().createTaskQuery()
        .processInstanceId(instance.getId()).taskDefinitionKey("multi").singleResult().getId());
    runPendingTurn(instance);

    assertThat(AgentTask.FAILURES).isEmpty();
    assertThat(ENGINE.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(instance.getId()).count())
        .as("the scope ends once every instance is done").isZero();
  }

  // --- a turn that fails, and its retry --------------------------------------

  /**
   * A turn that fails after asking to end the scope leaves no trace, and the retry
   * decides again.
   *
   * <p>The completion is a recorded request, honoured later by the engine when the
   * driver's execution ends. That is a long chain — tool, state flag, driver end,
   * behaviour, completion — and the question this pins is whether the flag is part of
   * the turn's transaction or a side channel around it. If it survived a rollback,
   * a turn that crashed after calling completeScope would leave a scope that ends
   * itself on the next child that finishes, with no agent having decided so.
   *
   * <p>It is also the honest form of the optimistic-locking worry: a lock conflict is
   * one of several ways a turn is rolled back and retried, and what matters is that
   * nothing of the failed attempt is left behind.
   */
  @Test
  public void aTurnThatFailsAfterAskingToEndLeavesNothingBehind() {
    ProcessInstance instance = startAsync("retried",
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
    ProcessInstance instance = startAsync("retriedCount",
        tool -> {
          tool.listAvailableActivities();
          throw new TurnFailure("died mid-turn");
        },
        tool -> tool.listAvailableActivities());

    String jobId = ENGINE.getManagementService().createJobQuery()
        .processInstanceId(instance.getId()).singleResult().getId();
    try {
      ENGINE.getManagementService().executeJob(jobId);
    } catch (RuntimeException expected) {
      // the turn was meant to fail
    }
    ENGINE.getManagementService().executeJob(jobId);

    assertThat(AgentTask.FAILURES).isEmpty();
    assertThat(result(0).get("turn"))
        .as("the same driver execution ran twice; that is one turn")
        .isEqualTo(1);
  }

  /** The completion flag of {@code instance}, or null when it was never recorded. */
  private Object completionRequested(ProcessInstance instance) {
    VariableInstance variable = ENGINE.getRuntimeService().createVariableInstanceQuery()
        .processInstanceIdIn(instance.getId())
        .variableName("adHocAgentCompletionRequested")
        .singleResult();
    return (variable == null) ? null : variable.getValue();
  }

  // --- the turn cap ----------------------------------------------------------

  /**
   * A turn is one run of the driver, not one tool call.
   *
   * <p>The count used to be incremented inside {@code listAvailableActivities}, so a
   * model that looked at the catalogue twice — entirely legitimate, and the tool
   * description even invites a look before deciding — spent two of its turns on one.
   * With {@code adHocMaxTurns} at its default of ten that quietly halves the budget,
   * and how far it goes depends on the model, on tool retries and on whatever
   * middleware sits in between.
   */
  @Test
  public void severalToolCallsInOneTurnCountAsOneTurn() {
    start("oneTurn", tool -> {
      List<Object> seen = new ArrayList<>();
      seen.add(tool.listAvailableActivities().get("turn"));
      seen.add(tool.listAvailableActivities().get("turn"));
      seen.add(tool.startActivity("quick", Collections.<String, Object>emptyMap()));
      seen.add(tool.listAvailableActivities().get("turn"));
      return seen;
    });

    assertThat(AgentTask.FAILURES).isEmpty();
    @SuppressWarnings("unchecked")
    List<Object> seen = (List<Object>) AgentTask.RESULTS.get(0);
    assertThat(seen.get(0)).as("first listing of the turn").isEqualTo(1);
    assertThat(seen.get(1)).as("a second look is the same turn").isEqualTo(1);
    assertThat(seen.get(3)).as("and so is a look after starting something").isEqualTo(1);
  }

  /** The next run of the driver is the next turn. */
  @Test
  public void thenextDriverRunIsTheNextTurn() {
    ProcessInstance instance = startAsync("twoTurns",
        tool -> tool.startActivity("waits", Collections.<String, Object>emptyMap()),
        tool -> tool.listAvailableActivities());

    runPendingTurn(instance);
    ENGINE.getTaskService().complete(task(instance, "waits").getId());
    runPendingTurn(instance);

    assertThat(AgentTask.FAILURES).isEmpty();
    assertThat(result(1).get("turn")).as("the re-activated driver is turn 2").isEqualTo(2);
  }

  /**
   * A cap of one allows the first turn and refuses the second.
   *
   * <p>Driven by two real re-activations of the driver. The previous version of this
   * test called the listing twice inside one turn to push the count along, which only
   * worked because the count was per tool call — the defect itself, written into a
   * test as a convenience.
   */
  @Test
  public void theTurnCapStopsFurtherActivities() {
    ProcessInstance instance = startAsync("capped",
        "<camunda:property name='adHocMaxTurns' value='1' />",
        tool -> tool.startActivity("waits", Collections.<String, Object>emptyMap()),
        tool -> tool.startActivity("quick", Collections.<String, Object>emptyMap()));

    runPendingTurn(instance);
    assertThat(AgentTask.FAILURES).as("the first turn is within the cap").isEmpty();

    ENGINE.getTaskService().complete(task(instance, "waits").getId());
    runPendingTurn(instance);

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
  /**
   * A loop inside one turn is stopped too, and the process continues.
   *
   * <p>The turn cap cannot see this one: starting synchronous children never ends the
   * turn, so no further turn is ever counted. Case 09 of the suite is exactly that
   * loop, and after turns moved to the driver run it ran 300 model calls in a
   * distribution until LangChain4j's own brake at 100 round trips failed the job and
   * left an incident — the very thing the turn cap was made to avoid.
   */
  @Test
  public void aLoopInsideOneTurnIsStoppedAndTheProcessContinues() {
    ProcessInstance instance = start("callLoop",
        "<camunda:property name='adHocMaxCallsPerTurn' value='3' />",
        tool -> {
          for (int i = 0; i < 10; i++) {
            tool.startActivity("quick", Collections.<String, Object>emptyMap());
          }
          return "never reached";
        });

    assertThat(AgentTask.FAILURES).hasSize(1);
    assertThat(AgentTask.FAILURES.get(0).getMessage())
        .contains("over the limit of 3")
        .contains("End your turn");
    assertThat(ENGINE.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(instance.getId()).count())
        .as("refused, not failed: the scope ends and the process runs on").isZero();
  }

  /** The next turn starts with a fresh call budget. */
  @Test
  public void theCallBudgetIsPerTurn() {
    ProcessInstance instance = startAsync("callBudget",
        "<camunda:property name='adHocMaxCallsPerTurn' value='3' />",
        tool -> tool.startActivity("waits", Collections.<String, Object>emptyMap()),
        tool -> tool.startActivity("quick", Collections.<String, Object>emptyMap()));

    runPendingTurn(instance);
    ENGINE.getTaskService().complete(task(instance, "waits").getId());
    runPendingTurn(instance);

    assertThat(AgentTask.FAILURES).as("the second turn starts counting again").isEmpty();
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
    start("zeroCap", "<camunda:property name='adHocMaxTurns' value='0' />",
        tool -> tool.listAvailableActivities());
    assertThat(result(0).get("maxTurns")).isEqualTo(AdHocSubProcessTool.DEFAULT_MAX_TURNS);

    AgentTask.SCRIPT.clear();
    AgentTask.RESULTS.clear();
    AgentTask.invocations = 0;

    start("negativeCap", "<camunda:property name='adHocMaxTurns' value='-10' />",
        tool -> tool.listAvailableActivities());
    assertThat(result(0).get("maxTurns")).isEqualTo(AdHocSubProcessTool.DEFAULT_MAX_TURNS);
  }

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

    assertThat(AgentTask.FAILURES).isEmpty();
    Map<String, Object> answer = result(0);
    assertThat(answer.get("status"))
        .as("a queued job has not run, so the model must not be told it finished")
        .isEqualTo("waiting");
    assertThat(answer.get("results")).as("nothing can be known yet")
        .isEqualTo(Collections.emptyMap());
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
        tool -> tool.listAvailableActivities());

    assertThat(AgentTask.FAILURES).isEmpty();
    assertThat(result(0).get("status")).as("the job is still queued").isEqualTo("waiting");

    // Running the job finishes the child, which gives the driver its next turn.
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

  /**
   * The agent is not offered to itself.
   *
   * <p>The agent task is a child of the scope like any other, so the catalogue read
   * from the model contains it. Offering it invites the model to start a second copy
   * of itself beside the one that is running — a further model call per copy, two
   * agents acting on one scope, and the turn budget spent on recursion instead of
   * work. The engine's coalescing does not prevent it: that guards the turn the
   * engine grants when a child ends, not an explicit activation.
   *
   * <p>Nothing caught this for a long time because the local test stub filtered the
   * agent out of its own candidate list, so the case was never exercised.
   */
  @Test
  public void theAgentIsNotOfferedToItself() {
    start("driverNotOffered", tool -> tool.listAvailableActivities());

    assertThat(AgentTask.FAILURES).isEmpty();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> offered =
        (List<Map<String, Object>>) result(0).get("activities");
    assertThat(offered).isNotEmpty();
    List<Object> ids = new ArrayList<Object>();
    for (Map<String, Object> item : offered) {
      ids.add(item.get("id"));
    }
    assertThat(ids).as("the driver must not be in its own catalogue").doesNotContain("agent");
  }

  /** And starting it is refused even if the model names it anyway. */
  @Test
  public void startingTheAgentItselfIsRefused() {
    start("driverStartRefused",
        tool -> tool.startActivity("agent", Collections.<String, Object>emptyMap()));

    assertThat(AgentTask.FAILURES).hasSize(1);
    assertThat(AgentTask.FAILURES.get(0).getMessage())
        .contains("that is this agent itself")
        .doesNotContain("is not inside an ad hoc sub process");
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

  /**
   * A scope with no driver ends on a completion request too.
   *
   * <p>This is the case that keeps the engine's two completion paths apart, and it
   * had no test. {@code completeOnIdleDrivenScope} requires a driver; only
   * {@code completeOnDriverEnd} serves a scope that has none, and merging the two
   * would silently break exactly this model. Legal, because the parser refuses a
   * driver without {@code explicitCompletionOnly} but not the other way round.
   */
  @Test
  public void aScopeWithoutADriverEndsOnRequestToo() {
    String processId = "noDriver";
    String bpmn = "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + " xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + " targetNamespace='http://cibseven.org/adhoc-tool'>"
        + "<process id='" + processId + "' isExecutable='true'>"
        + "  <startEvent id='start' />"
        + "  <sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + "  <adHocSubProcess id='adHoc'>"
        + "    <extensionElements><camunda:properties>"
        + "      <camunda:property name='explicitCompletionOnly' value='true' />"
        + "      <camunda:property name='activeElementsCollection' value='agent' />"
        + "    </camunda:properties></extensionElements>"
        + "    <serviceTask id='agent' name='Agent' camunda:class='" + AGENT + "' />"
        + "    <userTask id='waits' name='Waits' />"
        + "  </adHocSubProcess>"
        + "  <sequenceFlow id='f2' sourceRef='adHoc' targetRef='end' />"
        + "  <endEvent id='end' />"
        + "</process></definitions>";

    AgentTask.SCRIPT.add(tool -> tool.completeScope());
    ENGINE.getRepositoryService().createDeployment()
        .addString(processId + ".bpmn20.xml", bpmn).deploy();
    ProcessInstance instance = ENGINE.getRuntimeService().startProcessInstanceByKey(processId);

    assertThat(AgentTask.FAILURES).isEmpty();
    assertThat(result(0).get("completionRequested")).isEqualTo(Boolean.TRUE);
    assertThat(ENGINE.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(instance.getId()).count())
        .as("a driverless scope must end on the request as well").isZero();
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
      ENGINE.getRuntimeService().activateAdHocSubProcessActivities(
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
