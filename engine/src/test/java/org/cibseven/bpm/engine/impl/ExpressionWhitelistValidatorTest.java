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
package org.cibseven.bpm.engine.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.context.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ExpressionWhitelistValidator}.
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

  @Test
  public void shouldAllowStandardTasklistFilterExpressions() {
    // "Tasks due today" (Due After / Due Before) and the "within a timespan" example
    // from the out-of-the-box Tasklist filter templates, as documented in
    // content/webapps/tasklist/filters.md of cibseven-docs-manual
    assertThat(validator.isAllowed("${dateTime().withTimeAtStartOfDay()}")).isTrue();
    assertThat(validator.isAllowed("${dateTime().withTimeAtStartOfDay().plusDays(1).minusSeconds(1)}")).isTrue();
    assertThat(validator.isAllowed("${dateTime().plusDays(2)}")).isTrue();
  }

  // --- allowed: everything the product itself suggests or creates --------------------------
  // The default whitelist was derived from the filter documentation alone, which left the dialog's
  // own examples and one seeded filter rejected (CIB7-2247). These tests pin the other sources by
  // hand; nothing compares them against the real ones, so keep them in sync.

  @Test
  public void shouldKeepEveryDefaultEntryNormalized() {
    // an entry carrying a concrete argument - ${dateTime().plusWeeks(2)}, the way the dialog and
    // the docs spell it - could never match, since isAllowed normalizes its input
    for (String expression : ExpressionWhitelistValidator.DEFAULT_ALLOWED_EXPRESSIONS) {
      assertThat(ExpressionWhitelistValidator.normalize(expression))
          .as("default whitelist entry must be stored normalized")
          .isEqualTo(expression);
    }
  }

  @Test
  public void shouldAllowTheExpressionsSuggestedByTheFilterDialog() {
    // verbatim from cam-tasklist-filter-modal-criteria.js. Note that not every expression-capable
    // criterion carries a help text at all - candidateGroup, for one, has none.
    assertThat(validator.isAllowed("${ now() }")).isTrue();
    assertThat(validator.isAllowed("${ dateTime() }")).isTrue();
    assertThat(validator.isAllowed("${ dateTime().plusWeeks(2) }")).isTrue();
    assertThat(validator.isAllowed("${ currentUser() }")).isTrue();
    assertThat(validator.isAllowed("${ currentUserGroups() }")).isTrue();
  }

  @Test
  public void shouldNotThrowWhenValidatingAFilterBuiltFromTheDialogExample() {
    // the reported symptom is a BadUserRequestException from FilterManager#insertOrUpdateFilter,
    // which calls validate() - pin that path, not only the lookup underneath it
    validator.validate(queryWithExpression("dueBefore", "${ dateTime() }"));
    validator.validate(queryWithExpression("dueAfter", "${ dateTime().plusWeeks(2) }"));
    validator.validate(queryWithExpression("followUpBeforeOrNotExistent", "${ now() }"));
    // no exception
  }

  @Test
  public void shouldAllowTheFiltersSeededByTheDemoData() {
    // "Soon due tasks" from InvoiceDemoDataGenerator. Its chain order is the point: normalize()
    // strips numeric arguments but does not reorder, so plusDays().withTimeAtStartOfDay() is a
    // different entry than withTimeAtStartOfDay().plusDays().minusSeconds() - that was the defect.
    validator.validate(queryWithExpression("dueBefore", "${dateTime().plusDays(4).withTimeAtStartOfDay()}"));

    // "My Tasks" and "My Group Tasks", seeded here and by the invoice example's DemoDataGenerator
    assertThat(validator.isAllowed("${currentUser()}")).isTrue();
    assertThat(validator.isAllowed("${currentUserGroups()}")).isTrue();

    // "Accounting Tasks" passes a plain group name, which takes the literal short circuit
    assertThat(validator.isAllowed("accounting")).isTrue();
  }

  // --- numeric arguments act as a wildcard (see ExpressionWhitelistValidator#normalize) ----

  @Test
  public void shouldAllowAnyNumericArgumentOnWhitelistedMethod() {
    assertThat(validator.isAllowed("${dateTime().plusDays(5)}")).isTrue();
    assertThat(validator.isAllowed("${dateTime().plusDays(30)}")).isTrue();
    assertThat(validator.isAllowed("${dateTime().withMillis(999)}")).isTrue();
    assertThat(validator.isAllowed("${dateTime().withTimeAtStartOfDay().plusDays(7).minusSeconds(60)}")).isTrue();
  }

  @Test
  public void shouldAllowAnyNumericArgumentOnTheDialogAndDemoDataEntriesToo() {
    // the CIB7-2247 entries must wildcard their argument too, not just the single value their
    // source uses (plusWeeks(2) in the help text, plusDays(4) in InvoiceDemoDataGenerator)
    assertThat(validator.isAllowed("${dateTime().plusWeeks(1)}")).isTrue();
    assertThat(validator.isAllowed("${dateTime().plusWeeks(52)}")).isTrue();
    assertThat(validator.isAllowed("${dateTime().plusDays(1).withTimeAtStartOfDay()}")).isTrue();
    assertThat(validator.isAllowed("${dateTime().plusDays(30).withTimeAtStartOfDay()}")).isTrue();
  }

  @Test
  public void shouldAllowEmptyArgumentListAsWildcardNotation() {
    assertThat(validator.isAllowed("${dateTime().plusDays()}")).isTrue();
    assertThat(validator.isAllowed("${dateTime().withTimeAtStartOfDay().plusDays().minusSeconds()}")).isTrue();
    assertThat(validator.isAllowed("${dateTime().plusWeeks()}")).isTrue();
    assertThat(validator.isAllowed("${dateTime().plusDays().withTimeAtStartOfDay()}")).isTrue();
  }

  @Test
  public void shouldAllowNumericArgumentCombinedWithWhitespace() {
    assertThat(validator.isAllowed("${ dateTime().plusDays( 5 ) }")).isTrue();
    assertThat(validator.isAllowed("${ dateTime().plusWeeks( 3 ) }")).isTrue();
  }

  @Test
  public void shouldStillRejectDifferentMethodOrChainDespiteNumericWildcard() {
    // only the numeric value is wildcarded - names and structure must still match
    assertThat(validator.isAllowed("${dateTime().plusYears(2)}")).isFalse();
    assertThat(validator.isAllowed("${dateTime().plusDays(2).getClass()}")).isFalse();
    assertThat(validator.isAllowed("${someBean.getById(2)}")).isFalse();
    // same for the entries added in CIB7-2247: the wildcard must not buy a longer chain
    assertThat(validator.isAllowed("${dateTime().plusWeeks(2).getClass()}")).isFalse();
    assertThat(validator.isAllowed("${dateTime().plusDays(4).withTimeAtStartOfDay().getClass()}")).isFalse();
  }

  @Test
  public void shouldRejectRecombinationsOfTheNowLargerMethodVocabulary() {
    // the two new entries were added as whole chains, not as building blocks - the methods they
    // introduce must not become freely combinable. Rejected deliberately.
    assertThat(validator.isAllowed("${dateTime().plusWeeks(2).withTimeAtStartOfDay()}")).isFalse();
    assertThat(validator.isAllowed("${dateTime().withTimeAtStartOfDay().plusWeeks(1)}")).isFalse();
    assertThat(validator.isAllowed("${dateTime().plusWeeks(2).plusDays(1)}")).isFalse();
    assertThat(validator.isAllowed("${dateTime().plusDays(4).withTimeAtStartOfDay().minusSeconds(1)}")).isFalse();
  }

  @Test
  public void shouldStillRejectStringArgumentSinceOnlyNumbersAreWildcarded() {
    assertThat(validator.isAllowed("${dateTime().withZone('Europe/Berlin')}")).isFalse();
  }

  @Test
  public void shouldMatchConfiguredEntryRegardlessOfItsNumericArgument() {
    Context.setProcessEngineConfiguration(new StandaloneInMemProcessEngineConfiguration()
        .setAllowedFilterExpressions("${dateTime().plusHours(3)}"));

    assertThat(validator.isAllowed("${dateTime().plusHours(7)}")).isTrue();
    assertThat(validator.isAllowed("${dateTime().plusHours()}")).isTrue();
    assertThat(validator.isAllowed("${dateTime().plusMinutes(7)}")).isFalse();
  }

  @Test
  public void shouldNotRewriteTheExpressionThatIsEvaluated() {
    // normalization happens on a throwaway copy: the query keeps the original argument value,
    // so JUEL still evaluates plusDays(5) and not plusDays()
    TaskQueryImpl query = queryWithExpression("dueAfter", "${dateTime().plusDays(5)}");

    validator.validate(query);

    assertThat(query.getExpressions()).containsEntry("dueAfter", "${dateTime().plusDays(5)}");
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
    assertThat(validator.isAllowed("${ someRegisteredBean }")).isFalse();
  }

  @Test
  public void shouldRejectMethodInvocationOnWhitelistedFunction() {
    assertThat(validator.isAllowed("${currentUser().concat('x')}")).isFalse();
  }

  @Test
  public void shouldRejectArbitraryMethodChainOnDateTime() {
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
    assertThat(validator.isAllowed("${'test'}")).isFalse();
  }

  // --- validate() throws BadUserRequestException for disallowed expressions ----------------

  @Test
  public void shouldThrowOnDisallowedExpressionDuringValidate() {
    TaskQueryImpl query = queryWithExpression("taskAssignee", "${someBean.deleteAll()}");

    assertThatThrownBy(() -> validator.validate(query))
        .isInstanceOf(BadUserRequestException.class)
        .hasMessageContaining("task query criteria");
  }

  @Test
  public void shouldNotThrowOnAllowedExpressionDuringValidate() {
    TaskQueryImpl query = queryWithExpression("taskAssignee", "${currentUser()}");

    validator.validate(query);
    // no exception
  }

  // --- configurable whitelist (ProcessEngineConfiguration#allowedFilterExpressions) -------

  @AfterEach
  public void removeProcessEngineConfiguration() {
    if (Context.getProcessEngineConfiguration() != null) {
      Context.removeProcessEngineConfiguration();
    }
  }

  @Test
  public void shouldRejectExpressionNotInDefaultWhitelistWhenNoConfigurationIsActive() {
    assertThat(validator.isAllowed("${businessCalendar()}")).isFalse();
  }

  @Test
  public void shouldAllowExpressionAddedToConfiguredWhitelist() {
    Context.setProcessEngineConfiguration(new StandaloneInMemProcessEngineConfiguration()
        .setAllowedFilterExpressions("${businessCalendar()}"));

    assertThat(validator.isAllowed("${businessCalendar()}")).isTrue();
  }

  @Test
  public void shouldRejectExpressionNotPresentInConfiguredWhitelist() {
    Context.setProcessEngineConfiguration(new StandaloneInMemProcessEngineConfiguration()
        .setAllowedFilterExpressions("${businessCalendar()}"));

    assertThat(validator.isAllowed("${someOtherBean.exec()}")).isFalse();
  }

  @Test
  public void shouldRejectDefaultExpressionWhenConfigurationReplacesTheWholeWhitelist() {
    Context.setProcessEngineConfiguration(new StandaloneInMemProcessEngineConfiguration()
        .setAllowedFilterExpressions("${businessCalendar()}"));

    assertThat(validator.isAllowed("${currentUser()}")).isFalse();
  }

  @Test
  public void shouldParseSemicolonSeparatedConfiguredExpressions() {
    Context.setProcessEngineConfiguration(new StandaloneInMemProcessEngineConfiguration()
        .setAllowedFilterExpressions(" ${businessCalendar()} ; ${anotherFunction()} "));

    assertThat(validator.isAllowed("${businessCalendar()}")).isTrue();
    assertThat(validator.isAllowed("${anotherFunction()}")).isTrue();
    assertThat(validator.isAllowed("${notListed()}")).isFalse();
  }

  // --- enableFilterExpressionWhitelist toggle ----------------------------------------------

  @Test
  public void shouldNotThrowOnDisallowedExpressionWhenWhitelistDisabled() {
    StandaloneInMemProcessEngineConfiguration configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration.setEnableFilterExpressionWhitelist(false);
    Context.setProcessEngineConfiguration(configuration);
    TaskQueryImpl query = queryWithExpression("taskAssignee", "${someBean.deleteAll()}");

    validator.validate(query);
    // no exception
  }

  @Test
  public void shouldThrowOnDisallowedExpressionWhenWhitelistReenabled() {
    StandaloneInMemProcessEngineConfiguration configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration.setEnableFilterExpressionWhitelist(true);
    Context.setProcessEngineConfiguration(configuration);
    TaskQueryImpl query = queryWithExpression("taskAssignee", "${someBean.deleteAll()}");

    assertThatThrownBy(() -> validator.validate(query))
        .isInstanceOf(BadUserRequestException.class);
  }

  // --- adhoc queries go through the same whitelist as stored filters -----------------------

  @Test
  public void shouldRejectDisallowedExpressionOnAdhocQuery() {
    StandaloneInMemProcessEngineConfiguration configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration.setEnableExpressionsInAdhocQueries(true);
    configuration.setEnableFilterExpressionWhitelist(true);
    Context.setProcessEngineConfiguration(configuration);

    TaskQueryImpl adhocQuery = new TaskQueryImpl(null);
    adhocQuery.taskAssigneeExpression("${someBean.deleteAll()}");

    assertThatThrownBy(adhocQuery::validate)
        .isInstanceOf(BadUserRequestException.class)
        .hasMessageContaining("task query criteria");
  }

  @Test
  public void shouldAllowWhitelistedExpressionOnAdhocQuery() {
    StandaloneInMemProcessEngineConfiguration configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration.setEnableExpressionsInAdhocQueries(true);
    Context.setProcessEngineConfiguration(configuration);

    TaskQueryImpl adhocQuery = new TaskQueryImpl(null);
    adhocQuery.taskAssigneeExpression("${currentUser()}");

    adhocQuery.validate();
    // no exception
  }

  // --- or() branches (e.g. REST orQueries[] deserialization via addOrQuery) inherit the
  // same validators as the root query, so they cannot be used to sneak an expression past
  // the whitelist ------------------------------------------------------------------------

  @Test
  public void shouldRejectDisallowedExpressionInOrQueryBranch() {
    StandaloneInMemProcessEngineConfiguration configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration.setEnableExpressionsInAdhocQueries(true);
    configuration.setEnableFilterExpressionWhitelist(true);
    Context.setProcessEngineConfiguration(configuration);

    TaskQueryImpl rootQuery = new TaskQueryImpl(null);
    TaskQueryImpl orBranch = new TaskQueryImpl();
    orBranch.taskAssigneeExpression("${someBean.deleteAll()}");
    rootQuery.addOrQuery(orBranch);

    assertThatThrownBy(orBranch::validate)
        .isInstanceOf(BadUserRequestException.class)
        .hasMessageContaining("task query criteria");
  }

  @Test
  public void shouldAllowWhitelistedExpressionInOrQueryBranch() {
    StandaloneInMemProcessEngineConfiguration configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration.setEnableExpressionsInAdhocQueries(true);
    Context.setProcessEngineConfiguration(configuration);

    TaskQueryImpl rootQuery = new TaskQueryImpl(null);
    TaskQueryImpl orBranch = new TaskQueryImpl();
    orBranch.taskAssigneeExpression("${currentUser()}");
    rootQuery.addOrQuery(orBranch);

    orBranch.validate();
    // no exception
  }
}
