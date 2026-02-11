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
package org.cibseven.bpm.engine.test.api.cfg;

import static org.assertj.core.api.Assertions.assertThat;

import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.util.ReflectUtil;
import org.cibseven.bpm.engine.test.util.ProcessEngineBootstrapClassExtension;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;


public class CustomExpressionManagerFunctionsTest {


  @RegisterExtension
  public static ProcessEngineBootstrapClassExtension processEngineBootstrapClassExtension = ProcessEngineBootstrapClassExtension.builder()
    .useDefaultResource()
    .addProcessEngineTestRule()
    .build();

  protected ProcessEngineConfigurationImpl processEngineConfiguration;
  protected RuntimeService runtimeService;
  private ProvidedProcessEngineRule engineRule = null;
  private ProcessEngineTestRule testRule = null;

  @BeforeEach
  public void initializeServices() {
    processEngineConfiguration = engineRule.getProcessEngineConfiguration();
    runtimeService = engineRule.getRuntimeService();
  }

  @Test
  public void shouldResolveCustomFunction() {
    // given
    processEngineConfiguration.getExpressionManager().addFunction("foobar", ReflectUtil.getMethod(TestFunctions.class, "foobar"));
    testRule.deploy(Bpmn.createExecutableProcess("process")
        .camundaHistoryTimeToLive(180)
       .startEvent()
       .serviceTask().camundaExpression("${execution.setVariable(\"baz\", foobar())}")
       .userTask()
       .endEvent()
       .done());
    // when
    String processInstanceId = runtimeService.startProcessInstanceByKey("process").getId();
    // then
    assertThat(runtimeService.getVariable(processInstanceId, "baz")).isEqualTo("foobar");
  }

  @Test
  public void shouldResolveCustomPrefixedFunction() {
    // given
    processEngineConfiguration.getExpressionManager().addFunction("foo:bar", ReflectUtil.getMethod(TestFunctions.class, "foobar"));
    testRule.deploy(Bpmn.createExecutableProcess("process")
        .camundaHistoryTimeToLive(180)
        .startEvent()
        .serviceTask().camundaExpression("${execution.setVariable(\"baz\", foo:bar())}")
        .userTask()
        .endEvent()
        .done());
     // when
     String processInstanceId = runtimeService.startProcessInstanceByKey("process").getId();
     // then
     assertThat(runtimeService.getVariable(processInstanceId, "baz")).isEqualTo("foobar");
  }

  public static class TestFunctions {
    public static String foobar() {
      return "foobar";
    }
  }
}
