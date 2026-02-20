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
package org.cibseven.spin.impl.test;

import java.io.File;
import java.io.Reader;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.cibseven.spin.SpinScriptException;
import org.cibseven.spin.impl.util.SpinIoUtil;
import org.cibseven.spin.scripting.SpinScriptEnv;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * A jUnit4 {@link TestRule} to load and execute a script. To
 * execute a {@link org.cibseven.spin.impl.test.ScriptEngine} {@link ClassRule}
 * is used to obtain a {@link ScriptEngine}.
 *
 * @author Sebastian Menski
 * @author Daniel Meyer
 */
public class ScriptRule implements BeforeEachCallback, AfterEachCallback {

  private static final SpinTestLogger LOG = SpinTestLogger.TEST_LOGGER;

  private String script;
  private String scriptPath;
  private ScriptEngine scriptEngine;

  /**
   * The variables of the script accessed during script execution.
   */
  protected final Map<String, Object> variables = new HashMap<>();

  private Optional<Method> m2;

  @Override
  public void beforeEach(ExtensionContext context) throws Exception {
    loadScript(context);
  }

  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    for (Object variable : variables.values()) {
      if (variable != null && Reader.class.isAssignableFrom(variable.getClass())) {
        Reader reader = (Reader) variable;
        SpinIoUtil.closeSilently(reader);
      }
    }
  }

  /**
   * Load a script and the script variables defined. Also execute the
   * script if {@link Script#execute()} is {@link true}.
   *
   * @param context the context of the test method
   * @throws Exception
   */
  private void loadScript(ExtensionContext context) throws Exception {
    scriptEngine = getScriptEngine(context);
    if (scriptEngine == null) {
      return;
    }

    script = getScript(context);
    collectScriptVariables(context);
    if (scriptEngine.getFactory().getLanguageName().equalsIgnoreCase("ruby")) {
      // set magic property to remove all internal variables of the ruby scripting engine
      // otherwise global variables will live forever
      variables.put("org.jruby.embed.clear.variables", true);
    }
    boolean execute = isExecuteScript(context);
    if (execute) {
      executeScript();
    }
  }

  /**
   * Returns the {@link ScriptEngine} of the {@link ScriptEngineRule} of the
   * test class.
   *
   * @param context the context of the test method
   * @return the script engine found or null
   */
  private ScriptEngine getScriptEngine(ExtensionContext context) {
    try {
      ScriptEngineRule scriptEngineRule = (ScriptEngineRule) context.getTestClass().get().getField("scriptEngine").get(null);
      return scriptEngineRule.getScriptEngine();
    } catch (NoSuchFieldException e) {
      return null;
    } catch (IllegalAccessException e) {
      return null;
    }
  }

  /**
   * Return the script as {@link String} based on the {@literal @}{@link Script} annotation
   * of the test method.
   *
   * @param context ion the context of the test method
   * @return the script as string or null if no script was found
   */
  private String getScript(ExtensionContext context) {
    Script scriptAnnotation = context.getTestMethod().get().getAnnotation(Script.class);
    if (scriptAnnotation == null) {
      return null;
    }
    String scriptBasename = getScriptBasename(scriptAnnotation, context);
    scriptPath = getScriptPath(scriptBasename, context);
    File file = SpinIoUtil.getClasspathFile(scriptPath, context.getTestClass().get().getClassLoader());
    return SpinIoUtil.fileAsString(file);
  }

  /**
   * Collect all {@literal @}{@link ScriptVariable} annotations of the test method
   * and save the variables in the {@link #variables} field.
   *
   * @param context the context of the test method
   */
  private void collectScriptVariables(ExtensionContext context) {
    ScriptVariable scriptVariable = context.getTestMethod().get().getAnnotation(ScriptVariable.class);

    collectScriptVariable(scriptVariable, context);

    Script script = context.getTestMethod().get().getAnnotation(Script.class);
    if (script != null) {
      for (ScriptVariable variable : script.variables()) {
        collectScriptVariable(variable, context);
      }
    }
  }

  /**
   * Extract the variable of a single {@literal @}{@link ScriptVariable} annotation.
   *
   * @param scriptVariable the annotation
   * @param context the context of the test method
   */
  private void collectScriptVariable(ScriptVariable scriptVariable, ExtensionContext context) {
    if (scriptVariable == null) {
      return;
    }

    String name = scriptVariable.name();
    String value = scriptVariable.value();
    String filename = scriptVariable.file();
    boolean isNull = scriptVariable.isNull();
    if (isNull) {
      variables.put(name, null);
      LOG.scriptVariableFound(name, "isNull", null);
    }
    else if (!filename.isEmpty()) {
      Reader fileAsReader = SpinIoUtil.classpathResourceAsReader(filename);
      variables.put(name, fileAsReader);
      LOG.scriptVariableFound(name, "reader", filename);
    }
    else {
      variables.put(name, value);
      LOG.scriptVariableFound(name, "string", value);
    }
  }

  /**
   * Determines if the script should be executed before the call of the
   * java test method.
   *
   * @param context the context of the test method
   * @return true if the script should be executed in front or false otherwise
   */
  private boolean isExecuteScript(ExtensionContext context) {
    Script annotation = context.getTestMethod().get().getAnnotation(Script.class);
    return annotation != null && annotation.execute();
  }

  /**
   * Executes the script with the set variables.
   * @throws Exception
   *
   * @throws SpinScriptException if an error occurs during the script execution
   */
  private void executeScript() throws Exception {
    if (scriptEngine != null) {
      try {
        String environment = SpinScriptEnv.get(scriptEngine.getFactory().getLanguageName());

        Bindings bindings = new SimpleBindings(variables);
        LOG.executeScriptWithScriptEngine(scriptPath, scriptEngine.getFactory().getEngineName());
        scriptEngine.eval(environment, bindings);
        scriptEngine.eval(script, bindings);
      } catch (ScriptException e) {
        if ("graal.js".equalsIgnoreCase(scriptEngine.getFactory().getEngineName())) {
          if (e.getCause() instanceof Exception) {
            throw (Exception) e.getCause();
          }
          throw new RuntimeException(e.getCause());
        }
        throw LOG.scriptExecutionError(scriptPath, e);
      }
    }
  }

  /**
   * Execute the script and add the given variables to the script variables.
   *
   * @param scriptVariables the variables to set additionally
   * @return this script rule
   * @throws Throwable
   * @throws SpinScriptException if an error occurs during the script execution
   */
  public ScriptRule execute(Map<String, Object> scriptVariables) throws Throwable {
    variables.putAll(scriptVariables);
    executeScript();
    return this;
  }

  /**
   * Execute the script
   *
   * @return this script rule
   * @throws Throwable
   * @throws SpinScriptException if an error occurs during the script execution
   */
  public ScriptRule execute() throws Throwable {
    executeScript();
    return this;
  }

  /**
   * Determines the base filename of the script.
   *
   * @param scriptAnnotation the script annotation of the test method
   * @param context the context of the test method
   * @return the base filename of the script
   */
  private String getScriptBasename(Script scriptAnnotation, ExtensionContext context) {
    String scriptBasename = scriptAnnotation.value();
    if (scriptBasename.isEmpty()) {
      scriptBasename = scriptAnnotation.name();
    }
    if (scriptBasename.isEmpty()) {
      scriptBasename = context.getTestClass().get().getSimpleName() + "." + context.getTestMethod().get().getName();
    }
    return scriptBasename;
  }

  /**
   * Returns the directory path of the package.
   *
   * @param context the context of the test method
   * @return the directory for the package
   */
  private String getPackageDirectoryPath(ExtensionContext context) {
    String packageName = context.getTestClass().get().getPackage().getName();
    return replaceDotsWithPathSeparators(packageName) + File.separator;
  }

  /**
   * Replace all dots in the path with the {@link File#separator} character.
   *
   * @param path the path to process
   * @return the processed path
   */
  private String replaceDotsWithPathSeparators(String path) {
    return path.replace(".", File.separator);
  }

  /**
   * Returns the full path of the script based on package and basename.
   *
   * @param scriptBasename the basename of the script file
   * @param context the context of the test method
   * @return the full path
   */
  private String getScriptPath(String scriptBasename, ExtensionContext context) {
    return getPackageDirectoryPath(context) +  scriptBasename + getScriptExtension();
  }

  /**
   * Returns the script file extension based on the {@link ScriptEngine} language.
   *
   * @return the file extension or empty string if none was found
   */
  private String getScriptExtension() {
    String languageName = scriptEngine.getFactory().getLanguageName();
    String extension = SpinScriptEnv.getExtension(languageName);
    if (extension == null) {
      LOG.noScriptExtensionFoundForScriptLanguage(languageName);
      return "";
    }
    else {
      return "." + extension;
    }
  }

  /**
   * Returns the value of a named script variable
   * @param name the name of the variable
   * @return the value of the variable or null if the variable does not exist.
   */
  @SuppressWarnings("unchecked")
  public <T> T getVariable(String name) {
    try {
      if (scriptEngine.getFactory().getLanguageName().equals("ECMAScript")) {
        return (T) getVariableJs(name);
      }
      else {
        return (T) variables.get(name);
      }
    } catch(ClassCastException e) {
      throw LOG.cannotCastVariableError(name, e);
    }
  }

  /**
   * If JavaScript engine is used the variable may be placed in the global
   * variable map.
   *
   * @param name the name of the variable
   * @return the variable if found or null
   */
  @SuppressWarnings("unchecked")
  private Object getVariableJs(String name) {
    Object variable = variables.get(name);
    if (variable == null) {
      if (variables.containsKey("nashorn.global")) {
        variable = ((Map<String, Object>) variables.get("nashorn.global")).get(name);
      } else if (variables.containsKey("polyglot.context")) {
        Value member = ((org.graalvm.polyglot.Context)variables.get("polyglot.context")).getBindings("js").getMember(name);
        variable = member == null ? null : member.as(Object.class);
      }
    }
    return variable;
  }

  /**
   * Set the variable with the given name
   * @param name the name of the variable
   * @param value value of the variable
   * @return this script rule
   */
  public ScriptRule setVariable(String name, Object value) {
    variables.put(name, value);
    return this;
  }

}
