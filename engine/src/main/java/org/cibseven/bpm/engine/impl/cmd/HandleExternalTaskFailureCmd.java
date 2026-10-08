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
package org.cibseven.bpm.engine.impl.cmd;

import java.util.Map;

import org.cibseven.bpm.engine.impl.ProcessEngineLogger;
import org.cibseven.bpm.engine.impl.persistence.entity.ExternalTaskEntity;
import org.cibseven.bpm.engine.impl.util.EnsureUtil;

/**
 * @author Thorben Lindhauer
 * @author Christopher Zell
 * @author Askar Akhmerov
 */
public class HandleExternalTaskFailureCmd extends HandleExternalTaskCmd {

  protected String errorMessage;
  protected String errorDetails;
  protected long retryDuration;
  protected int retries;
  protected Map<String, Object> variables;
  protected Map<String, Object> localVariables;

  /**
   * Overloaded constructor to support short and full error messages
   *
   * @param externalTaskId
   * @param workerId
   * @param errorMessage
   * @param errorDetails
   * @param retries
   * @param retryDuration
   */
  public HandleExternalTaskFailureCmd(String externalTaskId, String workerId,
                                      String errorMessage, String errorDetails, 
                                      int retries, long retryDuration, 
                                      Map<String, Object> variables, Map<String, Object> localVariables) {
    super(externalTaskId, workerId);
    this.errorMessage = errorMessage;
    this.errorDetails = errorDetails;
    this.retries = retries;
    this.retryDuration = retryDuration;
    this.variables = variables;
    this.localVariables = localVariables;
  }

  @Override
  public void execute(ExternalTaskEntity externalTask) {
    int reportedRetries = retries;
    if (reportedRetries < 0 && externalTask.hasRetryConfiguration()) {
      // the engine already decremented the retries at fetch, so the last attempt is handed out
      // with retries 0; a worker that still reports getRetries() - 1 must not lose its failure
      ProcessEngineLogger.EXTERNAL_TASK_LOGGER.negativeRetriesReportedForRetryTimeCycle(externalTask.getId(), reportedRetries);
      reportedRetries = 0;
    }
    EnsureUtil.ensureGreaterThanOrEqual("retries", reportedRetries, 0);
    externalTask.failed(errorMessage, errorDetails, reportedRetries, retryDuration, variables, localVariables);
  }

  @Override
  protected void validateInput() {
    super.validateInput();
    // retries are validated in execute(), negative values are accepted for tasks with a retry time cycle
    EnsureUtil.ensureGreaterThanOrEqual("retryDuration", retryDuration, 0);
  }

  @Override
  public String getErrorMessageOnWrongWorkerAccess() {
    return "Failure of External Task " + externalTaskId + " cannot be reported by worker '" + workerId;
  }
}
