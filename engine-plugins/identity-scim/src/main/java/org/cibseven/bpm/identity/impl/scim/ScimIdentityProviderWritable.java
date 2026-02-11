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
package org.cibseven.bpm.identity.impl.scim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.cibseven.bpm.engine.authorization.Permission;
import org.cibseven.bpm.engine.authorization.Permissions;
import org.cibseven.bpm.engine.authorization.Resource;
import org.cibseven.bpm.engine.authorization.Resources;
import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.Tenant;
import org.cibseven.bpm.engine.identity.User;
import org.cibseven.bpm.engine.impl.identity.IdentityOperationResult;
import org.cibseven.bpm.engine.impl.identity.IdentityProviderException;
import org.cibseven.bpm.engine.impl.identity.WritableIdentityProvider;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import static org.cibseven.bpm.engine.impl.context.Context.getCommandContext;
import static org.cibseven.bpm.engine.impl.util.EnsureUtil.ensureNotNull;

/**
 * SCIM Identity Provider Session implementing ReadOnlyIdentityProvider.
 */
public class ScimIdentityProviderWritable extends ScimIdentityProviderReadOnly implements WritableIdentityProvider { 

  public ScimIdentityProviderWritable(ScimConfiguration scimConfiguration) {
    super(scimConfiguration);
  }

  // Session Lifecycle

  @Override
  public void flush() {
    super.flush();
  }

  @Override
  public void close() {
    super.close();
  }

  // Users
  
  @Override
  public User createNewUser(String userId) {
    return new ScimUserEntity(userId);
  }
 
  @Override
  public IdentityOperationResult saveUser(User user) {
    ScimUserEntity scimUser = (ScimUserEntity) user;
    String operation = null;
    if(scimUser.getScimId() == null || scimUser.getScimId().isEmpty()) {
      operation = IdentityOperationResult.OPERATION_CREATE;
      checkAuthorization(Permissions.CREATE, Resources.USER, null);
      String postUrl = scimConfiguration.getServerUrl() + scimConfiguration.getUsersEndpoint();
      JsonNode postBody = transformUser(scimUser);
      scimClient.executePost(postUrl, postBody);
      //createDefaultAuthorizations(userEntity);
    } else {
      operation = IdentityOperationResult.OPERATION_UPDATE;
      checkAuthorization(Permissions.UPDATE, Resources.USER, user.getId());
      ScimUserEntity scimUserOld = (ScimUserEntity)findUserById(scimUser.getId());
      JsonNode patchBody = createUserPatch(scimUserOld, scimUser);     
      scimClient.patchUserByScimId(scimUser.getScimId(), patchBody);
    }

    return new IdentityOperationResult(user, operation);
  }

  @Override
  public IdentityOperationResult deleteUser(String userId) {
    checkAuthorization(Permissions.DELETE, Resources.USER, userId);
    ScimUserEntity scimUser = (ScimUserEntity)findUserById(userId);
    if(scimUser != null) {
      // copied from DB identity provider, commented out, probably not needed for SCIM
      //deleteMembershipsByUserId(userId);
      //deleteTenantMembershipsOfUser(userId);
      //deleteAuthorizations(Resources.USER, userId);

      //Context.getCommandContext().runWithoutAuthorization(new Callable<Void>() {
      //  @Override
      //  public Void call() throws Exception {
      //    final List<Tenant> tenants = createTenantQuery().userMember(userId).list();
      //    if (tenants != null && !tenants.isEmpty()) {
      //      for (Tenant tenant : tenants) {
      //        deleteAuthorizationsForUser(Resources.TENANT, tenant.getId(), userId);
      //      }
      //    }
      //    return null;
      //  }
      //});
  
      scimClient.deleteUserByScimId(scimUser.getScimId());
      return new IdentityOperationResult(null, IdentityOperationResult.OPERATION_DELETE);
    }
    return new IdentityOperationResult(null, IdentityOperationResult.OPERATION_NONE);
  }

  @Override
  public IdentityOperationResult unlockUser(String userId) {
    // TODO Auto-generated method stub
    return null;
  }

  // Groups
  
  @Override
  public Group createNewGroup(String groupId) {
    return new ScimGroupEntity(groupId);
  }

