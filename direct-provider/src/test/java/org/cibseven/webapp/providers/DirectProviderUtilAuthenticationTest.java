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
import java.util.concurrent.atomic.AtomicInteger;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.authorization.Authorization;
import org.cibseven.bpm.engine.authorization.Permissions;
import org.cibseven.bpm.engine.authorization.Resources;
import org.cibseven.bpm.engine.history.UserOperationLogEntry;
import org.cibseven.bpm.engine.identity.User;
import org.cibseven.bpm.engine.impl.identity.Authentication;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.webapp.auth.CIBUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine writes a user operation log entry only for an authenticated user, so
 * {@link DirectProviderUtil#runAsUser} has to authenticate even when authorization is disabled.
 *
 * <p>Run against a real in-memory engine, since the behaviour under test is the engine's own guard.
 * History level {@code full} is required: no other level produces {@code USER_OPERATION_LOG}.</p>
 */
class DirectProviderUtilAuthenticationTest {

	private static final String USER_ID = "tester";
	private static final AtomicInteger DB_SEQUENCE = new AtomicInteger();

	private ProcessEngine engine;
	private DirectProviderUtil providerUtil;

	@AfterEach
	void tearDown() {
		if (engine != null) {
			engine.getIdentityService().clearAuthentication();
			engine.close();
		}
	}

	/** The regression: the authentication used to be skipped here, so the engine wrote nothing. */
	@Test
	void theUserOperationLogIsWrittenWhenAuthorizationIsDisabled() {
		startEngine(false);
		String taskId = seedTask();

		providerUtil.runAsUser(userOnEngine(), () -> {
			engine.getTaskService().setAssignee(taskId, "somebody");
			return null;
		});

		UserOperationLogEntry entry = assigneeChange();
		assertNotNull(entry, "no user operation log entry was written for the assignee change");
		assertEquals(USER_ID, entry.getUserId(), "the entry must be attributed to the acting user");
	}

	/** The same operation is logged identically when authorization is enabled. */
	@Test
	void theUserOperationLogIsWrittenWhenAuthorizationIsEnabled() {
		startEngine(true);
		grantAllOnTasks();
		String taskId = seedTask();

		providerUtil.runAsUser(userOnEngine(), () -> {
			engine.getTaskService().setAssignee(taskId, "somebody");
			return null;
		});

		UserOperationLogEntry entry = assigneeChange();
		assertNotNull(entry, "no user operation log entry was written for the assignee change");
		assertEquals(USER_ID, entry.getUserId(), "the entry must be attributed to the acting user");
	}

	/** The acting user is visible to the engine while the action runs. */
	@Test
	void theUserIsAuthenticatedWhileTheActionRuns() {
		startEngine(false);

		String authenticatedDuringAction = providerUtil.runAsUser(userOnEngine(),
			() -> engine.getIdentityService().getCurrentAuthentication().getUserId());

		assertEquals(USER_ID, authenticatedDuringAction);
	}

	/** Groups are resolved into the authentication; callers read them back off it. */
	@Test
	void theResolvedGroupsAreCarriedInTheAuthentication() {
		startEngine(false);
		engine.getIdentityService().saveGroup(engine.getIdentityService().newGroup("testers"));
		engine.getIdentityService().createMembership(USER_ID, "testers");

		List<String> groupsDuringAction = providerUtil.runAsUser(userOnEngine(),
			() -> engine.getIdentityService().getCurrentAuthentication().getGroupIds());

		assertNotNull(groupsDuringAction);
		assertTrue(groupsDuringAction.contains("testers"), "expected the membership to be resolved");
	}

	/** A previously established authentication must survive the call. */
	@Test
	void thePreviousAuthenticationIsRestored() {
		startEngine(false);
		engine.getIdentityService().setAuthentication("someoneElse", List.of("otherGroup"));

		providerUtil.runAsUser(userOnEngine(), () -> null);

		Authentication restored = engine.getIdentityService().getCurrentAuthentication();
		assertNotNull(restored, "the previous authentication was dropped");
		assertEquals("someoneElse", restored.getUserId());
	}

	/** Without a user there is nobody to authenticate, and no engine is resolved either. */
	@Test
	void noAuthenticationIsSetWithoutAUser() {
		startEngine(false);
		DirectProviderUtil withoutEngineProvider = new DirectProviderUtil();

		Authentication authenticated = withoutEngineProvider.runAsUser(null,
			() -> engine.getIdentityService().getCurrentAuthentication());

		assertNull(authenticated);
	}

	// --- fixture ---------------------------------------------------------------------------------

	private CIBUser userOnEngine() {
		CIBUser user = new CIBUser(USER_ID);
		user.setEngine(engine.getName());
		return user;
	}

	private UserOperationLogEntry assigneeChange() {
		return engine.getHistoryService().createUserOperationLogQuery()
			.operationType(UserOperationLogEntry.OPERATION_TYPE_ASSIGN)
			.singleResult();
	}

	private String seedTask() {
		Task task = engine.getTaskService().newTask();
		engine.getTaskService().saveTask(task);
		return task.getId();
	}

	private void grantAllOnTasks() {
		Authorization authorization =
			engine.getAuthorizationService().createNewAuthorization(Authorization.AUTH_TYPE_GRANT);
		authorization.setUserId(USER_ID);
		authorization.setResource(Resources.TASK);
		authorization.setResourceId(Authorization.ANY);
		authorization.addPermission(Permissions.ALL);
		engine.getAuthorizationService().saveAuthorization(authorization);
	}

	/** Own database per engine: the assertions read the operation log, so state must not leak. */
	private void startEngine(boolean authorizationEnabled) {
		String name = "runAsUser" + DB_SEQUENCE.incrementAndGet();
		engine = ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
			.setJdbcUrl("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=1000")
			.setProcessEngineName(name)
			.setAuthorizationEnabled(authorizationEnabled)
			.setHistory(ProcessEngineConfiguration.HISTORY_FULL)
			.setJobExecutorActivate(false)
			.buildProcessEngine();
		providerUtil = new DirectProviderUtil();
		providerUtil.processEngines.put(engine.getName(), engine);
		User user = engine.getIdentityService().newUser(USER_ID);
		user.setPassword(USER_ID);
		engine.getIdentityService().saveUser(user);
	}
}
