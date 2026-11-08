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
package org.cibseven.bpm.engine.rest.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import jakarta.ws.rs.core.Response.Status;

import org.cibseven.bpm.engine.rest.exception.InvalidRequestException;
import org.cibseven.bpm.engine.rest.security.auth.impl.jwt.Configuration;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.fasterxml.jackson.databind.ObjectMapper;

public class LicenseRestServiceImplTest {

  @Test
  public void testMissingJwtConfigurationIsUnavailableWithTheCause() {
    // CIB7-2493: no secret anywhere makes Configuration.getInstance() throw; the REST server runs
    // requests on other threads, so the static mock is checked here directly
    LicenseRestServiceImpl service = new LicenseRestServiceImpl("default", new ObjectMapper());
    try (MockedStatic<Configuration> configuration = mockStatic(Configuration.class)) {
      configuration.when(Configuration::getInstance)
        .thenThrow(new IllegalStateException("No JWT secret found: could not find configuration file"));

      InvalidRequestException e = assertThrows(InvalidRequestException.class, service::getSecret);

      assertEquals(Status.SERVICE_UNAVAILABLE, e.getStatus());
      assertTrue(e.getMessage().contains("JWT secret is not configured"));
      assertTrue(e.getMessage().contains("No JWT secret found"));
    }
  }
}
