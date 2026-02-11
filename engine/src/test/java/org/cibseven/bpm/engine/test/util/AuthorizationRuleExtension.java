package org.cibseven.bpm.engine.test.util;

import java.lang.reflect.Field;

import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.api.authorization.util.AuthorizationTestRule;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;


public class AuthorizationRuleExtension implements AfterEachCallback, BeforeEachCallback, TestInstancePostProcessor {
	  private ProcessEngineRule engineRule;
	  private AuthorizationTestRule authRule;
	  AuthorizationRuleExtension() {
		  engineRule = new ProvidedProcessEngineRule();
		  authRule = new AuthorizationTestRule(engineRule);
	  }
	  
	  @Override
	  public void beforeEach(ExtensionContext context) throws Exception {
			engineRule.beforeEach(context);
			//authRule.beforeEach(context);
		
	  }
	  @Override
	  public void afterEach(ExtensionContext context) throws Exception {
		  //authRule.beforeEach(context);
			engineRule.beforeEach(context);
		
	  }

		@Override
		public void postProcessTestInstance(Object testInstance, ExtensionContext context) throws Exception {
	        Field field = testInstance.getClass().getDeclaredField("authRule");
	        field.setAccessible(true);
	        field.set(testInstance, authRule);
	        field = testInstance.getClass().getDeclaredField("engineRule");
	        field.setAccessible(true);
	        field.set(testInstance, engineRule);
		}
	  
}
