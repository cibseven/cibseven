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
package org.cibseven.bpm.engine.rest.dto;

import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;

public class ProcessEngineConfigurationDto {

  private String engineName;
  private String historyLevel;
  private boolean authorizationEnabled;
  private boolean enablePasswordPolicy;
	// Time to live for historical data, in days.
	// If null, means to keep historical data indefinitely.
	// Valid when `enforceHistoryTimeToLive` is not null, elsewhere ignored.
	// Supported since engine version 2.2.4.
	private String historyTimeToLive;
	// Boxed, not primitive: null means "unknown" (e.g. an engine-rest version that doesn't
	// report it), which must stay distinguishable from a real, known false.
	// Supported since engine version 2.2.4.
	private Boolean enforceHistoryTimeToLive;

  public static ProcessEngineConfigurationDto fromProcessEngineConfiguration(ProcessEngineConfiguration configuration) {
    ProcessEngineConfigurationDto dto = new ProcessEngineConfigurationDto();
    dto.engineName = configuration.getProcessEngineName();
    dto.historyLevel = configuration.getHistory();
    dto.authorizationEnabled = configuration.isAuthorizationEnabled();
    dto.enablePasswordPolicy = configuration.isEnablePasswordPolicy();
    // historyTimeToLive/enforceHistoryTimeToLive are only declared on the impl class, not
    // on the abstract ProcessEngineConfiguration.
    if (configuration instanceof ProcessEngineConfigurationImpl) {
      ProcessEngineConfigurationImpl configurationImpl = (ProcessEngineConfigurationImpl) configuration;
      dto.historyTimeToLive = configurationImpl.getHistoryTimeToLive();
      dto.enforceHistoryTimeToLive = configurationImpl.isEnforceHistoryTimeToLive();
    }
    return dto;
  }

  public String getEngineName() {
    return engineName;
  }

  public void setEngineName(String engineName) {
    this.engineName = engineName;
  }

  public String getHistoryLevel() {
    return historyLevel;
  }

  public void setHistoryLevel(String historyLevel) {
    this.historyLevel = historyLevel;
  }

  public boolean isAuthorizationEnabled() {
    return authorizationEnabled;
  }

  public void setAuthorizationEnabled(boolean authorizationEnabled) {
    this.authorizationEnabled = authorizationEnabled;
  }

  public boolean isEnablePasswordPolicy() {
    return enablePasswordPolicy;
  }

  public void setEnablePasswordPolicy(boolean enablePasswordPolicy) {
    this.enablePasswordPolicy = enablePasswordPolicy;
  }

  public String getHistoryTimeToLive() {
    return historyTimeToLive;
  }

  public void setHistoryTimeToLive(String historyTimeToLive) {
    this.historyTimeToLive = historyTimeToLive;
  }

  public Boolean getEnforceHistoryTimeToLive() {
    return enforceHistoryTimeToLive;
  }

  public void setEnforceHistoryTimeToLive(Boolean enforceHistoryTimeToLive) {
    this.enforceHistoryTimeToLive = enforceHistoryTimeToLive;
  }

}
