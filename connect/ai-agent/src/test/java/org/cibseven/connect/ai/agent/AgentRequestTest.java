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

import org.cibseven.connect.ai.agent.impl.AgentConnectorImpl;
import org.cibseven.connect.ai.agent.impl.AgentRequestImpl;
import org.junit.Before;
import org.junit.Test;

public class AgentRequestTest {

  private AgentConnectorImpl connector;

  @Before
  public void setUp() {
    connector = new AgentConnectorImpl();
  }

  @Test
  public void shouldSetAndGetAgentName() {
    AgentRequest request = connector.createRequest().agentName("my-agent");
    assertThat(request.getAgentName()).isEqualTo("my-agent");
  }

  @Test
  public void shouldSetAndGetAgentDescription() {
    AgentRequest request = connector.createRequest().agentDescription("An agent that does things");
    assertThat(request.getAgentDescription()).isEqualTo("An agent that does things");
  }

  @Test
  public void shouldSetAndGetInstruction() {
    AgentRequest request = connector.createRequest().instruction("You are helpful.");
    assertThat(request.getInstruction()).isEqualTo("You are helpful.");
  }

  @Test
  public void shouldReturnDefaultModelWhenNotSet() {
    AgentRequest request = connector.createRequest();
    assertThat(request.getModel()).isEqualTo(AgentConnectorConstants.DEFAULT_MODEL);
  }

  @Test
  public void shouldSetAndGetModel() {
    AgentRequest request = connector.createRequest().model("gpt-5.4-nano");
    assertThat(request.getModel()).isEqualTo("gpt-5.4-nano");
  }

  @Test
  public void shouldSetAndGetMessage() {
    AgentRequest request = connector.createRequest().message("What is the weather?");
    assertThat(request.getMessage()).isEqualTo("What is the weather?");
  }

  @Test
  public void shouldSetAndGetToolClasses() {
    String tools = "com.example.WeatherTools,com.example.CalendarTools";
    AgentRequest request = connector.createRequest().toolClasses(tools);
    assertThat(request.getToolClasses()).isEqualTo(tools);
  }

  @Test
  public void shouldSetAndGetApiKey() {
    AgentRequest request = connector.createRequest().apiKey("sk-test-key");
    assertThat(request.getApiKey()).isEqualTo("sk-test-key");
  }

  @Test
  public void shouldSetAndGetBaseUrl() {
    AgentRequest request = connector.createRequest().baseUrl("http://localhost:11434/v1");
    assertThat(request.getBaseUrl()).isEqualTo("http://localhost:11434/v1");
  }

  @Test
  public void shouldStoreParametersInGenericMap() {
    AgentRequest request = connector.createRequest()
        .agentName("my-agent")
        .instruction("You are helpful.")
        .model("gpt-5.4-nano")
        .message("Hello");

    assertThat(request.getRequestParameters())
        .containsEntry(AgentConnector.PARAM_NAME_AGENT_NAME, "my-agent")
        .containsEntry(AgentConnector.PARAM_NAME_INSTRUCTION, "You are helpful.")
        .containsEntry(AgentConnector.PARAM_NAME_MODEL, "gpt-5.4-nano")
        .containsEntry(AgentConnector.PARAM_NAME_MESSAGE, "Hello");
  }

  @Test
  public void shouldSupportSettingParametersViaGenericMap() {
    AgentRequest request = connector.createRequest();
    request.setRequestParameter(AgentConnector.PARAM_NAME_AGENT_NAME, "map-agent");
    request.setRequestParameter(AgentConnector.PARAM_NAME_MESSAGE, "generic-message");

    assertThat(request.getAgentName()).isEqualTo("map-agent");
    assertThat(request.getMessage()).isEqualTo("generic-message");
  }

  @Test
  public void shouldBeInstanceOfAgentRequestImpl() {
    AgentRequest request = connector.createRequest();
    assertThat(request).isInstanceOf(AgentRequestImpl.class);
  }

  // ── persistChatLog round-trip (CIB7-1395) ────────────────────────────────

  @Test
  public void shouldReturnNullPersistChatLogByDefault() {
    AgentRequest request = connector.createRequest();
    assertThat(request.getPersistChatLog()).isNull();
  }

  @Test
  public void shouldRoundTripPersistChatLogBooleanTrue() {
    AgentRequest request = connector.createRequest().persistChatLog(Boolean.TRUE);
    assertThat(request.getPersistChatLog()).isTrue();
  }

  @Test
  public void shouldRoundTripPersistChatLogBooleanFalse() {
    AgentRequest request = connector.createRequest().persistChatLog(Boolean.FALSE);
    assertThat(request.getPersistChatLog()).isFalse();
  }

  @Test
  public void shouldTreatExplicitNullPersistChatLogAsUnset() {
    AgentRequest request = connector.createRequest().persistChatLog(null);
    assertThat(request.getPersistChatLog()).isNull();
  }

}
