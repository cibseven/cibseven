/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.cibseven.connect.ai.agent.impl;

import static org.cibseven.bpm.engine.authorization.Permissions.READ;
import static org.cibseven.bpm.engine.authorization.Permissions.READ_INSTANCE;
import static org.cibseven.bpm.engine.authorization.ProcessDefinitionPermissions.READ_INSTANCE_VARIABLE;
import static org.cibseven.bpm.engine.authorization.ProcessDefinitionPermissions.READ_TASK;
import static org.cibseven.bpm.engine.authorization.ProcessDefinitionPermissions.READ_TASK_VARIABLE;
import static org.cibseven.bpm.engine.authorization.Resources.PROCESS_DEFINITION;
import static org.cibseven.bpm.engine.authorization.Resources.PROCESS_INSTANCE;

import java.util.Set;
import java.util.TreeSet;

import org.cibseven.bpm.engine.identity.Tenant;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.db.CompositePermissionCheck;
import org.cibseven.bpm.engine.impl.db.PermissionCheckBuilder;
import org.cibseven.bpm.engine.impl.identity.Authentication;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.AuthorizationManager;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks that the caller of an agent task may read the variables of the process
 * instance it runs in, before {@code contextVariables} or {@code documents}
 * read them off the execution.
 *
 * <h3>Why the connector checks at all</h3>
 * The connector reads with {@code getVariableTyped(name, false)} straight off
 * the {@link ExecutionEntity}. The engine does not check delegate code either:
 * with the default {@code authorizationEnabledForCustomCode=false} any
 * JavaDelegate or connector reads every variable of its process instance
 * unchecked. This class is therefore deliberately <em>stricter than the
 * engine</em>, not a copy of it: the agent passes process data on to an external
 * model, so it should pass on only what the triggering user could read anyway.
 *
 * <h3>The rule</h3>
 * Any one of the ways the engine's API lets a user read these variables
 * suffices — through the process instance or through its user tasks, since in
 * the Tasklist the user who completes a task sees exactly these variables:
 * <ul>
 *   <li>with {@code enforceSpecificVariablePermission}: {@code READ_INSTANCE_VARIABLE}
 *       or {@code READ_TASK_VARIABLE} on the process definition;</li>
 *   <li>otherwise: {@code READ} on the process instance, or {@code READ_INSTANCE}
 *       or {@code READ_TASK} on the process definition.</li>
 * </ul>
 * Grants to any of the caller's groups count. Admin membership does not replace
 * a grant — {@code AuthorizationManager.isAuthCheckExecuted} does not waive the
 * check for admins either — but it does skip the tenant check, as in
 * {@code TenantManager}.
 *
 * <h3>Who the caller is</h3>
 * The caller captured by {@link AgentConnectorImpl}: the authenticated user when
 * the task runs synchronously, otherwise — for a job — the start user of the
 * process instance, who is not necessarily the user whose action led to the job.
 * Tenant membership is read from the identity service rather than from the
 * {@link Authentication}, so a caller derived from the process instance cannot
 * pass the tenant check merely because the instance's tenant was copied into it.
 *
 * <p>Without an identifiable caller nothing can be checked; the variables are
 * then read as before, which is logged at WARN and recorded as the outcome. The
 * tenant check applies whenever tenant checks are enabled, independently of
 * whether authorization is.
 */
