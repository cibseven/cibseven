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
package org.cibseven.bpm.engine.test.api.runtime;

import static junit.framework.TestCase.assertFalse;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.Sets;

public class UpdateProcessInstancesSuspendStateTest {

  @RegisterExtension
  @Order(4) protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  @Order(9) protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  protected RuntimeService runtimeService;
  protected HistoryService historyService;

  @BeforeEach
  public void init() {
    runtimeService = engineRule.getRuntimeService();
    historyService = engineRule.getHistoryService();
  }


  @Test
  @Deployment(resources = {"org/cibseven/bpm/engine/test/api/externaltask/oneExternalTaskProcess.bpmn20.xml",
    "org/cibseven/bpm/engine/test/api/externaltask/twoExternalTaskProcess.bpmn20.xml"})
  public void testBatchSuspensionById() {
    // given
    ProcessInstance processInstance1 = runtimeService.startProcessInstanceByKey("oneExternalTaskProcess");
    ProcessInstance processInstance2 = runtimeService.startProcessInstanceByKey("twoExternalTaskProcess");

    // when the process instances are suspended
    runtimeService.updateProcessInstanceSuspensionState()
      .byProcessInstanceIds(processInstance1.getId(), processInstance2.getId()).suspend();

    // Update the process instances and they are suspended
    ProcessInstance p1c = runtimeService.createProcessInstanceQuery().processInstanceId(processInstance1.getId()).singleResult();
    assertTrue(p1c.isSuspended());
    ProcessInstance p2c = runtimeService.createProcessInstanceQuery().processInstanceId(processInstance2.getId()).singleResult();
    assertTrue(p2c.isSuspended());

  }


  @Test
  @Deployment(resources = {"org/cibseven/bpm/engine/test/api/externaltask/oneExternalTaskProcess.bpmn20.xml",
    "org/cibseven/bpm/engine/test/api/externaltask/twoExternalTaskProcess.bpmn20.xml"})
  public void testBatchActivatationById() {
    // given
    ProcessInstance processInstance1 = runtimeService.startProcessInstanceByKey("oneExternalTaskProcess");
    ProcessInstance processInstance2 = runtimeService.startProcessInstanceByKey("twoExternalTaskProcess");

    // when the process instances are suspended
    runtimeService.updateProcessInstanceSuspensionState()
      .byProcessInstanceIds(processInstance1.getId(), processInstance2.getId()).suspend();

    // when they are activated again
    runtimeService.updateProcessInstanceSuspensionState()
      .byProcessInstanceIds(processInstance1.getId(), processInstance2.getId()).activate();

    // Update the process instances and they are active again
    ProcessInstance p1c = runtimeService.createProcessInstanceQuery().processInstanceId(processInstance1.getId()).singleResult();
    assertFalse(p1c.isSuspended());
    ProcessInstance p2c = runtimeService.createProcessInstanceQuery().processInstanceId(processInstance2.getId()).singleResult();
    assertFalse(p2c.isSuspended());

  }

  @Test
  @Deployment(resources = {"org/cibseven/bpm/engine/test/api/externaltask/oneExternalTaskProcess.bpmn20.xml",
    "org/cibseven/bpm/engine/test/api/externaltask/twoExternalTaskProcess.bpmn20.xml"})
  public void testBatchSuspensionByIdArray() {
    // given
    ProcessInstance processInstance1 = runtimeService.startProcessInstanceByKey("oneExternalTaskProcess");
    ProcessInstance processInstance2 = runtimeService.startProcessInstanceByKey("twoExternalTaskProcess");

    // when the process instances are suspended
    runtimeService.updateProcessInstanceSuspensionState()
      .byProcessInstanceIds(Arrays.asList(processInstance1.getId(), processInstance2.getId())).suspend();

    // Update the process instances and they are suspended
    ProcessInstance p1c = runtimeService.createProcessInstanceQuery().processInstanceId(processInstance1.getId()).singleResult();
    assertTrue(p1c.isSuspended());
    ProcessInstance p2c = runtimeService.createProcessInstanceQuery().processInstanceId(processInstance2.getId()).singleResult();
    assertTrue(p2c.isSuspended());

  }

