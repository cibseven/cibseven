package org.cibseven.bpm.engine.test.util;

import java.lang.reflect.Field;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;

public class ProvidedProcessEngineExtension implements AfterEachCallback, BeforeEachCallback, TestInstancePostProcessor {
	private ProvidedProcessEngineRule engineRule = new ProvidedProcessEngineRule();

	@Override
	public void beforeEach(ExtensionContext context) throws Exception {
		engineRule.beforeEach(context);
		
	}

	@Override
	public void afterEach(ExtensionContext context) throws Exception {
		engineRule.afterEach(context);
		
	}

	@Override
	public void postProcessTestInstance(Object testInstance, ExtensionContext context) throws Exception {
		Field field = testInstance.getClass().getDeclaredField("engineRule");
        field.setAccessible(true);
        field.set(testInstance, engineRule);
	}
}
