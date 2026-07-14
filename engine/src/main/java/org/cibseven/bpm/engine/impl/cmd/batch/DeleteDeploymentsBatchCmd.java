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
package org.cibseven.bpm.engine.impl.cmd.batch;

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.authorization.BatchPermissions;
import org.cibseven.bpm.engine.batch.Batch;
import org.cibseven.bpm.engine.history.UserOperationLogEntry;
import org.cibseven.bpm.engine.impl.DeploymentQueryImpl;
import org.cibseven.bpm.engine.impl.batch.BatchConfiguration;
import org.cibseven.bpm.engine.impl.batch.builder.BatchBuilder;
import org.cibseven.bpm.engine.impl.batch.deletion.DeleteDeploymentBatchConfiguration;
import org.cibseven.bpm.engine.impl.interceptor.Command;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.PropertyChange;
import org.cibseven.bpm.engine.impl.util.CollectionUtil;
import org.cibseven.bpm.engine.repository.DeploymentQuery;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.cibseven.bpm.engine.impl.util.EnsureUtil.ensureNotEmpty;

public class DeleteDeploymentsBatchCmd implements Command<Batch> {

  protected List<String> deploymentIds;
  protected DeploymentQuery deploymentQuery;
  protected boolean cascade;
  protected boolean skipCustomListeners;
  protected boolean skipIoMappings;

  public DeleteDeploymentsBatchCmd(List<String> deploymentIds,
                                   DeploymentQuery deploymentQuery,
                                   boolean cascade,
                                   boolean skipCustomListeners,
                                   boolean skipIoMappings) {
    this.deploymentIds = deploymentIds;
    this.deploymentQuery = deploymentQuery;
    this.cascade = cascade;
    this.skipCustomListeners = skipCustomListeners;
    this.skipIoMappings = skipIoMappings;
  }

  @Override
  public Batch execute(CommandContext commandContext) {
    List<String> ids = collectDeploymentIds(commandContext);

    ensureNotEmpty(BadUserRequestException.class, "deploymentIds", ids);

    return new BatchBuilder(commandContext)
        .type(Batch.TYPE_DEPLOYMENT_DELETION)
        .config(getConfiguration(ids))
        .permission(BatchPermissions.CREATE_BATCH_DELETE_DEPLOYMENTS)
        .operationLogHandler(this::writeUserOperationLog)
        .build();
  }

  protected List<String> collectDeploymentIds(CommandContext commandContext) {
    Set<String> ids = new LinkedHashSet<>();

    if (!CollectionUtil.isEmpty(deploymentIds)) {
      ids.addAll(deploymentIds);
    }

    DeploymentQueryImpl query = (DeploymentQueryImpl) this.deploymentQuery;
    if (query != null) {
      commandContext.runWithoutAuthorization(() -> query.evaluateExpressionsAndExecuteList(commandContext, null))
          .forEach(deployment -> ids.add(deployment.getId()));
    }

    return new ArrayList<>(ids);
  }

  protected void writeUserOperationLog(CommandContext commandContext, int numDeployments) {
    List<PropertyChange> propertyChanges = new ArrayList<>();
    propertyChanges.add(new PropertyChange("nrOfDeployments", null, numDeployments));
    propertyChanges.add(new PropertyChange("async", null, true));

    commandContext.getOperationLogManager()
        .logDeploymentOperation(UserOperationLogEntry.OPERATION_TYPE_DELETE,
            null,
            null,
            propertyChanges);
  }

  public BatchConfiguration getConfiguration(List<String> ids) {
    return new DeleteDeploymentBatchConfiguration(ids, cascade, skipCustomListeners, skipIoMappings);
  }
}
