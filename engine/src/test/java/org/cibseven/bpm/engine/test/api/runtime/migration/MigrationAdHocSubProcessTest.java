/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cibseven.bpm.engine.test.api.runtime.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cibseven.bpm.engine.test.util.MigrationPlanValidationReportAssert.assertThat;
import static org.junit.Assert.fail;

import org.cibseven.bpm.engine.migration.MigratingProcessInstanceValidationException;
import org.cibseven.bpm.engine.migration.MigrationInstruction;
import org.cibseven.bpm.engine.migration.MigrationPlan;
import org.cibseven.bpm.engine.migration.MigrationPlanValidationException;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.cibseven.bpm.model.bpmn.builder.AdHocSubProcessBuilder;
import org.cibseven.bpm.model.bpmn.instance.UserTask;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

/**
 * Migration of an ad hoc sub process, and of the activities inside one.
 *
 * <p>An ad hoc scope carries runtime state that no migration instruction describes: how many of its
 * children have been activated so far. That count decides completion when the scope has no
 * completionCondition, so carrying it into a target scope whose completion rule is different
 * silently changes when the instance ends.
 */
public class MigrationAdHocSubProcessTest {

  protected ProcessEngineRule rule = new ProvidedProcessEngineRule();
  protected MigrationTestRule testHelper = new MigrationTestRule(rule);

  @Rule
  public RuleChain ruleChain = RuleChain.outerRule(rule).around(testHelper);

  protected static BpmnModelInstance adHocProcess(String condition) {
    AdHocSubProcessBuilder adHoc = Bpmn.createExecutableProcess("Process")
        .startEvent("start")
        .adHocSubProcess("adHoc");
    adHoc.child(UserTask.class, "taskA");
    if (condition != null) {
      adHoc.completionCondition(condition);
    }
    return adHoc.endEvent("end").done();
  }

  protected static BpmnModelInstance plainSubProcessProcess() {
    return Bpmn.createExecutableProcess("Process")
        .startEvent("start")
        .subProcess("adHoc")
        .embeddedSubProcess()
          .startEvent("innerStart")
          .userTask("taskA")
          .endEvent("innerEnd")
        .subProcessDone()
        .endEvent("end")
        .done();
  }

  @Test
  public void cannotMigrateAdHocScopeToPlainSubProcess() {
    ProcessDefinition source = testHelper.deployAndGetDefinition(adHocProcess(null));
    ProcessDefinition target = testHelper.deployAndGetDefinition(plainSubProcessProcess());

    try {
      rule.getRuntimeService()
          .createMigrationPlan(source.getId(), target.getId())
          .mapActivities("adHoc", "adHoc")
          .mapActivities("taskA", "taskA")
          .build();
      fail("mapping an ad hoc scope onto a plain sub process must not be allowed");
    } catch (MigrationPlanValidationException e) {
      assertThat(e.getValidationReport())
          .hasInstructionFailures("adHoc", "Activities have incompatible types");
    }
  }

  @Test
  public void cannotMigrateAdHocScopeWhenTheCompletionRuleChanges() {
    // Source completes when nothing is active and at least one child has been activated. Target
    // completes when its condition says so. The activation count that the source scope was relying
    // on has no meaning in the target, and nothing in the plan says what should happen to it.
    ProcessDefinition source = testHelper.deployAndGetDefinition(adHocProcess(null));
    ProcessDefinition target = testHelper.deployAndGetDefinition(adHocProcess("${approved}"));

    try {
      rule.getRuntimeService()
          .createMigrationPlan(source.getId(), target.getId())
          .mapActivities("adHoc", "adHoc")
          .mapActivities("taskA", "taskA")
          .build();
      fail("changing the completion rule of an ad hoc scope must not be allowed");
    } catch (MigrationPlanValidationException e) {
      assertThat(e.getValidationReport())
          .hasInstructionFailures("adHoc",
              "Cannot migrate an ad hoc sub process to one with a different completion rule");
    }
  }