  @Override
  public IdentityOperationResult saveGroup(Group group) {
    ScimGroupEntity scimGroup =  (ScimGroupEntity) group;
    String operation = null;
    if (scimGroup.getScimId() == null || scimGroup.getScimId().isEmpty()) {
      operation = IdentityOperationResult.OPERATION_CREATE;
      checkAuthorization(Permissions.CREATE, Resources.GROUP, null);
      String postUrl = scimConfiguration.getServerUrl() + scimConfiguration.getGroupsEndpoint();
      JsonNode postBody = transformGroup(scimGroup);
      scimClient.executePost(postUrl, postBody);
      // createDefaultAuthorizations(group);
    } else {
      operation = IdentityOperationResult.OPERATION_UPDATE;
      checkAuthorization(Permissions.UPDATE, Resources.GROUP, group.getId());
      ScimGroupEntity scimGroupOld = (ScimGroupEntity)findGroupById(scimGroup.getId());
      JsonNode patchBody = createGroupPatch(scimGroupOld, scimGroup);
      scimClient.patchGroupByScimId(scimGroup.getScimId(), patchBody);
    }

    return new IdentityOperationResult(scimGroup, operation);
  }

  @Override
  public IdentityOperationResult deleteGroup(String groupId) {
    checkAuthorization(Permissions.DELETE, Resources.GROUP, groupId);
    ScimGroupEntity scimGroup =  (ScimGroupEntity) findGroupById(groupId);
    if(scimGroup != null) {
      // copied from DB identity provider, commented out, probably not needed for SCIM
      //deleteMembershipsByGroupId(groupId);
      //deleteTenantMembershipsOfGroup(groupId);
      // deleteAuthorizations(Resources.GROUP, groupId);

      //Context.getCommandContext().runWithoutAuthorization(new Callable<Void>() {
      //  @Override
      //  public Void call() throws Exception {
      //    final List<Tenant> tenants = createTenantQuery().groupMember(groupId).list();
      //    if (tenants != null && !tenants.isEmpty()) {
      //      for (Tenant tenant : tenants) {
      //        deleteAuthorizationsForGroup(Resources.TENANT, tenant.getId(), groupId);
      //      }
      //    }
      //    return null;
      //  }
      //});

      scimClient.deleteGroupByScimId(scimGroup.getScimId());
      return new IdentityOperationResult(null, IdentityOperationResult.OPERATION_DELETE);
    }
    
    return new IdentityOperationResult(null, IdentityOperationResult.OPERATION_NONE);
  }

  // Tenants: not used by SCIM identity provider
  
  @Override
  public Tenant createNewTenant(String tenantId) {
    throw new IdentityProviderException("This operation is not supported for SCIM identity provider.");
  }

  @Override
  public IdentityOperationResult saveTenant(Tenant tenant) {
    throw new IdentityProviderException("This operation is not supported for SCIM identity provider.");
  }

  @Override
  public IdentityOperationResult deleteTenant(String tenantId) {
    throw new IdentityProviderException("This operation is not supported for SCIM identity provider.");
  }

  // Memberships
  
  @Override
  public IdentityOperationResult createMembership(String userId, String groupId) {
    checkAuthorization(Permissions.CREATE, Resources.GROUP_MEMBERSHIP, groupId);
    ScimUserEntity user = (ScimUserEntity)findUserById(userId);
    ensureNotNull("No user found with id '" + userId + "'.", "user", user);
    ScimGroupEntity group = (ScimGroupEntity)findGroupById(groupId);
    ensureNotNull("No group found with id '" + groupId + "'.", "group", group);
    
    JsonNode patchBody = createMembershipPatch(user.getScimId(), "add", "User");
    scimClient.patchGroupByScimId(group.getScimId(), patchBody);
    //createDefaultMembershipAuthorizations(userId, groupId);   
    return new IdentityOperationResult(null, IdentityOperationResult.OPERATION_CREATE);
  }

  @Override
  public IdentityOperationResult deleteMembership(String userId, String groupId) {
    checkAuthorization(Permissions.DELETE, Resources.GROUP_MEMBERSHIP, groupId);
    ScimUserEntity user = (ScimUserEntity)findUserById(userId);
    ensureNotNull("No user found with id '" + userId + "'.", "user", user);
    ScimGroupEntity group = (ScimGroupEntity)findGroupById(groupId);
    ensureNotNull("No group found with id '" + groupId + "'.", "group", group);
    
    JsonNode patchBody = createMembershipPatch(user.getScimId(), "remove", null);
    scimClient.patchGroupByScimId(group.getScimId(), patchBody);
    //createDefaultMembershipAuthorizations(userId, groupId);  
    return new IdentityOperationResult(null, IdentityOperationResult.OPERATION_DELETE);
  }

