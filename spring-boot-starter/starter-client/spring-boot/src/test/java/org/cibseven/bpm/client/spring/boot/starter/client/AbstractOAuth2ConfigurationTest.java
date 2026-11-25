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
package org.cibseven.bpm.client.spring.boot.starter.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.cibseven.bpm.client.ExternalTaskClientBuilder;
import org.cibseven.bpm.client.interceptor.ClientRequestInterceptor;
import org.cibseven.bpm.client.interceptor.auth.OAuth2ClientCredentialsProvider;
import org.cibseven.bpm.client.spring.boot.starter.MockHelper;
import org.cibseven.bpm.client.spring.boot.starter.ParsePropertiesHelper;
import org.cibseven.bpm.client.spring.boot.starter.client.configuration.SimpleSubscriptionConfiguration;
import org.cibseven.bpm.client.spring.boot.starter.impl.ClientAutoConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * A complete OAuth2 client secret configuration; subclasses add
 * {@code camunda.bpm.client.oauth2.enabled} (or leave it unset).
 */
@TestPropertySource(properties = {
    "camunda.bpm.client.oauth2.token-uri=http://localhost:1/token",
    "camunda.bpm.client.oauth2.client-id=my-client",
    "camunda.bpm.client.oauth2.client-secret=my-secret",
})
@ContextConfiguration(classes = {
    ParsePropertiesHelper.TestConfig.class,
    ClientAutoConfiguration.class,
    SimpleSubscriptionConfiguration.class
})
@ExtendWith(SpringExtension.class)
@ExtendWith(MockitoExtension.class)
public abstract class AbstractOAuth2ConfigurationTest extends ParsePropertiesHelper {

  protected static ExternalTaskClientBuilder clientBuilder;

  @BeforeAll
  public static void initMocks() {
    MockHelper.initMocks();
    clientBuilder = MockHelper.getClientBuilder();
  }

  @AfterAll
  public static void reset() {
    MockHelper.reset();
  }

  protected void assertOAuth2InterceptorRegistered() {
    ArgumentCaptor<ClientRequestInterceptor> argumentCaptor =
        ArgumentCaptor.forClass(ClientRequestInterceptor.class);
    verify(clientBuilder).addInterceptor(argumentCaptor.capture());

    assertThat(argumentCaptor.getValue())
        .isInstanceOf(OAuth2ClientCredentialsProvider.class)
        .extracting("tokenUri", "clientId", "clientSecret")
        .containsExactly("http://localhost:1/token", "my-client", "my-secret");
  }

  protected void assertNoInterceptorRegistered() {
    verify(clientBuilder, never()).addInterceptor(any());
  }

}
