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
package org.cibseven.bpm.engine.impl.scripting;

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.impl.ProcessEngineLogger;

/**
 * @author Daniel Meyer
 *
 */
public class ScriptLogger extends ProcessEngineLogger {

  public void debugEvaluatingCompiledScript(String language) {
    logDebug(
        "001", "Evaluating compiled script {} in language", language);
  }

  public void debugCompiledScriptUsing(String language) {
    logDebug(
        "002", "Compiled script using {} script language", language);
  }

  public void debugEvaluatingNonCompiledScript(String scriptSource) {
    logDebug(
        "001", "Evaluating non-compiled script {}", scriptSource);
  }

  public void logClosingScriptEngineFailed(Exception e) {
    logDebug(
        "003", "Failed to close script engine: {}", e.getMessage());
  }

  public void warnJuelScriptWithoutExpressionManagerResolver(String expressionManagerClass) {
    logWarn(
        "004", "Expression manager '{}' is not a JuelExpressionManager, its resolver restrictions do not apply to JUEL scripts. "
            + "Extend JuelExpressionManager to apply them.",
        expressionManagerClass);
  }

  public void warnJuelScriptWithoutScriptBindings(String expressionManagerClass) {
    logWarn(
        "005", "JUEL script evaluated without the engine's script bindings, the resolver restrictions of expression manager '{}' "
            + "do not apply to it. A custom ScriptingEngines or ScriptBindingsFactory must create ScriptBindings.",
        expressionManagerClass);
  }

  public ProcessEngineException graalJsNumberNarrowingFailed(String mode, Throwable cause) {
    return new ProcessEngineException(exceptionMessage(
        "006", "Cannot create the GraalJS script engine with javaScriptNumberNarrowing '{}': {}. Set "
            + "javaScriptNumberNarrowing to 'none' to run JavaScript with the number types GraalJS gives.",
        // the cause as a value: a Throwable as the last parameter would be taken for the exception and leave {}
        mode, String.valueOf(cause)), cause);
  }

}
