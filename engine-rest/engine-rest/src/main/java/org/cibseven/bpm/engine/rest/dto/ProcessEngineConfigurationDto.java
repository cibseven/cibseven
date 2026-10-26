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
  private String webappSchemaVersion;
  /**
   * The engine-wide default history time to live, in days, as configured by the
   * {@code historyTimeToLive} engine property.
   * <p>
   * The engine applies this default at deployment time to every process, decision and case
   * definition whose BPMN, DMN or CMMN model does not set its own {@code historyTimeToLive}.
   * The resolved value is stored on the deployed definition, so changing the engine default
   * later does not affect definitions that were already deployed.
   * <p>
   * The configured value may be written in days ({@code "180"}) or as an ISO-8601 period in
   * days ({@code "P180D"}). Both forms are parsed by
   * {@link ParseUtil#parseHistoryTimeToLive(String)} and exposed here as a plain number of days.
   * <p>
   * Values:
   * <ul>
   *   <li>{@code n > 0}: history of definitions without their own TTL becomes eligible for
   *   removal {@code n} days after the process/decision instance started or ended (depending on
   *   the configured history removal time strategy).</li>
   *   <li>{@code 0}: history becomes eligible for removal right away and is deleted by the next
   *   history cleanup run.</li>
   *   <li>{@code -1}: no engine default is configured (the engine property is unset or empty).
   *   Definitions without their own TTL get no TTL at all, so no removal time is set and their
   *   history is kept indefinitely. If {@code enforceHistoryTimeToLive} is {@code true}, the
   *   engine additionally rejects new deployments of models without a TTL (definitions that
   *   were deployed earlier keep their history indefinitely regardless).</li>
   * </ul>
   * <p>
   * {@code -1} is a marker produced by this DTO only, it never comes from the engine
   * configuration itself: negative values are rejected by
   * {@link ParseUtil#parseHistoryTimeToLive(String)} and the engine fails to start with them.
   * <p>
   * Default value: {@code -1}.
   *
   * @since 3.0.0 (engine version)
   *
   * @implNote A missing {@code historyTimeToLive} property in the JSON response means that the
   * engine does not expose it (any engine before 3.0.0), so clients must treat a missing value
   * as "unknown". This is why the field is a primitive and "not configured" is represented by
   * {@code -1} rather than {@code null}: {@code null}/missing is reserved for older engines.
   */
  private int historyTimeToLive = -1;

  /**
   * Whether the engine enforces a history time to live for deployed models, as configured by the
   * {@code enforceHistoryTimeToLive} engine property.
   * <p>
   * If {@code true}, the deployment or redeployment of a model resource (BPMN, DMN, CMMN) fails
   * with a {@code ProcessEngineException} when no history time to live can be resolved for it,
   * i.e. when the model does not define its own {@code historyTimeToLive} <b>and</b> no
   * engine-wide default is configured ({@code historyTimeToLive} is {@code -1}).
   * If an engine-wide default is configured, models without their own TTL deploy fine and
   * get the default value.
   * <p>
   * If {@code false}, models without a TTL are deployed without one (or with the engine-wide
   * default, if configured).
   * <p>
   * Enforcement only applies to new (re)deployments. Definitions deployed before it was enabled,
   * or deployed while skipping enforcement, may still have no TTL.
   * <p>
   * Default value: {@code true} (also the engine default when the property is not set).
   *
   * @since 3.0.0 (engine version)
   *
   * @implNote A missing {@code enforceHistoryTimeToLive} property in the JSON response means that
   * the engine does not expose it (any engine before 3.0.0), so clients must treat a missing value
   * as "unknown" rather than {@code false}. This is why the field is a primitive: it is always
   * present in responses from engines that support it, and {@code null}/missing is reserved for
   * older engines.
   */
  private boolean enforceHistoryTimeToLive = true;

  public static ProcessEngineConfigurationDto fromProcessEngineConfiguration(ProcessEngineConfiguration configuration) {
    ProcessEngineConfigurationDto dto = new ProcessEngineConfigurationDto();
    dto.engineName = configuration.getProcessEngineName();
    dto.historyLevel = configuration.getHistory();
    dto.authorizationEnabled = configuration.isAuthorizationEnabled();
    dto.enablePasswordPolicy = configuration.isEnablePasswordPolicy();
    dto.webappSchemaVersion = configuration.getWebappSchemaVersion();
    // historyTimeToLive/enforceHistoryTimeToLive are only declared on the impl class, not
    // on the abstract ProcessEngineConfiguration.
    // Every engine created by CIB seven uses the impl class. For any other (custom) implementation
    // the real values cannot be read, so the field defaults are reported instead
    // (historyTimeToLive = -1, enforceHistoryTimeToLive = true, i.e. the engine defaults).
    // Reporting null is not an option, since a missing value is reserved for engines before 3.0.0.
    if (configuration instanceof ProcessEngineConfigurationImpl) {
      ProcessEngineConfigurationImpl configurationImpl = (ProcessEngineConfigurationImpl) configuration;

      // The engine stores the TTL as the raw configured string ("180", "P180D", empty or null).
      // Parse it the same way the engine does, so clients always get a number of days:
      // - "180" / "P180D" -> 180
      // - "0" / "P0D"     -> 0
      // - null / ""       -> null -> keep the -1 marker ("no engine default configured")
      // Parsing throws on invalid values (ProcessEngineException for non-numbers -> HTTP 500,
      // NotValidException for negative numbers -> HTTP 400). This cannot happen with a running
      // engine: ProcessEngineConfigurationImpl#initHistoryTimeToLive validates the value with the
      // same method at startup and refuses to start on an invalid value.
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

  public String getWebappSchemaVersion() {
    return webappSchemaVersion;
  }

  public void setWebappSchemaVersion(String webappSchemaVersion) {
    this.webappSchemaVersion = webappSchemaVersion;
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
