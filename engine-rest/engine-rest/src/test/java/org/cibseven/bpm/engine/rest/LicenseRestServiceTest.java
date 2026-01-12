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
package org.cibseven.bpm.engine.rest;

import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.ws.rs.core.Response.Status;

import org.cibseven.bpm.engine.ManagementService;
import org.junit.Before;
import org.junit.Test;

public class LicenseRestServiceTest extends AbstractRestServiceTest {

  protected ManagementService managementServiceMock;

  protected static final String LICENSE_KEY = "{}";

  @Before
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
  public void testGetLicense() {
    managementServiceMock.getLicenseKey();
    given()
    .then()
      .expect()
        .statusCode(Status.OK.getStatusCode());
  }
}
