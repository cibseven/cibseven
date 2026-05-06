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

import org.cibseven.connect.ai.agent.impl.AgentResponseImpl;
import org.junit.Test;

public class AgentResponseTest {

  @Test
  public void shouldExposeOutputViaTypedGetter() {
    AgentResponseImpl response = new AgentResponseImpl("The answer is 42.");
    assertThat(response.getOutput()).isEqualTo("The answer is 42.");
  }

  @Test
  public void shouldExposeOutputInGenericParameterMap() {
    AgentResponseImpl response = new AgentResponseImpl("response text");
    assertThat((String) response.getResponseParameter(AgentConnector.PARAM_NAME_OUTPUT))
        .isEqualTo("response text");
  }

  @Test
  public void shouldHandleNullOutput() {
    AgentResponseImpl response = new AgentResponseImpl(null);
    assertThat(response.getOutput()).isNull();
    assertThat((String) response.getResponseParameter(AgentConnector.PARAM_NAME_OUTPUT)).isNull();
  }

  @Test
  public void shouldDefaultMemoryIdToEmpty() {
    AgentResponseImpl response = new AgentResponseImpl("answer");
    assertThat(response.getMemoryId()).isEqualTo("");
    Object memoryIdParam = response.getResponseParameter(AgentConnector.PARAM_NAME_MEMORY_ID);
    assertThat(memoryIdParam).isEqualTo("");
  }

  @Test
  public void shouldExposeMemoryIdViaTypedGetterAndParameterMap() {
    AgentResponseImpl response = new AgentResponseImpl("answer", "mem-42");
    assertThat(response.getMemoryId()).isEqualTo("mem-42");
    assertThat((String) response.getResponseParameter(AgentConnector.PARAM_NAME_MEMORY_ID))
        .isEqualTo("mem-42");
  }

  @Test
  public void shouldExposeNullMemoryIdParameterWhenNull() {
    // The memoryId key must always be present in the response parameter map — even when null —
    // so BPMN output mappings like ${memoryId} resolve cleanly instead of throwing PropertyNotFoundException.
    AgentResponseImpl response = new AgentResponseImpl("answer", null);
    assertThat(response.getResponseParameters()).containsKey(AgentConnector.PARAM_NAME_MEMORY_ID);
    assertThat((String) response.getResponseParameter(AgentConnector.PARAM_NAME_MEMORY_ID)).isNull();
  }

}
