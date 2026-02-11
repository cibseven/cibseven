package org.cibseven.bpm.engine.test.util;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;

import java.lang.reflect.Field;
import java.util.function.Consumer;

import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;

public class ProcessEngineBootstrapClassExtension implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback, TestInstancePostProcessor {

	public static ProcessEngineBootstrapRule bootstrapRule = null;
	private  ProvidedProcessEngineRule engineRule;
	private ProcessEngineTestRule testRule = null;
	private EntityRemoveRule entityRemoveRule = null;
	private boolean addTestRule = false; 
	private boolean addEntityRemoveRule = false;
	private boolean lazy = false;

	static public ProcessEngineBootstrapClassExtension builder() {
		return new ProcessEngineBootstrapClassExtension();
	}
	
	public ProcessEngineBootstrapClassExtension setConfigurationResource(String resource) {
		bootstrapRule = new ProcessEngineBootstrapRule(resource);
		return this;
	}
	
	public ProcessEngineBootstrapClassExtension useDefaultResource() {
		bootstrapRule = new ProcessEngineBootstrapRule();
		return this;
	}
	
	public ProcessEngineBootstrapClassExtension useConsumer(Consumer<ProcessEngineConfigurationImpl> processEngineConfigurator) {
		bootstrapRule = new ProcessEngineBootstrapRule(processEngineConfigurator);
		return this;
	}
	
	public ProcessEngineBootstrapClassExtension build() {
		return this;
	}

    public ProcessEngineBootstrapClassExtension addProcessEngineTestRule() {
    	addTestRule = true;
    	return this;
    }
	
    public ProcessEngineBootstrapClassExtension  addEntityRemoveRule(boolean isLazy) {
    	addEntityRemoveRule = true;
    	lazy = isLazy;
		return this;
	}
	
	@Override
	public void beforeAll(ExtensionContext context) throws Exception {
		engineRule = new ProvidedProcessEngineRule(bootstrapRule);
		if (addTestRule)
			testRule = new ProcessEngineTestRule(engineRule);
		if (addEntityRemoveRule) {
			if (lazy)
				entityRemoveRule = EntityRemoveRule.ofLazyRule(() -> testRule);
			else
				entityRemoveRule = EntityRemoveRule.of(testRule);
		}
	}

	@Override
	public void afterAll(ExtensionContext context) throws Exception {
		bootstrapRule.afterAll(context);
		addTestRule = false;
		testRule = null;
		engineRule = null;
	}

	@Override
	public void postProcessTestInstance(Object testInstance, ExtensionContext context) throws Exception {
		Field[] fields = testInstance.getClass().getDeclaredFields();
		for (Field field : fields) {
			if (field.getName().equals("engineRule")) {
				field.setAccessible(true);
				field.set(testInstance, engineRule);
			} else  if (field.getName().equals("processEngine")) {
				field.setAccessible(true);
				field.set(testInstance, bootstrapRule.getProcessEngine());
			} else  if (testRule != null && field.getName().equals("testRule")) {
				field.setAccessible(true);
				field.set(testInstance, testRule);
			}
		}
	}

	@Override
	public void afterEach(ExtensionContext context) throws Exception {
		if (entityRemoveRule != null)
			entityRemoveRule.afterEach(context);
		if (testRule != null)
			testRule.afterEach(context);
		engineRule.afterEach(context);
	}

	@Override
	public void beforeEach(ExtensionContext context) throws Exception {
		engineRule.beforeEach(context);
		if (testRule != null)
			testRule.beforeEach(context);
	}
	
}
