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
import static org.cibseven.bpm.engine.variable.Variables.createVariables;
import static org.cibseven.bpm.engine.variable.Variables.emptyVariableContext;

import jakarta.el.ELContext;
import jakarta.el.ExpressionFactory;

import org.cibseven.bpm.dmn.engine.impl.el.JuelElProvider;
import org.cibseven.bpm.dmn.engine.impl.spi.el.ElExpression;
import org.junit.jupiter.api.Test;

/**
 * JUEL in standalone DMN must not depend on an EL implementation that the application
 * happens to bring (CIB7-2609).
 */
public class JuelElProviderTest {

  @Test
  public void shouldPutTheJuelFactoryIntoTheContext() {
    JuelElProvider provider = new JuelElProvider();

    ELContext context = provider.getElContextFactory().createElContext(emptyVariableContext());

    assertThat(context.getContext(ExpressionFactory.class)).isSameAs(provider.getFactory());
  }

  @Test
  public void shouldCallMethodThatNeedsArgumentConversion() {
    // the literal 1 is a Long, String#substring takes an int
    ElExpression expression = new JuelElProvider().createExpression("${name.substring(1)}");

    Object value = expression.getValue(createVariables().putValue("name", "Abc").asVariableContext());

    assertThat(value).isEqualTo("bc");
  }

  @Test
  public void shouldConvertNullMethodArgumentWithJuel() {
    ElExpression expression = new JuelElProvider().createExpression("${bean.describe(missing)}");

    Object value = expression.getValue(createVariables()
        .putValue("bean", new ArgumentBean())
        .putValue("missing", null)
        .asVariableContext());

    // JUEL converts null to 0 for a Long parameter, as CIB seven 2.2 did; EL 3+ would pass null
    assertThat(value).isEqualTo("0");
  }

  @Test
  public void shouldFindAnElImplementation() {
    // jakarta.el.BeanELResolver asks the global jakarta.el.ELManager whether an argument can be
    // converted, so cibseven-engine-dmn brings org.glassfish.expressly:expressly at runtime. The test
    // class path contains test-scoped dependencies too, so this test cannot tell runtime from test
    // scope; the engine test suite (JuelScriptEngineTest, ExpressionManagerTest) does.
    assertThat(ExpressionFactory.newInstance()).isNotNull();
  }

  public static class ArgumentBean {
    public String describe(Long value) {
      return String.valueOf(value);
    }
  }

}
