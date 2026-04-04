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
package org.cibseven.bpm.identity.impl.scim;

import static org.assertj.core.api.Assertions.assertThat;

import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.identity.scim.util.ScimTestEnvironment;
import org.cibseven.bpm.identity.scim.util.ScimTestEnvironmentRule;
import org.cibseven.bpm.identity.impl.scim.plugin.ScimIdentityProviderPlugin;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests for SCIM user queries.
 */
public class ScimUserModifyTest {

  @ClassRule
  public static ScimTestEnvironmentRule scimRule = new ScimTestEnvironmentRule();

  @Rule
  public ProcessEngineRule engineRule = new ProcessEngineRule("camunda.modify.cfg.xml");

  ProcessEngineConfiguration processEngineConfiguration;
  IdentityService identityService;
  ScimTestEnvironment scimTestEnvironment;

  protected static ProcessEngineConfigurationImpl buildProcessEngineConfiguration() {
    ProcessEngineConfigurationImpl config = (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
        .createStandaloneInMemProcessEngineConfiguration()
        .setJdbcUrl("jdbc:h2:mem:ScimIdentityServiceTest;DB_CLOSE_DELAY=-1")
        .setDatabaseSchemaUpdate("true");

    // Note: The SCIM plugin will be configured in setup() using the actual server URL
    return config;
  }

  @Before
  public void setup() {
    processEngineConfiguration = engineRule.getProcessEngineConfiguration();
    identityService = engineRule.getIdentityService();
    scimTestEnvironment = scimRule.getScimTestEnvironment();
    
    // Configure SCIM plugin with actual test server URL
    ScimIdentityProviderPlugin scimPlugin = new ScimIdentityProviderPlugin();
    scimPlugin.setServerUrl(scimTestEnvironment.getServerUrl());
    scimPlugin.setAuthenticationType("bearer");
    scimPlugin.setBearerToken("test-token");
    
    scimPlugin.setUserIdAttribute("userName");
    scimPlugin.setUserFirstnameAttribute("name.givenName");
    scimPlugin.setUserLastnameAttribute("name.familyName");
    
    scimPlugin.setGroupIdAttribute("id");
    scimPlugin.setGroupNameAttribute("displayName");
    
    scimPlugin.setAuthorizationCheckEnabled(false);

    scimPlugin.preInit((ProcessEngineConfigurationImpl) processEngineConfiguration);
  }

  @Test
  public void testCreateUser() {
    assertThat(!identityService.isReadOnly());
    ScimUserEntity user = (ScimUserEntity) identityService.newUser("oscar");
    user.setFirstName("Oscar");
    user.setLastName("The Crouch");
    user.setEmail("oscar@camunda.org");
    identityService.saveUser(user);
  }

  @Test
  public void testUpdateUser() {
    assertThat(!identityService.isReadOnly());
    ScimUserEntity user = (ScimUserEntity) identityService.newUser("oscar");
    user.setScimId("user-oscar");
    user.setFirstName("Oscar");
    user.setLastName("The (Even Cleaner) Crouch");
    user.setEmail("oscar@camunda.org");
    identityService.saveUser(user);
  }

  @Test
  public void testDeleteUser() {
    assertThat(!identityService.isReadOnly());  
    identityService.deleteUser("oscar");
  }
}
