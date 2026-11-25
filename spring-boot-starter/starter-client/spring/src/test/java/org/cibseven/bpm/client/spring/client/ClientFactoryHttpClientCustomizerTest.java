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
package org.cibseven.bpm.client.spring.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.util.Timeout;
import org.cibseven.bpm.client.ExternalTaskClient;
import org.cibseven.bpm.client.impl.EngineClient;
import org.cibseven.bpm.client.impl.ExternalTaskClientImpl;
import org.cibseven.bpm.client.spring.ExternalTaskClientHttpCustomizer;
import org.cibseven.bpm.client.spring.impl.client.ClientConfiguration;
import org.cibseven.bpm.client.spring.impl.client.ClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Builds a real client (no mocked builder) against a local server that accepts connections
 * but never answers, to check that a customizer's settings reach the HTTP client the
 * external task client sends its engine calls with.
 */
public class ClientFactoryHttpClientCustomizerTest {

  /** Far below HttpClient 5's default response timeout of three minutes. */
  protected static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

  protected ServerSocket silentEngine;
  protected List<Socket> acceptedConnections = new CopyOnWriteArrayList<>();
  protected ClientFactory clientFactory;

  @BeforeEach
  public void startSilentEngine() throws IOException {
    silentEngine = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    Thread acceptor = new Thread(() -> {
      try {
        while (!silentEngine.isClosed()) {
          // keep the connection open and never write a response
          acceptedConnections.add(silentEngine.accept());
        }
      } catch (IOException e) {
        // server socket closed by stopSilentEngine
      }
    }, "silent-engine");
    acceptor.setDaemon(true);
    acceptor.start();
  }

  @AfterEach
  public void stopClientAndSilentEngine() throws IOException {
    if (clientFactory != null) {
      clientFactory.destroy();
    }
    silentEngine.close();
    for (Socket connection : acceptedConnections) {
      connection.close();
    }
  }

  @Test
  public void shouldApplyCustomizedResponseTimeoutToEngineCalls() {
    // given
    ExternalTaskClientHttpCustomizer responseTimeout = builder ->
        builder.setDefaultRequestConfig(RequestConfig.custom()
            .setResponseTimeout(Timeout.ofSeconds(1))
            .build());

    clientFactory = createClientFactory();
    clientFactory.setHttpClientCustomizers(List.of(responseTimeout));
    EngineClient engineClient = getEngineClient(clientFactory.getObject());

    // when / then: the call fails on the customized timeout, not after the default three minutes
    assertTimeoutPreemptively(TEST_TIMEOUT, () ->
        assertThatThrownBy(() -> engineClient.unlock("aTaskId"))
            .hasRootCauseInstanceOf(SocketTimeoutException.class));
    assertThat(acceptedConnections).isNotEmpty();
  }

  @Test
  public void shouldBuildClientWithoutCustomizers() {
    // given
    clientFactory = createClientFactory();
    clientFactory.setHttpClientCustomizers(null);

    // when
    ExternalTaskClient client = clientFactory.getObject();

    // then
    assertThat(client).isNotNull();
    assertThat(clientFactory.getHttpClientCustomizers()).isEmpty();
  }

  protected ClientFactory createClientFactory() {
    ClientConfiguration configuration = new ClientConfiguration();
    configuration.setBaseUrl("http://" + silentEngine.getInetAddress().getHostAddress() + ":"
        + silentEngine.getLocalPort() + "/engine-rest");
    configuration.setDisableAutoFetching(true);

    ClientFactory factory = new ClientFactory();
    factory.setClientConfiguration(configuration);
    return factory;
  }

  protected EngineClient getEngineClient(ExternalTaskClient client) {
    return ((ExternalTaskClientImpl) client).getTopicSubscriptionManager().getEngineClient();
  }

}
