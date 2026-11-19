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
package org.cibseven.bpm.engine.impl;

import java.util.Locale;
import java.util.Set;

import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.digest.SecurityLogger;

/**
 * Checks the built-in execution mechanisms a process definition uses against
 * {@link ProcessEngineConfigurationImpl#getExecutionAllowList()}: the task types
 * {@code camunda:type="shell"}, {@code "mail"} and {@code "external"}, and the script
 * languages resolved by the {@link org.cibseven.bpm.engine.impl.scripting.engine.ScriptingEngines}.
 *
 * <p>An empty allow list means no check. Once it has entries, only the listed task types and
 * script languages are allowed; {@link ProcessEngineConfigurationImpl#getExpressionHardeningMode()}
 * decides what happens on a violation (off: no check, report: log only, enforce: block).</p>
 *
 * <p>Entries are matched in full and ignoring case.</p>
 */
public final class ExecutionAllowListChecker {

  private static final SecurityLogger LOG = ProcessEngineLogger.SECURITY_LOGGER;

  public static final String TASK_TYPE_SHELL = "shell";
  public static final String TASK_TYPE_MAIL = "mail";
  public static final String TASK_TYPE_EXTERNAL = "external";

  public static final String KIND_TASK_TYPE = "task type";
  public static final String KIND_SCRIPT_LANGUAGE = "script language";

  private ExecutionAllowListChecker() {
  }

  /**
   * Blocks the task type at execution time, unless the allow list permits it or is not active.
   *
   * @param taskType the value of {@code camunda:type}, e.g. {@code shell}
   * @param activityId the activity that uses the task type, for the message
   * @throws org.cibseven.bpm.engine.ProcessEngineException if the task type must be blocked
   */
  public static void ensureTaskTypeAllowed(String taskType, String activityId) {
    if (isBlocked(KIND_TASK_TYPE, taskType, activityId)) {
      throw LOG.executionNotAllowed(KIND_TASK_TYPE, taskType, activityId);
    }
  }

  /**
   * Blocks the script language, unless the allow list permits it or is not active.
   *
   * @param language the name of the script language, e.g. {@code groovy}
   * @throws org.cibseven.bpm.engine.ProcessEngineException if the script language must be blocked
   */
  public static void ensureScriptLanguageAllowed(String language) {
    if (isBlocked(KIND_SCRIPT_LANGUAGE, language, null)) {
      throw LOG.executionNotAllowed(KIND_SCRIPT_LANGUAGE, language, null);
    }
  }

  /**
   * @return the message of a blocked use, with the same code as the exception thrown at execution time
   */
  public static String getViolationMessage(String kind, String name, String activityId) {
    return LOG.executionNotAllowed(kind, name, activityId).getMessage();
  }

  /**
   * @return {@code true} if the use of {@code name} must be blocked. A violation in report mode
   *   is logged and returns {@code false}. A {@code null} name is not checked: there is nothing
   *   to execute, and the caller reports the missing name itself.
   */
  public static boolean isBlocked(String kind, String name, String activityId) {
    ProcessEngineConfigurationImpl configuration = Context.getProcessEngineConfiguration();
    if (name == null || configuration == null) {
      return false;
    }

    Set<String> allowList = configuration.getExecutionAllowList();
    if (allowList == null || allowList.isEmpty()) {
      return false;
    }

    String mode = configuration.getExpressionHardeningMode();
    if (ProcessEngineConfigurationImpl.EXPRESSION_HARDENING_MODE_OFF.equals(mode)) {
      return false;
    }

    if (allowList.contains(name.trim().toLowerCase(Locale.ROOT))) {
      return false;
    }

    if (ProcessEngineConfigurationImpl.EXPRESSION_HARDENING_MODE_REPORT.equals(mode)) {
      LOG.reportExecutionNotAllowed(kind, name, activityId, allowList);
      return false;
    }

    return true;
  }

}
