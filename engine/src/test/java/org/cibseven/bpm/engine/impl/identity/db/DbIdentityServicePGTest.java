package org.cibseven.bpm.engine.impl.identity.db;

import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.zonky.test.db.postgres.junit5.EmbeddedPostgresExtension;
import io.zonky.test.db.postgres.junit5.SingleInstancePostgresExtension;

public class DbIdentityServicePGTest extends DbIdentityServiceTestAbstract  implements BeforeAllCallback{

	@RegisterExtension
	public SingleInstancePostgresExtension pg = EmbeddedPostgresExtension.singleInstance();
	
	@RegisterExtension
	public final ProcessEngineRule processEngineRule = new ProcessEngineRule();
	
	@Override
	protected ProcessEngineRule getProcessEngineRule() {
		return processEngineRule;
	}

  @Override
  public void beforeAll(ExtensionContext context) throws Exception {
    processEngineRule.setProcessEngine(     ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
        .setJdbcUrl(pg.getEmbeddedPostgres().getJdbcUrl("postgres", "postgres"))
        .setJdbcDriver("org.postgresql.Driver")
        .setJdbcUsername("postgres")
        .setJdbcPassword("postgres")
        .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
        .setHistory(ProcessEngineConfiguration.HISTORY_FULL)
        .buildProcessEngine()
        );
    
  }
	
}
