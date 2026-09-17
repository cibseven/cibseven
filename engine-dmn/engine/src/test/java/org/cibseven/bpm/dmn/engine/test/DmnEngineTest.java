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
package org.cibseven.bpm.dmn.engine.test;

import static org.cibseven.bpm.dmn.engine.test.asserts.DmnEngineTestAssertions.assertThat;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import org.cibseven.bpm.dmn.engine.DmnDecision;
import org.cibseven.bpm.dmn.engine.DmnDecisionResult;
import org.cibseven.bpm.dmn.engine.DmnDecisionTableResult;
import org.cibseven.bpm.dmn.engine.DmnEngine;
import org.cibseven.bpm.dmn.engine.DmnEngineConfiguration;
import org.cibseven.bpm.dmn.engine.test.asserts.DmnDecisionTableResultAssert;
import org.cibseven.bpm.engine.variable.VariableMap;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.commons.utils.IoUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

public abstract class DmnEngineTest {

  public DmnEngineTestRule dmnEngineRule = new DmnEngineTestRule(getDmnEngineConfiguration());

  public DmnEngine dmnEngine;
  public DmnDecision decision;
  public VariableMap variables;

  public DmnEngineConfiguration getDmnEngineConfiguration() {
    return null;
  }

  @BeforeEach
  public void initAll(TestInfo testInfo) {
    dmnEngine = dmnEngineRule.getDmnEngine();

    // Load @DecisionResource annotation from the test method
    DecisionResource decisionResource = testInfo.getTestMethod()
      .flatMap(m -> Optional.ofNullable(m.getAnnotation(DecisionResource.class)))
      .orElse(null);
    if (decisionResource != null) {
      String resourcePath = decisionResource.resource();
      resourcePath = expandResourcePath(testInfo, resourcePath);
      InputStream inputStream = org.cibseven.commons.utils.IoUtil.fileAsStream(resourcePath);
      String decisionKey = decisionResource.decisionKey();
      if (decisionKey == null || decisionKey.isEmpty()) {
        List<DmnDecision> decisions = dmnEngine.parseDecisions(inputStream);
        decision = decisions.isEmpty() ? null : decisions.get(0);
      } else {
        decision = dmnEngine.parseDecision(decisionKey, inputStream);
      }
    } else {
      decision = null;
    }

    variables = Variables.createVariables();
  }

  protected String expandResourcePath(TestInfo testInfo, String resourcePath) {
    if (resourcePath.contains("/")) {
      return resourcePath;
    } else {
      Class<?> testClass = testInfo.getTestClass().orElseThrow();
      if (resourcePath.isEmpty()) {
        return testClass.getName().replace(".", "/") + "." + testInfo.getTestMethod().get().getName() + ".dmn";
      } else {
        return testClass.getPackageName().replace(".", "/") + "/" + resourcePath;
      }
    }
  }

  public VariableMap getVariables() {
    return variables;
  }

  // parsing //////////////////////////////////////////////////////////////////

  public List<DmnDecision> parseDecisionsFromFile(String filename) {
    InputStream inputStream = IoUtil.fileAsStream(filename);
    return dmnEngine.parseDecisions(inputStream);
  }

  public DmnDecision parseDecisionFromFile(String decisionKey, String filename) {
    InputStream inputStream = IoUtil.fileAsStream(filename);
    return dmnEngine.parseDecision(decisionKey, inputStream);
  }

  // evaluations //////////////////////////////////////////////////////////////

  public DmnDecisionTableResult evaluateDecisionTable() {
    return dmnEngine.evaluateDecisionTable(decision, variables);
  }

  public DmnDecisionTableResult evaluateDecisionTable(DmnEngine engine) {
    return engine.evaluateDecisionTable(decision, variables);
  }

  public DmnDecisionResult evaluateDecision() {
    return dmnEngine.evaluateDecision(decision, variables);
  }

  // assertions ///////////////////////////////////////////////////////////////

  public DmnDecisionTableResultAssert assertThatDecisionTableResult() {
    DmnDecisionTableResult results = evaluateDecisionTable(dmnEngine);
    return assertThat(results);
  }

  public DmnDecisionTableResultAssert assertThatDecisionTableResult(DmnEngine engine) {
    DmnDecisionTableResult results = evaluateDecisionTable(engine);
    return assertThat(results);
  }

}