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
package org.cibseven.bpm.engine.test.api.multitenancy.tenantcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;

import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.batch.Batch;
import org.cibseven.bpm.engine.repository.Deployment;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

/**
 * Tenant-check tests for {@code RepositoryService#deleteDeploymentsAsync} (CIB7-1597).
 * The tenant check is a separate mechanism from the {@code AuthorizationService} grants
 * covered in {@code DeleteDeploymentsBatchAuthorizationTest}: it is enforced by
 * {@code TenantCommandChecker#checkDeleteDeployment}, one of the command checkers that
 * {@code DeleteDeploymentsBatchCmd} runs for every collected deployment id.
 */
public class MultiTenancyDeleteDeploymentsBatchTenantCheckTest {

  protected static final String TENANT_ONE = "tenant1";
  protected static final String TENANT_TWO = "tenant2";

  protected static final BpmnModelInstance MODEL = Bpmn.createExecutableProcess().startEvent().endEvent().done();

  protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  @Rule
  public RuleChain ruleChain = RuleChain.outerRule(engineRule).around(testRule);

  protected RepositoryService repositoryService;
  protected ManagementService managementService;
  protected IdentityService identityService;
  protected ProcessEngineConfiguration processEngineConfiguration;
  protected Batch batch;

  @Before
  public void init() {
    repositoryService = engineRule.getRepositoryService();
    managementService = engineRule.getManagementService();
    identityService = engineRule.getIdentityService();
    processEngineConfiguration = engineRule.getProcessEngineConfiguration();
  }

  @Test
  public void shouldFailForDeploymentOfNonAuthenticatedTenant() {
    Deployment deployment = testRule.deployForTenant(TENANT_ONE, MODEL);

    identityService.setAuthentication("user", null, null);

    assertThatThrownBy(() -> repositoryService.deleteDeploymentsAsync(
        Collections.singletonList(deployment.getId()), null, false, false, false))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot delete the deployment");
  }

  @Test
  public void shouldSucceedForDeploymentOfAuthenticatedTenant() {
    Deployment deployment = testRule.deployForTenant(TENANT_ONE, MODEL);

    identityService.setAuthentication("user", null, Arrays.asList(TENANT_ONE));

    batch = repositoryService.deleteDeploymentsAsync(
        Collections.singletonList(deployment.getId()), null, false, false, false);

    assertThat(batch).isNotNull();
  }

  @Test
  public void shouldIgnoreTenantWhenTenantCheckDisabled() {
    Deployment deploymentOne = testRule.deployForTenant(TENANT_ONE, MODEL);
    Deployment deploymentTwo = testRule.deployForTenant(TENANT_TWO, MODEL);

    processEngineConfiguration.setTenantCheckEnabled(false);
    identityService.setAuthentication("user", null, null);

    batch = repositoryService.deleteDeploymentsAsync(
        Arrays.asList(deploymentOne.getId(), deploymentTwo.getId()), null, false, false, false);

    assertThat(batch).isNotNull();
  }

  @Test
  public void shouldFailWholeBatchWhenOneOfMultipleDeploymentsBelongsToNonAuthenticatedTenant() {
    Deployment authorizedDeployment = testRule.deployForTenant(TENANT_ONE, MODEL);
    Deployment foreignDeployment = testRule.deployForTenant(TENANT_TWO, MODEL);

    identityService.setAuthentication("user", null, Arrays.asList(TENANT_ONE));

    // the tenant gap on foreignDeployment must reject the whole batch, not just that one id -
    // no batch is created and authorizedDeployment is left untouched
    assertThatThrownBy(() -> repositoryService.deleteDeploymentsAsync(
        Arrays.asList(authorizedDeployment.getId(), foreignDeployment.getId()), null, false, false, false))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot delete the deployment");

    identityService.clearAuthentication();
    assertThat(repositoryService.createDeploymentQuery().deploymentId(authorizedDeployment.getId()).count()).isEqualTo(1L);
    assertThat(repositoryService.createDeploymentQuery().count()).isEqualTo(2L);
  }

