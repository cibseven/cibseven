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
package org.cibseven.bpm.impl.juel;

import java.util.Locale;

/**
 * What happens when an expression or script violates the expression hardening.
 */
public enum ExpressionHardeningMode {

  /** No check, behavior as before 3.0.1. */
  OFF,

  /** Log violations, evaluate the expression anyway. */
  REPORT,

  /** Block violations with an {@link ExpressionAccessDeniedException}. */
  ENFORCE;

  /**
   * @param mode {@code off}, {@code report} or {@code enforce}, case-insensitive; null or blank means {@link #ENFORCE}
   * @throws IllegalArgumentException for any other value
   */
  public static ExpressionHardeningMode parse(String mode) {
    if (mode == null || mode.trim().isEmpty()) {
      return ENFORCE;
    }
    String normalized = mode.trim().toUpperCase(Locale.ROOT);
    for (ExpressionHardeningMode value : values()) {
      if (value.name().equals(normalized)) {
        return value;
      }
    }
    throw new IllegalArgumentException(
        "Invalid expression hardening mode '" + mode + "', expected one of: off, report, enforce");
  }

  @Override
  public String toString() {
    return name().toLowerCase(Locale.ROOT);
  }
}
