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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.cibseven.connect.ai.agent.impl.AgentConnectorImpl;
import org.cibseven.connect.ai.agent.impl.AgentConnectorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AgentConnectorTest {

  private AgentConnectorImpl connector;

  @BeforeEach
  public void setUp() {
    connector = new AgentConnectorImpl();
  }

  @Test
  public void shouldReturnCorrectConnectorId() {
    assertThat(connector.getId()).isEqualTo(AgentConnector.ID);
    assertThat(connector.getId()).isEqualTo("cibseven-ai-agent");
  }

  @Test
  public void shouldCreateRequestInstance() {
    AgentRequest request = connector.createRequest();
    assertThat(request).isNotNull();
    assertThat(request).isInstanceOf(org.cibseven.connect.ai.agent.impl.AgentRequestImpl.class);
  }

  @Test
  public void shouldFailExecutionWhenAgentNameIsNull() {
    AgentRequest request = connector.createRequest()
        .instruction("You are a helpful assistant.")
        .message("Hello");

    assertThatThrownBy(request::execute)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("invalid");
  }

  @Test
  public void shouldFailExecutionWhenMessageIsNull() {
    AgentRequest request = connector.createRequest()
        .agentName("test-agent")
        .instruction("You are helpful.");

    assertThatThrownBy(request::execute)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("invalid");
  }

  @Test
  public void shouldThrowExceptionForToolClassWithNoPublicConstructor() {
    // java.lang.Math has a private constructor and cannot be instantiated.
    AgentConnectorImpl connectorUnderTest = new AgentConnectorImpl();
    AgentRequest request = connectorUnderTest.createRequest()
        .agentName("agent")
        .instruction("inst")
        .message("msg")
        .toolClasses("java.lang.Math");

    assertThatThrownBy(() -> connectorUnderTest.execute(request))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("java.lang.Math");
  }

  @Test
  public void shouldThrowExceptionForNonexistentToolClass() {
    AgentConnectorImpl connectorUnderTest = new AgentConnectorImpl();
    AgentRequest request = connectorUnderTest.createRequest()
        .agentName("agent")
        .instruction("inst")
        .message("msg")
        .toolClasses("com.example.NonExistentClass");

    assertThatThrownBy(() -> connectorUnderTest.execute(request))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("com.example.NonExistentClass");
  }

}
