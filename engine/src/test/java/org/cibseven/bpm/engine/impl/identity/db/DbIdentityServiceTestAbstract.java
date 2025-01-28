package org.cibseven.bpm.engine.impl.identity.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.identity.User;
import org.cibseven.bpm.engine.impl.persistence.entity.UserEntity;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.junit.Test;

public abstract class DbIdentityServiceTestAbstract {

	protected abstract ProcessEngineRule getProcessEngineRule();

	private static UserEntity createUser(IdentityService identityService, String userId) {
		UserEntity user = new UserEntity();
		user.setId(userId);
		user.setPassword(userId);
		identityService.saveUser(user);
		return user;
	}

	@Test
	public void testCheckPasswordCaseInsensitiveUserId() {
		checkPasswordCaseInsensitiveUserId(getProcessEngineRule().getIdentityService());
	}

	private static void checkPasswordCaseInsensitiveUserId(IdentityService identityService) {

		String userId = "testuser";

		createUser(identityService, userId);

		try {
			// Test with different cases for userId
			assertTrue(identityService.checkPassword(userId, userId));
			assertTrue(identityService.checkPassword(userId.toUpperCase(), userId));
			assertTrue(identityService.checkPassword("TestUser", userId));

			assertFalse(identityService.checkPassword(userId, "wrongpassword"));
			assertFalse(identityService.checkPassword(userId.toUpperCase(), "wrongpassword"));
			assertFalse(identityService.checkPassword("TestUser", "wrongpassword"));

			assertFalse(identityService.checkPassword("wronguser", userId));
		} finally {
			identityService.deleteUser(userId);
		}
	}

	@Test
	public void testGetUserByQueryCaseInsensitive() {

		IdentityService identityService = getProcessEngineRule().getIdentityService();

		// Create a test user
		String userId = "demo";
		UserEntity user = createUser(identityService, userId);

		try {
			User foundUser = identityService.createUserQuery().userId(userId).singleResult();
			assertNotNull(foundUser);
			assertEquals(user.getPassword(), foundUser.getPassword());
		} finally {
			identityService.deleteUser(userId);
		}
	}

	@Test
	public void testCheckPasswordWithSimilarUserIds() {
		checkPasswordWithSimilarUserIds(getProcessEngineRule().getIdentityService());
	}

	private static void checkPasswordWithSimilarUserIds(IdentityService identityService) {

		String userId1 = "jonny";
		String userId2 = "Jonny";

		try {

			createUser(identityService, userId1);
			createUser(identityService, userId2);

			assertTrue(identityService.checkPassword(userId1, userId1));
			assertTrue(identityService.checkPassword(userId2, userId2));
			assertTrue(identityService.checkPassword(userId1.toUpperCase(), userId1));
			assertFalse(identityService.checkPassword(userId2.toUpperCase(), userId2));

			assertFalse(identityService.checkPassword(userId1, "wrongpassword"));
			assertFalse(identityService.checkPassword(userId2, "wrongpassword"));

			assertFalse(identityService.checkPassword(userId1.toUpperCase(), "wrongpassword"));
			assertFalse(identityService.checkPassword(userId2.toUpperCase(), "wrongpassword"));

		} finally {
			try {
				identityService.deleteUser(userId1);
			} finally {
				identityService.deleteUser(userId2);
			}
		}

	}

}
