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
package org.cibseven.bpm.webapp.impl.security.auth;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.List;
import org.cibseven.bpm.engine.AuthorizationService;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.authorization.Authorization;
import org.cibseven.bpm.engine.authorization.Permissions;
import org.cibseven.bpm.engine.authorization.Resources;
import org.cibseven.bpm.engine.identity.User;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.util.ClockUtil;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.commons.testing.ProcessEngineLoggingRule;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

public class UserAuthenticationResourceLoggingTest {

  @Rule
  public ProcessEngineRule processEngineRule = new ProcessEngineRule("camunda-test-engine.cfg.xml");
  @Rule
  public ProcessEngineLoggingRule loggingRule = new ProcessEngineLoggingRule().watch("org.cibseven.bpm.webapp")
      .level(Level.INFO);

  protected ProcessEngine processEngine;
  protected ProcessEngineConfigurationImpl processEngineConfiguration;
  protected IdentityService identityService;
  protected AuthorizationService authorizationService;

  protected boolean authorizationEnabledInitialValue;
  protected boolean webappsAuthenticationLoggingEnabledInitialValue;

  @Before
  public void setUp() {
    this.processEngine = processEngineRule.getProcessEngine();
    this.processEngineConfiguration = (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
    this.identityService = processEngine.getIdentityService();
    this.authorizationService = processEngine.getAuthorizationService();

    authorizationEnabledInitialValue = processEngineConfiguration.isAuthorizationEnabled();
    webappsAuthenticationLoggingEnabledInitialValue = processEngineConfiguration.isWebappsAuthenticationLoggingEnabled();
  }

  @After
  public void tearDown() {
    ClockUtil.reset();
    processEngineConfiguration.setAuthorizationEnabled(authorizationEnabledInitialValue);
    processEngineConfiguration.setWebappsAuthenticationLoggingEnabled(webappsAuthenticationLoggingEnabledInitialValue);

    for (User user : identityService.createUserQuery().list()) {
      identityService.deleteUser(user.getId());
    }
    for (Authorization authorization : authorizationService.createAuthorizationQuery().list()) {
      authorizationService.deleteAuthorization(authorization.getId());
    }

    Authentications.clearCurrent();
  }

  @Test
  public void shouldProduceLogStatementOnValidLogin() {
    // given
    User jonny = identityService.newUser("jonny");
    jonny.setPassword("jonnyspassword");
    identityService.saveUser(jonny);

    UserAuthenticationResource authResource = new UserAuthenticationResource();
    authResource.request = new MockHttpServletRequest();

    processEngineConfiguration.setWebappsAuthenticationLoggingEnabled(true);

    // when
    authResource.doLogin("webapps-test-engine", "tasklist", "jonny", "jonnyspassword");

    // then
    List<ILoggingEvent> filteredLog = loggingRule.getFilteredLog("jonny");
    assertThat(filteredLog).hasSize(1);
    assertThat(filteredLog.get(0).getFormattedMessage()).contains("Successful login for user jonny");
  }

  @Test
  public void shouldNotProduceLogStatementOnValidLoginWhenDisabled() {
    // given
    User jonny = identityService.newUser("jonny");
    jonny.setPassword("jonnyspassword");
    identityService.saveUser(jonny);

    UserAuthenticationResource authResource = new UserAuthenticationResource();
    authResource.request = new MockHttpServletRequest();

    processEngineConfiguration.setWebappsAuthenticationLoggingEnabled(false);

    // when
    authResource.doLogin("webapps-test-engine", "tasklist", "jonny", "jonnyspassword");

    // then
    List<ILoggingEvent> filteredLog = loggingRule.getFilteredLog("jonny");
    assertThat(filteredLog).hasSize(0);
  }

  @Test
  public void shouldProduceLogStatementOnInvalidLogin() {
    // given
    User jonny = identityService.newUser("jonny");
    jonny.setPassword("jonnyspassword");
    identityService.saveUser(jonny);

    UserAuthenticationResource authResource = new UserAuthenticationResource();
    authResource.request = new MockHttpServletRequest();

    processEngineConfiguration.setWebappsAuthenticationLoggingEnabled(true);

    // when
    authResource.doLogin("webapps-test-engine", "tasklist", "jonny", "NOT_jonnyspassword");

    // then
    List<ILoggingEvent> filteredLog = loggingRule.getFilteredLog("jonny");
    assertThat(filteredLog).hasSize(1);
    assertThat(filteredLog.get(0).getFormattedMessage()).contains("Failed login attempt for user jonny. Reason: bad credentials");
  }

  @Test
  public void shouldNotProduceLogStatementOnInvalidLoginWhenDisabled() {
    // given
    User jonny = identityService.newUser("jonny");
    jonny.setPassword("jonnyspassword");
    identityService.saveUser(jonny);

    UserAuthenticationResource authResource = new UserAuthenticationResource();
    authResource.request = new MockHttpServletRequest();

    processEngineConfiguration.setWebappsAuthenticationLoggingEnabled(false);

    // when
    authResource.doLogin("webapps-test-engine", "tasklist", "jonny", "NOT_jonnyspassword");

    // then
    List<ILoggingEvent> filteredLog = loggingRule.getFilteredLog("jonny");
    assertThat(filteredLog).hasSize(0);
  }

  @Test
  public void shouldProduceLogStatementOnLogout() {
    // given
    User jonny = identityService.newUser("jonny");
    jonny.setPassword("jonnyspassword");
    identityService.saveUser(jonny);
    setAuthentication("jonny", "webapps-test-engine");

    UserAuthenticationResource authResource = new UserAuthenticationResource();
    authResource.request = new MockHttpServletRequest();

    processEngineConfiguration.setWebappsAuthenticationLoggingEnabled(true);

    // when
    authResource.doLogout("webapps-test-engine");

    // then
    List<ILoggingEvent> filteredLog = loggingRule.getFilteredLog("jonny");
    assertThat(filteredLog).hasSize(1);
    assertThat(filteredLog.get(0).getFormattedMessage()).contains("Successful logout for user jonny");
  }

  @Test
  public void shouldNotProduceLogStatementOnLogoutWhenDisabled() {
    // given
    User jonny = identityService.newUser("jonny");
    jonny.setPassword("jonnyspassword");
    identityService.saveUser(jonny);
    setAuthentication("jonny", "webapps-test-engine");

    UserAuthenticationResource authResource = new UserAuthenticationResource();
    authResource.request = new MockHttpServletRequest();

    processEngineConfiguration.setWebappsAuthenticationLoggingEnabled(false);

    // when
    authResource.doLogout("webapps-test-engine");

    // then
    List<ILoggingEvent> filteredLog = loggingRule.getFilteredLog("jonny");
    assertThat(filteredLog).hasSize(0);
  }

  @Test
  public void shouldNotProduceLogStatementOnLogoutWhenNoAuthentication() {
    // given
    UserAuthenticationResource authResource = new UserAuthenticationResource();
    authResource.request = new MockHttpServletRequest();

    processEngineConfiguration.setWebappsAuthenticationLoggingEnabled(true);

    // when
    authResource.doLogout("webapps-test-engine");

    // then
    List<ILoggingEvent> filteredLog = loggingRule.getFilteredLog("jonny");
    assertThat(filteredLog).hasSize(0);
  }

  @Test
  public void shouldProduceLogStatementOnLoginWhenAuthorized() {
    // given
    User jonny = identityService.newUser("jonny");
    jonny.setPassword("jonnyspassword");
    identityService.saveUser(jonny);

    Authorization authorization = authorizationService.createNewAuthorization(Authorization.AUTH_TYPE_GRANT);
    authorization.setResource(Resources.APPLICATION);
    authorization.setResourceId("tasklist");
    authorization.setPermissions(new Permissions[] {Permissions.ACCESS});
    authorization.setUserId(jonny.getId());
    authorizationService.saveAuthorization(authorization);

    processEngineConfiguration.setAuthorizationEnabled(true);
    processEngineConfiguration.setWebappsAuthenticationLoggingEnabled(true);

    UserAuthenticationResource authResource = new UserAuthenticationResource();
    authResource.request = new MockHttpServletRequest();

    // when
    authResource.doLogin("webapps-test-engine", "tasklist", "jonny", "jonnyspassword");

    // then
    List<ILoggingEvent> filteredLog = loggingRule.getFilteredLog("jonny");
    assertThat(filteredLog).hasSize(1);
    assertThat(filteredLog.get(0).getFormattedMessage()).contains("Successful login for user jonny");
  }

  @Test
  public void shouldProduceLogStatementOnLoginWhenNotAuthorized() {
    // given
    User jonny = identityService.newUser("jonny");
    jonny.setPassword("jonnyspassword");
    identityService.saveUser(jonny);

    processEngineConfiguration.setAuthorizationEnabled(true);
    processEngineConfiguration.setWebappsAuthenticationLoggingEnabled(true);

    UserAuthenticationResource authResource = new UserAuthenticationResource();
    authResource.request = new MockHttpServletRequest();

    // when
    authResource.doLogin("webapps-test-engine", "tasklist", "jonny", "jonnyspassword");

    // then
    List<ILoggingEvent> filteredLog = loggingRule.getFilteredLog("jonny");
    assertThat(filteredLog).hasSize(1);
    assertThat(filteredLog.get(0).getFormattedMessage()).contains("Failed login attempt for user jonny. Reason: not authorized");
  }

  @Test
  public void shouldNotProduceLogStatementOnLoginWhenNotAuthorizedAndWebappsLoggingDisabled() {
    // given
    User jonny = identityService.newUser("jonny");
    jonny.setPassword("jonnyspassword");
    identityService.saveUser(jonny);

    processEngineConfiguration.setAuthorizationEnabled(true);
    processEngineConfiguration.setWebappsAuthenticationLoggingEnabled(false);

    UserAuthenticationResource authResource = new UserAuthenticationResource();
    authResource.request = new MockHttpServletRequest();

    // when
    authResource.doLogin("webapps-test-engine", "tasklist", "jonny", "jonnyspassword");

    // then
    List<ILoggingEvent> filteredLog = loggingRule.getFilteredLog("jonny");
    assertThat(filteredLog).hasSize(0);
  }

  protected void setAuthentication(String user, String engineName) {
    Authentications authentications = new Authentications();
    authentications.addOrReplace(new UserAuthentication(user, engineName));
    Authentications.setCurrent(authentications);
  }

}