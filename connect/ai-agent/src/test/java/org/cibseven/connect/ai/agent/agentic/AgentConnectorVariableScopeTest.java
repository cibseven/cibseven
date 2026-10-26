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
package org.cibseven.connect.ai.agent.agentic;

import static org.assertj.core.api.Assertions.assertThat;

import org.cibseven.bpm.engine.impl.scripting.engine.VariableScopeResolver;
import org.cibseven.connect.plugin.impl.ConnectorVariableScope;
import org.junit.jupiter.api.Test;

/**
 * The connector's input and output parameters on an agentic scope are those of a service task's
 * connector, so a script among them has to find its variable scope under the same name.
 */
public class AgentConnectorVariableScopeTest {

  @Test
  public void aScriptFindsTheScopeUnderTheNameAServiceTaskGivesIt() {
    AgentConnectorVariableScope scope = new AgentConnectorVariableScope(null);

    assertThat(scope.getVariableScopeKey())
        .as("the key of the connect plugin's scope, which a service task's connector uses")
        .isEqualTo(new ConnectorVariableScope(null).getVariableScopeKey());

    VariableScopeResolver bindings = new VariableScopeResolver(scope);
    assertThat(bindings.containsKey("connector")).isTrue();
    assertThat(bindings.get("connector")).isSameAs(scope);
  }
}
