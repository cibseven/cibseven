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
package org.cibseven.bpm.spring.boot.starter.webapp;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.util.ClassUtils;

/**
 * The JAX-RS applications of the web applications (Jersey servlets) read and write JSON with the
 * Jackson 3 provider of the REST API, not with the Jackson 2 JacksonFeature of Jersey.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = { WebappTestApp.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class WebappJsonProviderIT {

  private static final String[] JAX_RS_SERVLETS = { "Cockpit Api", "Admin Api", "Tasklist Api", "Engine Api", "Welcome Api" };

  @LocalServerPort
  private int port;

  @Autowired
  private ServletContext servletContext;

  @Test
  public void shouldUseTheJackson3ProviderOfTheRestApiOnly() {
    // no Jackson 2 JSON support of Jersey (jersey-media-json-jackson) on the classpath ...
    assertThat(ClassUtils.isPresent("org.glassfish.jersey.jackson.JacksonFeature", null)).isFalse();
    // ... and if it comes back through the application, it stays switched off
    for (String servlet : JAX_RS_SERVLETS) {
      assertThat(servletContext.getServletRegistration(servlet).getInitParameter(CamundaBpmWebappInitializer.JERSEY_SERVER_JSON_FEATURE))
          .as(servlet)
          .isEqualTo(CamundaBpmWebappInitializer.JSON_FEATURE);
    }
  }

  @Test
  public void shouldReadAndWriteJsonWithTheRestApiProvider() throws Exception {
    HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    String base = "http://localhost:" + port + "/camunda/api/engine/engine/";

    // written by the provider (and the CSRF token for the next request)
    HttpResponse<String> engines = client.send(HttpRequest.newBuilder(URI.create(base)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(engines.statusCode()).isEqualTo(200);
    assertThat(engines.body()).isEqualTo("[{\"name\":\"default\"}]");
    String csrfToken = engines.headers().firstValue("X-XSRF-TOKEN").orElseThrow();

    // malformed JSON: the exception mapper of the REST API answers, not the one of Jersey's JacksonFeature
    HttpResponse<String> malformed = client.send(HttpRequest.newBuilder(URI.create(base + "default/identity/password-policy"))
            .header("Content-Type", "application/json")
            .header("X-XSRF-TOKEN", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString("{\"password\": "))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(malformed.statusCode()).isEqualTo(400);
    assertThat(malformed.body()).contains("\"type\":\"InvalidRequestException\"");
  }

}
