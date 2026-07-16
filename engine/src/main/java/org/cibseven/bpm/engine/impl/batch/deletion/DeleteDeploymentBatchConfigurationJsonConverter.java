/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cibseven.bpm.engine.impl.batch.deletion;

import java.util.List;

import org.cibseven.bpm.engine.impl.batch.AbstractBatchConfigurationObjectConverter;
import org.cibseven.bpm.engine.impl.batch.DeploymentMappingJsonConverter;
import org.cibseven.bpm.engine.impl.batch.DeploymentMappings;
import org.cibseven.bpm.engine.impl.util.JsonUtil;

import com.google.gson.JsonObject;

public class DeleteDeploymentBatchConfigurationJsonConverter
    extends AbstractBatchConfigurationObjectConverter<DeleteDeploymentBatchConfiguration> {

  public static final DeleteDeploymentBatchConfigurationJsonConverter INSTANCE = new DeleteDeploymentBatchConfigurationJsonConverter();

  public static final String DEPLOYMENT_IDS = "deploymentIds";
  public static final String DEPLOYMENT_ID_MAPPINGS = "deploymentIdMappings";
  public static final String SKIP_CUSTOM_LISTENERS = "skipCustomListeners";
  public static final String CASCADE = "cascade";
  public static final String SKIP_IO_MAPPINGS = "skipIoMappings";

  @Override
  public JsonObject writeConfiguration(DeleteDeploymentBatchConfiguration configuration) {
    JsonObject json = JsonUtil.createObject();
    JsonUtil.addListField(json, DEPLOYMENT_IDS, configuration.getIds());
    JsonUtil.addListField(json, DEPLOYMENT_ID_MAPPINGS, DeploymentMappingJsonConverter.INSTANCE, configuration.getIdMappings());
    JsonUtil.addField(json, CASCADE, configuration.isCascade());
    JsonUtil.addField(json, SKIP_CUSTOM_LISTENERS, configuration.isSkipCustomListeners());
    JsonUtil.addField(json, SKIP_IO_MAPPINGS, configuration.isSkipIoMappings());

    return json;
  }

  @Override
  public DeleteDeploymentBatchConfiguration readConfiguration(JsonObject json) {
    DeleteDeploymentBatchConfiguration configuration = new DeleteDeploymentBatchConfiguration(
        readDeploymentIds(json),
        readIdMappings(json),
        JsonUtil.getBoolean(json, CASCADE),
        JsonUtil.getBoolean(json, SKIP_CUSTOM_LISTENERS),
        JsonUtil.getBoolean(json, SKIP_IO_MAPPINGS));

    return configuration;
  }

  protected List<String> readDeploymentIds(JsonObject jsonObject) {
    return JsonUtil.asStringList(JsonUtil.getArray(jsonObject, DEPLOYMENT_IDS));
  }

  protected DeploymentMappings readIdMappings(JsonObject json) {
    return JsonUtil.asList(JsonUtil.getArray(json, DEPLOYMENT_ID_MAPPINGS), DeploymentMappingJsonConverter.INSTANCE, DeploymentMappings::new);
  }

}
