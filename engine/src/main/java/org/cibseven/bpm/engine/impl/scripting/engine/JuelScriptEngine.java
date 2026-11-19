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
package org.cibseven.bpm.engine.impl.scripting.engine;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import javax.script.AbstractScriptEngine;
import javax.script.Bindings;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.delegate.VariableScope;
import org.cibseven.bpm.engine.impl.ProcessEngineLogger;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.el.ExpressionFactoryResolver;
import org.cibseven.bpm.engine.impl.el.ExpressionManager;
import org.cibseven.bpm.engine.impl.el.JuelExpressionManager;
import org.cibseven.bpm.engine.impl.el.ProcessEngineElContext;
import org.cibseven.bpm.engine.impl.scripting.ScriptLogger;
import org.cibseven.bpm.impl.juel.JuelBeanELResolver;
import org.cibseven.bpm.impl.juel.SimpleResolver;

import jakarta.el.ArrayELResolver;
import jakarta.el.CompositeELResolver;
import jakarta.el.ELException;
import jakarta.el.ELResolver;
import jakarta.el.ELContext;
import jakarta.el.ExpressionFactory;
import jakarta.el.ListELResolver;
import jakarta.el.MapELResolver;
import jakarta.el.ResourceBundleELResolver;
import jakarta.el.ValueExpression;
import jakarta.el.VariableMapper;
import jakarta.el.FunctionMapper;
import org.cibseven.bpm.engine.impl.util.ReflectUtil;


/**
 * ScriptEngine that used JUEL for script evaluation and compilation (JSR-223).
 *
 * Uses EL 1.1 if available, to resolve expressions. Otherwise it reverts to EL
 * 1.0, using {@link ExpressionFactoryResolver}.
 *
 * @author Frederik Heremans
 */
public class JuelScriptEngine extends AbstractScriptEngine {

  private static final ScriptLogger LOG = ProcessEngineLogger.SCRIPT_LOGGER;

  // warn once per expression manager
  private static final Map<ExpressionManager, Boolean> WARNED_EXPRESSION_MANAGERS =
      Collections.synchronizedMap(new WeakHashMap<>());

  private ScriptEngineFactory scriptEngineFactory;
  private ExpressionFactory expressionFactory;

  public JuelScriptEngine(ScriptEngineFactory scriptEngineFactory) {
    this.scriptEngineFactory = scriptEngineFactory;
    // Resolve the ExpressionFactory
    expressionFactory = ExpressionFactoryResolver.resolveExpressionFactory();
  }

  public JuelScriptEngine() {
    this(null);
    SimpleResolver x = new SimpleResolver(false);
    x.hashCode();
  }

  public Object eval(String script, ScriptContext scriptContext) throws ScriptException {
    ValueExpression expr = parse(script, scriptContext);
    return evaluateExpression(expr, scriptContext);
  }

  public Object eval(Reader reader, ScriptContext scriptContext) throws ScriptException {
    return eval(readFully(reader), scriptContext);
  }

  public ScriptEngineFactory getFactory() {
    synchronized (this) {
      if (scriptEngineFactory == null) {
        scriptEngineFactory = new JuelScriptEngineFactory();
      }
    }
    return scriptEngineFactory;
  }

  public Bindings createBindings() {
    return new SimpleBindings();
  }

  private Object evaluateExpression(ValueExpression expr, ScriptContext ctx) throws ScriptException {
    try {
      return expr.getValue(createElContext(ctx));
    } catch (ELException elexp) {
      throw new ScriptException(elexp);
    }
  }

  /** Like attribute expressions, plus the variable scope key (e.g. {@code connector}). */
  private ELResolver createEngineElResolver(ELResolver expressionManagerResolver) {
    CompositeELResolver compositeResolver = new CompositeELResolver();
    compositeResolver.add(new ResourceBundleELResolver());
    compositeResolver.add(expressionManagerResolver);
    compositeResolver.add(new VariableScopeKeyElResolver());
    return compositeResolver;
  }

  private ELResolver createDefaultElResolver() {
    CompositeELResolver compositeResolver = new CompositeELResolver();
    compositeResolver.add(new ArrayELResolver());
    compositeResolver.add(new ListELResolver());
    compositeResolver.add(new MapELResolver());
    compositeResolver.add(new ResourceBundleELResolver());
    compositeResolver.add(new JuelBeanELResolver());
    return new SimpleResolver(compositeResolver);
  }

