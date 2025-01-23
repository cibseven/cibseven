package org.camunda.bpm.engine.impl.identity.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.identity.User;
import org.camunda.bpm.engine.impl.persistence.entity.UserEntity;
import org.camunda.bpm.engine.test.ProcessEngineRule;
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
		
		String userId1 = "Jonny";
		{
			UserEntity user = new UserEntity();
			user.setId(userId1);
			user.setPassword(userId1);
			identityService.saveUser(user);
		}
		
		String userId2 = "Jonny";
		{
			UserEntity user = new UserEntity();
			user.setId(userId2);
			user.setPassword(userId2);
			identityService.saveUser(user);
		}

		try {
			assertTrue(identityService.checkPassword(userId1, userId1));
			assertTrue(identityService.checkPassword(userId2, userId2));
			assertTrue(identityService.checkPassword(userId1.toUpperCase(), userId1));
			assertFalse(identityService.checkPassword(userId2.toUpperCase(), userId2));
			
			assertFalse(identityService.checkPassword(userId1, "wrongpassword"));
			assertFalse(identityService.checkPassword(userId2, "wrongpassword"));
			
			assertFalse(identityService.checkPassword(userId1.toUpperCase(), "wrongpassword"));
			assertFalse(identityService.checkPassword(userId2.toUpperCase(), "wrongpassword"));
			
		} finally {
			identityService.deleteUser(userId1);
			identityService.deleteUser(userId2);
		}
		
	}
	
}
