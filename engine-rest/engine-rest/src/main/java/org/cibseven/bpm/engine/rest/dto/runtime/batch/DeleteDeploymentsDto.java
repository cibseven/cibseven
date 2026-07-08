public class DeleteDeploymentsDto {

    protected List<String> deploymentIds;
    protected boolean cascade;
    protected boolean skipCustomListeners;
    protected boolean skipIoMappings;

    public List<String> getDeploymentIds() {
        return deploymentIds;
    }

    public void setDeploymentIds(List<String> deploymentIds) {
        this.deploymentIds = deploymentIds;
    }

    public boolean isCascade() {
        return cascade;
    }

    public void setCascade(boolean cascade) {
        this.cascade = cascade;
    }

    public boolean isSkipCustomListeners() {
        return skipCustomListeners;
    }

    public void setSkipCustomListeners(boolean skipCustomListeners) {
        this.skipCustomListeners = skipCustomListeners;
    }

    public boolean isSkipIoMappings() {
        return skipIoMappings;
    }

    public void setSkipIoMappings(boolean skipIoMappings) {
        this.skipIoMappings = skipIoMappings;
    }

}
