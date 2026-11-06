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
import org.cibseven.bpm.engine.repository.ProcessDefinition;
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
   * Throws a ProcessEngineException if a cycle is detected AND the cycle count limit would be exceeded.
   * 
   * A cycle occurs when the target process key already exists in the call hierarchy.
   * The limit counts how many times the same process appears in the chain (cycle iterations).
   * Non-cyclic call chains are allowed regardless of depth.
   * 
   * @param execution the current execution entity
   * @param targetDefinition the process definition being called
   * @throws ProcessEngineException if cycle iterations exceed the limit
   */
  protected void checkCallActivityRecursion(ExecutionEntity execution, ProcessDefinitionImpl targetDefinition) {
    int maxCycles = Context.getProcessEngineConfiguration().getMaxRecursiveCallIterations();
    
    // Skip check if disabled (0 or negative value)
    if (maxCycles <= 0) {
      return;
    }
    
    // Cast to ProcessDefinition to access getKey() method
    // This is safe because getProcessDefinitionToCall() returns ProcessDefinitionEntity at runtime
    // (from DeploymentCache), which extends ProcessDefinitionImpl and implements ProcessDefinition
    String targetProcessKey = ((ProcessDefinition) targetDefinition).getKey();
    List<String> callChain = new ArrayList<>();
    
    // Traverse up the execution chain to collect process definition keys
    // Always start from root execution: only process instances have superExecution links to parent processes
    ExecutionEntity currentExecution = execution.getProcessInstance();
    int cycleCount = 0;
    
    while (currentExecution != null) {
      String currentProcessKey = currentExecution.getProcessDefinitionKey();
      
      if (currentProcessKey != null) {
        callChain.add(currentProcessKey);
        
        // Count how many times the target process key appears in the call chain
        // This represents the number of times we've already entered this cycle
        if (currentProcessKey.equals(targetProcessKey)) {
          cycleCount++;
        }
      }
      
      // Traverse to parent's root: getSuperExecution() returns the execution that made the call
      // (might be a child execution), so we need getProcessInstance() to get its root for next traversal
      ExecutionEntity superExecution = currentExecution.getSuperExecution();
      currentExecution = (superExecution != null) ? superExecution.getProcessInstance() : null;
    }
    
    // Only enforce limit if a cycle is detected (cycleCount > 0)
    // This allows deep non-cyclic call chains while preventing infinite recursion from cycles
    if (cycleCount > 0 && cycleCount > maxCycles) {
      throw new ProcessEngineException(
        String.format("Call Activity recursion cycle detected: Process '%s' appears %d time(s) in the call chain. " +
            "Maximum allowed cycle iterations is %d. " +
            "Current call chain: %s. " +
            "Configure 'maxRecursiveCallIterations' in ProcessEngineConfiguration to adjust the limit.",
            targetProcessKey, cycleCount, maxCycles, buildCallChainString(callChain)));
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
      ExecutionEntity calledProcessInstance = callActivityExecution.getSubProcessInstance();
      migratingInstance.addMigratingDependentInstance(new MigratingCalledProcessInstance(calledProcessInstance));
    }
  }

}
