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
package org.cibseven.bpm.container.impl.ejb.deployment;

import org.cibseven.bpm.container.ExecutorService;
import org.cibseven.bpm.container.impl.RuntimeContainerDelegateImpl;
import org.cibseven.bpm.container.impl.spi.DeploymentOperation;
import org.cibseven.bpm.container.impl.spi.DeploymentOperationStep;
import org.cibseven.bpm.container.impl.spi.PlatformServiceContainer;
import org.cibseven.bpm.container.impl.spi.ServiceTypes;

/**
 * <p>Deployment operation responsible for stopping a service which represents a Proxy to the
 * JCA-Backed {@link ExecutorService}</p>
 *
 * @author Daniel Meyer
 *
 */
public class StopJcaExecutorServiceStep extends DeploymentOperationStep {

  public String getName() {
    return "Stop JCA Executor Service";
  }

  public void performOperationStep(DeploymentOperation operationContext) {
    final PlatformServiceContainer serviceContainer = operationContext.getServiceContainer();

    serviceContainer.stopService(ServiceTypes.BPM_PLATFORM, RuntimeContainerDelegateImpl.SERVICE_NAME_EXECUTOR);

  }

}