  @Override
  public IdentityOperationResult createTenantUserMembership(String tenantId, String userId) {
    throw new IdentityProviderException("This operation is not supported for SCIM identity provider.");
    // return new IdentityOperationResult(null, IdentityOperationResult.OPERATION_NONE);
  }

  @Override
  public IdentityOperationResult createTenantGroupMembership(String tenantId, String groupId) {
    throw new IdentityProviderException("This operation is not supported for SCIM identity provider.");
    //  return new IdentityOperationResult(null, IdentityOperationResult.OPERATION_NONE);
  }

  @Override
  public IdentityOperationResult deleteTenantUserMembership(String tenantId, String userId) {
    // throw new IdentityProviderException("This operation is not supported for SCIM identity provider.");
    return new IdentityOperationResult(null, IdentityOperationResult.OPERATION_NONE);
  }

  @Override
  public IdentityOperationResult deleteTenantGroupMembership(String tenantId, String groupId) {
    // throw new IdentityProviderException("This operation is not supported for SCIM identity provider.");
    return new IdentityOperationResult(null, IdentityOperationResult.OPERATION_NONE);
  }
  
  protected JsonNode createUserPatch(ScimUserEntity before, ScimUserEntity after) {
    // transform both to the Json and prepare simplest patch
    return createDiffNode(transformUser(before), transformUser(after));
  }

  protected JsonNode createGroupPatch(ScimGroupEntity before, ScimGroupEntity after) {
    // transform both to the Json and prepare simplest patch
    return createDiffNode(transformGroup(before), transformGroup(after));
  }
  
  protected JsonNode createMembershipPatch(String memberScimId, String operation, String memberType) {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode root = mapper.createObjectNode();
    ArrayNode schemas = mapper.createArrayNode().add("urn:ietf:params:scim:api:messages:2.0:PatchOp");
    ArrayNode operations = mapper.createArrayNode();
 
    root.set("schemas", schemas);
    root.set("Operations", operations);

    ObjectNode entry = operations.addObject();
    entry.put("op", operation);
    entry.put("path", "members");

    ArrayNode members = mapper.createArrayNode();
    ObjectNode membership = members.addObject();
    membership.put("value", memberScimId);
    if (!operation.equalsIgnoreCase("remove") && memberType != null && !memberType.isEmpty()) {
      membership.put("type", memberType);
    }
    entry.set("value", members);

    return root;
  }
  
  protected JsonNode createDiffNode(JsonNode first, JsonNode second) {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode root = mapper.createObjectNode();
    ArrayNode schemas = mapper.createArrayNode().add("urn:ietf:params:scim:api:messages:2.0:PatchOp");
    ArrayNode operations = mapper.createArrayNode();
    
    root.set("schemas", schemas);
    root.set("Operations", operations);
    
    // simplest for now: compare textual representation of the top-level values
    HashSet<String> topLevelFields = new HashSet<>();
    Iterator<Map.Entry<String, JsonNode>> itOld = first.fields();
    while (itOld.hasNext()) {
      topLevelFields.add(itOld.next().getKey());
    }

    Iterator<Map.Entry<String, JsonNode>> itNew = second.fields();
    while (itNew.hasNext()) {
      topLevelFields.add(itNew.next().getKey());
    }
     
    for (String field : topLevelFields) {
      JsonNode oldVal = first.get(field);
      JsonNode newVal = second.get(field);

      if (oldVal == null) {
        ObjectNode entry = operations.addObject();
        entry.put("op", "add");
          entry.put("path", field);
          entry.set("value", newVal);
      } else if (newVal == null) {
        ObjectNode entry = operations.addObject();
        entry.put("op", "remove");
          entry.put("path", field);
      } else if (!oldVal.toString().equals(newVal.toString())) {
        ObjectNode entry = operations.addObject();
        entry.put("op", "replace");
          entry.put("path", field);
          entry.set("value", newVal);
      }
    }
    
    return root;
  }
  
  protected void checkAuthorization(Permission permission, Resource resource, String resourceId) {
  if (scimConfiguration.isAuthorizationCheckEnabled()) {
         getCommandContext().getAuthorizationManager().checkAuthorization(permission, resource, resourceId);
    }
  }
}
