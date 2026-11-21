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
    originalMaxDepth = configuration.getMaxRecursiveCallIterations();
  }

  @After
  public void tearDown() {
    // Restore original configuration
    configuration.setMaxRecursiveCallIterations(originalMaxDepth);
  }

  /**
   * Test that maxRecursiveCallIterations is disabled by default (set to 0)
   */
  @Test
  public void testRecursionCheckDefaultValue() {
    // Verify the default value is 0
    assertThat(configuration.getMaxRecursiveCallIterations())
        .as("maxRecursiveCallIterations should have default value of 0")
        .isEqualTo(0);
  }

  /**
   * Test direct recursion: Process A calls itself
   */
  @Test
  public void testDirectRecursionDetected() {
    // Set a reasonable limit
    configuration.setMaxRecursiveCallIterations(5);

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

    // Starting the process should fail with a recursion cycle detected error
    // Process A calls itself, so on the first recursive call, A appears 1 time in chain
    // A further calls would surpass the allowed limit (5)
    assertThatThrownBy(() -> rule.getRuntimeService().startProcessInstanceByKey("processA"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Call Activity recursion cycle detected")
        .hasMessageContaining("Maximum allowed cycle iterations is 5")
        .hasMessageContaining("processA");
  }

  /**
   * Test indirect recursion: Process A -> Process B -> Process A
   */
  @Test
  public void testIndirectRecursionDetected() {
    // Set a reasonable limit
    configuration.setMaxRecursiveCallIterations(5);

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

    // Starting Process A should fail when cycle iteration limit is exceeded
    // A→B→A: When C calls A, A already appears 6 times, exceeding the allowed limit of 5
    assertThatThrownBy(() -> rule.getRuntimeService().startProcessInstanceByKey("processA"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Call Activity recursion cycle detected")
        .hasMessageContaining("Maximum allowed cycle iterations is 5")
        .hasMessageContaining("processA");
  }

  /**
   * Test three-way recursion: Process A -> B -> C -> A
   */
  @Test
  public void testThreeWayRecursionDetected() {
    // Set a reasonable limit
    configuration.setMaxRecursiveCallIterations(10);

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

    // Starting Process A should fail when cycle iteration limit is exceeded
    // A→B→C→A: When C tries to call A, A already appears 11 time, exceeds limit of 10
    assertThatThrownBy(() -> rule.getRuntimeService().startProcessInstanceByKey("processA"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Call Activity recursion cycle detected")
        .hasMessageContaining("Maximum allowed cycle iterations is 10")
        .hasMessageContaining("processA");
  }

  /**
   * Test that non-cyclic calls are allowed regardless of depth
   * (depth limit only applies to cyclic calls)
   */
  @Test
  public void testNonCyclicDeepCallsAllowed() {
    // Set a low limit for testing - but it shouldn't matter for non-cyclic calls
    configuration.setMaxRecursiveCallIterations(3);

    // Create a chain: Process A -> B -> C -> D (depth 4, exceeds limit of 3, but no cycle)
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
        .userTask("task")
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

    // Starting Process A should succeed even though depth > limit, because there's no cycle
    ProcessInstance processInstance = rule.getRuntimeService().startProcessInstanceByKey("processA");
    assertThat(processInstance).isNotNull();

    // There should be a task from processD
    Task task = rule.getTaskService().createTaskQuery().singleResult();
    assertThat(task).isNotNull();
    assertThat(task.getTaskDefinitionKey()).isEqualTo("task");

    // Clean up
    rule.getTaskService().complete(task.getId());
  }

  /**
   * Test that non-cyclic calls within the depth limit work correctly
   */
  @Test
  public void testNonCyclicCallsWithinLimitSucceed() {
    // Set a reasonable limit
    configuration.setMaxRecursiveCallIterations(5);

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
    configuration.setMaxRecursiveCallIterations(0);

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
    configuration.setMaxRecursiveCallIterations(-1);

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
    configuration.setMaxRecursiveCallIterations(3);

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
   * Test that cycle counting allows multiple iterations through a cycle
   * up to the specified limit
   */
  @Test
  public void testMultipleCycleIterationsAllowed() {
    // Set limit to 2 - allows process to appear twice in chain
    configuration.setMaxRecursiveCallIterations(2);

    // Create Process A with a conditional path that can complete after N iterations
    // For this test, we'll use a simple cycle A -> A with a user task
    BpmnModelInstance processA = Bpmn.createExecutableProcess("processA")
        .startEvent()
        .userTask("task")
        .callActivity()
          .calledElement("processA")
        .endEvent()
        .done();

    Deployment deployment = rule.getRepositoryService()
        .createDeployment()
        .addModelInstance("processA.bpmn", processA)
        .deploy();
    rule.manageDeployment(deployment);

    // First call - Process A starts (cycle count = 0)
    ProcessInstance processInstance = rule.getRuntimeService().startProcessInstanceByKey("processA");
    assertThat(processInstance).isNotNull();
    
    // There should be a task from the first A instance
    Task task1 = rule.getTaskService().createTaskQuery().taskDefinitionKey("task").singleResult();
    assertThat(task1).isNotNull();
    
    // Complete task1 - triggers first recursive call (cycleCount = 1, allowed with limit 2)
    rule.getTaskService().complete(task1.getId());
    
    // There should be a task from the second A instance (1st iteration)
    Task task2 = rule.getTaskService().createTaskQuery().taskDefinitionKey("task").singleResult();
    assertThat(task2).isNotNull();
    
    // Complete task2 - triggers second recursive call (cycleCount = 2, allowed, at limit)
    rule.getTaskService().complete(task2.getId());
    
    // There should be a task from the third A instance (2nd iteration)
    Task task3 = rule.getTaskService().createTaskQuery().taskDefinitionKey("task").singleResult();
    assertThat(task3).isNotNull();
    
    // Complete task3 - would trigger third recursive call (cycleCount = 3, exceeds limit of 2)
    // This should fail because cycleCount = 3 > maxCycles (2)
    assertThatThrownBy(() -> rule.getTaskService().complete(task3.getId()))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Call Activity recursion cycle detected")
        .hasMessageContaining("appears 3 time(s)")
        .hasMessageContaining("Maximum allowed cycle iterations is 2");
    
    // Clean up
    rule.getRuntimeService().deleteProcessInstance(processInstance.getId(), "test cleanup");
  }

  /**
   * Test error message format for cycle detection
   */
  @Test
  public void testCycleDetectionErrorMessage() {
    configuration.setMaxRecursiveCallIterations(10);

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
        .hasMessageContaining("Call Activity recursion cycle detected")
        .hasMessageContaining("Maximum allowed cycle iterations is 10")
        .hasMessageContaining("processA")
        .hasMessageContaining("Current call chain")
        .hasMessageContaining("maxRecursiveCallIterations");
  }
}
