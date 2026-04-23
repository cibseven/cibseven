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

public class ProcessEngineConfigurationDto {

  private String history;
  private boolean authorizationEnabled;
  private boolean enablePasswordPolicy;

  public static ProcessEngineConfigurationDto fromProcessEngineConfiguration(ProcessEngineConfiguration configuration) {
    ProcessEngineConfigurationDto dto = new ProcessEngineConfigurationDto();
    dto.history = configuration.getHistory();
    dto.authorizationEnabled = configuration.isAuthorizationEnabled();
    dto.enablePasswordPolicy = configuration.isEnablePasswordPolicy();
    return dto;
  }

  public String getHistory() {
    return history;
  }

  public void setHistory(String history) {
    this.history = history;
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

}