final class ProcessVariableAuthorization {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessVariableAuthorization.class);

  /** How the check concluded; written verbatim into the audit events. */
  enum Outcome {
    /** The caller holds one of the permissions. */
    GRANTED,
    /** Authorization is disabled on the engine; the tenant check, if any, passed. */
    AUTHORIZATION_DISABLED,
    /** No caller could be identified, so nothing could be checked. */
    NO_CALLER
  }

  private ProcessVariableAuthorization() {
  }

  /**
   * @throws AgentConnectorException when the caller lacks the permission to read
   *     the variables of {@code execution}'s process instance, or is not a member
   *     of its tenant. The message names the user and what is missing.
   */
  static Outcome check(ExecutionEntity execution, Authentication caller) {
    CommandContext commandContext = Context.getCommandContext();
    ProcessEngineConfigurationImpl configuration = commandContext.getProcessEngineConfiguration();
    String tenantId = execution.getTenantId();
    boolean authorizationEnabled = configuration.isAuthorizationEnabled();
    boolean tenantCheck = tenantId != null && configuration.isTenantCheckEnabled();
    if (!authorizationEnabled && !tenantCheck) {
      return Outcome.AUTHORIZATION_DISABLED;
    }
    if (caller == null || caller.getUserId() == null) {
      LOG.warn("Process instance '{}': no authenticated user and no start user, so the read "
          + "permission for 'contextVariables'/'documents' cannot be checked. The variables are "
          + "read unchecked.", execution.getProcessInstanceId());
      return Outcome.NO_CALLER;
    }
    AuthorizationManager authorizationManager = commandContext.getAuthorizationManager();

    if (tenantCheck && !authorizationManager.isCamundaAdmin(caller)
        && !tenantMemberships(commandContext, caller).contains(tenantId)) {
      throw new AgentConnectorException("User '" + caller.getUserId() + "' may not read the "
          + "variables of process instance '" + execution.getProcessInstanceId()
          + "': it belongs to tenant '" + tenantId + "', which the user is not a member of. "
          + "'contextVariables' and 'documents' are only read with the caller's permissions.");
    }
    if (!authorizationEnabled) {
      return Outcome.AUTHORIZATION_DISABLED;
    }

    String definitionKey = execution.getProcessDefinition().getKey();
    String processInstanceId = execution.getProcessInstanceId();
    boolean specific = authorizationManager.isEnsureSpecificVariablePermission();
    CompositePermissionCheck permissionCheck = specific
        ? new PermissionCheckBuilder()
            .disjunctive()
            .atomicCheckForResourceId(PROCESS_DEFINITION, definitionKey, READ_INSTANCE_VARIABLE)
            .atomicCheckForResourceId(PROCESS_DEFINITION, definitionKey, READ_TASK_VARIABLE)
            .build()
        : new PermissionCheckBuilder()
            .disjunctive()
            .atomicCheckForResourceId(PROCESS_INSTANCE, processInstanceId, READ)
            .atomicCheckForResourceId(PROCESS_DEFINITION, definitionKey, READ_INSTANCE)
            .atomicCheckForResourceId(PROCESS_DEFINITION, definitionKey, READ_TASK)
            .build();

    if (!authorizationManager.isAuthorized(caller.getUserId(), caller.getGroupIds(),
        permissionCheck)) {
      String required = specific
          ? "READ_INSTANCE_VARIABLE or READ_TASK_VARIABLE on process definition '"
            + definitionKey + "'"
          : "READ on process instance '" + processInstanceId + "', or READ_INSTANCE or "
            + "READ_TASK on process definition '" + definitionKey + "'";
      throw new AgentConnectorException("User '" + caller.getUserId() + "' may not read the "
          + "variables of process instance '" + processInstanceId + "', which 'contextVariables' "
          + "and 'documents' would pass to the model. Required: " + required + ".");
    }
    return Outcome.GRANTED;
  }

  /**
   * The tenants the caller is a member of, directly or through a group, as the
   * identity service records them. Read without authorization: whether the
   * caller may list tenants has nothing to do with whether they belong to one.
   */
  private static Set<String> tenantMemberships(CommandContext commandContext,
      Authentication caller) {
    return commandContext.runWithoutAuthorization(() -> {
      Set<String> tenantIds = new TreeSet<>();
      for (Tenant tenant : commandContext.getProcessEngineConfiguration().getIdentityService()
          .createTenantQuery()
          .userMember(caller.getUserId())
          .includingGroupsOfUser(true)
          .list()) {
        tenantIds.add(tenant.getId());
      }
      return tenantIds;
    });
  }
}
