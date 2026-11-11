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
package org.cibseven.bpm.engine.test.bpmn.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.cibseven.bpm.engine.history.HistoricVariableInstanceQuery;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.util.PluggableProcessEngineTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * @author Stefan Hentschel
 */
public class AsyncEndEventTest extends PluggableProcessEngineTest {

  @Deployment
  @Test
  public void testAsyncEndEvent() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("asyncEndEvent");
    long count = runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).active().count();

    Assertions.assertEquals(1, runtimeService.createExecutionQuery().activityId("endEvent").count());
    Assertions.assertEquals(1, count);

    testRule.executeAvailableJobs();
    count = runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).count();

    Assertions.assertEquals(0, runtimeService.createExecutionQuery().activityId("endEvent").active().count());
    Assertions.assertEquals(0, count);
  }

  @Deployment
  @Test
  public void testAsyncEndEventListeners() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("asyncEndEvent");
    long count = runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).active().count();

    Assertions.assertNull(runtimeService.getVariable(pi.getId(), "listener"));
    Assertions.assertEquals(1, runtimeService.createExecutionQuery().activityId("endEvent").count());
    Assertions.assertEquals(1, count);

    // as we are standing at the end event, we execute it.
    testRule.executeAvailableJobs();

    count = runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).active().count();
    Assertions.assertEquals(0, count);

    if(processEngineConfiguration.getHistoryLevel().getId() > ProcessEngineConfigurationImpl.HISTORYLEVEL_ACTIVITY) {

      // after the end event we have a event listener
      HistoricVariableInstanceQuery name = historyService.createHistoricVariableInstanceQuery()
                                                          .processInstanceId(pi.getId())
                                                          .variableName("listener");
      Assertions.assertNotNull(name);
      Assertions.assertEquals("listener invoked", name.singleResult().getValue());
    }
  }

  @Deployment
  @Test
  public void testMultipleAsyncEndEvents() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("multipleAsyncEndEvent");
    assertEquals(1, runtimeService.createProcessInstanceQuery().count());

    // should stop at both end events
    List<Job> jobs = managementService.createJobQuery().withRetriesLeft().list();
    assertEquals(2, jobs.size());

    // execute one of the end events
    managementService.executeJob(jobs.get(0).getId());
    jobs = managementService.createJobQuery().withRetriesLeft().list();
    assertEquals(1, jobs.size());

    // execute the second one
    managementService.executeJob(jobs.get(0).getId());
    // assert that we have finished our instance now
    assertEquals(0, runtimeService.createProcessInstanceQuery().count());

    if(processEngineConfiguration.getHistoryLevel().getId() > ProcessEngineConfigurationImpl.HISTORYLEVEL_ACTIVITY) {

      // after the end event we have a event listener
      HistoricVariableInstanceQuery name = historyService.createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId())
        .variableName("message");
      Assertions.assertNotNull(name);
      Assertions.assertEquals(true, name.singleResult().getValue());

    }
  }

  @Deployment(resources = {
      "org/cibseven/bpm/engine/test/bpmn/async/AsyncEndEventTest.testCallActivity-super.bpmn20.xml",
      "org/cibseven/bpm/engine/test/bpmn/async/AsyncEndEventTest.testCallActivity-sub.bpmn20.xml"
  })
  @Test
  public void testCallActivity() {
    runtimeService.startProcessInstanceByKey("super");

    ProcessInstance pi = runtimeService
        .createProcessInstanceQuery()
        .processDefinitionKey("sub")
        .singleResult();

    assertTrue(pi instanceof ExecutionEntity);

    assertEquals("theSubEnd", ((ExecutionEntity)pi).getActivityId());

  }

}
