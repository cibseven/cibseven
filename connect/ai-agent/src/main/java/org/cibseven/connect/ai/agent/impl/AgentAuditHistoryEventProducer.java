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
package org.cibseven.connect.ai.agent.impl;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.history.event.AgentAuditHistoryEventEntity;
import org.cibseven.bpm.engine.impl.history.event.HistoricProcessInstanceEventEntity;
import org.cibseven.bpm.engine.impl.history.event.HistoryEventTypes;

/**
 * Builds {@link AgentAuditHistoryEventEntity} instances from the audit events that
 * {@code AgentChatListener} assembles for its chat log.
 *
 * <p>Scalar fields are lifted into typed columns; the complete map is additionally serialised
 * into the entity's payload, so nothing is lost.</p>
 */
class AgentAuditHistoryEventProducer {

  private static final Logger LOG = LoggerFactory.getLogger(AgentAuditHistoryEventProducer.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * The entity's {@code id} is left unset — the engine assigns one from its id generator on
   * insert, keeping audit rows consistent with every other history entity.
   *
   * @param rootProcessInstanceId needed so the payload byte array is removed together with the
   *                              rest of the instance history; {@code null} outside a process
   */
  AgentAuditHistoryEventEntity createAgentAuditEvent(Map<String, Object> event,
                                                     String rootProcessInstanceId)
      throws JsonProcessingException {

    AgentAuditHistoryEventEntity entity = new AgentAuditHistoryEventEntity();

    entity.setEventType(HistoryEventTypes.AGENT_AUDIT.getEventName());
    entity.setAuditType(readString(event, "type"));

    entity.setRootProcessInstanceId(rootProcessInstanceId);
    entity.setProcessInstanceId(readString(event, "processInstanceId"));
    entity.setProcessDefinitionId(readString(event, "processDefinitionId"));
    entity.setProcessDefinitionKey(readString(event, "processDefinitionKey"));
    entity.setExecutionId(readString(event, "executionId"));
    entity.setActivityId(readString(event, "activityId"));
    entity.setTenantId(readString(event, "tenantId"));

    entity.setTimestamp(readTimestamp(event));
    entity.setSchemaVersion(readInt(event, "schemaVersion"));
    entity.setRunId(readString(event, "runId"));
    entity.setEventSeq(readInt(event, "eventSeq"));

    entity.setProvider(readString(event, "provider"));
    entity.setModel(readString(event, "model"));
    entity.setResponseId(readString(event, "responseId"));
    entity.setEndpoint(readString(event, "endpoint"));

    entity.setUserId(readString(event, "userId"));
    entity.setGroupIds(readJoined(event, "groupIds"));

    entity.setFinishReason(readString(event, "finishReason"));
    entity.setDurationMs(readLong(event, "durationMs"));
    entity.setErrorClass(readString(event, "errorClass"));
    entity.setModelParams(readJson(event, "modelParams"));

    entity.setPayload(MAPPER.writeValueAsString(event).getBytes(StandardCharsets.UTF_8));

    provideRemovalTime(entity);

    return entity;
  }

  /**
   * Copies the removal time from the historic root process instance, mirroring
   * {@code DefaultHistoryEventProducer}. Under the {@code end} strategy the root instance has no
   * removal time yet, so this leaves the field null and
   * {@code HistoricProcessInstanceManager} stamps it once the instance finishes.
   */
  private static void provideRemovalTime(AgentAuditHistoryEventEntity entity) {
    String rootProcessInstanceId = entity.getRootProcessInstanceId();
    if (rootProcessInstanceId == null) {
      return;
    }

    HistoricProcessInstanceEventEntity root = Context.getCommandContext()
        .getDbEntityManager()
        .selectById(HistoricProcessInstanceEventEntity.class, rootProcessInstanceId);

    if (root != null) {
      entity.setRemovalTime(root.getRemovalTime());
    }
  }

  private static String readString(Map<String, Object> event, String key) {
    Object value = event.get(key);
    return value == null ? null : value.toString();
  }

  private static int readInt(Map<String, Object> event, String key) {
    Object value = event.get(key);
    return value instanceof Number ? ((Number) value).intValue() : 0;
  }

  private static Long readLong(Map<String, Object> event, String key) {
    Object value = event.get(key);
    return value instanceof Number ? ((Number) value).longValue() : null;
  }

  /** Tolerates a plain string, which is what a single-valued entry deserialises to. */
  private static String readJoined(Map<String, Object> event, String key) {
    Object value = event.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof Collection) {
      Collection<?> values = (Collection<?>) value;
      return values.isEmpty() ? null
          : values.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
    return value.toString();
  }

  private static String readJson(Map<String, Object> event, String key) {
    Object value = event.get(key);
    if (value == null) {
      return null;
    }
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      LOG.warn("Could not serialise audit field '{}'; it is omitted from its column but remains "
          + "present in the payload.", key, e);
      return null;
    }
  }

  /** Falls back to the current time: the column is {@code not null}. */
  private static Date readTimestamp(Map<String, Object> event) {
    Object value = event.get("timestamp");
    if (value != null) {
      try {
        return Date.from(Instant.parse(value.toString()));
      } catch (DateTimeParseException e) {
        LOG.warn("Unparseable audit timestamp '{}'; falling back to the current time.", value);
      }
    }
    return new Date();
  }

}
