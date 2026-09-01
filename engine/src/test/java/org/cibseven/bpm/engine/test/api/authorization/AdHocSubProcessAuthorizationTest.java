/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.cibseven.bpm.engine.test.api.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cibseven.bpm.engine.authorization.Permissions.UPDATE;
import static org.cibseven.bpm.engine.authorization.Permissions.UPDATE_INSTANCE;
import static org.cibseven.bpm.engine.authorization.Resources.PROCESS_DEFINITION;
import static org.cibseven.bpm.engine.authorization.Resources.PROCESS_INSTANCE;
import static org.junit.Assert.fail;

import java.util.Collections;

import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.runtime.Execution;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Authorization for the ad hoc activation API. Both commands check
 * {@code checkUpdateProcessInstance}, the same permission process instance modification requires,
 * and CIB7-1885 decided to keep it: anyone who can modify an instance can already start the same
 * activity through {@code startBeforeActivity}, so a narrower permission would guard a door that is
 * open beside it.
 *
 * <p>These tests exist because that check had none. It could have been deleted, or moved after the
 * point where it still protects anything, and every ad hoc test would have stayed green — for an
 * operation that starts arbitrary activities inside a running instance. The reference implementation
 * has no authorization test for its equivalent either, so there was nothing to compare against.
 *
 * <p>{@code checkUpdateProcessInstance} is <em>disjunctive</em>: it grants access through
 * {@code PROCESS_INSTANCE/UPDATE} <em>or</em> {@code PROCESS_DEFINITION/UPDATE_INSTANCE}
 * ({@code AuthorizationCommandChecker:266-272}). Both arms need a positive case, or a regression that
 * drops one route stays invisible — and the definition-level grant is the arm real deployments lean
 * on, since it is configured once per definition instead of once per instance.
 */
public class AdHocSubProcessAuthorizationTest extends AuthorizationTest {

  protected static final String PROCESS_KEY = "adHocAuthProcess";

  @Override
  @Before
  public void setUp() throws Exception {
    testRule.deploy("org/cibseven/bpm/engine/test/api/authorization/adHocSubProcess.bpmn20.xml");
    super.setUp();
  }

  @Override
  @After
  public void tearDown() {
    super.tearDown();
  }

  // trigger //////////////////////////////////////////////////////////

  @Test
  public void testTriggerAdHocActivitiesWithoutAuthorization() {
    // given
    String scopeExecutionId = startInstanceAndGetScopeExecutionId();

    try {
      // when
      runtimeService.triggerAdHocActivities(scopeExecutionId, Collections.singletonList("taskA"));
      fail("activating a child without UPDATE on the process instance must be refused");

    } catch (AuthorizationException e) {
      // then
      String message = e.getMessage();
      testRule.assertTextPresent(userId, message);
      testRule.assertTextPresent(UPDATE.getName(), message);
      testRule.assertTextPresent(PROCESS_INSTANCE.resourceName(), message);
      testRule.assertTextPresent(UPDATE_INSTANCE.getName(), message);
      testRule.assertTextPresent(PROCESS_KEY, message);
      testRule.assertTextPresent(PROCESS_DEFINITION.resourceName(), message);
    }

    // and nothing was started: the refusal has to precede the activation, not follow it
    disableAuthorization();
    assertThat(taskService.createTaskQuery().count()).isZero();
    enableAuthorization();
  }

  @Test
  public void testTriggerAdHocActivitiesWithUpdatePermissionOnProcessInstance() {
    // given
    String scopeExecutionId = startInstanceAndGetScopeExecutionId();
    createGrantAuthorization(PROCESS_INSTANCE, processInstanceId(scopeExecutionId), userId, UPDATE);

    // when
    runtimeService.triggerAdHocActivities(scopeExecutionId, Collections.singletonList("taskA"));

    // then
    disableAuthorization();
    assertThat(taskService.createTaskQuery().taskDefinitionKey("taskA").count()).isEqualTo(1);
    enableAuthorization();
  }

  @Test
  public void testTriggerAdHocActivitiesWithUpdateInstancePermissionOnProcessDefinition() {
    // given
    String scopeExecutionId = startInstanceAndGetScopeExecutionId();
    createGrantAuthorization(PROCESS_DEFINITION, PROCESS_KEY, userId, UPDATE_INSTANCE);

    // when
    runtimeService.triggerAdHocActivities(scopeExecutionId, Collections.singletonList("taskA"));

    // then
    disableAuthorization();
    assertThat(taskService.createTaskQuery().taskDefinitionKey("taskA").count()).isEqualTo(1);
    enableAuthorization();
  }

  // complete //////////////////////////////////////////////////////////

  @Test
  public void testCompleteAdHocSubProcessWithoutAuthorization() {
    // given
    String scopeExecutionId = startInstanceAndGetScopeExecutionId();

    try {
      // when
      runtimeService.completeAdHocSubProcess(scopeExecutionId);
      fail("completing the scope without UPDATE on the process instance must be refused");

    } catch (AuthorizationException e) {
      // then -- both arms of the disjunction are named, so this also fails if one is dropped
      String message = e.getMessage();
      testRule.assertTextPresent(userId, message);
      testRule.assertTextPresent(UPDATE.getName(), message);
      testRule.assertTextPresent(PROCESS_INSTANCE.resourceName(), message);
      testRule.assertTextPresent(UPDATE_INSTANCE.getName(), message);
      testRule.assertTextPresent(PROCESS_KEY, message);
      testRule.assertTextPresent(PROCESS_DEFINITION.resourceName(), message);
    }

    // and the instance is still running -- the refusal did not half-complete the scope
    disableAuthorization();
    assertThat(runtimeService.createProcessInstanceQuery().count()).isEqualTo(1);
    enableAuthorization();
  }

  @Test
  public void testCompleteAdHocSubProcessWithUpdatePermissionOnProcessInstance() {
    // given
    String scopeExecutionId = startInstanceAndGetScopeExecutionId();
    createGrantAuthorization(PROCESS_INSTANCE, processInstanceId(scopeExecutionId), userId, UPDATE);

    // when
    runtimeService.completeAdHocSubProcess(scopeExecutionId);

    // then
    disableAuthorization();
    assertThat(runtimeService.createProcessInstanceQuery().count()).isZero();
    enableAuthorization();
  }

  @Test
  public void testCompleteAdHocSubProcessWithUpdateInstancePermissionOnProcessDefinition() {
    // given
    String scopeExecutionId = startInstanceAndGetScopeExecutionId();
    createGrantAuthorization(PROCESS_DEFINITION, PROCESS_KEY, userId, UPDATE_INSTANCE);

    // when
    runtimeService.completeAdHocSubProcess(scopeExecutionId);

    // then
    disableAuthorization();
    assertThat(runtimeService.createProcessInstanceQuery().count()).isZero();
    enableAuthorization();
  }

  // helpers //////////////////////////////////////////////////////////

  protected String startInstanceAndGetScopeExecutionId() {
    ProcessInstance pi = startProcessInstanceByKey(PROCESS_KEY);
    disableAuthorization();
    Execution scope = runtimeService.createExecutionQuery()
        .processInstanceId(pi.getId())
        .activityId("adHoc")
        .singleResult();
    enableAuthorization();
    return scope.getId();
  }

  protected String processInstanceId(String executionId) {
    disableAuthorization();
    String id = runtimeService.createExecutionQuery().executionId(executionId)
        .singleResult().getProcessInstanceId();
    enableAuthorization();
    return id;
  }

}
