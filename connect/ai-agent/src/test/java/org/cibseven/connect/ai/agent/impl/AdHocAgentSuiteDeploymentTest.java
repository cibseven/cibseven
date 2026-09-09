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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.junit.Rule;
import org.junit.Test;

/**
 * Deploys the hand-written BPMN suite under {@code src/test/resources/ad-hoc-agent}
 * and checks that each file is what it claims to be.
 *
 * <p>These files exist for manual and end-to-end runs against a real distribution
 * and a real language model, which this test cannot do. What it can do is stop
 * them rotting: a renamed property, a tightened deployment rule or a typo in an
 * activity id would otherwise only surface when someone deploys the file by hand
 * and cannot tell a broken model from a broken agent.
 *
 * <p>No model is ever called. The job executor is off, so starting an instance
 * creates the driver's job and stops there — which is itself the assertion that
 * entry activation reached the agent and that the agent is {@code asyncBefore}.
 */
public class AdHocAgentSuiteDeploymentTest {

  /** Every file of the suite, with the children each one is expected to offer. */
  private static final Object[][] SUITE = {
      {"01-sync-simple.bpmn", "adHocAgent01", new String[] {"agent", "calculatePrice"}},
      {"02-sync-multi-turn.bpmn", "adHocAgent02",
          new String[] {"agent", "calculatePrice", "validatePrice"}},
      {"03-async-user-task.bpmn", "adHocAgent03", new String[] {"agent", "approveOrder"}},
      {"04-async-rejection.bpmn", "adHocAgent04",
          new String[] {"agent", "approveOrder", "sendRejection", "sendConfirmation"}},
      {"05-mixed-sync-async.bpmn", "adHocAgent05",
          new String[] {"agent", "calculatePrice", "approveOrder", "sendConfirmation"}},
      {"06-multiple-sync.bpmn", "adHocAgent06",
          new String[] {"agent", "checkCustomer", "getCustomerData"}},
      {"07-parallel-async-fan-in.bpmn", "adHocAgent07",
          new String[] {"agent", "userApprovalA", "userApprovalB"}},
      {"08-agent-completion.bpmn", "adHocAgent08", new String[] {"agent"}},
      {"09-turn-limit.bpmn", "adHocAgent09",
          new String[] {"agent", "activityA", "activityB"}},
      {"10-restart-while-waiting.bpmn", "adHocAgent10", new String[] {"agent", "approveOrder"}},
      {"11-no-result.bpmn", "adHocAgent11", new String[] {"agent", "logAction"}},
      {"12-multiple-results.bpmn", "adHocAgent12",
          new String[] {"agent", "calculateOrder", "calcViaResultVariable", "calcViaProperty",
              "approveWithForm"}},
      {"13-repeated-result-variable.bpmn", "adHocAgent13",
          new String[] {"agent", "stepA", "stepB"}},
      {"14-agent-choice.bpmn", "adHocAgent14",
          new String[] {"agent", "calculatePrice", "sendEmail", "createDocument", "approveOrder"}},
  };

  private static final ProcessEngine ENGINE = buildInMemoryEngine();

  @Rule
  public ProcessEngineRule engineRule = new ProcessEngineRule(ENGINE);

