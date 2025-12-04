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
package org.cibseven.bpm.engine.rest.impl;

import static org.cibseven.bpm.engine.authorization.Authorization.ANY;
import static org.cibseven.bpm.engine.authorization.Authorization.AUTH_TYPE_GRANT;
import static org.cibseven.bpm.engine.authorization.Permissions.ALL;
import static org.cibseven.bpm.engine.authorization.Permissions.CREATE;
import static org.cibseven.bpm.engine.authorization.Resources.USER;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.List;
import java.util.function.Supplier;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import org.cibseven.bpm.engine.AuthorizationService;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.authorization.Groups;
import org.cibseven.bpm.engine.authorization.Resource;
import org.cibseven.bpm.engine.authorization.Resources;
import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.User;
import org.cibseven.bpm.engine.identity.UserQuery;
import org.cibseven.bpm.engine.impl.identity.Authentication;
import org.cibseven.bpm.engine.impl.persistence.entity.AuthorizationEntity;
import org.cibseven.bpm.engine.rest.UserRestService;
import org.cibseven.bpm.engine.rest.dto.CountResultDto;
import org.cibseven.bpm.engine.rest.dto.ResourceOptionsDto;
import org.cibseven.bpm.engine.rest.dto.identity.UserDto;
import org.cibseven.bpm.engine.rest.dto.identity.UserProfileDto;
import org.cibseven.bpm.engine.rest.dto.identity.UserQueryDto;
import org.cibseven.bpm.engine.rest.exception.InvalidRequestException;
import org.cibseven.bpm.engine.rest.sub.identity.UserResource;
import org.cibseven.bpm.engine.rest.sub.identity.impl.UserResourceImpl;
import org.cibseven.bpm.engine.rest.util.PathUtil;
import org.cibseven.bpm.engine.rest.util.QueryUtil;

/**
 * @author Daniel Meyer
 *
 */
public class UserRestServiceImpl extends AbstractAuthorizedRestResource implements UserRestService {

  public UserRestServiceImpl(String engineName, ObjectMapper objectMapper) {
    super(engineName, USER, ANY, objectMapper);
  }

  @Override
  public UserResource getUser(String id) {
    id = PathUtil.decodePathParam(id);
    return new UserResourceImpl(getProcessEngine().getName(), id, relativeRootResourcePath, getObjectMapper());
  }

  @Override
  public List<UserProfileDto> queryUsers(UriInfo uriInfo, Integer firstResult, Integer maxResults) {
    UserQueryDto queryDto = new UserQueryDto(getObjectMapper(), uriInfo.getQueryParameters());
    return queryUsers(queryDto, firstResult, maxResults);
  }

  public List<UserProfileDto> queryUsers(UserQueryDto queryDto, Integer firstResult, Integer maxResults) {

    queryDto.setObjectMapper(getObjectMapper());
    UserQuery query = queryDto.toQuery(getProcessEngine());

    List<User> resultList = QueryUtil.list(query, firstResult, maxResults);

    return UserProfileDto.fromUserList(resultList);
  }


  @Override
  public CountResultDto getUserCount(UriInfo uriInfo) {
    UserQueryDto queryDto = new UserQueryDto(getObjectMapper(), uriInfo.getQueryParameters());
    return getUserCount(queryDto);
  }

  protected CountResultDto getUserCount(UserQueryDto queryDto) {
    UserQuery query = queryDto.toQuery(getProcessEngine());
    long count = query.count();
    return new CountResultDto(count);
  }

  @Override
  public void createUser(UserDto userDto) {
    final IdentityService identityService = getIdentityService();

    if(identityService.isReadOnly()) {
      throw new InvalidRequestException(Status.FORBIDDEN, "Identity service implementation is read-only.");
    }

    UserProfileDto profile = userDto.getProfile();
    if(profile == null || profile.getId() == null) {
      throw new InvalidRequestException(Status.BAD_REQUEST, "request object must provide profile information with valid id.");
    }

    User newUser = identityService.newUser(profile.getId());
    profile.update(newUser);

    if(userDto.getCredentials() != null) {
      newUser.setPassword(userDto.getCredentials().getPassword());
    }

    Boolean done = runWithoutAuthorization(() -> {
        Boolean result = hasNoCamundaAdminGroup(getProcessEngine());
        if (result)
          createInitialUserInternal(userDto);
        return result;
    });

    if (!done)
      identityService.saveUser(newUser);
  }

  private <V extends Object> V runWithoutAuthorization(Supplier<V> action) {
    IdentityService identityService = getIdentityService();
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

  private boolean hasNoCamundaAdminGroup(ProcessEngine processEngine) {
    final IdentityService identityService = processEngine.getIdentityService();
    long groupCount = identityService.createGroupQuery().groupId(Groups.CAMUNDA_ADMIN).count();
    return groupCount == 0;
  }

  private void createInitialUserInternal(UserDto userDto) {

    ProcessEngine processEngine = getProcessEngine();
    IdentityService identityService = getIdentityService();

    UserProfileDto profile = userDto.getProfile();
    if(profile == null || profile.getId() == null) {
      throw new InvalidRequestException(Status.BAD_REQUEST, "request object must provide profile information with valid id.");
    }

    User newUser = identityService.newUser(profile.getId());
    profile.update(newUser);

    if(userDto.getCredentials() != null) {
      newUser.setPassword(userDto.getCredentials().getPassword());
    }

    // crate the camunda admin group
    ensureCamundaAdminGroupExists(processEngine);

    identityService.saveUser(newUser);
    // create group membership (add new user to admin group)
    processEngine.getIdentityService()
      .createMembership(userDto.getProfile().getId(), Groups.CAMUNDA_ADMIN);
  }

  private void ensureCamundaAdminGroupExists(ProcessEngine processEngine) {

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

  @Override
  public ResourceOptionsDto availableOperations(UriInfo context) {

    final IdentityService identityService = getIdentityService();

    UriBuilder baseUriBuilder = context.getBaseUriBuilder()
        .path(relativeRootResourcePath)
        .path(UserRestService.PATH);

    ResourceOptionsDto resourceOptionsDto = new ResourceOptionsDto();

    // GET /
    URI baseUri = baseUriBuilder.build();
    resourceOptionsDto.addReflexiveLink(baseUri, HttpMethod.GET, "list");

    // GET /count
    URI countUri = baseUriBuilder.clone().path("/count").build();
    resourceOptionsDto.addReflexiveLink(countUri, HttpMethod.GET, "count");

    // POST /create
    if(!identityService.isReadOnly() && isAuthorized(CREATE)) {
      URI createUri = baseUriBuilder.clone().path("/create").build();
      resourceOptionsDto.addReflexiveLink(createUri, HttpMethod.POST, "create");
    }

    return resourceOptionsDto;
  }

  // utility methods //////////////////////////////////////

  protected IdentityService getIdentityService() {
    return getProcessEngine().getIdentityService();
  }

}
