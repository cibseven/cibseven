package org.cibseven.impl.test.utils.testcontainers;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.JdbcDatabaseContainerProvider;
import org.testcontainers.utility.DockerImageName;


public class CamundaOracle19ContainerProvider extends JdbcDatabaseContainerProvider {

  private static final String NAME = "ciboracle19";

  @Override
  public boolean supports(String databaseType) {
    return NAME.equals(databaseType);
  }

  @Override
  public JdbcDatabaseContainer newInstance(String tag) {

    DockerImageName dockerImageName = TestcontainersHelper
        .resolveDockerImageName("oracle19", tag, "container-registry.oracle.com/database/enterprise");

    return new Oracle19EnterpriseContainer(dockerImageName);
  }
}
