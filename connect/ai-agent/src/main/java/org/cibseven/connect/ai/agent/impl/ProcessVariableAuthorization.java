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

import java.util.Collection;
import java.util.Collections;
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
 * Any one of these grants suffices, to the caller or to one of their groups:
 * <ul>
 *   <li>with {@code enforceSpecificVariablePermission}: {@code READ_INSTANCE_VARIABLE}
 *       or {@code READ_TASK_VARIABLE} on the process definition;</li>
 *   <li>otherwise: {@code READ} on the process instance, or {@code READ_INSTANCE}
 *       or {@code READ_TASK} on the process definition.</li>
 * </ul>
 * Grants on a single task do not count: they are gone once the task completes,
 * which is exactly when an agent task after it runs. Admin membership does not
 * replace a grant — {@code AuthorizationManager.isAuthCheckExecuted} does not
 * waive the check for admins either — but it does skip the tenant check.
 *
 * <h3>A deliberate trade-off: READ_TASK and READ_TASK_VARIABLE</h3>
 * The engine lets {@code READ_TASK} — or {@code READ_TASK_VARIABLE} with
 * {@code enforceSpecificVariablePermission} — read variables only through an
 * existing task, in that task's scope chain ({@code TaskService.getVariables});
 * a variable query does not accept it. Here it admits the whole instance. In the
 * main case — an agent task right after a user task, reading process-level
 * variables — the two are equivalent: the user who completed the task could
 * read exactly those. They differ in two edge cases, where this check lets a
 * {@code READ_TASK}- or {@code READ_TASK_VARIABLE}-only user through although
 * the engine would not: an
 * instance that has no user task at all, and variables in a scope no task sees
 * (a subprocess, a multi-instance body, the agent task's own input mapping).
 * Requiring an open task instead would break the main case, because the task
 * just completed no longer exists.
 *
 * <h3>Who the caller is</h3>
 * The user authenticated on the thread, when there is one: for a synchronous
 * run, and for a job a user executes by hand ({@code ManagementService.executeJob},
 * REST {@code POST /job/{id}/execute}). As in the engine's {@code TenantManager},
 * their tenant list is the authentication's own.
 *
 * <p>Otherwise — a job run by the job executor, which authenticates a job of a
 * tenant only as {@code (null, null, [tenantId])} — the start user of the
 * process instance, as a {@link StartUserAuthentication}. That is not
 * necessarily whoever led to the job. Its tenant list merely copies the
 * instance's tenant, so memberships are read from the identity service instead.
 * The marker survives {@code ProcessStarterTool}, so an agent task in a child
 * process it starts does not mistake the start user for a login.
 *
 * <p>Limitation, deliberately stricter than the engine: identity providers whose
 * tenant queries return nothing — LDAP, and OAuth2 with tenants — make the
 * tenant check of a job-executor run fail for every instance with a tenant. For
 * such setups, run the agent task synchronously or without a tenant.
 *
 * <h3>When nothing is checked, and what is checked without authorization</h3>
 * Without an identifiable caller — no authenticated user and no start user — the
 * variables are read unchecked; this is logged at WARN and recorded as
 * {@link Outcome#NO_CALLER}. The tenant check depends only on the tenant-check
 * settings, as in the engine, so it applies even when authorization is off.
 */
final class ProcessVariableAuthorization {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessVariableAuthorization.class);

  /** How the check concluded; written verbatim into the audit events. */
  enum Outcome {
    /** The caller holds one of the permissions (and passed the tenant check, if any). */
    GRANTED,
    /** Authorization is disabled; the caller passed the tenant check. */
    TENANT_CHECKED,
    /**
     * Authorization is disabled and no tenant check applied — none was due, or
     * the caller is an admin, who skips it: nothing was checked.
     */
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
    // Same switches as TenantManager.isTenantCheckEnabled.
    boolean tenantCheck = tenantId != null && configuration.isTenantCheckEnabled()
        && commandContext.isTenantCheckEnabled();
    if (!authorizationEnabled && !tenantCheck) {
      return Outcome.AUTHORIZATION_DISABLED;
    }
    if (caller == null || caller.getUserId() == null) {
      LOG.warn("Process instance '{}': no user is authenticated and the instance has no start "
          + "user, so the read permission for 'contextVariables'/'documents' cannot be checked. "
          + "The variables are read unchecked.", execution.getProcessInstanceId());
      return Outcome.NO_CALLER;
    }
    AuthorizationManager authorizationManager = commandContext.getAuthorizationManager();

    boolean tenantChecked = tenantCheck && !authorizationManager.isCamundaAdmin(caller);
    if (tenantChecked) {
      Collection<String> tenantIds = (caller instanceof StartUserAuthentication)
          ? tenantMemberships(commandContext, caller)
          : nullToEmpty(caller.getTenantIds());
      if (!tenantIds.contains(tenantId)) {
        throw new AgentConnectorException("User '" + caller.getUserId() + "' may not read the "
            + "variables of process instance '" + execution.getProcessInstanceId()
            + "': it belongs to tenant '" + tenantId + "', which the user is not a member of. "
            + "'contextVariables' and 'documents' are only read with the caller's permissions.");
      }
    }
    if (!authorizationEnabled) {
      return tenantChecked ? Outcome.TENANT_CHECKED : Outcome.AUTHORIZATION_DISABLED;
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

  private static Collection<String> nullToEmpty(Collection<String> tenantIds) {
    return (tenantIds == null) ? Collections.emptySet() : tenantIds;
  }

  /**
   * The tenants a derived caller is a member of, directly or through a group, as
   * the identity service records them. Read without authorization: whether the
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
