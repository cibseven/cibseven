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
package org.cibseven.bpm.engine.test.api.authorization;

import static org.cibseven.bpm.engine.authorization.Authorization.ANY;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cibseven.bpm.engine.AuthorizationService;
import org.cibseven.bpm.engine.authorization.OptimizePermissions;
import org.cibseven.bpm.engine.authorization.Resources;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationTestBaseRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;


public class OptimizeAuthorizationTest {

  protected static final String USER_ID = "user";

  @RegisterExtension
  @Order(1) public ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  @Order(2) public AuthorizationTestBaseRule authRule = new AuthorizationTestBaseRule(engineRule);


  ProcessEngineConfigurationImpl processEngineConfiguration;
  AuthorizationService authorizationService;

  @BeforeEach
  public void setUp() {
    processEngineConfiguration = engineRule.getProcessEngineConfiguration();
    authorizationService = engineRule.getAuthorizationService();
  }

  @Test
  public void testOptimizePermissionExists() {
    // given
    authRule.createGrantAuthorization(Resources.OPTIMIZE, ANY, USER_ID, OptimizePermissions.ALL);

    // when
    authRule.enableAuthorization(USER_ID);

    // then
    assertTrue(authorizationService.isUserAuthorized(USER_ID, null, OptimizePermissions.EDIT, Resources.OPTIMIZE));
    assertTrue(authorizationService.isUserAuthorized(USER_ID, null, OptimizePermissions.SHARE, Resources.OPTIMIZE));
  }

  @AfterEach
  public void tearDown() {
    authRule.disableAuthorization();
    authRule.deleteUsersAndGroups();
  }
}
