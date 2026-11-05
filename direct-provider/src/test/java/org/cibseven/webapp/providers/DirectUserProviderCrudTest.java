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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.UserQuery;
import org.cibseven.bpm.engine.impl.identity.Authentication;
import org.cibseven.bpm.engine.impl.persistence.entity.UserEntity;
import org.cibseven.bpm.engine.rest.mapper.JacksonConfigurator;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.NoObjectFoundException;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.Credentials;
import org.cibseven.webapp.rest.model.NewUser;
import org.cibseven.webapp.rest.model.SevenUser;
import org.cibseven.webapp.rest.model.User;
import org.cibseven.webapp.rest.model.UserGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Covers the user, group and membership CRUD of {@link DirectUserProvider}. The authorization
 * checks are covered by {@link DirectUserProviderAuthorizationTest} and
 * {@link DirectUserProviderAuthorizationsTest}.
 */
public class DirectUserProviderCrudTest {

	private DirectProviderUtil directProviderUtil;
	private IdentityService identityService;
	private UserQuery userQuery;
	private DirectUserProvider userProvider;
	private CIBUser user;

	@BeforeEach
	void setUp() {
		user = new CIBUser("testUser");

		ProcessEngine processEngine = mock(ProcessEngine.class);
		identityService = mock(IdentityService.class);
		when(processEngine.getIdentityService()).thenReturn(identityService);

		userQuery = mock(UserQuery.class, withSettings().defaultAnswer(RETURNS_SELF));
		when(identityService.createUserQuery()).thenReturn(userQuery);

		ObjectMapper objectMapper = new ObjectMapper();
		JacksonConfigurator.configureObjectMapper(objectMapper);

		directProviderUtil = Mockito.spy(new DirectProviderUtil());
		doReturn(processEngine).when(directProviderUtil).getProcessEngine(any(CIBUser.class));
		doReturn(objectMapper).when(directProviderUtil).getObjectMapper(any(CIBUser.class));

		userProvider = new DirectUserProvider(directProviderUtil, "org.cibseven.webapp.auth.SevenUserProvider", "%");
	}

	private org.cibseven.bpm.engine.identity.User mockEngineUser(String id) {
		org.cibseven.bpm.engine.identity.User engineUser =
			mock(org.cibseven.bpm.engine.identity.User.class);
		when(engineUser.getId()).thenReturn(id);
		return engineUser;
	}

	/**
	 * getUserProfile hands the engine user straight to Jackson, which cannot serialise a Mockito
	 * mock, so those tests need a real entity rather than a mock.
	 */
	private org.cibseven.bpm.engine.identity.User realEngineUser(String id) {
		UserEntity engineUser = new UserEntity();
		engineUser.setId(id);
		return engineUser;
	}

	// ---------- users ----------

	@Test
	void createUser_savesTheProfileAndPassword() {
		org.cibseven.bpm.engine.identity.User newUser = mockEngineUser("demo");
		when(identityService.newUser("demo")).thenReturn(newUser);

		NewUser request = new NewUser(new User("demo", "Demo", "User", "demo@example.com"),
			new Credentials("s3cret"));

		userProvider.createUser(request, user);

		verify(newUser).setFirstName("Demo");
		verify(newUser).setLastName("User");
		verify(newUser).setEmail("demo@example.com");
		verify(newUser).setPassword("s3cret");
		verify(identityService).saveUser(newUser);
	}

	@Test
	void updateUserProfile_savesTheChangedProfile() {
		org.cibseven.bpm.engine.identity.User dbUser = mockEngineUser("demo");
		when(userQuery.list()).thenReturn(List.of(dbUser));

		userProvider.updateUserProfile("demo", new User("demo", "New", "Name", "new@example.com"), user);

		verify(dbUser).setFirstName("New");
		verify(dbUser).setEmail("new@example.com");
		verify(identityService).saveUser(dbUser);
	}

	@Test
	void updateUserProfile_throwsWhenTheUserIsUnknown() {
		when(userQuery.list()).thenReturn(List.of());

		assertThatThrownBy(() -> userProvider.updateUserProfile(
				"missing", new User("missing", "a", "b", "c@d.e"), user))
			.isInstanceOf(NoObjectFoundException.class);
		verify(identityService, never()).saveUser(any());
	}

