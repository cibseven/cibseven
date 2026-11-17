package org.cibseven.impl.test.utils.testcontainers;

import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.Db2Container;
import org.testcontainers.containers.Db2ContainerProvider;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.command.InspectContainerResponse;


public class CamundaDb2ContainerProvider extends Db2ContainerProvider {

  private static final String NAME = "cibdb2";

  // Additional log files on demand: DB2 11.5 defaults to 3 primary + 10 secondary logs (~52 MB), 
  // so large transactions can fail with SQL0964C (SQLCODE=-1476) on the qa/large-data test.
  private static final int LOG_SECONDARY_FILES = 254;
  // 4 KB pages: 8192 pages are 32 MB per log file, instead of the 1024 pages (4 MB) of the image
  private static final int LOG_FILE_SIZE_PAGES = 8192;

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
      // LOGSECOND can be changed online, LOGFILSIZ cannot: the database has to be deactivated, which
      // needs the connection of the Testcontainers wait strategy to be gone - hence force applications.
      // The steps are separated by ; and not by &&: deactivating a database that is not active answers
      // with the warning SQL1496W and a non-zero exit code, which must not stop the rest.
      // The settings are read back afterwards, so that the log of the build says what the database
      // really ended up with instead of what was asked for.
      String commands = "db2 force applications all"
          + "; db2 deactivate db " + database
          + "; db2 update db cfg for " + database
          + " using LOGFILSIZ " + LOG_FILE_SIZE_PAGES + " LOGSECOND " + LOG_SECONDARY_FILES
          + "; db2 activate db " + database
          + "; db2 get db cfg for " + database + " | grep -E 'LOGFILSIZ|LOGPRIMARY|LOGSECOND'";
      ExecResult result = container.execInContainer("su", "-", "db2inst1", "-c", commands);
      String settings = result.getStdout() == null ? "" : result.getStdout().trim();
      if (settings.contains("LOGFILSIZ")) {
        System.out.println("DB2 transaction log of " + database + ":\n" + settings);
      } else {
        System.err.println("Could not increase the DB2 transaction log: " + result.getStdout() + result.getStderr());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      System.err.println("Could not increase the DB2 transaction log: " + e.getMessage());
    }
  }
}