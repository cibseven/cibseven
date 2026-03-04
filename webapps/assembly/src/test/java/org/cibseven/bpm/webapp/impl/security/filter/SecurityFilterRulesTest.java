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
package org.cibseven.bpm.webapp.impl.security.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.cibseven.bpm.cockpit.Cockpit;
import org.cibseven.bpm.cockpit.impl.DefaultCockpitRuntimeDelegate;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.impl.util.IoUtil;
import org.cibseven.bpm.webapp.impl.security.auth.Authentication;
import org.cibseven.bpm.webapp.impl.security.auth.Authentications;
import org.cibseven.bpm.webapp.impl.security.auth.UserAuthentication;
import org.cibseven.bpm.webapp.impl.security.filter.util.FilterRules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

/**
 *
 * @author nico.rehwaldt
 */

public class SecurityFilterRulesTest {

  public static final String FILTER_RULES_FILE = "src/main/webapp/WEB-INF/securityFilterRules.json";

  protected static final String EMPTY_PATH = "";
  protected static final String CUSTOM_APP_PATH = "/my-custom/application/path";

  public static List<SecurityFilterRule> FILTER_RULES;

  public static Authentication NO_AUTHENTICATION = null;
  public static Authentication LOGGED_IN_USER = new Authentication("user", "default");

  public static final String TESTUSER_ID = "testuser";


  public static Collection<String> data() {
    return Arrays.asList(EMPTY_PATH, CUSTOM_APP_PATH);
  }


  @BeforeEach
  public void createEngine() throws Exception
  {
    final ProcessEngine engine = Mockito.mock(ProcessEngine.class);

    Cockpit.setCockpitRuntimeDelegate(new DefaultCockpitRuntimeDelegate() {

      @Override
      public ProcessEngine getProcessEngine(String processEngineName) {
        if ("default".equals(processEngineName)) {
          return engine;
        }
        else {
          return null;
        }
      }
    });
  }

  @AfterEach
  public void after() {
    Authentications.setCurrent(null);
    Cockpit.setCockpitRuntimeDelegate(null);
  }

  @ParameterizedTest
  @MethodSource("data")
  public void shouldHaveRulesLoaded(String applicationPath) throws Exception {
    FILTER_RULES = loadFilterRules(applicationPath);
    assertThat(FILTER_RULES).hasSize(1);
  }


  @ParameterizedTest
  @MethodSource("data")
  public void shouldPassPasswordPolicy(String applicationPath) throws IOException {
    FILTER_RULES = loadFilterRules(applicationPath);
    assertThat(isAuthorized("GET",
      applicationPath + "/api/engine/engine/default/identity/password-policy")).isTrue();
    assertThat(isAuthorized("POST",
      applicationPath + "/api/engine/engine/default/identity/password-policy")).isTrue();
  }

  @ParameterizedTest
  @MethodSource("data")
  public void shouldPassStaticCockpitPluginResources_GET(String applicationPath) throws Exception {
    FILTER_RULES = loadFilterRules(applicationPath);
    assertThat(isAuthorized("GET",
      applicationPath + "/api/cockpit/plugin/some-plugin/static/foo.html")).isTrue();
    assertThat(isAuthorized("GET",
      applicationPath + "/api/cockpit/plugin/bar/static/foo.html")).isTrue();
  }

  @ParameterizedTest
  @MethodSource("data")
  public void shouldRejectEngineApi_GET(String applicationPath) throws Exception {
    FILTER_RULES = loadFilterRules(applicationPath);

    authenticatedForEngine("otherEngine", new Runnable() {
      @Override
      public void run() {

        Authorization authorization =
          getAuthorization("POST", applicationPath + "/api/engine/engine/default/bar");

        assertThat(authorization.isGranted()).isFalse();
        assertThat(authorization.isAuthenticated()).isFalse();
      }
    });
  }

  @ParameterizedTest
  @MethodSource("data")
  public void shouldGrantEngineApi_GET(String applicationPath) throws Exception {
    FILTER_RULES = loadFilterRules(applicationPath);

    authenticatedForEngine("default", new Runnable() {
      @Override
      public void run() {

        Authorization authorization =
          getAuthorization("POST", applicationPath + "/api/engine/engine/default/bar");

        assertThat(authorization.isGranted()).isTrue();
        assertThat(authorization.isAuthenticated()).isTrue();
      }
    });
  }

