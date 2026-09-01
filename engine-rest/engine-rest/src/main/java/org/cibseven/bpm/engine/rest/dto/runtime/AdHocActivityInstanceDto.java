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

/**
 * One activity instance created by starting an ad hoc child.
 *
 * <p>Pairs the id with the activity it belongs to. The Java API returns bare ids in request order and
 * leaves the caller to line them up; over the wire that is worth avoiding, and a pair also survives
 * the same activity being started twice in one request, which a map keyed by activity id would not.
 */
public class AdHocActivityInstanceDto {

  private String activityId;
  private String activityInstanceId;

  public AdHocActivityInstanceDto() {
  }

  public AdHocActivityInstanceDto(String activityId, String activityInstanceId) {
    this.activityId = activityId;
    this.activityInstanceId = activityInstanceId;
  }

  public String getActivityId() {
    return activityId;
  }

  public void setActivityId(String activityId) {
    this.activityId = activityId;
  }

  public String getActivityInstanceId() {
    return activityInstanceId;
  }

  public void setActivityInstanceId(String activityInstanceId) {
    this.activityInstanceId = activityInstanceId;
  }
}
