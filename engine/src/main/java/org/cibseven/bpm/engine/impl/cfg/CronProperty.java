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
package org.cibseven.bpm.engine.impl.cfg;

import com.cronutils.model.CronType;

/**
 * Configuration properties for cron expression handling in the process engine.
 */
public class CronProperty {

  /**
   * Cron type used for parsing cron expressions in timer events and other scheduled tasks.
   * 
   * Supported values:
   * - SPRING53: Uses Spring Framework 5.3+ cron syntax (default, recommended for new applications)
   * - QUARTZ: Uses Quartz Scheduler 2.5.0 cron syntax (for compatibility with existing Quartz-based applications)
   * 
   * When migrating from Quartz-based applications, use QUARTZ type with supportLegacyQuartzSyntax
   * enabled for maximum compatibility with existing process definitions.
   */
  private String type = "SPRING53";

  /**
   * This flag enables backward compatibility for cron expressions that were valid in
   * Quartz 1.8.4 but are rejected by newer Quartz versions due to stricter parsing rules.
   * 
   * Enable this when:
   * - Existing process definitions contain legacy cron expressions
   * - Encountering parsing errors with historical timer configurations
   * 
   * Default: false
   */
  private boolean supportLegacyQuartzSyntax = false;

  public String getType() {
    return type;
  }

  public void setType(String type) {
    if (type != null) {
      type = type.trim();
      if (type.isEmpty()) {
        // Ignore empty/whitespace-only values, keep existing value
        return;
      }
      try {
        CronType.valueOf(type);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Invalid cronType: " + type + ". Valid values are: SPRING53, QUARTZ", e);
      }
    }
    this.type = type;
  }

  public boolean isSupportLegacyQuartzSyntax() {
    return supportLegacyQuartzSyntax;
  }

  public void setSupportLegacyQuartzSyntax(boolean supportLegacyQuartzSyntax) {
    this.supportLegacyQuartzSyntax = supportLegacyQuartzSyntax;
  }

  @Override
  public String toString() {
    return "CronProperty [type=" + type + ", supportLegacyQuartzSyntax=" + supportLegacyQuartzSyntax + "]";
  }
}