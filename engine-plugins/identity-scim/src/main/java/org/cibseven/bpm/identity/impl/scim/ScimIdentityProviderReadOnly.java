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

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.authorization.Resource;
import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.GroupQuery;
import org.cibseven.bpm.engine.identity.NativeUserQuery;
import org.cibseven.bpm.engine.identity.Tenant;
import org.cibseven.bpm.engine.identity.TenantQuery;
import org.cibseven.bpm.engine.identity.User;
import org.cibseven.bpm.engine.identity.UserQuery;
import org.cibseven.bpm.engine.impl.Direction;
import org.cibseven.bpm.engine.impl.GroupQueryProperty;
import org.cibseven.bpm.engine.impl.QueryOrderingProperty;
import org.cibseven.bpm.engine.impl.UserQueryProperty;
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

/**
 * SCIM Identity Provider Session implementing ReadOnlyIdentityProvider.
 */
public class ScimIdentityProviderReadOnly implements ReadOnlyIdentityProvider {

  protected ScimConfiguration scimConfiguration;
  protected ScimClient scimClient;

  public ScimIdentityProviderReadOnly(ScimConfiguration scimConfiguration) {
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
    return new ScimUserQuery(getProcessEngineConfiguration().getCommandExecutorTxRequired());
  }

  @Override
  public UserQuery createUserQuery(CommandContext commandContext) {
    return new ScimUserQuery();
  }

  @Override
  public NativeUserQuery createNativeUserQuery() {
    throw new BadUserRequestException("Native user queries are not supported for SCIM identity service provider.");
  }

  public long findUserCountByQueryCriteria(ScimUserQuery query) {
    // SCIM 2.0 returns total number of filtered resources as a parameter of the response
    // therefore restrict the number of returned elements to decrease network usage
    if (query.getMaxResults() >= Short.MAX_VALUE) {
      query.setMaxResults(scimConfiguration.getPageSize());
    }
    List<User> users = findUserByQueryCriteria(query);
    return Math.max(users.size(), query.getTotalResults());
  }

  public List<User> findUserByQueryCriteria(ScimUserQuery query) {
    if (query.getGroupId() != null) {
      return findUsersByGroupId(query);
    } else {
      return findUsersWithoutGroupId(query);
    }
  }

  protected List<User> findUsersWithoutGroupId(ScimUserQuery query) {
    String filter = buildUserFilter(query);
    String sorting = buildUserSorting(query);
    int startIndex = query.getFirstResult() + 1; // SCIM uses 1-based indexing
    int count = query.getMaxResults();

    List<User> users = new ArrayList<>();
    JsonNode response = scimClient.searchUsers(filter, startIndex, count, sorting);    
    if (response != null && response.has("Resources")) {
      JsonNode resources = response.get("Resources");
      for (JsonNode resource : resources) {
        ScimUserEntity user = transformUser(resource);
        if (user.getScimId() != null) {
          users.add(user);
        } else {
          ScimPluginLogger.INSTANCE.invalidScimEntityReturned("User", user.toString());          
        }
      }
    }
    
    // set total result
    if (response != null && response.has("totalResults")) {
      query.setTotalResults(response.get("totalResults").asInt());
    }

    return users;
  }

  protected List<User> findUsersByGroupId(ScimUserQuery query) {
    // First find the group to get its members
    ScimGroupEntity group = (ScimGroupEntity)findGroupById(query.getGroupId());
    if (group == null) {
      return new ArrayList<>();
    }

    // Get group details with members
    JsonNode groupData = scimClient.getGroupByScimId(group.getScimId());
    List<User> users = new ArrayList<>();

    String membersAttrib = scimConfiguration.getGroupMembersAttribute();
    if (groupData != null && groupData.has(membersAttrib)) {
      int resultCount = 0;
      JsonNode members = groupData.get(membersAttrib);
      for (JsonNode member : members) {
        if (resultCount >= query.getFirstResult() && users.size() < query.getMaxResults()) {
          String memberType = getJsonValue(member, "type"); // treat as user type by default if the type is absent
          if (memberType == null || memberType.isEmpty() || memberType.equals("User")) {
            String userScimId = getJsonValue(member, "value");
            if (userScimId != null) {
              ScimUserEntity user = transformUser(scimClient.getUserByScimId(userScimId));
              if (user.getScimId() != null) {
                users.add(user);
              } else {
                ScimPluginLogger.INSTANCE.invalidScimEntityReturned("User", user.toString());          
              }
            }
          }
        }
        resultCount++;
      }

      // set total result
      query.setTotalResults(members.size());
    }

    return users;
  }

