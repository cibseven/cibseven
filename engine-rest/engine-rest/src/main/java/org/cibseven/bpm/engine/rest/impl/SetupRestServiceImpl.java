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
package org.cibseven.bpm.engine.rest.impl;

import static org.cibseven.bpm.engine.authorization.Authorization.ANY;
import static org.cibseven.bpm.engine.authorization.Authorization.AUTH_TYPE_GRANT;
import static org.cibseven.bpm.engine.authorization.Permissions.ALL;

import java.util.function.Supplier;

import javax.ws.rs.core.Response.Status;

import org.cibseven.bpm.engine.AuthorizationService;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.authorization.Groups;
import org.cibseven.bpm.engine.authorization.Resource;
import org.cibseven.bpm.engine.authorization.Resources;
import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.impl.identity.Authentication;
import org.cibseven.bpm.engine.impl.persistence.entity.AuthorizationEntity;
import org.cibseven.bpm.engine.rest.SetupRestService;
import org.cibseven.bpm.engine.rest.dto.identity.UserDto;
import org.cibseven.bpm.engine.rest.exception.InvalidRequestException;

import com.fasterxml.jackson.databind.ObjectMapper;

public class SetupRestServiceImpl extends AbstractRestProcessEngineAware  implements SetupRestService {

  public SetupRestServiceImpl(String processEngineName, ObjectMapper objectMapper) {
    super(processEngineName, objectMapper);
  }

  @Override
  public boolean requiresSetup() {
    if(getProcessEngine().getIdentityService().isReadOnly()) {
      return false;
    }

    Boolean isNoAdminGroup = runWithoutAuthorization(() -> {
        return hasNoCamundaAdminGroup();
    });
    return isNoAdminGroup;
  }

  private <V extends Object> V runWithoutAuthorization(Supplier<V> action) {
    IdentityService identityService = getProcessEngine().getIdentityService();
    Authentication currentAuthentication = identityService.getCurrentAuthentication();
    try {
      identityService.clearAuthentication();
      return action.get();
    } catch (Exception e) {
      throw e;
    } finally {
      identityService.setAuthentication(currentAuthentication);
    }
  }

  private boolean hasNoCamundaAdminGroup() {
    final IdentityService identityService = getProcessEngine().getIdentityService();
    long groupCount = identityService.createGroupQuery().groupId(Groups.CAMUNDA_ADMIN).count();
    return groupCount == 0;
  }

  @Override
  public void createUser(UserDto userDto) {
    if(getProcessEngine().getIdentityService().isReadOnly()) {
      throw new InvalidRequestException(Status.FORBIDDEN, "Identity service implementation is read-only.");
    }

    if (!requiresSetup())  {
      throw new InvalidRequestException(Status.FORBIDDEN, "User creation not allowed - already initialized.");
    }

    runWithoutAuthorization(() -> {
      createUserImpl(userDto);
      return true;
    });
  }

  private void createUserImpl(UserDto userDto) {
    UserRestServiceImpl userRestServiceImpl = new UserRestServiceImpl(getProcessEngine().getName(), getObjectMapper());
    userRestServiceImpl.createUser(userDto);
    ensureCamundaAdminGroupExists();
    getProcessEngine().getIdentityService().createMembership(userDto.getProfile().getId(), Groups.CAMUNDA_ADMIN);
  }

  private void ensureCamundaAdminGroupExists() {
    ProcessEngine processEngine = getProcessEngine();
    final IdentityService identityService = processEngine.getIdentityService();
    final AuthorizationService authorizationService = processEngine.getAuthorizationService();

    // create group
    long groupCount = identityService.createGroupQuery().groupId(Groups.CAMUNDA_ADMIN).count();
    if(groupCount == 0) {
      Group camundaAdminGroup = identityService.newGroup(Groups.CAMUNDA_ADMIN);
      camundaAdminGroup.setName("camunda BPM Administrators");
      camundaAdminGroup.setType(Groups.GROUP_TYPE_SYSTEM);
      identityService.saveGroup(camundaAdminGroup);
    }

    // create ADMIN authorizations on all built-in resources
    for (Resource resource : Resources.values()) {
      if(authorizationService.createAuthorizationQuery().groupIdIn(Groups.CAMUNDA_ADMIN).resourceType(resource).resourceId(ANY).count() == 0) {
        AuthorizationEntity userAdminAuth = new AuthorizationEntity(AUTH_TYPE_GRANT);
        userAdminAuth.setGroupId(Groups.CAMUNDA_ADMIN);
        userAdminAuth.setResource(resource);
        userAdminAuth.setResourceId(ANY);
        userAdminAuth.addPermission(ALL);
        authorizationService.saveAuthorization(userAdminAuth);
      }
    }

  }
}
