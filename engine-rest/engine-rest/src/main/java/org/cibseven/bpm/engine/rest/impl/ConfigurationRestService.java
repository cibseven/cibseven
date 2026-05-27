/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.cibseven.bpm.engine.rest.impl;

import javax.ws.rs.GET;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.rest.dto.ProcessEngineConfigurationDto;

import com.fasterxml.jackson.databind.ObjectMapper;

@Produces(MediaType.APPLICATION_JSON)
public class ConfigurationRestService extends AbstractRestProcessEngineAware {

  public static final String PATH = "/configuration";

  public ConfigurationRestService(String engineName, ObjectMapper objectMapper) {
    super(engineName, objectMapper);
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public ProcessEngineConfigurationDto getConfiguration() {
    ProcessEngineConfiguration config = getProcessEngine().getProcessEngineConfiguration();
    return ProcessEngineConfigurationDto.fromProcessEngineConfiguration(config);
  }

}
