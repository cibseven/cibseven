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
import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.authorization.Resource;
import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.GroupQuery;
import org.cibseven.bpm.engine.identity.NativeUserQuery;
import org.cibseven.bpm.engine.identity.Tenant;
import org.cibseven.bpm.engine.identity.TenantQuery;
import org.cibseven.bpm.engine.identity.User;
import org.cibseven.bpm.engine.identity.UserQuery;
import org.cibseven.bpm.engine.impl.UserQueryImpl;
import org.cibseven.bpm.engine.impl.identity.ReadOnlyIdentityProvider;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.identity.impl.scim.util.ScimPluginLogger;

import java.util.ArrayList;
import java.util.List;

import static org.cibseven.bpm.engine.authorization.Permissions.READ;
import static org.cibseven.bpm.engine.authorization.Resources.GROUP;
import static org.cibseven.bpm.engine.authorization.Resources.USER;
import static org.cibseven.bpm.engine.impl.context.Context.getCommandContext;
import static org.cibseven.bpm.engine.impl.context.Context.getProcessEngineConfiguration;
import static org.cibseven.bpm.identity.impl.scim.ScimConfiguration.DB_QUERY_WILDCARD;
import static org.cibseven.bpm.identity.impl.scim.ScimConfiguration.SCIM_QUERY_WILDCARD;

/**
 * SCIM Identity Provider Session implementing ReadOnlyIdentityProvider.
 */
public class ScimIdentityProviderSession implements ReadOnlyIdentityProvider {

  protected ScimConfiguration scimConfiguration;
  protected ScimClient scimClient;

  public ScimIdentityProviderSession(ScimConfiguration scimConfiguration) {
    this.scimConfiguration = scimConfiguration;
    this.scimClient = new ScimClient(scimConfiguration);
  }

  // Session Lifecycle

  @Override
  public void flush() {
    // nothing to do for read-only provider
  }

  @Override
  public void close() {
    if (scimClient != null) {
      scimClient.close();
    }
  }

  // Users

  @Override
  public User findUserById(String userId) {
    return createUserQuery(getCommandContext()).userId(userId).singleResult();
  }

  @Override
  public UserQuery createUserQuery() {
    return new ScimUserQueryImpl(getProcessEngineConfiguration().getCommandExecutorTxRequired(), scimConfiguration);
  }

  @Override
  public UserQueryImpl createUserQuery(CommandContext commandContext) {
    return new ScimUserQueryImpl(scimConfiguration);
  }

  @Override
  public NativeUserQuery createNativeUserQuery() {
    throw new BadUserRequestException("Native user queries are not supported for SCIM identity service provider.");
  }

  public long findUserCountByQueryCriteria(ScimUserQueryImpl query) {
    return findUserByQueryCriteria(query).size();
  }

  public List<User> findUserByQueryCriteria(ScimUserQueryImpl query) {
    // Convert DB wildcards to SCIM wildcards if necessary
    if (query.getEmailLike() != null) {
      query.userEmailLike(query.getEmailLike().replaceAll(DB_QUERY_WILDCARD, SCIM_QUERY_WILDCARD));
    }
    if (query.getFirstNameLike() != null) {
      query.userFirstNameLike(query.getFirstNameLike().replaceAll(DB_QUERY_WILDCARD, SCIM_QUERY_WILDCARD));
    }
    if (query.getLastNameLike() != null) {
      query.userLastNameLike(query.getLastNameLike().replaceAll(DB_QUERY_WILDCARD, SCIM_QUERY_WILDCARD));
    }

    if (query.getGroupId() != null) {
      return findUsersByGroupId(query);
    } else {
      return findUsersWithoutGroupId(query);
    }
  }

  protected List<User> findUsersWithoutGroupId(ScimUserQueryImpl query) {
    String filter = buildUserFilter(query);
    int startIndex = query.getFirstResult() + 1; // SCIM uses 1-based indexing
    int count = query.getMaxResults();

    List<User> users = new ArrayList<>();
    JsonNode response = scimClient.searchUsers(filter, startIndex, count);

    if (response != null && response.has("Resources")) {
      JsonNode resources = response.get("Resources");
      for (JsonNode resource : resources) {
        ScimUserEntity user = transformUser(resource);
        if (user.getId() == null) {
          ScimPluginLogger.INSTANCE.invalidScimEntityReturned("user", getJsonValue(resource, "id"));
        } else if (isAuthenticatedAndAuthorized(user.getId())) {
          users.add(user);
        }
      }
    }

    return users;
  }

