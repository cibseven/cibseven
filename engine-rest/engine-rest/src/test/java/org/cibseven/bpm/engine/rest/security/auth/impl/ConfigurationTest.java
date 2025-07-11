package org.cibseven.bpm.engine.rest.security.auth.impl;

import org.cibseven.bpm.engine.rest.security.auth.impl.jwt.Configuration;
import org.junit.Assert;
import org.junit.Test;

import uk.org.webcompere.systemstubs.rules.EnvironmentVariablesRule;

import org.junit.Rule;
import org.cibseven.bpm.engine.rest.helper.MockProvider;

public class ConfigurationTest {

	@Rule
	public EnvironmentVariablesRule environmentVariablesRule = new EnvironmentVariablesRule();

	@Test
	public void testConfigurationWithEnvironment() {

		String key = "CIBSEVEN_WEBCLIENT_AUTHENTICATION_JWTSECRET";

        	environmentVariablesRule.set(key, MockProvider.EXAMPLE_CONFIGURATION_JWTSECRET_ENVIRONMENT);

	        Assert.assertEquals(System.getenv(key), MockProvider.EXAMPLE_CONFIGURATION_JWTSECRET_ENVIRONMENT);

        	String jwtSecret = Configuration.getInstance().getSecret();

	   	Assert.assertEquals(MockProvider.EXAMPLE_CONFIGURATION_JWTSECRET_ENVIRONMENT, jwtSecret);

	}

}
