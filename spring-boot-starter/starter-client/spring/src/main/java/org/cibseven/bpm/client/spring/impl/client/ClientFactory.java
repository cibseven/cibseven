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
package org.cibseven.bpm.client.spring.impl.client;

import static org.cibseven.bpm.client.spring.annotation.EnableExternalTaskClient.STRING_ORDER_BY_ASC_VALUE;
import static org.cibseven.bpm.client.spring.annotation.EnableExternalTaskClient.STRING_ORDER_BY_DESC_VALUE;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.cibseven.bpm.client.ExternalTaskClient;
import org.cibseven.bpm.client.ExternalTaskClientBuilder;
import org.cibseven.bpm.client.backoff.BackoffStrategy;
import org.cibseven.bpm.client.interceptor.ClientRequestInterceptor;
import org.cibseven.bpm.client.spring.ExternalTaskClientHttpCustomizer;
import org.cibseven.bpm.client.spring.exception.SpringExternalTaskClientException;
import org.cibseven.bpm.client.spring.impl.client.util.ClientLoggerUtil;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.env.PropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;

public class ClientFactory
    implements FactoryBean<ExternalTaskClient>, InitializingBean, DisposableBean {

  protected static final ClientLoggerUtil LOG = ClientLoggerUtil.CLIENT_LOGGER;

  /** Below this margin over asyncResponseTimeout, a warning about the responseTimeout is logged. */
  protected static final long RECOMMENDED_RESPONSE_TIMEOUT_MARGIN = 10_000;

  protected ClientConfiguration clientConfiguration;

  protected BackoffStrategy backoffStrategy;
  protected List<ClientRequestInterceptor> requestInterceptors = new ArrayList<>();
  protected List<ExternalTaskClientHttpCustomizer> httpClientCustomizers = new ArrayList<>();

  protected ExternalTaskClient client;

  protected PropertyResolver propertyResolver;

  @Override
  public ExternalTaskClient getObject() {
    if (client == null) {
      ExternalTaskClientBuilder clientBuilder = ExternalTaskClient.create();
      if (clientConfiguration.getBaseUrl() != null) {
          clientBuilder.baseUrl(resolve(clientConfiguration.getBaseUrl()));
      }
      if (clientConfiguration.getWorkerId() != null) {
        clientBuilder.workerId(resolve(clientConfiguration.getWorkerId()));
      }

      addClientRequestInterceptors(clientBuilder);
      // properties first, so that customizer beans can override them
      applyHttpClientTimeouts(clientBuilder);
      applyHttpClientCustomizers(clientBuilder);

      if (clientConfiguration.getMaxTasks() != null) {
        clientBuilder.maxTasks(clientConfiguration.getMaxTasks());
      }
      if (clientConfiguration.getUsePriority() != null && !clientConfiguration.getUsePriority()) {
        clientBuilder.usePriority(false);
      }
      if (clientConfiguration.getDefaultSerializationFormat() != null) {
        clientBuilder.defaultSerializationFormat(resolve(clientConfiguration.getDefaultSerializationFormat()));
      }
      if (clientConfiguration.getDateFormat() != null) {
        clientBuilder.dateFormat(resolve(clientConfiguration.getDateFormat()));
      }
      if (clientConfiguration.getAsyncResponseTimeout() != null) {
        clientBuilder.asyncResponseTimeout(clientConfiguration.getAsyncResponseTimeout());
      }
      if (clientConfiguration.getLockDuration() != null) {
        clientBuilder.lockDuration(clientConfiguration.getLockDuration());
      }
      if (clientConfiguration.getDisableAutoFetching() != null &&
          clientConfiguration.getDisableAutoFetching()) {
        clientBuilder.disableAutoFetching();
      }
      if (clientConfiguration.getDisableBackoffStrategy() != null &&
          clientConfiguration.getDisableBackoffStrategy()) {
        clientBuilder.disableBackoffStrategy();
      }
      if (backoffStrategy != null) {
        clientBuilder.backoffStrategy(backoffStrategy);
      }

      tryConfigureCreateTimeOrder(clientBuilder);

      client = clientBuilder.build();
    }

    LOG.bootstrapped();

    return client;
  }

  protected void addClientRequestInterceptors(ExternalTaskClientBuilder taskClientBuilder) {
    requestInterceptors.forEach(taskClientBuilder::addInterceptor);
  }

  protected void applyHttpClientCustomizers(ExternalTaskClientBuilder taskClientBuilder) {
    httpClientCustomizers.forEach(taskClientBuilder::customizeHttpClient);
  }

  /**
   * Applies the timeout properties to the HTTP client. Runs before
   * {@link #applyHttpClientCustomizers(ExternalTaskClientBuilder)}, so customizers can override it.
   * <ul>
   *   <li>The response and connection request timeouts become the default request configuration.
   *   A customizer that sets its own default request configuration replaces them.</li>
   *   <li>The connect timeout goes on the connection manager, as HttpClient 5 recommends.
   *   A customizer that sets its own connection manager replaces it.</li>
   * </ul>
   * Does nothing when no timeout is configured, so the HTTP client keeps its defaults.
   */
  protected void applyHttpClientTimeouts(ExternalTaskClientBuilder taskClientBuilder) {
    Long connectTimeout = clientConfiguration.getConnectTimeout();
    boolean hasRequestTimeouts = clientConfiguration.getResponseTimeout() != null
        || clientConfiguration.getConnectionRequestTimeout() != null;
    if (connectTimeout == null && !hasRequestTimeouts) {
      return;
    }

    checkHttpClientTimeouts();

    taskClientBuilder.customizeHttpClient(httpClientBuilder -> {
      if (hasRequestTimeouts) {
        httpClientBuilder.setDefaultRequestConfig(createRequestConfig());
      }
      if (connectTimeout != null) {
        httpClientBuilder.setConnectionManager(createConnectionManager(connectTimeout));
      }
    });
  }

  protected void checkHttpClientTimeouts() {
    checkGreaterThanZero("connectTimeout", clientConfiguration.getConnectTimeout());
    checkGreaterThanZero("responseTimeout", clientConfiguration.getResponseTimeout());
    checkGreaterThanZero("connectionRequestTimeout", clientConfiguration.getConnectionRequestTimeout());

    Long responseTimeout = clientConfiguration.getResponseTimeout();
    Long asyncResponseTimeout = clientConfiguration.getAsyncResponseTimeout();
    if (responseTimeout != null && asyncResponseTimeout != null) {
      if (responseTimeout <= asyncResponseTimeout) {
        throw LOG.responseTimeoutNotGreaterThanAsyncResponseTimeout(responseTimeout, asyncResponseTimeout);
      }
      if (responseTimeout - asyncResponseTimeout < RECOMMENDED_RESPONSE_TIMEOUT_MARGIN) {
        LOG.responseTimeoutMarginTooSmall(responseTimeout, asyncResponseTimeout,
            RECOMMENDED_RESPONSE_TIMEOUT_MARGIN);
      }
    }
  }

  protected void checkGreaterThanZero(String property, Long value) {
    if (value != null && value <= 0) {
      throw LOG.httpClientTimeoutNotGreaterThanZero(property, value);
    }
  }

  protected RequestConfig createRequestConfig() {
    RequestConfig.Builder requestConfig = RequestConfig.custom();
    if (clientConfiguration.getResponseTimeout() != null) {
      requestConfig.setResponseTimeout(Timeout.ofMilliseconds(clientConfiguration.getResponseTimeout()));
    }
    if (clientConfiguration.getConnectionRequestTimeout() != null) {
      requestConfig.setConnectionRequestTimeout(
          Timeout.ofMilliseconds(clientConfiguration.getConnectionRequestTimeout()));
    }
    return requestConfig.build();
  }

  /**
   * Builds the connection manager the HTTP client would create by default (the external task
   * client enables system properties), plus the connect timeout.
   */
  protected PoolingHttpClientConnectionManager createConnectionManager(long connectTimeout) {
    return PoolingHttpClientConnectionManagerBuilder.create()
        .useSystemProperties()
        .setDefaultConnectionConfig(createConnectionConfig(connectTimeout))
        .build();
  }

  protected ConnectionConfig createConnectionConfig(long connectTimeout) {
    return ConnectionConfig.custom()
        .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout))
        .build();
  }

  protected void tryConfigureCreateTimeOrder(ExternalTaskClientBuilder builder) {
    checkForCreateTimeMisconfiguration();

    if (isUseCreateTimeEnabled()) {
      builder.orderByCreateTime().desc();
      return;
    }

    if (isOrderByCreateTimeEnabled()) {
      handleOrderByCreateTimeConfig(builder);
    }
  }

  protected void handleOrderByCreateTimeConfig(ExternalTaskClientBuilder builder) {
    String orderByCreateTime = clientConfiguration.getOrderByCreateTime();

    if (STRING_ORDER_BY_ASC_VALUE.equals(orderByCreateTime)) {
      builder.orderByCreateTime().asc();
      return;
    }

    if (STRING_ORDER_BY_DESC_VALUE.equals(orderByCreateTime)) {
      builder.orderByCreateTime().desc();
      return;
    }

    throw new SpringExternalTaskClientException("Invalid value " + clientConfiguration.getOrderByCreateTime()
        + ". Please use either \"asc\" or \"desc\" value for configuring \"orderByCreateTime\" on the client");
  }

  protected boolean isOrderByCreateTimeEnabled() {
    return clientConfiguration.getOrderByCreateTime() != null;
  }

  protected boolean isUseCreateTimeEnabled() {
    return Boolean.TRUE.equals(clientConfiguration.getUseCreateTime());
  }

  protected void checkForCreateTimeMisconfiguration() {
    if (isUseCreateTimeEnabled() && isOrderByCreateTimeEnabled()) {
      throw new SpringExternalTaskClientException(
          "Both \"useCreateTime\" and \"orderByCreateTime\" are enabled. Please use one or the other");
    }
  }

  @Autowired(required = false)
  public void setRequestInterceptors(List<ClientRequestInterceptor> requestInterceptors) {
    if (requestInterceptors != null) {
      this.requestInterceptors.addAll(requestInterceptors);
      LOG.requestInterceptorsFound(this.requestInterceptors.size());
    }
  }

  @Autowired(required = false)
  public void setHttpClientCustomizers(List<ExternalTaskClientHttpCustomizer> httpClientCustomizers) {
    if (httpClientCustomizers != null) {
      this.httpClientCustomizers.addAll(httpClientCustomizers);
      LOG.httpClientCustomizersFound(this.httpClientCustomizers.size());
    }
  }

  @Autowired(required = false)
  public void setClientBackoffStrategy(BackoffStrategy backoffStrategy) {
    this.backoffStrategy = backoffStrategy;
    LOG.backoffStrategyFound();
  }

  @Override
  public Class<ExternalTaskClient> getObjectType() {
    return ExternalTaskClient.class;
  }

  @Override
  public boolean isSingleton() {
    return true;
  }

  @Override
  public void afterPropertiesSet() throws Exception {
  }

  public ClientConfiguration getClientConfiguration() {
    return clientConfiguration;
  }

  public void setClientConfiguration(ClientConfiguration clientConfiguration) {
    this.clientConfiguration = clientConfiguration;
  }

  public List<ClientRequestInterceptor> getRequestInterceptors() {
    return requestInterceptors;
  }

  public List<ExternalTaskClientHttpCustomizer> getHttpClientCustomizers() {
    return httpClientCustomizers;
  }

  @Override
  public void destroy() {
    close();
    for (ClientRequestInterceptor interceptor : requestInterceptors) {
      if (interceptor instanceof Closeable) {
        try {
          ((Closeable) interceptor).close();
        } catch (IOException ignored) {
          // best-effort cleanup during shutdown
        }
      }
    }
  }

  protected void close() {
    if (client != null) {
      client.stop();
    }
  }

  @Autowired(required = false)
  protected void setPropertyConfigurer(PropertySourcesPlaceholderConfigurer configurer) {
    PropertySources appliedPropertySources = configurer.getAppliedPropertySources();
    propertyResolver = new PropertySourcesPropertyResolver(appliedPropertySources);
  }

  protected String resolve(String property) {
    if (propertyResolver == null) {
      return property;
    }

    if (property != null) {
      return propertyResolver.resolvePlaceholders(property);
    } else {
      return null;
    }
  }

}