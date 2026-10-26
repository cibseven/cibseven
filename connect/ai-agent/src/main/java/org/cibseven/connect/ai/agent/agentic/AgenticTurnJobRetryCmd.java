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
package org.cibseven.connect.ai.agent.agentic;

import org.cibseven.bpm.engine.impl.bpmn.parser.FailedJobRetryConfiguration;
import org.cibseven.bpm.engine.impl.cmd.DefaultJobRetryCmd;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.JobEntity;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.impl.util.ParseUtil;

/**
 * The engine's retry of a failed job, for a turn job (CIB7-2149): the next attempt waits for the
 * scope's retry cycle instead of following at once.
 *
 * <p>Only two things differ from the engine's own command. The activity is the scope, which is the
 * turn job's execution; the engine looks one up only for timer and async-continuation jobs. And
 * the cycle is the one {@link AgenticTurnRetry} parsed onto that scope. Counting, the first failure
 * setting the retries to the cycle's, the due date and the incident at the end stay the engine's.
 */
public class AgenticTurnJobRetryCmd extends DefaultJobRetryCmd {

  public AgenticTurnJobRetryCmd(String jobId, Throwable exception) {
    super(jobId, exception);
  }

  /** The scope the turn belongs to; {@code null}, the engine's immediate retry, if it is gone. */
  @Override
  public ActivityImpl getCurrentActivity(CommandContext commandContext, JobEntity job) {
    if (job.getExecutionId() == null) {
      return null;
    }
    ExecutionEntity scope = fetchExecutionEntity(job.getExecutionId());
    return (scope == null) ? null : scope.getActivity();
  }

  /** The scope's turn retry cycle, its expression evaluated as the engine does for its own. */
  @Override
  public FailedJobRetryConfiguration getFailedJobRetryConfiguration(JobEntity job, ActivityImpl activity) {
    FailedJobRetryConfiguration configuration =
        activity.getProperties().get(AgenticTurnRetry.RETRY_CONFIGURATION);
    while (configuration != null && configuration.getExpression() != null) {
      configuration = ParseUtil.parseRetryIntervals(
          getFailedJobRetryTimeCycle(job, configuration.getExpression()));
    }
    return configuration;
  }
}
