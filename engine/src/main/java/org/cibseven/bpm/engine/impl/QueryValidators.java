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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.impl.context.Context;

/**
 * @author Thorben Lindhauer
 *
 */
public class QueryValidators {

  public static class AdhocQueryValidator<T extends AbstractQuery<?, ?>> implements Validator<T> {

    @SuppressWarnings("rawtypes")
    public static final AdhocQueryValidator INSTANCE = new AdhocQueryValidator();

    private AdhocQueryValidator() {
    }

    @Override
    public void validate(T query) {
      if (!Context.getProcessEngineConfiguration().isEnableExpressionsInAdhocQueries() &&
          !query.getExpressions().isEmpty()) {
        throw new BadUserRequestException("Expressions are forbidden in adhoc queries. This behavior can be toggled"
            + " in the process engine configuration");
      }
    }

    @SuppressWarnings("unchecked")
    public static <T extends AbstractQuery<?, ?>> AdhocQueryValidator<T> get() {
      return (AdhocQueryValidator<T>) INSTANCE;
    }

  }

  public static class StoredQueryValidator<T extends AbstractQuery<?, ?>> implements Validator<T> {

    @SuppressWarnings("rawtypes")
    public static final StoredQueryValidator INSTANCE = new StoredQueryValidator();

    private StoredQueryValidator() {
    }

    @Override
    public void validate(T query) {
      if (!Context.getProcessEngineConfiguration().isEnableExpressionsInStoredQueries() &&
          !query.getExpressions().isEmpty()) {
        throw new BadUserRequestException("Expressions are forbidden in stored queries. This behavior can be toggled"
            + " in the process engine configuration");
      }
    }

    @SuppressWarnings("unchecked")
    public static <T extends AbstractQuery<?, ?>> StoredQueryValidator<T> get() {
      return (StoredQueryValidator<T>) INSTANCE;
    }
  }

  public static class ExpressionWhitelistValidator<T extends AbstractQuery<?, ?>> implements Validator<T> {

    // bare, zero-argument, side-effect-free functions: safe to evaluate with no restrictions
    // on where they appear (covers the "My Tasks" / "My Group Tasks" / date-comparison use cases)
    protected static final Set<String> ALLOWED_FUNCTION_EXPRESSIONS = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList(
            "${currentUser()}",
            "${currentUserGroups()}",
            "${now()}")));

    // dateTime() only exposes a bounded, safe set of Joda-Time methods, but we whitelist this
    // one exact expression rather than allowing arbitrary method chaining after dateTime(),
    // to keep the attack surface as small as possible.
    protected static final String ALLOWED_DATE_TIME_EXPRESSION = "${dateTime().withMillis(0)}";

    @SuppressWarnings("rawtypes")
    public static final ExpressionWhitelistValidator INSTANCE = new ExpressionWhitelistValidator();

    private ExpressionWhitelistValidator() {
    }

    @Override
    public void validate(T query) {
      for (String expression : query.getExpressions().values()) {
        if (!isAllowed(expression)) {
          throw new BadUserRequestException("Expression '" + expression + "' is not allowed in task filter criteria."
              + " Only currentUser(), currentUserGroups(), now() and plain literal values may be used.");
        }
      }
    }

    protected boolean isAllowed(String expression) {
      if (expression == null) {
        return false;
      }

      // plain literal text (no EL delimiters at all) is never evaluated by JUEL,
      // so it cannot invoke any function, method or bean and is always safe
      if (!expression.contains("${") && !expression.contains("#{")) {
        return true;
      }

      String normalized = expression.replaceAll("\\s+", "");
      return ALLOWED_FUNCTION_EXPRESSIONS.contains(normalized)
          || ALLOWED_DATE_TIME_EXPRESSION.equals(normalized);
    }

    @SuppressWarnings("unchecked")
    public static <T extends AbstractQuery<?, ?>> ExpressionWhitelistValidator<T> get() {
      return (ExpressionWhitelistValidator<T>) INSTANCE;
    }
  }

}
