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
package org.cibseven.bpm.engine.rest.dto.history;

import static java.lang.Boolean.TRUE;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response.Status;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.history.HistoricActivityStatisticsPostQuery;
import org.cibseven.bpm.engine.impl.HistoricActivityStatisticsPostQueryImpl;
import org.cibseven.bpm.engine.rest.dto.AbstractQueryDto;
import org.cibseven.bpm.engine.rest.dto.VariableQueryParameterDto;
import org.cibseven.bpm.engine.rest.exception.InvalidRequestException;

import com.fasterxml.jackson.databind.ObjectMapper;

public class HistoricActivityStatisticsPostQueryDto extends AbstractQueryDto<HistoricActivityStatisticsPostQuery> {

  protected static final String SORT_ORDER_ACTIVITY_ID = "activityId";

  private String processInstanceId;
  private Set<String> processInstanceIds;
  private List<String> processInstanceIdNotIn;
  private String rootProcessInstanceId;
  private String processDefinitionId;
  private String processDefinitionKey;
  private List<String> processDefinitionKeys;
  private String processDefinitionName;
  private String processDefinitionNameLike;
  private List<String> processDefinitionKeyNotIn;
  private String processInstanceBusinessKey;
  private List<String> processInstanceBusinessKeyIn;
  private String processInstanceBusinessKeyLike;
  private Boolean rootProcessInstances;
  private Boolean finished;
  private Boolean unfinished;
  private Boolean withJobsRetrying;
  private Boolean withIncidents;
  private Boolean withRootIncidents;
  private String incidentType;
  private String incidentStatus;
  private String incidentMessage;
  private String incidentMessageLike;
  private Date startedBefore;
  private Date startedAfter;
  private Date finishedBefore;
  private Date finishedAfter;
  private Date executedActivityAfter;
  private Date executedActivityBefore;
  private Date executedJobAfter;
  private Date executedJobBefore;
  private String startedBy;
  private String superProcessInstanceId;
  private String subProcessInstanceId;
  private String superCaseInstanceId;
  private String subCaseInstanceId;
  private String caseInstanceId;
  private List<String> tenantIds;
  private Boolean withoutTenantId;
  private List<String> executedActivityIdIn;
  private List<String> activeActivityIdIn;
  private List<String> activityIdIn;
  private Boolean active;
  private Boolean suspended;
  private Boolean completed;
  private Boolean externallyTerminated;
  private Boolean internallyTerminated;
  private List<String> incidentIds;

  private Boolean includeCanceled;
  private Boolean includeFinished;
  private Boolean includeCompleteScope;
  private Boolean includeIncidents;

  private List<VariableQueryParameterDto> variables;
  protected Boolean variableNamesIgnoreCase;
  protected Boolean variableValuesIgnoreCase;
  private List<HistoricActivityStatisticsPostQueryDto> orQueries;

  public HistoricActivityStatisticsPostQueryDto() {
  }

  public HistoricActivityStatisticsPostQueryDto(ObjectMapper objectMapper,String processDefinitionId, MultivaluedMap<String, String> bodyParams) {
    super(objectMapper, bodyParams);
    this.processDefinitionId = processDefinitionId;
  }

  public void setProcessDefinitionId(String processDefinitionId) {
    this.processDefinitionId = processDefinitionId;
  }

  public void setOrQueries(List<HistoricActivityStatisticsPostQueryDto> orQueries) {
    this.orQueries = orQueries;
  }

  public void setProcessInstanceId(String processInstanceId) {
    this.processInstanceId = processInstanceId;
  }

  public void setProcessInstanceIds(Set<String> processInstanceIds) {
    this.processInstanceIds = processInstanceIds;
  }

  public void setProcessInstanceIdNotIn(List<String> processInstanceIdNotIn) {
    this.processInstanceIdNotIn = processInstanceIdNotIn;
  }

  public void setRootProcessInstanceId(String rootProcessInstanceId) {
    this.rootProcessInstanceId = rootProcessInstanceId;
  }

  public void setProcessDefinitionName(String processDefinitionName) {
    this.processDefinitionName = processDefinitionName;
  }

  public void setProcessDefinitionNameLike(String processDefinitionNameLike) {
    this.processDefinitionNameLike = processDefinitionNameLike;
  }

  public void setProcessDefinitionKey(String processDefinitionKey) {
    this.processDefinitionKey = processDefinitionKey;
  }

  public void setProcessDefinitionKeyIn(List<String> processDefinitionKeys) {
    this.processDefinitionKeys = processDefinitionKeys;
  }

  public void setProcessDefinitionKeyNotIn(List<String> processDefinitionKeys) {
    this.processDefinitionKeyNotIn = processDefinitionKeys;
  }

