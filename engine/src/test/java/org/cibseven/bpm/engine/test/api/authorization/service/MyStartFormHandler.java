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
package org.cibseven.bpm.engine.test.api.authorization.service;

import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.delegate.VariableScope;
import org.cibseven.bpm.engine.form.StartFormData;
import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParse;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.form.StartFormDataImpl;
import org.cibseven.bpm.engine.impl.form.handler.StartFormHandler;
import org.cibseven.bpm.engine.impl.persistence.entity.DeploymentEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.cibseven.bpm.engine.impl.util.xml.Element;
import org.cibseven.bpm.engine.variable.VariableMap;

/**
 * @author Roman Smirnov
 *
 */
public class MyStartFormHandler extends MyDelegationService implements StartFormHandler {

  public void parseConfiguration(Element activityElement, DeploymentEntity deployment, ProcessDefinitionEntity processDefinition, BpmnParse bpmnParse) {
    // do nothing
  }

  public void submitFormVariables(VariableMap properties, VariableScope variableScope) {
    ProcessEngineConfigurationImpl processEngineConfiguration = Context.getProcessEngineConfiguration();
    IdentityService identityService = processEngineConfiguration.getIdentityService();
    RuntimeService runtimeService = processEngineConfiguration.getRuntimeService();

    logAuthentication(identityService);
    logInstancesCount(runtimeService);
  }

  public StartFormData createStartFormData(ProcessDefinitionEntity processDefinition) {
    ProcessEngineConfigurationImpl processEngineConfiguration = Context.getProcessEngineConfiguration();
    IdentityService identityService = processEngineConfiguration.getIdentityService();
    RuntimeService runtimeService = processEngineConfiguration.getRuntimeService();

    logAuthentication(identityService);
    logInstancesCount(runtimeService);

    StartFormDataImpl result = new StartFormDataImpl();
    result.setProcessDefinition(processDefinition);
    return result;
  }

}
