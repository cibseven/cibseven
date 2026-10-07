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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.identity.PasswordPolicy;
import org.cibseven.bpm.engine.identity.User;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.identity.DefaultPasswordPolicyImpl;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * @author Miklas Boskamp
 */
public class CustomPasswordPolicyTest {

  @RegisterExtension
  public ProcessEngineRule engineRule = new ProvidedProcessEngineRule();

  private ProcessEngineConfigurationImpl processEngineConfiguration;
  private IdentityService identityService;

  @BeforeEach
  public void init() {
    identityService = engineRule.getIdentityService();
    processEngineConfiguration = engineRule.getProcessEngineConfiguration();
    processEngineConfiguration.setPasswordPolicy(new DefaultPasswordPolicyImpl());
    processEngineConfiguration.setEnablePasswordPolicy(true);
  }

  @AfterEach
  public void tearDown() {
    // reset configuration
    processEngineConfiguration.setPasswordPolicy(null);
    processEngineConfiguration.setEnablePasswordPolicy(false);
    // reset database
    identityService.deleteUser("user");
  }

  @Test
  public void testPasswordPolicyConfiguration() {
    PasswordPolicy policy = processEngineConfiguration.getPasswordPolicy();
    assertThat(policy.getClass().isAssignableFrom(DefaultPasswordPolicyImpl.class)).isTrue();
    assertThat(policy.getRules()).hasSize(6);
  }

  @Test
  public void testCustomPasswordPolicyWithCompliantPassword() {
    User user = identityService.newUser("user");
    user.setPassword("this-is-1-STRONG-password");
    identityService.saveUser(user);
    assertThat(identityService.createUserQuery().userId(user.getId()).count()).isEqualTo(1L);
  }

  @Test
  public void testCustomPasswordPolicyWithNonCompliantPassword() {
    // given
    User user = identityService.newUser("user");
    user.setPassword("weakpassword");

    // when/then
    assertThatThrownBy(() -> identityService.saveUser(user))
      .isInstanceOf(ProcessEngineException.class)
      .hasMessageContaining("Password does not match policy");

    // and
    assertThat(identityService.createUserQuery().userId(user.getId()).count()).isEqualTo(0L);
  }
}