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

import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

import jakarta.el.ELContext;
import jakarta.el.ELResolver;

/**
 * Checks each property access, method call and result of a resolver chain against an
 * {@link ExpressionAccessPolicy}. Throws instead of declining, which a
 * {@link jakarta.el.CompositeELResolver} would skip.
 */
public class RestrictedELResolver extends ELResolver {

  protected final ELResolver delegate;
  protected final ExpressionAccessPolicy policy;

  public RestrictedELResolver(ELResolver delegate, ExpressionAccessPolicy policy) {
    this.delegate = delegate;
    this.policy = policy;
  }

  /** @return the unrestricted chain of a {@link RestrictedELResolver}, any other resolver itself */
  public static ELResolver unwrap(ELResolver resolver) {
    return resolver instanceof RestrictedELResolver ? ((RestrictedELResolver) resolver).getDelegate() : resolver;
  }

  public ELResolver getDelegate() {
    return delegate;
  }

  public ExpressionAccessPolicy getPolicy() {
    return policy;
  }

  @Override
  public Object getValue(ELContext context, Object base, Object property) {
    policy.checkAccess(base, property, false);
    Object value = delegate.getValue(context, base, property);
    if (context.isPropertyResolved()) {
      policy.checkResult(base, property, value);
    }
    return value;
  }

  @Override
  public Object invoke(ELContext context, Object base, Object method, Class<?>[] paramTypes, Object[] params) {
    policy.checkAccess(base, method, true);
    Object result = delegate.invoke(context, base, method, paramTypes, params);
    if (context.isPropertyResolved()) {
      policy.checkResult(null, null, result);
    }
    return result;
  }

  @Override
  public Class<?> getType(ELContext context, Object base, Object property) {
    policy.checkAccess(base, property, false);
    return delegate.getType(context, base, property);
  }

  @Override
  public void setValue(ELContext context, Object base, Object property, Object value) {
    policy.checkAccess(base, property, false);
    delegate.setValue(context, base, property, value);
  }

  @Override
  public boolean isReadOnly(ELContext context, Object base, Object property) {
    policy.checkAccess(base, property, false);
    return delegate.isReadOnly(context, base, property);
  }

  @Override
  public <T> T convertToType(ELContext context, Object obj, Class<T> type) {
    T result = delegate.convertToType(context, obj, type);
    if (context.isPropertyResolved()) {
      policy.checkResult(null, null, result);
    }
    return result;
  }

  @Override
  public Class<?> getCommonPropertyType(ELContext context, Object base) {
    return delegate.getCommonPropertyType(context, base);
  }

  // properties of maps, lists, arrays and resource bundles are data, not getters
  static boolean isBean(Object base) {
    return base != null
        && !(base instanceof Map)
        && !(base instanceof List)
        && !(base instanceof ResourceBundle)
        && !base.getClass().isArray();
  }
}
