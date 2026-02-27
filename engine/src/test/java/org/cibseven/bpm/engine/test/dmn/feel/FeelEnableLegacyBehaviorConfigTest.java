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
package org.cibseven.bpm.engine.test.dmn.feel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.cibseven.bpm.dmn.feel.impl.juel.FeelSyntaxException;
import org.cibseven.bpm.engine.DecisionService;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.util.ProcessEngineBootstrapRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.engine.variable.Variables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Order;

import org.junit.jupiter.api.Test;


public class FeelEnableLegacyBehaviorConfigTest {

  @RegisterExtension
  @Order(3) public static ProcessEngineBootstrapRule bootstrapRule = new ProcessEngineBootstrapRule(configuration ->
      configuration.setDmnFeelEnableLegacyBehavior(true));
  @RegisterExtension
  protected ProvidedProcessEngineRule engineRule = new ProvidedProcessEngineRule(bootstrapRule);
  @RegisterExtension
  @Order(9) protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  protected DecisionService decisionService;

  @BeforeEach
  public void setup() {
    decisionService = engineRule.getProcessEngine().getDecisionService();
  }

  @Test
  @Deployment(resources = {"org/cibseven/bpm/engine/test/dmn/feel/legacy/literal-expression.dmn"})
  public void shouldEvaluateLiteralExpression() {
    // given

    // when
    String result = decisionService.evaluateDecisionByKey("c").evaluate()
        .getSingleEntry();

    // then
    assertThat(result).isEqualTo("foo");
  }

  @Test
  @Deployment(resources = {"org/cibseven/bpm/engine/test/dmn/feel/legacy/input-expression.dmn"})
  public void shouldEvaluateInputExpression() {
    // given

    // when
    String result = decisionService.evaluateDecisionByKey("c").evaluate()
        .getSingleEntry();

    // then
    assertThat(result).isEqualTo("foo");
  }

  @Test
  @Deployment(resources = {"org/cibseven/bpm/engine/test/dmn/feel/legacy/input-rule.dmn"})
  public void shouldEvaluateInputRule() {
    // given

    // when/then
    assertThatThrownBy(() -> decisionService.evaluateDecisionTableByKey("c",
        Variables.putValue("cellInput", 6)).getSingleEntry())
      .hasCauseInstanceOf(FeelSyntaxException.class)
      .satisfies(ex -> assertThat(ex)
    	      .hasFieldOrPropertyWithValue("cause.message", "FEEL-01010 Syntax error in expression 'for x in 1..3 return x * 2'"));
  }

  @Test
  @Deployment(resources = {"org/cibseven/bpm/engine/test/dmn/feel/legacy/output-rule.dmn"})
  public void shouldEvaluateOutputRule() {
    // given

    // when
    String result = decisionService.evaluateDecisionByKey("c").evaluate()
        .getSingleEntry();

    // then
    assertThat(result).isEqualTo("foo");
  }

}
