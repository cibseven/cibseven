/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
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
package org.cibseven.bpm.engine.test.api.cfg;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Predicate;

import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.impl.ProcessEngineImpl;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.db.sql.DbSqlSession;
import org.cibseven.bpm.engine.impl.test.TestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The notification tables are a schema component of their own, switched by
 * notificationsEnabled independently of the modeler.
 */
public class NotificationSchemaComponentTest {

  protected ProcessEngineImpl engine;

  @AfterEach
  public void close() {
    if (engine != null) {
      engine.close();
      engine = null;
    }
  }

  @Test
  public void createsTheNotificationTablesWithoutTheModeler() {
    engine = build("notifications-only", false, true, ProcessEngineConfiguration.DB_SCHEMA_UPDATE_CREATE_DROP);

    assertTrue(present(DbSqlSession::isNotificationTablePresent));
    assertFalse(present(DbSqlSession::isModelerTablePresent));
  }

  @Test
  public void leavesTheNotificationTablesOutWhenSwitchedOff() {
    engine = build("modeler-only", true, false, ProcessEngineConfiguration.DB_SCHEMA_UPDATE_CREATE_DROP);

    assertFalse(present(DbSqlSession::isNotificationTablePresent));
    assertTrue(present(DbSqlSession::isModelerTablePresent));
  }

  /** A schema without the component is refused at startup, and a schema update adds it. */
  @Test
  public void reportsAndAddsMissingNotificationTables() {
    build("add-notifications", true, false, ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE).close();

    ProcessEngineException missing = assertThrows(ProcessEngineException.class,
        () -> build("add-notifications", true, true, ProcessEngineConfiguration.DB_SCHEMA_UPDATE_FALSE));
    assertTrue(missing.getMessage().contains("notification"), missing.getMessage());

    engine = build("add-notifications", true, true, ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
    assertTrue(present(DbSqlSession::isNotificationTablePresent));
    TestHelper.dropSchema(engine.getProcessEngineConfiguration());
  }

  protected ProcessEngineImpl build(String name, boolean modelerEnabled, boolean notificationsEnabled, String schemaUpdate) {
    StandaloneInMemProcessEngineConfiguration config = new StandaloneInMemProcessEngineConfiguration();
    config.setModelerEnabled(modelerEnabled);
    config.setNotificationsEnabled(notificationsEnabled);
    config
      .setProcessEngineName(name + "-engine")
      // kept open between engines, so the startup check sees what the previous engine left
      .setJdbcUrl("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1")
      .setDatabaseSchemaUpdate(schemaUpdate)
      .setJobExecutorActivate(false);
    return (ProcessEngineImpl) config.buildProcessEngine();
  }

  protected boolean present(Predicate<DbSqlSession> check) {
    return engine.getProcessEngineConfiguration().getCommandExecutorTxRequired()
      .execute(commandContext -> check.test(commandContext.getDbSqlSession()));
  }
}
