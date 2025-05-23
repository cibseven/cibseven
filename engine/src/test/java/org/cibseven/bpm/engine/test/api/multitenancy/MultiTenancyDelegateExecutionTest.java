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
package org.cibseven.bpm.engine.test.api.multitenancy;

import static org.assertj.core.api.Assertions.assertThat;

import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.test.api.delegate.AssertingJavaDelegate;
import org.cibseven.bpm.engine.test.api.delegate.AssertingJavaDelegate.DelegateExecutionAsserter;
import org.cibseven.bpm.engine.test.util.PluggableProcessEngineTest;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.junit.After;
import org.junit.Test;

/**
 * Tests if a {@link DelegateExecution} has the correct tenant-id. The
 * assertions are checked inside the service tasks.
 */
public class MultiTenancyDelegateExecutionTest extends PluggableProcessEngineTest {

  protected static final String PROCESS_DEFINITION_KEY = "testProcess";

  @Test
  public void testSingleExecution() {
    testRule.deployForTenant("tenant1", Bpmn.createExecutableProcess(PROCESS_DEFINITION_KEY)
      .startEvent()
      .serviceTask()
        .camundaClass(AssertingJavaDelegate.class.getName())
      .endEvent()
    .done());

    AssertingJavaDelegate.addAsserts(hasTenantId("tenant1"));

    startProcessInstance(PROCESS_DEFINITION_KEY);
  }

  @Test
  public void testConcurrentExecution() {

    testRule.deployForTenant("tenant1", Bpmn.createExecutableProcess(PROCESS_DEFINITION_KEY)
      .startEvent()
      .parallelGateway("fork")
        .serviceTask()
          .camundaClass(AssertingJavaDelegate.class.getName())
        .parallelGateway("join")
        .endEvent()
        .moveToNode("fork")
          .serviceTask()
          .camundaClass(AssertingJavaDelegate.class.getName())
          .connectTo("join")
          .done());

    AssertingJavaDelegate.addAsserts(hasTenantId("tenant1"));

    startProcessInstance(PROCESS_DEFINITION_KEY);
  }

  @Test
  public void testEmbeddedSubprocess() {
    testRule.deployForTenant("tenant1", Bpmn.createExecutableProcess(PROCESS_DEFINITION_KEY)
        .startEvent()
        .subProcess()
          .embeddedSubProcess()
            .startEvent()
            .serviceTask()
              .camundaClass(AssertingJavaDelegate.class.getName())
            .endEvent()
        .subProcessDone()
        .endEvent()
      .done());

    AssertingJavaDelegate.addAsserts(hasTenantId("tenant1"));

    startProcessInstance(PROCESS_DEFINITION_KEY);
  }

  protected void startProcessInstance(String processDefinitionKey) {
    ProcessDefinition processDefinition = repositoryService
        .createProcessDefinitionQuery()
        .processDefinitionKey(processDefinitionKey)
        .latestVersion()
        .singleResult();

    runtimeService.startProcessInstanceById(processDefinition.getId());
  }

  @After
  public void tearDown() throws Exception {
    AssertingJavaDelegate.clear();

  }

  protected static DelegateExecutionAsserter hasTenantId(final String expectedTenantId) {
    return new DelegateExecutionAsserter() {

      @Override
      public void doAssert(DelegateExecution execution) {
        assertThat(execution.getTenantId()).isEqualTo(expectedTenantId);
      }
    };
  }

}
