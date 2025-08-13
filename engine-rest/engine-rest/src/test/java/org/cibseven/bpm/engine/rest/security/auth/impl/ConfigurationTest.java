package org.cibseven.bpm.engine.rest.security.auth.impl;

import org.cibseven.bpm.engine.rest.security.auth.impl.jwt.Configuration;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

import uk.org.webcompere.systemstubs.rules.EnvironmentVariablesRule;

import org.junit.Rule;
import org.cibseven.bpm.engine.rest.helper.MockProvider;

public class ConfigurationTest {
	
	@Rule
	public EnvironmentVariablesRule environmentVariablesRule =
	  new EnvironmentVariablesRule("CIBSEVEN_WEBCLIENT_AUTHENTICATION_JWTSECRET", MockProvider.EXAMPLE_CONFIGURATION_JWTSECRET_ENVIRONMENT);
	
	@Ignore
	@Test
	public void testConfigurationWithEnvironment() {
        Assert.assertEquals(System.getenv("CIBSEVEN_WEBCLIENT_AUTHENTICATION_JWTSECRET"), MockProvider.EXAMPLE_CONFIGURATION_JWTSECRET_ENVIRONMENT);
        String jwtSecret = Configuration.getInstance().getSecret();
	    Assert.assertEquals(MockProvider.EXAMPLE_CONFIGURATION_JWTSECRET_ENVIRONMENT, jwtSecret);
	}

}
