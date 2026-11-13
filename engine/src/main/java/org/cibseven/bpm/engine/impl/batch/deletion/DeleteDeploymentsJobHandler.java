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
import org.cibseven.bpm.engine.impl.batch.BatchEntity;
import org.cibseven.bpm.engine.impl.batch.BatchJobContext;
import org.cibseven.bpm.engine.impl.batch.BatchJobDeclaration;
import org.cibseven.bpm.engine.impl.batch.DeploymentMappings;
import org.cibseven.bpm.engine.impl.cmd.DeleteDeploymentCmd;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.interceptor.CommandExecutor;
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

  /**
   * One job per group of deployments that hold versions of the same process (see
   * {@code DeleteDeploymentsBatchCmd}), so that versions of one process are never deleted by two jobs at the same
   * time (CIB7-2038). Batches stored before that have one mapping per deployment and are created as before.
   */
  @Override
  public boolean createJobs(BatchEntity batch) {
    DeleteDeploymentBatchConfiguration configuration = readConfiguration(batch.getConfigurationBytes());
    DeploymentMappings groups = configuration.getIdMappings();
    if (groups == null || groups.isEmpty() || groups.get(0).getDeploymentId() != null) {
      return super.createJobs(batch);
    }

    List<String> ids = configuration.getIds();
    for (int i = 0; i < batch.getBatchJobsPerSeed() && !groups.isEmpty(); i++) {
      List<String> group = groups.remove(0).getIds(ids);
      createJobEntities(batch, configuration, null, group, group.size());
    }

    batch.setConfigurationBytes(writeConfiguration(configuration));
    return groups.isEmpty();
  }

  @Override
  public void executeHandler(DeleteDeploymentBatchConfiguration batchConfiguration,
                             ExecutionEntity execution,
                             CommandContext commandContext,
                             String tenantId) {

    // Each deployment in a transaction of its own, newest first: deleting the latest version of a process moves its
    // start events to the previous version, and the next deletion must see that (CIB7-2038). A deployment that is
    // already gone, after a retry, is deleted again without effect.
    CommandExecutor newTransaction = commandContext.getProcessEngineConfiguration().getCommandExecutorTxRequiresNew();
    for (String deploymentId : batchConfiguration.getIds()) {
      DeleteDeploymentCmd deleteDeployment = new DeleteDeploymentCmd(
          deploymentId,
          batchConfiguration.isCascade(),
          batchConfiguration.isSkipCustomListeners(),
          batchConfiguration.isSkipIoMappings());
      newTransaction.execute(context -> {
        context.executeWithOperationLogPrevented(deleteDeployment);
        return null;
      });
    }
  }
}
