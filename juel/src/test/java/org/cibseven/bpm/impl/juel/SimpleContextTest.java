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

import java.math.BigDecimal;
import java.util.stream.Stream;

import jakarta.el.ELContext;
import jakarta.el.ELException;
import jakarta.el.ELResolver;
import jakarta.el.ExpressionFactory;
import jakarta.el.FunctionMapper;
import jakarta.el.VariableMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link SimpleContext#convertToType(Object, Class)}: the resolvers first, then the
 * {@link ExpressionFactory} in the context (CIB7-2609). The test class path has the upstream
 * {@code jakarta.el-api}, which converts the same way, so these tests describe the behaviour but do
 * not tell the override from the inherited method. {@code ConvertToTypeOnTomcatElApiTest} in
 * {@code cibseven-engine} runs the conversion on Tomcat's EL API, where it does.
 */
public class SimpleContextTest {

  static Stream<Arguments> nullConversions() {
    return Stream.of(
        Arguments.of(Long.class, 0L),
        Arguments.of(Boolean.class, false),
        Arguments.of(BigDecimal.class, BigDecimal.ZERO));
  }

  @ParameterizedTest
  @MethodSource("nullConversions")
  public void shouldConvertNullWithJuel(Class<?> type, Object expected) {
    ELContext context = createContext(new ConvertingResolver());

    assertThat(context.convertToType(null, type)).isEqualTo(expected);
  }

  @ParameterizedTest
  @MethodSource("nullConversions")
  public void shouldConvertNullWithJuelAndTheDefaultResolver(Class<?> type, Object expected) {
    SimpleContext context = new SimpleContext();
    context.putContext(ExpressionFactory.class, new ExpressionFactoryImpl());

    assertThat(context.convertToType(null, type)).isEqualTo(expected);
  }

  @Test
  public void shouldReturnTheResultOfAResolverThatClaimsTheConversion() {
    ConvertingResolver resolver = new ConvertingResolver().claiming(42L);
    ELContext context = createContext(resolver);

    assertThat(context.convertToType(null, Long.class)).isEqualTo(42L);
    assertThat(resolver.calls).isEqualTo(1);
    assertThat(resolver.resolvedOnEntry).isFalse();
  }

  @Test
  public void shouldReturnNullClaimedByAResolver() {
    ELContext context = createContext(new ConvertingResolver().claiming(null));

    assertThat(context.convertToType(null, Long.class)).isNull();
  }

  @ParameterizedTest
  @ValueSource(booleans = { true, false })
  public void shouldRestorePropertyResolvedAfterAResolverClaimedTheConversion(boolean resolved) {
    ELContext context = createContext(new ConvertingResolver().claiming(42L));
    context.setPropertyResolved(resolved);

    context.convertToType("1", Long.class);

    assertThat(context.isPropertyResolved()).isEqualTo(resolved);
  }

  @ParameterizedTest
  @ValueSource(booleans = { true, false })
  public void shouldRestorePropertyResolvedAfterConvertingWithTheFactory(boolean resolved) {
    ConvertingResolver resolver = new ConvertingResolver();
    ELContext context = createContext(resolver);
    context.setPropertyResolved(resolved);

    assertThat(context.convertToType(null, Long.class)).isEqualTo(0L);

    assertThat(context.isPropertyResolved()).isEqualTo(resolved);
    assertThat(resolver.resolvedOnEntry).isFalse();
  }

  @ParameterizedTest
  @ValueSource(booleans = { true, false })
  public void shouldWrapAResolverFailureAndRestorePropertyResolved(boolean resolved) {
    IllegalStateException failure = new IllegalStateException("resolver failure");
    ELContext context = createContext(new ConvertingResolver().failingWith(failure));
    context.setPropertyResolved(resolved);

    assertThatThrownBy(() -> context.convertToType(null, Long.class))
        .isInstanceOf(ELException.class)
        .hasCause(failure);
    assertThat(context.isPropertyResolved()).isEqualTo(resolved);
  }

  @ParameterizedTest
  @ValueSource(booleans = { true, false })
  public void shouldRethrowAnELExceptionOfAResolverAndRestorePropertyResolved(boolean resolved) {
    ELException failure = new ELException("resolver failure");
    ELContext context = createContext(new ConvertingResolver().failingWith(failure));
    context.setPropertyResolved(resolved);

    assertThatThrownBy(() -> context.convertToType(null, Long.class)).isSameAs(failure);
    assertThat(context.isPropertyResolved()).isEqualTo(resolved);
  }

  @ParameterizedTest
  @ValueSource(classes = { Long.class, Boolean.class, BigDecimal.class })
  public void shouldConvertLikeTheElApiWithoutAFactoryInTheContext(Class<?> type) {
    ELContext context = new SimpleContext(new ConvertingResolver());
    ELContext plainContext = new PlainContext(new ConvertingResolver());

    Object expected = outcome(plainContext, type);

    assertThat(outcome(context, type)).isEqualTo(expected);
  }

  @Test
  public void shouldAskAResolverWithoutAFactoryInTheContext() {
    ELContext context = new SimpleContext(new ConvertingResolver().claiming(42L));

    assertThat(context.convertToType(null, Long.class)).isEqualTo(42L);
  }

  protected static SimpleContext createContext(ELResolver resolver) {
    SimpleContext context = new SimpleContext(resolver);
    context.putContext(ExpressionFactory.class, new ExpressionFactoryImpl());
    return context;
  }

  /**
   * @return the converted value, or the class of the exception the conversion threw
   */
  protected static Object outcome(ELContext context, Class<?> type) {
    try {
      return context.convertToType(null, type);
    } catch (RuntimeException e) {
      return e.getClass();
    }
  }

  /**
   * An {@link ELContext} that keeps the conversion of the EL API.
   */
  protected static class PlainContext extends ELContext {

    protected final ELResolver resolver;

    public PlainContext(ELResolver resolver) {
      this.resolver = resolver;
    }

    @Override
    public ELResolver getELResolver() {
      return resolver;
    }

    @Override
    public FunctionMapper getFunctionMapper() {
      return null;
    }

    @Override
    public VariableMapper getVariableMapper() {
      return null;
    }
  }

  /**
   * Resolves nothing. Claims {@link #convertToType(ELContext, Object, Class)} only when told to.
   */
  protected static class ConvertingResolver extends ELResolver {

    protected boolean claim;
    protected Object result;
    protected RuntimeException failure;
    protected int calls;
    protected boolean resolvedOnEntry;

    public ConvertingResolver claiming(Object result) {
      this.claim = true;
      this.result = result;
      return this;
    }

    public ConvertingResolver failingWith(RuntimeException failure) {
      this.failure = failure;
      return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T convertToType(ELContext context, Object obj, Class<T> type) {
      calls++;
      resolvedOnEntry = context.isPropertyResolved();
      if (failure != null) {
        throw failure;
      }
      if (claim) {
        context.setPropertyResolved(true);
        return (T) result;
      }
      return null;
    }

    @Override
    public Object getValue(ELContext context, Object base, Object property) {
      return null;
    }

    @Override
    public Class<?> getType(ELContext context, Object base, Object property) {
      return null;
    }

    @Override
    public void setValue(ELContext context, Object base, Object property, Object value) {
    }

    @Override
    public boolean isReadOnly(ELContext context, Object base, Object property) {
      return true;
    }

    @Override
    public Class<?> getCommonPropertyType(ELContext context, Object base) {
      return null;
    }
  }

}
