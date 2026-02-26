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
package org.cibseven.bpm.engine.test.api.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.OptimisticLockingException;
import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.User;
import org.cibseven.bpm.engine.test.util.ProcessEngineBootstrapRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

import org.junit.jupiter.api.Test;


/**
 * @author Svetlana Dorokhova.
 */
public class IdentityServiceWithJdbcSimpleProcessingTest {

  @RegisterExtension
  public static ProcessEngineBootstrapRule bootstrapRule = new ProcessEngineBootstrapRule(configuration ->
      configuration.setJdbcBatchProcessing(false));
  @RegisterExtension
  protected ProvidedProcessEngineRule engineRule = new ProvidedProcessEngineRule(bootstrapRule);
  @RegisterExtension
  protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  protected IdentityService identityService;

  @BeforeEach
  public void init() {
    identityService = engineRule.getIdentityService();
  }

  @AfterEach
  public void cleanUp() {
    for (User user : identityService.createUserQuery().list()) {
      identityService.deleteUser(user.getId());
    }
    for (Group group : identityService.createGroupQuery().list()) {
      identityService.deleteGroup(group.getId());
    }
  }

  @Test
  public void testUserOptimisticLockingException() {
    User user = identityService.newUser("kermit");
    identityService.saveUser(user);

    User user1 = identityService.createUserQuery().singleResult();
    User user2 = identityService.createUserQuery().singleResult();

    user1.setFirstName("name one");
    identityService.saveUser(user1);

    user2.setFirstName("name two");

    // when/then
    assertThatThrownBy(() -> identityService.saveUser(user2))
      .isInstanceOf(OptimisticLockingException.class);

  }

  @Test
  public void testGroupOptimisticLockingException() {
    Group group = identityService.newGroup("group");
    identityService.saveGroup(group);

    Group group1 = identityService.createGroupQuery().singleResult();
    Group group2 = identityService.createGroupQuery().singleResult();

    group1.setName("name one");
    identityService.saveGroup(group1);

    group2.setName("name two");

    // when/then
    assertThatThrownBy(() -> identityService.saveGroup(group2))
      .isInstanceOf(OptimisticLockingException.class);
  }

}
