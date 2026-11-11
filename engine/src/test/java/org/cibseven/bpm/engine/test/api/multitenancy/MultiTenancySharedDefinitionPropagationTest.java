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

import org.cibseven.bpm.engine.impl.cfg.multitenancy.TenantIdProvider;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineBootstrapRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;


public class MultiTenancySharedDefinitionPropagationTest {

  protected static final String PROCESS_DEFINITION_KEY = "testProcess";

  protected static final String TENANT_ID = "tenant1";

  @RegisterExtension
  @Order(1) public static ProcessEngineBootstrapRule bootstrapRule = new ProcessEngineBootstrapRule(configuration -> {
    TenantIdProvider tenantIdProvider = new StaticTenantIdTestProvider(TENANT_ID);
    configuration.setTenantIdProvider(tenantIdProvider);
  });
  @RegisterExtension
  @Order(2) protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule(bootstrapRule);
  @RegisterExtension
  @Order(3) protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  @Test
  public void propagateTenantIdToProcessInstance() {
    testRule.deploy(Bpmn.createExecutableProcess(PROCESS_DEFINITION_KEY)
        .startEvent()
        .userTask()
        .endEvent()
       .done());

    engineRule.getRuntimeService().startProcessInstanceByKey(PROCESS_DEFINITION_KEY);

    ProcessInstance processInstance = engineRule.getRuntimeService().createProcessInstanceQuery().singleResult();
    assertThat(processInstance).isNotNull();
    // get the tenant id from the provider
    assertThat(processInstance.getTenantId()).isEqualTo(TENANT_ID);
  }

  @Test
  public void propagateTenantIdToIntermediateTimerJob() {
    testRule.deploy(Bpmn.createExecutableProcess("process")
      .startEvent()
      .intermediateCatchEvent()
        .timerWithDuration("PT1M")
      .endEvent()
    .done());

    engineRule.getRuntimeService().startProcessInstanceByKey("process");

    // the job is created when the timer event is reached
    Job job = engineRule.getManagementService().createJobQuery().singleResult();
    assertThat(job).isNotNull();
    // inherit the tenant id from execution
    assertThat(job.getTenantId()).isEqualTo(TENANT_ID);
  }

  @Test
  public void propagateTenantIdToAsyncJob() {
    testRule.deploy(Bpmn.createExecutableProcess("process")
      .startEvent()
      .userTask()
        .camundaAsyncBefore()
      .endEvent()
    .done());

    engineRule.getRuntimeService().startProcessInstanceByKey("process");

    // the job is created when the asynchronous activity is reached
    Job job = engineRule.getManagementService().createJobQuery().singleResult();
    assertThat(job).isNotNull();
    // inherit the tenant id from execution
    assertThat(job.getTenantId()).isEqualTo(TENANT_ID);
  }

}
