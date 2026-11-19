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
package org.cibseven.bpm.dmn.engine.el;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.cibseven.bpm.dmn.engine.DmnEngineConfiguration;
import org.cibseven.bpm.dmn.engine.impl.DefaultDmnEngineConfiguration;
import org.cibseven.bpm.dmn.engine.impl.el.JuelElContextFactory;
import org.cibseven.bpm.dmn.engine.impl.el.JuelElProvider;
import org.cibseven.bpm.dmn.engine.impl.el.VariableContextElResolver;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.context.VariableContext;
import org.cibseven.bpm.impl.juel.ExpressionAccessDeniedException;
import org.cibseven.bpm.impl.juel.ExpressionAccessPolicy;
import org.cibseven.bpm.impl.juel.ExpressionFactoryImpl;
import org.cibseven.bpm.impl.juel.JuelBeanELResolver;
import org.cibseven.bpm.impl.juel.ExpressionHardeningMode;
import org.junit.jupiter.api.Test;

import jakarta.el.CompositeELResolver;

/**
 * The deny list for DMN JUEL expressions of a standalone DMN engine (CIB7-2652).
 */
public class JuelElProviderAccessPolicyTest {

  protected final VariableContext variables = Variables.createVariables().putValue("name", "abc").asVariableContext();

  @Test
  public void shouldDenyClassAccessByDefault() {
    JuelElProvider provider = new JuelElProvider();

    assertThatThrownBy(() -> provider.createExpression("${name.getClass().simpleName}").getValue(variables))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'getClass'");
    assertThatThrownBy(() -> provider.createExpression("${name.class}").getValue(variables))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'class'");
  }

  @Test
  public void shouldDenyClassAccessWithCustomElContextFactory() {
    ExpressionFactoryImpl factory = new ExpressionFactoryImpl();
    // both constructors without a policy restrict the resolver with the default deny list
    for (JuelElContextFactory elContextFactory : List.of(
        new JuelElContextFactory(customResolver()),
        new JuelElContextFactory(customResolver(), factory))) {
      JuelElProvider provider = new JuelElProvider(factory, elContextFactory);

      assertThatThrownBy(() -> provider.createExpression("${name.getClass().simpleName}").getValue(variables))
          .isInstanceOf(ExpressionAccessDeniedException.class)
          .hasMessageContaining("entry 'getClass'");
      assertThat(provider.createExpression("${name.toUpperCase()}").getValue(variables)).isEqualTo("ABC");
    }
  }

  @Test
  public void shouldKeepOrdinaryExpressions() {
    JuelElProvider provider = new JuelElProvider();

    assertThat(provider.createExpression("${name.toUpperCase()}").getValue(variables)).isEqualTo("ABC");
  }

  @Test
  public void shouldApplyConfiguredPolicy() {
    JuelElProvider provider = new JuelElProvider(ExpressionAccessPolicy.create("-getClass,-java.lang.Class", ExpressionHardeningMode.ENFORCE));

    assertThat(provider.createExpression("${name.getClass().simpleName}").getValue(variables)).isEqualTo("String");
  }

  @Test
  public void shouldReportAndEvaluateInReportMode() {
    List<String> reports = new ArrayList<>();
    JuelElProvider provider = new JuelElProvider(
        ExpressionAccessPolicy.create(null, ExpressionHardeningMode.REPORT).withReporter(reports::add));

    assertThat(provider.createExpression("${name.getClass().simpleName}").getValue(variables)).isEqualTo("String");
    // the getter, its Class result and the access on that Class
    assertThat(reports).hasSize(3)
        .anyMatch(report -> report.contains("entry 'getClass'"))
        .anyMatch(report -> report.contains("entry 'java.lang.Class'"));
  }

  @Test
  public void shouldUseDefaultPolicyInDmnEngineConfiguration() {
    DefaultDmnEngineConfiguration configuration = (DefaultDmnEngineConfiguration) DmnEngineConfiguration
        .createDefaultDmnEngineConfiguration();
    configuration.buildEngine();

    assertThat(configuration.getExpressionAccessPolicy()).isSameAs(ExpressionAccessPolicy.DEFAULT);
    assertThatThrownBy(() -> configuration.getElProvider().createExpression("${name.getClass()}").getValue(variables))
        .isInstanceOf(ExpressionAccessDeniedException.class);
  }

  @Test
  public void shouldUseConfiguredPolicyInDmnEngineConfiguration() {
    DefaultDmnEngineConfiguration configuration = (DefaultDmnEngineConfiguration) DmnEngineConfiguration
        .createDefaultDmnEngineConfiguration();
    configuration.expressionAccessPolicy(ExpressionAccessPolicy.create(null, ExpressionHardeningMode.OFF));
    configuration.buildEngine();

    assertThat(configuration.getElProvider().createExpression("${name.getClass().simpleName}").getValue(variables))
        .isEqualTo("String");
  }

  protected static CompositeELResolver customResolver() {
    CompositeELResolver resolver = new CompositeELResolver();
    resolver.add(new VariableContextElResolver());
    resolver.add(new JuelBeanELResolver());
    return resolver;
  }
}
