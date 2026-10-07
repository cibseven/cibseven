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
package org.cibseven.bpm.engine.test.bpmn.event.error;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.util.ProcessEngineBootstrapRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;

import org.junit.jupiter.api.Test;


public class UnhandledBpmnErrorTest {

  @RegisterExtension
  @Order(1) public static ProcessEngineBootstrapRule bootstrapRule = new ProcessEngineBootstrapRule(configuration ->
      configuration.setEnableExceptionsAfterUnhandledBpmnError(true));
  @RegisterExtension
  @Order(2) protected ProvidedProcessEngineRule engineRule = new ProvidedProcessEngineRule(bootstrapRule);
  @RegisterExtension
  @Order(3) protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  protected RuntimeService runtimeService;
  protected TaskService taskService;

  @BeforeEach
  public void setUp() {
    runtimeService = engineRule.getRuntimeService();
    taskService = engineRule.getTaskService();
  }

  @Test
  public void testThrownInJavaDelegate() {

    // given
    BpmnModelInstance instance = Bpmn.createExecutableProcess("process")
        .startEvent()
        .serviceTask().camundaClass(ThrowBpmnErrorDelegate.class)
        .endEvent().done();
    testRule.deploy(instance);

    // when/then
    assertThatThrownBy(() -> runtimeService.startProcessInstanceByKey("process"))
      .isInstanceOf(ProcessEngineException.class)
      .hasMessageContaining("no error handler");
  }

  @Test
  @Deployment
  public void testUncaughtErrorSimpleProcess() {

    // given simple process definition

    // when/then
    assertThatThrownBy(() -> runtimeService.startProcessInstanceByKey("process"))
      .isInstanceOf(ProcessEngineException.class)
      .hasMessageContaining("no error handler");
  }

  @Test
  @Deployment
  public void testUnhandledErrorInEmbeddedSubprocess() {
    // given
    runtimeService.startProcessInstanceByKey("boundaryErrorOnEmbeddedSubprocess");

    // assume
    // After process start, usertask in subprocess should exist
    Task task = taskService.createTaskQuery().singleResult();
    assertEquals("subprocessTask", task.getName());

    // when/then
    // After task completion, error end event is reached which is never caught in the process
    assertThatThrownBy(() -> taskService.complete(task.getId()))
      .isInstanceOf(ProcessEngineException.class)
      .hasMessageContaining("no error handler");
  }

  @Test
  @Deployment(resources = {
      "org/cibseven/bpm/engine/test/bpmn/event/error/UnhandledBpmnErrorTest.testUncaughtErrorOnCallActivity.bpmn20.xml",
      "org/cibseven/bpm/engine/test/bpmn/event/error/UnhandledBpmnErrorTest.subprocess.bpmn20.xml" })
  public void testUncaughtErrorOnCallActivity() {
    // given
    runtimeService.startProcessInstanceByKey("uncaughtErrorOnCallActivity");

    // assume
    Task task = taskService.createTaskQuery().singleResult();
    assertEquals("Task in subprocess", task.getName());

    // when/then
    // Completing the task will reach the end error event,
    // which is never caught in the process
    assertThatThrownBy(() -> taskService.complete(task.getId()))
      .isInstanceOf(ProcessEngineException.class)
      .hasMessageContaining("no error handler");
  }

  @Test
  @Deployment
  public void testUncaughtErrorOnEventSubprocess() {

    // given
    runtimeService.startProcessInstanceByKey("process").getId();

    // assume
    Task task = taskService.createTaskQuery().singleResult();
    assertEquals("subprocessTask", task.getName());

    // when/then
    // After task completion, error end event is reached which is never caught in the process
    assertThatThrownBy(() -> taskService.complete(task.getId()))
      .isInstanceOf(ProcessEngineException.class)
      .hasMessageContaining("no error handler");
  }
}