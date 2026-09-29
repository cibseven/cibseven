/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
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
package org.cibseven.bpm.qa.upgrade.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;

import org.cibseven.bpm.engine.AuthorizationService;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.authorization.Groups;
import org.cibseven.bpm.engine.authorization.Permissions;
import org.cibseven.bpm.engine.authorization.Resources;
import org.cibseven.bpm.engine.impl.db.sql.DbSqlSessionFactory;
import org.cibseven.bpm.engine.impl.test.RequiredDatabase;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Disabled;

public class AuthorizationTest {

  protected AuthorizationService authorizationService;

  protected IdentityService identityService;

  protected ProcessEngineConfiguration processEngineConfiguration;

  protected boolean defaultAuthorizationEnabled;

  @RegisterExtension
  public ProcessEngineRule rule = new ProcessEngineRule();

  @BeforeEach
  public void init() {
    authorizationService = rule.getAuthorizationService();
    identityService = rule.getIdentityService();
    processEngineConfiguration = rule.getProcessEngineConfiguration();
    defaultAuthorizationEnabled = processEngineConfiguration.isAuthorizationEnabled();
  }

  @AfterEach
  public void restoreAuthorization() {
    processEngineConfiguration.setAuthorizationEnabled(defaultAuthorizationEnabled);
  }

  // TODO: fix and enable for CIB seven migration test
  @Disabled
  @Test
  public void testDefaultAuthorizationQueryForCamundaAdminOnUpgrade() {

    processEngineConfiguration.setAuthorizationEnabled(true);

    assertEquals(1, authorizationService.createAuthorizationQuery()
      .resourceType(Resources.TENANT)
      .groupIdIn(Groups.CAMUNDA_ADMIN)
      .hasPermission(Permissions.ALL).count());

    assertEquals(1, authorizationService.createAuthorizationQuery()
      .resourceType(Resources.TENANT_MEMBERSHIP)
      .groupIdIn(Groups.CAMUNDA_ADMIN)
      .hasPermission(Permissions.ALL).count());

    assertEquals(1, authorizationService.createAuthorizationQuery()
      .resourceType(Resources.BATCH)
      .groupIdIn(Groups.CAMUNDA_ADMIN)
      .hasPermission(Permissions.ALL).count());

  }

  // The below test cases are skipped for H2 as there is a bug in H2 version 1.3 (Query does not return the expected output)
  // This H2 exclusion check will be removed as part of CAM-6044, when the H2 database is upgraded to the version 1.4 (Bug was fixed)
  // Update: Upgrading to 1.4.190 did not help, still failing
  @Test
  @RequiredDatabase(excludes = DbSqlSessionFactory.H2)
  public void testDefaultAuthorizationForCamundaAdminOnUpgrade() {

    processEngineConfiguration.setAuthorizationEnabled(true);
    assertEquals(true,authorizationService.isUserAuthorized(null, Collections.singletonList(Groups.CAMUNDA_ADMIN), Permissions.ALL, Resources.TENANT));
    assertEquals(true,authorizationService.isUserAuthorized(null, Collections.singletonList(Groups.CAMUNDA_ADMIN), Permissions.ALL, Resources.TENANT_MEMBERSHIP));
    assertEquals(true,authorizationService.isUserAuthorized(null, Collections.singletonList(Groups.CAMUNDA_ADMIN), Permissions.ALL, Resources.BATCH));
  }
}