  private ELResolver getExpressionManagerResolver() {
    ProcessEngineConfigurationImpl configuration = Context.getProcessEngineConfiguration();
    if (configuration == null) {
      // no command context
      return null;
    }
    ExpressionManager expressionManager = configuration.getExpressionManager();
    if (expressionManager instanceof JuelExpressionManager) {
      JuelExpressionManager juelExpressionManager = (JuelExpressionManager) expressionManager;
      return juelExpressionManager.isAppliedToJuelScripts() ? juelExpressionManager.getElResolver() : null;
    }
    if (expressionManager != null && WARNED_EXPRESSION_MANAGERS.putIfAbsent(expressionManager, Boolean.TRUE) == null) {
      LOG.warnJuelScriptWithoutExpressionManagerResolver(expressionManager.getClass().getName());
    }
    return null;
  }

  private String readFully(Reader reader) throws ScriptException {
    char[] array = new char[8192];
    StringBuilder strBuffer = new StringBuilder();
    int count;
    try {
      while ((count = reader.read(array, 0, array.length)) > 0) {
        strBuffer.append(array, 0, count);
      }
    } catch (IOException exp) {
      throw new ScriptException(exp);
    }
    return strBuffer.toString();
  }

  private ValueExpression parse(String script, ScriptContext scriptContext) throws ScriptException {
    try {
      return expressionFactory.createValueExpression(createElContext(scriptContext), script, Object.class);
    } catch (ELException ele) {
      throw new ScriptException(ele);
    }
  }

  private ELContext createElContext(final ScriptContext scriptCtx) {
    // Check if the ELContext is already stored on the ScriptContext
    Object existingELCtx = scriptCtx.getAttribute("elcontext");
    if (existingELCtx instanceof ELContext) {
      return (ELContext) existingELCtx;
    }

    scriptCtx.setAttribute("context", scriptCtx, ScriptContext.ENGINE_SCOPE);

    // Built-in function are added to ScriptCtx
    scriptCtx.setAttribute("out:print", getPrintMethod(), ScriptContext.ENGINE_SCOPE);

    SecurityManager securityManager = System.getSecurityManager();
    if (securityManager == null) {
      scriptCtx.setAttribute("lang:import", getImportMethod(), ScriptContext.ENGINE_SCOPE);
    }

    ELResolver expressionManagerResolver = getExpressionManagerResolver();
    Bindings engineBindings = scriptCtx.getBindings(ScriptContext.ENGINE_SCOPE);
    // in the engine, resolve names through the expression manager's chain
    final boolean useExpressionManager = expressionManagerResolver != null && engineBindings instanceof ScriptBindings;
    if (expressionManagerResolver != null && !useExpressionManager) {
      ExpressionManager expressionManager = Context.getProcessEngineConfiguration().getExpressionManager();
      if (WARNED_EXPRESSION_MANAGERS.putIfAbsent(expressionManager, Boolean.TRUE) == null) {
        LOG.warnJuelScriptWithoutScriptBindings(expressionManager.getClass().getName());
      }
    }
    final ELResolver elResolver = useExpressionManager
        ? createEngineElResolver(expressionManagerResolver)
        : createDefaultElResolver();

    // ProcessEngineElContext converts with the factory put into the context, i.e. with JUEL, on
    // every EL API, not with the server's EL implementation (CIB7-2609)
    ELContext elContext = new ProcessEngineElContext(new ScriptContextFunctionMapper(scriptCtx), elResolver) {

      final VariableMapper varMapper = new ScriptContextVariableMapper(scriptCtx, !useExpressionManager);

      @Override
      public VariableMapper getVariableMapper() {
        return varMapper;
      }
    };
    elContext.putContext(ExpressionFactory.class, expressionFactory);
    if (useExpressionManager) {
      VariableScope variableScope = ((ScriptBindings) engineBindings).getVariableScope();
      if (variableScope != null) {
        elContext.putContext(VariableScope.class, variableScope);
      }
    }
    // Store the elcontext in the scriptContext to be able to reuse
    scriptCtx.setAttribute("elcontext", elContext, ScriptContext.ENGINE_SCOPE);
    return elContext;
  }

  private static Method getPrintMethod() {
    try {
      return JuelScriptEngine.class.getMethod("print", new Class[] { Object.class });
    } catch (Exception exp) {
      // Will never occur
      return null;
    }
  }

