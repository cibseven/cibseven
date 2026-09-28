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

import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.jobexecutor.JobHandler;
import org.cibseven.bpm.engine.impl.jobexecutor.JobHandlerConfiguration;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.JobEntity;

/** One turn of an agentic ad hoc scope, in its own transaction. */
public class AgenticTurnJobHandler implements JobHandler<AgenticTurnJobHandler.Configuration> {

  public static final String TYPE = "agentic-ad-hoc-turn";

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public void execute(Configuration configuration, ExecutionEntity execution,
                      CommandContext commandContext, String tenantId) {
    AgenticTurnRunner.runTurn(execution, commandContext);
  }

  @Override
  public Configuration newConfiguration(String canonicalString) {
    return new Configuration(canonicalString);
  }

  @Override
  public void onDelete(Configuration configuration, JobEntity jobEntity) {
    // Nothing: a turn keeps no state outside the execution, which is going with it.
  }

  /**
   * Carries the scope execution id, which is also the key the scheduler looks a waiting job up by
   * -- covered by ACT_IDX_JOB_HANDLER.
   */
  public static class Configuration implements JobHandlerConfiguration {

    protected final String scopeExecutionId;

    public Configuration(String scopeExecutionId) {
      this.scopeExecutionId = scopeExecutionId;
    }

    public String getScopeExecutionId() {
      return scopeExecutionId;
    }

    @Override
    public String toCanonicalString() {
      return scopeExecutionId;
    }
  }
}
