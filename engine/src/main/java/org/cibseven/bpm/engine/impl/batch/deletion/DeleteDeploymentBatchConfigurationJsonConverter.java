package org.cibseven.bpm.engine.impl.batch.deletion;

import org.cibseven.bpm.engine.impl.batch.AbstractBatchConfigurationObjectConverter;
import org.cibseven.bpm.engine.impl.batch.DeploymentMappingJsonConverter;
import org.cibseven.bpm.engine.impl.util.JsonUtil;

import com.google.gson.JsonObject;

public class DeleteDeploymentBatchConfigurationJsonConverter
    extends AbstractBatchConfigurationObjectConverter<DeleteDeploymentBatchConfiguration> {

  public static final DeleteDeploymentBatchConfigurationJsonConverter INSTANCE = new DeleteDeploymentBatchConfigurationJsonConverter();

  public static final String DEPLOYMENT_IDS = "deploymentIds";
  public static final String SKIP_CUSTOM_LISTENERS = "skipCustomListeners";
  public static final String CASCADE = "cascade";
  public static final String SKIP_IO_MAPPINGS = "skipIoMappings";
  public static final String PROCESS_INSTANCE_ID_MAPPINGS = "processInstanceIdMappings";

  @Override
  public JsonObject writeConfiguration(DeleteDeploymentBatchConfiguration configuration) {
    JsonObject json = JsonUtil.createObject();
    JsonUtil.addListField(json, PROCESS_INSTANCE_ID_MAPPINGS, DeploymentMappingJsonConverter.INSTANCE,
        configuration.getIdMappings());
    JsonUtil.addListField(json, DEPLOYMENT_IDS, configuration.getIds());
    JsonUtil.addField(json, CASCADE, configuration.isCascade());
    JsonUtil.addField(json, SKIP_CUSTOM_LISTENERS, configuration.isSkipCustomListeners());
    JsonUtil.addField(json, SKIP_IO_MAPPINGS, configuration.isSkipIoMappings());

    return json;
  }

  @Override
  public DeleteDeploymentBatchConfiguration readConfiguration(JsonObject json) {
    DeleteDeploymentBatchConfiguration configuration = new DeleteDeploymentBatchConfiguration(
        readDeploymentIds(json),
        JsonUtil.getBoolean(json, CASCADE),
        JsonUtil.getBoolean(json, SKIP_CUSTOM_LISTENERS),
        JsonUtil.getBoolean(json, SKIP_IO_MAPPINGS));

    return configuration;
  }

  protected java.util.List<String> readDeploymentIds(JsonObject jsonObject) {
    return JsonUtil.asStringList(JsonUtil.getArray(jsonObject, DEPLOYMENT_IDS));
  }

}
