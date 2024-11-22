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
package org.cibseven.bpm.engine.impl.batch;

import static org.cibseven.bpm.engine.impl.util.EnsureUtil.ensureNotContainsNull;
import static org.cibseven.bpm.engine.impl.util.EnsureUtil.ensureNotEmpty;
import static org.cibseven.bpm.engine.impl.util.EnsureUtil.ensureNotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.authorization.BatchPermissions;
import org.cibseven.bpm.engine.batch.Batch;
import org.cibseven.bpm.engine.impl.ProcessEngineLogger;
import org.cibseven.bpm.engine.impl.RestartProcessInstanceBuilderImpl;
import org.cibseven.bpm.engine.impl.RestartProcessInstancesBatchConfiguration;
import org.cibseven.bpm.engine.impl.batch.builder.BatchBuilder;
import org.cibseven.bpm.engine.impl.cmd.AbstractProcessInstanceModificationCommand;
import org.cibseven.bpm.engine.impl.cmd.AbstractRestartProcessInstanceCmd;
import org.cibseven.bpm.engine.impl.cmd.CommandLogger;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.interceptor.CommandExecutor;
import org.cibseven.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;

/**
 *
 * @author Anna Pazola
 *
 */
public class RestartProcessInstancesBatchCmd extends AbstractRestartProcessInstanceCmd<Batch> {

  private final CommandLogger LOG = ProcessEngineLogger.CMD_LOGGER;

  public RestartProcessInstancesBatchCmd(CommandExecutor commandExecutor, RestartProcessInstanceBuilderImpl builder) {
    super(commandExecutor, builder);
  }

  @Override
  public Batch execute(CommandContext commandContext) {
    Collection<String> collectedInstanceIds = collectProcessInstanceIds();

    List<AbstractProcessInstanceModificationCommand> instructions = builder.getInstructions();
    ensureNotEmpty(BadUserRequestException.class, "Restart instructions cannot be empty",
        "instructions", instructions);
    ensureNotEmpty(BadUserRequestException.class, "Process instance ids cannot be empty",
        "processInstanceIds", collectedInstanceIds);
    ensureNotContainsNull(BadUserRequestException.class, "Process instance ids cannot be null",
        "processInstanceIds", collectedInstanceIds);

    String processDefinitionId = builder.getProcessDefinitionId();
    ProcessDefinitionEntity processDefinition =
        getProcessDefinition(commandContext, processDefinitionId);

    ensureNotNull(BadUserRequestException.class,
        "Process definition cannot be null", processDefinition);
    ensureTenantAuthorized(commandContext, processDefinition);

    String tenantId = processDefinition.getTenantId();

    return new BatchBuilder(commandContext)
        .type(Batch.TYPE_PROCESS_INSTANCE_RESTART)
        .config(getConfiguration(collectedInstanceIds, processDefinition.getDeploymentId()))
        .permission(BatchPermissions.CREATE_BATCH_RESTART_PROCESS_INSTANCES)
        .tenantId(tenantId)
        .operationLogHandler((ctx, instanceCount) ->
            writeUserOperationLog(ctx, processDefinition, instanceCount, true))
        .build();
  }

  protected void ensureTenantAuthorized(CommandContext commandContext, ProcessDefinitionEntity processDefinition) {
    if (!commandContext.getTenantManager().isAuthenticatedTenant(processDefinition.getTenantId())) {
      throw LOG.exceptionCommandWithUnauthorizedTenant("restart process instances of process definition '" + processDefinition.getId() + "'");
    }
  }

  public BatchConfiguration getConfiguration(Collection<String> instanceIds, String deploymentId) {
    return new RestartProcessInstancesBatchConfiguration(
        new ArrayList<>(instanceIds),
        DeploymentMappings.of(new DeploymentMapping(deploymentId, instanceIds.size())),
        builder.getInstructions(),
        builder.getProcessDefinitionId(),
        builder.isInitialVariables(),
        builder.isSkipCustomListeners(),
        builder.isSkipIoMappings(),
        builder.isWithoutBusinessKey());
  }

}
