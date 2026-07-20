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
package org.cibseven.bpm.engine.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.context.Context;
import org.junit.After;
import org.junit.Test;

/**
 * Unit tests for {@link ExpressionWhitelistValidator}, which restricts
 * which JUEL expressions may be used as Task Filter criteria (CIB7-1597).
 */
public class ExpressionWhitelistValidatorTest {

  protected final ExpressionWhitelistValidator<AbstractQuery<?, ?>> validator = ExpressionWhitelistValidator.get();

  protected TaskQueryImpl queryWithExpression(String key, String expression) {
    TaskQueryImpl query = new TaskQueryImpl();
    query.addExpression(key, expression);
    return query;
  }

  // --- allowed: documented safe functions -------------------------------------------------

  @Test
  public void shouldAllowCurrentUser() {
    assertThat(validator.isAllowed("${currentUser()}")).isTrue();
  }

  @Test
  public void shouldAllowCurrentUserGroups() {
    assertThat(validator.isAllowed("${currentUserGroups()}")).isTrue();
  }

  @Test
  public void shouldAllowNow() {
    assertThat(validator.isAllowed("${now()}")).isTrue();
  }

  @Test
  public void shouldAllowWhitespaceVariants() {
    assertThat(validator.isAllowed("${ currentUser() }")).isTrue();
    assertThat(validator.isAllowed("${currentUser( )}")).isTrue();
  }

  @Test
  public void shouldAllowWhitelistedDateTimeExpression() {
    assertThat(validator.isAllowed("${dateTime().withMillis(0)}")).isTrue();
  }

  // --- allowed: plain literal text (never evaluated by JUEL) ------------------------------

  @Test
  public void shouldAllowPlainLiteralText() {
    assertThat(validator.isAllowed("test")).isTrue();
    assertThat(validator.isAllowed("aUserId")).isTrue();
  }

  @Test
  public void shouldRejectNullExpression() {
    assertThat(validator.isAllowed(null)).isFalse();
  }

  // --- rejected: the actual attack surface --------------------------------------------------

  @Test
  public void shouldRejectArbitraryBeanOrMockReference() {
    // bare identifier resolution - the same vector as ${ mockKey } in production Spring beans
    assertThat(validator.isAllowed("${ someRegisteredBean }")).isFalse();
  }

  @Test
  public void shouldRejectMethodInvocationOnWhitelistedFunction() {
    // trying to "extend" an allowed function call to smuggle in extra behavior
    assertThat(validator.isAllowed("${currentUser().concat('x')}")).isFalse();
  }

  @Test
  public void shouldRejectArbitraryMethodChainOnDateTime() {
    // only the one exact whitelisted dateTime() expression is allowed, not arbitrary chaining
    assertThat(validator.isAllowed("${dateTime().getClass()}")).isFalse();
  }

  @Test
  public void shouldRejectReflectionBasedCodeExecutionAttempt() {
    String attack = "${''.getClass().forName('java.lang.Runtime').getMethod('exec', "
        + "''.getClass()).invoke(''.getClass().forName('java.lang.Runtime')"
        + ".getMethod('getRuntime').invoke(null), 'calc.exe')}";
    assertThat(validator.isAllowed(attack)).isFalse();
  }

  @Test
  public void shouldRejectStringLiteralExpression() {
    // not covered by the current whitelist, even though it cannot execute code either
    assertThat(validator.isAllowed("${'test'}")).isFalse();
  }

  // --- validate() throws BadUserRequestException for disallowed expressions ----------------

  @Test
  public void shouldThrowOnDisallowedExpressionDuringValidate() {
    TaskQueryImpl query = queryWithExpression("taskAssignee", "${someBean.deleteAll()}");

    // assert on a stable, domain-level substring rather than the exact wording,
    // so rephrasing the message doesn't break this test
    assertThatThrownBy(() -> validator.validate(query))
        .isInstanceOf(BadUserRequestException.class)
        .hasMessageContaining("task filter");
  }

  @Test
  public void shouldNotThrowOnAllowedExpressionDuringValidate() {
    TaskQueryImpl query = queryWithExpression("taskAssignee", "${currentUser()}");

    validator.validate(query);
    // no exception
  }

  // --- configurable additional whitelist entries (additionalAllowedFilterExpressions) ----

  @After
  public void removeProcessEngineConfiguration() {
    // only the configured-whitelist tests below push a configuration onto the context stack
    if (Context.getProcessEngineConfiguration() != null) {
      Context.removeProcessEngineConfiguration();
    }
  }

  @Test
  public void shouldRejectExpressionNotInConfiguredWhitelistWhenNoConfigurationIsActive() {
    // no Context.setProcessEngineConfiguration(...) call: simulates isAllowed() being invoked
    // outside of any command/engine context, e.g. in a plain unit test
    assertThat(validator.isAllowed("${businessCalendar()}")).isFalse();
  }

  @Test
  public void shouldAllowExpressionAddedToConfiguredWhitelist() {
    Context.setProcessEngineConfiguration(new StandaloneInMemProcessEngineConfiguration()
        .setAdditionalAllowedFilterExpressions(Collections.singleton("${businessCalendar()}")));

    assertThat(validator.isAllowed("${businessCalendar()}")).isTrue();
  }

  @Test
  public void shouldRejectExpressionNotPresentInConfiguredWhitelist() {
    Context.setProcessEngineConfiguration(new StandaloneInMemProcessEngineConfiguration()
        .setAdditionalAllowedFilterExpressions(Collections.singleton("${businessCalendar()}")));

    assertThat(validator.isAllowed("${someOtherBean.exec()}")).isFalse();
  }
}
