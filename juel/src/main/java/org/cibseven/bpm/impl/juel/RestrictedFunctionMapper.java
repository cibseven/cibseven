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

import java.lang.reflect.Method;

import jakarta.el.FunctionMapper;

/**
 * Checks each EL function a {@link FunctionMapper} resolves against an {@link ExpressionAccessPolicy}:
 * its name ({@code lang:import}) and the type that declares its method. EL functions call their
 * method directly, so no {@link jakarta.el.ELResolver} sees them.
 */
public class RestrictedFunctionMapper extends FunctionMapper {

  protected final FunctionMapper delegate;
  protected final ExpressionAccessPolicy policy;

  public RestrictedFunctionMapper(FunctionMapper delegate, ExpressionAccessPolicy policy) {
    this.delegate = delegate;
    this.policy = policy;
  }

  /** @return the unrestricted mapper of a {@link RestrictedFunctionMapper}, any other mapper itself */
  public static FunctionMapper unwrap(FunctionMapper functionMapper) {
    return functionMapper instanceof RestrictedFunctionMapper
        ? ((RestrictedFunctionMapper) functionMapper).getDelegate()
        : functionMapper;
  }

  public FunctionMapper getDelegate() {
    return delegate;
  }

  public ExpressionAccessPolicy getPolicy() {
    return policy;
  }

  @Override
  public Method resolveFunction(String prefix, String localName) {
    Method method = delegate.resolveFunction(prefix, localName);
    if (method != null) {
      policy.checkFunction(prefix, localName, method);
    }
    return method;
  }

  @Override
  public void mapFunction(String prefix, String localName, Method method) {
    delegate.mapFunction(prefix, localName, method);
  }
}
