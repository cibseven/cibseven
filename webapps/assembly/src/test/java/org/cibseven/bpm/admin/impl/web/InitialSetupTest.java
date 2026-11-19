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
package org.cibseven.bpm.admin.impl.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.cibseven.bpm.engine.authorization.Authorization.ANY;
import static org.cibseven.bpm.engine.authorization.Authorization.AUTH_TYPE_GRANT;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.ws.rs.core.Response.Status;

import org.cibseven.bpm.cockpit.Cockpit;
import org.cibseven.bpm.cockpit.impl.DefaultCockpitRuntimeDelegate;
import org.cibseven.bpm.engine.AuthorizationService;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.authorization.Authorization;
import org.cibseven.bpm.engine.authorization.Groups;
import org.cibseven.bpm.engine.authorization.Permissions;
import org.cibseven.bpm.engine.authorization.Resources;
import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.User;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.identity.ReadOnlyIdentityProvider;
import org.cibseven.bpm.engine.impl.identity.db.DbReadOnlyIdentityServiceProvider;
import org.cibseven.bpm.engine.impl.interceptor.Session;
import org.cibseven.bpm.engine.impl.interceptor.SessionFactory;
import org.cibseven.bpm.engine.rest.dto.identity.UserCredentialsDto;
import org.cibseven.bpm.engine.rest.dto.identity.UserDto;
import org.cibseven.bpm.engine.rest.dto.identity.UserProfileDto;
import org.cibseven.bpm.engine.rest.exception.InvalidRequestException;
import org.cibseven.bpm.engine.rest.impl.SetupRestServiceImpl;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.webapp.impl.engine.ProcessEnginesFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import tools.jackson.databind.json.JsonMapper;

/**
 * The first-run setup lets anyone create an administrator without logging in (CVE-2026-77226,
 * CIB7-2230). It may only be offered on an engine nobody has configured yet, and both setup
 * endpoints must agree on that: the one of the old Admin app and the one of engine-rest, which
 * the webclient uses.
 */
public class InitialSetupTest {

  @RegisterExtension
  public ProcessEngineRule processEngineRule = new ProcessEngineRule("camunda-test-engine.cfg.xml");

  protected ProcessEngine processEngine;
  protected ProcessEngineConfigurationImpl processEngineConfiguration;
  protected IdentityService identityService;
  protected AuthorizationService authorizationService;
  protected List<String> adminUsers;
  protected List<String> adminGroups;