  @Test
  public void canMigrateAnAdHocScopeWhenOnlyTheConditionExpressionChanges() {
    // The rule is unchanged: a condition still decides. Re-reading the expression from the target
    // definition is ordinary migration behaviour.
    ProcessDefinition source = testHelper.deployAndGetDefinition(adHocProcess("${approved}"));
    ProcessDefinition target = testHelper.deployAndGetDefinition(adHocProcess("${signedOff}"));

    rule.getRuntimeService()
        .createMigrationPlan(source.getId(), target.getId())
        .mapActivities("adHoc", "adHoc")
        .mapActivities("taskA", "taskA")
        .build();
  }

  @Test
  public void mappingAChildWithoutItsAdHocScopeIsAcceptedByThePlan() {
    // Documents what actually happens rather than what might be expected. The plan builds; whether
    // any instance can then move is decided per instance at execution time.
    ProcessDefinition source = testHelper.deployAndGetDefinition(adHocProcess(null));
    ProcessDefinition target = testHelper.deployAndGetDefinition(adHocProcess(null));

    rule.getRuntimeService()
        .createMigrationPlan(source.getId(), target.getId())
        .mapActivities("taskA", "taskA")
        .build();
  }

  /**
   * The one that matters. An ad hoc scope is absent from
   * SupportedActivityValidator.SUPPORTED_ACTIVITY_BEHAVIORS, so an instance sitting inside one
   * cannot migrate at all, and the refusal arrives at execution time, per instance. Over REST that
   * is a 500 on the execute call, or a failed job in a batch, rather than a 400 when the plan is
   * built.
   *
   * <p>This test pins the current behaviour so that the refusal cannot disappear unnoticed. It is
   * NOT an endorsement of it: whether to support migrating an active ad hoc scope is an open
   * decision, because it requires deciding what happens to the activation count the scope has
   * accumulated. Adding AdHocSubProcessActivityBehavior to that list without answering that
   * question would migrate the instance and silently lose the count.
   */
  @Test
  public void migratingAnActiveAdHocScopeIsRefusedAtExecutionTime() {
    ProcessDefinition source = testHelper.deployAndGetDefinition(adHocProcess("${approved}"));
    ProcessDefinition target = testHelper.deployAndGetDefinition(adHocProcess("${approved}"));

    ProcessInstance pi = rule.getRuntimeService().startProcessInstanceById(source.getId());
    rule.getRuntimeService().createProcessInstanceModification(pi.getId())
        .startBeforeActivity("taskA").execute();

    MigrationPlan plan = rule.getRuntimeService()
        .createMigrationPlan(source.getId(), target.getId())
        .mapActivities("adHoc", "adHoc")
        .mapActivities("taskA", "taskA")
        .build();

    try {
      rule.getRuntimeService().newMigration(plan).processInstanceIds(pi.getId()).execute();
      fail("an instance inside an ad hoc scope is not migratable, so this must be refused");
    } catch (MigratingProcessInstanceValidationException e) {
      assertThat(e.getMessage())
          .contains("The type of the source activity is not supported for activity instance migration");
    }

    assertThat(rule.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(pi.getId()).singleResult().getProcessDefinitionId())
        .as("a refused migration must leave the instance on its original definition")
        .isEqualTo(source.getId());
  }

  @Test
  public void anAdHocScopeIsNotOfferedByPlanGeneration() {
    // SupportedActivityValidator also drives instruction generation, so a generated plan leaves the
    // ad hoc scope out rather than producing a mapping that could never be executed.
    ProcessDefinition source = testHelper.deployAndGetDefinition(adHocProcess(null));
    ProcessDefinition target = testHelper.deployAndGetDefinition(adHocProcess(null));

    MigrationPlan generated = rule.getRuntimeService()
        .createMigrationPlan(source.getId(), target.getId())
        .mapEqualActivities()
        .build();

    for (MigrationInstruction instruction : generated.getInstructions()) {
      assertThat(instruction.getSourceActivityId())
          .as("plan generation must not map an ad hoc scope")
          .isNotEqualTo("adHoc");
    }
  }

}
