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

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.delegate.BaseDelegateExecution;
import org.cibseven.bpm.engine.delegate.VariableScope;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.delegate.ExpressionGetInvocation;
import org.cibseven.bpm.engine.impl.delegate.ExpressionSetInvocation;
import org.cibseven.bpm.impl.juel.jakarta.el.ELContext;
import org.cibseven.bpm.impl.juel.jakarta.el.ELException;
import org.cibseven.bpm.impl.juel.jakarta.el.MethodNotFoundException;
import org.cibseven.bpm.impl.juel.jakarta.el.PropertyNotFoundException;
import org.cibseven.bpm.impl.juel.jakarta.el.ValueExpression;


/**
 * Expression implementation backed by a JUEL {@link ValueExpression}.
 *
 * @author Frederik Heremans
 * @author Joram Barrez
 */
public class JuelExpression implements Expression {

  protected String expressionText;
  protected ValueExpression valueExpression;
  protected JuelExpressionManager expressionManager;

  public JuelExpression(ValueExpression valueExpression, JuelExpressionManager expressionManager, String expressionText) {
    this.valueExpression = valueExpression;
    this.expressionManager = expressionManager;
    this.expressionText = expressionText;
  }

  public Object getValue(VariableScope variableScope) {
    return getValue(variableScope, null);
  }

  public Object getValue(VariableScope variableScope, BaseDelegateExecution contextExecution) {
    ELContext elContext = expressionManager.getElContext(variableScope);
    try {
      ExpressionGetInvocation invocation = new ExpressionGetInvocation(valueExpression, elContext, contextExecution);
      Context.getProcessEngineConfiguration()
        .getDelegateInterceptor()
        .handleInvocation(invocation);
      return invocation.getInvocationResult();
    } catch (PropertyNotFoundException pnfe) {
      throw new ProcessEngineException("Unknown property used in expression: " + expressionText+". Cause: "+pnfe.getMessage(), pnfe);
    } catch (MethodNotFoundException mnfe) {
      throw new ProcessEngineException("Unknown method used in expression: " + expressionText+". Cause: "+mnfe.getMessage(), mnfe);
    } catch(ELException ele) {
      Throwable cause = ele.getCause();
      if (cause != null) {
        throw new ProcessEngineException(cause);

      } else {
        throw new ProcessEngineException("Error while evaluating expression: " + expressionText + ". Cause: " + ele.getMessage(), ele);

      }
    } catch (Exception e) {
      throw new ProcessEngineException("Error while evaluating expression: " + expressionText+". Cause: "+e.getMessage(), e);
    }
  }

  public void setValue(Object value, VariableScope variableScope) {
    setValue(value, variableScope, null);
  }

  public void setValue(Object value, VariableScope variableScope, BaseDelegateExecution contextExecution) {
    ELContext elContext = expressionManager.getElContext(variableScope);
    try {
      ExpressionSetInvocation invocation = new ExpressionSetInvocation(valueExpression, elContext, value, contextExecution);
      Context.getProcessEngineConfiguration()
        .getDelegateInterceptor()
        .handleInvocation(invocation);
    } catch (Exception e) {
      throw new ProcessEngineException("Error while evaluating expression: " + expressionText+". Cause: "+e.getMessage(), e);
    }
  }

  @Override
  public String toString() {
    if(valueExpression != null) {
      return valueExpression.getExpressionString();
    }
    return super.toString();
  }

  @Override
  public boolean isLiteralText() {
    return valueExpression.isLiteralText();
  }

  public String getExpressionText() {
    return expressionText;
  }
}