  @Test
  public void shouldFailForForeignDeploymentRegardlessOfListPosition() {
    Deployment foreignDeployment = testRule.deployForTenant(TENANT_TWO, MODEL);
    Deployment authorizedDeployment = testRule.deployForTenant(TENANT_ONE, MODEL);

    identityService.setAuthentication("user", null, Arrays.asList(TENANT_ONE));

    // foreign deployment listed first: the tenant check must not depend on the position in the list
    assertThatThrownBy(() -> repositoryService.deleteDeploymentsAsync(
        Arrays.asList(foreignDeployment.getId(), authorizedDeployment.getId()), null, false, false, false))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot delete the deployment");

    identityService.clearAuthentication();
    assertThat(repositoryService.createDeploymentQuery().count()).isEqualTo(2L);
  }

  @Test
  public void shouldSucceedWithTenantScopedQuery() {
    testRule.deployForTenant(TENANT_ONE, MODEL);
    testRule.deployForTenant(TENANT_TWO, MODEL);

    identityService.setAuthentication("user", null, Arrays.asList(TENANT_ONE));

    // the query is tenant-scoped, so it only ever selects the authenticated tenant's deployment;
    // the foreign one is filtered out during collection and never reaches the tenant check
    batch = repositoryService.deleteDeploymentsAsync(
        null, repositoryService.createDeploymentQuery(), false, false, false);

    assertThat(batch).isNotNull();
    assertThat(batch.getTotalJobs()).isEqualTo(1);
  }

  @Test
  public void shouldFailForForeignExplicitIdCombinedWithQuery() {
    Deployment foreignDeployment = testRule.deployForTenant(TENANT_TWO, MODEL);
    testRule.deployForTenant(TENANT_ONE, MODEL);

    identityService.setAuthentication("user", null, Arrays.asList(TENANT_ONE));

    // a tenant-scoped query would never surface the foreign deployment, but passing it as an
    // explicit id must still be caught by the per-deployment tenant check
    assertThatThrownBy(() -> repositoryService.deleteDeploymentsAsync(
        Collections.singletonList(foreignDeployment.getId()),
        repositoryService.createDeploymentQuery(), false, false, false))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot delete the deployment");

    identityService.clearAuthentication();
    assertThat(repositoryService.createDeploymentQuery().count()).isEqualTo(2L);
  }

  @Test
  public void shouldSucceedForDeploymentsOfMultipleAuthenticatedTenants() {
    Deployment deploymentOne = testRule.deployForTenant(TENANT_ONE, MODEL);
    Deployment deploymentTwo = testRule.deployForTenant(TENANT_TWO, MODEL);

    // the tenant checker accepts a list of authenticated tenants
    identityService.setAuthentication("user", null, Arrays.asList(TENANT_ONE, TENANT_TWO));

    batch = repositoryService.deleteDeploymentsAsync(
        Arrays.asList(deploymentOne.getId(), deploymentTwo.getId()), null, false, false, false);

    assertThat(batch).isNotNull();
    assertThat(batch.getTotalJobs()).isEqualTo(2);
  }

  @Test
  public void shouldFailWhenAuthenticatedForDifferentTenant() {
    Deployment deployment = testRule.deployForTenant(TENANT_ONE, MODEL);

    // authenticated for tenant2, but the deployment belongs to tenant1
    identityService.setAuthentication("user", null, Arrays.asList(TENANT_TWO));

    assertThatThrownBy(() -> repositoryService.deleteDeploymentsAsync(
        Collections.singletonList(deployment.getId()), null, false, false, false))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot delete the deployment");
  }

  @After
  public void tearDown() {
    identityService.clearAuthentication();
    processEngineConfiguration.setTenantCheckEnabled(true);
    if (batch != null) {
      managementService.deleteBatch(batch.getId(), true);
    }
    for (Deployment deployment : repositoryService.createDeploymentQuery().list()) {
      repositoryService.deleteDeployment(deployment.getId(), true);
    }
  }
}
