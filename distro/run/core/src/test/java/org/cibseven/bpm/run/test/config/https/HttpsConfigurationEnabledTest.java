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
package org.cibseven.bpm.run.test.config.https;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.cibseven.bpm.run.CamundaBpmRun;
import org.cibseven.bpm.run.test.AbstractRestTest;
import org.cibseven.bpm.run.test.util.TestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.ResourceAccessException;

@SpringBootTest(classes = { CamundaBpmRun.class }, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles(profiles = { "test-https-enabled" }, inheritProfiles = true)
public class HttpsConfigurationEnabledTest extends AbstractRestTest {
  
  @BeforeEach
  public void init() throws Exception {
    TestUtils.trustSelfSignedSSL();
  }

  @Test
  public void shouldConnectWithHttps() {
    // given
    String url = "https://localhost:" + localPort + CONTEXT_PATH + "/task";

    // when
    ResponseEntity<List> response = testRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(null), List.class);

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  public void shouldNotRedirect() {
    // given: a plain HTTP request against the HTTPS-only server port
    String url = "http://localhost:" + localPort + CONTEXT_PATH + "/task";

    // when / then: the server must not redirect plain HTTP to HTTPS.
    // Depending on the connector, this either fails at connection level
    // (ResourceAccessException) or returns a 4xx Bad Request. Both are acceptable;
    // a 2xx (served content) or 3xx (redirect) would be a failure.
    try {
      ResponseEntity<String> response =
          testRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(null), String.class);

      assertThat(response.getStatusCode().is3xxRedirection())
          .as("Server must not redirect plain HTTP to HTTPS")
          .isFalse();
      assertThat(response.getStatusCode().is2xxSuccessful())
          .as("Server must not serve content over plain HTTP")
          .isFalse();
    } catch (ResourceAccessException e) {
      // expected: connection reset / TLS handshake failure on the HTTPS-only port
      assertThat(e).hasMessageContaining("I/O error on GET request for \"" + url + "\":");
    }
  }
}
