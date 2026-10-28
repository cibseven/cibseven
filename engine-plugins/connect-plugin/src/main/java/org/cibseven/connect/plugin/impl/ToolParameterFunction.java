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
package org.cibseven.connect.plugin.impl;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Locale;

/**
 * The expression function {@code toolParameter} (CIB7-2136), with which a tool of an agentic ad
 * hoc sub process declares a parameter in its input mapping:
 *
 * <pre>
 * &lt;camunda:inputParameter name="amount"&gt;
 *   ${toolParameter(toolCall.amount, 'Amount in euros', 'integer')}
 * &lt;/camunda:inputParameter&gt;
 * </pre>
 *
 * <p>What the declaration means is the AI agent's business: its plugin reads the call at
 * deployment and offers the parameter to the model, and the arguments of a call arrive in
 * {@code toolCall}. The function itself only hands its value on, so it is registered here, by the
 * plugin every distribution registers, and not by the AI agent's, which is absent while the agent
 * is switched off. An expression binds its functions when the model is parsed: registered only
 * with the agent, a model using it would be refused while the agent is off, and a definition
 * deployed before would no longer parse after a restart, which stops every instance of it.
 */
public final class ToolParameterFunction {

  /** The function's name in expressions. */
  public static final String NAME = "toolParameter";

  private ToolParameterFunction() {
    // the function only
  }

  /**
   * The value the model passed for the parameter. When the call names the type, a number gets
   * the Java type the agent's call converts it to, which a map read back from JSON may not have
   * kept: an integral number that fits a Long becomes one for 'integer', any number a Double for
   * 'number'.
   * For 'integer' nothing is cut: a fraction, or a number beyond a Long, is handed on as it is;
   * the agent's call never gets there, it is refused before the activity starts. For 'number' a
   * Double is what the agent's call holds already, since the call's arguments are
   * checked and converted before the activity starts: a number with more significant digits than
   * a Double keeps has lost them there. A call without a type, and a plain read of
   * {@code toolCall.<name>}, hand the stored value on as it is. The description is for the
   * catalogue only. The type of an optional parameter carries a trailing '?' (CIB7-2135); its value
   * gets the same Java type.
   */
  public static Object toolParameter(Object value, String... descriptionAndType) {
    if (value instanceof Number && descriptionAndType != null && descriptionAndType.length > 1
        && descriptionAndType[1] != null) {
      String type = descriptionAndType[1].trim().toLowerCase(Locale.ROOT);
      if (type.endsWith("?")) {
        type = type.substring(0, type.length() - 1).trim();
      }
      if ("integer".equals(type) && !(value instanceof Long)) {
        Long integral = integral((Number) value);
        return (integral != null) ? integral : value;
      }
      if ("number".equals(type) && !(value instanceof Double)) {
        return ((Number) value).doubleValue();
      }
    }
    return value;
  }

  /** The number as a Long when it is integral and within range, otherwise null. */
  private static Long integral(Number value) {
    try {
      return new BigDecimal(value.toString()).longValueExact();
    } catch (ArithmeticException | NumberFormatException e) {
      return null;
    }
  }

  /** The method expressions call for {@link #NAME}. */
  public static Method method() {
    try {
      return ToolParameterFunction.class.getMethod(NAME, Object.class, String[].class);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }
}
