package org.cibseven.impl.test.utils.testcontainers;

import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.Db2Container;
import org.testcontainers.containers.Db2ContainerProvider;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.command.InspectContainerResponse;


public class CamundaDb2ContainerProvider extends Db2ContainerProvider {

  private static final String NAME = "cibdb2";

  // additional log files (4 MB each) on demand; DB2 11.5 creates the database with only 3 primary + 10 secondary
  // log files (about 52 MB), which is too small for tests deleting large histories in one transaction (SQL0964C)
  private static final int LOG_SECONDARY_FILES = 100;

  @Override
  public boolean supports(String databaseType) {
    return NAME.equals(databaseType);
  }

  @Override
  public JdbcDatabaseContainer newInstance(String tag) {

  DockerImageName dockerImageName = TestcontainersHelper
      .resolveDockerImageName("db2", tag, "icr.io/db2_community/db2");

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
