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
package org.cibseven.bpm.engine.test.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.history.event.AgentAuditHistoryEventEntity;
import org.cibseven.bpm.engine.impl.history.event.HistoryEventTypes;
import org.cibseven.bpm.engine.impl.interceptor.Command;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.ByteArrayEntity;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.util.ProcessEngineBootstrapRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

/**
 * Verifies the persistence path of {@link AgentAuditHistoryEventEntity}: that the MyBatis
 * statement name derived from the class name resolves, that the row lands in
 * {@code ACT_HI_AGENT_AUDIT} with its typed columns, and that the unbounded payload is stored
 * as a byte array and read back intact.
 */
@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
public class AgentAuditHistoryEventTest {

  @ClassRule
  public static ProcessEngineBootstrapRule bootstrapRule = new ProcessEngineBootstrapRule();

  @Rule
  public ProvidedProcessEngineRule engineRule = new ProvidedProcessEngineRule(bootstrapRule);

  private static final String PAYLOAD = "{\"messages\":[{\"role\":\"system\",\"content\":\"a long prompt\"}]}";

  private String insertedId;

  @After
  public void removeInsertedEvent() {
    if (insertedId == null) {
      return;
    }
    final String id = insertedId;
    insertedId = null;
    execute(commandContext -> {
      AgentAuditHistoryEventEntity event =
          commandContext.getDbEntityManager().selectById(AgentAuditHistoryEventEntity.class, id);
      if (event != null) {
        commandContext.getDbEntityManager().delete(event);
        if (event.getPayloadByteArrayId() != null) {
          ByteArrayEntity payload = commandContext.getDbEntityManager()
              .selectById(ByteArrayEntity.class, event.getPayloadByteArrayId());
          if (payload != null) {
            commandContext.getDbEntityManager().delete(payload);
          }
        }
      }
      return null;
    });
  }

  @Test
  public void shouldPersistAndReadBackAgentAuditEvent() {
    // given
    AgentAuditHistoryEventEntity event = new AgentAuditHistoryEventEntity();
    event.setEventType(HistoryEventTypes.AGENT_AUDIT.getEventName());
    event.setAuditType("request");
    event.setRunId("run-1");
    event.setEventSeq(0);
    event.setSchemaVersion(1);
    event.setTimestamp(new Date());
    event.setProvider("OPEN_AI");
    event.setModel("gpt-test");
    event.setEndpoint("http://localhost:8090/v1");
    event.setActivityId("AgentTask_1");
    event.setProcessInstanceId("pi-1");
    event.setExecutionId("ex-1");
    event.setProcessDefinitionKey("someProcess");
    event.setUserId("demo");
    event.setGroupIds("camunda-admin,sales");
    event.setFinishReason("STOP");
    event.setDurationMs(123L);
    event.setModelParams("{\"temperature\":0.7}");
    event.setPayload(PAYLOAD.getBytes(StandardCharsets.UTF_8));

    // when — routed through the configured handler chain, exactly as the connector does
    execute(commandContext -> {
      getConfiguration().getHistoryEventHandler().handleEvent(event);
      return null;
    });

    insertedId = event.getId();

    // then
    assertThat(insertedId).as("the engine assigns an id on insert").isNotNull();

    AgentAuditHistoryEventEntity persisted = execute(commandContext ->
        commandContext.getDbEntityManager().selectById(AgentAuditHistoryEventEntity.class, insertedId));

    assertThat(persisted).isNotNull();

    // eventType is a routing field, not a stored one: it steers the handler chain, and once the
    // row sits in ACT_HI_AGENT_AUDIT the type is implied by the table. The other history
    // entities behave the same way — ACT_HI_INCIDENT has no EVENT_TYPE_ column either. The
    // discriminator that does carry information per row is auditType.
    assertThat(persisted.getEventType()).as("event type is not a stored column").isNull();
    assertThat(persisted.getAuditType()).isEqualTo("request");
    assertThat(persisted.getRunId()).isEqualTo("run-1");
    assertThat(persisted.getEventSeq()).isZero();
    assertThat(persisted.getSchemaVersion()).isEqualTo(1);
    assertThat(persisted.getProvider()).isEqualTo("OPEN_AI");
    assertThat(persisted.getModel()).isEqualTo("gpt-test");
    assertThat(persisted.getEndpoint()).isEqualTo("http://localhost:8090/v1");
    assertThat(persisted.getActivityId()).isEqualTo("AgentTask_1");
    assertThat(persisted.getProcessInstanceId()).isEqualTo("pi-1");
    assertThat(persisted.getUserId()).isEqualTo("demo");
    assertThat(persisted.getGroupIds()).isEqualTo("camunda-admin,sales");
    assertThat(persisted.getFinishReason()).isEqualTo("STOP");
    assertThat(persisted.getDurationMs()).isEqualTo(123L);
    assertThat(persisted.getModelParams()).isEqualTo("{\"temperature\":0.7}");

    // the payload is stored out of line and must be readable through its reference
    assertThat(persisted.getPayloadByteArrayId()).as("payload is stored as a byte array").isNotNull();

    ByteArrayEntity payload = execute(commandContext -> commandContext.getDbEntityManager()
        .selectById(ByteArrayEntity.class, persisted.getPayloadByteArrayId()));

    assertThat(payload).isNotNull();
    assertThat(new String(payload.getBytes(), StandardCharsets.UTF_8)).isEqualTo(PAYLOAD);
  }

  @Test
  public void shouldPersistEventWithoutPayload() {
    // given — an entry that carries no out-of-line payload at all
    AgentAuditHistoryEventEntity event = new AgentAuditHistoryEventEntity();
    event.setEventType(HistoryEventTypes.AGENT_AUDIT.getEventName());
    event.setAuditType("error");
    event.setRunId("run-2");
    event.setTimestamp(new Date());
    event.setErrorClass("java.net.SocketTimeoutException");

    // when
    execute(commandContext -> {
      getConfiguration().getHistoryEventHandler().handleEvent(event);
      return null;
    });

    insertedId = event.getId();

    // then
    AgentAuditHistoryEventEntity persisted = execute(commandContext ->
        commandContext.getDbEntityManager().selectById(AgentAuditHistoryEventEntity.class, insertedId));

    assertThat(persisted).isNotNull();
    assertThat(persisted.getAuditType()).isEqualTo("error");
    assertThat(persisted.getErrorClass()).isEqualTo("java.net.SocketTimeoutException");
    assertThat(persisted.getPayloadByteArrayId()).isNull();
  }

  @Test
  public void shouldBeProducedFromHistoryLevelAuditUpwards() {
    assertThat(new org.cibseven.bpm.engine.impl.history.HistoryLevelAudit()
        .isHistoryEventProduced(HistoryEventTypes.AGENT_AUDIT, null)).isTrue();
    assertThat(new org.cibseven.bpm.engine.impl.history.HistoryLevelFull()
        .isHistoryEventProduced(HistoryEventTypes.AGENT_AUDIT, null)).isTrue();
    assertThat(new org.cibseven.bpm.engine.impl.history.HistoryLevelActivity()
        .isHistoryEventProduced(HistoryEventTypes.AGENT_AUDIT, null)).isFalse();
    assertThat(new org.cibseven.bpm.engine.impl.history.HistoryLevelNone()
        .isHistoryEventProduced(HistoryEventTypes.AGENT_AUDIT, null)).isFalse();
  }

  private ProcessEngineConfigurationImpl getConfiguration() {
    return engineRule.getProcessEngineConfiguration();
  }

  private <T> T execute(Command<T> command) {
    return getConfiguration().getCommandExecutorTxRequired().execute(command);
  }

}
