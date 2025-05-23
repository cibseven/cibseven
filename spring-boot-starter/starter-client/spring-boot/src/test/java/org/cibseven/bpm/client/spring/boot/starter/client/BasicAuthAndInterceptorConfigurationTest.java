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
package org.cibseven.bpm.client.spring.boot.starter.client;

import org.cibseven.bpm.client.ExternalTaskClientBuilder;
import org.cibseven.bpm.client.interceptor.ClientRequestInterceptor;
import org.cibseven.bpm.client.interceptor.auth.BasicAuthProvider;
import org.cibseven.bpm.client.spring.boot.starter.MockHelper;
import org.cibseven.bpm.client.spring.boot.starter.ParsePropertiesHelper;
import org.cibseven.bpm.client.spring.boot.starter.client.configuration.RequestInterceptorConfiguration;
import org.cibseven.bpm.client.spring.boot.starter.client.configuration.SimpleSubscriptionConfiguration;
import org.cibseven.bpm.client.spring.boot.starter.impl.ClientAutoConfiguration;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@TestPropertySource(properties = {
    "camunda.bpm.client.basic-auth.username=my-username",
    "camunda.bpm.client.basic-auth.password=my-password",
})
@ContextConfiguration(classes = {
    ParsePropertiesHelper.TestConfig.class,
    ClientAutoConfiguration.class,
    SimpleSubscriptionConfiguration.class,
    RequestInterceptorConfiguration.class
})
@RunWith(SpringRunner.class)
public class BasicAuthAndInterceptorConfigurationTest extends ParsePropertiesHelper {

  protected static ExternalTaskClientBuilder clientBuilder;
  
  @Rule
  public MockitoRule mockitoRule = MockitoJUnit.rule();

  @BeforeClass
  public static void initMocks() {
    MockHelper.initMocks();
    clientBuilder = MockHelper.getClientBuilder();
  }

  @AfterClass
  public static void reset() {
    MockHelper.reset();
  }

  @Autowired
  protected ClientRequestInterceptor interceptorOne;

  @Autowired
  protected ClientRequestInterceptor interceptorTwo;

  @Test
  public void shouldVerifyBasicAuthAndInterceptors() {
    ArgumentCaptor<ClientRequestInterceptor> interceptorCaptor =
        ArgumentCaptor.forClass(ClientRequestInterceptor.class);
    verify(clientBuilder, times(3))
        .addInterceptor(interceptorCaptor.capture());

    assertThat(interceptorCaptor.getAllValues().size()).isEqualTo(3);
    assertThat(interceptorCaptor.getAllValues())
        .containsOnlyOnce(interceptorOne, interceptorTwo);
    assertThat(interceptorCaptor.getAllValues())
        .extracting("class")
        .containsOnlyOnce(BasicAuthProvider.class);
  }

}
