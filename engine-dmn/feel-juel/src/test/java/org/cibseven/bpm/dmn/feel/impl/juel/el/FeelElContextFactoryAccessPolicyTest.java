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
package org.cibseven.bpm.dmn.feel.impl.juel.el;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.el.ELContext;

import org.cibseven.bpm.dmn.feel.impl.FeelEngine;
import org.cibseven.bpm.dmn.feel.impl.custom.StartsWithFunctionTransformer;
import org.cibseven.bpm.dmn.feel.impl.juel.FeelEngineFactoryImpl;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.context.VariableContext;
import org.cibseven.bpm.impl.juel.ExpressionAccessDeniedException;
import org.cibseven.bpm.impl.juel.ExpressionAccessPolicy;
import org.cibseven.bpm.impl.juel.ExpressionFactoryImpl;
import org.cibseven.bpm.impl.juel.ExpressionHardeningMode;
import org.junit.jupiter.api.Test;

/**
 * The deny list for the legacy JUEL-based FEEL engine (CIB7-2652).
 */
public class FeelElContextFactoryAccessPolicyTest {

  protected final ExpressionFactoryImpl expressionFactory = new ExpressionFactoryImpl();
  protected final VariableContext variables = Variables.createVariables().putValue("input", "foobar").asVariableContext();

  @Test
  public void shouldDenyClassAccessByDefault() {
    FeelElContextFactory factory = new FeelElContextFactory();

    assertThatThrownBy(() -> evaluate(factory, "${input.getClass().simpleName}"))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'getClass'");
  }

  @Test
  public void shouldApplyConfiguredPolicy() {
    FeelElContextFactory factory = new FeelElContextFactory(ExpressionAccessPolicy.create("-getClass,-java.lang.Class", ExpressionHardeningMode.ENFORCE));

    assertThat(evaluate(factory, "${input.getClass().simpleName}")).isEqualTo("String");
  }

  @Test
  public void shouldReportAndEvaluateInReportMode() {
    List<String> reports = new ArrayList<>();
    FeelElContextFactory factory = new FeelElContextFactory(
        ExpressionAccessPolicy.create(null, ExpressionHardeningMode.REPORT).withReporter(reports::add));

    assertThat(evaluate(factory, "${input.getClass().simpleName}")).isEqualTo("String");
    // the getter, its Class result and the access on that Class
    assertThat(reports).hasSize(3)
        .anyMatch(report -> report.contains("entry 'getClass'"))
        .anyMatch(report -> report.contains("entry 'java.lang.Class'"));
  }

  @Test
  public void shouldDenyCustomFunctionByDeclaringType() {
    StartsWithFunctionTransformer transformer = new StartsWithFunctionTransformer();
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create(
        transformer.getMethod().getDeclaringClass().getName(), ExpressionHardeningMode.ENFORCE);
    FeelEngine feelEngine = new FeelEngineFactoryImpl(FeelEngineFactoryImpl.DEFAULT_EXPRESSION_CACHE_SIZE,
        Collections.singletonList(transformer), policy).createInstance();

    assertThatThrownBy(() -> feelEngine.evaluateSimpleUnaryTests("starts with(\"foo\")", "input", variables))
        .hasRootCauseInstanceOf(ExpressionAccessDeniedException.class);
  }

  @Test
  public void shouldKeepFeelExpressions() {
    FeelEngine feelEngine = new FeelEngineFactoryImpl().createInstance();

    assertThat(feelEngine.evaluateSimpleUnaryTests("\"foobar\"", "input", variables)).isTrue();
    assertThat(feelEngine.evaluateSimpleUnaryTests("not(\"foo\")", "input", variables)).isTrue();
  }

  protected Object evaluate(FeelElContextFactory factory, String expression) {
    ELContext context = factory.createContext(expressionFactory, variables);
    return expressionFactory.createValueExpression(context, expression, Object.class).getValue(context);
  }
}
