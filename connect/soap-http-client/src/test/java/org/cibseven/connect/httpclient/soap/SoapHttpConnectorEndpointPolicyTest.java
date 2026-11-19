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
package org.cibseven.connect.httpclient.soap;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.cibseven.connect.ConnectorEndpointNotAllowedException;
import org.cibseven.connect.httpclient.HttpConnector;
import org.cibseven.connect.httpclient.soap.impl.SoapHttpConnectorImpl;
import org.cibseven.connect.impl.ConnectorEndpointPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

@WireMockTest
public class SoapHttpConnectorEndpointPolicyTest {

  private static final String SOAP_PROPERTY = ConnectorEndpointPolicy.systemPropertyName(SoapHttpConnector.ID);
  private static final String HTTP_PROPERTY = ConnectorEndpointPolicy.systemPropertyName(HttpConnector.ID);

  private SoapHttpConnector connector;
  private int port;

  @BeforeEach
  public void setUp(WireMockRuntimeInfo wireMock) {
    // a real allow-list in the environment would interfere
    for (String variable : new String[] {ConnectorEndpointPolicy.environmentVariableName(SoapHttpConnector.ID),
        ConnectorEndpointPolicy.environmentVariableName(HttpConnector.ID),
        ConnectorEndpointPolicy.GLOBAL_ENVIRONMENT_VARIABLE}) {
      assumeTrue(System.getenv(variable) == null, variable + " is set in the environment");
    }
    port = wireMock.getHttpPort();
    connector = new SoapHttpConnectorImpl();
    stubFor(post(urlEqualTo("/soap")).willReturn(aResponse().withStatus(200)));
  }

  @AfterEach
  public void clearProperties() {
    System.clearProperty(SOAP_PROPERTY);
    System.clearProperty(HTTP_PROPERTY);
    System.clearProperty(ConnectorEndpointPolicy.GLOBAL_SYSTEM_PROPERTY);
  }

  @Test
  public void shouldUseItsOwnList() {
    System.setProperty(HTTP_PROPERTY, "http://allowed.test");
    connector.createRequest().url("http://localhost:" + port + "/soap").payload("test").execute();

    System.setProperty(SOAP_PROPERTY, "http://allowed.test");
    assertThatThrownBy(() -> connector.createRequest().url("http://localhost:" + port + "/soap").payload("test").execute())
        .isInstanceOf(ConnectorEndpointNotAllowedException.class)
        .hasMessageContaining("'soap-http-connector'");

    verify(1, postRequestedFor(urlEqualTo("/soap")));
  }

  @Test
  public void shouldConnectToAllowedEndpoint() {
    System.setProperty(SOAP_PROPERTY, "http://localhost:" + port);

    connector.createRequest().url("http://localhost:" + port + "/soap").payload("test").execute();

    verify(1, anyRequestedFor(anyUrl()));
  }

}
