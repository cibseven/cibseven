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
	
	@Test
	public void testCheckPasswordCaseInsensitiveUserId() {
		checkPasswordCaseInsensitiveUserId(getProcessEngineRule().getIdentityService());
	}

	private void checkPasswordCaseInsensitiveUserId(IdentityService identityService) {
		// Create a test user
		UserEntity user = new UserEntity();
		String userId = "testuser";
		user.setId(userId);
		user.setPassword("s3cret");
		identityService.saveUser(user);

		try {
			// Test with different cases for userId
			assertTrue(identityService.checkPassword("TESTUSER", "s3cret"));
			assertTrue(identityService.checkPassword("testuser", "s3cret"));
			assertTrue(identityService.checkPassword("TestUser", "s3cret"));
			assertFalse(identityService.checkPassword("testuser", "wrongpassword"));
			assertFalse(identityService.checkPassword("wronguser", "s3cret"));
		} finally {
			identityService.deleteUser(userId);
		}
	}
	
	@Test
	public void testCheckPasswordWithSimilarUserIds() {
		checkPasswordWithSimilarUserIds(getProcessEngineRule().getIdentityService());
	}

	@Test
	public void testGetUserByQueryCaseInsensitive() {

		IdentityService identityService = getProcessEngineRule().getIdentityService();

		// Create a test user
		UserEntity user = new UserEntity();
		String userId = "demo";
		user.setId(userId);
		user.setPassword("s3cret");
		identityService.saveUser(user);

		try {
			User foundUser = identityService.createUserQuery().userId(userId).singleResult();
			assertNotNull(foundUser);
			assertEquals(user.getPassword(), foundUser.getPassword());
		} finally {
			identityService.deleteUser(userId);
		}
	}

	private void checkPasswordWithSimilarUserIds(IdentityService identityService) {
		
		UserEntity user1 = new UserEntity();
		String userId1 = "TestUser";
		user1.setId(userId1);
		user1.setPassword("password1");
		identityService.saveUser(user1);

		UserEntity user2 = new UserEntity();
		String userId2 = "TESTUSER";
		user2.setId(userId2);
		user2.setPassword("password2");
		identityService.saveUser(user2);

		try {
			assertTrue(identityService.checkPassword("TestUser", "password1"));
			assertTrue(identityService.checkPassword("TESTUSER", "password2"));
			
			assertTrue(identityService.checkPassword("testuser", "password1"));
			// first returned user has password1
			assertFalse(identityService.checkPassword("testuser", "password2"));
			
			assertFalse(identityService.checkPassword("TestUser", "wrongpassword"));
			assertFalse(identityService.checkPassword("TESTUSER", "wrongpassword"));
		} finally {
			identityService.deleteUser(userId1);
			identityService.deleteUser(userId2);
		}
	}
	
}
