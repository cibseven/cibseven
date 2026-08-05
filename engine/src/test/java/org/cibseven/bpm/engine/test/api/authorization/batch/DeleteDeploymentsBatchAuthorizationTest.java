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
package org.cibseven.bpm.engine.test.api.authorization.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario.scenario;
import static org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationSpec.grant;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.authorization.BatchPermissions;
import org.cibseven.bpm.engine.authorization.Permissions;
import org.cibseven.bpm.engine.authorization.Resources;
import org.cibseven.bpm.engine.batch.Batch;
import org.cibseven.bpm.engine.repository.Deployment;
import org.cibseven.bpm.engine.repository.DeploymentQuery;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationTestRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Authorization tests for {@code RepositoryService#deleteDeploymentsAsync} (CIB7-1597).
 * {@code DeleteDeploymentsBatchCmd} checks two independent things while building the batch,
 * both fail-fast and both reject the whole batch (no partial creation):
 * layer 1 - {@code CREATE_BATCH_DELETE_DEPLOYMENTS} (or the generic {@code CREATE} on BATCH) to create the batch at all;
 * layer 2 - {@code DELETE} on every single collected deployment id; the whole batch is rejected
 * as soon as any one deployment is missing it.
 *
 * <p>Every scenario grants {@code READ} on both deployments as a baseline: deployment queries are
 * READ-filtered ({@code AuthorizationManager#configureDeploymentQuery}), so without it a query-based
 * selection would silently drop the deployment before any DELETE check ever runs, rather than
 * exercising the permission gap under test.
 */
@RunWith(Parameterized.class)
public class DeleteDeploymentsBatchAuthorizationTest {

  protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  protected AuthorizationTestRule authRule = new AuthorizationTestRule(engineRule);
  protected ProcessEngineTestRule testHelper = new ProcessEngineTestRule(engineRule);

  @Rule
  public RuleChain ruleChain = RuleChain.outerRule(engineRule).around(authRule).around(testHelper);

  @Parameterized.Parameter
  public AuthorizationScenario scenario;

  protected RepositoryService repositoryService;
  protected ManagementService managementService;

  protected Deployment deployment1;
  protected Deployment deployment2;
  protected Batch batch;

  @Parameterized.Parameters(name = "Scenario {index}")
  public static Collection<AuthorizationScenario[]> scenarios() {
    return AuthorizationTestRule.asParameters(
        // no right to create the batch at all - deployment-level rights are irrelevant,
        // since layer 1 is checked first and short-circuits before layer 2 ever runs
        scenario()
            .withAuthorizations(
                grant(Resources.DEPLOYMENT, "deployment1", "userId", Permissions.READ),
                grant(Resources.DEPLOYMENT, "deployment2", "userId", Permissions.READ)
            )
            .failsDueToRequired(
                grant(Resources.BATCH, "*", "userId", Permissions.CREATE),
                grant(Resources.BATCH, "*", "userId", BatchPermissions.CREATE_BATCH_DELETE_DEPLOYMENTS)
            ),
        // batch-level right present, but DELETE missing on deployment1 (deployment2 fully granted)
        scenario()
            .withAuthorizations(
                grant(Resources.BATCH, "*", "userId", BatchPermissions.CREATE_BATCH_DELETE_DEPLOYMENTS),
                grant(Resources.DEPLOYMENT, "deployment1", "userId", Permissions.READ),
                grant(Resources.DEPLOYMENT, "deployment2", "userId", Permissions.READ, Permissions.DELETE)
            )
            .failsDueToRequired(
                grant(Resources.DEPLOYMENT, "deployment1", "userId", Permissions.DELETE)
            ),
        // symmetric to the previous scenario: DELETE missing on deployment2 (deployment1 fully granted).
        // together the two prove that EVERY collected deployment is checked, not just one of them
        scenario()
            .withAuthorizations(
                grant(Resources.BATCH, "*", "userId", BatchPermissions.CREATE_BATCH_DELETE_DEPLOYMENTS),
                grant(Resources.DEPLOYMENT, "deployment1", "userId", Permissions.READ, Permissions.DELETE),
                grant(Resources.DEPLOYMENT, "deployment2", "userId", Permissions.READ)
            )
            .failsDueToRequired(
                grant(Resources.DEPLOYMENT, "deployment2", "userId", Permissions.DELETE)
            ),
        // both rights present via the specific batch permission -> succeeds
        scenario()
            .withAuthorizations(
                grant(Resources.BATCH, "*", "userId", BatchPermissions.CREATE_BATCH_DELETE_DEPLOYMENTS),
                grant(Resources.DEPLOYMENT, "deployment1", "userId", Permissions.READ, Permissions.DELETE),
                grant(Resources.DEPLOYMENT, "deployment2", "userId", Permissions.READ, Permissions.DELETE)
            ).succeeds(),
        // generic CREATE on BATCH is a valid alternative to the specific batch permission (disjunctive) -> succeeds
        scenario()
            .withAuthorizations(
                grant(Resources.BATCH, "*", "userId", Permissions.CREATE),
                grant(Resources.DEPLOYMENT, "deployment1", "userId", Permissions.READ, Permissions.DELETE),
                grant(Resources.DEPLOYMENT, "deployment2", "userId", Permissions.READ, Permissions.DELETE)
            ).succeeds()
    );
  }

  @Before
  public void setUp() {
    batch = null;
    authRule.createUserAndGroup("userId", "groupId");
    repositoryService = engineRule.getRepositoryService();
    managementService = engineRule.getManagementService();
  }

  @Before
  public void deployDeployments() {
    deployment1 = deploy("process1");
    deployment2 = deploy("process2");
  }

  @After
  public void tearDown() {
    if (batch != null) {
      managementService.deleteBatch(batch.getId(), true);
    }
    authRule.deleteUsersAndGroups();
  }

  @After
  public void deleteRemainingDeployments() {
    for (Deployment deployment : repositoryService.createDeploymentQuery().list()) {
      repositoryService.deleteDeployment(deployment.getId(), true);
    }
  }

  protected Deployment deploy(String processKey) {
    BpmnModelInstance model = Bpmn.createExecutableProcess(processKey).startEvent().endEvent().done();
    return repositoryService.createDeployment()
        .name(processKey)
        .addModelInstance(processKey + ".bpmn", model)
        .deploy();
  }

  @Test
  public void testDeploymentIdsList() {
    // given
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("deployment1", deployment1.getId())
        .bindResource("deployment2", deployment2.getId())
        .start();

    // when
    batch = repositoryService.deleteDeploymentsAsync(
        Arrays.asList(deployment1.getId(), deployment2.getId()), null, false, false, false);

    // then
    if (authRule.assertScenario(scenario)) {
      assertThat(batch.getCreateUserId()).isEqualTo("userId");
    }
  }

  @Test
  public void testDeploymentIdsListReversed() {
    // same as testDeploymentIdsList but with the ids in reverse order [d2, d1]:
    // the per-deployment DELETE check must not depend on the position in the list
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("deployment1", deployment1.getId())
        .bindResource("deployment2", deployment2.getId())
        .start();

    // when
    batch = repositoryService.deleteDeploymentsAsync(
        Arrays.asList(deployment2.getId(), deployment1.getId()), null, false, false, false);

    // then
    if (authRule.assertScenario(scenario)) {
      assertThat(batch.getCreateUserId()).isEqualTo("userId");
    }
  }

  @Test
  public void testWithQuery() {
    // given
    DeploymentQuery query = repositoryService.createDeploymentQuery();

    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("deployment1", deployment1.getId())
        .bindResource("deployment2", deployment2.getId())
        .start();

    // when
    batch = repositoryService.deleteDeploymentsAsync(null, query, false, false, false);

    // then
    if (authRule.assertScenario(scenario)) {
      assertThat(batch.getCreateUserId()).isEqualTo("userId");
    }
  }

  @Test
  public void testMixedIdsAndQuery() {
    // given: deployment1 is selected explicitly, deployment2 comes in only via the query
    DeploymentQuery query = repositoryService.createDeploymentQuery().deploymentId(deployment2.getId());

    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("deployment1", deployment1.getId())
        .bindResource("deployment2", deployment2.getId())
        .start();

    // when
    batch = repositoryService.deleteDeploymentsAsync(
        Collections.singletonList(deployment1.getId()), query, false, false, false);

    // then
    if (authRule.assertScenario(scenario)) {
      assertThat(batch.getCreateUserId()).isEqualTo("userId");
    }
  }
}
