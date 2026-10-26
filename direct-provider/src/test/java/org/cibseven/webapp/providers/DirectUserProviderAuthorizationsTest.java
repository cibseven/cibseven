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

import java.util.List;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.authorization.Authorization;
import org.cibseven.bpm.engine.authorization.Permissions;
import org.cibseven.bpm.engine.authorization.Resources;
import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.User;
import org.cibseven.bpm.engine.impl.identity.Authentication;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.rest.model.Authorizations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The embedded-engine counterpart of {@code GET /authorization/self}, exercised against a real
 * in-memory engine rather than mocks: the point of the implementation is that the engine's own
 * authorization checks do not filter what it reads, so a test that stubbed the queries would prove
 * nothing.
 *
 * <p>Covers the matrix the REST path is verified over - direct grant, group grant, global grant -
 * for each of the ways the engine can be authenticated when the call arrives: as
 * {@link AuthorizingProviderProxy} leaves it with the memberships, without them, and not at all.</p>
 */
class DirectUserProviderAuthorizationsTest {

	private static final String ENGINE_NAME = "authorizations";
	private static final CIBUser TESTER = testerOn(ENGINE_NAME);

	private ProcessEngine engine;
	private DirectUserProvider provider;

	@BeforeEach
	void setUp() {
		engine = buildEngine(true);
		provider = providerFor(engine);
		engine.getIdentityService().saveUser(newUser("tester"));
	}

	@AfterEach
	void tearDown() {
		engine.getIdentityService().clearAuthentication();
		engine.close();
	}

	@Test
	void aGrantOnTheUserIsReported() {
		grant(authorization -> {
			authorization.setUserId("tester");
			authorization.setResource(Resources.PROCESS_DEFINITION);
			authorization.setResourceId("invoice");
			authorization.addPermission(Permissions.READ);
		});
		authenticateTester(List.of());

		Authorizations authorizations = provider.getUserAuthorization(TESTER);

		assertThat(authorizations.getProcessDefinition()).extracting("resourceId").containsExactly("invoice");
		assertThat(authorizations.getTask()).isEmpty();
	}

	@Test
	void aGrantOnAnotherUserIsNotReported() {
		engine.getIdentityService().saveUser(newUser("other"));
		grant(authorization -> {
			authorization.setUserId("other");
			authorization.setResource(Resources.PROCESS_DEFINITION);
			authorization.setResourceId("invoice");
			authorization.addPermission(Permissions.READ);
		});
		authenticateTester(List.of());

		assertThat(provider.getUserAuthorization(TESTER).getProcessDefinition()).isEmpty();
	}

	/** The global grants apply to everybody, so they belong in the answer as well. */
	@Test
	void aGlobalGrantIsReported() {
		globalGrant(authorization -> {
			authorization.setResource(Resources.TASK);
			authorization.setResourceId("*");
			authorization.addPermission(Permissions.READ);
		});
		authenticateTester(List.of());

		assertThat(provider.getUserAuthorization(TESTER).getTask()).extracting("resourceId").containsExactly("*");
	}

	/** How the call arrives in production: the proxy authenticated the user with its memberships. */
	@Test
	void aGroupGrantIsReportedFromTheAuthenticatedMemberships() {
		grantToGroupOfTester();
		authenticateTester(List.of("accounting"));

		assertThat(provider.getUserAuthorization(TESTER).getProcessDefinition())
				.extracting("resourceId").containsExactly("invoice");
	}

	/** Authenticated without memberships in the context: they have to be resolved, unfiltered. */
	@Test
	void aGroupGrantIsReportedWhenTheAuthenticationCarriesNoGroups() {
		grantToGroupOfTester();
		authenticateTester(null);

		assertThat(provider.getUserAuthorization(TESTER).getProcessDefinition())
				.extracting("resourceId").containsExactly("invoice");
	}

	/**
	 * The engine is left unauthenticated whenever it has authorization disabled, because the proxy
	 * skips authenticating then - nothing may be read from an authentication that is not there.
	 */
	@Test
	void theAuthorizationsAreReportedWhenTheEngineIsNotAuthenticated() {
		grantToGroupOfTester();
		grant(authorization -> {
			authorization.setUserId("tester");
			authorization.setResource(Resources.TASK);
			authorization.setResourceId("*");
			authorization.addPermission(Permissions.READ);
		});

		Authorizations authorizations = provider.getUserAuthorization(TESTER);

		assertThat(authorizations.getProcessDefinition()).extracting("resourceId").containsExactly("invoice");
		assertThat(authorizations.getTask()).extracting("resourceId").containsExactly("*");
	}

