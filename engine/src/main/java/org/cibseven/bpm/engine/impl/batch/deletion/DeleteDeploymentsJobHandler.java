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
package org.cibseven.bpm.engine.impl.batch.deletion;

import java.util.List;
import org.cibseven.bpm.engine.batch.Batch;
import org.cibseven.bpm.engine.impl.batch.AbstractBatchJobHandler;
import org.cibseven.bpm.engine.impl.batch.BatchJobContext;
import org.cibseven.bpm.engine.impl.batch.BatchJobDeclaration;
import org.cibseven.bpm.engine.impl.cmd.DeleteDeploymentCmd;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.jobexecutor.JobDeclaration;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.MessageEntity;

public class DeleteDeploymentsJobHandler extends AbstractBatchJobHandler<DeleteDeploymentBatchConfiguration> {

  public static final BatchJobDeclaration JOB_DECLARATION = new BatchJobDeclaration(Batch.TYPE_DEPLOYMENT_DELETION);

  @Override
  public String getType() {
    return Batch.TYPE_DEPLOYMENT_DELETION;
  }

  @Override
  protected DeleteDeploymentBatchConfigurationJsonConverter getJsonConverterInstance() {
    return DeleteDeploymentBatchConfigurationJsonConverter.INSTANCE;
  }

  @Override
  public JobDeclaration<BatchJobContext, MessageEntity> getJobDeclaration() {
    return JOB_DECLARATION;
  }

  @Override
  protected DeleteDeploymentBatchConfiguration createJobConfiguration(DeleteDeploymentBatchConfiguration configuration, List<String> deploymentIds) {
    return new DeleteDeploymentBatchConfiguration(
        deploymentIds,
        configuration.isCascade(),
        configuration.isSkipCustomListeners(),
        configuration.isSkipIoMappings()
    );
  }

  @Override
  public void executeHandler(DeleteDeploymentBatchConfiguration batchConfiguration,
                             ExecutionEntity execution,
                             CommandContext commandContext,
                             String tenantId) {

    for (String deploymentId : batchConfiguration.getIds()) {
      commandContext.executeWithOperationLogPrevented(
          new DeleteDeploymentCmd(
              deploymentId,
              batchConfiguration.isCascade(),
              batchConfiguration.isSkipCustomListeners(),
              batchConfiguration.isSkipIoMappings()
          ));
    }
  }
}
