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
package org.cibseven.connect.httpclient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;

import java.util.HashSet;
import java.util.Set;

import org.apache.hc.core5.http.HttpHeaders;
import org.cibseven.connect.httpclient.impl.HttpConnectorImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;


/**
 * Since Apache HTTP client makes it extremely hard to test the proper configuration
 * of a http client, this is more of an integration test that checks that a
 * system property is respected
 *
 * @author Thorben Lindhauer
 */
@WireMockTest(httpPort=51234)
public class HttpConnectorSystemPropertiesTest {

  public static final int PORT = 51234;

  protected Set<String> updatedSystemProperties;

  @BeforeEach
  public void setUp() {
    updatedSystemProperties = new HashSet<>();
    stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200)));
  }

  @AfterEach
  public void clearCustomSystemProperties() {
    for (String property : updatedSystemProperties) {
      System.getProperties().remove(property);
    }
  }

  public void setSystemProperty(String property, String value) {
    if (!System.getProperties().containsKey(property)) {
      updatedSystemProperties.add(property);
      System.setProperty(property, value);
    } else {
      throw new RuntimeException("Cannot perform test: System property "
          + property + " is already set. Will not attempt to overwrite this property.");
    }
  }

  @Test
  public void shouldSetUserAgentFromSystemProperty() {
    // given
    setSystemProperty("http.agent", "foo");

    HttpConnector customConnector = new HttpConnectorImpl();
    // when
    customConnector.createRequest().url("http://localhost:" +  PORT).get().execute();
    // then
    verify(getRequestedFor(urlEqualTo("/")).withHeader(HttpHeaders.USER_AGENT, equalTo("foo")));

  }
}
