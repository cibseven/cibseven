package org.cibseven.bpm.engine.rest.security.auth.impl;

import org.cibseven.bpm.engine.rest.security.auth.impl.jwt.Configuration;
import org.junit.Assert;
import org.junit.Test;
import org.cibseven.bpm.engine.rest.helper.MockProvider;

public class ConfigurationNoEnvTest {
	
	@Test
	public void testConfigurationFromProperties() {
	    String jwtSecret = Configuration.getInstance().getSecret();
	    Assert.assertEquals(MockProvider.EXAMPLE_CONFIGURATION_JWTSECRET_PROPERTY, jwtSecret);
	}

}
