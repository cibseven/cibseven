package org.cibseven.bpm.engine.rest.security.auth.impl;

import org.cibseven.bpm.engine.rest.security.auth.impl.jwt.Configuration;
import org.junit.Assert;
import org.junit.Test;

import uk.org.webcompere.systemstubs.rules.EnvironmentVariablesRule;

import org.junit.Rule;
import org.cibseven.bpm.engine.rest.helper.MockProvider;

public class ConfigurationTest {
	
	@Rule
	public EnvironmentVariablesRule environmentVariablesRule =
	  new EnvironmentVariablesRule("CIBSEVEN_WEBCLIENT_AUTHENTICATION_JWTSECRET", MockProvider.EXAMPLE_CONFIGURATION_JWTSECRET_ENVIRONMENT);
	
	@Test
	public void testConfigurationWithEnvironment() {
        String jwtSecret = Configuration.getInstance().getSecret();
	    Assert.assertEquals(MockProvider.EXAMPLE_CONFIGURATION_JWTSECRET_ENVIRONMENT, jwtSecret);
	}

}
/*
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.core.classloader.annotations.SuppressStaticInitializationFor;
import org.powermock.modules.junit4.PowerMockRunner;

@RunWith(PowerMockRunner.class)
@PrepareForTest( { System.class })
@SuppressStaticInitializationFor("java.lang.System") // <-- this is it :)
public class ConfigurationTest {
	
	@Test
	public void testConfigurationWithEnvironment() {
		PowerMockito.mockStatic(System.class);
        Mockito.when(System.getenv("CIBSEVEN_WEBCLIENT_AUTHENTICATION_JWTSECRET"))
            .thenReturn(MockProvider.EXAMPLE_CONFIGURATION_JWTSECRET_ENVIRONMENT);		

        String jwtSecret = Configuration.getInstance().getSecret();
	    Assert.assertEquals(MockProvider.EXAMPLE_CONFIGURATION_JWTSECRET_ENVIRONMENT, jwtSecret);
	}

//	@Test
//	public void testConfigurationFromProperties() {
//	    String jwtSecret = Configuration.getInstance().getSecret();
//	    Assert.assertEquals(MockProvider.EXAMPLE_CONFIGURATION_JWTSECRET_PROPERTY, jwtSecret);
//	}
//
//	@Test(expected = IllegalStateException.class)
//	public void testConfigurationFail() {
//	    Configuration.getInstance().getSecret();
//	}

}
*/