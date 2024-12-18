package org.camunda.bpm.engine.impl.identity.db;

import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.test.ProcessEngineRule;
import org.junit.Rule;

public class DbIdentityServiceH2Test extends DbIdentityServiceTestAbstract {

	@Rule
	public final ProcessEngineRule processEngineRule = new ProcessEngineRule(
		ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
//			.setJdbcUrl("jdbc:h2:mem:camunda;DB_CLOSE_DELAY=-1")
//			.setJdbcDriver("org.h2.Driver")
//			.setJdbcUsername("sa")
//			.setJdbcPassword("")
			.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
			.setHistory(ProcessEngineConfiguration.HISTORY_FULL)
			.buildProcessEngine()
	);
	
	@Override
	protected ProcessEngineRule getProcessEngineRule() {
		return processEngineRule;
	}

}
