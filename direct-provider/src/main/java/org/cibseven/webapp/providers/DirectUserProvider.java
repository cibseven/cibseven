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
package org.cibseven.webapp.providers;

import static org.cibseven.webapp.auth.SevenAuthorizationUtils.resourceType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.authorization.AuthorizationQuery;
import org.cibseven.bpm.engine.authorization.Permissions;
import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.GroupQuery;
import org.cibseven.bpm.engine.identity.UserQuery;
import org.cibseven.bpm.engine.impl.identity.Authentication;
import org.cibseven.bpm.engine.impl.util.PermissionConverter;
import org.cibseven.bpm.engine.rest.dto.authorization.AuthorizationDto;
import org.cibseven.bpm.engine.rest.dto.authorization.AuthorizationQueryDto;
import org.cibseven.bpm.engine.rest.dto.identity.GroupQueryDto;
import org.cibseven.bpm.engine.rest.dto.identity.UserQueryDto;
import org.cibseven.bpm.engine.rest.util.QueryUtil;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.auth.SevenResourceType;
import org.cibseven.webapp.auth.rest.StandardLogin;
import org.cibseven.webapp.exception.InvalidUserIdException;
import org.cibseven.webapp.exception.NoObjectFoundException;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.Authorization;
import org.cibseven.webapp.rest.model.Authorizations;
import org.cibseven.webapp.rest.model.NewUser;
import org.cibseven.webapp.rest.model.SevenUser;
import org.cibseven.webapp.rest.model.SevenVerifyUser;
import org.cibseven.webapp.rest.model.User;
import org.cibseven.webapp.rest.model.UserGroup;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.authorization.Permission;
import org.cibseven.bpm.engine.authorization.Resource;
import org.cibseven.bpm.engine.impl.util.ResourceTypeUtil;
import org.cibseven.webapp.exception.UnknownResourceTypeException;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;

public class DirectUserProvider implements IUserProvider {

	private String userProvider;
	private String wildcard;
	DirectProviderUtil directProviderUtil;

	public DirectUserProvider(DirectProviderUtil directProviderUtil, String userProvider, String wildcard) {
		this.userProvider = userProvider;
		this.directProviderUtil = directProviderUtil;
		this.wildcard = wildcard;
	}

