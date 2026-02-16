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
package org.cibseven.bpm.engine.test.cmmn.decisiontask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cibseven.bpm.dmn.engine.DmnDecisionResult;
import org.cibseven.bpm.dmn.engine.DmnDecisionResultEntries;
import org.cibseven.bpm.engine.runtime.CaseInstance;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.cmmn.CmmnTest;
import org.cibseven.bpm.engine.variable.Variables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * @author Roman Smirnov
 *
 */
public class DmnDecisionTaskResultListenerTest extends CmmnTest {

  protected static final String TEST_CASE = "org/cibseven/bpm/engine/test/cmmn/decisiontask/DmnDecisionTaskResultListenerTest.cmmn";
  protected static final String TEST_DECISION = "org/cibseven/bpm/engine/test/dmn/result/DmnDecisionResultTest.dmn11.xml";
  protected static final String TEST_DECISION_COLLECT_SUM = "org/cibseven/bpm/engine/test/dmn/result/DmnDecisionResultCollectSumHitPolicyTest.dmn11.xml";
  protected static final String TEST_DECISION_COLLECT_COUNT = "org/cibseven/bpm/engine/test/dmn/result/DmnDecisionResultCollectCountHitPolicyTest.dmn11.xml";

  protected DmnDecisionResult results;

  @Deployment(resources = { TEST_CASE, TEST_DECISION})
  @Test
  public void testNoOutput() {
    startTestCase("no output");

    assertTrue("The decision result 'ruleResult' should be empty", results.isEmpty());
  }

  @Deployment(resources = { TEST_CASE, TEST_DECISION})
  @Test
  public void testEmptyOutput() {
    startTestCase("empty output");

    assertFalse("The decision result 'ruleResult' should not be empty", results.isEmpty());

    DmnDecisionResultEntries decisionOutput = results.get(0);
    assertNull(decisionOutput.getFirstEntry());
  }

  @Deployment(resources = { TEST_CASE, TEST_DECISION})
  @Test
  public void testEmptyMap() {
    startTestCase("empty map");

    assertEquals(2, results.size());

    for (DmnDecisionResultEntries output : results) {
      assertTrue("The decision output should be empty", output.isEmpty());
    }
  }

  @Deployment(resources = { TEST_CASE, TEST_DECISION})
  @Test
  public void testSingleEntry() {
    startTestCase("single entry");

    DmnDecisionResultEntries firstOutput = results.get(0);
    assertEquals("foo", firstOutput.getFirstEntry());
    assertEquals(Variables.stringValue("foo"), firstOutput.getFirstEntryTyped());
  }

  @Deployment(resources = { TEST_CASE, TEST_DECISION})
  @Test
  public void testMultipleEntries() {
    startTestCase("multiple entries");

    DmnDecisionResultEntries firstOutput = results.get(0);
    assertEquals("foo", firstOutput.get("result1"));
    assertEquals("bar", firstOutput.get("result2"));

    assertEquals(Variables.stringValue("foo"), firstOutput.getEntryTyped("result1"));
    assertEquals(Variables.stringValue("bar"), firstOutput.getEntryTyped("result2"));
  }

  @Deployment(resources = { TEST_CASE, TEST_DECISION})
  @Test
  public void testSingleEntryList() {
    startTestCase("single entry list");

    assertEquals(2, results.size());

    for (DmnDecisionResultEntries output : results) {
      assertEquals("foo", output.getFirstEntry());
      assertEquals(Variables.stringValue("foo"), output.getFirstEntryTyped());
    }
  }

  @Deployment(resources = { TEST_CASE, TEST_DECISION})
  @Test
  public void testMultipleEntriesList() {
    startTestCase("multiple entries list");

    assertEquals(2, results.size());

    for (DmnDecisionResultEntries output : results) {
      assertEquals(2, output.size());
      assertEquals("foo", output.get("result1"));
      assertEquals("bar", output.get("result2"));

      assertEquals(Variables.stringValue("foo"), output.getEntryTyped("result1"));
      assertEquals(Variables.stringValue("bar"), output.getEntryTyped("result2"));
    }
  }

  @Deployment(resources = { TEST_CASE, TEST_DECISION_COLLECT_COUNT })
  @Test
  public void testCollectCountHitPolicyNoOutput() {
    startTestCase("no output");

    assertEquals(1, results.size());
    DmnDecisionResultEntries firstOutput = results.get(0);

    assertEquals(0, (int) firstOutput.getFirstEntry());
    assertEquals(Variables.integerValue(0), firstOutput.getFirstEntryTyped());
  }

  @Deployment(resources = { TEST_CASE, TEST_DECISION_COLLECT_SUM })
  @Test
  public void testCollectSumHitPolicyNoOutput() {
    startTestCase("no output");

    assertTrue("The decision result 'ruleResult' should be empty", results.isEmpty());
  }

  @Deployment(resources = { TEST_CASE, TEST_DECISION_COLLECT_SUM })
  @Test
  public void testCollectSumHitPolicySingleEntry() {
    startTestCase("single entry");

    assertEquals(1, results.size());
    DmnDecisionResultEntries firstOutput = results.get(0);

    assertEquals(12, (int) firstOutput.getFirstEntry());
    assertEquals(Variables.integerValue(12), firstOutput.getFirstEntryTyped());
  }

  @Deployment(resources = { TEST_CASE, TEST_DECISION_COLLECT_SUM })
  @Test
  public void testCollectSumHitPolicySingleEntryList() {
    startTestCase("single entry list");

    assertEquals(1, results.size());
    DmnDecisionResultEntries firstOutput = results.get(0);

    assertEquals(33, (int) firstOutput.getFirstEntry());
    assertEquals(Variables.integerValue(33), firstOutput.getFirstEntryTyped());
  }

  protected CaseInstance startTestCase(String input) {
    CaseInstance caseInstance = createCaseInstanceByKey("case", Variables.createVariables().putValue("input", input));
    results = DecisionResultTestListener.getDecisionResult();
    assertNotNull(results);
    return caseInstance;
  }

  @AfterEach
  public void tearDown() throws Exception {
    // reset the invoked execution listener
    DecisionResultTestListener.reset();
  }

}
