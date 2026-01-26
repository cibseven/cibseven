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
package org.cibseven.bpm.engine.test.bpmn.callactivity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.repository.Deployment;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests for Call Activity recursion detection and depth limiting.
 *
 * @author CIB Seven Team
 */
public class CallActivityRecursionTest {

  @Rule
  public ProvidedProcessEngineRule rule = new ProvidedProcessEngineRule();

  private ProcessEngineConfigurationImpl configuration;
  private int originalMaxDepth;

  @Before
  public void setUp() {
    configuration = (ProcessEngineConfigurationImpl) rule.getProcessEngine().getProcessEngineConfiguration();
    originalMaxDepth = configuration.getMaxCallActivityRecursionDepth();
  }

  @After
  public void tearDown() {
    // Restore original configuration
    configuration.setMaxCallActivityRecursionDepth(originalMaxDepth);
  }

  /**
   * Test direct recursion: Process A calls itself
   */
  @Test
  public void testDirectRecursionDetected() {
    // Set a reasonable limit
    configuration.setMaxCallActivityRecursionDepth(5);

    // Create a process that calls itself
    BpmnModelInstance processA = Bpmn.createExecutableProcess("processA")
        .startEvent()
        .callActivity()
          .calledElement("processA")
        .endEvent()
        .done();

    Deployment deployment = rule.getRepositoryService()
        .createDeployment()
        .addModelInstance("processA.bpmn", processA)
        .deploy();
    rule.manageDeployment(deployment);

    // Starting the process should fail with a recursion error
    assertThatThrownBy(() -> rule.getRuntimeService().startProcessInstanceByKey("processA"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Recursive Call Activity detected")
        .hasMessageContaining("processA")
        .hasMessageContaining("cycle detected");
  }

  /**
   * Test indirect recursion: Process A -> Process B -> Process A
   */
  @Test
  public void testIndirectRecursionDetected() {
    // Set a reasonable limit
    configuration.setMaxCallActivityRecursionDepth(5);

    // Create Process A that calls Process B
    BpmnModelInstance processA = Bpmn.createExecutableProcess("processA")
        .startEvent()
        .callActivity()
          .calledElement("processB")
        .endEvent()
        .done();

    // Create Process B that calls Process A (creates a cycle)
    BpmnModelInstance processB = Bpmn.createExecutableProcess("processB")
        .startEvent()
        .callActivity()
          .calledElement("processA")
        .endEvent()
        .done();

    Deployment deployment = rule.getRepositoryService()
        .createDeployment()
        .addModelInstance("processA.bpmn", processA)
        .addModelInstance("processB.bpmn", processB)
        .deploy();
    rule.manageDeployment(deployment);

    // Starting Process A should fail when B tries to call A again
    assertThatThrownBy(() -> rule.getRuntimeService().startProcessInstanceByKey("processA"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Recursive Call Activity detected")
        .hasMessageContaining("processA")
        .hasMessageContaining("cycle detected");
  }

  /**
   * Test three-way recursion: Process A -> B -> C -> A
   */
  @Test
  public void testThreeWayRecursionDetected() {
    // Set a reasonable limit
    configuration.setMaxCallActivityRecursionDepth(10);

    // Create Process A that calls Process B
    BpmnModelInstance processA = Bpmn.createExecutableProcess("processA")
        .startEvent()
        .callActivity()
          .calledElement("processB")
        .endEvent()
        .done();

    // Create Process B that calls Process C
    BpmnModelInstance processB = Bpmn.createExecutableProcess("processB")
        .startEvent()
        .callActivity()
          .calledElement("processC")
        .endEvent()
        .done();

    // Create Process C that calls Process A (creates a cycle)
    BpmnModelInstance processC = Bpmn.createExecutableProcess("processC")
        .startEvent()
        .callActivity()
          .calledElement("processA")
        .endEvent()
        .done();

    Deployment deployment = rule.getRepositoryService()
        .createDeployment()
        .addModelInstance("processA.bpmn", processA)
        .addModelInstance("processB.bpmn", processB)
        .addModelInstance("processC.bpmn", processC)
        .deploy();
    rule.manageDeployment(deployment);

    // Starting Process A should fail when C tries to call A again
    assertThatThrownBy(() -> rule.getRuntimeService().startProcessInstanceByKey("processA"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Recursive Call Activity detected")
        .hasMessageContaining("processA")
        .hasMessageContaining("cycle detected");
  }

  /**
   * Test maximum depth limit for non-cyclic calls
   */
  @Test
  public void testMaxDepthExceeded() {
    // Set a low limit for testing
    configuration.setMaxCallActivityRecursionDepth(3);

    // Create a chain: Process A -> B -> C -> D (depth 4, exceeds limit of 3)
    BpmnModelInstance processA = Bpmn.createExecutableProcess("processA")
        .startEvent()
        .callActivity()
          .calledElement("processB")
        .endEvent()
        .done();

    BpmnModelInstance processB = Bpmn.createExecutableProcess("processB")
        .startEvent()
        .callActivity()
          .calledElement("processC")
        .endEvent()
        .done();

    BpmnModelInstance processC = Bpmn.createExecutableProcess("processC")
        .startEvent()
        .callActivity()
          .calledElement("processD")
        .endEvent()
        .done();

    BpmnModelInstance processD = Bpmn.createExecutableProcess("processD")
        .startEvent()
        .userTask()
        .endEvent()
        .done();

    Deployment deployment = rule.getRepositoryService()
        .createDeployment()
        .addModelInstance("processA.bpmn", processA)
        .addModelInstance("processB.bpmn", processB)
        .addModelInstance("processC.bpmn", processC)
        .addModelInstance("processD.bpmn", processD)
        .deploy();
    rule.manageDeployment(deployment);

    // Starting Process A should fail when trying to call D at depth 4
    assertThatThrownBy(() -> rule.getRuntimeService().startProcessInstanceByKey("processA"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("recursion depth limit exceeded")
        .hasMessageContaining("Maximum depth is 3");
  }

  /**
   * Test that non-cyclic calls within the depth limit work correctly
   */
  @Test
  public void testNonCyclicCallsWithinLimitSucceed() {
    // Set a reasonable limit
    configuration.setMaxCallActivityRecursionDepth(5);

    // Create a chain: Process A -> B -> C (depth 3, within limit of 5)
    BpmnModelInstance processA = Bpmn.createExecutableProcess("processA")
        .startEvent()
        .callActivity()
          .calledElement("processB")
        .endEvent()
        .done();

    BpmnModelInstance processB = Bpmn.createExecutableProcess("processB")
        .startEvent()
        .callActivity()
          .calledElement("processC")
        .endEvent()
        .done();

    BpmnModelInstance processC = Bpmn.createExecutableProcess("processC")
        .startEvent()
        .userTask("task")
        .endEvent()
        .done();

    Deployment deployment = rule.getRepositoryService()
        .createDeployment()
        .addModelInstance("processA.bpmn", processA)
        .addModelInstance("processB.bpmn", processB)
        .addModelInstance("processC.bpmn", processC)
        .deploy();
    rule.manageDeployment(deployment);

    // This should succeed
    ProcessInstance processInstance = rule.getRuntimeService().startProcessInstanceByKey("processA");
    assertThat(processInstance).isNotNull();

    // There should be a task from processC
    Task task = rule.getTaskService().createTaskQuery().singleResult();
    assertThat(task).isNotNull();
    assertThat(task.getTaskDefinitionKey()).isEqualTo("task");

    // Clean up
    rule.getTaskService().complete(task.getId());
  }

  /**
   * Test that the check can be disabled by setting the limit to 0
   */
  @Test
  public void testRecursionCheckDisabledWithZero() {
    // Disable the check
    configuration.setMaxCallActivityRecursionDepth(0);

    // Create a process that calls itself - this would normally fail
    BpmnModelInstance processA = Bpmn.createExecutableProcess("processA")
        .startEvent()
        .userTask("beforeCall")
        .callActivity()
          .calledElement("processA")
        .endEvent()
        .done();

    Deployment deployment = rule.getRepositoryService()
        .createDeployment()
        .addModelInstance("processA.bpmn", processA)
        .deploy();
    rule.manageDeployment(deployment);

    // Starting the process should succeed (check is disabled)
    ProcessInstance processInstance = rule.getRuntimeService().startProcessInstanceByKey("processA");
    assertThat(processInstance).isNotNull();

    // There should be a task from the first instance
    Task task = rule.getTaskService().createTaskQuery().singleResult();
    assertThat(task).isNotNull();
    assertThat(task.getTaskDefinitionKey()).isEqualTo("beforeCall");

    // Clean up - don't complete the task as that would trigger the recursive call
    rule.getRuntimeService().deleteProcessInstance(processInstance.getId(), "test cleanup");
  }

  /**
   * Test that the check can be disabled by setting the limit to -1
   */
  @Test
  public void testRecursionCheckDisabledWithNegative() {
    // Disable the check
    configuration.setMaxCallActivityRecursionDepth(-1);

    // Create a process that calls itself - this would normally fail
    BpmnModelInstance processA = Bpmn.createExecutableProcess("processA")
        .startEvent()
        .userTask("beforeCall")
        .callActivity()
          .calledElement("processA")
        .endEvent()
        .done();

    Deployment deployment = rule.getRepositoryService()
        .createDeployment()
        .addModelInstance("processA.bpmn", processA)
        .deploy();
    rule.manageDeployment(deployment);

    // Starting the process should succeed (check is disabled)
    ProcessInstance processInstance = rule.getRuntimeService().startProcessInstanceByKey("processA");
    assertThat(processInstance).isNotNull();

    // There should be a task from the first instance
    Task task = rule.getTaskService().createTaskQuery().singleResult();
    assertThat(task).isNotNull();
    assertThat(task.getTaskDefinitionKey()).isEqualTo("beforeCall");

    // Clean up - don't complete the task as that would trigger the recursive call
    rule.getRuntimeService().deleteProcessInstance(processInstance.getId(), "test cleanup");
  }

  /**
   * Test that deeply nested but non-cyclic calls work up to the limit
   */
  @Test
  public void testDeepNonCyclicCallsAtLimit() {
    // Set limit to exactly 3
    configuration.setMaxCallActivityRecursionDepth(3);

    // Create a chain at exactly the limit: A -> B -> C (depth 3)
    BpmnModelInstance processA = Bpmn.createExecutableProcess("processA")
        .startEvent()
        .callActivity()
          .calledElement("processB")
        .endEvent()
        .done();

    BpmnModelInstance processB = Bpmn.createExecutableProcess("processB")
        .startEvent()
        .callActivity()
          .calledElement("processC")
        .endEvent()
        .done();

    BpmnModelInstance processC = Bpmn.createExecutableProcess("processC")
        .startEvent()
        .userTask("task")
        .endEvent()
        .done();

    Deployment deployment = rule.getRepositoryService()
        .createDeployment()
        .addModelInstance("processA.bpmn", processA)
        .addModelInstance("processB.bpmn", processB)
        .addModelInstance("processC.bpmn", processC)
        .deploy();
    rule.manageDeployment(deployment);

    // This should succeed at exactly the limit
    ProcessInstance processInstance = rule.getRuntimeService().startProcessInstanceByKey("processA");
    assertThat(processInstance).isNotNull();

    Task task = rule.getTaskService().createTaskQuery().singleResult();
    assertThat(task).isNotNull();

    // Clean up
    rule.getTaskService().complete(task.getId());
  }

  /**
   * Test error message format for cycle detection
   */
  @Test
  public void testCycleDetectionErrorMessage() {
    configuration.setMaxCallActivityRecursionDepth(10);

    BpmnModelInstance processA = Bpmn.createExecutableProcess("processA")
        .startEvent()
        .callActivity()
          .calledElement("processB")
        .endEvent()
        .done();

    BpmnModelInstance processB = Bpmn.createExecutableProcess("processB")
        .startEvent()
        .callActivity()
          .calledElement("processA")
        .endEvent()
        .done();

    Deployment deployment = rule.getRepositoryService()
        .createDeployment()
        .addModelInstance("processA.bpmn", processA)
        .addModelInstance("processB.bpmn", processB)
        .deploy();
    rule.manageDeployment(deployment);

    assertThatThrownBy(() -> rule.getRuntimeService().startProcessInstanceByKey("processA"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Recursive Call Activity detected")
        .hasMessageContaining("processA")
        .hasMessageContaining("already present in the call hierarchy")
        .hasMessageContaining("call chain")
        .hasMessageContaining("maxCallActivityRecursionDepth");
  }
}
