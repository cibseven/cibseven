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
package org.cibseven.connect.httpclient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.cibseven.connect.ConnectorEndpointNotAllowedException;
import org.cibseven.connect.httpclient.impl.HttpConnectorImpl;
import org.cibseven.connect.impl.ConnectorEndpointPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

@WireMockTest
public class HttpConnectorEndpointPolicyTest {

  private static final String PROPERTY = ConnectorEndpointPolicy.systemPropertyName(HttpConnector.ID);

  private HttpConnector connector;
  private int port;

  @BeforeEach
  public void setUp(WireMockRuntimeInfo wireMock) {
    // a real allow-list in the environment would interfere
    for (String variable : new String[] {ConnectorEndpointPolicy.environmentVariableName(HttpConnector.ID),
        ConnectorEndpointPolicy.environmentVariableName("soap-http-connector"),
        ConnectorEndpointPolicy.GLOBAL_ENVIRONMENT_VARIABLE}) {
      assumeTrue(System.getenv(variable) == null, variable + " is set in the environment");
    }
    port = wireMock.getHttpPort();
    connector = new HttpConnectorImpl();
    stubFor(get(urlEqualTo("/target")).willReturn(aResponse().withStatus(200).withBody("reached")));
    stubFor(get(urlEqualTo("/redirect")).willReturn(aResponse().withStatus(302)
        .withHeader("Location", "http://127.0.0.1:" + port + "/target")));
  }

  @AfterEach
  public void clearProperty() {
    System.clearProperty(PROPERTY);
    System.clearProperty(ConnectorEndpointPolicy.GLOBAL_SYSTEM_PROPERTY);
  }

  @Test
  public void shouldApplyGlobalListUnlessLiftedForConnector() {
    System.setProperty(ConnectorEndpointPolicy.GLOBAL_SYSTEM_PROPERTY, "http://allowed.test");

    assertThatThrownBy(() -> fetch("http://localhost:" + port + "/target"))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class);

    System.setProperty(PROPERTY, ConnectorEndpointPolicy.UNRESTRICTED);
    assertThat(fetch("http://localhost:" + port + "/target").getResponse()).isEqualTo("reached");
  }

  private HttpResponse fetch(String url) {
    return connector.createRequest().url(url).get().header("X-Api-Key", "secret").execute();
  }

  @Test
  public void shouldConnectAnywhereWhenNotRestricted() {
    HttpResponse response = fetch("http://localhost:" + port + "/redirect");

    assertThat(response.getResponse()).isEqualTo("reached");
  }

  @Test
  public void shouldConnectToAllowedEndpoint() {
    System.setProperty(PROPERTY, "http://localhost:" + port);

    assertThat(fetch("http://localhost:" + port + "/target").getResponse()).isEqualTo("reached");
  }

  @Test
  public void shouldRefuseOtherEndpointWithoutSendingAnything() {
    System.setProperty(PROPERTY, "http://allowed.test");

    assertThatThrownBy(() -> fetch("http://localhost:" + port + "/target"))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class)
        .hasMessageContaining("http://localhost:" + port);
    verify(0, anyRequestedFor(anyUrl()));
  }

  @Test
  public void shouldRefuseRedirectToEndpointThatIsNotAllowed() {
    System.setProperty(PROPERTY, "http://localhost:" + port);

    assertThatThrownBy(() -> fetch("http://localhost:" + port + "/redirect"))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class)
        .hasMessageContaining("http://127.0.0.1:" + port);
    verify(1, getRequestedFor(urlEqualTo("/redirect")));
    verify(0, getRequestedFor(urlEqualTo("/target")));
  }

  @Test
  public void shouldFollowRedirectToAllowedEndpoint() {
    System.setProperty(PROPERTY, "http://localhost:" + port + ", http://127.0.0.1:" + port);

    assertThat(fetch("http://localhost:" + port + "/redirect").getResponse()).isEqualTo("reached");
  }

  @Test
  public void shouldFollowRelativeRedirect() {
    stubFor(get(urlEqualTo("/relative")).willReturn(aResponse().withStatus(302).withHeader("Location", "/target")));
    System.setProperty(PROPERTY, "http://localhost:" + port);

    assertThat(fetch("http://localhost:" + port + "/relative").getResponse()).isEqualTo("reached");
  }

  @Test
  public void shouldCheckEveryHopOfRedirectChain() {
    stubFor(get(urlEqualTo("/hop1")).willReturn(aResponse().withStatus(302).withHeader("Location", "/hop2")));
    stubFor(get(urlEqualTo("/hop2")).willReturn(aResponse().withStatus(307).withHeader("Location", "/redirect")));
    System.setProperty(PROPERTY, "http://localhost:" + port);

    assertThatThrownBy(() -> fetch("http://localhost:" + port + "/hop1"))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class)
        .hasMessageContaining("http://127.0.0.1:" + port);
    verify(1, getRequestedFor(urlEqualTo("/hop2")));
    verify(1, getRequestedFor(urlEqualTo("/redirect")));
    verify(0, getRequestedFor(urlEqualTo("/target")));
  }

  @Test
  public void shouldAllowHostWithUnderscore() {
    // no real DNS
    DnsResolver composeDns = new SystemDefaultDnsResolver() {
      @Override
      public InetAddress[] resolve(String host) throws UnknownHostException {
        return "mcp_server".equalsIgnoreCase(host)
            ? new InetAddress[] {InetAddress.getLoopbackAddress()}
            : super.resolve(host);
      }
    };
    ((HttpConnectorImpl) connector).setHttpClient(HttpClients.custom()
        .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(composeDns).build())
        .build());

    System.setProperty(PROPERTY, "http://mcp_server:" + port);
    assertThat(fetch("http://mcp_server:" + port + "/target").getResponse()).isEqualTo("reached");

    System.setProperty(PROPERTY, "http://localhost:" + port);
    assertThatThrownBy(() -> fetch("http://mcp_server:" + port + "/target"))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class);
    verify(1, getRequestedFor(urlEqualTo("/target")));
  }

  @Test
  public void shouldCheckTargetAfterInterceptorsChangedIt() {
    System.setProperty(PROPERTY, "http://localhost:" + port);
    connector.addRequestInterceptor(invocation -> {
      ((BasicClassicHttpRequest) invocation.getTarget())
          .setUri(URI.create("http://127.0.0.1:" + port + "/target"));
      return invocation.proceed();
    });

    assertThatThrownBy(() -> fetch("http://localhost:" + port + "/target"))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class);
    verify(0, anyRequestedFor(anyUrl()));
  }

  @Test
  public void shouldNotApplyListOfOtherConnector() {
    System.setProperty(ConnectorEndpointPolicy.systemPropertyName("soap-http-connector"), "http://allowed.test");
    try {
      assertThat(fetch("http://localhost:" + port + "/target").getResponse()).isEqualTo("reached");
    } finally {
      System.clearProperty(ConnectorEndpointPolicy.systemPropertyName("soap-http-connector"));
    }
  }

}
