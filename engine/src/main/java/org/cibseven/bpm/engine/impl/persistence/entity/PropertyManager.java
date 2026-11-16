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
package org.cibseven.bpm.engine.impl.persistence.entity;

import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.persistence.AbstractManager;


/**
 * @author Tom Baeyens
 * @author Daniel Meyer
 */
public class PropertyManager extends AbstractManager {

  public PropertyEntity findPropertyById(String propertyId) {
    return getDbEntityManager().selectById(PropertyEntity.class, propertyId);
  }

  public void acquireExclusiveLock() {
    // We lock a special deployment lock property
    getDbEntityManager().lock("lockDeploymentLockProperty");

  }

  /**
   * Acquires the deployment lock (same row as {@link #acquireExclusiveLock()}) unless
   * the deployment lock is disabled via
   * {@link org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl#isDeploymentLockUsed()}.
   * Unlike {@link #acquireExclusiveLock()}, the lock is also acquired on H2.
   * Used to serialize the deletion of deployments and process definitions with each
   * other and with deployments. On H2 deployments do not lock, so only deletions wait
   * for each other there; with long cascading deletions the H2 {@code LOCK_TIMEOUT}
   * (default 2 s) may need to be raised in the JDBC URL.
   */
  public void acquireExclusiveLockIfDeploymentLockUsed() {
    if (Context.getProcessEngineConfiguration().isDeploymentLockUsed()) {
      getDbEntityManager().lockIncludingH2("lockDeploymentLockProperty");
    }
  }

  public void acquireExclusiveLockForHistoryCleanupJob() {
    // We lock a special history cleanup lock property
    getDbEntityManager().lock("lockHistoryCleanupJobLockProperty");

  }

  public void acquireExclusiveLockForStartup() {
    // We lock a special startup lock property
    getDbEntityManager().lock("lockStartupLockProperty");

  }

  public void acquireExclusiveLockForInstallationId() {
    // We lock a special installation id lock property
    getDbEntityManager().lock("lockInstallationIdLockProperty");
  }

}
