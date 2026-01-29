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

import java.util.List;

import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.GroupQuery;
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
 * Tests for SCIM group queries.
 */
public class ScimGroupQueryTest {

  @ClassRule
  public static ScimTestEnvironmentRule scimRule = new ScimTestEnvironmentRule();

  @Rule
  public ProcessEngineRule engineRule = new ProcessEngineRule();

  ProcessEngineConfiguration processEngineConfiguration;
  IdentityService identityService;
  ScimTestEnvironment scimTestEnvironment;

  protected static ProcessEngineConfigurationImpl buildProcessEngineConfiguration() {
    ProcessEngineConfigurationImpl config = (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
        .createStandaloneInMemProcessEngineConfiguration()
        .setJdbcUrl("jdbc:h2:mem:scim-group-test;DB_CLOSE_DELAY=-1")
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
    scimPlugin.setGroupIdAttribute("displayName");
    scimPlugin.setGroupNameAttribute("displayName");
    scimPlugin.setAuthorizationCheckEnabled(false);

    scimPlugin.preInit((ProcessEngineConfigurationImpl) processEngineConfiguration);
  }

  @Test
  public void testCountGroups() {
    // when
    GroupQuery groupQuery = identityService.createGroupQuery();

    // then
    long count = groupQuery.count();
    assertThat(count).isEqualTo(scimTestEnvironment.getTotalNumberOfGroupsCreated());
  }

  @Test
  public void testQueryNoFilter() {
    // when
    List<Group> result = identityService.createGroupQuery().list();

    // then
    assertThat(result).hasSize(scimTestEnvironment.getTotalNumberOfGroupsCreated());
  }

  @Test
  public void testFilterByGroupId() {
    // when
    Group group = identityService.createGroupQuery().groupId("group-development").singleResult();

    // then
    assertThat(group).isNotNull();
    assertThat(group.getId()).isEqualTo("group-development");
    assertThat(group.getName()).isEqualTo("development");
  }

  @Test
  public void testFilterByNonexistentGroupId() {
    // when
    Group group = identityService.createGroupQuery().groupId("non-existing").singleResult();

    // then
    assertThat(group).isNull();
  }

  @Test
  public void testFilterByGroupName() {
    // when
    Group group = identityService.createGroupQuery().groupName("management").singleResult();

    // then
    assertThat(group).isNotNull();
    assertThat(group.getId()).isEqualTo("group-management");
    assertThat(group.getName()).isEqualTo("management");
  }

  @Test
  public void testFilterGroupsByMemberId() {
    // when
    List<Group> groups = identityService.createGroupQuery().groupMember("oscar").list();

    // then
    assertThat(groups).hasSize(1);
    assertThat(groups.get(0).getId()).isEqualTo("group-development");
  }

  @Test
  public void testFilterGroupsByMemberIdMultipleGroups() {
    // when - daniel is member of both groups
    List<Group> groups = identityService.createGroupQuery().groupMember("daniel").list();

    // then
    assertThat(groups).hasSize(2);
    assertThat(groups).extracting("id").containsOnly("group-development", "group-management");
  }

  @Test
  public void testFindGroupById() {
    // when
    Group group = identityService.createGroupQuery().groupId("group-development").singleResult();

    // then
    assertThat(group).isNotNull();
    assertThat(group.getId()).isEqualTo("group-development");
    assertThat(group.getName()).isEqualTo("development");
  }

  @Test
  public void testFindGroupByName() {
    // when
    Group group = identityService.createGroupQuery().groupName("development").singleResult();

    // then
    assertThat(group).isNotNull();
    assertThat(group.getId()).isEqualTo("group-development");
    assertThat(group.getName()).isEqualTo("development");
  }
}
