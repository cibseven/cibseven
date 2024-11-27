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
package org.cibseven.bpm.container.impl.spi;

import org.cibseven.bpm.container.impl.spi.PlatformServiceContainer.ServiceType;

/**
 * The service types managed by this container.
 *
 */
public enum ServiceTypes implements ServiceType {

  BPM_PLATFORM("org.cibseven.bpm.platform"),
  PROCESS_ENGINE("org.cibseven.bpm.platform.process-engine"),
  JOB_EXECUTOR("org.cibseven.bpm.platform.job-executor"),
  PROCESS_APPLICATION("org.cibseven.bpm.platform.job-executor.process-application");

  protected String serviceRealm;

  private ServiceTypes(String serviceRealm) {
    this.serviceRealm = serviceRealm;
  }

  public String getTypeName() {
    return serviceRealm;
  }

}