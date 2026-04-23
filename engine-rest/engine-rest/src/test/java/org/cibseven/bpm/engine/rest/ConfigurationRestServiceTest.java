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
import static org.mockito.Mockito.when;

import javax.ws.rs.core.Response.Status;

import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.rest.impl.ConfigurationRestService;
import org.cibseven.bpm.engine.rest.util.container.TestContainerRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

public class ConfigurationRestServiceTest extends AbstractRestServiceTest {

  @ClassRule
  public static TestContainerRule rule = new TestContainerRule();

  protected static final String CONFIGURATION_URL = TEST_RESOURCE_ROOT_PATH + ConfigurationRestService.PATH;
  protected static final String NAMED_ENGINE_CONFIGURATION_URL = TEST_RESOURCE_ROOT_PATH + "/engine/{name}" + ConfigurationRestService.PATH;

  private ProcessEngineConfiguration mockEngineConfiguration;

  @Before
  public void setUpMocks() {
    mockEngineConfiguration = processEngine.getProcessEngineConfiguration();

    when(mockEngineConfiguration.getHistory()).thenReturn("full");
    when(mockEngineConfiguration.isAuthorizationEnabled()).thenReturn(true);
    when(mockEngineConfiguration.isEnablePasswordPolicy()).thenReturn(false);
  }

  @Test
  public void testGetConfiguration() {
    given()
      .header(ACCEPT_JSON_HEADER)
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
      .body("history", equalTo("full"))
      .body("authorizationEnabled", equalTo(true))
      .body("enablePasswordPolicy", equalTo(false))
    .when().get(CONFIGURATION_URL);
  }

  @Test
  public void testGetConfigurationWithNamedEngine() {
    given()
      .header(ACCEPT_JSON_HEADER)
      .pathParam("name", "default")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
      .body("history", equalTo("full"))
      .body("authorizationEnabled", equalTo(true))
      .body("enablePasswordPolicy", equalTo(false))
    .when().get(NAMED_ENGINE_CONFIGURATION_URL);
  }

  @Test
  public void testGetConfigurationDifferentValues() {
    when(mockEngineConfiguration.getHistory()).thenReturn("none");
    when(mockEngineConfiguration.isAuthorizationEnabled()).thenReturn(false);
    when(mockEngineConfiguration.isEnablePasswordPolicy()).thenReturn(true);

    given()
      .header(ACCEPT_JSON_HEADER)
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
      .body("history", equalTo("none"))
      .body("authorizationEnabled", equalTo(false))
      .body("enablePasswordPolicy", equalTo(true))
    .when().get(CONFIGURATION_URL);
  }

}