  private static Method getImportMethod() {
    try {
      return JuelScriptEngine.class.getMethod("importFunctions", new Class[] { ScriptContext.class, String.class, Object.class });
    } catch (Exception exp) {
      // Will never occur
      return null;
    }
  }

  public static void importFunctions(ScriptContext ctx, String namespace, Object obj) {
    Class< ? > clazz = null;
    if (obj instanceof Class) {
      clazz = (Class< ? >) obj;
    } else if (obj instanceof String) {
      try {
        clazz = ReflectUtil.loadClass((String) obj);
      } catch (ProcessEngineException ae) {
        throw new ELException(ae);
      }
    } else {
      throw new ELException("Class or class name is missing");
    }
    Method[] methods = clazz.getMethods();
    for (Method m : methods) {
      int mod = m.getModifiers();
      if (Modifier.isStatic(mod) && Modifier.isPublic(mod)) {
        String name = namespace + ":" + m.getName();
        ctx.setAttribute(name, m, ScriptContext.ENGINE_SCOPE);
      }
    }
  }

  /**
   * Class representing a compiled script using JUEL.
   *
   * @author Frederik Heremans
   */
  private class JuelCompiledScript extends CompiledScript {

    private ValueExpression valueExpression;

    JuelCompiledScript(ValueExpression valueExpression) {
      this.valueExpression = valueExpression;
    }

    public ScriptEngine getEngine() {
      // Return outer class instance
      return JuelScriptEngine.this;
    }

    public Object eval(ScriptContext ctx) throws ScriptException {
      return evaluateExpression(valueExpression, ctx);
    }
  }

  /**
   * ValueMapper that uses the ScriptContext to get variable values or value
   * expressions.
   *
   * @author Frederik Heremans
   */
  private class ScriptContextVariableMapper extends VariableMapper {

    private ScriptContext scriptContext;
    private boolean bindVariables;

    ScriptContextVariableMapper(ScriptContext scriptCtx, boolean bindVariables) {
      this.scriptContext = scriptCtx;
      this.bindVariables = bindVariables;
    }

    @Override
    public ValueExpression resolveVariable(String variableName) {
      if (!bindVariables) {
        // resolved by the EL resolver
        return null;
      }
      int scope = scriptContext.getAttributesScope(variableName);
      if (scope != -1) {
        Object value = scriptContext.getAttribute(variableName, scope);
        if (value instanceof ValueExpression) {
          // Just return the existing ValueExpression
          return (ValueExpression) value;
        } else {
          // Create a new ValueExpression based on the variable value
          return expressionFactory.createValueExpression(value, Object.class);
        }
      }
      return null;
    }

    @Override
    public ValueExpression setVariable(String name, ValueExpression value) {
      ValueExpression previousValue = resolveVariable(name);
      scriptContext.setAttribute(name, value, ScriptContext.ENGINE_SCOPE);
      return previousValue;
    }
  }

  /** Resolves the variable scope key, e.g. {@code connector}. */
  private static class VariableScopeKeyElResolver extends ELResolver {

    @Override
    public Object getValue(ELContext context, Object base, Object property) {
      if (base == null && property instanceof String) {
        VariableScope variableScope = (VariableScope) context.getContext(VariableScope.class);
        if (variableScope != null && property.equals(variableScope.getVariableScopeKey())) {
          context.setPropertyResolved(true);
          return variableScope;
        }
      }
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
      return base == null ? String.class : null;
    }
  }

  /**
   * FunctionMapper that uses the ScriptContext to resolve functions in EL.
   *
   * @author Frederik Heremans
   */
  private class ScriptContextFunctionMapper extends FunctionMapper {

    private ScriptContext scriptContext;

    ScriptContextFunctionMapper(ScriptContext ctx) {
      this.scriptContext = ctx;
    }

    private String getFullFunctionName(String prefix, String localName) {
      return prefix + ":" + localName;
    }

    @Override
    public Method resolveFunction(String prefix, String localName) {
      String functionName = getFullFunctionName(prefix, localName);
      int scope = scriptContext.getAttributesScope(functionName);
      if (scope != -1) {
        // Methods are added as variables in the ScriptScope
        Object attributeValue = scriptContext.getAttribute(functionName);
        return (attributeValue instanceof Method) ? (Method) attributeValue : null;
      } else {
        return null;
      }
    }
  }

}
