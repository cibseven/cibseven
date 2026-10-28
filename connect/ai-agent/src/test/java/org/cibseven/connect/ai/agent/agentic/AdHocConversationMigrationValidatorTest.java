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
package org.cibseven.connect.ai.agent.agentic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.migration.validation.instance.MigratingActivityInstanceValidator;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.migration.MigratingProcessInstanceValidationException;
import org.cibseven.bpm.engine.migration.MigrationPlan;
import org.cibseven.bpm.engine.repository.Deployment;
import org.cibseven.bpm.engine.runtime.Execution;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link AdHocConversationMigrationValidator} — why a holder that survives its scope (CIB7-2137)
 * cannot be migrated silently.
 *
 * <p>Found the hard way: a holder's variables sit on an execution the {@code ActivityInstance}
 * tree migration walks never visits (it has no activity), so they are never claimed by anything
 * migration does, and the engine's own, unrelated check fails late with
 * {@code Process instance contains not migrated variables}, naming only variable ids. This proves
 * the replacement: a refusal that names the scope and says why, exactly once per holder, and that
 * the very same migration succeeds once the holder -- not necessarily the scope, which may have
 * ended long before -- is actually gone.
 */
public class AdHocConversationMigrationValidatorTest {

  protected ProcessEngine engine;
  protected RuntimeService runtimeService;

  @BeforeEach
  public void startEngine() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName("adhoc-conversation-migration");
    configuration.setJdbcUrl("jdbc:h2:mem:adhoc-conversation-migration;DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    configuration.setEnforceHistoryTimeToLive(false);
    List<BpmnParseListener> postParseListeners = new ArrayList<BpmnParseListener>();
    postParseListeners.add(new AdHocConversationTest.ConversationModeListener());
    configuration.setCustomPostBPMNParseListeners(postParseListeners);
    List<MigratingActivityInstanceValidator> validators =
        new ArrayList<MigratingActivityInstanceValidator>();
    validators.add(new AdHocConversationMigrationValidator());
    configuration.setCustomPostMigratingActivityInstanceValidators(validators);
    engine = configuration.buildProcessEngine();
    runtimeService = engine.getRuntimeService();
  }

  @AfterEach
  public void stopEngine() {
    engine.close();
  }

  protected String scopeExecutionId(String processInstanceId, String activityId) {
    for (Execution execution : runtimeService.createExecutionQuery()
        .processInstanceId(processInstanceId).list()) {
      if (activityId.equals(((ExecutionEntity) execution).getActivityId())) {
        return execution.getId();
      }
    }
    throw new AssertionError("no execution sitting on '" + activityId + "'");
  }

  protected void completeTask(String processInstanceId, String taskDefinitionKey) {
    Task task = engine.getTaskService().createTaskQuery()
        .processInstanceId(processInstanceId).taskDefinitionKey(taskDefinitionKey).singleResult();
    assertThat(task).as("'" + taskDefinitionKey + "' is waiting").isNotNull();
    engine.getTaskService().complete(task.getId());
  }

  /** One deployment of the model, so {@code mapEqualActivities()} has a target to migrate to. */
  protected String deployDefinition() {
    Deployment deployment = engine.getRepositoryService().createDeployment()
        .addClasspathResource("agentic/AdHocConversationMigrationValidatorTest.bpmn20.xml")
        .deploy();
    return engine.getRepositoryService().createProcessDefinitionQuery()
        .deploymentId(deployment.getId()).singleResult().getId();
  }

  protected MigrationPlan planBetween(String sourceDefinitionId, String targetDefinitionId) {
    return runtimeService.createMigrationPlan(sourceDefinitionId, targetDefinitionId)
        .mapEqualActivities().build();
  }

  protected void migrate(MigrationPlan plan, String processInstanceId) {
    runtimeService.newMigration(plan)
        .processInstanceIds(Collections.singletonList(processInstanceId)).execute();
  }

  /**
   * The ad hoc scope has already ended -- the instance waits at the plain "feedback" user task,
   * nowhere near it -- but its holder survives under the enclosing subprocess. Migrating the
   * whole instance is refused, naming the scope the holder belongs to, not the engine's generic
   * "not migrated variables" failure.
   */
  @Test
  public void refusesMigrationWhileTheHolderSurvivesTheEndedScope() {
    refusesMigrationWhileTheHolderSurvivesTheEndedScope(false);
  }

  @Test
  public void refusesMigrationForALegacyHolderByActivityName() {
    refusesMigrationWhileTheHolderSurvivesTheEndedScope(true);
  }

  private void refusesMigrationWhileTheHolderSurvivesTheEndedScope(boolean legacy) {
    String v1 = deployDefinition();
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("conversationMigration");
    runtimeService.activateAdHocSubProcessActivities(
        scopeExecutionId(pi.getId(), "adHocPerActivity"), Collections.singletonList("enter"));
    if (legacy) {
      String holder = runtimeService.createVariableInstanceQuery().processInstanceIdIn(pi.getId())
          .variableName(AdHocConversation.HOLDER_MARKER).singleResult().getExecutionId();
      runtimeService.setVariableLocal(holder, AdHocConversation.HOLDER_MARKER, "adHocPerActivity");
    }

    String v2 = deployDefinition();
    MigrationPlan plan = planBetween(v1, v2);

    assertThatThrownBy(() -> migrate(plan, pi.getId()))
        .isInstanceOf(MigratingProcessInstanceValidationException.class)
        .hasMessageContaining("adHocPerActivity")
        .hasMessageContaining("perActivity")
        .hasMessageContaining("no defined migration")
        .hasMessageNotContaining("activityId=");
  }

  /**
   * Once "feedback" completes, the enclosing subprocess ends and takes the holder with it: the
   * very same plan, against the very same instance, now migrates cleanly.
   */
  @Test
  public void migratesOnceTheHolderIsActuallyGone() {
    String v1 = deployDefinition();
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("conversationMigration");
    runtimeService.activateAdHocSubProcessActivities(
        scopeExecutionId(pi.getId(), "adHocPerActivity"), Collections.singletonList("enter"));
    completeTask(pi.getId(), "feedback");

    assertThat(engine.getRuntimeService().createVariableInstanceQuery()
        .processInstanceIdIn(pi.getId()).variableName(AdHocConversation.HOLDER_MARKER).list())
        .as("the holder ended with the enclosing subprocess").isEmpty();

    String v2 = deployDefinition();
    MigrationPlan plan = planBetween(v1, v2);

    migrate(plan, pi.getId());

    assertThat(engine.getRuntimeService().createExecutionQuery()
        .processInstanceId(pi.getId()).activityId("afterOuter").singleResult())
        .as("migrated onto the new definition's matching wait state").isNotNull();
  }
}
