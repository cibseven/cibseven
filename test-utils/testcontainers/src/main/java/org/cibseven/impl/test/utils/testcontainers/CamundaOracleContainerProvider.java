package org.cibseven.impl.test.utils.testcontainers;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.containers.OracleContainerProvider;
import org.testcontainers.utility.DockerImageName;


public class CamundaOracleContainerProvider extends OracleContainerProvider {

  private static final String NAME = "camoracle";

  @Override
  public boolean supports(String databaseType) {
    return NAME.equals(databaseType);
  }
  
  @Override
  public JdbcDatabaseContainer newInstance(String tag) {
  
  // https://testcontainers.com/modules/oracle-xe/
  DockerImageName dockerImageName = TestcontainersHelper
      .resolveDockerImageName("oracle", tag, "gvenzl/oracle-xe");

    return new OracleContainer(dockerImageName);
  }
}