	@Override
	public Collection<SevenUser> fetchUsers(CIBUser user) throws SystemException {
		UserQueryDto queryDto = new UserQueryDto();
		queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
		UserQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));
		query.userId(user.getId());
		List<org.cibseven.bpm.engine.identity.User> resultList = QueryUtil.list(query, null, null);

		Collection<SevenUser> users = new ArrayList<>();
		for (org.cibseven.bpm.engine.identity.User resultUser : resultList) {
			users.add(directProviderUtil.convertValue(resultUser, SevenUser.class, user));
		}
		return users;
	}

	@Override
	public Authorizations getUserAuthorization(CIBUser user) {
		Authentication currentAuthentication = directProviderUtil.getProcessEngine(user).getIdentityService()
				.getCurrentAuthentication();
		List<String> authenticatedGroupIds = currentAuthentication != null ? currentAuthentication.getGroupIds() : null;

		Collection<Authorization> userAuthorizations = directProviderUtil
				.runWithoutAuthorization(() -> fetchOwnAuthorizations(user, authenticatedGroupIds), user);

		Authorizations auths = new Authorizations();
		auths.setApplication(SevenProviderBase.filterResources(userAuthorizations, resourceType(SevenResourceType.APPLICATION)));
		auths.setFilter(SevenProviderBase.filterResources(userAuthorizations, resourceType(SevenResourceType.FILTER)));
		auths.setProcessDefinition(SevenProviderBase.filterResources(userAuthorizations, resourceType(SevenResourceType.PROCESS_DEFINITION)));
		auths.setProcessInstance(SevenProviderBase.filterResources(userAuthorizations, resourceType(SevenResourceType.PROCESS_INSTANCE)));
		auths.setTask(SevenProviderBase.filterResources(userAuthorizations, resourceType(SevenResourceType.TASK)));
		auths.setAuthorization(SevenProviderBase.filterResources(userAuthorizations, resourceType(SevenResourceType.AUTHORIZATION)));
		auths.setUser(SevenProviderBase.filterResources(userAuthorizations, resourceType(SevenResourceType.USER)));
		auths.setGroup(SevenProviderBase.filterResources(userAuthorizations, resourceType(SevenResourceType.GROUP)));
		auths.setDecisionDefinition(SevenProviderBase.filterResources(userAuthorizations, resourceType(SevenResourceType.DECISION_DEFINITION)));
		auths.setDecisionRequirementsDefinition(
				SevenProviderBase.filterResources(userAuthorizations, resourceType(SevenResourceType.DECISION_REQUIREMENTS_DEFINITION)));
		auths.setDeployment(SevenProviderBase.filterResources(userAuthorizations, resourceType(SevenResourceType.DEPLOYMENT)));
		// auths.setCaseDefinition(SevenProviderBase.filterResources(userAuthorizations, resourceType(SevenResourceType.CASE_DEFINITION)));
		// auths.setCaseInstance(SevenProviderBase.filterResources(userAuthorizations, resourceType(SevenResourceType.CASE_INSTANCE)));
		// auths.setJobDefinition(SevenProviderBase.filterResources(userAuthorizations, resourceType(SevenResourceType.JOB_DEFINITION)));
		auths.setBatch(SevenProviderBase.filterResources(userAuthorizations, resourceType(SevenResourceType.BATCH)));
		auths.setGroupMembership(SevenProviderBase.filterResources(userAuthorizations, resourceType(SevenResourceType.GROUP_MEMBERSHIP)));
		auths.setHistoricTask(SevenProviderBase.filterResources(userAuthorizations, resourceType(SevenResourceType.HISTORIC_TASK)));
		auths.setHistoricProcessInstance(
				SevenProviderBase.filterResources(userAuthorizations, resourceType(SevenResourceType.HISTORIC_PROCESS_INSTANCE)));
		auths.setTenant(SevenProviderBase.filterResources(userAuthorizations, resourceType(SevenResourceType.TENANT)));
		auths.setTenantMembership(SevenProviderBase.filterResources(userAuthorizations, resourceType(SevenResourceType.TENANT_MEMBERSHIP)));
		auths.setReport(SevenProviderBase.filterResources(userAuthorizations, resourceType(SevenResourceType.REPORT)));
		auths.setDashboard(SevenProviderBase.filterResources(userAuthorizations, resourceType(SevenResourceType.DASHBOARD)));
		auths.setUserOperationLogCategory(
				SevenProviderBase.filterResources(userAuthorizations, resourceType(SevenResourceType.USER_OPERATION_LOG_CATEGORY)));
		auths.setSystem(SevenProviderBase.filterResources(userAuthorizations, resourceType(SevenResourceType.SYSTEM)));
		// auths.setMessage(SevenProviderBase.filterResources(userAuthorizations, resourceType(SevenResourceType.MESSAGE)));
		// auths.setEventSubscription(SevenProviderBase.filterResources(userAuthorizations, resourceType(SevenResourceType.EVENT_SUBSCRIPTION)));

		return auths;
	}

	/**
	 * Collects the authorizations that apply to {@code user}: the ones granted to the user itself, the
	 * ones granted to a group it belongs to, and the global ones.
	 *
	 * @param authenticatedGroupIds the memberships carried by the engine authentication, or {@code null}
	 *        when the engine did not authenticate the user; they are then queried instead.
	 */
	private Collection<Authorization> fetchOwnAuthorizations(CIBUser user, List<String> authenticatedGroupIds) {
		ProcessEngine processEngine = directProviderUtil.getProcessEngine(user);
		List<String> groupIds = authenticatedGroupIds != null ? authenticatedGroupIds
				: directProviderUtil.getGroupsOfUser(user);

		Collection<Authorization> authorizations = queryAuthorizations(processEngine, user,
				queryDto -> queryDto.setUserIdIn(new String[] { user.getId() }));

		if (groupIds != null && !groupIds.isEmpty()) {
			authorizations.addAll(queryAuthorizations(processEngine, user,
					queryDto -> queryDto.setGroupIdIn(groupIds.toArray(new String[0]))));
		}

		authorizations.addAll(queryAuthorizations(processEngine, user, queryDto -> queryDto.setType(0)));

		return authorizations;
	}

	private Collection<Authorization> queryAuthorizations(ProcessEngine processEngine, CIBUser user,
			Consumer<AuthorizationQueryDto> filter) {
		AuthorizationQueryDto queryDto = new AuthorizationQueryDto();
		queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
		filter.accept(queryDto);
		AuthorizationQuery query = queryDto.toQuery(processEngine);
		return createAuthorizationCollection(QueryUtil.list(query, null, null));
	}

	@Override
	public SevenVerifyUser verifyUser(StandardLogin login, CIBUser user) throws SystemException {
		if ((login.getUsername() == null || login.getUsername().isBlank()) || 
				(login.getPassword() == null || login.getPassword().isBlank()))
			throw new SystemException("Username and password are required");
		SevenVerifyUser verifyUser = new SevenVerifyUser();
		boolean valid = directProviderUtil.getProcessEngine(user).getIdentityService()
				.checkPassword(login.getUsername(), login.getPassword());
		verifyUser.setAuthenticated(valid);
		verifyUser.setAuthenticatedUser(login.getUsername());
		return verifyUser;
	}
	@Override
	public Collection<User> findUsers(Optional<String> id, Optional<String> firstName, Optional<String> firstNameLike,
			Optional<String> lastName, Optional<String> lastNameLike, Optional<String> email, Optional<String> emailLike,
			Optional<String> memberOfGroup, Optional<String> memberOfTenant, Optional<String> idIn,
			Optional<String> firstResult, Optional<String> maxResults, Optional<String> sortBy, Optional<String> sortOrder,
			Optional<Boolean> likePatternIgnoreCase, CIBUser user) {
		String wcard = getWildcard();

		return getUsers(id, firstName, firstNameLike, lastName, lastNameLike, email, emailLike, memberOfGroup, memberOfTenant,
				idIn, firstResult, maxResults, sortBy, sortOrder, wcard, likePatternIgnoreCase, user);
	}

	private String getWildcard () {
		String wcard = "";
		if (wildcard != null && !wildcard.equals("")) wcard = wildcard;
		else {
			if (userProvider.equals("org.cibseven.webapp.auth.LdapUserProvider") || userProvider.equals("org.cibseven.webapp.auth.AdfsUserProvider")) {
				wcard = "*";
			} else wcard = "%";
		}
		return wcard;
	}

	@Override
	public void createUser(NewUser user, CIBUser flowUser) throws InvalidUserIdException {
		User profile = user.getProfile();
		org.cibseven.bpm.engine.identity.User newUser = directProviderUtil.getProcessEngine(flowUser).getIdentityService().newUser(profile.getId());
		newUser.setId(profile.getId());
		newUser.setFirstName(profile.getFirstName());
		newUser.setLastName(profile.getLastName());
		newUser.setEmail(profile.getEmail());
		newUser.setPassword(user.getCredentials().getPassword());
		directProviderUtil.getProcessEngine(flowUser).getIdentityService().saveUser(newUser);
	}

	@Override
	public void updateUserProfile(String userId, User user, CIBUser flowUser) {
		if (directProviderUtil.getProcessEngine(flowUser).getIdentityService().isReadOnly()) {
			throw new SystemException("Identity service implementation is read-only.");
		}

		org.cibseven.bpm.engine.identity.User dbUser = findUserObject(user.getId(), flowUser);
		if (dbUser == null) {
			throw new NoObjectFoundException(new SystemException("User with id " + user.getId() + " does not exist"));
		}

		dbUser.setId(user.getId());
		dbUser.setFirstName(user.getFirstName());
		dbUser.setLastName(user.getLastName());
		dbUser.setEmail(user.getEmail());
		directProviderUtil.getProcessEngine(flowUser).getIdentityService().saveUser(dbUser);
	}

	@Override
	public void updateUserCredentials(String userId, Map<String, Object> data, CIBUser user) {
		if (directProviderUtil.getProcessEngine(user).getIdentityService().isReadOnly()) {
			throw new SystemException("Identity service implementation is read-only.");
		}
		Authentication currentAuthentication = directProviderUtil.getProcessEngine(user).getIdentityService().getCurrentAuthentication();
		if (currentAuthentication != null && currentAuthentication.getUserId() != null) {
			if (!directProviderUtil.getProcessEngine(user).getIdentityService().checkPassword(currentAuthentication.getUserId(),
					(String) data.get("authenticatedUserPassword"))) {
				throw new SystemException("The given authenticated user password is not valid.");
			}
		}

		org.cibseven.bpm.engine.identity.User dbUser = findUserObject(userId, user);
		if (dbUser == null) {
			throw new NoObjectFoundException(new SystemException("User with id " + user.getId() + " does not exist"));
		}

		dbUser.setPassword((String) data.get("password"));
		directProviderUtil.getProcessEngine(user).getIdentityService().saveUser(dbUser);
	}

	@Override
	public void addMemberToGroup(String groupId, String userId, CIBUser user) {
		if (directProviderUtil.getProcessEngine(user).getIdentityService().isReadOnly()) {
			throw new SystemException("Identity service implementation is read-only.");
		}
		directProviderUtil.getProcessEngine(user).getIdentityService().createMembership(userId, groupId);
	}

	@Override
	public void deleteMemberFromGroup(String groupId, String userId, CIBUser user) {
		if (directProviderUtil.getProcessEngine(user).getIdentityService().isReadOnly()) {
			throw new SystemException("Identity service implementation is read-only.");
		}
		directProviderUtil.getProcessEngine(user).getIdentityService().deleteMembership(userId, groupId);
	}

	@Override
	public void deleteUser(String userId, CIBUser user) {
		directProviderUtil.getProcessEngine(user).getIdentityService().deleteUser(userId);
	}

	@Override
	public SevenUser getUserProfile(String userId, CIBUser user) {
		List<org.cibseven.bpm.engine.identity.User> users = directProviderUtil.getProcessEngine(user).getIdentityService().createUserQuery().userId(userId).list();
		org.cibseven.bpm.engine.identity.User identityUser = null;
		if (users.isEmpty()) {
			return null;
		} else if (users.size() == 1) {
			identityUser = users.get(0);
		} else {
			identityUser = users.stream().filter(u -> u.getId().equals(userId)).findFirst().orElse(null);
			if (identityUser == null) {
				identityUser = users.get(0);
			}
		}
		return directProviderUtil.convertValue(identityUser, SevenUser.class, user);
	}

	@Override
	public Collection<UserGroup> findGroups(Optional<String> id, Optional<String> name, Optional<String> nameLike,
			Optional<String> type, Optional<String> member, Optional<String> memberOfTenant, Optional<String> sortBy,
			Optional<String> sortOrder, Optional<String> firstResult, Optional<String> maxResults, CIBUser user) {
		final String wcard = "%";
		GroupQueryDto queryDto = new GroupQueryDto();
		queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
		// set parameters
		if (id.isPresent())
			queryDto.setId(id.get());
		if (name.isPresent())
			queryDto.setName(name.get());
		if (nameLike.isPresent())
			queryDto.setNameLike(nameLike.get().replace("*", wcard));
		;
		if (type.isPresent())
			queryDto.setType(type.get());
		if (member.isPresent())
			queryDto.setMember(member.get());
		if (memberOfTenant.isPresent())
			queryDto.setMemberOfTenant(memberOfTenant.get());
		if (sortBy.isPresent())
			queryDto.setSortBy(sortBy.get());
		if (sortOrder.isPresent())
			queryDto.setSortOrder(sortOrder.get().equals("asc") ? "asc" : "desc");
		GroupQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));

		Integer first = firstResult.isPresent() ? Integer.parseInt(firstResult.get()) : null;
		Integer max = maxResults.isPresent() ? Integer.parseInt(maxResults.get()) : null;
		List<Group> resultList = QueryUtil.list(query, first, max);

		Collection<UserGroup> userGroups = createUserGroups(resultList);
		return userGroups;
	}

	@Override
	public void createGroup(UserGroup group, CIBUser user) {
		if (directProviderUtil.getProcessEngine(user).getIdentityService().isReadOnly()) {
			throw new SystemException("Identity service implementation is read-only.");
		}
		Group newGroup = directProviderUtil.getProcessEngine(user).getIdentityService().newGroup(group.getId());
		newGroup.setId(group.getId());
		newGroup.setName(group.getName());
		newGroup.setType(group.getType());
		directProviderUtil.getProcessEngine(user).getIdentityService().saveGroup(newGroup);
	}

	@Override
	public void updateGroup(String groupId, UserGroup group, CIBUser user) {
		if (directProviderUtil.getProcessEngine(user).getIdentityService().isReadOnly()) {
			throw new SystemException("Identity service implementation is read-only.");
		}

		Group dbGroup = findGroupObject(groupId, user);
		if (dbGroup == null) {
			throw new NoObjectFoundException(new SystemException("Group with id " + groupId + " does not exist"));
		}

		dbGroup.setId(group.getId());
		dbGroup.setName(group.getName());
		dbGroup.setType(group.getType());

		directProviderUtil.getProcessEngine(user).getIdentityService().saveGroup(dbGroup);
	}

	@Override
	public void deleteGroup(String groupId, CIBUser user) {
		if (directProviderUtil.getProcessEngine(user).getIdentityService().isReadOnly()) {
			throw new SystemException("Identity service implementation is read-only.");
		}
		directProviderUtil.getProcessEngine(user).getIdentityService().deleteGroup(groupId);
	}

	@Override
	public Collection<Authorization> findAuthorization(Optional<String> id, Optional<String> type,
			Optional<String> userIdIn, Optional<String> groupIdIn, Optional<String> resourceType, Optional<String> resourceId,
			Optional<String> sortBy, Optional<String> sortOrder, Optional<String> firstResult, Optional<String> maxResults,
			CIBUser user) {
		AuthorizationQueryDto queryDto = new AuthorizationQueryDto();
		queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
		if (id.isPresent())
			queryDto.setId(id.get());
		if (type.isPresent())
			queryDto.setType(Integer.parseInt(type.get()));
		if (userIdIn.isPresent())
			queryDto.setUserIdIn(new String[] { userIdIn.get() });
		if (groupIdIn.isPresent())
			queryDto.setGroupIdIn(new String[] { groupIdIn.get() });
		if (resourceType.isPresent())
			queryDto.setResourceType(Integer.parseInt(resourceType.get()));
		if (resourceId.isPresent())
			queryDto.setResourceId(resourceId.get());
		if (sortOrder.isPresent())
			queryDto.setSortOrder(sortOrder.get());
		if (sortOrder.isPresent())
			queryDto.setSortOrder(sortOrder.get());
		Integer firstResultParam = null;
		if (firstResult.isPresent())
			firstResultParam = Integer.parseInt(firstResult.get());
		Integer maxResultsParam = null;
		if (maxResults.isPresent())
			maxResultsParam = Integer.parseInt(maxResults.get());
		return queryAuthorizations(queryDto, firstResultParam, maxResultsParam, user);
	}

	private List<Authorization> queryAuthorizations(AuthorizationQueryDto queryDto, Integer firstResult,
			Integer maxResults, CIBUser user) {
		queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
		AuthorizationQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));

		List<org.cibseven.bpm.engine.authorization.Authorization> resultList = QueryUtil.list(query, firstResult, maxResults);
		List<AuthorizationDto> authorizationDtoList = AuthorizationDto.fromAuthorizationList(resultList,
				directProviderUtil.getProcessEngine(user).getProcessEngineConfiguration());
		List<Authorization> authorizationList = new ArrayList<>();
		for (AuthorizationDto authorizationDto : authorizationDtoList) {
			authorizationList.add(directProviderUtil.convertValue(authorizationDto, Authorization.class, user));
		}
		return authorizationList;
	}

	@Override
	public ResponseEntity<Authorization> createAuthorization(Authorization authorization, CIBUser user) {
		org.cibseven.bpm.engine.authorization.Authorization newAuthorization = directProviderUtil.getProcessEngine(user).getAuthorizationService()
				.createNewAuthorization(authorization.getType());
		newAuthorization.setGroupId(authorization.getGroupId());
		newAuthorization.setUserId(authorization.getUserId());
		newAuthorization.setResourceType(authorization.getResourceType());
		newAuthorization.setResourceId(authorization.getResourceId());
		newAuthorization.setPermissions(PermissionConverter.getPermissionsForNames(authorization.getPermissions(),
				authorization.getResourceType(), directProviderUtil.getProcessEngine(user).getProcessEngineConfiguration()));

		newAuthorization = directProviderUtil.getProcessEngine(user).getAuthorizationService().saveAuthorization(newAuthorization);

		Authorization resultAuthorization = new Authorization();
		resultAuthorization.setGroupId(newAuthorization.getGroupId());
		resultAuthorization.setId(newAuthorization.getId());
		resultAuthorization.setPermissions(PermissionConverter.getNamesForPermissions(newAuthorization,
				newAuthorization.getPermissions(Permissions.values())));
		resultAuthorization.setResourceId(newAuthorization.getResourceId());
		resultAuthorization.setResourceType(newAuthorization.getResourceType());
		resultAuthorization.setType(newAuthorization.getAuthorizationType());
		resultAuthorization.setUserId(newAuthorization.getUserId());
		return ResponseEntity.ok(resultAuthorization);
	}

	@Override
	public void updateAuthorization(String authorizationId, Map<String, Object> data, CIBUser user) {
		org.cibseven.bpm.engine.authorization.Authorization dbAuthorization = directProviderUtil.getProcessEngine(user).getAuthorizationService().createAuthorizationQuery()
				.authorizationId(authorizationId).singleResult();

		if (dbAuthorization == null) {
			throw new NoObjectFoundException(new SystemException("Authorization with id " + authorizationId + " does not exist."));
		}
		AuthorizationDto authorizationDto = new AuthorizationDto();
		if (data.containsKey("groupId"))
			authorizationDto.setGroupId((String) data.get("groupId"));
		if (data.containsKey("permissions")) {
			List<String> permissionList = (List<String>) data.get("permissions");
			authorizationDto.setPermissions(permissionList.toArray(new String[0]));
		}
		if (data.containsKey("resourceId"))
			authorizationDto.setResourceId((String) data.get("resourceId"));
		if (data.containsKey("resourceType"))
			authorizationDto.setResourceType((Integer) data.get("resourceType"));
		if (data.containsKey("type"))
			authorizationDto.setType((Integer) data.get("type"));
		if (data.containsKey("userId"))
			authorizationDto.setUserId((String) data.get("userId"));
		AuthorizationDto.update(authorizationDto, dbAuthorization, directProviderUtil.getProcessEngine(user).getProcessEngineConfiguration());
		// save
		directProviderUtil.getProcessEngine(user).getAuthorizationService().saveAuthorization(dbAuthorization);
	}

	@Override
	public void deleteAuthorization(String authorizationId, CIBUser user) {
		directProviderUtil.getProcessEngine(user).getAuthorizationService().deleteAuthorization(authorizationId);
	}

	private org.cibseven.bpm.engine.identity.User findUserObject(String id, CIBUser user) {
		org.cibseven.bpm.engine.identity.User dbUser = null;
		try {
			List<org.cibseven.bpm.engine.identity.User> users = directProviderUtil.getProcessEngine(user).getIdentityService().createUserQuery().userId(id).list();

			if (users.size() == 1) {
				dbUser = users.get(0);
			} else if (!users.isEmpty()) {

				dbUser = users.stream().filter(u -> u.getId().equals(id)).findFirst().orElse(null);

				if (dbUser == null) {
					dbUser = users.get(0);
				}
			}
		} catch (ProcessEngineException e) {
			throw new SystemException("Exception while performing user query: " + e.getMessage());
		}
		return dbUser;
	}

	private Group findGroupObject(String groupId, CIBUser user) {
		try {
			return directProviderUtil.getProcessEngine(user).getIdentityService().createGroupQuery().groupId(groupId).singleResult();
		} catch (ProcessEngineException e) {
			throw new SystemException("Exception while performing group query: " + e.getMessage());
		}
	}

	private Collection<User> getUsers(Optional<String> id, Optional<String> firstName, Optional<String> firstNameLike,
			Optional<String> lastName, Optional<String> lastNameLike, Optional<String> email, Optional<String> emailLike,
			Optional<String> memberOfGroup, Optional<String> memberOfTenant, Optional<String> idIn,
			Optional<String> firstResult, Optional<String> maxResults, Optional<String> sortBy, Optional<String> sortOrder,
			String wcard, Optional<Boolean> likePatternIgnoreCase, CIBUser user) {
		UserQueryDto queryDto = new UserQueryDto();
		queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
		queryDto.setLikePatternIgnoreCase(likePatternIgnoreCase.orElse(null));
		UserQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));
		if (memberOfGroup.isPresent())
			query.memberOfGroup(memberOfGroup.get());
		if (memberOfTenant.isPresent())
			query.memberOfTenant(memberOfTenant.get());
		if (sortBy.isPresent()) {
			String sortByValue = sortBy.get();
			switch (sortByValue) {
			case "userId":
				query.orderByUserId();
				break;
			case "firstName":
				query.orderByUserFirstName();
				break;
			case "lastName":
				query.orderByUserLastName();
				break;
			case "email":
				query.orderByUserEmail();
				break;
			default:
			}
		}
		if (email.isPresent())
			query.userEmail(email.get());
		if (emailLike.isPresent())
			query.userEmailLike(emailLike.get().replace("*", wcard));
		if (firstName.isPresent())
			query.userFirstName(firstName.get());
		if (firstNameLike.isPresent())
			query.userFirstNameLike(firstNameLike.get().replace("*", wcard));
		if (id.isPresent())
			query.userId(id.get());
		if (lastName.isPresent())
			query.userLastName(lastName.get());
		if (lastNameLike.isPresent())
			query.userLastNameLike(lastNameLike.get().replace("*", wcard));
		Integer first = firstResult.isPresent() ? Integer.parseInt(firstResult.get()) : null;
		Integer max = maxResults.isPresent() ? Integer.parseInt(maxResults.get()) : null;
		List<org.cibseven.bpm.engine.identity.User> resultList = QueryUtil.list(query, first, max);

		Collection<User> userCollection = createUsers(resultList);
		return userCollection;
	}

	private Collection<User> createUsers(List<org.cibseven.bpm.engine.identity.User> resultList) {
		Collection<User> users = new ArrayList<>();
		for (org.cibseven.bpm.engine.identity.User resultUser : resultList) {
			User user = new User();
			user.setEmail(resultUser.getEmail());
			user.setFirstName(resultUser.getFirstName());
			user.setId(resultUser.getId());
			user.setLastName(resultUser.getLastName());
			users.add(user);
		}
		return users;
	}

	private Collection<Authorization> createAuthorizationCollection(
			List<org.cibseven.bpm.engine.authorization.Authorization> userAuthorizationList) {
		Collection<Authorization> resultAuthorization = new ArrayList<>();
		for (org.cibseven.bpm.engine.authorization.Authorization userAuthorization : userAuthorizationList) {
			resultAuthorization.add(createAuthorization(userAuthorization));
		}
		return resultAuthorization;
	}

	private Authorization createAuthorization(org.cibseven.bpm.engine.authorization.Authorization userAuthorization) {
		Authorization newUserAuthorization = new Authorization();
		newUserAuthorization.setGroupId(userAuthorization.getGroupId());
		newUserAuthorization.setId(userAuthorization.getId());
		newUserAuthorization.setPermissions(PermissionConverter.getNamesForPermissions(userAuthorization,
				userAuthorization.getPermissions(Permissions.values())));
		newUserAuthorization.setResourceId(userAuthorization.getResourceId());
		newUserAuthorization.setResourceType(userAuthorization.getResourceType());
		newUserAuthorization.setType(userAuthorization.getAuthorizationType());
		newUserAuthorization.setUserId(userAuthorization.getUserId());
		return newUserAuthorization;
	}

	private Collection<UserGroup> createUserGroups(List<Group> resultList) {
		Collection<UserGroup> userGroups = new ArrayList<>();
		for (Group group : resultList) {
			userGroups.add(createUserGroup(group));
		}
		return userGroups;
	}

	private UserGroup createUserGroup(Group group) {
		UserGroup userGroup = new UserGroup();
		userGroup.setId(group.getId());
		userGroup.setName(group.getName());
		userGroup.setType(group.getType());
		return userGroup;
	}

	@Override
	public long countUsers(Map<String, Object> filters, CIBUser user) throws SystemException {
		UserQueryDto queryDto = directProviderUtil.getObjectMapper(user).convertValue(filters, UserQueryDto.class);
		UserQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));
		return query.count();
	}


	/**
	 * Decided by the engine in-process. The call runs with the user authenticated (see
	 * {@link AuthorizingProviderProxy}), but the engine evaluates the check internally rather than
	 * through a query the caller must be allowed to make, so a grant the user may not read still counts.
	 */
	@Override
	public boolean isUserAuthorized(CIBUser user, int resourceType, String resourceId, String permission) {
		ProcessEngine engine = directProviderUtil.getProcessEngine(user);
		if (!engine.getProcessEngineConfiguration().isAuthorizationEnabled()) {
			return true;
		}
		Resource resource = ResourceTypeUtil.getResourceByType(resourceType);
		Permission required = ResourceTypeUtil.getPermissionByNameAndResourceType(permission, resourceType);
		if (resource == null || required == null) {
			throw new UnknownResourceTypeException(resourceType);
		}
		return engine.getAuthorizationService()
			.isUserAuthorized(user.getId(), groupIdsOf(user, engine), required, resource, resourceId);
	}

	/**
	 * Taken from the authentication {@link AuthorizingProviderProxy} already established, because
	 * querying memberships as that user is filtered by READ on the group resource and would miss a
	 * permission granted through a group. Resolved outside the authentication for non-proxied callers.
	 */
	private List<String> groupIdsOf(CIBUser user, ProcessEngine engine) {
		Authentication authentication = engine.getIdentityService().getCurrentAuthentication();
		if (authentication != null && user.getId().equals(authentication.getUserId())
				&& authentication.getGroupIds() != null) {
			return authentication.getGroupIds();
		}
		return directProviderUtil.runWithoutAuthorization(() -> engine.getIdentityService().createGroupQuery()
			.groupMember(user.getId()).unlimitedList().stream()
			.map(Group::getId)
			.collect(Collectors.toList()), user);
	}
}
