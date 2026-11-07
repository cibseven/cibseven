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

import org.cibseven.bpm.engine.impl.interceptor.Command;
import org.cibseven.bpm.engine.impl.jobexecutor.FailedJobCommandFactory;
import org.cibseven.bpm.engine.impl.persistence.entity.JobEntity;

/**
 * Hands a failed turn job to {@link AgenticTurnJobRetryCmd}, and every other job to the factory the
 * engine had before (CIB7-2149).
 *
 * <p>A decorator rather than a replacement: whatever the engine, or another plugin, set up for its
 * own jobs keeps working, and only the turn job's retry changes. The job is looked up when the
 * command runs, inside the failed-job listener's own command, which is when its type is known.
 */
public class AgenticTurnFailedJobCommandFactory implements FailedJobCommandFactory {

  protected final FailedJobCommandFactory delegate;

  public AgenticTurnFailedJobCommandFactory(FailedJobCommandFactory delegate) {
    this.delegate = delegate;
  }

  @Override
  public Command<Object> getCommand(String jobId, Throwable exception) {
    Command<Object> otherwise = delegate.getCommand(jobId, exception);
    return commandContext -> {
      JobEntity job = commandContext.getJobManager().findJobById(jobId);
      if (job != null && AgenticTurnJobHandler.TYPE.equals(job.getJobHandlerType())) {
        return new AgenticTurnJobRetryCmd(jobId, exception).execute(commandContext);
      }
      return otherwise.execute(commandContext);
    };
  }

  /** The factory this one hands every other job to. */
  public FailedJobCommandFactory getDelegate() {
    return delegate;
  }
}
