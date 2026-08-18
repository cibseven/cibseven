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

import org.cibseven.bpm.engine.impl.history.event.AgentAuditHistoryEventEntity;
import org.cibseven.bpm.engine.impl.history.event.HistoryEventTypes;

/**
 * Builds {@link AgentAuditHistoryEventEntity} instances from the structured audit events that
 * {@code AgentChatListener} assembles for its chat log.
 *
 * <p>Kept separate from the listener so the mapping from payload map to history entity can be
 * tested on its own, and so other producers of audit events — for example the retrieval audit
 * path in {@code AgentConnectorImpl} — can reuse it.</p>
 *
 * <p>Scalar fields an auditor would filter on are lifted out of the map into typed columns; the
 * complete map is additionally serialised into {@link AgentAuditHistoryEventEntity#getPayload()}
 * so nothing is lost, including the message history, tool inventory and tool calls.</p>
 *
 * @see AgentAuditHistoryEventEntity
 */
class AgentAuditHistoryEventProducer {

  private static final Logger LOG = LoggerFactory.getLogger(AgentAuditHistoryEventProducer.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Builds one audit history event.
   *
   * <p>The {@code id} is deliberately left unset: the engine's {@code DbEntityManager} assigns
   * one from the configured id generator on insert, which keeps the audit rows consistent with
   * every other history entity.</p>
   *
   * @param event                 the audit event payload as assembled by the listener
   * @param rootProcessInstanceId root process instance, needed so the payload byte array is
   *                              cleaned up together with the rest of the instance's history;
   *                              may be {@code null} outside a process context
   * @return the history event, never {@code null}
   * @throws JsonProcessingException if the payload cannot be serialised
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

    return entity;
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

  /**
   * Joins a collection-valued field into a comma-separated string so it fits one column.
   * Tolerates a plain string, which is what a single-valued entry deserialises to.
   */
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

  /** Re-serialises a nested structure so it can be stored in a single text column. */
  private static String readJson(Map<String, Object> event, String key) {
    Object value = event.get(key);
    if (value == null) {
      return null;
    }
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      LOG.warn("Could not serialise audit field '{}'; the field is omitted from its column but "
          + "remains present in the payload.", key, e);
      return null;
    }
  }

  /**
   * Reads the ISO-8601 timestamp the listener stamped on the event. Falls back to the current
   * time so a malformed value never costs the whole audit entry — the column is
   * {@code not null}.
   */
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
