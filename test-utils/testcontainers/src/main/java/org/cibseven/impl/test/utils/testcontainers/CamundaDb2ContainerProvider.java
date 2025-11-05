package org.cibseven.impl.test.utils.testcontainers;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.Db2Container;
import org.testcontainers.containers.Db2ContainerProvider;
import org.testcontainers.utility.DockerImageName;


public class CamundaDb2ContainerProvider extends Db2ContainerProvider {

  private static final String NAME = "camdb2";

  @Override
  public boolean supports(String databaseType) {
    return NAME.equals(databaseType);
  }
  
  @Override
  public JdbcDatabaseContainer newInstance(String tag) {
  
  DockerImageName dockerImageName = TestcontainersHelper
      .resolveDockerImageName("ibmdb2", tag, "ibmcom/db2");

    Db2Container db2Container = new Db2Container(dockerImageName);
    db2Container.acceptLicense();
    return db2Container;
  }
}