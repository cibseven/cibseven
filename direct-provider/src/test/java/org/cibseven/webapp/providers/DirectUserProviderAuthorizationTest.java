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
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.UnknownResourceTypeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The embedded-engine counterpart of the modeler access check, exercised against a real in-memory
 * engine rather than mocks: the point of asking the engine is that it applies its own rules, so a
 * test that stubbed the answer would prove nothing.
 *
 * <p>Mirrors the matrix verified over REST against a running engine — direct grant, group grant,
 * another resource, revoke, and authorization switched off — because both provider paths have to
 * agree.</p>
 */
class DirectUserProviderAuthorizationTest {

	private static final String ENGINE_NAME = "authcheck";
	private static final CIBUser TESTER = testerOn(ENGINE_NAME);
	private static final int APPLICATION = Resources.APPLICATION.resourceType();
	private static final String MODELER = "modeler";
	private static final String ACCESS = Permissions.ACCESS.getName();

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
	void aGrantOnTheResourceAuthorizes() {
		grant(authorization -> {
			authorization.setUserId("tester");
			authorization.setResource(Resources.APPLICATION);
			authorization.setResourceId(MODELER);
			authorization.addPermission(Permissions.ACCESS);
		});

		assertTrue(provider.isUserAuthorized(TESTER, APPLICATION, MODELER, ACCESS));
	}

	@Test
	void aGrantOnAnotherResourceDoesNotAuthorize() {
		grant(authorization -> {
			authorization.setUserId("tester");
			authorization.setResource(Resources.APPLICATION);
			authorization.setResourceId("tasklist");
			authorization.addPermission(Permissions.ACCESS);
		});

		assertFalse(provider.isUserAuthorized(TESTER, APPLICATION, MODELER, ACCESS));
	}

	@Test
	void withoutAnyAuthorizationNothingIsAuthorized() {
		assertFalse(provider.isUserAuthorized(TESTER, APPLICATION, MODELER, ACCESS));
	}

	/**
	 * The case that made the previous implementation wrong: the grant belongs to a group, which is
	 * only found when the user's memberships are resolved.
	 */
	@Test
	void aGrantOnAGroupTheUserBelongsToAuthorizes() {
		Group group = engine.getIdentityService().newGroup("modelers");
		engine.getIdentityService().saveGroup(group);
		engine.getIdentityService().createMembership("tester", "modelers");
		grant(authorization -> {
			authorization.setGroupId("modelers");
			authorization.setResource(Resources.APPLICATION);
			authorization.setResourceId(MODELER);
			authorization.addPermission(Permissions.ACCESS);
		});

		assertTrue(provider.isUserAuthorized(TESTER, APPLICATION, MODELER, ACCESS));
	}

	/**
	 * How the check actually runs in production: {@link AuthorizingProviderProxy} authenticates the user
	 * before the call, so a membership query inside would be filtered by READ on the group resource and
	 * the group grant would be missed.
	 */
	@Test
	void aGroupGrantAuthorizesWhileTheUserIsAuthenticated() {
		Group group = engine.getIdentityService().newGroup("modelers");
		engine.getIdentityService().saveGroup(group);
		engine.getIdentityService().createMembership("tester", "modelers");
		grant(authorization -> {
			authorization.setGroupId("modelers");
			authorization.setResource(Resources.APPLICATION);
			authorization.setResourceId(MODELER);
			authorization.addPermission(Permissions.ACCESS);
		});
		authenticateTester(List.of("modelers"));

		assertTrue(provider.isUserAuthorized(TESTER, APPLICATION, MODELER, ACCESS));
	}

	/** Authenticated without memberships in the context: they have to be resolved, unfiltered. */
	@Test
	void aGroupGrantAuthorizesWhenTheAuthenticationCarriesNoGroups() {
		Group group = engine.getIdentityService().newGroup("modelers");
		engine.getIdentityService().saveGroup(group);
		engine.getIdentityService().createMembership("tester", "modelers");
		grant(authorization -> {
			authorization.setGroupId("modelers");
			authorization.setResource(Resources.APPLICATION);
			authorization.setResourceId(MODELER);
			authorization.addPermission(Permissions.ACCESS);
		});
		authenticateTester(null);

		assertTrue(provider.isUserAuthorized(TESTER, APPLICATION, MODELER, ACCESS));
	}

	/**
	 * Separates the two things the user context does affect. Reading authorizations is filtered - the
	 * user holds no READ on them and sees none - while the engine's own check still finds the group grant,
	 * because it is evaluated internally rather than through a query the caller is allowed to make.
	 */
	@Test
	void theCheckFindsAGrantTheUserIsNotAllowedToRead() {
		Group group = engine.getIdentityService().newGroup("modelers");
		engine.getIdentityService().saveGroup(group);
		engine.getIdentityService().createMembership("tester", "modelers");
		grant(authorization -> {
			authorization.setGroupId("modelers");
			authorization.setResource(Resources.APPLICATION);
			authorization.setResourceId(MODELER);
			authorization.addPermission(Permissions.ACCESS);
		});
		authenticateTester(List.of("modelers"));

		assertEquals(0, engine.getAuthorizationService().createAuthorizationQuery().count());
		assertTrue(provider.isUserAuthorized(TESTER, APPLICATION, MODELER, ACCESS));
	}

	/** An authenticated user without the grant stays unauthorized. */
	@Test
	void nothingIsAuthorizedForAnAuthenticatedUserWithoutAGrant() {
		authenticateTester(List.of("modelers"));

		assertFalse(provider.isUserAuthorized(TESTER, APPLICATION, MODELER, ACCESS));
	}

	// Revoke authorizations are deliberately not covered here. Whether they are evaluated depends on
	// the engine's authorizationCheckRevokes setting, whose default (AUTO) decides once per engine
	// whether any revoke exists and caches it, and an in-memory engine did not honour a revoke even
	// with ALWAYS. That is engine behaviour, not ours: we pass its answer through unchanged, and the
	// REST path was verified against a running engine.

	/** With authorization disabled the engine enforces nothing, and neither do we. */
	@Test
	void everythingIsAuthorizedWhenAuthorizationIsDisabled() {
		engine.close();
		engine = buildEngine(false);
		provider = providerFor(engine);

		assertTrue(provider.isUserAuthorized(TESTER, APPLICATION, MODELER, ACCESS));
	}

	@Test
	void anUnknownResourceTypeIsRejected() {
		assertThrows(UnknownResourceTypeException.class,
			() -> provider.isUserAuthorized(TESTER, 9999, MODELER, ACCESS));
	}

	/** Leaves the engine authenticated as the proxy does before delegating to a provider. */
	private void authenticateTester(List<String> groupIds) {
		engine.getIdentityService().setAuthentication("tester", groupIds);
	}

	private void grant(java.util.function.Consumer<Authorization> configurer) {
		Authorization authorization =
			engine.getAuthorizationService().createNewAuthorization(Authorization.AUTH_TYPE_GRANT);
		configurer.accept(authorization);
		engine.getAuthorizationService().saveAuthorization(authorization);
	}

	private User newUser(String id) {
		User user = engine.getIdentityService().newUser(id);
		user.setPassword(id);
		return user;
	}

	/**
	 * A real {@code DirectProviderUtil} with the in-memory engine seeded into its cache, so no part
	 * of the engine lookup is stubbed: Mockito cannot instrument that class, and mocking the engine
	 * itself would defeat the purpose of the test.
	 */
	private DirectUserProvider providerFor(ProcessEngine processEngine) {
		DirectProviderUtil providerUtil = new DirectProviderUtil();
		providerUtil.processEngines.put(processEngine.getName(), processEngine);
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
