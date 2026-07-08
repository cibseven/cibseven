package org.cibseven.bpm.engine.impl.batch.deletion;

import java.util.List;
import org.cibseven.bpm.engine.impl.batch.BatchConfiguration;


public class DeleteDeploymentBatchConfiguration extends BatchConfiguration {

  public DeleteDeploymentBatchConfiguration(List<String> ids) {
    super(ids);
  }

  public DeleteDeploymentBatchConfiguration(List<String> ids, boolean cascade,
      boolean skipCustomListeners, boolean skipIoMappings) {
    super(ids);
    this.cascade = cascade;
    this.skipCustomListeners = skipCustomListeners;
    this.skipIoMappings = skipIoMappings;
  }

  
  protected boolean skipCustomListeners;
  protected boolean skipIoMappings;
  protected boolean cascade;



  public boolean isCascade() {
    return cascade;
  }

  public void setCascade(boolean cascade) {
    this.cascade = cascade;
  }

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
