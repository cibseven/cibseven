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
package org.cibseven.bpm.engine.rest.dto.runtime.batch;

import org.cibseven.bpm.engine.rest.dto.repository.DeploymentQueryDto;

import java.util.List;

public class DeleteDeploymentsDto {

  protected List<String> deploymentIds;
  protected DeploymentQueryDto deploymentQuery;
  protected boolean cascade;
  protected boolean skipCustomListeners;
  protected boolean skipIoMappings;

  public List<String> getDeploymentIds() {
    return deploymentIds;
  }

  public void setDeploymentIds(List<String> deploymentIds) {
    this.deploymentIds = deploymentIds;
  }

  public DeploymentQueryDto getDeploymentQuery() {
    return deploymentQuery;
  }

  public void setDeploymentQuery(DeploymentQueryDto deploymentQuery) {
    this.deploymentQuery = deploymentQuery;
  }

  public boolean isCascade() {
    return cascade;
  }

  public void setCascade(boolean cascade) {
    this.cascade = cascade;
  }

  public boolean isSkipCustomListeners() {
    return skipCustomListeners;
  }

  public void setSkipCustomListeners(boolean skipCustomListeners) {
    this.skipCustomListeners = skipCustomListeners;
  }

  public boolean isSkipIoMappings() {
    return skipIoMappings;
  }

  public void setSkipIoMappings(boolean skipIoMappings) {
    this.skipIoMappings = skipIoMappings;
  }

}
