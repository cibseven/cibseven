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
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response.Status;

import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.rest.util.container.TestContainerRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;

public class LicenseRestServiceTest extends AbstractRestServiceTest {

  protected ManagementService managementServiceMock;

  protected static final String LICENSE_KEY = "{}";

  protected static final String LICENSE_URL = TEST_RESOURCE_ROOT_PATH + LicenseRestService.PATH;

  @RegisterExtension
  public static TestContainerRule rule = new TestContainerRule();

  @BeforeEach
  public void setupMocks() {
    managementServiceMock = mock(ManagementService.class);
    when(processEngine.getManagementService()).thenReturn(managementServiceMock);
  }
  
  @Test
  public void testSetLicense() {
    managementServiceMock.setLicenseKey(LICENSE_KEY);
    given()
    .then()
      .expect()
        .statusCode(Status.OK.getStatusCode());
    
  }

  @Test
  public void testSetLicenseWithSignatureFromAnotherSecretNamesTheSecretMismatch() {
    // CIB7-2493: the webclient maps this message to "different JWT secrets"
    given()
      .contentType(ContentType.JSON)
      .body("{\"licenseKey\":\"{\\\"customer\\\":\\\"ACME\\\",\\\"signature\\\":\\\"not-a-jwe\\\"}\"}")
    .then()
      .expect()
        .statusCode(Status.INTERNAL_SERVER_ERROR.getStatusCode())
        .body("message", containsString("Failed to decrypt license signature"))
        .body("message", containsString("same JWT secret"))
    .when()
      .post(LICENSE_URL);

    verify(managementServiceMock, never()).setLicenseKey(anyString());
  }

  @Test
  public void testGetLicense() {
    managementServiceMock.getLicenseKey();
    given()
    .then()
      .expect()
        .statusCode(Status.OK.getStatusCode());
  }
}
