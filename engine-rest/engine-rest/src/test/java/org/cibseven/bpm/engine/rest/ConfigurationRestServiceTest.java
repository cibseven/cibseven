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
import static org.hamcrest.CoreMatchers.equalTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response.Status;

import org.cibseven.bpm.engine.ProcessEngineConfiguration;
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
      .body("historyTimeToLive", equalTo("180"))
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
      .body("historyTimeToLive", equalTo("180"))
      .body("enforceHistoryTimeToLive", equalTo(true))
    .when().get(NAMED_ENGINE_CONFIGURATION_URL);
  }

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
      .body("historyTimeToLive", equalTo(null))
      .body("enforceHistoryTimeToLive", equalTo(false))
    .when().get(CONFIGURATION_URL);
  }

  @Test
  public void testGetConfiguration_notImplClass_leavesTtlFieldsUnknown() {
    // A ProcessEngineConfiguration that isn't the impl subclass (e.g. a custom
    // implementation) must not blow up - it should just report the two new fields as
    // unknown (null), same as an engine-rest client too old to know about them.
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
      .body("historyTimeToLive", equalTo(null))
      .body("enforceHistoryTimeToLive", equalTo(null))
    .when().get(CONFIGURATION_URL);
  }

}
