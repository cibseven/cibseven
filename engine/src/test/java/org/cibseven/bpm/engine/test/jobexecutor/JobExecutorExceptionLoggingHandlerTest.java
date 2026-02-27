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
package org.cibseven.bpm.engine.test.jobexecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.jobexecutor.ExecuteJobHelper;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class JobExecutorExceptionLoggingHandlerTest {

  @RegisterExtension
  @Order(4) public ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  @Order(9) public ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  protected ProcessEngineConfigurationImpl processEngineConfiguration;
  protected ExecuteJobHelper.ExceptionLoggingHandler originalHandler;

  @BeforeEach
  public void init() {
    processEngineConfiguration = engineRule.getProcessEngineConfiguration();
    originalHandler = ExecuteJobHelper.LOGGING_HANDLER;
  }

  @AfterEach
  public void tearDown() {
    // cleanup
    ExecuteJobHelper.LOGGING_HANDLER = originalHandler;
  }

  @Test
  public void shouldBeAbleToReplaceLoggingHandler() {
    // given
    CollectingHandler collectingHandler = new CollectingHandler();
    ExecuteJobHelper.LOGGING_HANDLER = collectingHandler;
    BpmnModelInstance modelInstance = Bpmn.createExecutableProcess("failingDelegate")
        .startEvent()
        .serviceTask()
          .camundaClass("org.cibseven.bpm.engine.test.jobexecutor.FailingDelegate")
          .camundaAsyncBefore()
        .done();
    testRule.deploy(modelInstance);

    // when
    engineRule.getRuntimeService().startProcessInstanceByKey("failingDelegate");
    final String jobId = engineRule.getManagementService().createJobQuery().singleResult().getId();
    processEngineConfiguration.getJobExecutor().start();
    testRule.waitForJobExecutorToProcessAllJobs();
    processEngineConfiguration.getJobExecutor().shutdown();

    Throwable collectedException = collectingHandler.collectedExceptions.get(jobId);

    // then
    assertTrue(collectedException instanceof RuntimeException);
    assertThat(collectedException.getMessage()).isEqualTo("Expected Exception");
  }

  static class CollectingHandler implements ExecuteJobHelper.ExceptionLoggingHandler {

    Map<String, Throwable> collectedExceptions = new HashMap<String, Throwable>();

    @Override
    public void exceptionWhileExecutingJob(String jobId, Throwable exception) {
      collectedExceptions.put(jobId, exception);
    }

  }

}