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
package org.cibseven.bpm.engine.test.api.authorization.externaltask;

import org.cibseven.bpm.engine.externaltask.ExternalTask;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationScenario;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class LockExternalTaskAuthorizationTest extends HandleExternalTaskAuthorizationTest {

  @ParameterizedTest
  @MethodSource("scenarios")
  @Deployment(resources = "org/cibseven/bpm/engine/test/api/externaltask/oneExternalTaskProcess.bpmn20.xml")
  public void shouldLockExternalTaskWithAuthorizations(AuthorizationScenario scenario) {

    // given
    ProcessInstance processInstance = engineRule.getRuntimeService()
        .startProcessInstanceByKey("oneExternalTaskProcess");
    ExternalTask externalTask = engineRule.getExternalTaskService()
        .createExternalTaskQuery().notLocked().singleResult();

    // when
    authRule
        .init(scenario)
        .withUser("userId")
        .bindResource("processInstanceId", processInstance.getId())
        .bindResource("processDefinitionKey", "oneExternalTaskProcess")
        .start();
    engineRule.getExternalTaskService().lock(externalTask.getId(), "workerId", 2000L);

    // then
    if (authRule.assertScenario(scenario)) {
      ExternalTask lockedTask = engineRule.getExternalTaskService().createExternalTaskQuery().locked().singleResult();
      Assertions.assertNotNull(lockedTask);
    }
  }
}
