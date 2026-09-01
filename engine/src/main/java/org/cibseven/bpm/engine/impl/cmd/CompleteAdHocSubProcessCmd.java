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
package org.cibseven.bpm.engine.impl.cmd;

import static org.cibseven.bpm.engine.impl.util.EnsureUtil.ensureNotNull;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.history.UserOperationLogEntry;
import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocSubProcessActivityBehavior;
import org.cibseven.bpm.engine.impl.cfg.CommandChecker;
import org.cibseven.bpm.engine.impl.interceptor.Command;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.PropertyChange;
import org.cibseven.bpm.engine.impl.pvm.PvmActivity;

/**
 * Ends an ad hoc sub process, cancelling whatever is still running inside it.
 *
 * <p>Without this a scope whose completion condition never becomes true has no way out short of
 * deleting the process instance. The performers decide when an ad hoc scope is finished, per BPMN
 * 2.0.0 section 10.3.5, so they need a way to say so.
 */
public class CompleteAdHocSubProcessCmd implements Command<Void>, Serializable {

  private static final long serialVersionUID = 1L;

  protected String executionId;
  protected Map<String, Object> variables;

  public CompleteAdHocSubProcessCmd(String executionId, Map<String, Object> variables) {
    this.executionId = executionId;
    this.variables = variables;
  }

  @Override
  public Void execute(CommandContext commandContext) {
    ensureNotNull(BadUserRequestException.class, "executionId is null", "executionId", executionId);

    ExecutionEntity scopeExecution = commandContext.getExecutionManager().findExecutionById(executionId);
    ensureNotNull(BadUserRequestException.class,
        "execution " + executionId + " doesn't exist", "execution", scopeExecution);

    for (CommandChecker checker : commandContext.getProcessEngineConfiguration().getCommandCheckers()) {
      checker.checkUpdateProcessInstance(scopeExecution);
    }

    PvmActivity activity = scopeExecution.getActivity();
    if (activity == null || !(activity.getActivityBehavior() instanceof AdHocSubProcessActivityBehavior)) {
      throw new BadUserRequestException("Execution '" + executionId
          + "' is not an ad hoc sub process instance. Pass the execution of the ad hoc sub process"
          + " scope itself.");
    }

    if (variables != null) {
      scopeExecution.setVariables(variables);
    }

    // Logged before completing, not after. completeScopeOnRequest leaves the scope synchronously, so
    // by the time it returns the execution has moved on and getActivityId() reports the following
    // activity or nothing at all. If completion then fails, the transaction discards this entry
    // along with everything else, so writing it first costs nothing.
    writeUserOperationLog(commandContext, scopeExecution, activity.getId());

    AdHocSubProcessActivityBehavior behavior = (AdHocSubProcessActivityBehavior) activity.getActivityBehavior();
    behavior.completeScopeOnRequest(scopeExecution);

    return null;
  }

  protected void writeUserOperationLog(CommandContext commandContext, ExecutionEntity scopeExecution,
      String adHocActivityId) {
    List<PropertyChange> propertyChanges = new ArrayList<>();
    propertyChanges.add(new PropertyChange("adHocActivityId", null, adHocActivityId));

    commandContext.getOperationLogManager().logProcessInstanceOperation(
        UserOperationLogEntry.OPERATION_TYPE_MODIFY_PROCESS_INSTANCE,
        scopeExecution.getProcessInstanceId(),
        scopeExecution.getProcessDefinitionId(),
        null,
        propertyChanges);
  }

}
