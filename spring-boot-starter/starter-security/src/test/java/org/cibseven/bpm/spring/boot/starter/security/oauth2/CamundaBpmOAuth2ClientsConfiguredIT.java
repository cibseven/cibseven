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
package org.cibseven.bpm.spring.boot.starter.security.oauth2;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Registered OAuth2 clients must not put the application behind an OAuth2 login: the webclient
 * authenticates on its own and the engine REST API only checks the bearer token of a resource server.
 */
@AutoConfigureTestRestTemplate
@TestPropertySource("/oauth2-mock.properties")
public class CamundaBpmOAuth2ClientsConfiguredIT extends AbstractSpringSecurityIT {

  @Autowired
  private TestRestTemplate testRestTemplate;

  @Test
  public void testRestApiIsAvailableWithoutLogin() {
    // given oauth2 clients configured
    // when calling the rest api
    ResponseEntity<String> entity = testRestTemplate.getForEntity(baseUrl + "/engine-rest/engine/", String.class);

    // then rest api is accessible without a login
    assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(entity.getBody()).isEqualTo(EXPECTED_NAME_DEFAULT);
  }

  @Test
  public void testNoOAuth2Login() {
    // given oauth2 clients configured
    // when starting an oauth2 login
    ResponseEntity<String> entity = testRestTemplate.getForEntity(baseUrl + "/oauth2/authorization/" + PROVIDER, String.class);

    // then there is no oauth2 login
    assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