  protected String buildUserFilter(ScimUserQuery query) {
    List<String> filters = new ArrayList<>();

    String baseFilter = scimConfiguration.getUserBaseFilter();
    if (baseFilter != null && !baseFilter.isEmpty()) {
      filters.add(baseFilter);
    }
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
      String emailLike = query.getEmailLike();
      String op = emailLike.startsWith("%") && emailLike.endsWith("%") ? "co" : 
          emailLike.startsWith("%") ? "sw" : emailLike.endsWith("%") ? "ew" : "eq";
          
      emailLike = emailLike.replaceAll("^%|%$", "");
      filters.add(scimConfiguration.getUserEmailAttribute() + " " + op + " \"" + escapeScimFilter(emailLike) + "\"");
    }
    if (query.getFirstName() != null) {
      filters.add(scimConfiguration.getUserFirstnameAttribute() + " eq \"" + escapeScimFilter(query.getFirstName()) + "\"");
    }
    if (query.getFirstNameLike() != null) {
      String nameLike = query.getFirstNameLike();
      String op = nameLike.startsWith("%") && nameLike.endsWith("%") ? "co" : 
          nameLike.startsWith("%") ? "sw" : nameLike.endsWith("%") ? "ew" : "eq";
      
      nameLike = nameLike.replaceAll("^%|%$", "");
      filters.add(scimConfiguration.getUserFirstnameAttribute() + " " + op + " \"" +escapeScimFilter(nameLike) + "\"");
    }
    if (query.getLastName() != null) {
      filters.add(scimConfiguration.getUserLastnameAttribute() + " eq \"" + escapeScimFilter(query.getLastName()) + "\"");
    }
    if (query.getLastNameLike() != null) {
      String nameLike = query.getLastNameLike();
      String op = nameLike.startsWith("%") && nameLike.endsWith("%") ? "co" : 
          nameLike.startsWith("%") ? "sw" : nameLike.endsWith("%") ? "ew" : "eq";
        
      nameLike = nameLike.replaceAll("^%|%$", "");
      filters.add(scimConfiguration.getUserLastnameAttribute() + " " + op + " \"" + escapeScimFilter(nameLike) + "\"");
    }

