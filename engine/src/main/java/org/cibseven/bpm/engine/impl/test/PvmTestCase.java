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
package org.cibseven.bpm.engine.impl.test;

import org.cibseven.bpm.engine.delegate.Expression;
import org.cibseven.bpm.engine.impl.cmmn.behavior.CaseControlRuleImpl;
import org.cibseven.bpm.engine.impl.el.FixedValue;
import org.opentest4j.AssertionFailedError;


/**
 * Plain helper base class (assertions) for tests.
 *
 * <p>Since the JUnit 5 migration this class no longer extends {@code junit.framework.TestCase}:
 * it has no test life cycle of its own, and test methods of subclasses must be annotated with
 * {@link org.junit.jupiter.api.Test @Test} (JUnit 3 style {@code public void testXxx()} methods
 * are not discovered anymore).</p>
 *
 * @author Tom Baeyens
 */
public class PvmTestCase {
  /**
   * This class isn't used in the Process Engine test suite anymore.
   * However, some Test classes in the following modules still use it:
   *   * cibseven-engine-plugin-spin
   *   * cibseven-engine-plugin-connect
   *   * cibseven-engine-spring
   *
   * It should be removed once those Test classes are migrated to the JUnit 5 ProcessEngineExtension.
   */

  /**
   * Asserts if the provided text is part of some text.
   */
  public void assertTextPresent(String expected, String actual) {
    if ((actual == null) || (!actual.contains(expected))) {
      throw new AssertionFailedError("expected presence of [" + expected + "], but was [" + actual + "]");
    }
  }

  /**
   * Asserts if the provided text is part of some text, ignoring any uppercase characters
   */
  public void assertTextPresentIgnoreCase(String expected, String actual) {
    assertTextPresent(expected.toLowerCase(), actual.toLowerCase());
  }

  public Object defaultManualActivation() {
    Expression expression = new FixedValue(true);
    CaseControlRuleImpl caseControlRule = new CaseControlRuleImpl(expression);
    return caseControlRule;
  }
}