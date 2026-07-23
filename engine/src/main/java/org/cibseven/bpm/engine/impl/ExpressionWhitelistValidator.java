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
  // fallback when no process engine configuration is available (e.g. isolated unit tests)
  public static final Set<String> DEFAULT_ALLOWED_EXPRESSIONS = Collections.unmodifiableSet(
      new HashSet<>(Arrays.asList(
          "${currentUser()}",
          "${currentUserGroups()}",
          "${now()}",
          "${dateTime().withMillis(0)}",
          "${dateTime().withTimeAtStartOfDay()}",
          "${dateTime().withTimeAtStartOfDay().plusDays(1).minusSeconds(1)}",
          "${dateTime().plusDays(2)}")));

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
        throw new BadUserRequestException("Expression '" + expression + "' is not allowed in task filter criteria."
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

    String normalized = expression.replaceAll("\\s+", "");
    return getAllowedExpressions().contains(normalized);
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
