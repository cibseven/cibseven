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
package org.cibseven.bpm.client.spring.boot.starter.impl;

import org.cibseven.bpm.client.interceptor.auth.AzureWorkloadIdentityAssertionProvider;
import org.cibseven.bpm.client.interceptor.auth.BasicAuthProvider;
import org.cibseven.bpm.client.interceptor.auth.ClientAssertionProvider;
import org.cibseven.bpm.client.interceptor.auth.JjwtClientAssertionProvider;
import org.cibseven.bpm.client.interceptor.auth.OAuth2ClientCredentialsProvider;
import org.cibseven.bpm.client.spring.boot.starter.BasicAuthProperties;
import org.cibseven.bpm.client.spring.boot.starter.ClientProperties;
import org.cibseven.bpm.client.spring.boot.starter.OAuth2Properties;
import org.cibseven.bpm.client.spring.boot.starter.OAuth2Properties.AssertionProperties;
import org.cibseven.bpm.client.spring.boot.starter.OAuth2Properties.AssertionProperties.AssertionType;
import org.cibseven.bpm.client.spring.impl.client.ClientConfiguration;
import org.cibseven.bpm.client.spring.impl.client.ClientFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ssl.pem.PemContent;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.InputStream;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;

public class PropertiesAwareClientFactory extends ClientFactory implements ResourceLoaderAware {

  @Autowired
  protected ClientProperties clientProperties;

  protected ResourceLoader resourceLoader;

  @Override
  public void setResourceLoader(ResourceLoader resourceLoader) {
    this.resourceLoader = resourceLoader;
  }

  @Override
  public void afterPropertiesSet() throws Exception {
    applyPropertiesFrom(clientProperties);
    addBasicAuthInterceptor();
    addOAuth2Interceptor();
    super.afterPropertiesSet();
  }

  protected void addBasicAuthInterceptor() {
    BasicAuthProperties basicAuth = clientProperties.getBasicAuth();
    if (basicAuth != null) {

      String username = basicAuth.getUsername();
      String password = basicAuth.getPassword();
      BasicAuthProvider basicAuthProvider = new BasicAuthProvider(username, password);

      getRequestInterceptors().add(basicAuthProvider);
    }
  }

  protected void addOAuth2Interceptor() throws Exception {
    OAuth2Properties oauth2 = clientProperties.getOauth2();
    if (oauth2 == null) {
      return;
    }

    OAuth2ClientCredentialsProvider.Builder builder = OAuth2ClientCredentialsProvider.builder()
        .tokenUri(oauth2.getTokenUri())
        .clientId(oauth2.getClientId())
        .scope(oauth2.getScope())
        .audience(oauth2.getAudience())
        .resource(oauth2.getResource())
        .additionalParameters(oauth2.getAdditionalParameters())
        .expiryBuffer(Duration.ofSeconds(oauth2.getExpiryBufferSeconds()));

    if (oauth2.getClientSecret() != null && !oauth2.getClientSecret().isBlank()) {
      builder.clientSecret(oauth2.getClientSecret());
    } else {
      builder.clientAssertionProvider(buildAssertionProvider(oauth2));
    }

    getRequestInterceptors().add(builder.build());
  }

  protected ClientAssertionProvider buildAssertionProvider(OAuth2Properties oauth2) throws Exception {
    AssertionProperties assertion = oauth2.getAssertion();
    if (assertion == null || assertion.getType() == null) {
      throw new IllegalStateException(
          "camunda.bpm.client.oauth2.assertion.type must be set when no clientSecret is configured. "
              + "Valid values: JJWT, AZURE_WORKLOAD_IDENTITY");
    }

    AssertionType type = assertion.getType();

    if (type == AssertionType.AZURE_WORKLOAD_IDENTITY) {
      return new AzureWorkloadIdentityAssertionProvider();
    }

    String keyLocation = assertion.getKeyLocation();
    if (keyLocation == null || keyLocation.isBlank()) {
      throw new IllegalStateException(
          "camunda.bpm.client.oauth2.assertion.key-location must be set for assertion type " + type);
    }

    Resource resource = resourceLoader.getResource(keyLocation);
    PrivateKey privateKey;
    try (InputStream is = resource.getInputStream()) {
      privateKey = PemContent.load(is).getPrivateKey();
    }

    if (privateKey == null) {
      throw new IllegalStateException(
          "No private key found in PEM content at '" + keyLocation + "'");
    }

    if (!(privateKey instanceof RSAPrivateKey) && !(privateKey instanceof ECPrivateKey)) {
      throw new IllegalStateException(
          "Unsupported private key type '" + privateKey.getAlgorithm()
              + "' (" + privateKey.getClass().getName() + ") loaded from '" + keyLocation
              + "'. Expected RSA or EC key.");
    }

    return new JjwtClientAssertionProvider(oauth2.getClientId(), oauth2.getTokenUri(), privateKey);
  }

  public void applyPropertiesFrom(ClientProperties clientConfigurationProps) {
    ClientConfiguration clientConfiguration = new ClientConfiguration();
    if (clientConfigurationProps.getBaseUrl() != null) {
      clientConfiguration.setBaseUrl(clientConfigurationProps.getBaseUrl());
    }
    if (clientConfigurationProps.getWorkerId() != null) {
      clientConfiguration.setWorkerId(clientConfigurationProps.getWorkerId());
    }
    if (clientConfigurationProps.getMaxTasks() != null) {
      clientConfiguration.setMaxTasks(clientConfigurationProps.getMaxTasks());
    }
    if (clientConfigurationProps.getUsePriority() != null && !clientConfigurationProps.getUsePriority()) {
      clientConfiguration.setUsePriority(false);
    }
    if (clientConfigurationProps.getDefaultSerializationFormat() != null) {
      clientConfiguration.setDefaultSerializationFormat(clientConfigurationProps.getDefaultSerializationFormat());
    }
    if (clientConfigurationProps.getDateFormat() != null) {
      clientConfiguration.setDateFormat(clientConfigurationProps.getDateFormat());
    }
    if (clientConfigurationProps.getLockDuration() != null) {
      clientConfiguration.setLockDuration(clientConfigurationProps.getLockDuration());
    }
    if (clientConfigurationProps.getAsyncResponseTimeout() != null) {
      clientConfiguration.setAsyncResponseTimeout(clientConfigurationProps.getAsyncResponseTimeout());
    }
    if (clientConfigurationProps.getDisableAutoFetching() != null &&
        clientConfigurationProps.getDisableAutoFetching()) {
      clientConfiguration.setDisableAutoFetching(true);
    }
    if (clientConfigurationProps.getDisableBackoffStrategy() != null &&
        clientConfigurationProps.getDisableBackoffStrategy()) {
      clientConfiguration.setDisableBackoffStrategy(true);
    }
    setClientConfiguration(clientConfiguration);
  }

}