	/** The same, with authorization switched off on the engine - the configuration that leads to it. */
	@Test
	void theAuthorizationsAreReportedWhenAuthorizationIsDisabled() {
		engine.close();
		engine = buildEngine(false);
		provider = providerFor(engine);
		engine.getIdentityService().saveUser(newUser("tester"));
		grant(authorization -> {
			authorization.setUserId("tester");
			authorization.setResource(Resources.PROCESS_DEFINITION);
			authorization.setResourceId("invoice");
			authorization.addPermission(Permissions.READ);
		});

		assertThat(provider.getUserAuthorization(TESTER).getProcessDefinition())
				.extracting("resourceId").containsExactly("invoice");
	}

	/**
	 * The reason the queries run unauthenticated: the user holds no READ on the Authorization resource,
	 * so a query it is allowed to make returns nothing - and the authorizations would be reported empty.
	 */
	@Test
	void theGrantsAreReportedAlthoughTheUserMayNotReadThem() {
		grant(authorization -> {
			authorization.setUserId("tester");
			authorization.setResource(Resources.PROCESS_DEFINITION);
			authorization.setResourceId("invoice");
			authorization.addPermission(Permissions.READ);
		});
		authenticateTester(List.of());

		assertThat(engine.getAuthorizationService().createAuthorizationQuery().count()).isZero();
		assertThat(provider.getUserAuthorization(TESTER).getProcessDefinition())
				.extracting("resourceId").containsExactly("invoice");
	}

	/** The authentication the call arrived with has to survive it, whatever the queries needed. */
	@Test
	void theAuthenticationIsRestored() {
		authenticateTester(List.of("accounting"));

		provider.getUserAuthorization(TESTER);

		Authentication authentication = engine.getIdentityService().getCurrentAuthentication();
		assertThat(authentication).isNotNull();
		assertThat(authentication.getUserId()).isEqualTo("tester");
		assertThat(authentication.getGroupIds()).containsExactly("accounting");
	}

	private void grantToGroupOfTester() {
		Group group = engine.getIdentityService().newGroup("accounting");
		engine.getIdentityService().saveGroup(group);
		engine.getIdentityService().createMembership("tester", "accounting");
		grant(authorization -> {
			authorization.setGroupId("accounting");
			authorization.setResource(Resources.PROCESS_DEFINITION);
			authorization.setResourceId("invoice");
			authorization.addPermission(Permissions.READ);
		});
	}

	/** Leaves the engine authenticated as the proxy does before delegating to a provider. */
	private void authenticateTester(List<String> groupIds) {
		engine.getIdentityService().setAuthentication("tester", groupIds);
	}

	private void grant(java.util.function.Consumer<Authorization> configurer) {
		saveAuthorization(Authorization.AUTH_TYPE_GRANT, configurer);
	}

	private void globalGrant(java.util.function.Consumer<Authorization> configurer) {
		saveAuthorization(Authorization.AUTH_TYPE_GLOBAL, configurer);
	}

	private void saveAuthorization(int type, java.util.function.Consumer<Authorization> configurer) {
		Authorization authorization = engine.getAuthorizationService().createNewAuthorization(type);
		configurer.accept(authorization);
		engine.getAuthorizationService().saveAuthorization(authorization);
	}

	private User newUser(String id) {
		User user = engine.getIdentityService().newUser(id);
		user.setPassword(id);
		return user;
	}

	/**
	 * A real {@code DirectProviderUtil} with the in-memory engine seeded into its caches, so no part
	 * of the engine lookup is stubbed: Mockito cannot instrument that class, and mocking the engine
	 * itself would defeat the purpose of the test.
	 */
	private DirectUserProvider providerFor(ProcessEngine processEngine) {
		DirectProviderUtil providerUtil = new DirectProviderUtil();
		providerUtil.processEngines.put(processEngine.getName(), processEngine);
		providerUtil.objectMappers.put(processEngine.getName(), new ObjectMapper());
		return new DirectUserProvider(providerUtil, "org.cibseven.webapp.auth.SevenUserProvider", "");
	}

	private ProcessEngine buildEngine(boolean authorizationEnabled) {
		ProcessEngineConfiguration configuration = ProcessEngineConfiguration
			.createStandaloneInMemProcessEngineConfiguration();
		return configuration
			.setJdbcUrl("jdbc:h2:mem:" + ENGINE_NAME + authorizationEnabled + ";DB_CLOSE_DELAY=1000")
			.setProcessEngineName(ENGINE_NAME)
			.setAuthorizationEnabled(authorizationEnabled)
			.setJobExecutorActivate(false)
			.buildProcessEngine();
	}

	private static CIBUser testerOn(String engineName) {
		CIBUser user = new CIBUser("tester");
		user.setEngine(engineName);
		return user;
	}
}
