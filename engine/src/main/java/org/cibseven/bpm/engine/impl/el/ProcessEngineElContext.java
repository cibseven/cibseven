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
package org.cibseven.bpm.engine.impl.el;

import jakarta.el.FunctionMapper;
import jakarta.el.VariableMapper;
import jakarta.el.ELContext;
import jakarta.el.ELException;
import jakarta.el.ELResolver;
import jakarta.el.ExpressionFactory;


/**
 * {@link ELContext} used by the process engine.
 *
 * @author Joram Barrez
 * @author Daniel Meyer
 */
public class ProcessEngineElContext extends ELContext {

  protected ELResolver elResolver;

  protected FunctionMapper functionMapper;

  public ProcessEngineElContext(FunctionMapper functionMapper, ELResolver elResolver) {
    this(functionMapper);
    this.elResolver = elResolver;
  }


  public ProcessEngineElContext(FunctionMapper functionMapper) {
    this.functionMapper = functionMapper;
  }

  public ELResolver getELResolver() {
    return elResolver;
  }

  public FunctionMapper getFunctionMapper() {
    return functionMapper;
  }

  public VariableMapper getVariableMapper() {
    return null;
  }

  /**
   * Converts like the upstream {@code jakarta.el-api}: the resolvers first, then the
   * {@link ExpressionFactory} put into this context, which is JUEL's. Without this override, the EL
   * API of Tomcat, WildFly and JBoss EAP would skip the context factory and convert method
   * arguments with the server's EL implementation. For example, {@code null} passed to a
   * {@code Long} parameter would become {@code null} instead of {@code 0} (CIB7-2609).
   * Without a factory in the context, the EL API's own conversion applies.
   */
  @Override
  public <T> T convertToType(Object obj, Class<T> type) {
    Object factory = getContext(ExpressionFactory.class);
    if (!(factory instanceof ExpressionFactory)) {
      return super.convertToType(obj, type);
    }
    boolean propertyResolved = isPropertyResolved();
    try {
      setPropertyResolved(false);
      ELResolver resolver = getELResolver();
      if (resolver != null) {
        T result = resolver.convertToType(this, obj, type);
        if (isPropertyResolved()) {
          return result;
        }
      }
    } catch (ELException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new ELException(e);
    } finally {
      setPropertyResolved(propertyResolved);
    }
    return ((ExpressionFactory) factory).coerceToType(obj, type);
  }

}
