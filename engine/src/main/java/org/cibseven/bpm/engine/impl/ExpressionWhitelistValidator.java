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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.context.Context;

public class ExpressionWhitelistValidator<T extends AbstractQuery<?, ?>> implements Validator<T> {

  // default value of ProcessEngineConfigurationImpl#allowedFilterExpressions; used as a
  // fallback when no process engine configuration is available (e.g. isolated unit tests).
  // Kept in normalized form (see #normalize): numeric arguments are stripped, so empty
  // parentheses stand for any numeric argument. An entry written with a concrete number would be
  // dead weight - lookups are normalized, so it could never match, not even the literal value it
  // was written with. shouldKeepEveryDefaultEntryNormalized guards against that.
  //
  // This list has to cover every expression the product itself suggests or creates, otherwise
  // users following our own examples hit a BadUserRequestException (CIB7-2247). Known sources:
  //   - the Tasklist filter dialog's help texts (dateExpLangHelp, userExpLangHelp and
  //     commaSeparatedExps in cam-tasklist-filter-modal-criteria.js),
  //   - the filter documentation (content/webapps/tasklist/filters.md in cibseven-docs-manual),
  //   - the filters seeded by InvoiceDemoDataGenerator and by the invoice example's
  //     DemoDataGenerator,
  //   - the engine test suite, which is where ${dateTime().withMillis()} comes from - it is not
  //     documented or suggested anywhere (FilterTaskQueryTest, followUpBeforeOrNotExistent).
  //
  // ExpressionWhitelistValidatorTest holds a hand-maintained copy of those expressions. Nothing
  // in the build compares it against the actual sources - the dialog is JavaScript, the docs live
  // in another repository, and the demo generator is only compiled under the develop profile. So
  // when you change one of the sources, or add one, update the test too; it cannot notice by
  // itself.
  public static final Set<String> DEFAULT_ALLOWED_EXPRESSIONS = Collections.unmodifiableSet(
      new HashSet<>(Arrays.asList(
          "${currentUser()}",
          "${currentUserGroups()}",
          "${now()}",
          "${dateTime()}",
          "${dateTime().withMillis()}",
          "${dateTime().withTimeAtStartOfDay()}",
          "${dateTime().withTimeAtStartOfDay().plusDays().minusSeconds()}",
          "${dateTime().plusDays()}",
          "${dateTime().plusDays().withTimeAtStartOfDay()}",
          "${dateTime().plusWeeks()}")));

  @SuppressWarnings("rawtypes")
  public static final ExpressionWhitelistValidator INSTANCE = new ExpressionWhitelistValidator();

  private ExpressionWhitelistValidator() {
  }

  @Override
  public void validate(T query) {
    if (!isEnabled()) {
      return;
    }
    for (String expression : query.getExpressions().values()) {
      if (!isAllowed(expression)) {
        throw new BadUserRequestException("Expression '" + expression + "' is not allowed in task query criteria."
            + " Only " + getAllowedExpressions() + " and plain literal values may be used.");
      }
    }
  }

  protected boolean isEnabled() {
    ProcessEngineConfigurationImpl configuration = Context.getProcessEngineConfiguration();
    return configuration == null || configuration.isEnableFilterExpressionWhitelist();
  }

  protected boolean isAllowed(String expression) {
    if (expression == null) {
      return false;
    }

    // plain literal text (no EL delimiters) is never evaluated by JUEL, so it is always safe
    if (!expression.contains("${") && !expression.contains("#{")) {
      return true;
    }

    return getAllowedExpressions().contains(normalize(expression));
  }

  /**
   * Strips whitespace and numeric argument lists, so {@code ${dateTime().plusDays()}},
   * {@code (2)} and {@code (5)} are equivalent and permit any day count. Method names, bean
   * names, string arguments and the chaining structure still have to match exactly.
   * Whitelists are stored normalized.
   */
  public static String normalize(String expression) {
    return expression
        .replaceAll("\\s+", "")
        .replaceAll("\\(\\d+(?:\\.\\d+)?(?:,\\d+(?:\\.\\d+)?)*\\)", "()");
  }

  protected Set<String> getAllowedExpressions() {
    ProcessEngineConfigurationImpl configuration = Context.getProcessEngineConfiguration();
    return configuration != null ? configuration.getAllowedFilterExpressions() : DEFAULT_ALLOWED_EXPRESSIONS;
  }

  @SuppressWarnings("unchecked")
  public static <T extends AbstractQuery<?, ?>> ExpressionWhitelistValidator<T> get() {
    return (ExpressionWhitelistValidator<T>) INSTANCE;
  }
}