    return filters.isEmpty() ? null : String.join(" and ", filters);
  }
  
  protected String buildUserSorting(ScimUserQuery query) {
    String sorting = null;
    List<QueryOrderingProperty> orderBy = query.getOrderingProperties();
    if (orderBy != null) {
      for (QueryOrderingProperty orderingProperty : orderBy) {
        String key = null;
        String direction = null;
        String propertyName = orderingProperty.getQueryProperty().getName();

        if (UserQueryProperty.USER_ID.getName().equals(propertyName)) {
          key = scimConfiguration.getUserIdAttribute();
          direction = Direction.ASCENDING.equals(orderingProperty.getDirection()) ? "ascending" : "descending";
        } else if (UserQueryProperty.EMAIL.getName().equals(propertyName)) {
          key = scimConfiguration.getUserEmailAttribute().contains("[") ? "emails" : scimConfiguration.getUserEmailAttribute();
          direction = Direction.ASCENDING.equals(orderingProperty.getDirection()) ? "ascending" : "descending";
        } else if (UserQueryProperty.FIRST_NAME.getName().equals(propertyName)) {
          key = scimConfiguration.getUserFirstnameAttribute();
          direction = Direction.ASCENDING.equals(orderingProperty.getDirection()) ? "ascending" : "descending";
        } else if (UserQueryProperty.LAST_NAME.getName().equals(propertyName)) {
           key = scimConfiguration.getUserLastnameAttribute();
           direction = Direction.ASCENDING.equals(orderingProperty.getDirection()) ? "ascending" : "descending";
        }
        
        // only single key is supported by SCIM for sorting
        if (key != null && direction != null) {
          sorting = "sortBy=" + key + "&sortOrder=" + direction;
          break;
        }
      }
    }
    
    return sorting;
  }

  protected ScimUserEntity transformUser(JsonNode resource) {
    ScimUserEntity user = new ScimUserEntity();
    user.setScimId(getJsonValue(resource, scimConfiguration.getUserScimIdAttribute()));
    user.setId(getJsonValue(resource, scimConfiguration.getUserIdAttribute()));
    if (user.getId() == null || user.getId().isEmpty()) {
      user.setId(user.getScimId()); // use scim ID as a fallback
    }
    
    user.setFirstName(getJsonValue(resource, scimConfiguration.getUserFirstnameAttribute()));
    user.setLastName(getJsonValue(resource, scimConfiguration.getUserLastnameAttribute()));
    
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
      user.setEmail(getJsonValue(resource, emailAttr));
    }
    
    return user;
  }

  protected JsonNode transformUser(ScimUserEntity user) {

    ObjectMapper mapper = new ObjectMapper();
    ObjectNode root = mapper.createObjectNode();
    
    ArrayNode schemas = root.putArray("schemas");
    schemas.add("urn:ietf:params:scim:schemas:core:2.0:User");

    String scimIdAttr = scimConfiguration.getUserScimIdAttribute();
    setJsonValue(root, scimIdAttr, user.getScimId(), mapper);
    
    String idAttr = scimConfiguration.getUserIdAttribute();
    setJsonValue(root, idAttr, user.getId(), mapper);

    String firstNameAttr = scimConfiguration.getUserFirstnameAttribute();
    setJsonValue(root, firstNameAttr, user.getFirstName(), mapper);

    String lastNameAttr = scimConfiguration.getUserLastnameAttribute();
    setJsonValue(root, lastNameAttr, user.getLastName(), mapper);

    String emailAttr = scimConfiguration.getUserEmailAttribute();

    if (emailAttr.contains("[")) {
        // SCIM complex structure: emails[type eq "work"].value
        ArrayNode emailsArray = root.putArray("emails");
        ObjectNode emailObj = mapper.createObjectNode();
        emailObj.put("type", "work");
        emailObj.put("value", user.getEmail());
        emailsArray.add(emailObj);

    } else {
        // Simple nested JSON path
        setJsonValue(root, emailAttr, user.getEmail(), mapper);
    }

    return root;
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
    // SCIM 2.0 returns total number of filtered resources as a parameter of the response
    // therefore restrict the number of returned elements to decrease network usage
    if (query.getMaxResults() >= Short.MAX_VALUE) {
      query.setMaxResults(scimConfiguration.getPageSize());
    }
    List<Group> groups = findGroupByQueryCriteria(query);
    return Math.max(groups.size(), query.getTotalResults());
  }

  public List<Group> findGroupByQueryCriteria(ScimGroupQuery query) {
    String filter = buildGroupFilter(query);
    String sorting = buildGroupSorting(query);
    int startIndex = query.getFirstResult() + 1; // SCIM uses 1-based indexing
    int count = query.getMaxResults();

    List<Group> groups = new ArrayList<>();
    JsonNode response = scimClient.searchGroups(filter, startIndex, count, sorting);
    if (response != null && response.has("Resources")) {
      JsonNode resources = response.get("Resources");
      for (JsonNode resource : resources) {
        ScimGroupEntity group = transformGroup(resource);
        if (group.getScimId() != null) {
          groups.add(group);
        } else if (isAuthorizedToReadGroup(group.getId())) {
          ScimPluginLogger.INSTANCE.invalidScimEntityReturned("group", resource.toString());
        }
      }
    }
    
    // set total result
    if (response != null && response.has("totalResults")) {
      query.setTotalResults(response.get("totalResults").asInt());
    }
    
    //// TODO: remove this fallback for SCIMPLE, because it doesn't correctly filter by an users
    //if (groups.isEmpty() && query.getUserId() != null) {
    //	groups = findGroupByUserId(query);
    //}

    return groups;
  }
  
