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

import static org.cibseven.bpm.engine.impl.util.EnsureUtil.ensureNotNull;

import java.util.Collection;

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.exception.DeploymentResourceNotFoundException;
import org.cibseven.bpm.engine.form.CamundaFormRef;
import org.cibseven.bpm.engine.form.FormField;
import org.cibseven.bpm.engine.form.TaskFormData;
import org.cibseven.bpm.engine.impl.cfg.CommandChecker;
import org.cibseven.bpm.engine.impl.form.entity.CamundaFormDefinitionManager;
import org.cibseven.bpm.engine.impl.form.handler.DefaultFormHandler;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.CamundaFormDefinitionEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.ResourceEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.TaskEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.TaskManager;
import org.cibseven.bpm.engine.impl.task.TaskDefinition;
import org.cibseven.bpm.engine.variable.VariableMap;
import org.cibseven.bpm.engine.variable.impl.VariableMapImpl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * @author Daniel Meyer
 *
 */
public class GetTaskFormVariablesCmd extends AbstractGetFormVariablesCmd {

  private static final long serialVersionUID = 1L;

  public GetTaskFormVariablesCmd(String taskId, Collection<String> variableNames, boolean deserializeObjectValues, boolean localVariablesOnly) {
    super(taskId, variableNames, deserializeObjectValues, localVariablesOnly);
  }

  public VariableMap execute(CommandContext commandContext) {
    final TaskManager taskManager = commandContext.getTaskManager();
    TaskEntity task = taskManager.findTaskById(resourceId);

    ensureNotNull(BadUserRequestException.class, "Cannot find task with id '" + resourceId + "'.", "task", task);

    checkGetTaskFormVariables(task, commandContext);

    VariableMapImpl result = new VariableMapImpl();

    VariableMapImpl scopeVariables = new VariableMapImpl();
    task.collectVariables(scopeVariables, formVariableNames, false, deserializeObjectValues);

    // first, evaluate form fields
    TaskDefinition taskDefinition = task.getTaskDefinition();
    if (taskDefinition != null) {
      TaskFormData taskFormData = taskDefinition.getTaskFormHandler().createTaskForm(task);
      for (FormField formField : taskFormData.getFormFields()) {
        if(formVariableNames == null || formVariableNames.contains(formField.getId())) {
          if (localVariablesOnly) {
            result.put(formField.getId(), createExtendedVariable(formField, task));
          } else {
            result.put(formField.getId(), createVariable(formField, task));
          }
        }
      }
      if (localVariablesOnly && taskFormData.getCamundaFormRef() != null && taskFormData.getCamundaFormRef().getKey() != null) {

        CamundaFormRef camundaFormRef = taskFormData.getCamundaFormRef();
          String binding = camundaFormRef.getBinding();
          String formKkey = camundaFormRef.getKey();
          CamundaFormDefinitionEntity definition = null;
          CamundaFormDefinitionManager manager = commandContext.getCamundaFormDefinitionManager();
          if (binding.equals(DefaultFormHandler.FORM_REF_BINDING_DEPLOYMENT)) {
            definition = manager.findDefinitionByDeploymentAndKey(taskFormData.getDeploymentId(), formKkey);
          } else if (binding.equals(DefaultFormHandler.FORM_REF_BINDING_LATEST)) {
            definition = manager.findLatestDefinitionByKey(formKkey);
          } else if (binding.equals(DefaultFormHandler.FORM_REF_BINDING_VERSION)) {
            definition = manager.findDefinitionByKeyVersionAndTenantId(formKkey, camundaFormRef.getVersion(), null);
          } else {
            throw new BadUserRequestException("Unsupported binding type for camundaFormRef. Expected to be one of "
                + DefaultFormHandler.ALLOWED_FORM_REF_BINDINGS + " but was:" + binding);
          }
          ResourceEntity resource = commandContext
              .getResourceManager()
              .findResourceByDeploymentIdAndResourceName(taskFormData.getDeploymentId(), definition.getResourceName());
            ensureNotNull(DeploymentResourceNotFoundException.class, "no resource found with name '"
              + definition.getResourceName() + "' in deployment '" + taskFormData.getDeploymentId() + "'", "resource", resource);
            //resource.getBytes();
            JsonObject formJson = JsonParser.parseString(new String(resource.getBytes())).getAsJsonObject();
            if (formJson.has("components")) {
            JsonArray components = formJson.getAsJsonArray("components");
              for (JsonElement componentElement : components) {
                JsonObject component = componentElement.getAsJsonObject();
                if (component.has("key")) {
                  String key = component.get("key").getAsString();
                  if(formVariableNames == null || formVariableNames.contains(key)) {
                    result.put(key, createExtendedVariable(component, scopeVariables.get(key)));
                  }
                }
              }
            }
      }
      //TODO: if (localVariablesOnly) load HTML
    }

    // collect remaining variables from task scope and parent scopes
    if (!localVariablesOnly)
      result.putAll(scopeVariables);

    return result;
  }

  protected void checkGetTaskFormVariables(TaskEntity task, CommandContext commandContext) {
    for(CommandChecker checker : commandContext.getProcessEngineConfiguration().getCommandCheckers()) {
      checker.checkReadTaskVariable(task);
    }
  }
}
