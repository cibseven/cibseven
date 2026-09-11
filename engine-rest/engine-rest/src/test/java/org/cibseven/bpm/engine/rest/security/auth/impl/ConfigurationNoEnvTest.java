package org.cibseven.bpm.engine.rest.security.auth.impl;

import org.cibseven.bpm.engine.rest.security.auth.impl.jwt.Configuration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.cibseven.bpm.engine.rest.helper.MockProvider;

public class ConfigurationNoEnvTest {
	
	@Test
	public void testConfigurationFromProperties() {
	    String jwtSecret = Configuration.getInstance().getSecret();
	    Assertions.assertEquals(MockProvider.EXAMPLE_CONFIGURATION_JWTSECRET_PROPERTY, jwtSecret);
	}

}