	@Test
	void updateUserProfile_refusesWhenTheIdentityServiceIsReadOnly() {
		when(identityService.isReadOnly()).thenReturn(true);

		assertThatThrownBy(() -> userProvider.updateUserProfile(
				"demo", new User("demo", "a", "b", "c@d.e"), user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("read-only");
	}

	@Test
	void deleteUser_delegatesToTheIdentityService() {
		userProvider.deleteUser("demo", user);

		verify(identityService).deleteUser("demo");
	}

	@Test
	void getUserProfile_returnsNullWhenNoUserMatches() {
		when(userQuery.list()).thenReturn(List.of());

		assertThat(userProvider.getUserProfile("missing", user)).isNull();
	}

	@Test
	void getUserProfile_mapsTheSingleMatch() {
		org.cibseven.bpm.engine.identity.User engineUser = realEngineUser("demo");
		when(userQuery.list()).thenReturn(List.of(engineUser));

		SevenUser profile = userProvider.getUserProfile("demo", user);

		assertThat(profile.getId()).isEqualTo("demo");
	}

	@Test
	void getUserProfile_prefersTheExactIdWhenTheQueryIsAmbiguous() {
		// a userId query can match case-insensitively in some identity backends
		org.cibseven.bpm.engine.identity.User other = realEngineUser("DEMO");
		org.cibseven.bpm.engine.identity.User exact = realEngineUser("demo");
		when(userQuery.list()).thenReturn(List.of(other, exact));

		SevenUser profile = userProvider.getUserProfile("demo", user);

		assertThat(profile.getId()).isEqualTo("demo");
	}

	@Test
	void getUserProfile_fallsBackToTheFirstMatchWhenNoneMatchesExactly() {
		org.cibseven.bpm.engine.identity.User first = realEngineUser("DEMO");
		org.cibseven.bpm.engine.identity.User second = realEngineUser("Demo");
		when(userQuery.list()).thenReturn(List.of(first, second));

		SevenUser profile = userProvider.getUserProfile("demo", user);

		assertThat(profile.getId()).isEqualTo("DEMO");
	}

	// ---------- credentials ----------

	@Test
	void updateUserCredentials_setsTheNewPassword() {
		org.cibseven.bpm.engine.identity.User dbUser = mockEngineUser("demo");
		when(userQuery.list()).thenReturn(List.of(dbUser));
		Map<String, Object> data = new HashMap<>();
		data.put("password", "newPassword");

		userProvider.updateUserCredentials("demo", data, user);

		verify(dbUser).setPassword("newPassword");
		verify(identityService).saveUser(dbUser);
	}

	@Test
	void updateUserCredentials_requiresTheAuthenticatedUsersOwnPassword() {
		when(identityService.getCurrentAuthentication()).thenReturn(new Authentication("admin", List.of()));
		when(identityService.checkPassword("admin", "wrong")).thenReturn(false);
		Map<String, Object> data = new HashMap<>();
		data.put("password", "newPassword");
		data.put("authenticatedUserPassword", "wrong");

		// stops an authenticated session from changing passwords without re-confirming its own
		assertThatThrownBy(() -> userProvider.updateUserCredentials("demo", data, user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("authenticated user password is not valid");
		verify(identityService, never()).saveUser(any());
	}

	@Test
	void updateUserCredentials_proceedsWhenTheAuthenticatedPasswordChecksOut() {
		when(identityService.getCurrentAuthentication()).thenReturn(new Authentication("admin", List.of()));
		when(identityService.checkPassword("admin", "right")).thenReturn(true);
		org.cibseven.bpm.engine.identity.User dbUser = mockEngineUser("demo");
		when(userQuery.list()).thenReturn(List.of(dbUser));
		Map<String, Object> data = new HashMap<>();
		data.put("password", "newPassword");
		data.put("authenticatedUserPassword", "right");

		userProvider.updateUserCredentials("demo", data, user);

		verify(dbUser).setPassword("newPassword");
	}

	@Test
	void updateUserCredentials_refusesWhenTheIdentityServiceIsReadOnly() {
		when(identityService.isReadOnly()).thenReturn(true);

		assertThatThrownBy(() -> userProvider.updateUserCredentials("demo", new HashMap<>(), user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("read-only");
	}

	// ---------- groups and memberships ----------

	@Test
	void createGroup_savesANewEngineGroup() {
		Group newGroup = mock(Group.class);
		when(identityService.newGroup("sales")).thenReturn(newGroup);

		userProvider.createGroup(new UserGroup("sales", "Sales", "WORKFLOW"), user);

		verify(newGroup).setName("Sales");
		verify(newGroup).setType("WORKFLOW");
		verify(identityService).saveGroup(newGroup);
	}

	@Test
	void createGroup_refusesWhenTheIdentityServiceIsReadOnly() {
		when(identityService.isReadOnly()).thenReturn(true);

		assertThatThrownBy(() -> userProvider.createGroup(new UserGroup("sales", "Sales", "WORKFLOW"), user))
			.isInstanceOf(SystemException.class);
		verify(identityService, never()).saveGroup(any());
	}

	@Test
	void deleteGroup_delegatesToTheIdentityService() {
		userProvider.deleteGroup("sales", user);

		verify(identityService).deleteGroup("sales");
	}

	@Test
	void deleteGroup_refusesWhenTheIdentityServiceIsReadOnly() {
		when(identityService.isReadOnly()).thenReturn(true);

		assertThatThrownBy(() -> userProvider.deleteGroup("sales", user))
			.isInstanceOf(SystemException.class);
		verify(identityService, never()).deleteGroup(Mockito.anyString());
	}

	@Test
	void addMemberToGroup_createsTheMembershipUserFirst() {
		userProvider.addMemberToGroup("sales", "demo", user);

		// the engine's argument order is (userId, groupId) - the webclient's is (groupId, userId)
		verify(identityService).createMembership("demo", "sales");
	}

	@Test
	void deleteMemberFromGroup_removesTheMembership() {
		userProvider.deleteMemberFromGroup("sales", "demo", user);

		verify(identityService).deleteMembership("demo", "sales");
	}

	@Test
	void addMemberToGroup_refusesWhenTheIdentityServiceIsReadOnly() {
		when(identityService.isReadOnly()).thenReturn(true);

		assertThatThrownBy(() -> userProvider.addMemberToGroup("sales", "demo", user))
			.isInstanceOf(SystemException.class);
		verify(identityService, never()).createMembership(Mockito.anyString(), Mockito.anyString());
	}
}
