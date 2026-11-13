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
package org.cibseven.bpm.engine.impl.cmd.batch;

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.authorization.BatchPermissions;
import org.cibseven.bpm.engine.batch.Batch;
import org.cibseven.bpm.engine.exception.NotFoundException;
import org.cibseven.bpm.engine.history.UserOperationLogEntry;
import org.cibseven.bpm.engine.impl.DeploymentQueryImpl;
import org.cibseven.bpm.engine.impl.batch.BatchConfiguration;
import org.cibseven.bpm.engine.impl.batch.DeploymentMapping;
import org.cibseven.bpm.engine.impl.batch.DeploymentMappings;
import org.cibseven.bpm.engine.impl.batch.builder.BatchBuilder;
import org.cibseven.bpm.engine.impl.batch.deletion.DeleteDeploymentBatchConfiguration;
import org.cibseven.bpm.engine.impl.interceptor.Command;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.DeploymentEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.PropertyChange;
import org.cibseven.bpm.engine.impl.util.CollectionUtil;
import org.cibseven.bpm.engine.repository.DeploymentQuery;
import org.cibseven.bpm.engine.repository.ProcessDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.cibseven.bpm.engine.impl.util.EnsureUtil.ensureNotEmpty;
import static org.cibseven.bpm.engine.impl.util.EnsureUtil.ensureNotNull;

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

    DeploymentMappings groups = groupByProcess(commandContext, ids);

    return new BatchBuilder(commandContext)
        .type(Batch.TYPE_DEPLOYMENT_DELETION)
        .config(getConfiguration(ids, groups))
        .totalJobs(groups.size())
        .permission(BatchPermissions.CREATE_BATCH_DELETE_DEPLOYMENTS)
        .permissionHandler(ctx -> checkDeleteDeploymentPermissions(ctx, ids))
        .operationLogHandler(this::writeUserOperationLog)
        .build();
  }

  protected void checkDeleteDeploymentPermissions(CommandContext commandContext, List<String> ids) {
    for (String deploymentId : ids) {
      commandContext.getProcessEngineConfiguration()
          .getCommandCheckers()
          .forEach(checker -> checker.checkDeleteDeployment(deploymentId));
    }
  }

  protected List<String> collectDeploymentIds(CommandContext commandContext) {
    Set<String> ids = new LinkedHashSet<>();

    if (!CollectionUtil.isEmpty(deploymentIds)) {
      for (String deploymentId : deploymentIds) {
        DeploymentEntity deployment = commandContext.getDeploymentManager().findDeploymentById(deploymentId);
        ensureNotNull(NotFoundException.class, "deployment " + deploymentId + " does not exist", "deployment", deployment);
        ids.add(deploymentId);
      }
    }

    DeploymentQueryImpl query = (DeploymentQueryImpl) this.deploymentQuery;
    if (query != null) {
      query.evaluateExpressionsAndExecuteList(commandContext, null)
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

  public BatchConfiguration getConfiguration(List<String> ids, DeploymentMappings groups) {
    // the mappings are groups without a deployment id on purpose, so that neither the seed jobs nor the execution
    // jobs are bound to a deployment (CIB7-2038). A deployment-aware job executor only acquires jobs of deployments
    // that are registered on its node. This batch deletes the deployments it works on, which also unregisters them,
    // and it must be able to delete deployments that are not registered on any node, as the synchronous deletion does.
    return new DeleteDeploymentBatchConfiguration(ids, groups, cascade, skipCustomListeners, skipIoMappings);
  }

  /**
   * Puts the deployments that hold versions of the same process (key and tenant) into one group, newest first, and
   * reorders the ids group by group (CIB7-2038). Deleting the latest version of a process moves its start events
   * (timers, message and signal subscriptions) to the previous version; two jobs that delete versions of one process
   * at the same time can leave them on a deleted version. One group is deleted by one job, one deployment after another.
   */
  protected DeploymentMappings groupByProcess(CommandContext commandContext, List<String> ids) {
    Map<String, String> groupOf = new HashMap<>();
    Map<String, String> deploymentOfProcess = new HashMap<>();
    Map<String, Integer> newestVersion = new HashMap<>();
    for (String id : ids) {
      groupOf.put(id, id);
      newestVersion.put(id, 0);
      for (ProcessDefinition definition : commandContext.getProcessDefinitionManager().findProcessDefinitionsByDeploymentId(id)) {
        newestVersion.merge(id, definition.getVersion(), Math::max);
        String other = deploymentOfProcess.putIfAbsent(definition.getKey() + '\u0000' + definition.getTenantId(), id);
        if (other != null) {
          groupOf.put(findGroup(groupOf, other), findGroup(groupOf, id));
        }
      }
    }

    Map<String, List<String>> groups = new LinkedHashMap<>();
    for (String id : ids) {
      groups.computeIfAbsent(findGroup(groupOf, id), group -> new ArrayList<>()).add(id);
    }

    Comparator<String> newestFirst = Comparator
        .comparing((String id) -> commandContext.getDeploymentManager().findDeploymentById(id).getDeploymentTime(),
            Comparator.nullsLast(Comparator.reverseOrder()))
        .thenComparing(newestVersion::get, Comparator.reverseOrder());
    DeploymentMappings mappings = new DeploymentMappings();
    ids.clear();
    for (List<String> group : groups.values()) {
      group.sort(newestFirst);
      ids.addAll(group);
      mappings.add(new DeploymentMapping(null, group.size()));
    }
    return mappings;
  }

  protected String findGroup(Map<String, String> groupOf, String id) {
    String group = id;
    while (!group.equals(groupOf.get(group))) {
      group = groupOf.get(group);
    }
    return group;
  }
}
