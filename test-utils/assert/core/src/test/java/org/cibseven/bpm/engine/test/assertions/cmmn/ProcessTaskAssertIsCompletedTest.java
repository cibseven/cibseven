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
package org.cibseven.bpm.engine.test.assertions.cmmn;

import static org.cibseven.bpm.engine.test.assertions.bpmn.BpmnAwareTests.complete;
import static org.cibseven.bpm.engine.test.assertions.bpmn.BpmnAwareTests.processInstanceQuery;
import static org.cibseven.bpm.engine.test.assertions.bpmn.BpmnAwareTests.task;
import static org.cibseven.bpm.engine.test.assertions.cmmn.CmmnAwareTests.assertThat;
import static org.cibseven.bpm.engine.test.assertions.cmmn.CmmnAwareTests.caseService;

import org.cibseven.bpm.engine.runtime.CaseInstance;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.assertions.helpers.Failure;
import org.cibseven.bpm.engine.test.assertions.helpers.ProcessAssertTestCase;
import org.junit.Rule;
import org.junit.Test;

public class ProcessTaskAssertIsCompletedTest extends ProcessAssertTestCase {

  public static final String TASK_A = "PI_TaskA";
  public static final String USER_TASK = "UserTask_1";
  public static final String CASE_KEY = "Case_ProcessTaskAssertIsCompletedTest";

  @Rule
  public ProcessEngineRule processEngineRule = new ProcessEngineRule();

  @Test
  @Deployment(resources = { "cmmn/ProcessTaskAssertIsCompletedTest.cmmn", "cmmn/ProcessTaskAssert-calledProcess.bpmn" })
  public void testIsCompleted_Success() {
    // Given
    final CaseInstance caseInstance = givenCaseIsCreated();
    ProcessTaskAssert processTask = assertThat(caseInstance).processTask(TASK_A);
    // When
    complete(task(USER_TASK, calledProcessInstance(caseInstance)));
    // Then
    processTask.isCompleted();
  }

  @Test
  @Deployment(resources = { "cmmn/ProcessTaskAssertIsCompletedTest.cmmn", "cmmn/ProcessTaskAssert-calledProcess.bpmn" })
  public void testIsCompleted_Failure() {
    // Given
    // case model is deployed
    // When
    final CaseInstance caseInstance = givenCaseIsCreated();
    // Then
    expect(new Failure() {
      @Override
      public void when() {
        assertThat(caseInstance).processTask(TASK_A).isCompleted();
      }
    });
  }

  private ProcessInstance calledProcessInstance(CaseInstance caseInstance) {
    return processInstanceQuery().superCaseInstanceId(caseInstance.getId()).singleResult();
  }

  private CaseInstance givenCaseIsCreated() {
    return caseService().createCaseInstanceByKey(CASE_KEY);
  }
}