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
package org.cibseven.bpm.engine.cdi.test.bpmn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.cdi.BusinessProcess;
import org.cibseven.bpm.engine.cdi.test.CdiProcessEngineTestCase;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.Deployment;
import org.junit.jupiter.api.Test;

public class SignalEventTest extends CdiProcessEngineTestCase {

  @Named
  @Dependent
  public static class SignalReceivedDelegate implements JavaDelegate {    
    
    @Inject
    private BusinessProcess businessProcess;
    
    public void execute(DelegateExecution execution) {
      businessProcess.setVariable("processName", "catchSignal-visited (was " + businessProcess.getVariable("processName")  + ")");
    }
  }

  @Named
  @Dependent
  public static class SendSignalDelegate implements JavaDelegate {

    @Inject
    private RuntimeService runtimeService;  

    @Inject
    private BusinessProcess businessProcess;   

    public void execute(DelegateExecution execution) throws Exception {
      businessProcess.setVariable("processName", "throwSignal-visited (was " + businessProcess.getVariable("processName")  + ")");

      String signalProcessInstanceId = (String) execution.getVariable("signalProcessInstanceId");      
      String executionId = runtimeService.createExecutionQuery().processInstanceId(signalProcessInstanceId).signalEventSubscriptionName("alert").singleResult().getId();      
      
      runtimeService.signalEventReceived("alert", executionId);
    }

  }

  @Test
  @Deployment(resources = {"org/cibseven/bpm/engine/cdi/test/bpmn/SignalEventTests.catchAlertSignalBoundaryWithReceiveTask.bpmn20.xml", 
                          "org/cibseven/bpm/engine/cdi/test/bpmn/SignalEventTests.throwAlertSignalWithDelegate.bpmn20.xml"})
  public void testSignalCatchBoundaryWithVariables() {
    HashMap<String, Object> variables1 = new HashMap<String, Object>();
    variables1.put("processName", "catchSignal");
    ProcessInstance piCatchSignal = runtimeService.startProcessInstanceByKey("catchSignal", variables1);
        
    HashMap<String, Object> variables2 = new HashMap<String, Object>();
    variables2.put("processName", "throwSignal");
    variables2.put("signalProcessInstanceId", piCatchSignal.getProcessInstanceId());
    ProcessInstance piThrowSignal = runtimeService.startProcessInstanceByKey("throwSignal", variables2);
    
    assertEquals(1, runtimeService.createExecutionQuery().processInstanceId(piCatchSignal.getProcessInstanceId()).activityId("receiveTask").count());
    assertEquals(1, runtimeService.createExecutionQuery().processInstanceId(piThrowSignal.getProcessInstanceId()).activityId("receiveTask").count());
    
    assertEquals("catchSignal-visited (was catchSignal)", runtimeService.getVariable(piCatchSignal.getId(), "processName"));
    assertEquals("throwSignal-visited (was throwSignal)", runtimeService.getVariable(piThrowSignal.getId(), "processName"));

    // clean up
    runtimeService.signal(piCatchSignal.getId());
    runtimeService.signal(piThrowSignal.getId());
  }

}
