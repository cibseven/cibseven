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

import static org.cibseven.bpm.engine.test.assertions.cmmn.CmmnAwareTests.assertThat;
import static org.cibseven.bpm.engine.test.assertions.cmmn.CmmnAwareTests.caseExecution;
import static org.cibseven.bpm.engine.test.assertions.cmmn.CmmnAwareTests.caseService;
import static org.cibseven.bpm.engine.test.assertions.cmmn.CmmnAwareTests.complete;

import org.cibseven.bpm.engine.runtime.CaseInstance;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.assertions.helpers.Failure;
import org.cibseven.bpm.engine.test.assertions.helpers.ProcessAssertTestCase;
import org.junit.jupiter.api.Test;

@Deployment(resources = "cmmn/MilestoneAssertIsCompletedTest.cmmn")
public class MilestoneAssertIsCompletedTest extends ProcessAssertTestCase {


  @Test
  public void test_IsCompleted_Success() {
    CaseInstance caseInstance = caseService().createCaseInstanceByKey("MilestoneAssertIsCompletedTest");
    MilestoneAssert milestoneAssert = assertThat(caseInstance).milestone("Milestone");

    complete(caseExecution("PI_TaskA", caseInstance));

    milestoneAssert.isCompleted();
  }

  @Test
  public void test_IsCompleted_Fail() {
    final CaseInstance caseInstance = caseService().createCaseInstanceByKey("MilestoneAssertIsCompletedTest");

    expect(new Failure() {
      @Override
      public void when() {
        assertThat(caseInstance).milestone("Milestone").isCompleted();
      }
    });
  }
}
