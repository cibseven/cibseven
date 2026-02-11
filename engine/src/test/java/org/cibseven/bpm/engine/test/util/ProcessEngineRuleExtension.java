package org.cibseven.bpm.engine.test.util;

import java.lang.reflect.Field;

import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;

public class ProcessEngineRuleExtension  implements AfterEachCallback, BeforeEachCallback, TestInstancePostProcessor {
	protected ProcessEngineRule engineRule;
	protected ProcessEngineTestRule testRule;

	public ProcessEngineRuleExtension() {
		engineRule = new ProcessEngineRule();
		testRule = new ProcessEngineTestRule(engineRule);
	}
	@Override
	public void beforeEach(ExtensionContext context) throws Exception {
		engineRule.beforeEach(context);
		testRule.beforeEach(context);
	}
	@Override
	public void afterEach(ExtensionContext context) throws Exception {
		testRule.afterEach(context);
		engineRule.afterEach(context);
	}
	@Override
	public void postProcessTestInstance(Object testInstance, ExtensionContext context) throws Exception {
		Field[] fields = testInstance.getClass().getDeclaredFields();
		for (Field field : fields) {
			if (field.getName().equals("testRule")) {
				field.setAccessible(true);
				field.set(testInstance, testRule);
			} else if (field.getName().equals("engineRule")) {
				field.setAccessible(true);
				field.set(testInstance, engineRule);
			}
		}
	}
}
