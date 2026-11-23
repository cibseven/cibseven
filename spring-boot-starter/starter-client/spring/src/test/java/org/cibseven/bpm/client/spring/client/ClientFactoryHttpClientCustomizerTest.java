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

import java.util.ArrayList;
import java.util.List;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.util.Timeout;
import org.cibseven.bpm.client.ExternalTaskClient;
import org.cibseven.bpm.client.spring.ExternalTaskClientHttpCustomizer;
import org.cibseven.bpm.client.spring.impl.client.ClientConfiguration;
import org.cibseven.bpm.client.spring.impl.client.ClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Builds a real client (no mocked builder) to check that the customizers receive the
 * HTTP client builder the external task client is built from.
 */
public class ClientFactoryHttpClientCustomizerTest {

  protected ClientFactory clientFactory;

  @AfterEach
  public void stopClient() {
    if (clientFactory != null) {
      clientFactory.destroy();
    }
  }

  @Test
  public void shouldPassHttpClientBuilderToCustomizersBeforeClientIsBuilt() {
    // given
    List<HttpClientBuilder> customizedBuilders = new ArrayList<>();
    ExternalTaskClientHttpCustomizer responseTimeout = builder -> {
      customizedBuilders.add(builder);
      builder.setDefaultRequestConfig(RequestConfig.custom()
          .setResponseTimeout(Timeout.ofSeconds(30))
          .build());
    };
    ExternalTaskClientHttpCustomizer second = customizedBuilders::add;

    clientFactory = createClientFactory();
    clientFactory.setHttpClientCustomizers(List.of(responseTimeout, second));

    // when
    ExternalTaskClient client = clientFactory.getObject();

    // then
    assertThat(client).isNotNull();
    assertThat(customizedBuilders).hasSize(2);
    assertThat(customizedBuilders.get(0)).isNotNull().isSameAs(customizedBuilders.get(1));
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
    configuration.setBaseUrl("http://localhost:8080/engine-rest");
    configuration.setDisableAutoFetching(true);

    ClientFactory factory = new ClientFactory();
    factory.setClientConfiguration(configuration);
    return factory;
  }

}
