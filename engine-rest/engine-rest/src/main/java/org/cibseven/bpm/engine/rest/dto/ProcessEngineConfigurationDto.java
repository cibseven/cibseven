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
import org.cibseven.bpm.engine.impl.util.ParseUtil;

public class ProcessEngineConfigurationDto {

  private String engineName;
  private String historyLevel;
  private boolean authorizationEnabled;
  private boolean enablePasswordPolicy;
  /**
   * Defines history time to live in days for process definitions and decision definitions
   * if no other value is defined inside their own BPMN or DMN models.
   * <p>
   * The history time to live defines the default number of days for which history is kept.
   * {@code -1} means to keep history indefinitely.
   * {@code 0} means to delete history immediately.
   * <p>
   * Default value: {@code -1}.
   *
   * @since 3.0.0 (engine version)
   *
   * @implNote Omitted value in this DTO indicates that the value was not explicitly exposed (any engine before 3.0.0),
   * while the {@code null} inside configuration files means to keep history indefinitely.
   */
  private int historyTimeToLive = -1;

  /**
   * Feature flag that prevents the deployment or redeployment
   * of any model resource (BPMN, DMN, CMMN)
   * that does not define their own {@code historyTimeToLive} value
   * by throwing a {@code ProcessEngineException}.
   * <p>
   * Default value: {@code true}.
   *
   * @since 3.0.0 (engine version)
   *
   * @implNote Omitted value in this DTO indicates that the value was not explicitly exposed (any engine before 3.0.0),
   * while the {@code null} inside configuration files means
   * to prevents the deployment or redeployment of any model resource without a defined {@code historyTimeToLive}.
   */
  private boolean enforceHistoryTimeToLive = true;

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

      // Parse errors: the parsing throws on invalid configured values, so GET /configuration would return an error instead of the raw value.
      // The engine already validates the value at startup, so this is unlikely.
      Integer historyTimeToLive = ParseUtil.parseHistoryTimeToLive(configurationImpl.getHistoryTimeToLive());
      if (historyTimeToLive != null) {
        dto.historyTimeToLive = historyTimeToLive;
      }

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

  public int getHistoryTimeToLive() {
    return historyTimeToLive;
  }

  public void setHistoryTimeToLive(int historyTimeToLive) {
    this.historyTimeToLive = historyTimeToLive;
  }

  public boolean isEnforceHistoryTimeToLive() {
    return enforceHistoryTimeToLive;
  }

  public void setEnforceHistoryTimeToLive(boolean enforceHistoryTimeToLive) {
    this.enforceHistoryTimeToLive = enforceHistoryTimeToLive;
  }

}
