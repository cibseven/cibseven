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
package org.cibseven.bpm.engine.test.assertions.bpmn;

import static org.cibseven.bpm.engine.test.assertions.bpmn.BpmnAwareTests.assertThat;
import static org.cibseven.bpm.engine.test.assertions.bpmn.BpmnAwareTests.claim;
import static org.cibseven.bpm.engine.test.assertions.bpmn.BpmnAwareTests.runtimeService;
import static org.cibseven.bpm.engine.test.assertions.bpmn.BpmnAwareTests.task;

import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.assertions.helpers.Failure;
import org.cibseven.bpm.engine.test.assertions.helpers.ProcessAssertTestCase;
import org.junit.jupiter.api.Test;

public class ProcessEngineTestsClaimTest extends ProcessAssertTestCase {


  @Test
  @Deployment(resources = {"bpmn/ProcessEngineTests-claim.bpmn"
  })
  public void testClaim_Success() {
    // Given
    ProcessInstance processInstance = runtimeService().startProcessInstanceByKey(
      "ProcessEngineTests-claim"
    );
    // When
    claim(task(processInstance), "fozzie");
    // Then
    assertThat(task(processInstance)).isNotNull().hasDefinitionKey("UserTask_1").isAssignedTo("fozzie");
  }

  @Test
  @Deployment(resources = {"bpmn/ProcessEngineTests-claim.bpmn"
  })
  public void testClaimNoTask_Failure() {
    // Given
    final ProcessInstance processInstance = runtimeService().startProcessInstanceByKey(
      "ProcessEngineTests-claim"
    );
    // Then
    expect(new Failure() {
      @Override
      public void when() {
        claim(task("UserTask_2", processInstance), "fozzie");
      }
    }, IllegalArgumentException.class);
  }

  @Test
  @Deployment(resources = {"bpmn/ProcessEngineTests-claim.bpmn"
  })
  public void testClaimNoUser_Failure() {
    // Given
    final ProcessInstance processInstance = runtimeService().startProcessInstanceByKey(
      "ProcessEngineTests-claim"
    );
    // Then
    expect(new Failure() {
      @Override
      public void when() {
        claim(task("UserTask_1", processInstance), null);
      }
    }, IllegalArgumentException.class);
  }

}