  @ParameterizedTest
  @MethodSource("data")
  public void shouldRejectCockpitPluginApi_GET(String applicationPath) throws Exception {
    FILTER_RULES = loadFilterRules(applicationPath);

    authenticatedForEngine("otherEngine", new Runnable() {
      @Override
      public void run() {

        Authorization authorization = getAuthorization("POST",
          applicationPath + "/api/cockpit/plugin/" +
            "reporting-process-count/default/process-instance-count");

        assertThat(authorization.isGranted()).isFalse();
        assertThat(authorization.isAuthenticated()).isFalse();
      }
    });
  }

  @ParameterizedTest
  @MethodSource("data")
  public void shouldPassCockpitPluginApi_GET_LOGGED_IN(String applicationPath) throws Exception {
    FILTER_RULES = loadFilterRules(applicationPath);
    authenticatedForEngine("default", new Runnable() {
      @Override
      public void run() {

        Authorization authorization =
          getAuthorization("POST",
            applicationPath + "/api/cockpit/plugin/" +
              "reporting-process-count/default/process-instance-count");

        assertThat(authorization.isGranted()).isTrue();
        assertThat(authorization.isAuthenticated()).isTrue();
      }
    });
  }

  @ParameterizedTest
  @MethodSource("data")
  public void shouldPassCockpit_GET_LOGGED_OUT(String applicationPath) throws Exception {
    FILTER_RULES = loadFilterRules(applicationPath);

    Authorization authorization =
      getAuthorization("GET", applicationPath + "/app/cockpit/non-existing-engine/foo");

    assertThat(authorization.isGranted()).isTrue();
    assertThat(authorization.isAuthenticated()).isFalse();
  }

  @ParameterizedTest
  @MethodSource("data")
  public void shouldPassCockpit_GET_LOGGED_IN(String applicationPath) throws Exception {
    FILTER_RULES = loadFilterRules(applicationPath);

    authenticatedForApp("default", "cockpit", new Runnable() {

      @Override
      public void run() {
        Authorization authorization =
          getAuthorization("GET", applicationPath + "/app/cockpit/default/");

        assertThat(authorization.isGranted()).isTrue();
        assertThat(authorization.isAuthenticated()).isTrue();
      }
    });
  }

  @ParameterizedTest
  @MethodSource("data")
  public void shouldPassCockpitNonExistingEngine_GET_LOGGED_IN(String applicationPath) throws Exception {
    FILTER_RULES = loadFilterRules(applicationPath);

    authenticatedForApp("default", "cockpit", new Runnable() {

      @Override
      public void run() {
        Authorization authorization =
          getAuthorization("GET", applicationPath + "/app/cockpit/non-existing-engine/");

        assertThat(authorization.isGranted()).isTrue();
        assertThat(authorization.isAuthenticated()).isFalse();
      }
    });
  }


  @ParameterizedTest
  @MethodSource("data")
  public void shouldRejectTasklistApi_GET(String applicationPath) throws Exception {
    FILTER_RULES = loadFilterRules(applicationPath);

    authenticatedForEngine("otherEngine", new Runnable() {
      @Override
      public void run() {

        Authorization authorization =
          getAuthorization("POST",
            applicationPath + "/api/tasklist/plugin/example-plugin/default/example-resource");

        assertThat(authorization.isGranted()).isFalse();
        assertThat(authorization.isAuthenticated()).isFalse();
      }
    });
  }

  @ParameterizedTest
  @MethodSource("data")
  public void shouldPassTasklistApi_GET_LOGGED_IN(String applicationPath) throws Exception {
    FILTER_RULES = loadFilterRules(applicationPath);
    authenticatedForEngine("default", new Runnable() {
      @Override
      public void run() {

        Authorization authorization =
          getAuthorization("POST",
            applicationPath + "/api/tasklist/plugin/example-plugin/default/example-resource");

        assertThat(authorization.isGranted()).isTrue();
        assertThat(authorization.isAuthenticated()).isTrue();
      }
    });
  }

  @ParameterizedTest
  @MethodSource("data")
  public void shouldRejectTasklistApi_GET_LOGGED_OUT(String applicationPath) throws Exception {
    FILTER_RULES = loadFilterRules(applicationPath);
    Authorization authorization =
      getAuthorization("POST",
        applicationPath + "/api/tasklist/plugin/example-plugin/default/example-resource");

    assertThat(authorization.isGranted()).isFalse();
    assertThat(authorization.isAuthenticated()).isFalse();
  }

  @ParameterizedTest
  @MethodSource("data")
  public void shouldPassTasklistPluginResource_GET_LOGGED_IN(String applicationPath) throws Exception {
    FILTER_RULES = loadFilterRules(applicationPath);

    authenticatedForEngine("default", new Runnable() {
      @Override
      public void run() {

        Authorization authorization =
          getAuthorization("GET",
            applicationPath + "/api/tasklist/plugin/example-plugin/static/example-resource");

        assertThat(authorization.isGranted()).isTrue();
        assertThat(authorization.isAuthenticated()).isFalse();
      }
    });
  }

