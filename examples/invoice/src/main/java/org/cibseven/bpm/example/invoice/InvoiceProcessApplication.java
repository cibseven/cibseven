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
package org.cibseven.bpm.example.invoice;

import org.cibseven.bpm.BpmPlatform;
import org.cibseven.bpm.application.PostDeploy;
import org.cibseven.bpm.application.ProcessApplication;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.repository.DeploymentBuilder;

/**
 * Process Application exposing this application's resources the process engine.
 */
@ProcessApplication(name = "InvoiceProcessApplication")
// Using fully-qualified class name instead of import statement to allow for automatic Jakarta transformation
public class InvoiceProcessApplication extends org.cibseven.bpm.application.impl.ServletProcessApplication {

  /**
   * In a @PostDeploy Hook you can interact with the process engine and access
   * the processes the application has deployed.
   */
  @PostDeploy
  public void startFirstProcess(ProcessEngine processEngine) {
    InvoiceApplicationHelper.startFirstProcess(processEngine);
  }

  @Override
  public void createDeployment(String processArchiveName, DeploymentBuilder deploymentBuilder) {
    ProcessEngine processEngine = BpmPlatform.getProcessEngineService().getProcessEngine("default");
    InvoiceApplicationHelper.createDeployment(processArchiveName, processEngine, getProcessApplicationClassloader(), getReference());
  }
}