  public void setProcessInstanceBusinessKey(String businessKey) {
    this.processInstanceBusinessKey = businessKey;
  }

  public void setProcessInstanceBusinessKeyIn(List<String> businessKeyIn) {
    this.processInstanceBusinessKeyIn = businessKeyIn;
  }

  public void setProcessInstanceBusinessKeyLike(String businessKeyLike) {
    this.processInstanceBusinessKeyLike = businessKeyLike;
  }

  public void setRootProcessInstances(Boolean rootProcessInstances) {
    this.rootProcessInstances = rootProcessInstances;
  }

  public void setFinished(Boolean finished) {
    this.finished = finished;
  }

  public void setUnfinished(Boolean unfinished) {
    this.unfinished = unfinished;
  }

  public void setWithJobsRetrying(Boolean withJobsRetrying) {
    this.withJobsRetrying = withJobsRetrying;
  }

  public void setWithIncidents(Boolean withIncidents) {
    this.withIncidents = withIncidents;
  }

  public void setWithRootIncidents(Boolean withRootIncidents) {
    this.withRootIncidents = withRootIncidents;
  }

  public void setIncidentStatus(String status) {
    this.incidentStatus = status;
  }

  public void setIncidentMessage(String incidentMessage) {
    this.incidentMessage = incidentMessage;
  }

  public void setIncidentMessageLike(String incidentMessageLike) {
    this.incidentMessageLike = incidentMessageLike;
  }

  public void setStartedBefore(Date startedBefore) {
    this.startedBefore = startedBefore;
  }

  public void setStartedAfter(Date startedAfter) {
    this.startedAfter = startedAfter;
  }

  public void setFinishedBefore(Date finishedBefore) {
    this.finishedBefore = finishedBefore;
  }

  public void setFinishedAfter(Date finishedAfter) {
    this.finishedAfter = finishedAfter;
  }

  public void setStartedBy(String startedBy) {
    this.startedBy = startedBy;
  }

  public void setSuperProcessInstanceId(String superProcessInstanceId) {
    this.superProcessInstanceId = superProcessInstanceId;
  }

  public void setSubProcessInstanceId(String subProcessInstanceId) {
    this.subProcessInstanceId = subProcessInstanceId;
  }

  public void setSuperCaseInstanceId(String superCaseInstanceId) {
    this.superCaseInstanceId = superCaseInstanceId;
  }

  public void setSubCaseInstanceId(String subCaseInstanceId) {
    this.subCaseInstanceId = subCaseInstanceId;
  }

  public void setCaseInstanceId(String caseInstanceId) {
    this.caseInstanceId = caseInstanceId;
  }

  public void setVariables(List<VariableQueryParameterDto> variables) {
    this.variables = variables;
  }

  public void setVariableNamesIgnoreCase(Boolean variableNamesIgnoreCase) {
    this.variableNamesIgnoreCase = variableNamesIgnoreCase;
  }

  public void setVariableValuesIgnoreCase(Boolean variableValuesIgnoreCase) {
    this.variableValuesIgnoreCase = variableValuesIgnoreCase;
  }

  public void setIncidentType(String incidentType) {
    this.incidentType = incidentType;
  }

  public void setIncidentIdIn(List<String> incidentIds) {
    this.incidentIds = incidentIds;
  }

  public void setTenantIdIn(List<String> tenantIds) {
    this.tenantIds = tenantIds;
  }

  public void setWithoutTenantId(Boolean withoutTenantId) {
    this.withoutTenantId = withoutTenantId;
  }

  public void setExecutedActivityAfter(Date executedActivityAfter) {
    this.executedActivityAfter = executedActivityAfter;
  }

  public void setExecutedActivityIdIn(List<String> executedActivityIds) {
    this.executedActivityIdIn = executedActivityIds;
  }

  public void setExecutedActivityBefore(Date executedActivityBefore) {
    this.executedActivityBefore = executedActivityBefore;
  }

  public void setActiveActivityIdIn(List<String> activeActivityIdIn) {
    this.activeActivityIdIn = activeActivityIdIn;
  }

  public void setActivityIdIn(List<String> activityIdIn) {
    this.activityIdIn = activityIdIn;
  }

  public void setExecutedJobAfter(Date executedJobAfter) {
    this.executedJobAfter = executedJobAfter;
  }

  public void setExecutedJobBefore(Date executedJobBefore) {
    this.executedJobBefore = executedJobBefore;
  }

  public void setActive(Boolean active) {
    this.active = active;
  }

  public void setSuspended(Boolean suspended) {
    this.suspended = suspended;
  }

  public void setCompleted(Boolean completed) {
    this.completed = completed;
  }