  @ParameterizedTest
  @MethodSource("data")
  public void shouldPassTasklistPluginResource_GET_LOGGED_OUT(String applicationPath) throws Exception {
    FILTER_RULES = loadFilterRules(applicationPath);

    Authorization authorization =
      getAuthorization("GET",
        applicationPath + "/api/tasklist/plugin/example-plugin/static/example-resource");

    assertThat(authorization.isGranted()).isTrue();
    assertThat(authorization.isAuthenticated()).isFalse();
  }


  @ParameterizedTest
  @MethodSource("data")
  public void shouldPassTasklist_GET_LOGGED_OUT(String applicationPath) throws Exception {
    FILTER_RULES = loadFilterRules(applicationPath);

    Authorization authorization =
      getAuthorization("GET", applicationPath + "/app/tasklist/non-existing-engine");

    assertThat(authorization.isGranted()).isTrue();
    assertThat(authorization.isAuthenticated()).isFalse();
  }

  @ParameterizedTest
  @MethodSource("data")
  public void shouldPassTasklist_GET_LOGGED_IN(String applicationPath) throws Exception {
    FILTER_RULES = loadFilterRules(applicationPath);

    authenticatedForApp("default", "tasklist", new Runnable() {

      @Override
      public void run() {
        Authorization authorization =
          getAuthorization("GET", applicationPath + "/app/tasklist/default/");

        assertThat(authorization.isGranted()).isTrue();
        assertThat(authorization.isAuthenticated()).isTrue();
      }
    });
  }

  @ParameterizedTest
  @MethodSource("data")
  public void shouldRejectAdminApi_GET_LOGGED_OUT(String applicationPath) throws Exception {
    FILTER_RULES = loadFilterRules(applicationPath);

    Authorization authorization =
      getAuthorization("GET", applicationPath + "/api/admin/auth/user/some-engine/");

    assertThat(authorization.isGranted()).isFalse();
    assertThat(authorization.isAuthenticated()).isFalse();

    authorization =
      getAuthorization("GET", applicationPath + "/api/admin/setup/some-engine/");

    assertThat(authorization.isGranted()).isFalse();
    assertThat(authorization.isAuthenticated()).isFalse();
  }

  @ParameterizedTest
  @MethodSource("data")
  public void shouldPassAdminApi_GET_LOGGED_IN(String applicationPath) throws Exception {
    FILTER_RULES = loadFilterRules(applicationPath);

    authenticatedForApp("default", "admin", new Runnable() {

      @Override
      public void run() {
        Authorization authorization =
          getAuthorization("GET", applicationPath + "/api/admin/foo/");

        assertThat(authorization.isGranted()).isTrue();
        assertThat(authorization.isAuthenticated()).isFalse();
      }
    });
  }

  @ParameterizedTest
  @MethodSource("data")
  public void shouldPassAdminApi_AnonymousEndpoints_LOGGED_OUT(String applicationPath) throws Exception {
    FILTER_RULES = loadFilterRules(applicationPath);

    Authorization authorization =
      getAuthorization("GET", applicationPath + "/api/admin/auth/user/bar");

    assertThat(authorization.isGranted()).isTrue();
    assertThat(authorization.isAuthenticated()).isFalse();

    authorization =
      getAuthorization("POST", applicationPath + "/api/admin/auth/user/bar/logout");

    assertThat(authorization.isGranted()).isTrue();
    assertThat(authorization.isAuthenticated()).isFalse();

    authorization =
      getAuthorization("POST", applicationPath + "/api/admin/auth/user/bar/login/some-app");

    assertThat(authorization.isGranted()).isTrue();
    assertThat(authorization.isAuthenticated()).isFalse();

    authorization =
      getAuthorization("POST", applicationPath + "/api/admin/setup/some-engine/user/create");

    assertThat(authorization.isGranted()).isTrue();
    assertThat(authorization.isAuthenticated()).isFalse();
  }


  @ParameterizedTest
  @MethodSource("data")
  public void shouldRejectAdminApiPlugin_GET_LOGGED_OUT(String applicationPath) throws Exception {
    FILTER_RULES = loadFilterRules(applicationPath);

    Authorization authorization =
      getAuthorization("GET",
        applicationPath + "/api/admin/plugin/adminPlugins/some-engine/endpoint");

    assertThat(authorization.isGranted()).isFalse();
    assertThat(authorization.isAuthenticated()).isFalse();
  }

