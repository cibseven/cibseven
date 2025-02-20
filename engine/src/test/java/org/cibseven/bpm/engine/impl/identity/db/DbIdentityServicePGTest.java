package org.cibseven.bpm.engine.impl.identity.db;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.junit.ClassRule;
import org.junit.Rule;

import io.zonky.test.db.postgres.junit.EmbeddedPostgresRules;
import io.zonky.test.db.postgres.junit.SingleInstancePostgresRule;

public class DbIdentityServicePGTest extends DbIdentityServiceTestAbstract {

    private static Path getWorkingDirectory() {
        // Retrieve the Jenkins workspace directory from the environment variable
        final String workspaceDir = System.getenv("WORKSPACE");
        if (workspaceDir != null) {
            final Path tempPath = Paths.get(workspaceDir, "temp_embedded_pg");
            //System.setProperty("java.io.tmpdir", tempPath.toString());
            System.setProperty("TMPDIR", tempPath.toString());
            return tempPath;
        }
        else {
            // fallback to default /tmp
            return Paths.get(System.getProperty("java.io.tmpdir"), "temp_embedded_pg");
        }
    }

	@ClassRule
	public static SingleInstancePostgresRule pg = EmbeddedPostgresRules.singleInstance()
	.customize(builder -> builder.setDataDirectory(DbIdentityServicePGTest.getWorkingDirectory()));
	
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
