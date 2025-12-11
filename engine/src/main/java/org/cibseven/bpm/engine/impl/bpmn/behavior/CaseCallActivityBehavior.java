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
package org.cibseven.bpm.engine.impl.bpmn.behavior;

import static org.cibseven.bpm.engine.impl.util.CallableElementUtil.getCaseDefinitionToCall;

import java.util.ArrayList;
import java.util.List;

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.impl.cmmn.entity.runtime.CaseExecutionEntity;
import org.cibseven.bpm.engine.impl.cmmn.execution.CmmnCaseInstance;
import org.cibseven.bpm.engine.impl.cmmn.model.CmmnCaseDefinition;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.migration.instance.MigratingActivityInstance;
import org.cibseven.bpm.engine.impl.migration.instance.MigratingCalledCaseInstance;
import org.cibseven.bpm.engine.impl.migration.instance.parser.MigratingInstanceParseContext;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.delegate.ActivityExecution;
import org.cibseven.bpm.engine.impl.pvm.delegate.MigrationObserverBehavior;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.runtime.CaseInstance;
import org.cibseven.bpm.engine.variable.VariableMap;

/**
 * Implementation to create a new {@link CaseInstance} using the BPMN 2.0 call activity
 *
 * @author Roman Smirnov
 *
 */
public class CaseCallActivityBehavior extends CallableElementActivityBehavior implements MigrationObserverBehavior {

  protected void startInstance(ActivityExecution execution, VariableMap variables, String businessKey) {
    ExecutionEntity executionEntity = (ExecutionEntity) execution;

    CmmnCaseDefinition definition = getCaseDefinitionToCall(executionEntity,
        executionEntity.getProcessDefinitionTenantId(),
        getCallableElement());
    
    // Check for recursion before starting the case
    checkCaseCallActivityRecursion(executionEntity, definition);
    
    CmmnCaseInstance caseInstance = execution.createSubCaseInstance(definition, businessKey);
    caseInstance.create(variables);
  }
  
  /**
   * Checks for recursive Case Call Activity invocations by traversing the superExecution chain.
   * This prevents processes from calling cases that create processes in an infinite loop.
   * 
   * @param execution the current execution entity
   * @param targetDefinition the case definition being called
   * @throws ProcessEngineException if recursion depth limit is exceeded
   */
  protected void checkCaseCallActivityRecursion(ExecutionEntity execution, CmmnCaseDefinition targetDefinition) {
    int maxDepth = Context.getProcessEngineConfiguration().getMaxCallActivityRecursionDepth();
    
    // Skip check if disabled (0 or negative value)
    if (maxDepth <= 0) {
      return;
    }
    
    List<String> callChain = new ArrayList<>();
    
    // Traverse up the superExecution chain to count depth
    ExecutionEntity currentExecution = execution;
    int depth = 0;
    
    while (currentExecution != null) {
      String currentProcessKey = currentExecution.getProcessDefinitionKey();
      
      if (currentProcessKey != null) {
        callChain.add(currentProcessKey);
        depth++;
      }
      
      // Move to the parent execution
      currentExecution = currentExecution.getSuperExecution();
    }
    
    // Check if adding the new case would exceed the limit
    // depth represents the current number of processes in the hierarchy
    // Adding one more would make it depth + 1 processes
    if (depth + 1 > maxDepth) {
      throw new ProcessEngineException(
        String.format("Case Call Activity recursion depth limit exceeded: Maximum depth is %d, current depth is %d. " +
            "Attempting to call case: %s. Current call chain: %s. " +
            "Configure 'maxCallActivityRecursionDepth' in ProcessEngineConfiguration to adjust the limit.",
            maxDepth, depth, targetDefinition.getKey(), buildCallChainString(callChain)));
    }
  }
  
  /**
   * Builds a string representation of the call chain for error messages.
   * 
   * @param callChain the list of process definition keys in the call chain
   * @return a formatted string like "processC -> processB -> processA"
   */
  private String buildCallChainString(List<String> callChain) {
    if (callChain.isEmpty()) {
      return "";
    }
    
    StringBuilder sb = new StringBuilder();
    // Reverse the chain to show root -> ... -> current
    for (int i = callChain.size() - 1; i >= 0; i--) {
      sb.append(callChain.get(i));
      if (i > 0) {
        sb.append(" -> ");
      }
    }
    
    return sb.toString();
  }

  @Override
  public void migrateScope(ActivityExecution scopeExecution) {
  }

  @Override
  public void onParseMigratingInstance(MigratingInstanceParseContext parseContext, MigratingActivityInstance migratingInstance) {
    ActivityImpl callActivity = (ActivityImpl) migratingInstance.getSourceScope();

    // A call activity is typically scope and since we guarantee stability of scope executions during migration,
    // the superExecution link does not have to be maintained during migration.
    // There are some exceptions, though: A multi-instance call activity is not scope and therefore
    // does not have a dedicated scope execution. In this case, the link to the super execution
    // must be maintained throughout migration
    if (!callActivity.isScope()) {
      ExecutionEntity callActivityExecution = migratingInstance.resolveRepresentativeExecution();
      CaseExecutionEntity calledCaseInstance = callActivityExecution.getSubCaseInstance();
      migratingInstance.addMigratingDependentInstance(new MigratingCalledCaseInstance(calledCaseInstance));
    }
  }

}
