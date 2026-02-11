package org.cibseven.bpm.engine.test.util;

import java.lang.reflect.Field;

import org.cibseven.bpm.engine.test.api.runtime.migration.MigrationTestRule;
//import org.cibseven.bpm.engine.test.api.runtime.migration.batch.BatchMigrationHelper;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;

public class MigrationRuleExtension implements AfterEachCallback, BeforeEachCallback, TestInstancePostProcessor {

	private  ProvidedProcessEngineRule engineRule;
	private  ProcessEngineTestRule testRule;
	private  MigrationTestRule migrationRule;

	public MigrationRuleExtension() {
		engineRule = new ProvidedProcessEngineRule();
		testRule = new ProcessEngineTestRule(engineRule);
		migrationRule = new MigrationTestRule(engineRule);
	}
	@Override
	public void beforeEach(ExtensionContext context) throws Exception {
		engineRule.beforeEach(context);
		testRule.beforeEach(context);
		migrationRule.beforeEach(context);
	}
	@Override
	public void afterEach(ExtensionContext context) throws Exception {
		migrationRule.afterEach(context);
		testRule.afterEach(context);
		engineRule.afterEach(context);
	}
	@Override
	public void postProcessTestInstance(Object testInstance, ExtensionContext context) throws Exception {
        Field field = testInstance.getClass().getDeclaredField("testRule");
        field.setAccessible(true);
        field.set(testInstance, testRule);
        field = testInstance.getClass().getDeclaredField("engineRule");
        field.setAccessible(true);
        field.set(testInstance, engineRule);
        field = testInstance.getClass().getDeclaredField("migrationRule");
        field.setAccessible(true);
        field.set(testInstance, migrationRule);
	}
}