//  // TODO: remove this fallback for SCIMPLE, because it doesn't correctly filter by an users 
//  public List<Group> findGroupByUserId(ScimGroupQuery query) {
//    List<Group> groups = new ArrayList<>();
//    ScimUserEntity scimUser = (ScimUserEntity) findUserById(query.getUserId());
//    if (scimUser != null) {
//      ScimGroupQuery queryNew = new ScimGroupQuery();
//      String filter = buildGroupFilter(queryNew);
//      String sorting = buildGroupSorting(queryNew);
//      int startIndex = queryNew.getFirstResult() + 1; // SCIM uses 1-based indexing
//      int count = queryNew.getMaxResults();
//      JsonNode response = scimClient.searchGroups(filter, startIndex, count, sorting);
//      int groupCount = 0;
//      if (response != null && response.has("Resources")) {
//        JsonNode resources = response.get("Resources");
//        for (JsonNode resource : resources) {
//          JsonNode members = resource.get(scimConfiguration.getGroupMembersAttribute());
//          if (members != null) {
//            for (JsonNode member : members) {
//              String userScimId = getJsonValue(member, "value");
//              if (scimUser.getScimId().equals(userScimId)) {
//                groups.add(transformGroup(resource));
//                groupCount++;
//                break;
//              }  
//            }
//          }
//        }
//      }
//      // set total result
//      query.setTotalResults(groupCount);
//    }
//
//    return groups;
//  }

  protected String buildGroupFilter(ScimGroupQuery query) {
    List<String> filters = new ArrayList<>();
    
    String baseFilter = scimConfiguration.getGroupBaseFilter();
    if (baseFilter != null && !baseFilter.isEmpty()) {
      filters.add(escapeScimFilter(baseFilter));
    }
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
      String nameLike = query.getNameLike();
      String op = nameLike.startsWith("%") && nameLike.endsWith("%") ? "co" : 
          nameLike.startsWith("%") ? "sw" : nameLike.endsWith("%") ? "ew" : "eq";
      
      nameLike = nameLike.replaceAll("^%|%$", "");
      filters.add(scimConfiguration.getGroupNameAttribute() + " " + op + " \"" + escapeScimFilter(nameLike) + "\"");
    }
    if (query.getUserId() != null) {
      ScimUserEntity scimUser = (ScimUserEntity)findUserById(query.getUserId());
      String skimUserId = scimUser != null ? scimUser.getScimId() : "null";
      filters.add("members[value eq \"" + escapeScimFilter(skimUserId) + "\"]");
    }

    return filters.isEmpty() ? null : String.join(" and ", filters);
  }
  
  protected String buildGroupSorting(ScimGroupQuery query) {
    String sorting = null;
    List<QueryOrderingProperty> orderBy = query.getOrderingProperties();
    if (orderBy != null) {
      for (QueryOrderingProperty orderingProperty : orderBy) {
        String key = null;
        String direction = null;
        String propertyName = orderingProperty.getQueryProperty().getName();

        if (GroupQueryProperty.GROUP_ID.getName().equals(propertyName)) {
          key = scimConfiguration.getGroupIdAttribute();
          direction = Direction.ASCENDING.equals(orderingProperty.getDirection()) ? "ascending" : "descending";
        } else if (GroupQueryProperty.NAME.getName().equals(propertyName)) {
          key = scimConfiguration.getGroupNameAttribute();
          direction = Direction.ASCENDING.equals(orderingProperty.getDirection()) ? "ascending" : "descending";
        }

        // only single key is supported by SCIM for sorting
        if (key != null && direction != null) {
          sorting = "sortBy=" + key + "&sortOrder=" + direction;
          break;
        }
      }
    }
    
    return sorting;
  }

  protected ScimGroupEntity transformGroup(JsonNode resource) {
    ScimGroupEntity group = new ScimGroupEntity();
    group.setScimId(getJsonValue(resource, scimConfiguration.getGroupScimIdAttribute()));
    group.setId(getJsonValue(resource, scimConfiguration.getGroupIdAttribute()));
    if (group.getId() == null || group.getId().isEmpty()) { 
      group.setId(group.getScimId()); // use scim ID as a fallback
    }
    
    group.setName(getJsonValue(resource, scimConfiguration.getGroupNameAttribute()));
    group.setType("SCIM");
    return group;
  }

  protected JsonNode transformGroup(ScimGroupEntity group) {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode root = mapper.createObjectNode();

    ArrayNode schemas = root.putArray("schemas");
    schemas.add("urn:ietf:params:scim:schemas:core:2.0:Group");
    
    String scimIdAttr = scimConfiguration.getGroupScimIdAttribute();
    setJsonValue(root, scimIdAttr, group.getScimId(), mapper);

    String idAttr = scimConfiguration.getGroupIdAttribute();
    setJsonValue(root, idAttr, group.getId(), mapper);

    String groupNameAttr = scimConfiguration.getGroupNameAttribute();
    setJsonValue(root, groupNameAttr, group.getName(), mapper);
    
    return root;
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
    // TODO: need clarification !!!!!
    // SCIM is a provisioning protocol, not an authentication protocol
    // Authentication is optionally supported by a SCIM service extension
    // with using POST on the search sub-endpoint: /Users/.search.
    // For now, optionally use this SCIM extension.
    boolean result = false;
    try {
      if (scimConfiguration.getScimAuthenticationEnabled() ) {
        String url = scimConfiguration.getServerUrl() + "/" + scimConfiguration.getUsersEndpoint() + "/.search";
        String userIdAttrib = scimConfiguration.getUserIdAttribute();
        String filter = userIdAttrib + " eq \"" + escapeScimFilter(userId) + "\" and password eq \"" + escapeScimFilter(password) + "\"";
 
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ArrayNode schemas = mapper.createArrayNode().add("urn:ietf:params:scim:api:messages:2.0:SearchRequest");
        root.set("schemas", schemas);
        root.put("filter", filter);
        
        JsonNode response = scimClient.executePost(url, root);
        result = (response != null && response.has("Resources") && response.get("Resources").size() == 1);
      } else { // scim authentication is disabled: simply check that the user really exists
        ScimUserEntity scimUser = (ScimUserEntity) findUserById(userId);
        result = scimUser != null && scimUser.getScimId() != null;
      }
    } catch(Exception e) {
      ScimPluginLogger.INSTANCE.httpClientException("authentication request", e);
    }
    return result;
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
  
  protected String getJsonValue(JsonNode node, String path) {
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
  
  private void setJsonValue(ObjectNode root, String path, String value, ObjectMapper mapper) {
    if (value == null) {
        return;
    }

    String[] parts = path.split("\\.");
    ObjectNode current = root;

    for (int i = 0; i < parts.length - 1; i++) {
        JsonNode child = current.get(parts[i]);
        if (child == null || !child.isObject()) {
            child = mapper.createObjectNode();
            current.set(parts[i], child);
        }
        current = (ObjectNode) child;
    }

    current.put(parts[parts.length - 1], value);
  }

  protected String escapeScimFilter(String value) {
    if (value == null) {
      return "";
    }
    // Escape special characters in SCIM filter values
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
