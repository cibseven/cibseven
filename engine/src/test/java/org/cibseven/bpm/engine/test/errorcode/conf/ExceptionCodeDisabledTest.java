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
package org.cibseven.bpm.engine.test.errorcode.conf;

import org.assertj.core.api.ThrowableAssert;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.identity.User;
import org.cibseven.bpm.engine.impl.errorcode.BuiltinExceptionCode;
import org.cibseven.bpm.engine.test.errorcode.FailingJavaDelegateWithErrorCode;
import org.cibseven.bpm.engine.test.util.ProcessEngineBootstrapRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ExceptionCodeDisabledTest {

  @RegisterExtension
  public static ProcessEngineBootstrapRule bootstrapRule =
    new ProcessEngineBootstrapRule(c -> c.setDisableExceptionCode(true));

  @RegisterExtension
  protected ProvidedProcessEngineRule engineRule = new ProvidedProcessEngineRule(bootstrapRule);
  @RegisterExtension
  protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  protected RuntimeService runtimeService;
  protected IdentityService identityService;

  @BeforeEach
  public void assignServices() {
    runtimeService = engineRule.getRuntimeService();
    identityService = engineRule.getIdentityService();
  }

  @AfterEach
  public void clear() {
    engineRule.getIdentityService().deleteUser("kermit");
  }

  @Test
  public void shouldReturnDefaultErrorCodeWhenColumnSizeTooSmall() {
    // given
    BpmnModelInstance modelInstance = Bpmn.createExecutableProcess("process")
        .startEvent()
        .endEvent()
        .done();

    testRule.deploy(modelInstance);

    String businessKey = generateString(1_000);

    // when/then
    assertThatThrownBy(() -> runtimeService.startProcessInstanceByKey("process", businessKey))
    .satisfies(ex -> assertThat(ex)
            .hasFieldOrPropertyWithValue("code",BuiltinExceptionCode.FALLBACK.getCode()));
  }

  @Test
  public void shouldReturnDefaultErrorCodeWhenOptimisticLockingExceptionThrown() {
    // given
    User user = identityService.newUser("kermit");
    identityService.saveUser(user);

    User user1 = identityService.createUserQuery().singleResult();
    User user2 = identityService.createUserQuery().singleResult();

    user1.setFirstName("name one");
    identityService.saveUser(user1);

    user2.setFirstName("name two");

    // when/then
    assertThatThrownBy(() -> identityService.saveUser(user2))
    .satisfies(ex -> assertThat(ex)
            .hasFieldOrPropertyWithValue("code",BuiltinExceptionCode.FALLBACK.getCode()));
  }

  @Test
  public void shouldPassCodeFromDelegationCode() {
    // given
    BpmnModelInstance myProcess = Bpmn.createExecutableProcess("foo")
        .startEvent()
        .serviceTask()
          .camundaClass(FailingJavaDelegateWithErrorCode.class)
        .endEvent()
        .done();

    testRule.deploy(myProcess);

    // when
    ThrowableAssert.ThrowingCallable callable =
        () -> runtimeService.startProcessInstanceByKey("foo",
            Variables.putValue("code", 999_999));

    // then
    assertThatThrownBy(callable)
    .satisfies(ex -> assertThat(ex)
    	      .hasFieldOrPropertyWithValue("code", 999_999));
  }

  @Test
  public void shouldPassReservedCodeFromDelegationCode() {
    // given
    BpmnModelInstance myProcess = Bpmn.createExecutableProcess("foo")
        .startEvent()
        .serviceTask()
          .camundaClass(FailingJavaDelegateWithErrorCode.class)
        .endEvent()
        .done();

    testRule.deploy(myProcess);

    // when
    ThrowableAssert.ThrowingCallable callable =
        () -> runtimeService.startProcessInstanceByKey("foo",
            Variables.putValue("code", 1000));

    // then
    assertThatThrownBy(callable)
      .satisfies(ex -> assertThat(ex)
      .hasFieldOrPropertyWithValue("code", 1000));
  }

  // helper ////////////////////////////////////////////////////////////////////////////////////////

  protected String generateString(int size) {
    return new String(new char[size]).replace('\0', 'a');
  }

}