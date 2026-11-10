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
package org.cibseven.bpm.engine.impl.externaltask;

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.impl.ProcessEngineLogger;
import org.cibseven.bpm.engine.impl.bpmn.parser.CamundaErrorEventDefinition;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;

/**
 * Represents the logger for the external task.
 *
 * @author Christopher Zell <christopher.zell@camunda.com>
 */
public class ExternalTaskLogger extends ProcessEngineLogger {

  /**
   * Logs that the priority could not be determined in the given context.
   *
   * @param execution the context that is used for determining the priority
   * @param value the default value
   * @param e the exception which was caught
   */
  public void couldNotDeterminePriority(ExecutionEntity execution, Object value, ProcessEngineException e) {
    logWarn(
        "001",
        "Could not determine priority for external task created in context of execution {}. Using default priority {}",
        execution, value, e);
  }

  /**
   * Logs that the error event definition expression could not be evaluated and will be considered as false
   *
   * @param taskId the context of the definition
   * @param errorEventDefinition the definition whose expression failed
   * @param exception the exception that was caught
   */
  public void errorEventDefinitionEvaluationException(String taskId, CamundaErrorEventDefinition errorEventDefinition, Exception exception) {
    logDebug("002", "Evaluation of error event definition's expression {} on external task {} failed and will be considered as 'false'. "
        + "Received exception: {}", errorEventDefinition.getExpression(), taskId, exception.getMessage());
  }

  /**
   * Logs that the retry time cycle expression of an external task activity could not be evaluated.
   * The external task is handled as if no retry time cycle was configured.
   *
   * @param activityId the activity of the external task
   * @param expression the expression that failed
   * @param exception the exception that was caught
   */
  public void exceptionWhileResolvingRetryTimeCycle(String activityId, Object expression, Exception exception) {
    logWarn("003", "Could not resolve retry time cycle expression {} of external task activity {}. "
        + "The external task is handled as if no retry time cycle was configured. Received exception: {}",
        expression, activityId, exception.getMessage());
  }

  /**
   * Logs that a retry time cycle of an external task activity contains an invalid interval.
   * The external task is handled as if no retry time cycle was configured.
   *
   * @param activityId the activity of the external task
   * @param retryTimeCycle the invalid retry time cycle
   */
  public void invalidRetryTimeCycle(String activityId, String retryTimeCycle) {
    logWarn("004", "Retry time cycle '{}' of external task activity {} contains an invalid interval. "
        + "The external task is handled as if no retry time cycle was configured.",
        retryTimeCycle, activityId);
  }

  /**
   * Logs that a worker reported negative retries for an external task with a retry time cycle,
   * which are treated as 0.
   *
   * @param taskId the external task
   * @param retries the reported retries
   */
  public void negativeRetriesReportedForRetryTimeCycle(String taskId, int retries) {
    logDebug("005", "Worker reported retries {} for external task {} with a retry time cycle. Using 0 instead.",
        retries, taskId);
  }
}