  @ParameterizedTest
  @MethodSource("data")
  public void shouldPassAdminApiPlugin_GET_LOGGED_IN(String applicationPath) throws Exception {
    FILTER_RULES = loadFilterRules(applicationPath);

    authenticatedForApp("default", "admin", new Runnable() {

      @Override
      public void run() {
        Authorization authorization =
          getAuthorization("GET",
            applicationPath + "/api/admin/plugin/adminPlugins/some-engine");

        assertThat(authorization.isGranted()).isTrue();
        assertThat(authorization.isAuthenticated()).isFalse();
      }
    });
  }

  @ParameterizedTest
  @MethodSource("data")
  public void shouldPassAdmin_GET_LOGGED_OUT(String applicationPath) throws Exception {
    FILTER_RULES = loadFilterRules(applicationPath);

    Authorization authorization =
      getAuthorization("GET", applicationPath + "/app/admin/default");

    assertThat(authorization.isGranted()).isTrue();
    assertThat(authorization.isAuthenticated()).isFalse();
  }

  @ParameterizedTest
  @MethodSource("data")
  public void shouldPassAdmin_GET_LOGGED_IN(String applicationPath) throws Exception {
    FILTER_RULES = loadFilterRules(applicationPath);

    authenticatedForApp("default", "admin", new Runnable() {

      @Override
      public void run() {
        Authorization authorization =
          getAuthorization("GET", applicationPath + "/app/admin/default/");

        assertThat(authorization.isGranted()).isTrue();
        assertThat(authorization.isAuthenticated()).isTrue();
      }
    });
  }


  @ParameterizedTest
  @MethodSource("data")
  public void shouldPassAdminResources_GET_LOGGED_OUT(String applicationPath) throws Exception {
    FILTER_RULES = loadFilterRules(applicationPath);
    Authorization authorization =
      getAuthorization("GET", applicationPath + "/app/admin/scripts");

    assertThat(authorization.isGranted()).isTrue();
    assertThat(authorization.isAuthenticated()).isFalse();
  }

  @ParameterizedTest
  @MethodSource("data")
  public void shouldPassAdminResources_GET_LOGGED_IN(String applicationPath) throws Exception {
    FILTER_RULES = loadFilterRules(applicationPath);
    authenticatedForApp("default", "admin", new Runnable() {

      @Override
      public void run() {
        Authorization authorization =
          getAuthorization("GET", applicationPath + "/app/admin/scripts");

        assertThat(authorization.isGranted()).isTrue();
        assertThat(authorization.isAuthenticated()).isFalse();
      }
    });
  }

  @ParameterizedTest
  @MethodSource("data")
  public void shouldPassAdminLicenseCheck_GET_LOGGED_OUT(String applicationPath) throws IOException {
    FILTER_RULES = loadFilterRules(applicationPath);

    Authorization authorization =
      getAuthorization("GET", applicationPath + "/api/admin/plugin/license/default/check-key");

    assertThat(authorization.isGranted()).isTrue();
    assertThat(authorization.isAuthenticated()).isFalse();
  }

  protected Authorization getAuthorization(String method, String uri) {
    return FilterRules.authorize(method, uri, FILTER_RULES);
  }

  protected boolean isAuthorized(String method, String uri) {
    return getAuthorization(method, uri).isGranted();
  }

  private static List<SecurityFilterRule> loadFilterRules(String appPath) throws IOException {
    InputStream is = null;

    try {
      is = new FileInputStream(FILTER_RULES_FILE);
      return FilterRules.load(is, appPath);
    } finally {
      IoUtil.closeSilently(is);
    }
  }

  private void authenticatedForEngine(String engineName, Runnable codeBlock) {
    UserAuthentication engineAuth = new UserAuthentication(LOGGED_IN_USER.getIdentityId(), engineName);

    Authentications authentications = new Authentications();
    authentications.addOrReplace(engineAuth);

    Authentications.setCurrent(authentications);

    try {
      codeBlock.run();
    } finally {
      Authentications.clearCurrent();
    }
  }

  private void authenticatedForApp(String engineName, String appName, Runnable codeBlock) {
    HashSet<String> authorizedApps = new HashSet<>(Arrays.asList(appName));

    UserAuthentication engineAuth = new UserAuthentication(LOGGED_IN_USER.getIdentityId(), engineName);
    engineAuth.setGroupIds(Collections.<String> emptyList());
    engineAuth.setTenantIds(Collections.<String> emptyList());
    engineAuth.setAuthorizedApps(authorizedApps);

    Authentications authentications = new Authentications();
    authentications.addOrReplace(engineAuth);

    Authentications.setCurrent(authentications);

    try {
      codeBlock.run();
    } finally {
      Authentications.clearCurrent();
    }
  }
}
