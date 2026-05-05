/*
 * Copyright CIB seven GmbH and/or licensed to CIB seven GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB seven licenses this file to you under the Apache License,
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
package org.cibseven.connect.agentconnector;

import static org.assertj.core.api.Assertions.assertThat;

import org.cibseven.connect.agentconnector.impl.KnowledgeIngestorConnectorImpl;
import org.cibseven.connect.agentconnector.impl.KnowledgeIngestorProviderImpl;
import org.junit.Test;

public class KnowledgeIngestorProviderTest {

  @Test
  public void shouldReturnCorrectConnectorId() {
    KnowledgeIngestorProviderImpl provider = new KnowledgeIngestorProviderImpl();
    assertThat(provider.getConnectorId()).isEqualTo(KnowledgeIngestorConnector.ID);
  }

  @Test
  public void shouldCreateConnectorInstance() {
    KnowledgeIngestorProviderImpl provider = new KnowledgeIngestorProviderImpl();
    KnowledgeIngestorConnector connector = provider.createConnectorInstance();
    assertThat(connector).isNotNull();
    assertThat(connector.getId()).isEqualTo(KnowledgeIngestorConnector.ID);
  }

  @Test
  public void shouldCreateNewInstanceOnEachCall() {
    KnowledgeIngestorProviderImpl provider = new KnowledgeIngestorProviderImpl();
    KnowledgeIngestorConnector first = provider.createConnectorInstance();
    KnowledgeIngestorConnector second = provider.createConnectorInstance();
    assertThat(first).isNotSameAs(second);
  }

  @Test
  public void shouldCreateInstanceOfKnowledgeIngestorConnectorImpl() {
    KnowledgeIngestorProviderImpl provider = new KnowledgeIngestorProviderImpl();
    KnowledgeIngestorConnector connector = provider.createConnectorInstance();
    assertThat(connector).isInstanceOf(KnowledgeIngestorConnectorImpl.class);
  }

  @Test
  public void shouldBeDiscoverableViaServiceLoader() {
    boolean found = false;
    for (org.cibseven.connect.spi.ConnectorProvider provider :
        java.util.ServiceLoader.load(org.cibseven.connect.spi.ConnectorProvider.class)) {
      if (KnowledgeIngestorConnector.ID.equals(provider.getConnectorId())) {
        found = true;
        break;
      }
    }
    assertThat(found).as("KnowledgeIngestorProviderImpl must be discoverable via ServiceLoader").isTrue();
  }

}
