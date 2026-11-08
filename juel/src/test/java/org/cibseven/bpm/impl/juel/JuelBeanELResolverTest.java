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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;

import jakarta.el.ELException;
import jakarta.el.ExpressionFactory;
import jakarta.el.MethodNotFoundException;
import jakarta.el.ValueExpression;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Method calls through {@link JuelBeanELResolver}, which selects the method and converts the
 * arguments as CIB seven 2.2 did on every runtime (CIB7-2609).
 */
public class JuelBeanELResolverTest {

  protected ExpressionFactory factory;
  protected SimpleContext context;

  @BeforeEach
  public void createContext() {
    factory = new ExpressionFactoryImpl();
    context = new SimpleContext(new SimpleResolver());
    context.putContext(ExpressionFactory.class, factory);
    setVariable("bean", new MyBean());
    setVariable("i1", new I1Impl());
    setVariable("i12", new I1AndI2Impl());
    setVariable("i12s", new I1AndI2ImplSub());
    setVariable("strings", new String[] { "x", "y" });
    setVariable("missing", null);
  }

  @Test
  public void shouldPreferTheNumberOverloadForCoercibleArguments() {
    assertThat(eval("${bean.coercible(12345678, 12345678)}")).isEqualTo("Integer, Integer");
  }

  @Test
  public void shouldSelectTheMostSpecificVarArgsMethod() {
    assertThat(eval("${bean.varArgs(i1)}")).isEqualTo("I1, I1AndI2Impl...");
    assertThat(eval("${bean.varArgs(i12)}")).isEqualTo("I1, I1AndI2Impl...");
    assertThat(eval("${bean.varArgs(i1, i12)}")).isEqualTo("I1, I1AndI2Impl...");
  }

  @Test
  public void shouldSelectTheMostSpecificMethodForSubclassArguments() {
    assertThat(eval("${bean.doubleArgs(i12s, i12s)}")).isEqualTo("I1AndI2Impl, I1AndI2Impl");
  }

  @Test
  public void shouldPassAnArrayToAVarArgsParameter() {
    assertThat(eval("${bean.join(strings)}")).isEqualTo("x, y");
  }

  @Test
  public void shouldPassSingleArgumentsToAVarArgsParameter() {
    assertThat(eval("${bean.join('a', 'b')}")).isEqualTo("a, b");
    assertThat(eval("${bean.join()}")).isEqualTo("");
  }

  @Test
  public void shouldConvertNullWithJuel() {
    assertThat(eval("${bean.describe(missing)}")).isEqualTo("0");
  }

  @Test
  public void shouldCallAPublicInterfaceMethodOfANonPublicClass() {
    setVariable("list", Collections.unmodifiableList(List.of("a", "b")));

    assertThat(eval("${list.size()}")).isEqualTo(2);
  }

  @Test
  public void shouldFailForAnUnknownMethod() {
    assertThatThrownBy(() -> eval("${bean.unknown(i1)}")).isInstanceOf(MethodNotFoundException.class);
  }

  @Test
  public void shouldFailForAnAmbiguousMethod() {
    assertThatThrownBy(() -> eval("${bean.ambiguous(i12, i12)}"))
        .isInstanceOf(MethodNotFoundException.class)
        .hasMessageContaining("unambiguous");
  }

  @Test
  public void shouldWrapTheExceptionOfTheMethod() {
    assertThatThrownBy(() -> eval("${bean.fail()}"))
        .isInstanceOf(ELException.class)
        .hasRootCauseMessage("bean failure");
  }

  @Test
  public void shouldNotResolveWithoutABase() {
    context.setPropertyResolved(false);

    assertThat(new JuelBeanELResolver().invoke(context, null, "size", null, new Object[0])).isNull();
    assertThat(context.isPropertyResolved()).isFalse();
  }

  protected void setVariable(String name, Object value) {
    context.setVariable(name, factory.createValueExpression(value, Object.class));
  }

  protected Object eval(String expression) {
    ValueExpression valueExpression = factory.createValueExpression(context, expression, Object.class);
    return valueExpression.getValue(context);
  }

  public static interface I1 {}
  public static interface I2 {}
  public static class I1Impl implements I1 {}
  public static class I1AndI2Impl implements I1, I2 {}
  public static class I1AndI2ImplSub extends I1AndI2Impl {}

  public static class MyBean {

    public String coercible(String s1, String s2) {
      return "String, String";
    }

    public String coercible(Integer i1, Integer i2) {
      return "Integer, Integer";
    }

    public String varArgs(I1 i1, I1... more) {
      return "I1, I1...";
    }

    public String varArgs(I1 i1, I1AndI2Impl... more) {
      return "I1, I1AndI2Impl...";
    }

    public String doubleArgs(I1 i1, I2 i2) {
      return "I1, I2";
    }

    public String doubleArgs(I1AndI2Impl i1, I1AndI2Impl i2) {
      return "I1AndI2Impl, I1AndI2Impl";
    }

    public String ambiguous(I1AndI2Impl i1, I2 i2) {
      return "I1AndI2Impl, I2";
    }

    public String ambiguous(I1 i1, I1AndI2Impl i2) {
      return "I1, I1AndI2Impl";
    }

    public String join(String... values) {
      return String.join(", ", values);
    }

    public String describe(Long value) {
      return String.valueOf(value);
    }

    public String fail() {
      throw new IllegalStateException("bean failure");
    }
  }

}
