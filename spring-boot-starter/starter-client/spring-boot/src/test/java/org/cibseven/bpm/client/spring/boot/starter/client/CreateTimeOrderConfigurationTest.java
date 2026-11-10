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

import org.cibseven.bpm.client.spring.boot.starter.ClientProperties;
import org.cibseven.bpm.client.spring.boot.starter.MockHelper;
import org.cibseven.bpm.client.spring.boot.starter.impl.PropertiesAwareClientFactory;
import org.cibseven.bpm.client.spring.exception.SpringExternalTaskClientException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class CreateTimeOrderConfigurationTest {

  @BeforeEach
  public void init() {
    MockHelper.initMocks();
  }

  @AfterEach
  public void reset() {
    MockHelper.reset();
  }

  @Test
  public void shouldNotOrderByDefault() {
    createClient(new ClientProperties());

    verify(MockHelper.getClientBuilder(), never()).orderByCreateTime();
  }

  @Test
  public void shouldOrderByCreateTimeAsc() {
    ClientProperties properties = new ClientProperties();
    properties.setOrderByCreateTime("asc");

    createClient(properties);

    verify(MockHelper.getClientBuilder()).orderByCreateTime();
    verify(MockHelper.getClientBuilder()).asc();
  }

  @Test
  public void shouldOrderByCreateTimeDesc() {
    ClientProperties properties = new ClientProperties();
    properties.setOrderByCreateTime("desc");

    createClient(properties);

    verify(MockHelper.getClientBuilder()).orderByCreateTime();
    verify(MockHelper.getClientBuilder()).desc();
  }

  @Test
  public void shouldOrderByCreateTimeDescWhenUseCreateTime() {
    ClientProperties properties = new ClientProperties();
    properties.setUseCreateTime(true);

    createClient(properties);

    verify(MockHelper.getClientBuilder()).orderByCreateTime();
    verify(MockHelper.getClientBuilder()).desc();
  }

  @Test
  public void shouldRefuseBothKeys() {
    ClientProperties properties = new ClientProperties();
    properties.setOrderByCreateTime("asc");
    properties.setUseCreateTime(true);

    assertThatThrownBy(() -> createClient(properties))
        .isInstanceOf(SpringExternalTaskClientException.class)
        .hasMessageContaining("Both \"useCreateTime\" and \"orderByCreateTime\" are enabled");
  }

  @Test
  public void shouldRefuseInvalidOrder() {
    ClientProperties properties = new ClientProperties();
    properties.setOrderByCreateTime("sideways");

    assertThatThrownBy(() -> createClient(properties))
        .isInstanceOf(SpringExternalTaskClientException.class)
        .hasMessageContaining("Invalid value sideways");
  }

  protected void createClient(ClientProperties properties) {
    PropertiesAwareClientFactory factory = new PropertiesAwareClientFactory();
    factory.applyPropertiesFrom(properties);
    factory.getObject();
  }

}
