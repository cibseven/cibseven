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
package org.cibseven.bpm.engine.impl.jobexecutor;

import static org.cibseven.bpm.engine.impl.util.EnsureUtil.ensureNotNull;

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;


public class TimerCatchIntermediateEventJobHandler extends TimerEventJobHandler {

  public static final String TYPE = "timer-intermediate-transition";

  public String getType() {
    return TYPE;
  }

  public void execute(TimerJobConfiguration configuration, ExecutionEntity execution, CommandContext commandContext, String tenantId) {
    String activityId = configuration.getTimerElementKey();
    ActivityImpl intermediateEventActivity = execution.getProcessDefinition().findActivity(activityId);

    ensureNotNull("Error while firing timer: intermediate event activity " + configuration + " not found", "intermediateEventActivity", intermediateEventActivity);

    try {
      if(activityId.equals(execution.getActivityId())) {
        // Regular Intermediate timer catch
        execution.signal("signal", null);
      }
      else {
        // Event based gateway
        execution.executeEventHandlerActivity(intermediateEventActivity);
      }

    }
    catch (RuntimeException e) {
      throw e;
    }
    catch (Exception e) {
      throw new ProcessEngineException("exception during timer execution: " + e.getMessage(), e);
    }
  }
}