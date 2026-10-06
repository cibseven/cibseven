package org.cibseven.impl.test.utils.testcontainers;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.oracle.OracleContainerProvider;
import org.testcontainers.utility.DockerImageName;


public class CamundaOracleFreeContainerProvider extends OracleContainerProvider {

  private static final String NAME = "ciboraclefree";

  @Override
  public boolean supports(String databaseType) {
    return NAME.equals(databaseType);
  }

  @Override
  public JdbcDatabaseContainer newInstance(String tag) {

    // https://testcontainers.com/modules/oracle-free/
    DockerImageName dockerImageName = TestcontainersHelper
        .resolveDockerImageName("oraclefree", tag, "gvenzl/oracle-free");

    return new OracleContainer(dockerImageName);
  }
}
