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
package org.cibseven.bpm.engine.rest.dto.runtime;

import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.rest.dto.VariableValueDto;

/**
 * Request body for starting children of an ad hoc sub process.
 */
public class TriggerAdHocActivitiesDto {

  private List<String> activityIds;
  private Map<String, Map<String, VariableValueDto>> activityVariables;

  public List<String> getActivityIds() {
    return activityIds;
  }

  public void setActivityIds(List<String> activityIds) {
    this.activityIds = activityIds;
  }

  /**
   * Variables per activity id, applied locally to the execution of every performance of that
   * activity in this request. Keyed per activity definition, not per performance: if
   * {@code activityIds} names the same activity twice, both performances receive the same variables.
   * Send one request per performance to vary them.
   */
  public Map<String, Map<String, VariableValueDto>> getActivityVariables() {
    return activityVariables;
  }

  public void setActivityVariables(Map<String, Map<String, VariableValueDto>> activityVariables) {
    this.activityVariables = activityVariables;
  }
}
