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
package org.cibseven.bpm.container.impl.deployment.jobexecutor;

import org.cibseven.bpm.container.impl.deployment.Attachments;
import org.cibseven.bpm.container.impl.metadata.spi.BpmPlatformXml;
import org.cibseven.bpm.container.impl.metadata.spi.JobAcquisitionXml;
import org.cibseven.bpm.container.impl.metadata.spi.JobExecutorXml;
import org.cibseven.bpm.container.impl.spi.DeploymentOperation;
import org.cibseven.bpm.container.impl.spi.DeploymentOperationStep;

/**
 * <p>Deployment operation step responsible for starting the JobExecutor</p>
 * 
 * @author Daniel Meyer
 * 
 */
public class StartJobExecutorStep extends DeploymentOperationStep {

  public String getName() {
    return "Starting the Managed Job Executor";
  }

  public void performOperationStep(DeploymentOperation operationContext) {

    final JobExecutorXml jobExecutorXml = getJobExecutorXml(operationContext);
    
    // add a deployment operation step for each job acquisition
    for (JobAcquisitionXml jobAcquisitionXml : jobExecutorXml.getJobAcquisitions()) {      
      operationContext.addStep(new StartJobAcquisitionStep(jobAcquisitionXml));                  
    }
    
  }

  private JobExecutorXml getJobExecutorXml(DeploymentOperation operationContext) {
    BpmPlatformXml bpmPlatformXml = operationContext.getAttachment(Attachments.BPM_PLATFORM_XML);
    JobExecutorXml jobExecutorXml = bpmPlatformXml.getJobExecutor();
    return jobExecutorXml;
  }
  
  

}
