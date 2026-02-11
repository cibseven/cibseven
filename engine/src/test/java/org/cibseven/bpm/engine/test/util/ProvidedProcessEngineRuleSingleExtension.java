package org.cibseven.bpm.engine.test.util;

import java.lang.reflect.Field;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;

public class ProvidedProcessEngineRuleSingleExtension implements AfterEachCallback, BeforeEachCallback, TestInstancePostProcessor {
	protected ProvidedProcessEngineRule engineRule;

	public ProvidedProcessEngineRuleSingleExtension() {
		engineRule = new ProvidedProcessEngineRule();
	}
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
		Field[] fields = testInstance.getClass().getDeclaredFields();
		for (Field field : fields) {
			if (field.getName().equals("engineRule")) {
				field.setAccessible(true);
				field.set(testInstance, engineRule);
			}
		}
	}
}
