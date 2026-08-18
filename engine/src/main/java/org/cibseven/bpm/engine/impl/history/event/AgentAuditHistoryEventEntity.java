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
package org.cibseven.bpm.engine.impl.history.event;

import java.util.Date;

/**
 * History event carrying one audit entry of an AI agent activity, as required by
 * EU AI Act Art. 12 (record-keeping) and Art. 26 (deployer obligations).
 *
 * <p>One instance is emitted per LLM request, response, error and retrieval. All four
 * share the single history event type {@link HistoryEventTypes#AGENT_AUDIT}; which of
 * them it is, is carried in {@link #auditType}. That keeps one entity and one table
 * ({@code ACT_HI_AGENT_AUDIT}) for the whole audit stream.</p>
 *
 * <h3>Field layout</h3>
 * <p>Scalar fields that an auditor or operator would filter on — {@code runId},
 * {@code auditType}, {@code provider}, {@code model}, {@code finishReason},
 * {@code userId}, {@code errorClass} — are individual columns so they are reachable
 * with plain SQL. The unbounded parts of the audit payload (the message history, the
 * tool inventory and the tool calls) live in {@link #payload}, which is written to
 * {@code ACT_GE_BYTEARRAY} and referenced through {@link #payloadByteArrayId}. The
 * system prompt alone exceeds the 4000-character limit that all supported databases
 * share for {@code varchar}, so an inline column is not an option.</p>
 *
 * <p>Correlation fields ({@code processInstanceId}, {@code executionId},
 * {@code processDefinitionId}, {@code processDefinitionKey},
 * {@code rootProcessInstanceId}) and {@code removalTime} are inherited from
 * {@link HistoryEvent} and are not redeclared here.</p>
 *
 * <p>Audit entries are append-only: an instance is never updated after insertion, so
 * {@link org.cibseven.bpm.engine.impl.history.handler.DbHistoryEventHandler} inserts it
 * directly rather than going through the insert-or-update path used by entities that
 * have a lifecycle.</p>
 *
 * @since 2.3
 */
public class AgentAuditHistoryEventEntity extends HistoryEvent {

  private static final long serialVersionUID = 1L;

  /** Wall-clock time at which the audited interaction occurred. */
  protected Date timestamp;

  protected String tenantId;

  /** Id of the BPMN activity the agent runs in. */
  protected String activityId;

  /** Version of the audit payload schema, so consumers can adapt to changes. */
  protected int schemaVersion;

  /** Kind of audit entry: {@code request}, {@code response}, {@code error} or {@code retrieval}. */
  protected String auditType;

  /** Correlates all entries belonging to one agent invocation. */
  protected String runId;

  /** Monotonic sequence number of this entry within {@link #runId}. */
  protected int eventSeq;

  /** LLM provider as reported by the chat model, e.g. {@code OPEN_AI}. */
  protected String provider;

  /** Model name or provider-reported snapshot used for this turn. */
  protected String model;

  /** Provider-assigned response identifier, when reported. */
  protected String responseId;

  /** Endpoint the request was sent to — relevant for data-residency questions. */
  protected String endpoint;

  /** Engine user on whose behalf the agent ran, when an authentication was present. */
  protected String userId;

  /** Comma-separated group ids of {@link #userId}. */
  protected String groupIds;

  /** Provider-reported reason the turn ended, e.g. {@code STOP}, {@code CONTENT_FILTER}. */
  protected String finishReason;

  /** Duration of the LLM call in milliseconds; {@code null} on request events. */
  protected Long durationMs;

  /** Class name of the failure on error events; {@code null} otherwise. */
  protected String errorClass;

  /** Model invocation parameters as JSON (temperature, topP, maxTokens, seed, ...). */
  protected String modelParams;

  /** Full audit payload as JSON, including message history, tools and tool calls. */
  protected byte[] payload;

  /** Reference into {@code ACT_GE_BYTEARRAY} holding {@link #payload}. */
  protected String payloadByteArrayId;

  public Date getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(Date timestamp) {
    this.timestamp = timestamp;
  }

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  public String getActivityId() {
    return activityId;
  }

  public void setActivityId(String activityId) {
    this.activityId = activityId;
  }

  public int getSchemaVersion() {
    return schemaVersion;
  }

  public void setSchemaVersion(int schemaVersion) {
    this.schemaVersion = schemaVersion;
  }

  public String getAuditType() {
    return auditType;
  }

  public void setAuditType(String auditType) {
    this.auditType = auditType;
  }

  public String getRunId() {
    return runId;
  }

  public void setRunId(String runId) {
    this.runId = runId;
  }

  public int getEventSeq() {
    return eventSeq;
  }

  public void setEventSeq(int eventSeq) {
    this.eventSeq = eventSeq;
  }

  public String getProvider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public String getResponseId() {
    return responseId;
  }

  public void setResponseId(String responseId) {
    this.responseId = responseId;
  }

  public String getEndpoint() {
    return endpoint;
  }

  public void setEndpoint(String endpoint) {
    this.endpoint = endpoint;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getGroupIds() {
    return groupIds;
  }

  public void setGroupIds(String groupIds) {
    this.groupIds = groupIds;
  }

  public String getFinishReason() {
    return finishReason;
  }

  public void setFinishReason(String finishReason) {
    this.finishReason = finishReason;
  }

  public Long getDurationMs() {
    return durationMs;
  }

  public void setDurationMs(Long durationMs) {
    this.durationMs = durationMs;
  }

  public String getErrorClass() {
    return errorClass;
  }

  public void setErrorClass(String errorClass) {
    this.errorClass = errorClass;
  }

  public String getModelParams() {
    return modelParams;
  }

  public void setModelParams(String modelParams) {
    this.modelParams = modelParams;
  }

  public byte[] getPayload() {
    return payload;
  }

  public void setPayload(byte[] payload) {
    this.payload = payload;
  }

  public String getPayloadByteArrayId() {
    return payloadByteArrayId;
  }

  public void setPayloadByteArrayId(String payloadByteArrayId) {
    this.payloadByteArrayId = payloadByteArrayId;
  }

  @Override
  public String toString() {
    return this.getClass().getSimpleName()
           + "[id=" + id
           + ", eventType=" + eventType
           + ", auditType=" + auditType
           + ", runId=" + runId
           + ", eventSeq=" + eventSeq
           + ", provider=" + provider
           + ", model=" + model
           + ", finishReason=" + finishReason
           + ", durationMs=" + durationMs
           + ", errorClass=" + errorClass
           + ", activityId=" + activityId
           + ", executionId=" + executionId
           + ", processInstanceId=" + processInstanceId
           + ", processDefinitionKey=" + processDefinitionKey
           + ", tenantId=" + tenantId
           + ", payloadByteArrayId=" + payloadByteArrayId
           + ", removalTime=" + removalTime
           + "]";
  }

}
