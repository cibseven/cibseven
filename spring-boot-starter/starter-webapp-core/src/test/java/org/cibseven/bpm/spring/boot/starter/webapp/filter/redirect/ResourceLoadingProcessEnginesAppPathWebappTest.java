/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
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
package org.cibseven.bpm.spring.boot.starter.webapp.filter.redirect;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.HttpURLConnection;

import org.cibseven.bpm.spring.boot.starter.property.WebappProperty;
import org.cibseven.bpm.spring.boot.starter.webapp.filter.util.FilterTestApp;
import org.cibseven.bpm.spring.boot.starter.webapp.filter.util.HttpClientRule;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = { FilterTestApp.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "camunda.bpm.webapp.application-path=" + WebappProperty.DEFAULT_APP_PATH,
        "camunda.bpm.webapp.index-redirect-enabled=false",
        "camunda.bpm.admin-user.id=admin" })
@DirtiesContext
public class ResourceLoadingProcessEnginesAppPathWebappTest {

  @RegisterExtension
  public HttpClientRule rule = new HttpClientRule().followRedirects(true);

  @LocalServerPort
  public int port;

  @Test
  public void shouldServeIndexForApplicationPath() throws IOException {
    // when
    // send GET request to /webapp
    HttpURLConnection con = rule.performRequest("http://localhost:" + port + WebappProperty.DEFAULT_APP_PATH);

    // then
    // the request should have been redirected to /webapp/ and the index served,
    // even though index-redirect-enabled=false
    assertThat(con.getURL().toString()).isEqualTo("http://localhost:" + port + WebappProperty.DEFAULT_APP_PATH + "/");
    assertThat(con.getResponseCode()).isEqualTo(200);
  }
}
