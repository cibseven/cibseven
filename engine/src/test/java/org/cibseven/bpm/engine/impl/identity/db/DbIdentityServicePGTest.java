package org.cibseven.bpm.engine.impl.identity.db;

import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.junit.ClassRule;
import org.junit.Rule;

import io.zonky.test.db.postgres.junit.EmbeddedPostgresRules;
import io.zonky.test.db.postgres.junit.SingleInstancePostgresRule;

public class DbIdentityServicePGTest extends DbIdentityServiceTestAbstract {

	@ClassRule
	public static SingleInstancePostgresRule pg = EmbeddedPostgresRules.singleInstance();
	
	@Rule
	public final ProcessEngineRule processEngineRule = new ProcessEngineRule(
		ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
			.setJdbcUrl(pg.getEmbeddedPostgres().getJdbcUrl("postgres", "postgres"))
			.setJdbcDriver("org.postgresql.Driver")
			.setJdbcUsername("postgres")
			.setJdbcPassword("postgres")
			.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
			.setHistory(ProcessEngineConfiguration.HISTORY_FULL)
			.buildProcessEngine()
	);
	
	@Override
	protected ProcessEngineRule getProcessEngineRule() {
		return processEngineRule;
	}
	
}