  @Test
  @Deployment(resources = {"org/cibseven/bpm/engine/test/api/externaltask/oneExternalTaskProcess.bpmn20.xml",
    "org/cibseven/bpm/engine/test/api/externaltask/twoExternalTaskProcess.bpmn20.xml"})
  public void testBatchActivatationByIdArray() {
    // given
    ProcessInstance processInstance1 = runtimeService.startProcessInstanceByKey("oneExternalTaskProcess");
    ProcessInstance processInstance2 = runtimeService.startProcessInstanceByKey("twoExternalTaskProcess");

    // when the process instances are suspended
    runtimeService.updateProcessInstanceSuspensionState()
      .byProcessInstanceIds(Arrays.asList(processInstance1.getId(), processInstance2.getId())).suspend();

    // when they are activated again
    runtimeService.updateProcessInstanceSuspensionState()
      .byProcessInstanceIds(Arrays.asList(processInstance1.getId(), processInstance2.getId())).activate();


    // Update the process instances and they are active again
    ProcessInstance p1c = runtimeService.createProcessInstanceQuery().processInstanceId(processInstance1.getId()).singleResult();
    assertFalse(p1c.isSuspended());
    ProcessInstance p2c = runtimeService.createProcessInstanceQuery().processInstanceId(processInstance2.getId()).singleResult();
    assertFalse(p2c.isSuspended());

  }


  @Test
  @Deployment(resources = {"org/cibseven/bpm/engine/test/api/externaltask/oneExternalTaskProcess.bpmn20.xml",
    "org/cibseven/bpm/engine/test/api/externaltask/twoExternalTaskProcess.bpmn20.xml"})
  public void testBatchSuspensionByProcessInstanceQuery() {
    // given
    ProcessInstance processInstance1 = runtimeService.startProcessInstanceByKey("oneExternalTaskProcess");
    ProcessInstance processInstance2 = runtimeService.startProcessInstanceByKey("twoExternalTaskProcess");

    // when the process instances are suspended
    runtimeService.updateProcessInstanceSuspensionState()
      .byProcessInstanceQuery(runtimeService.createProcessInstanceQuery().active()).suspend();

    // Update the process instances and they are suspended
    ProcessInstance p1c = runtimeService.createProcessInstanceQuery().processInstanceId(processInstance1.getId()).singleResult();
    assertTrue(p1c.isSuspended());
    ProcessInstance p2c = runtimeService.createProcessInstanceQuery().processInstanceId(processInstance2.getId()).singleResult();
    assertTrue(p2c.isSuspended());

  }


  @Test
  @Deployment(resources = {"org/cibseven/bpm/engine/test/api/externaltask/oneExternalTaskProcess.bpmn20.xml",
    "org/cibseven/bpm/engine/test/api/externaltask/twoExternalTaskProcess.bpmn20.xml"})
  public void testBatchActivatationByProcessInstanceQuery() {
    // given
    ProcessInstance processInstance1 = runtimeService.startProcessInstanceByKey("oneExternalTaskProcess");
    ProcessInstance processInstance2 = runtimeService.startProcessInstanceByKey("twoExternalTaskProcess");

    // when the process instances are suspended
    runtimeService.updateProcessInstanceSuspensionState()
      .byProcessInstanceQuery(runtimeService.createProcessInstanceQuery().active()).suspend();


    // when they are activated again
    runtimeService.updateProcessInstanceSuspensionState()
      .byProcessInstanceQuery(runtimeService.createProcessInstanceQuery().suspended()).activate();


    // Update the process instances and they are active again
    ProcessInstance p1c = runtimeService.createProcessInstanceQuery().processInstanceId(processInstance1.getId()).singleResult();
    assertFalse(p1c.isSuspended());
    ProcessInstance p2c = runtimeService.createProcessInstanceQuery().processInstanceId(processInstance2.getId()).singleResult();
    assertFalse(p2c.isSuspended());

  }

