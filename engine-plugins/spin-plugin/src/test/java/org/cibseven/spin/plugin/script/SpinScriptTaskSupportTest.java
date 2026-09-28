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
package org.cibseven.spin.plugin.script;

import static org.assertj.core.api.Assertions.assertThat;

import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.repository.Deployment;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

/**
 * @author Daniel Meyer
 *
 */
public class SpinScriptTaskSupportTest {

  @RegisterExtension
  public ProcessEngineRule engineRule = new ProcessEngineRule();

  public static Object[] data() {
      return new Object[][] {
               { "groovy", "" },
               { "javascript", "" },
               { "python", "" },
               { "ruby", "$" }
         };
  }


  private RuntimeService runtimeService;
  private RepositoryService repositoryService;


  @BeforeEach
  public void setUp() {
    this.runtimeService = engineRule.getRuntimeService();
    this.repositoryService = engineRule.getRepositoryService();
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testSpinAvailable(String language, String variablePrefix) {
    deployProcess(language, setVariableScript(variablePrefix, "name", "S('<test />').name()"));
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("testProcess");

    String var = (String) runtimeService.getVariable(pi.getId(), "name");
    assertThat(var).isEqualTo("test");
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testTwoScriptTasks(String language, String variablePrefix) {
    // given
    BpmnModelInstance modelInstance = Bpmn.createExecutableProcess("testProcess")
      .startEvent()
      .scriptTask()
        .scriptFormat(language)
        .scriptText(setVariableScript(variablePrefix, "task1Name", "S('<task1 />').name()"))
      .scriptTask()
        .scriptFormat(language)
        .scriptText(setVariableScript(variablePrefix, "task2Name", "S('<task2 />').name()"))
      .userTask()
      .endEvent()
    .done();

    Deployment deployment = repositoryService.createDeployment().addModelInstance("process.bpmn", modelInstance).deploy();
    engineRule.manageDeployment(deployment);

    // when
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("testProcess");

    // then
    Object task1Name = runtimeService.getVariable(pi.getId(), "task1Name");
    assertThat(task1Name).isEqualTo("task1");

    Object task2Name = runtimeService.getVariable(pi.getId(), "task2Name");
    assertThat(task2Name).isEqualTo("task2");
  }

  protected String setVariableScript(String variablePrefix, String name, String valueExpression) {
    return variablePrefix + "execution" + ".setVariable('" + name + "',  " + valueExpression + ")";
  }

  protected void deployProcess(String scriptFormat, String scriptText) {
    BpmnModelInstance process = createProcess(scriptFormat, scriptText);
    Deployment deployment = repositoryService.createDeployment()
      .addModelInstance("testProcess.bpmn", process)
      .deploy();

    engineRule.manageDeployment(deployment);
  }

  protected BpmnModelInstance createProcess(String scriptFormat, String scriptText) {

    return Bpmn.createExecutableProcess("testProcess")
      .startEvent()
      .scriptTask()
        .scriptFormat(scriptFormat)
        .scriptText(scriptText)
      .userTask()
      .endEvent()
    .done();

  }
}
