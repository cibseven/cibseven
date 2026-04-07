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
package org.cibseven.bpm.engine.test.api.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.impl.db.entitymanager.DbEntityManager;
import org.cibseven.bpm.engine.impl.db.entitymanager.cache.CachedDbEntity;
import org.cibseven.bpm.engine.impl.db.entitymanager.cache.DbEntityState;
import org.cibseven.bpm.engine.impl.interceptor.Command;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.ByteArrayEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.VariableInstanceEntity;
import org.cibseven.bpm.engine.test.util.PluggableProcessEngineTest;
import org.cibseven.bpm.engine.variable.Variables;
import org.junit.Test;

/**
 *
 * @author Christopher Zell <christopher.zell@camunda.com>
 */
public class VariableInTransactionTest extends PluggableProcessEngineTest {

  @Test
  public void testCreateAndDeleteVariableInTransaction() throws Exception {

    processEngineConfiguration.getCommandExecutorTxRequired().execute(new Command<Void>() {
      @Override
      public Void execute(CommandContext commandContext) {
        //create a variable
        VariableInstanceEntity variable = VariableInstanceEntity.createAndInsert("aVariable", Variables.byteArrayValue(new byte[0]));
        String byteArrayId = variable.getByteArrayValueId();

        //delete the variable
        variable.delete();

        //check if the variable is deleted transient
        //-> no insert and delete stmt will be flushed
        DbEntityManager dbEntityManager = commandContext.getDbEntityManager();
        CachedDbEntity cachedEntity = dbEntityManager.getDbEntityCache().getCachedEntity(ByteArrayEntity.class, byteArrayId);

        DbEntityState entityState = cachedEntity.getEntityState();
        assertEquals(DbEntityState.DELETED_TRANSIENT, entityState);

        return null;
      }
    });

  }

  @Test
  public void testCreateVariableWithNonExistingTaskIdByDefault() {
    // given - default config has checkVariableTaskId disabled
    String nonExistingTaskId = "non-existing-task-id";

    // when - inserting a variable with a non-existing taskId
    processEngineConfiguration.getCommandExecutorTxRequired().execute(new Command<Void>() {
      @Override
      public Void execute(CommandContext commandContext) {
        VariableInstanceEntity variable = VariableInstanceEntity.create("myVar",
            Variables.stringValue("myValue"), false);
        variable.setTaskId(nonExistingTaskId);
        VariableInstanceEntity.insert(variable);

        // clean up
        variable.delete();
        return null;
      }
    });

    // then - no exception is thrown
  }

  @Test
  public void testCreateVariableWithNonExistingTaskIdWhenCheckEnabled() {
    // given - enable checkVariableTaskId
    String nonExistingTaskId = "non-existing-task-id";
    processEngineConfiguration.setCheckVariableTaskId(true);

    try {
      // when/then - inserting a variable with a non-existing taskId should fail
      assertThatThrownBy(() ->
          processEngineConfiguration.getCommandExecutorTxRequired().execute(new Command<Void>() {
            @Override
            public Void execute(CommandContext commandContext) {
              VariableInstanceEntity variable = VariableInstanceEntity.create("myVar",
                  Variables.stringValue("myValue"), false);
              variable.setTaskId(nonExistingTaskId);
              VariableInstanceEntity.insert(variable);
              return null;
            }
          })
      ).isInstanceOf(ProcessEngineException.class)
       .hasMessageContaining("Task with id 'non-existing-task-id' does not exist");
    } finally {
      // restore default
      processEngineConfiguration.setCheckVariableTaskId(false);
    }
  }
}
