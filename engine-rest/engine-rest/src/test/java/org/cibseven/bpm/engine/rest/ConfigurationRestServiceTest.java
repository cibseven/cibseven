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
package org.cibseven.bpm.engine.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response.Status;

import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.exception.NotValidException;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.rest.impl.ConfigurationRestService;
import org.cibseven.bpm.engine.rest.util.container.TestContainerRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class ConfigurationRestServiceTest extends AbstractRestServiceTest {

  @RegisterExtension
  public static TestContainerRule rule = new TestContainerRule();

  protected static final String CONFIGURATION_URL = TEST_RESOURCE_ROOT_PATH + ConfigurationRestService.PATH;
  protected static final String NAMED_ENGINE_CONFIGURATION_URL = TEST_RESOURCE_ROOT_PATH + "/engine/{name}" + ConfigurationRestService.PATH;

  // Mocked as the impl class (not the abstract ProcessEngineConfiguration) since
  // historyTimeToLive/enforceHistoryTimeToLive are only declared there.
  private ProcessEngineConfigurationImpl mockEngineConfiguration;

  @BeforeEach
  public void setUpMocks() {
    mockEngineConfiguration = mock(ProcessEngineConfigurationImpl.class);
    when(processEngine.getProcessEngineConfiguration()).thenReturn(mockEngineConfiguration);

    when(mockEngineConfiguration.getProcessEngineName()).thenReturn("default");
    when(mockEngineConfiguration.getHistory()).thenReturn("full");
    when(mockEngineConfiguration.isAuthorizationEnabled()).thenReturn(true);
    when(mockEngineConfiguration.isEnablePasswordPolicy()).thenReturn(false);
    when(mockEngineConfiguration.getWebclientExtensionsVersion()).thenReturn("3.0.0");
    when(mockEngineConfiguration.getHistoryTimeToLive()).thenReturn("180");
    when(mockEngineConfiguration.isEnforceHistoryTimeToLive()).thenReturn(true);
  }

  @Test
  public void testGetConfiguration() {
    given()
      .header(ACCEPT_JSON_HEADER)
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
      .body("engineName", equalTo("default"))
      .body("historyLevel", equalTo("full"))
      .body("authorizationEnabled", equalTo(true))
      .body("enablePasswordPolicy", equalTo(false))
      .body("webclientExtensionsVersion", equalTo("3.0.0"))
      .body("historyTimeToLive", equalTo(180))
      .body("enforceHistoryTimeToLive", equalTo(true))
    .when().get(CONFIGURATION_URL);
  }

  @Test
  public void testGetConfigurationWithNamedEngine() {
    given()
      .header(ACCEPT_JSON_HEADER)
      .pathParam("name", "default")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
      .body("engineName", equalTo("default"))
      .body("historyLevel", equalTo("full"))
      .body("authorizationEnabled", equalTo(true))
      .body("enablePasswordPolicy", equalTo(false))
      .body("webclientExtensionsVersion", equalTo("3.0.0"))
      .body("historyTimeToLive", equalTo(180))
      .body("enforceHistoryTimeToLive", equalTo(true))
    .when().get(NAMED_ENGINE_CONFIGURATION_URL);
  }

  /**
   * No engine-wide default ({@code null}) is reported as the {@code -1} marker, and a disabled
   * enforcement as a known {@code false} (not as a missing value).
   */
  @Test
  public void testGetConfigurationDifferentValues() {
    when(mockEngineConfiguration.getProcessEngineName()).thenReturn("custom");
    when(mockEngineConfiguration.getHistory()).thenReturn("none");
    when(mockEngineConfiguration.isAuthorizationEnabled()).thenReturn(false);
    when(mockEngineConfiguration.isEnablePasswordPolicy()).thenReturn(true);
    when(mockEngineConfiguration.getHistoryTimeToLive()).thenReturn(null);
    when(mockEngineConfiguration.isEnforceHistoryTimeToLive()).thenReturn(false);

    given()
      .header(ACCEPT_JSON_HEADER)
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
      .body("engineName", equalTo("custom"))
      .body("historyLevel", equalTo("none"))
      .body("authorizationEnabled", equalTo(false))
      .body("enablePasswordPolicy", equalTo(true))
      .body("historyTimeToLive", equalTo(-1))
      .body("enforceHistoryTimeToLive", equalTo(false))
    .when().get(CONFIGURATION_URL);
  }

  /**
   * The engine accepts the history time to live either in days or as an ISO-8601 period in days.
   * The REST API must always expose it as a plain number of days, so clients never have to parse
   * the ISO form themselves.
   */
  @Test
  public void testGetConfiguration_isoHistoryTimeToLive_isExposedInDays() {
    when(mockEngineConfiguration.getHistoryTimeToLive()).thenReturn("P180D");

    given()
      .header(ACCEPT_JSON_HEADER)
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
      .body("historyTimeToLive", equalTo(180))
      .body("enforceHistoryTimeToLive", equalTo(true))
    .when().get(CONFIGURATION_URL);
  }

  /**
   * {@code 0} is a real, configured value ("history is removed by the next cleanup run") and must
   * stay distinguishable from {@code -1} ("no engine default configured"), both in the plain and
   * in the ISO-8601 form.
   */
  @Test
  public void testGetConfiguration_zeroHistoryTimeToLive_isNotReportedAsNotConfigured() {
    when(mockEngineConfiguration.getHistoryTimeToLive()).thenReturn("0");

    given()
      .header(ACCEPT_JSON_HEADER)
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
      .body("historyTimeToLive", equalTo(0))
    .when().get(CONFIGURATION_URL);

    when(mockEngineConfiguration.getHistoryTimeToLive()).thenReturn("P0D");

    given()
      .header(ACCEPT_JSON_HEADER)
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
      .body("historyTimeToLive", equalTo(0))
    .when().get(CONFIGURATION_URL);
  }

  /**
   * An empty configured value means "not configured", exactly like {@code null}, and is reported
   * as the {@code -1} marker (a missing/null value is reserved for engines before 3.0.0).
   */
  @Test
  public void testGetConfiguration_emptyHistoryTimeToLive_isReportedAsNotConfigured() {
    when(mockEngineConfiguration.getHistoryTimeToLive()).thenReturn("");

    given()
      .header(ACCEPT_JSON_HEADER)
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
      .body("historyTimeToLive", equalTo(-1))
    .when().get(CONFIGURATION_URL);
  }

  /**
   * Pins the behavior for an invalid configured value: the request fails instead of reporting a
   * made-up value. A running engine never has such a value, since
   * ProcessEngineConfigurationImpl#initHistoryTimeToLive rejects it at startup with the same
   * parsing method, so this only guards against a silent fallback being introduced.
   * <p>
   * A non-numeric value fails with a plain ProcessEngineException (HTTP 500).
   */
  @Test
  public void testGetConfiguration_nonNumericHistoryTimeToLive_fails() {
    when(mockEngineConfiguration.getHistoryTimeToLive()).thenReturn("abc");

    given()
      .header(ACCEPT_JSON_HEADER)
    .then().expect()
      .statusCode(Status.INTERNAL_SERVER_ERROR.getStatusCode())
      .body("type", equalTo(ProcessEngineException.class.getSimpleName()))
      .body("message", containsString("Cannot parse historyTimeToLive"))
    .when().get(CONFIGURATION_URL);
  }

  /**
   * A negative value fails with a NotValidException (a BadUserRequestException, hence HTTP 400).
   * In particular, {@code -1} cannot be configured: it is only the marker this API uses for
   * "no engine default configured".
   */
  @Test
  public void testGetConfiguration_negativeHistoryTimeToLive_fails() {
    when(mockEngineConfiguration.getHistoryTimeToLive()).thenReturn("-1");

    given()
      .header(ACCEPT_JSON_HEADER)
    .then().expect()
      .statusCode(Status.BAD_REQUEST.getStatusCode())
      .body("type", equalTo(NotValidException.class.getSimpleName()))
      .body("message", containsString("negative value is not allowed"))
    .when().get(CONFIGURATION_URL);
  }

  @Test
  public void testGetConfiguration_notImplClass_usesTtlDefaults() {
    // A ProcessEngineConfiguration that isn't the impl subclass (e.g. a custom
    // implementation) must not blow up - historyTimeToLive falls back to -1 and
    // enforceHistoryTimeToLive falls back to its default (true), i.e. the engine defaults.
    // The fields must still be present: a missing value is reserved for engines before 3.0.0.
    ProcessEngineConfiguration bareConfiguration = mock(ProcessEngineConfiguration.class);
    when(bareConfiguration.getProcessEngineName()).thenReturn("default");
    when(bareConfiguration.getHistory()).thenReturn("full");
    when(bareConfiguration.isAuthorizationEnabled()).thenReturn(true);
    when(bareConfiguration.isEnablePasswordPolicy()).thenReturn(false);
    when(processEngine.getProcessEngineConfiguration()).thenReturn(bareConfiguration);

    given()
      .header(ACCEPT_JSON_HEADER)
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
      .body("engineName", equalTo("default"))
      .body("historyTimeToLive", equalTo(-1))
      .body("enforceHistoryTimeToLive", equalTo(true))
    .when().get(CONFIGURATION_URL);
  }

  /** No row in ACT_GE_PROPERTY means the webclient tables are not in the database. */
  @Test
  public void testGetConfigurationWithoutWebclientExtensions() {
    when(mockEngineConfiguration.getWebclientExtensionsVersion()).thenReturn(null);

    given()
      .header(ACCEPT_JSON_HEADER)
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
      .body("webclientExtensionsVersion", nullValue())
    .when().get(CONFIGURATION_URL);
  }

  @Test
  public void testGetConfigurationWithoutWebclientExtensionsWithNamedEngine() {
    when(mockEngineConfiguration.getWebclientExtensionsVersion()).thenReturn(null);

    given()
      .header(ACCEPT_JSON_HEADER)
      .pathParam("name", "default")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
      .body("webclientExtensionsVersion", nullValue())
    .when().get(NAMED_ENGINE_CONFIGURATION_URL);
  }

}
