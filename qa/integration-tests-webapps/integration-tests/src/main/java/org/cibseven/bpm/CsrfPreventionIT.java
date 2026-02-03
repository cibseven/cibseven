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
package org.cibseven.bpm;

import javax.ws.rs.core.MediaType;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(10) // 10 seconds timeout for all tests in this class
public class CsrfPreventionIT extends AbstractWebIntegrationTest {

  @BeforeEach
  public void createClient() throws Exception {
    preventRaceConditions();
    createClient(getWebappCtxPath());
  }

  @Test
  public void shouldCheckPresenceOfCsrfPreventionCookie() {
    // given

    // when
    HttpResponse<String> response = Unirest.get(appBasePath + TASKLIST_PATH)
        .asString();

    // then
    assertThat(response.getStatus()).isEqualTo(200);
    String xsrfTokenHeader = getXsrfTokenHeader(response);
    String xsrfCookieValue = getXsrfCookieValue(response);

    assertThat(xsrfTokenHeader).isNotNull();
    assertThat(xsrfTokenHeader.length()).isEqualTo(32);
    assertThat(xsrfCookieValue).isNotNull();
    assertThat(xsrfCookieValue).contains(";SameSite=Lax");
  }

  @Test
  public void shouldRejectModifyingRequest() {
    // given
    String baseUrl = testProperties.getApplicationPath("/" + getWebappCtxPath());
    String modifyingRequestPath = "api/admin/auth/user/default/login/welcome";

    // when
    HttpResponse<String> response = Unirest.post(baseUrl + modifyingRequestPath)
        .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED)
        .asString();

    // then
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(getXsrfTokenHeader(response)).isEqualTo("Required");
  }

}