  @Test
  @Deployment(resources = {"org/cibseven/bpm/engine/test/api/externaltask/oneExternalTaskProcess.bpmn20.xml",
    "org/cibseven/bpm/engine/test/api/externaltask/twoExternalTaskProcess.bpmn20.xml"})
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_ACTIVITY)
  public void testBatchSuspensionByHistoricProcessInstanceQuery() {
    // given
    ProcessInstance processInstance1 = runtimeService.startProcessInstanceByKey("oneExternalTaskProcess");
    ProcessInstance processInstance2 = runtimeService.startProcessInstanceByKey("twoExternalTaskProcess");


    // when the process instances are suspended
    runtimeService.updateProcessInstanceSuspensionState()
      .byHistoricProcessInstanceQuery(historyService.createHistoricProcessInstanceQuery().processInstanceIds(Sets.newHashSet(processInstance1.getId(), processInstance2.getId()))).suspend();

    // Update the process instances and they are suspended
    ProcessInstance p1c = runtimeService.createProcessInstanceQuery().processInstanceId(processInstance1.getId()).singleResult();
    assertTrue(p1c.isSuspended());
    ProcessInstance p2c = runtimeService.createProcessInstanceQuery().processInstanceId(processInstance2.getId()).singleResult();
    assertTrue(p2c.isSuspended());

  }


  @Test
  @Deployment(resources = {"org/cibseven/bpm/engine/test/api/externaltask/oneExternalTaskProcess.bpmn20.xml",
    "org/cibseven/bpm/engine/test/api/externaltask/twoExternalTaskProcess.bpmn20.xml"})
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_ACTIVITY)
  public void testBatchActivatationByHistoricProcessInstanceQuery() {
    // given
    ProcessInstance processInstance1 = runtimeService.startProcessInstanceByKey("oneExternalTaskProcess");
    ProcessInstance processInstance2 = runtimeService.startProcessInstanceByKey("twoExternalTaskProcess");


    // when the process instances are suspended
    runtimeService.updateProcessInstanceSuspensionState()
      .byHistoricProcessInstanceQuery(historyService.createHistoricProcessInstanceQuery().processInstanceIds(Sets.newHashSet(processInstance1.getId(), processInstance2.getId()))).suspend();

    // when they are activated again
    runtimeService.updateProcessInstanceSuspensionState()
      .byHistoricProcessInstanceQuery(historyService.createHistoricProcessInstanceQuery().processInstanceIds(Sets.newHashSet(processInstance1.getId(), processInstance2.getId()))).activate();


    // Update the process instances and they are active again
    ProcessInstance p1c = runtimeService.createProcessInstanceQuery().processInstanceId(processInstance1.getId()).singleResult();
    assertFalse(p1c.isSuspended());
    ProcessInstance p2c = runtimeService.createProcessInstanceQuery().processInstanceId(processInstance2.getId()).singleResult();
    assertFalse(p2c.isSuspended());

  }


  @Test
  public void testEmptyProcessInstanceListSuspend() {
    // given
    // nothing

    // when/then
    assertThatThrownBy(() -> runtimeService.updateProcessInstanceSuspensionState().byProcessInstanceIds().suspend())
      .isInstanceOf(BadUserRequestException.class)
      .hasMessageContaining("No process instance ids given");

  }

  @Test
  public void testEmptyProcessInstanceListActivateUpdateProcessInstancesSuspendStateAsyncTest() {
    // given
    // nothing

    // when/then
    assertThatThrownBy(() -> runtimeService.updateProcessInstanceSuspensionState().byProcessInstanceIds().activate())
      .isInstanceOf(BadUserRequestException.class)
      .hasMessageContaining("No process instance ids given");

  }


  @Test
  @Deployment(resources = {"org/cibseven/bpm/engine/test/api/externaltask/oneExternalTaskProcess.bpmn20.xml",
    "org/cibseven/bpm/engine/test/api/externaltask/twoExternalTaskProcess.bpmn20.xml"})
  public void testNullProcessInstanceListActivate() {
    // given
    ProcessInstance processInstance1 = runtimeService.startProcessInstanceByKey("oneExternalTaskProcess");
    ProcessInstance processInstance2 = runtimeService.startProcessInstanceByKey("twoExternalTaskProcess");

    // when/then
    assertThatThrownBy(() -> runtimeService.updateProcessInstanceSuspensionState().byProcessInstanceIds(Arrays.asList(processInstance1.getId(), processInstance2.getId(), null)).activate())
      .isInstanceOf(BadUserRequestException.class)
      .hasMessageContaining("Cannot be null");

  }

  @Test
  @Deployment(resources = {"org/cibseven/bpm/engine/test/api/externaltask/oneExternalTaskProcess.bpmn20.xml",
    "org/cibseven/bpm/engine/test/api/externaltask/twoExternalTaskProcess.bpmn20.xml"})
  public void testNullProcessInstanceListSuspend() {
    // given
    ProcessInstance processInstance1 = runtimeService.startProcessInstanceByKey("oneExternalTaskProcess");
    ProcessInstance processInstance2 = runtimeService.startProcessInstanceByKey("twoExternalTaskProcess");

    // when/then
    assertThatThrownBy(() -> runtimeService.updateProcessInstanceSuspensionState().byProcessInstanceIds(Arrays.asList(processInstance1.getId(), processInstance2.getId(), null)).suspend())
      .isInstanceOf(BadUserRequestException.class)
      .hasMessageContaining("Cannot be null");

  }

}
