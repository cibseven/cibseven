package org.cibseven.bpm.engine.impl.batch.deletion;

import java.util.List;
import org.cibseven.bpm.engine.impl.batch.BatchConfiguration;
import org.cibseven.bpm.engine.impl.batch.DeploymentMappings;

public class DeleteDeploymentBatchConfiguration extends BatchConfiguration {

  public DeleteDeploymentBatchConfiguration(List<String> ids) {
    super(ids);
  }

  public DeleteDeploymentBatchConfiguration(List<String> ids, DeploymentMappings deploymentMappings) {
    super(ids, deploymentMappings);
  }

  
  protected boolean skipCustomListeners;
  protected boolean skipIoMappings;



  public boolean isSkipCustomListeners() {
    return skipCustomListeners;
  }


  public boolean isSkipIoMappings() {
    return skipIoMappings;
  }

  public void setSkipIoMappings(boolean skipIoMappings) {
    this.skipIoMappings = skipIoMappings;
  }

}
