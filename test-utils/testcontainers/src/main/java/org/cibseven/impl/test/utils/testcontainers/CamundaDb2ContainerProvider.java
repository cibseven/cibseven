package org.cibseven.impl.test.utils.testcontainers;

import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.Db2Container;
import org.testcontainers.containers.Db2ContainerProvider;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.command.InspectContainerResponse;


public class CamundaDb2ContainerProvider extends Db2ContainerProvider {

  private static final String NAME = "camdb2";

  // Additional log files on demand: DB2 11.5 defaults to 3 primary + 10 secondary logs (~52 MB), 
  // so large transactions can fail with SQL0964C (SQLCODE=-1476) on the qa/large-data test.
  private static final int LOG_SECONDARY_FILES = 254;

  @Override
  public boolean supports(String databaseType) {
    return NAME.equals(databaseType);
  }
  
  @Override
  public JdbcDatabaseContainer newInstance(String tag) {
  
  DockerImageName dockerImageName = TestcontainersHelper
      .resolveDockerImageName("db2", tag, "ibmcom/db2");

    Db2Container db2Container = new Db2Container(dockerImageName) {
      @Override
      protected void containerIsStarted(InspectContainerResponse containerInfo) {
        super.containerIsStarted(containerInfo);
        increaseTransactionLog(this);
      }
    };
    db2Container.acceptLicense();
    return TestcontainersHelper.withLabel(db2Container);
  }

  protected void increaseTransactionLog(Db2Container container) {
    String database = container.getDatabaseName();
    try {
      // LOGSECOND can be changed online, but only with a connection to the database
      ExecResult result = container.execInContainer("su", "-", "db2inst1", "-c",
          "db2 connect to " + database + " && db2 update db cfg for " + database
          + " using LOGSECOND " + LOG_SECONDARY_FILES + " immediate");
      if (result.getExitCode() != 0) {
        System.err.println("Could not increase the DB2 transaction log: " + result.getStdout() + result.getStderr());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      System.err.println("Could not increase the DB2 transaction log: " + e.getMessage());
    }
  }
}