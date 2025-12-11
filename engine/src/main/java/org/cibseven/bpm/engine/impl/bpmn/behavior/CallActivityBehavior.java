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

import java.util.ArrayList;
import java.util.List;

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.delegate.Expression;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.migration.instance.MigratingActivityInstance;
import org.cibseven.bpm.engine.impl.migration.instance.MigratingCalledProcessInstance;
import org.cibseven.bpm.engine.impl.migration.instance.parser.MigratingInstanceParseContext;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.PvmProcessInstance;
import org.cibseven.bpm.engine.impl.pvm.delegate.ActivityExecution;
import org.cibseven.bpm.engine.impl.pvm.delegate.MigrationObserverBehavior;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.impl.pvm.process.ProcessDefinitionImpl;
import org.cibseven.bpm.engine.variable.VariableMap;

import static org.cibseven.bpm.engine.impl.util.CallableElementUtil.getProcessDefinitionToCall;


/**
 * Implementation of the BPMN 2.0 call activity
 * (limited currently to calling a subprocess and not (yet) a global task).
 *
 * @author Joram Barrez
 * @author Roman Smirnov
 */
public class CallActivityBehavior extends CallableElementActivityBehavior implements MigrationObserverBehavior {

  public CallActivityBehavior() {
  }

  public CallActivityBehavior(String className) {
    super(className);
  }

  public CallActivityBehavior(Expression expression) {
    super(expression);
  }

  @Override
  protected void startInstance(ActivityExecution execution, VariableMap variables, String businessKey) {
    ExecutionEntity executionEntity = (ExecutionEntity) execution;

    ProcessDefinitionImpl definition = getProcessDefinitionToCall(
        executionEntity,
        executionEntity.getProcessDefinitionTenantId(),
        getCallableElement());
    
    // Check for recursion before starting the subprocess
    checkCallActivityRecursion(executionEntity, definition);
    
    PvmProcessInstance processInstance = execution.createSubProcessInstance(definition, businessKey);
    processInstance.start(variables);
  }
  
  /**
   * Checks for recursive Call Activity invocations by traversing the superExecution chain.
   * Throws a ProcessEngineException if:
   * - The target process definition key already exists in the call hierarchy (cycle detected)
   * - The maximum recursion depth is exceeded
   * 
   * @param execution the current execution entity
   * @param targetDefinition the process definition being called
   * @throws ProcessEngineException if recursion is detected or depth limit is exceeded
   */
  protected void checkCallActivityRecursion(ExecutionEntity execution, ProcessDefinitionImpl targetDefinition) {
    int maxDepth = Context.getProcessEngineConfiguration().getMaxCallActivityRecursionDepth();
    
    // Skip check if disabled (0 or negative value)
    if (maxDepth <= 0) {
      return;
    }
    
    String targetProcessKey = targetDefinition.getKey();
    List<String> callChain = new ArrayList<>();
    
    // Traverse up the superExecution chain to collect process definition keys
    ExecutionEntity currentExecution = execution;
    int depth = 0;
    
    while (currentExecution != null) {
      String currentProcessKey = currentExecution.getProcessDefinitionKey();
      
      if (currentProcessKey != null) {
        callChain.add(currentProcessKey);
        depth++;
        
        // Check for cycle: target process is already in the call chain
        if (targetProcessKey.equals(currentProcessKey)) {
          throw new ProcessEngineException(
            String.format("Recursive Call Activity detected: Process '%s' is already present in the call hierarchy. " +
                "Current call chain: %s (cycle detected at depth %d). " +
                "Configure 'maxCallActivityRecursionDepth' in ProcessEngineConfiguration to adjust the limit.",
                targetProcessKey, buildCallChainString(callChain, targetProcessKey), depth));
        }
      }
      
      // Move to the parent execution
      currentExecution = currentExecution.getSuperExecution();
    }
    
    // Check if depth would exceed the limit after adding the new process
    if (depth >= maxDepth) {
      throw new ProcessEngineException(
        String.format("Call Activity recursion depth limit exceeded: Maximum depth is %d, current depth is %d. " +
            "Current call chain: %s -> %s. " +
            "Configure 'maxCallActivityRecursionDepth' in ProcessEngineConfiguration to adjust the limit.",
            maxDepth, depth, buildCallChainString(callChain, null), targetProcessKey));
    }
  }
  
  /**
   * Builds a string representation of the call chain for error messages.
   * 
   * @param callChain the list of process definition keys in the call chain
   * @param cyclePoint the process key where the cycle was detected, or null if no cycle
   * @return a formatted string like "processC -> processB -> processA" or with cycle indicator
   */
  private String buildCallChainString(List<String> callChain, String cyclePoint) {
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
    
    if (cyclePoint != null) {
      sb.append(" -> ").append(cyclePoint);
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
      ExecutionEntity calledProcessInstance = callActivityExecution.getSubProcessInstance();
      migratingInstance.addMigratingDependentInstance(new MigratingCalledProcessInstance(calledProcessInstance));
    }
  }

}
