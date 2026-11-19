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
package org.cibseven.bpm.engine.impl.digest;

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.impl.ProcessEngineLogger;

import java.util.Collection;

public class SecurityLogger extends ProcessEngineLogger {

  public ProcessEngineException cannotResolveAlgorithmPrefixFromGivenPasswordException(String resolvedHashAlgorithmName, Collection<String> availableHashAlgorithmNames) {
    return new PasswordEncryptionException(exceptionMessage(
      "001",
      "Could not resolve hash algorithm name of a hashed password. Resolved hash algorithm name {}. Available hash algorithms {}",
      resolvedHashAlgorithmName,
      availableHashAlgorithmNames
    ));
  }

  public ProcessEngineException hashAlgorithmForPasswordEncryptionAlreadyAvailableException(String hashAlgorithmName) {
    return new PasswordEncryptionException(exceptionMessage(
      "002",
      "Hash algorithm with the name '{}' was already added. The algorithm cannot be added twice!",
      hashAlgorithmName
    ));
  }

  /**
   * The message does not name the configured list: it reaches API clients (e.g. REST responses of a
   * deployment or a process start), and the list is part of the security configuration.
   */
  public ProcessEngineException executionNotAllowed(String kind, String name, String activityId) {
    return new ProcessEngineException(exceptionMessage(
      "003",
      "The {} '{}'{} is not allowed by the executionAllowList. An administrator can add it to the list to allow it.",
      kind,
      name,
      describeActivity(activityId)
    ));
  }

  /**
   * Server log only, so the configured list is included to help the administrator.
   */
  public void reportExecutionNotAllowed(String kind, String name, String activityId, Collection<String> allowList) {
    logWarn(
      "004",
      "The {} '{}'{} is not in the executionAllowList {}. It is not blocked, since the expressionHardeningMode is 'report'.",
      kind,
      name,
      describeActivity(activityId),
      allowList
    );
  }

  protected String describeActivity(String activityId) {
    return activityId != null ? " of activity '" + activityId + "'" : "";
  }

  // expression deny list (CIB7-2652): codes 010-013, 003-009 are left to the execution allow list (CIB7-2653)
  public void reportDeniedExpressionAccess(String violation) {
    logWarn(
      "010",
      "{}. Allowed anyway because the expression access policy is in report mode; switch it to enforce to block this access.",
      violation
    );
  }

  public void warnExpressionManagerNotRestricted(String expressionManagerClass) {
    logWarn(
      "011",
      "Expression manager '{}' is not a JuelExpressionManager: deniedExpressionAccess and expressionHardeningMode "
          + "do not apply to its expressions. Restrict its EL resolver chain with an ExpressionAccessPolicy.",
      expressionManagerClass
    );
  }

  public void warnExpressionAccessPropertiesIgnored(String expressionManagerClass) {
    logWarn(
      "012",
      "Expression manager '{}' has its own expression access policy: deniedExpressionAccess and "
          + "expressionHardeningMode of the process engine configuration are ignored.",
      expressionManagerClass
    );
  }

  public void warnExpressionManagerAlreadyUsed(String expressionManagerClass) {
    logWarn(
      "013",
      "Expression manager '{}' was used before the process engine was built and keeps its expression access policy: "
          + "deniedExpressionAccess and expressionHardeningMode of the process engine configuration are ignored. "
          + "Build the process engine before using the expression manager.",
      expressionManagerClass
    );
  }
}
