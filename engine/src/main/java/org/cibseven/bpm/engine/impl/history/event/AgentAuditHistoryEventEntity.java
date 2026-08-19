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
package org.cibseven.bpm.engine.impl.history.event;

import java.util.Date;

/**
 * One audit entry of an AI agent activity, stored in {@code ACT_HI_AGENT_AUDIT}.
 *
 * <p>Request, response, error and retrieval entries all share the single event type
 * {@link HistoryEventTypes#AGENT_AUDIT}; which one it is, is carried in {@link #auditType}.</p>
 *
 * <p>Scalar fields are individual columns so they are reachable with plain SQL. The unbounded
 * parts of the entry — message history, tool inventory, tool calls — go into {@link #payload},
 * which is written to {@code ACT_GE_BYTEARRAY}: the agent system prompt alone exceeds the
 * 4000-character {@code varchar} limit shared by all supported databases.</p>
 */
public class AgentAuditHistoryEventEntity extends HistoryEvent {

  private static final long serialVersionUID = 1L;

  protected Date timestamp;
  protected String tenantId;
  protected String activityId;
  protected int schemaVersion;

  /** One of {@code request}, {@code response}, {@code error}, {@code retrieval}. */
  protected String auditType;

  /** Correlates all entries of one agent invocation; {@link #eventSeq} orders them within it. */
  protected String runId;
  protected int eventSeq;

  protected String provider;
  protected String model;
  protected String responseId;
  protected String endpoint;
  protected String userId;

  /** Comma-separated. */
  protected String groupIds;

  protected String finishReason;

  /** {@code null} on request entries, which have not completed yet. */
  protected Long durationMs;

  protected String errorClass;

  /** JSON. */
  protected String modelParams;

  /** The complete entry as JSON, including message history, tools and tool calls. */
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