  @BeforeEach
  public void setUp() {
    processEngine = processEngineRule.getProcessEngine();
    processEngineConfiguration = (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
    identityService = processEngine.getIdentityService();
    authorizationService = processEngine.getAuthorizationService();
    adminUsers = new ArrayList<>(processEngineConfiguration.getAdminUsers());
    adminGroups = new ArrayList<>(processEngineConfiguration.getAdminGroups());

    // the engine is shared with other test classes, so start from an empty identity store
    deleteIdentitiesAndAuthorizations();
    Cockpit.setCockpitRuntimeDelegate(new DefaultCockpitRuntimeDelegate());
  }

  @AfterEach
  public void tearDown() {
    Cockpit.setCockpitRuntimeDelegate(null);
    processEngineConfiguration.setAdminUsers(adminUsers);
    processEngineConfiguration.setAdminGroups(adminGroups);
    deleteIdentitiesAndAuthorizations();
  }

  // configured systems without an administrator in camunda-admin ////////////////////

  @ParameterizedTest
  @EnumSource(SetupEndpoint.class)
  public void shouldRefuseSetupWhenCamundaAdminIsEmptyAndAdminUsersAreConfigured(SetupEndpoint endpoint) {
    // given the case of the CVE: the group exists, the administrator is configured elsewhere
    createUser("root");
    configureAdminUser("root");
    createGroup(Groups.CAMUNDA_ADMIN);

    assertSetupRefused(endpoint, processEngine);
  }

  @ParameterizedTest
  @EnumSource(SetupEndpoint.class)
  public void shouldRefuseSetupWhenAdminUsersAreConfiguredWithoutCamundaAdmin(SetupEndpoint endpoint) {
    createUser("root");
    configureAdminUser("root");

    assertSetupRefused(endpoint, processEngine);
  }

  @ParameterizedTest
  @EnumSource(SetupEndpoint.class)
  public void shouldRefuseSetupWhenAdminGroupsAreConfiguredWithoutCamundaAdmin(SetupEndpoint endpoint) {
    createUser("root");
    createGroup("ops");
    identityService.createMembership("root", "ops");
    configureAdminGroup("ops");

    assertSetupRefused(endpoint, processEngine);
  }

  @ParameterizedTest
  @EnumSource(SetupEndpoint.class)
  public void shouldRefuseSetupWhenUsersExistWithoutAnyAdministrator(SetupEndpoint endpoint) {
    createUser("mary");

    assertSetupRefused(endpoint, processEngine);
  }

  @ParameterizedTest
  @EnumSource(SetupEndpoint.class)
  public void shouldRefuseSetupWhenOnlyAuthorizationsForExternalGroupsExist(SetupEndpoint endpoint) {
    // given users and groups come from a single sign-on, only their rights are in the engine
    Authorization authorization = authorizationService.createNewAuthorization(AUTH_TYPE_GRANT);
    authorization.setGroupId("sso-ops");
    authorization.setResource(Resources.PROCESS_DEFINITION);
    authorization.setResourceId(ANY);
    authorization.addPermission(Permissions.ALL);
    authorizationService.saveAuthorization(authorization);

    assertSetupRefused(endpoint, processEngine);
  }

  @ParameterizedTest
  @EnumSource(SetupEndpoint.class)
  public void shouldRefuseSetupWhenAdminGroupsOfExternalUsersAreConfigured(SetupEndpoint endpoint) {
    // given users and groups come from a single sign-on, the engine has none of them
    configureAdminGroup("sso-admins");

    assertSetupRefused(endpoint, processEngine);
  }

  @ParameterizedTest
  @EnumSource(SetupEndpoint.class)
  public void shouldRefuseSetupWhenTheIdentityServiceIsReadOnly(SetupEndpoint endpoint) {
    // given an engine like one with the LDAP plugin: no user of its own, all users read-only
    ProcessEngine readOnlyEngine = buildEngineWithReadOnlyIdentityService();
    try {
      assertThat(readOnlyEngine.getIdentityService().isReadOnly()).isTrue();

      assertSetupRefused(endpoint, readOnlyEngine);
    } finally {
      readOnlyEngine.close();
    }
  }

  @ParameterizedTest
  @EnumSource(SetupEndpoint.class)
  public void shouldRefuseSetupWhenCamundaAdminHasAMember(SetupEndpoint endpoint) {
    createUser("root");
    createGroup(Groups.CAMUNDA_ADMIN);
    identityService.createMembership("root", Groups.CAMUNDA_ADMIN);

    assertSetupRefused(endpoint, processEngine);
  }

  // an engine nobody has configured yet /////////////////////////////////////////////

  @ParameterizedTest
  @EnumSource(SetupEndpoint.class)
  public void shouldCreateAnAdministratorOnAnUnconfiguredEngine(SetupEndpoint endpoint) {
    assertThat(endpoint.offersSetup(processEngine)).isTrue();

    endpoint.createInitialUser(processEngine, newUser("admin"));

    assertThat(identityService.createUserQuery().userId("admin").memberOfGroup(Groups.CAMUNDA_ADMIN).count())
        .isEqualTo(1);
    assertThat(authorizationService.createAuthorizationQuery()
        .groupIdIn(Groups.CAMUNDA_ADMIN).resourceId(ANY).count())
        .isEqualTo(Resources.values().length);
  }

  @ParameterizedTest
  @EnumSource(SetupEndpoint.class)
  public void shouldRefuseASecondSetup(SetupEndpoint endpoint) {
    endpoint.createInitialUser(processEngine, newUser("admin"));

    assertSetupRefused(endpoint, processEngine);
  }

  // the two setup endpoints /////////////////////////////////////////////////////////

  public enum SetupEndpoint {

    /** POST /camunda/api/admin/setup/{engine}/user/create, and the redirect to its page */
    ADMIN_APP {
      boolean offersSetup(ProcessEngine engine) {
        return new SetupPageProbe().showsSetupPage(engine.getName());
      }

      void createInitialUser(ProcessEngine engine, UserDto user) {
        new SetupResource().createInitialUserInternal(engine.getName(), user, engine);
      }
    },

    /** GET /engine-rest/setup/status and POST /engine-rest/setup/user/create */
    ENGINE_REST {
      boolean offersSetup(ProcessEngine engine) {
        return new SetupRestServiceImpl(engine.getName(), new JsonMapper()).requiresSetup();
      }

      void createInitialUser(ProcessEngine engine, UserDto user) {
        new SetupRestServiceImpl(engine.getName(), new JsonMapper()).createUser(user);
      }
    };

    abstract boolean offersSetup(ProcessEngine engine);

    abstract void createInitialUser(ProcessEngine engine, UserDto user);
  }

  protected static class SetupPageProbe extends ProcessEnginesFilter {
    boolean showsSetupPage(String engineName) {
      try {
        return needsInitialUser(engineName);
      } catch (IOException | ServletException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  // helpers /////////////////////////////////////////////////////////////////////////

  protected void assertSetupRefused(SetupEndpoint endpoint, ProcessEngine engine) {
    assertThatThrownBy(() -> endpoint.createInitialUser(engine, newUser("eve")))
        .isInstanceOfSatisfying(InvalidRequestException.class,
            e -> assertThat(e.getStatus()).isEqualTo(Status.FORBIDDEN));
    assertThat(engine.getIdentityService().createUserQuery().userId("eve").count())
        .as("users created by the setup")
        .isZero();
    assertThat(endpoint.offersSetup(engine)).as("setup offered").isFalse();
  }

  protected ProcessEngine buildEngineWithReadOnlyIdentityService() {
    ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
        .createStandaloneInMemProcessEngineConfiguration()
        .setProcessEngineName("read-only-identity-engine")
        .setJdbcUrl("jdbc:h2:mem:InitialSetupTestReadOnlyIdentity;DB_CLOSE_DELAY=1000")
        .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_CREATE_DROP)
        .setJobExecutorActivate(false);
    configuration.setDbMetricsReporterActivate(false);
    configuration.setIdentityProviderSessionFactory(new SessionFactory() {
      public Class<?> getSessionType() {
        return ReadOnlyIdentityProvider.class;
      }

      public Session openSession() {
        return new DbReadOnlyIdentityServiceProvider();
      }
    });
    return configuration.buildProcessEngine();
  }

  protected static UserDto newUser(String id) {
    UserProfileDto profile = new UserProfileDto();
    profile.setId(id);
    profile.setFirstName(id);
    profile.setLastName(id);
    UserCredentialsDto credentials = new UserCredentialsDto();
    credentials.setPassword(id + "-password");
    UserDto user = new UserDto();
    user.setProfile(profile);
    user.setCredentials(credentials);
    return user;
  }

  protected void createUser(String id) {
    User user = identityService.newUser(id);
    user.setPassword(id + "-password");
    identityService.saveUser(user);
  }

  protected void createGroup(String id) {
    Group group = identityService.newGroup(id);
    identityService.saveGroup(group);
  }

  protected void configureAdminUser(String userId) {
    List<String> users = new ArrayList<>(adminUsers);
    users.add(userId);
    processEngineConfiguration.setAdminUsers(users);
  }

  protected void configureAdminGroup(String groupId) {
    List<String> groups = new ArrayList<>(adminGroups);
    groups.add(groupId);
    processEngineConfiguration.setAdminGroups(groups);
  }

  protected void deleteIdentitiesAndAuthorizations() {
    for (User user : identityService.createUserQuery().list()) {
      identityService.deleteUser(user.getId());
    }
    for (Group group : identityService.createGroupQuery().list()) {
      identityService.deleteGroup(group.getId());
    }
    for (Authorization authorization : authorizationService.createAuthorizationQuery().list()) {
      authorizationService.deleteAuthorization(authorization.getId());
    }
  }
}