  public void setExternallyTerminated(Boolean externallyTerminated) {
    this.externallyTerminated = externallyTerminated;
  }

  public void setInternallyTerminated(Boolean internallyTerminated) {
    this.internallyTerminated = internallyTerminated;
  }

  public void setIncludeCanceled(Boolean includeCanceled) {
    this.includeCanceled = includeCanceled;
  }

  public void setIncludeFinished(Boolean includeFinished) {
    this.includeFinished = includeFinished;
  }

  public void setIncludeCompleteScope(Boolean includeCompleteScope) {
    this.includeCompleteScope = includeCompleteScope;
  }

  public void setIncludeIncidents(Boolean includeIncidents) {
    this.includeIncidents = includeIncidents;
  }

  @Override
  protected HistoricActivityStatisticsPostQuery createNewQuery(ProcessEngine engine) {
    return engine.getHistoryService().createHistoricActivityStatisticsPostQuery(processDefinitionId);
  }

  @Override
  protected void applyFilters(HistoricActivityStatisticsPostQuery query) {
    // Apply include flags first - these affect how other filters work
    if (includeCanceled != null && includeCanceled) {
      query.includeCanceled();
    }
    if (includeFinished != null && includeFinished) {
      query.includeFinished();
    }
    if (includeCompleteScope != null && includeCompleteScope) {
      query.includeCompleteScope();
    }
    if (includeIncidents != null && includeIncidents) {
      query.includeIncidents();
    }

    if (orQueries != null) {
      for (HistoricActivityStatisticsPostQueryDto orQueryDto : orQueries) {
        orQueryDto.setObjectMapper(objectMapper);
        HistoricActivityStatisticsPostQueryImpl orQuery = new HistoricActivityStatisticsPostQueryImpl(processDefinitionId);
        orQuery.setIsOrQueryActive();
        orQueryDto.applyFilters(orQuery);
        ((HistoricActivityStatisticsPostQueryImpl) query).addOrQuery(orQuery);
      }
    }
    if (processInstanceId != null) {
      query.processInstanceId(processInstanceId);
    }
    if (processInstanceIds != null) {
      query.processInstanceIds(processInstanceIds);
    }
    if (processInstanceIdNotIn != null && !processInstanceIdNotIn.isEmpty()) {
      query.processInstanceIdNotIn(processInstanceIdNotIn.toArray(new String[0]));
    }
    if (rootProcessInstanceId != null) {
      query.rootProcessInstanceId(rootProcessInstanceId);
    }
    if (processDefinitionKey != null) {
      query.processDefinitionKey(processDefinitionKey);
    }
    if (processDefinitionKeys != null && !processDefinitionKeys.isEmpty()) {
      query.processDefinitionKeyIn(processDefinitionKeys.toArray(new String[0]));
    }
    if (processDefinitionName != null) {
      query.processDefinitionName(processDefinitionName);
    }
    if (processDefinitionNameLike != null) {
      query.processDefinitionNameLike(processDefinitionNameLike);
    }
    if (processDefinitionKeyNotIn != null) {
      query.processDefinitionKeyNotIn(processDefinitionKeyNotIn);
    }
    if (processInstanceBusinessKey != null) {
      query.businessKey(processInstanceBusinessKey);
    }
    if (processInstanceBusinessKeyIn != null && !processInstanceBusinessKeyIn.isEmpty()) {
      query.businessKeyIn(processInstanceBusinessKeyIn.toArray(new String[0]));
    }
    if (processInstanceBusinessKeyLike != null) {
      query.businessKeyLike(processInstanceBusinessKeyLike);
    }
    if (rootProcessInstances != null && rootProcessInstances) {
      query.rootProcessInstances();
    }
    if (finished != null && finished) {
      query.finished();
    }
    if (unfinished != null && unfinished) {
      query.unfinished();
    }
    if (withJobsRetrying != null && withJobsRetrying) {
      query.withJobsRetrying();
    }
    if (withIncidents != null && withIncidents) {
      query.withIncidents();
    }
    if (withRootIncidents != null && withRootIncidents) {
      query.withRootIncidents();
    }
    if (incidentIds != null && !incidentIds.isEmpty()) {
      query.incidentIdIn(incidentIds.toArray(new String[0]));
    }
    if (incidentStatus != null) {
      query.incidentStatus(incidentStatus);
    }
    if (incidentType != null) {
      query.incidentType(incidentType);
    }
    if (incidentMessage != null) {
      query.incidentMessage(incidentMessage);
    }
    if (incidentMessageLike != null) {
      query.incidentMessageLike(incidentMessageLike);
    }
    if (startedBefore != null) {
      query.startedBefore(startedBefore);
    }
    if (startedAfter != null) {
      query.startedAfter(startedAfter);
    }
    if (finishedBefore != null) {
      query.finishedBefore(finishedBefore);
    }
    if (finishedAfter != null) {
      query.finishedAfter(finishedAfter);
    }
    if (startedBy != null) {
      query.startedBy(startedBy);
    }
    if (superProcessInstanceId != null) {
      query.superProcessInstanceId(superProcessInstanceId);
    }
    if (subProcessInstanceId != null) {
      query.subProcessInstanceId(subProcessInstanceId);
    }
    if (superCaseInstanceId != null) {
      query.superCaseInstanceId(superCaseInstanceId);
    }
    if (subCaseInstanceId != null) {
      query.subCaseInstanceId(subCaseInstanceId);
    }
    if (caseInstanceId != null) {
      query.caseInstanceId(caseInstanceId);
    }
    if (tenantIds != null && !tenantIds.isEmpty()) {
      query.tenantIdIn(tenantIds.toArray(new String[0]));
    }
    if (TRUE.equals(withoutTenantId)) {
      query.withoutTenantId();
    }
    if (TRUE.equals(variableNamesIgnoreCase)) {
      query.matchVariableNamesIgnoreCase();
    }
    if (TRUE.equals(variableValuesIgnoreCase)) {
      query.matchVariableValuesIgnoreCase();
    }
    if (variables != null) {
      for (VariableQueryParameterDto variableQueryParam : variables) {
        String variableName = variableQueryParam.getName();
        String op = variableQueryParam.getOperator();
        Object variableValue = variableQueryParam.resolveValue(objectMapper);

        if (op.equals(VariableQueryParameterDto.EQUALS_OPERATOR_NAME)) {
          query.variableValueEquals(variableName, variableValue);
        } else if (op.equals(VariableQueryParameterDto.GREATER_THAN_OPERATOR_NAME)) {
          query.variableValueGreaterThan(variableName, variableValue);
        } else if (op.equals(VariableQueryParameterDto.GREATER_THAN_OR_EQUALS_OPERATOR_NAME)) {
          query.variableValueGreaterThanOrEqual(variableName, variableValue);
        } else if (op.equals(VariableQueryParameterDto.LESS_THAN_OPERATOR_NAME)) {
          query.variableValueLessThan(variableName, variableValue);
        } else if (op.equals(VariableQueryParameterDto.LESS_THAN_OR_EQUALS_OPERATOR_NAME)) {
          query.variableValueLessThanOrEqual(variableName, variableValue);
        } else if (op.equals(VariableQueryParameterDto.NOT_EQUALS_OPERATOR_NAME)) {
          query.variableValueNotEquals(variableName, variableValue);
        } else if (op.equals(VariableQueryParameterDto.LIKE_OPERATOR_NAME)) {
          query.variableValueLike(variableName, String.valueOf(variableValue));
        } else {
          throw new InvalidRequestException(Status.BAD_REQUEST, "Invalid variable comparator specified: " + op);
        }
      }
    }

    if (executedActivityAfter != null) {
      query.executedActivityAfter(executedActivityAfter);
    }
    if (executedActivityBefore != null) {
      query.executedActivityBefore(executedActivityBefore);
    }
    if (executedActivityIdIn != null && !executedActivityIdIn.isEmpty()) {
      query.executedActivityIdIn(executedActivityIdIn.toArray(new String[0]));
    }
    if (activeActivityIdIn != null && !activeActivityIdIn.isEmpty()) {
      query.activeActivityIdIn(activeActivityIdIn.toArray(new String[0]));
    }
    if (activityIdIn != null && !activityIdIn.isEmpty()) {
      query.activityIdIn(activityIdIn.toArray(new String[0]));
    }
    if (executedJobAfter != null) {
      query.executedJobAfter(executedJobAfter);
    }
    if (executedJobBefore != null) {
      query.executedJobBefore(executedJobBefore);
    }
    if (active != null && active) {
      query.active();
    }
    if (suspended != null && suspended) {
      query.suspended();
    }
    if (completed != null && completed) {
      query.completed();
    }
    if (externallyTerminated != null && externallyTerminated) {
      query.externallyTerminated();
    }
    if (internallyTerminated != null && internallyTerminated) {
      query.internallyTerminated();
    }
  }

  @Override
  protected boolean isValidSortByValue(String value) {
    return SORT_ORDER_ACTIVITY_ID.equals(value);
  }

@Override
  protected void applySortBy(HistoricActivityStatisticsPostQuery query, String sortBy, Map<String, Object> parameters, ProcessEngine engine) {
    if (SORT_ORDER_ACTIVITY_ID.equals(sortBy)) {
      query.orderByActivityId();
    }
  }
}
