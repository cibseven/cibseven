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
package org.cibseven.bpm.client.spring.impl.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.function.Consumer;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.cibseven.bpm.client.ExternalTaskClientBuilder;
import org.cibseven.bpm.client.spring.exception.SpringExternalTaskClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

public class ClientFactoryHttpClientTimeoutsTest {

  protected ClientConfiguration configuration;
  protected ClientFactory clientFactory;
  protected ExternalTaskClientBuilder taskClientBuilder;

  @BeforeEach
  public void setUp() {
    configuration = new ClientConfiguration();
    clientFactory = new ClientFactory();
    clientFactory.setClientConfiguration(configuration);
    taskClientBuilder = mock(ExternalTaskClientBuilder.class);
  }

  @Test
  public void shouldLeaveHttpClientUntouchedWithoutTimeoutProperties() {
    // when
    clientFactory.applyHttpClientTimeouts(taskClientBuilder);

    // then
    verifyNoInteractions(taskClientBuilder);
  }

  @Test
  public void shouldSetResponseAndConnectionRequestTimeoutsAsDefaultRequestConfig() {
    // given: no connect timeout
    configuration.setResponseTimeout(2_000L);
    configuration.setConnectionRequestTimeout(3_000L);

    // when
    HttpClientBuilder httpClientBuilder = applyToMockedHttpClientBuilder();

    // then
    RequestConfig requestConfig = captureRequestConfig(httpClientBuilder);
    assertThat(requestConfig.getResponseTimeout()).isEqualTo(Timeout.ofMilliseconds(2_000));
    assertThat(requestConfig.getConnectionRequestTimeout()).isEqualTo(Timeout.ofMilliseconds(3_000));
    // the HTTP client keeps the connection manager it creates by default
    verify(httpClientBuilder, never()).setConnectionManager(any());
  }

  @Test
  public void shouldKeepHttpClientDefaultsForUnsetTimeouts() {
    // given
    configuration.setResponseTimeout(2_000L);

    // when
    HttpClientBuilder httpClientBuilder = applyToMockedHttpClientBuilder();

    // then
    RequestConfig requestConfig = captureRequestConfig(httpClientBuilder);
    assertThat(requestConfig.getResponseTimeout()).isEqualTo(Timeout.ofMilliseconds(2_000));
    assertThat(requestConfig.getConnectionRequestTimeout())
        .isEqualTo(RequestConfig.DEFAULT.getConnectionRequestTimeout());
  }

  @Test
  public void shouldSetConnectTimeoutOnConnectionManager() {
    // given: only a connect timeout
    configuration.setConnectTimeout(1_000L);

    // when
    HttpClientBuilder httpClientBuilder = applyToMockedHttpClientBuilder();

    // then: a connection manager is set, and the default request configuration is left alone
    captureConnectionManager(httpClientBuilder);
    verify(httpClientBuilder, never()).setDefaultRequestConfig(any());
  }

  @Test
  public void shouldApplyAllTimeoutProperties() {
    // given
    configuration.setConnectTimeout(1_000L);
    configuration.setResponseTimeout(2_000L);
    configuration.setConnectionRequestTimeout(3_000L);

    // when
    HttpClientBuilder httpClientBuilder = applyToMockedHttpClientBuilder();

    // then
    captureConnectionManager(httpClientBuilder);
    RequestConfig requestConfig = captureRequestConfig(httpClientBuilder);
    assertThat(requestConfig.getResponseTimeout()).isEqualTo(Timeout.ofMilliseconds(2_000));
    assertThat(requestConfig.getConnectionRequestTimeout()).isEqualTo(Timeout.ofMilliseconds(3_000));
  }

  @Test
  public void shouldCreateConnectionConfigWithConnectTimeout() {
    // when: the connection manager reads its default connection config from here
    ConnectionConfig connectionConfig = clientFactory.createConnectionConfig(1_000L);

    // then
    assertThat(connectionConfig.getConnectTimeout()).isEqualTo(Timeout.ofMilliseconds(1_000));
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  public void shouldRejectTimeoutNotGreaterThanZero(long value) {
    // given
    configuration.setConnectTimeout(value);

    // when / then
    assertThatThrownBy(() -> clientFactory.applyHttpClientTimeouts(taskClientBuilder))
        .isInstanceOf(SpringExternalTaskClientException.class)
        .hasMessageContaining("connectTimeout")
        .hasMessageContaining("greater than zero");
    verifyNoInteractions(taskClientBuilder);
  }

  @ParameterizedTest
  @ValueSource(longs = {30_000, 60_000})
  public void shouldRejectResponseTimeoutNotGreaterThanAsyncResponseTimeout(long responseTimeout) {
    // given
    configuration.setAsyncResponseTimeout(60_000L);
    configuration.setResponseTimeout(responseTimeout);

    // when / then
    assertThatThrownBy(() -> clientFactory.applyHttpClientTimeouts(taskClientBuilder))
        .isInstanceOf(SpringExternalTaskClientException.class)
        .hasMessageContaining("responseTimeout")
        .hasMessageContaining("asyncResponseTimeout");
    verifyNoInteractions(taskClientBuilder);
  }

  @ParameterizedTest
  @ValueSource(longs = {60_001, 65_000, 70_000})
  public void shouldAcceptResponseTimeoutGreaterThanAsyncResponseTimeout(long responseTimeout) {
    // given: below the recommended margin only a warning is logged
    configuration.setAsyncResponseTimeout(60_000L);
    configuration.setResponseTimeout(responseTimeout);

    // when / then
    assertThatCode(() -> clientFactory.applyHttpClientTimeouts(taskClientBuilder))
        .doesNotThrowAnyException();
  }

  /**
   * Applies the timeout properties and runs the resulting customizer on a mocked HTTP client builder.
   */
  @SuppressWarnings("unchecked")
  protected HttpClientBuilder applyToMockedHttpClientBuilder() {
    clientFactory.applyHttpClientTimeouts(taskClientBuilder);

    ArgumentCaptor<Consumer<HttpClientBuilder>> customizer = ArgumentCaptor.forClass(Consumer.class);
    verify(taskClientBuilder).customizeHttpClient(customizer.capture());

    HttpClientBuilder httpClientBuilder = mock(HttpClientBuilder.class);
    customizer.getValue().accept(httpClientBuilder);
    return httpClientBuilder;
  }

  protected RequestConfig captureRequestConfig(HttpClientBuilder httpClientBuilder) {
    ArgumentCaptor<RequestConfig> requestConfig = ArgumentCaptor.forClass(RequestConfig.class);
    verify(httpClientBuilder).setDefaultRequestConfig(requestConfig.capture());
    return requestConfig.getValue();
  }

  protected void captureConnectionManager(HttpClientBuilder httpClientBuilder) {
    ArgumentCaptor<HttpClientConnectionManager> connectionManager =
        ArgumentCaptor.forClass(HttpClientConnectionManager.class);
    verify(httpClientBuilder).setConnectionManager(connectionManager.capture());
    assertThat(connectionManager.getValue()).isInstanceOf(PoolingHttpClientConnectionManager.class);
    // the mocked builder never takes ownership of the manager
    connectionManager.getValue().close(CloseMode.IMMEDIATE);
  }

}
