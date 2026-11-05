/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
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
package org.cibseven.bpm.engine.test.bpmn.event.conditional;

import static org.assertj.core.api.Assertions.assertThat;

import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.runtime.Callback;
import org.cibseven.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.util.PluggableProcessEngineTest;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.Test;

/**
 * CIB7-2264. A variable the engine writes for its own bookkeeping, such as the activation a branch
 * of an ad hoc sub process belongs to, dispatches no variable event: an unfiltered
 * non-interrupting conditional event must not fire again on a write the model never made.
 */
public class VariableWithoutEventsTest extends PluggableProcessEngineTest {

  /** A task with an unfiltered non-interrupting conditional boundary event: no variableName. */
  protected static final BpmnModelInstance MODEL = Bpmn.createExecutableProcess("bookkeeping")
      .camundaHistoryTimeToLive(180)
      .startEvent()
      .userTask("work")
      .boundaryEvent("onFire")
        .cancelActivity(false)
        .conditionalEventDefinition()
          .condition("${fire}")
        .conditionalEventDefinitionDone()
      .userTask("handle")
      .endEvent()
      .moveToActivity("work")
      .endEvent()
      .done();

  @Test
  public void aWriteWithoutEventsFiresNoConditionalEventAnOrdinaryOneDoes() {
    testRule.deploy(MODEL);
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("bookkeeping",
        Variables.createVariables().putValue("fire", false));
    runtimeService.setVariable(pi.getId(), "fire", true);
    assertThat(handlers(pi)).as("the model's change fires the event once").isEqualTo(1);

    processEngineConfiguration.getCommandExecutorTxRequired().execute(commandContext -> {
      ExecutionEntity instance = commandContext.getExecutionManager().findExecutionById(pi.getId());
      instance.setVariableLocalWithoutEvents("bookkeeping", "kept");
      // dispatch whatever the write delayed, as an operation would
      instance.dispatchDelayedEventsAndPerformOperation((Callback<PvmExecutionImpl, Void>) null);
      return null;
    });
    assertThat(handlers(pi)).as("bookkeeping fires nothing, though the condition still holds")
        .isEqualTo(1);
    assertThat(runtimeService.getVariable(pi.getId(), "bookkeeping")).as("but it is stored")
        .isEqualTo("kept");

    runtimeService.setVariable(pi.getId(), "modelled", "changed");
    assertThat(handlers(pi)).as("an ordinary write fires it again, the condition holding")
        .isEqualTo(2);
  }

  @Test
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_AUDIT)
  public void aWriteWithoutEventsIsInTheHistory() {
    testRule.deploy(MODEL);
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("bookkeeping",
        Variables.createVariables().putValue("fire", false));

    processEngineConfiguration.getCommandExecutorTxRequired().execute(commandContext -> {
      commandContext.getExecutionManager().findExecutionById(pi.getId())
          .setVariableLocalWithoutEvents("bookkeeping", "kept");
      return null;
    });

    assertThat(historyService.createHistoricVariableInstanceQuery().processInstanceId(pi.getId())
        .variableName("bookkeeping").singleResult().getValue()).isEqualTo("kept");
  }

  protected long handlers(ProcessInstance pi) {
    return taskService.createTaskQuery().processInstanceId(pi.getId()).taskDefinitionKey("handle")
        .count();
  }
}
