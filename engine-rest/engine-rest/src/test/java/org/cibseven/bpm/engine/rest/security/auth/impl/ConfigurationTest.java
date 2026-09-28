package org.cibseven.bpm.engine.rest.security.auth.impl;

import org.cibseven.bpm.engine.rest.security.auth.impl.jwt.Configuration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

import org.cibseven.bpm.engine.rest.helper.MockProvider;

@ExtendWith(SystemStubsExtension.class)
public class ConfigurationTest {
	
    @SystemStub
    private EnvironmentVariables environment = 
      new EnvironmentVariables("CIBSEVEN_WEBCLIENT_AUTHENTICATION_JWTSECRET", MockProvider.EXAMPLE_CONFIGURATION_JWTSECRET_ENVIRONMENT);
	@Disabled

	@Test
	public void testConfigurationWithEnvironment() {
        Assertions.assertEquals(System.getenv("CIBSEVEN_WEBCLIENT_AUTHENTICATION_JWTSECRET"), MockProvider.EXAMPLE_CONFIGURATION_JWTSECRET_ENVIRONMENT);
        String jwtSecret = Configuration.getInstance().getSecret();
	    Assertions.assertEquals(MockProvider.EXAMPLE_CONFIGURATION_JWTSECRET_ENVIRONMENT, jwtSecret);
	}

}
