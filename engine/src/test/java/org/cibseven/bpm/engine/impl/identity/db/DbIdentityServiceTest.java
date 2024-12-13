package org.cibseven.bpm.engine.impl.identity.db;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.persistence.entity.UserEntity;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

public class DbIdentityServiceTest {

	private static EmbeddedPostgres embeddedPostgres;
	
	@BeforeClass
	public static void setUpClass() throws IOException {
		embeddedPostgres = EmbeddedPostgres.builder().start();
	}
	
	@Rule
	public final ProcessEngineRule processEngineRuleH2 = new ProcessEngineRule(
		ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
//			.setJdbcUrl("jdbc:h2:mem:camunda;DB_CLOSE_DELAY=-1")
//			.setJdbcDriver("org.h2.Driver")
//			.setJdbcUsername("sa")
//			.setJdbcPassword("")
			.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
			.buildProcessEngine()
	);
	
	@Rule
	public final ProcessEngineRule processEngineRulePG = new ProcessEngineRule(
		ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
			.setJdbcUrl(embeddedPostgres.getJdbcUrl("postgres", "postgres"))
			.setJdbcDriver("org.postgresql.Driver")
			.setJdbcUsername("postgres")
			.setJdbcPassword("postgres")
			.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
			.buildProcessEngine()
	);
	
	@Test
	public void testCheckPasswordCaseInsensitiveUserIdH2() {
		checkPasswordCaseInsensitiveUserId(processEngineRuleH2.getIdentityService());
	}
	
	@Test
	public void testCheckPasswordCaseInsensitiveUserIdPG() {
		checkPasswordCaseInsensitiveUserId(processEngineRulePG.getIdentityService());
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
	public void testCheckPasswordWithSimilarUserIdsH2() {
		checkPasswordWithSimilarUserIds(processEngineRulePG.getIdentityService());
	}
	
	@Test
	public void testCheckPasswordWithSimilarUserIdsPG() {
		checkPasswordWithSimilarUserIds(processEngineRulePG.getIdentityService());
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

	@AfterClass
	public static void tearDownClass() throws IOException {
		if (embeddedPostgres != null) {
			embeddedPostgres.close();
		}
	}

}
