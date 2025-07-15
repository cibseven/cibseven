/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.cibseven.bpm.engine.impl.scripting.util;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import org.cibseven.bpm.engine.impl.scripting.engine.CamundaScriptEngineManager;

/**
 * Utility class for creating script engines with CibSeven namespace support.
 * This class provides helper methods for users who want to create their own
 * script engines with the CibSevenClassLoader without having compile-time
 * dependencies on specific script engine implementations.
 * 
 * @author CibSeven Team
 */
public class CibSevenScriptEngineUtil {
  
  /**
   * Creates a Groovy script engine with CibSevenClassLoader support.
   * This method uses reflection to avoid compile-time dependencies on Groovy.
   * 
   * Usage example:
   * <pre>
   * ScriptEngine groovyEngine = CibSevenScriptEngineUtil.createGroovyScriptEngine();
   * if (groovyEngine != null) {
   *   // Use the script engine
   *   groovyEngine.eval("println 'Hello from CibSeven!'");
   * }
   * </pre>
   * 
   * @return A Groovy ScriptEngine with CibSevenClassLoader, or null if Groovy is not available
   */
  public static ScriptEngine createGroovyScriptEngine() {
    return createGroovyScriptEngine(Thread.currentThread().getContextClassLoader());
  }
  
  /**
   * Creates a Groovy script engine with CibSevenClassLoader support using a specific parent ClassLoader.
   * 
   * @param parentClassLoader The parent ClassLoader to use
   * @return A Groovy ScriptEngine with CibSevenClassLoader, or null if Groovy is not available
   */
  public static ScriptEngine createGroovyScriptEngine(ClassLoader parentClassLoader) {
    try {
      ClassLoader cibSevenClassLoader = CamundaScriptEngineManager.createCibSevenClassLoader(parentClassLoader);
      
      // Use reflection to create GroovyClassLoader
      Class<?> groovyClassLoaderClass = Class.forName("groovy.lang.GroovyClassLoader", true, cibSevenClassLoader);
      var groovyClassLoaderConstructor = groovyClassLoaderClass.getConstructor(ClassLoader.class);
      var groovyClassLoader = groovyClassLoaderConstructor.newInstance(cibSevenClassLoader);
      
      // Use reflection to create GroovyScriptEngineImpl
      Class<?> groovyScriptEngineClass = Class.forName("org.codehaus.groovy.jsr223.GroovyScriptEngineImpl", true, cibSevenClassLoader);
      var groovyScriptEngineConstructor = groovyScriptEngineClass.getConstructor(groovyClassLoaderClass);
      return (ScriptEngine) groovyScriptEngineConstructor.newInstance(groovyClassLoader);
      
    } catch (Exception e) {
      // Return null if Groovy is not available
      return null;
    }
  }
  
  /**
   * Creates a custom ScriptEngineManager that uses CibSevenClassLoader for script engines.
   * This can be used to get any script engine with CibSeven namespace support.
   * 
   * @return A ScriptEngineManager with CibSeven support
   */
  public static ScriptEngineManager createCibSevenScriptEngineManager() {
    return new CamundaScriptEngineManager();
  }
  
  /**
   * Gets the CibSevenClassLoader that can be used with any script engine.
   * This classloader automatically translates Camunda namespace classes to CibSeven namespace.
   * 
   * Usage example with any script engine:
   * <pre>
   * ClassLoader cibSevenClassLoader = CibSevenScriptEngineUtil.getCibSevenClassLoader();
   * // Use with any script engine that accepts a ClassLoader
   * </pre>
   * 
   * @return A ClassLoader that handles CibSeven namespace translation
   */
  public static ClassLoader getCibSevenClassLoader() {
    return CamundaScriptEngineManager.createCibSevenClassLoader();
  }
  
  /**
   * Gets the CibSevenClassLoader with a specific parent ClassLoader.
   * 
   * @param parentClassLoader The parent ClassLoader
   * @return A ClassLoader that handles CibSeven namespace translation
   */
  public static ClassLoader getCibSevenClassLoader(ClassLoader parentClassLoader) {
    return CamundaScriptEngineManager.createCibSevenClassLoader(parentClassLoader);
  }
}