  protected List<User> findUsersByGroupId(ScimUserQueryImpl query) {
    // First find the group to get its members
    Group group = findGroupById(query.getGroupId());
    if (group == null) {
      return new ArrayList<>();
    }

    // Get group details with members
    JsonNode groupData = scimClient.getGroupById(query.getGroupId());
    List<User> users = new ArrayList<>();

    if (groupData != null && groupData.has("members")) {
      JsonNode members = groupData.get("members");
      int resultCount = 0;
      
      for (JsonNode member : members) {
        if (resultCount >= query.getFirstResult() && users.size() < query.getMaxResults()) {
          String userId = getJsonValue(member, "value");
          if (userId != null) {
            User user = findUserById(userId);
            if (user != null) {
              users.add(user);
            }
          }
        }
        resultCount++;
      }
    }

    return users;
  }

  protected String buildUserFilter(ScimUserQueryImpl query) {
    List<String> filters = new ArrayList<>();

    if (query.getId() != null) {
      filters.add(scimConfiguration.getUserIdAttribute() + " eq \"" + escapeScimFilter(query.getId()) + "\"");
    }
    if (query.getIds() != null && query.getIds().length > 0) {
      List<String> idFilters = new ArrayList<>();
      for (String userId : query.getIds()) {
        idFilters.add(scimConfiguration.getUserIdAttribute() + " eq \"" + escapeScimFilter(userId) + "\"");
      }
      filters.add("(" + String.join(" or ", idFilters) + ")");
    }
    if (query.getEmail() != null) {
      filters.add(scimConfiguration.getUserEmailAttribute() + " eq \"" + escapeScimFilter(query.getEmail()) + "\"");
    }
    if (query.getEmailLike() != null) {
      filters.add(scimConfiguration.getUserEmailAttribute() + " sw \"" + escapeScimFilter(query.getEmail()) + "\"");
    }
    if (query.getFirstName() != null) {
      filters.add(scimConfiguration.getUserFirstnameAttribute() + " eq \"" + escapeScimFilter(query.getFirstName()) + "\"");
    }
    if (query.getFirstNameLike() != null) {
      filters.add(scimConfiguration.getUserFirstnameAttribute() + " sw \"" + escapeScimFilter(query.getFirstNameLike()) + "\"");
    }
    if (query.getLastName() != null) {
      filters.add(scimConfiguration.getUserLastnameAttribute() + " eq \"" + escapeScimFilter(query.getLastName()) + "\"");
    }
    if (query.getLastNameLike() != null) {
      filters.add(scimConfiguration.getUserLastnameAttribute() + " sw \"" + escapeScimFilter(query.getLastNameLike()) + "\"");
    }

    return filters.isEmpty() ? null : String.join(" and ", filters);
  }

  protected String buildEmailFilter(String email, String operator) {
    String emailAttr = scimConfiguration.getUserEmailAttribute();
    // Handle complex email attribute paths like emails[type eq "work"].value
    if (emailAttr.contains("[")) {
      return emailAttr.replace(".value", "." + operator + " \"" + escapeScimFilter(email) + "\"");
    } else {
      return emailAttr + " " + operator + " \"" + escapeScimFilter(email) + "\"";
    }
  }

  protected ScimUserEntity transformUser(JsonNode resource) {
    ScimUserEntity user = new ScimUserEntity();
    user.setScimId(getJsonValue(resource, "id"));
    user.setId(getNestedJsonValue(resource, scimConfiguration.getUserIdAttribute()));
    user.setFirstName(getNestedJsonValue(resource, scimConfiguration.getUserFirstnameAttribute()));
    user.setLastName(getNestedJsonValue(resource, scimConfiguration.getUserLastnameAttribute()));
    
    // Handle email which might be in an array
    String emailAttr = scimConfiguration.getUserEmailAttribute();
    if (emailAttr.contains("[")) {
      // Extract from complex path like emails[type eq "work"].value
      if (resource.has("emails") && resource.get("emails").isArray()) {
        for (JsonNode email : resource.get("emails")) {
          if (email.has("type") && "work".equals(email.get("type").asText())) {
            user.setEmail(email.has("value") ? email.get("value").asText() : null);
            break;
          }
        }
        // Fallback to first email if no work email found
        if (user.getEmail() == null && resource.get("emails").size() > 0) {
          JsonNode firstEmail = resource.get("emails").get(0);
          user.setEmail(firstEmail.has("value") ? firstEmail.get("value").asText() : null);
        }
      }
    } else {
      user.setEmail(getNestedJsonValue(resource, emailAttr));
    }
    
    return user;
  }

  // Groups

  @Override
  public Group findGroupById(String groupId) {
    return createGroupQuery(getCommandContext()).groupId(groupId).singleResult();
  }

  @Override
  public GroupQuery createGroupQuery() {
    return new ScimGroupQuery(getProcessEngineConfiguration().getCommandExecutorTxRequired());
  }

  @Override
  public GroupQuery createGroupQuery(CommandContext commandContext) {
    return new ScimGroupQuery();
  }

  public long findGroupCountByQueryCriteria(ScimGroupQuery query) {
    return findGroupByQueryCriteria(query).size();
  }

