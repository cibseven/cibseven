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
package org.cibseven.bpm.engine.impl.form.handler;

import org.cibseven.bpm.engine.delegate.Expression;
import org.cibseven.bpm.engine.form.StartFormData;
import org.cibseven.bpm.engine.impl.form.CamundaFormRefImpl;
import org.cibseven.bpm.engine.impl.form.FormDefinition;
import org.cibseven.bpm.engine.impl.form.StartFormDataImpl;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.cibseven.bpm.engine.variable.VariableMap;


/**
 * @author Tom Baeyens
 */
public class DefaultStartFormHandler extends DefaultFormHandler implements StartFormHandler {

  public StartFormData createStartFormData(ProcessDefinitionEntity processDefinition) {
    StartFormDataImpl startFormData = new StartFormDataImpl();

    FormDefinition startFormDefinition = processDefinition.getStartFormDefinition();
    Expression formKey = startFormDefinition.getFormKey();
    Expression camundaFormDefinitionKey = startFormDefinition.getCamundaFormDefinitionKey();
    String camundaFormDefinitionBinding = startFormDefinition.getCamundaFormDefinitionBinding();
    Expression camundaFormDefinitionVersion = startFormDefinition.getCamundaFormDefinitionVersion();

    if (formKey != null) {
      startFormData.setFormKey(formKey.getExpressionText());
    } else if (camundaFormDefinitionKey != null && camundaFormDefinitionBinding != null) {
      CamundaFormRefImpl ref = new CamundaFormRefImpl(camundaFormDefinitionKey.getExpressionText(), camundaFormDefinitionBinding);
      if (camundaFormDefinitionBinding.equals(FORM_REF_BINDING_VERSION) && camundaFormDefinitionVersion != null) {
        ref.setVersion(Integer.parseInt(camundaFormDefinitionVersion.getExpressionText()));
      }
      startFormData.setCamundaFormRef(ref);
    }

    startFormData.setDeploymentId(deploymentId);
    startFormData.setProcessDefinition(processDefinition);
    initializeFormProperties(startFormData, null);
    initializeFormFields(startFormData, null);
    return startFormData;
  }

  public ExecutionEntity submitStartFormData(ExecutionEntity processInstance, VariableMap properties) {
    submitFormVariables(properties, processInstance);
    return processInstance;
  }
}
