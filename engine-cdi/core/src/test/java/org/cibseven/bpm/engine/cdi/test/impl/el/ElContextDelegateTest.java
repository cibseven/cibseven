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
package org.cibseven.bpm.engine.cdi.test.impl.el;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.stream.Stream;

import jakarta.el.ELContext;
import jakarta.el.ELException;
import jakarta.el.ELResolver;
import jakarta.el.ExpressionFactory;

import org.cibseven.bpm.engine.cdi.impl.el.ElContextDelegate;
import org.cibseven.bpm.engine.impl.el.ProcessEngineElContext;
import org.cibseven.bpm.impl.juel.ExpressionFactoryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link ElContextDelegate#convertToType(Object, Class)}: the resolver of the wrapper (the CDI
 * bean manager's) first, then the wrapped engine context, which converts with JUEL (CIB7-2609).
 */
public class ElContextDelegateTest {

  static Stream<Arguments> nullConversions() {
    return Stream.of(
        Arguments.of(Long.class, 0L),
        Arguments.of(Boolean.class, false),
        Arguments.of(BigDecimal.class, BigDecimal.ZERO));
  }

  @Test
  public void shouldReturnTheResultOfTheWrapperResolver() {
    ConvertingResolver engineResolver = new ConvertingResolver().claiming(7L);
    ConvertingResolver cdiResolver = new ConvertingResolver().claiming(42L);
    ELContext context = new ElContextDelegate(createEngineContext(engineResolver), cdiResolver);

    assertThat(context.convertToType(null, Long.class)).isEqualTo(42L);
    assertThat(cdiResolver.calls).isEqualTo(1);
    assertThat(cdiResolver.resolvedOnEntry).isFalse();
    assertThat(engineResolver.calls).isZero();
  }

  @Test
  public void shouldReturnNullClaimedByTheWrapperResolver() {
    ELContext context = new ElContextDelegate(createEngineContext(new ConvertingResolver()),
        new ConvertingResolver().claiming(null));

    assertThat(context.convertToType(null, Long.class)).isNull();
  }

  @ParameterizedTest
  @MethodSource("nullConversions")
  public void shouldConvertNullWithTheWrappedContext(Class<?> type, Object expected) {
    ConvertingResolver cdiResolver = new ConvertingResolver();
    ELContext context = new ElContextDelegate(createEngineContext(new ConvertingResolver()), cdiResolver);

    assertThat(context.convertToType(null, type)).isEqualTo(expected);
    assertThat(cdiResolver.calls).isEqualTo(1);
  }

  @Test
  public void shouldAskTheResolverOfTheWrappedContextAfterTheWrapperResolver() {
    ConvertingResolver engineResolver = new ConvertingResolver().claiming(7L);
    ConvertingResolver cdiResolver = new ConvertingResolver();
    ELContext context = new ElContextDelegate(createEngineContext(engineResolver), cdiResolver);

    assertThat(context.convertToType(null, Long.class)).isEqualTo(7L);
    assertThat(cdiResolver.calls).isEqualTo(1);
    assertThat(engineResolver.calls).isEqualTo(1);
  }

  @Test
  public void shouldConvertWithTheWrappedContextWithoutAWrapperResolver() {
    ELContext context = new ElContextDelegate(createEngineContext(new ConvertingResolver()), null);

    assertThat(context.convertToType(null, Long.class)).isEqualTo(0L);
  }

  @ParameterizedTest
  @ValueSource(booleans = { true, false })
  public void shouldRestorePropertyResolvedAfterTheWrapperResolverClaimedTheConversion(boolean resolved) {
    ProcessEngineElContext engineContext = createEngineContext(new ConvertingResolver());
    ELContext context = new ElContextDelegate(engineContext, new ConvertingResolver().claiming(42L));
    context.setPropertyResolved(resolved);

    context.convertToType("1", Long.class);

    assertThat(context.isPropertyResolved()).isEqualTo(resolved);
    assertThat(engineContext.isPropertyResolved()).isEqualTo(resolved);
  }

  @ParameterizedTest
  @ValueSource(booleans = { true, false })
  public void shouldRestorePropertyResolvedAfterConvertingWithTheWrappedContext(boolean resolved) {
    ProcessEngineElContext engineContext = createEngineContext(new ConvertingResolver());
    ELContext context = new ElContextDelegate(engineContext, new ConvertingResolver());
    context.setPropertyResolved(resolved);

    assertThat(context.convertToType(null, Long.class)).isEqualTo(0L);

    assertThat(engineContext.isPropertyResolved()).isEqualTo(resolved);
  }

  @ParameterizedTest
  @ValueSource(booleans = { true, false })
  public void shouldWrapAWrapperResolverFailureAndRestorePropertyResolved(boolean resolved) {
    IllegalStateException failure = new IllegalStateException("resolver failure");
    ProcessEngineElContext engineContext = createEngineContext(new ConvertingResolver());
    ELContext context = new ElContextDelegate(engineContext, new ConvertingResolver().failingWith(failure));
    context.setPropertyResolved(resolved);

    assertThatThrownBy(() -> context.convertToType(null, Long.class))
        .isInstanceOf(ELException.class)
        .hasCause(failure);
    assertThat(engineContext.isPropertyResolved()).isEqualTo(resolved);
  }

  @ParameterizedTest
  @ValueSource(booleans = { true, false })
  public void shouldRethrowAnELExceptionOfTheWrapperResolverAndRestorePropertyResolved(boolean resolved) {
    ELException failure = new ELException("resolver failure");
    ProcessEngineElContext engineContext = createEngineContext(new ConvertingResolver());
    ELContext context = new ElContextDelegate(engineContext, new ConvertingResolver().failingWith(failure));
    context.setPropertyResolved(resolved);

    assertThatThrownBy(() -> context.convertToType(null, Long.class)).isSameAs(failure);
    assertThat(engineContext.isPropertyResolved()).isEqualTo(resolved);
  }

  protected static ProcessEngineElContext createEngineContext(ELResolver resolver) {
    ProcessEngineElContext context = new ProcessEngineElContext(null, resolver);
    context.putContext(ExpressionFactory.class, new ExpressionFactoryImpl());
    return context;
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