  public List<Group> findGroupByQueryCriteria(ScimGroupQuery query) {
    // Convert DB wildcards to SCIM wildcards if necessary
    if (query.getNameLike() != null) {
      query.groupNameLike(query.getNameLike().replaceAll(DB_QUERY_WILDCARD, SCIM_QUERY_WILDCARD));
    }

    String filter = buildGroupFilter(query);
    int startIndex = query.getFirstResult() + 1; // SCIM uses 1-based indexing
    int count = query.getMaxResults();

    List<Group> groups = new ArrayList<>();
    JsonNode response = scimClient.searchGroups(filter, startIndex, count);

    if (response != null && response.has("Resources")) {
      JsonNode resources = response.get("Resources");
      for (JsonNode resource : resources) {
        ScimGroupEntity group = transformGroup(resource);
        if (group.getId() == null) {
          ScimPluginLogger.INSTANCE.invalidScimEntityReturned("group", getJsonValue(resource, "id"));
        } else if (isAuthorizedToReadGroup(group.getId())) {
          groups.add(group);
        }
      }
    }

    return groups;
  }

  protected String buildGroupFilter(ScimGroupQuery query) {
    List<String> filters = new ArrayList<>();

    if (query.getId() != null) {
      filters.add(scimConfiguration.getGroupIdAttribute() + " eq \"" + escapeScimFilter(query.getId()) + "\"");
    }
    if (query.getIds() != null && query.getIds().length > 0) {
      List<String> idFilters = new ArrayList<>();
      for (String groupId : query.getIds()) {
        idFilters.add(scimConfiguration.getGroupIdAttribute() + " eq \"" + escapeScimFilter(groupId) + "\"");
      }
      filters.add("(" + String.join(" or ", idFilters) + ")");
    }
    if (query.getName() != null) {
      filters.add(scimConfiguration.getGroupNameAttribute() + " eq \"" + escapeScimFilter(query.getName()) + "\"");
    }
    if (query.getNameLike() != null) {
      filters.add(scimConfiguration.getGroupNameAttribute() + " sw \"" + escapeScimFilter(query.getNameLike()) + "\"");
    }
    if (query.getUserId() != null) {
      filters.add("members[value eq \"" + escapeScimFilter(query.getUserId()) + "\"]");
    }

    return filters.isEmpty() ? null : String.join(" and ", filters);
  }

  protected ScimGroupEntity transformGroup(JsonNode resource) {
    ScimGroupEntity group = new ScimGroupEntity();
    group.setScimId(getJsonValue(resource, "id"));
    group.setId(getNestedJsonValue(resource, scimConfiguration.getGroupIdAttribute()));
    group.setName(getNestedJsonValue(resource, scimConfiguration.getGroupNameAttribute()));
    group.setType("SCIM");
    return group;
  }

  // Tenants

  @Override
  public TenantQuery createTenantQuery() {
    return new ScimTenantQuery(getProcessEngineConfiguration().getCommandExecutorTxRequired());
  }

  @Override
  public TenantQuery createTenantQuery(CommandContext commandContext) {
    return new ScimTenantQuery();
  }

  @Override
  public Tenant findTenantById(String id) {
    // Multi-tenancy is not supported for SCIM plugin
    return null;
  }

  // Password check (not supported for SCIM)

  @Override
  public boolean checkPassword(String userId, String password) {
    // SCIM is a provisioning protocol, not an authentication protocol
    // Authentication should be handled by the SCIM server itself
    return false;
  }

  // Utility methods

  protected boolean isAuthenticatedAndAuthorized(String userId) {
    return isAuthenticatedUser(userId) || isAuthorizedToRead(USER, userId);
  }

  protected boolean isAuthenticatedUser(String userId) {
    if (userId == null) {
      return false;
    }
    return userId.equalsIgnoreCase(getCommandContext().getAuthenticatedUserId());
  }

  protected boolean isAuthorizedToRead(Resource resource, String resourceId) {
    return !scimConfiguration.isAuthorizationCheckEnabled() ||
        getCommandContext().getAuthorizationManager()
            .isAuthorized(READ, resource, resourceId);
  }

  protected boolean isAuthorizedToReadGroup(String groupId) {
    return isAuthorizedToRead(GROUP, groupId);
  }

  protected String getJsonValue(JsonNode node, String key) {
    if (node == null || !node.has(key)) {
      return null;
    }
    JsonNode valueNode = node.get(key);
    return valueNode.isNull() ? null : valueNode.asText();
  }

  protected String getNestedJsonValue(JsonNode node, String path) {
    if (node == null || path == null) {
      return null;
    }

    String[] parts = path.split("\\.");
    JsonNode current = node;

    for (String part : parts) {
      if (current == null || !current.has(part)) {
        return null;
      }
      current = current.get(part);
    }

    return current == null || current.isNull() ? null : current.asText();
  }

  protected String escapeScimFilter(String value) {
    if (value == null) {
      return "";
    }
    // Escape special characters in SCIM filter values
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
