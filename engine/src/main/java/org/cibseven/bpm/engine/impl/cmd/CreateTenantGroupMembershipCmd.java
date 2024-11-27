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
package org.cibseven.bpm.engine.impl.cmd;

import static org.cibseven.bpm.engine.impl.util.EnsureUtil.ensureNotNull;

import java.io.Serializable;

import org.cibseven.bpm.engine.history.UserOperationLogEntry;
import org.cibseven.bpm.engine.impl.identity.IdentityOperationResult;
import org.cibseven.bpm.engine.impl.interceptor.Command;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;

public class CreateTenantGroupMembershipCmd extends AbstractWritableIdentityServiceCmd<Void> implements Command<Void>, Serializable {

  private static final long serialVersionUID = 1L;

  protected final String tenantId;
  protected final String groupId;

  public CreateTenantGroupMembershipCmd(String tenantId, String groupId) {
    this.tenantId = tenantId;
    this.groupId = groupId;
  }

  @Override
  protected Void executeCmd(CommandContext commandContext) {
    ensureNotNull("tenantId", tenantId);
    ensureNotNull("groupId", groupId);

    IdentityOperationResult operationResult = commandContext
      .getWritableIdentityProvider()
      .createTenantGroupMembership(tenantId, groupId);
    
    commandContext.getOperationLogManager().logMembershipOperation(operationResult, null, groupId, tenantId);

    return null;
  }

}