package org.cibseven.impl.test.utils.testcontainers;

import java.util.Collections;
import java.util.Set;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * Oracle Database 19c Enterprise Edition from the Oracle Container Registry
 * (container-registry.oracle.com/database/enterprise). Requires a registry login.
 *
 * In contrast to the gvenzl images the database is created on the first start of the container
 * (15-30 minutes), and no application user exists, so it is created by a setup script.
 */
public class Oracle19EnterpriseContainer extends JdbcDatabaseContainer<Oracle19EnterpriseContainer> {

  static final int ORACLE_PORT = 1521;
  static final String PDB_NAME = "ORCLPDB1";
  static final String SYS_PASSWORD = "Camunda_19c";
  static final String APP_USER = "camunda";
  static final String APP_USER_PASSWORD = "camunda";

  // executed by the image as SYSDBA in the CDB after the database has been created
  static final String SETUP_SCRIPT =
      "ALTER SESSION SET CONTAINER=" + PDB_NAME + ";\n"
      + "CREATE USER " + APP_USER + " IDENTIFIED BY " + APP_USER_PASSWORD + " QUOTA UNLIMITED ON USERS;\n"
      + "GRANT CONNECT, RESOURCE, CREATE VIEW, CREATE SEQUENCE, CREATE TABLE TO " + APP_USER + ";\n"
      + "exit;\n";

  public Oracle19EnterpriseContainer(DockerImageName dockerImageName) {
    super(dockerImageName);
    addExposedPort(ORACLE_PORT);
    addEnv("ORACLE_PWD", SYS_PASSWORD);
    addEnv("ORACLE_PDB", PDB_NAME);
    withCopyToContainer(Transferable.of(SETUP_SCRIPT), "/opt/oracle/scripts/setup/01_create_app_user.sql");
    // the database is created on the first start
    withStartupTimeoutSeconds(3600);
    withConnectTimeoutSeconds(120);
  }

  @Override
  public Set<Integer> getLivenessCheckPortNumbers() {
    return Collections.singleton(getMappedPort(ORACLE_PORT));
  }

  @Override
  public String getDriverClassName() {
    return "oracle.jdbc.OracleDriver";
  }

  @Override
  public String getJdbcUrl() {
    return "jdbc:oracle:thin:@" + getHost() + ":" + getMappedPort(ORACLE_PORT) + "/" + PDB_NAME;
  }

  @Override
  public String getUsername() {
    return APP_USER;
  }

  @Override
  public String getPassword() {
    return APP_USER_PASSWORD;
  }

  @Override
  public String getDatabaseName() {
    return PDB_NAME;
  }

  @Override
  protected String getTestQueryString() {
    return "SELECT 1 FROM DUAL";
  }
}