  private static ProcessEngine buildInMemoryEngine() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setJdbcUrl("jdbc:h2:mem:adhoc-agent-suite-test;DB_CLOSE_DELAY=-1");
    // Off on purpose: the driver's job must stay unexecuted, or the connector
    // would try to reach a language model.
    configuration.setJobExecutorActivate(false);
    configuration.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
    // Without this the engine refuses to parse camunda:connector at all: "one of
    // the attributes class, delegateExpression, type or expression is mandatory
    // on serviceTask". A distribution registers the plugin; a bare in-memory
    // configuration does not, which is worth knowing before deploying these
    // files anywhere.
    configuration.setProcessEnginePlugins(
        Collections.<org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin>singletonList(
            new ConnectProcessEnginePlugin()));
    return configuration.buildProcessEngine();
  }

  private static String read(String file) {
    String path = "ad-hoc-agent/" + file;
    try (InputStream in =
        AdHocAgentSuiteDeploymentTest.class.getClassLoader().getResourceAsStream(path)) {
      assertThat(in).as(path).isNotNull();
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      byte[] chunk = new byte[8192];
      int read;
      while ((read = in.read(chunk)) != -1) {
        buffer.write(chunk, 0, read);
      }
      return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new AssertionError("could not read " + path, e);
    }
  }

  private String deploy(String file) {
    return ENGINE.getRepositoryService().createDeployment()
        .addString(file, read(file))
        .deployWithResult()
        .getDeployedProcessDefinitions().get(0)
        .getId();
  }

  /**
   * Every file deploys, and its scope offers exactly the children the suite says.
   *
   * <p>Deployment is the real assertion here. The four refusals in the parser mean
   * a scope that names a driver without being parked, or names a driver that is not
   * directly startable, does not deploy at all — so a file that survives this has a
   * driver the engine will actually re-activate.
   */
  @Test
  public void everyFileDeploysAndOffersTheExpectedActivities() {
    for (Object[] entry : SUITE) {
      String file = (String) entry[0];
      String[] expected = (String[]) entry[2];

      String definitionId = deploy(file);

      List<String> offered = new ArrayList<>();
      for (AdHocToolCatalog.Entry candidate :
          AdHocToolCatalog.read(ENGINE.getRepositoryService(), definitionId, "adHoc")) {
        offered.add(candidate.getId());
      }
      assertThat(offered).as(file).containsExactlyInAnyOrder(expected);
    }
  }

  /**
   * Starting an instance activates the agent and leaves it waiting as a job.
   *
   * <p>Three things at once: entry activation reached the agent, the agent is
   * {@code asyncBefore} so its work is a separate transaction, and no language
   * model was contacted because the job was never executed.
   */
  @Test
  public void startingAnInstanceLeavesTheAgentWaitingAsAJob() {
    for (Object[] entry : SUITE) {
      String file = (String) entry[0];
      String processId = (String) entry[1];
      deploy(file);

      ProcessInstance instance =
          ENGINE.getRuntimeService().startProcessInstanceByKey(processId);

      List<Job> jobs = ENGINE.getManagementService().createJobQuery()
          .processInstanceId(instance.getId()).list();
      assertThat(jobs).as(file + ": the driver's job").hasSize(1);
      // Job carries no activity id, so the filter does the work.
      assertThat(ENGINE.getManagementService().createJobQuery()
          .processInstanceId(instance.getId()).activityId("agent").count())
          .as(file + ": the job belongs to the agent").isEqualTo(1);

      // Nothing has run yet, so nothing is complete and the scope is still there.
      assertThat(ENGINE.getRuntimeService().createProcessInstanceQuery()
          .processInstanceId(instance.getId()).count()).as(file).isEqualTo(1);
    }
  }

  /** The parked scope really is parked, in every file. */
  @Test
  public void everyScopeIsParkedAndDrivenByTheAgent() {
    for (Object[] entry : SUITE) {
      String file = (String) entry[0];
      String definitionId = deploy(file);

      String xml = read(file);
      assertThat(xml).as(file).contains("name=\"explicitCompletionOnly\" value=\"true\"");
      assertThat(xml).as(file).contains("name=\"adHocDriverActivity\" value=\"agent\"");
      assertThat(xml).as(file).contains("name=\"activeActivityIds\" value=\"agent\"");
      assertThat(xml).as(file).contains("camunda:asyncBefore=\"true\"");
      assertThat(xml).as(file)
          .contains("org.cibseven.connect.ai.agent.impl.AdHocSubProcessTool");
      // No completion condition anywhere: it and explicitCompletionOnly are two
      // answers to the same question, and the parser refuses both together.
      assertThat(xml).as(file).doesNotContain("completionCondition");

      ProcessDefinition definition =
          ENGINE.getRepositoryService().getProcessDefinition(definitionId);
      assertThat(definition).as(file).isNotNull();
    }
  }

  /** The result-variable derivation sees what each file set up for it. */
  @Test
  public void theDerivedResultVariablesAreWhatTheFilesDeclare() {
    String definitionId = deploy("12-multiple-results.bpmn");

    assertThat(resultVariables(definitionId, "calculateOrder"))
        .as("output parameters").containsExactly("amount", "currency", "valid");
    assertThat(resultVariables(definitionId, "calcViaResultVariable"))
        .as("camunda:resultVariable").containsExactly("amount2");
    assertThat(resultVariables(definitionId, "calcViaProperty"))
        .as("explicit adHocResultVariables").containsExactly("note");
    assertThat(resultVariables(definitionId, "approveWithForm"))
        .as("form field ids").containsExactly("approvedByForm", "comment");

    // Case 11's activity declares nothing, which is the point of that file.
    assertThat(resultVariables(deploy("11-no-result.bpmn"), "logAction")).isEmpty();

    // Case 13's two activities deliberately share one name.
    String repeated = deploy("13-repeated-result-variable.bpmn");
    assertThat(resultVariables(repeated, "stepA")).containsExactly("result");
    assertThat(resultVariables(repeated, "stepB")).containsExactly("result");
  }

  /** Only case 09 caps the turns, and it caps them low enough to be observable. */
  @Test
  public void onlyTheTurnLimitFileSetsATurnCap() {
    for (Object[] entry : SUITE) {
      String file = (String) entry[0];
      String xml = read(file);
      if ("09-turn-limit.bpmn".equals(file)) {
        assertThat(xml).as(file).contains("name=\"adHocMaxTurns\" value=\"3\"");
      } else {
        assertThat(xml).as(file).doesNotContain("adHocMaxTurns");
      }
    }
  }

  private List<String> resultVariables(String definitionId, String activityId) {
    for (AdHocToolCatalog.Entry entry :
        AdHocToolCatalog.read(ENGINE.getRepositoryService(), definitionId, "adHoc")) {
      if (activityId.equals(entry.getId())) {
        return entry.getResultVariables();
      }
    }
    throw new AssertionError("no entry for " + activityId
        + " among " + Arrays.toString(new Object[] {definitionId}));
  }
}
