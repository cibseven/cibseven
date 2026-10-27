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
import static org.cibseven.bpm.engine.authorization.Resources.PROCESS_DEFINITION;
import static org.cibseven.bpm.engine.authorization.Resources.PROCESS_INSTANCE;

import java.util.List;

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
 * <p>The connector reads with {@code getVariableTyped(name, false)} straight off
 * the {@link ExecutionEntity}, which performs no authorization check at all —
 * and inside a job the engine runs without an authenticated user anyway. Without
 * this check, an agent task would hand the model data the triggering principal
 * could not read through the API. The rule applied is the engine's own for
 * reading process-instance variables ({@code checkReadProcessInstanceVariable}):
 * <ul>
 *   <li>with {@code enforceSpecificVariablePermission}: {@code READ_INSTANCE_VARIABLE}
 *       on the process definition;</li>
 *   <li>otherwise: {@code READ} on the process instance, or {@code READ_INSTANCE}
 *       on the process definition;</li>
 *   <li>plus the tenant check, when the instance belongs to a tenant.</li>
 * </ul>
 * It is evaluated for the caller captured by {@link AgentConnectorImpl} — the
 * authenticated user, or else the start user of the process instance — not for
 * whoever happens to be authenticated on the job thread.
 *
 * <p>As in the engine, a call without an identifiable caller is not checked, and
 * admin membership skips only the tenant check — not the permission check,
 * which {@code AuthorizationManager.isAuthCheckExecuted} does not waive for
 * admins either; they read through their grants like everyone else. The outcome
 * is returned so it can be recorded in the audit trail; the unchecked case is
 * also logged at WARN.
 */
final class ProcessVariableAuthorization {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessVariableAuthorization.class);

  /** How the check concluded; written verbatim into the audit events. */
  enum Outcome {
    /** The caller holds the permissions. */
    GRANTED,
    /** Authorization is disabled on the engine. */
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
    if (!configuration.isAuthorizationEnabled()) {
      return Outcome.AUTHORIZATION_DISABLED;
    }
    if (caller == null || caller.getUserId() == null) {
      LOG.warn("Process instance '{}': no authenticated user and no start user, so the read "
          + "permission for 'contextVariables'/'documents' cannot be checked. The variables are "
          + "read unchecked, as the engine itself does for unauthenticated calls.",
          execution.getProcessInstanceId());
      return Outcome.NO_CALLER;
    }
    AuthorizationManager authorizationManager = commandContext.getAuthorizationManager();

    // Same conditions as TenantManager.isAuthenticatedTenant, evaluated for the
    // caller instead of the thread's authentication.
    String tenantId = execution.getTenantId();
    if (tenantId != null && configuration.isTenantCheckEnabled()
        && !authorizationManager.isCamundaAdmin(caller)) {
      List<String> tenantIds = caller.getTenantIds();
      if (tenantIds == null || !tenantIds.contains(tenantId)) {
        throw new AgentConnectorException("User '" + caller.getUserId() + "' may not read the "
            + "variables of process instance '" + execution.getProcessInstanceId()
            + "': it belongs to tenant '" + tenantId + "', which the user is not a member of. "
            + "'contextVariables' and 'documents' are only read with the caller's permissions.");
      }
    }

    String definitionKey = execution.getProcessDefinition().getKey();
    String processInstanceId = execution.getProcessInstanceId();
    boolean specific = authorizationManager.isEnsureSpecificVariablePermission();
    CompositePermissionCheck permissionCheck = specific
        ? new PermissionCheckBuilder()
            .disjunctive()
            .atomicCheckForResourceId(PROCESS_DEFINITION, definitionKey, READ_INSTANCE_VARIABLE)
            .build()
        : new PermissionCheckBuilder()
            .disjunctive()
            .atomicCheckForResourceId(PROCESS_INSTANCE, processInstanceId, READ)
            .atomicCheckForResourceId(PROCESS_DEFINITION, definitionKey, READ_INSTANCE)
            .build();

    if (!authorizationManager.isAuthorized(caller.getUserId(), caller.getGroupIds(),
        permissionCheck)) {
      String required = specific
          ? "READ_INSTANCE_VARIABLE on process definition '" + definitionKey + "'"
          : "READ on process instance '" + processInstanceId + "' or READ_INSTANCE on process "
            + "definition '" + definitionKey + "'";
      throw new AgentConnectorException("User '" + caller.getUserId() + "' may not read the "
          + "variables of process instance '" + processInstanceId + "', which 'contextVariables' "
          + "and 'documents' would pass to the model. Required: " + required + ".");
    }
    return Outcome.GRANTED;
  }
}
