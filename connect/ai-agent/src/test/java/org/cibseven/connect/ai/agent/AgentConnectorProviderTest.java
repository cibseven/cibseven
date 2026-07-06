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
package org.cibseven.connect.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.cibseven.connect.ai.agent.impl.AgentConnectorProviderImpl;
import org.junit.jupiter.api.Test;

public class AgentConnectorProviderTest {

  @Test
  public void shouldReturnCorrectConnectorId() {
    AgentConnectorProviderImpl provider = new AgentConnectorProviderImpl();
    assertThat(provider.getConnectorId()).isEqualTo(AgentConnector.ID);
  }

  @Test
  public void shouldCreateConnectorInstance() {
    AgentConnectorProviderImpl provider = new AgentConnectorProviderImpl();
    AgentConnector connector = provider.createConnectorInstance();
    assertThat(connector).isNotNull();
    assertThat(connector.getId()).isEqualTo(AgentConnector.ID);
  }

  @Test
  public void shouldCreateNewInstanceOnEachCall() {
    AgentConnectorProviderImpl provider = new AgentConnectorProviderImpl();
    AgentConnector first = provider.createConnectorInstance();
    AgentConnector second = provider.createConnectorInstance();
    assertThat(first).isNotSameAs(second);
  }

}
