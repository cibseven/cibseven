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
package org.cibseven.bpm.engine.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.history.HistoricActivityStatistics;
import org.cibseven.bpm.engine.history.HistoricActivityStatisticsPostQuery;
import org.cibseven.bpm.engine.history.HistoricProcessInstance;
import static org.cibseven.bpm.engine.impl.util.EnsureUtil.ensureEmpty;
import static org.cibseven.bpm.engine.impl.util.EnsureUtil.ensureNotNull;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.interceptor.CommandExecutor;
import org.cibseven.bpm.engine.impl.variable.serializer.VariableSerializers;

/**
 * Extended implementation for historic activity statistics query with support for
 * additional process instance filters.
 *
 * @author CIBSeven
 */
public class HistoricActivityStatisticsPostQueryImpl extends AbstractVariableQueryImpl<HistoricActivityStatisticsPostQuery, HistoricActivityStatistics> implements HistoricActivityStatisticsPostQuery {

  private static final long serialVersionUID = 1L;

  protected String processDefinitionId;

  // Include flags
  protected boolean includeFinished;
  protected boolean includeCanceled;
  protected boolean includeCompleteScope;
  protected boolean includeIncidents;

  // Process Instance filters
  protected String processInstanceId;
  protected Set<String> processInstanceIds;
  protected String[] processInstanceIdNotIn;
  protected String rootProcessInstanceId;

  // Process Definition filters
  protected String processDefinitionKey;
  protected String[] processDefinitionKeys;
  protected List<String> processDefinitionKeyNotIn;
  protected String processDefinitionName;
  protected String processDefinitionNameLike;

  // Business Key filters
  protected String businessKey;
  protected String[] businessKeyIn;
  protected String businessKeyLike;

  // State filters
  protected boolean finished;
  protected boolean unfinished;
  protected boolean withJobsRetrying;
  protected boolean withIncidents;
  protected boolean withRootIncidents;

  // Incident filters
  protected String incidentType;
  protected String incidentStatus;
  protected String incidentMessage;
  protected String incidentMessageLike;
  protected String[] incidentIds;

  // Date filters
  protected Date startedBefore;
  protected Date startedAfter;
  protected Date finishedBefore;
  protected Date finishedAfter;
  protected Date executedActivityAfter;
  protected Date executedActivityBefore;
  protected Date executedJobAfter;
  protected Date executedJobBefore;

  // User and Case filters
  protected String startedBy;
  protected boolean isRootProcessInstances;
  protected String superProcessInstanceId;
  protected String subProcessInstanceId;
  protected String superCaseInstanceId;
  protected String subCaseInstanceId;
  protected String caseInstanceId;

  // Tenant filters
  protected String[] tenantIds;
  protected boolean isTenantIdSet;

  // Activity filters
  protected String[] executedActivityIds;
  protected String[] activeActivityIds;
  protected String[] activityIds;

  // Activity State filters
  
  protected boolean active;
  protected boolean suspended;
  protected boolean completed;
  protected boolean externallyTerminated;
  protected boolean internallyTerminated;

  // Variable filters
  protected boolean variableNamesIgnoreCase;
  protected boolean variableValuesIgnoreCase;
  protected Map<String, Set<QueryVariableValue>> queryVariableNameToValuesMap = new HashMap<>();

  // Or queries support
  protected List<HistoricActivityStatisticsPostQueryImpl> queries = new ArrayList<>(Collections.singletonList(this));
  protected boolean isOrQueryActive = false;

  protected Set<String> state = new HashSet<>();
  protected Date startDateBy;
  protected Date startDateOn;
  protected Date finishDateBy;
  protected Date finishDateOn;
  protected Date startDateOnBegin;
  protected Date startDateOnEnd;
  protected Date finishDateOnBegin;
  protected Date finishDateOnEnd;


  public HistoricActivityStatisticsPostQueryImpl(String processDefinitionId) {
    this.processDefinitionId = processDefinitionId;
  }

  public HistoricActivityStatisticsPostQueryImpl(String processDefinitionId, CommandExecutor commandExecutor) {
    super(commandExecutor);
    this.processDefinitionId = processDefinitionId;
  }


  
  // Include methods

  @Override
  public HistoricActivityStatisticsPostQueryImpl includeFinished() {
    this.includeFinished = true;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl includeCanceled() {
    this.includeCanceled = true;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl includeCompleteScope() {
    this.includeCompleteScope = true;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl includeIncidents() {
    this.includeIncidents = true;
    return this;
  }

  // Process Instance filters

  @Override
  public HistoricActivityStatisticsPostQueryImpl processInstanceId(String processInstanceId) {
    this.processInstanceId = processInstanceId;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl processInstanceIds(Set<String> processInstanceIds) {
    this.processInstanceIds = processInstanceIds;
    return this;
  }

  
  public HistoricActivityStatisticsPostQueryImpl processInstanceIdIn(String... processInstanceIds) {
    this.processInstanceIds = new HashSet<>(Arrays.asList(processInstanceIds));
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl processInstanceIdNotIn(String... processInstanceIds) {
    this.processInstanceIdNotIn = processInstanceIds;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl rootProcessInstanceId(String rootProcessInstanceId) {
    this.rootProcessInstanceId = rootProcessInstanceId;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl rootProcessInstances() {
    if (superProcessInstanceId != null) {
      throw new BadUserRequestException("Invalid query usage: cannot set both rootProcessInstances and superProcessInstanceId");
    }
    if (superCaseInstanceId != null) {
      throw new BadUserRequestException("Invalid query usage: cannot set both rootProcessInstances and superCaseInstanceId");
    }
    isRootProcessInstances = true;
    return this;
  }

  // Process Definition filters

  @Override
  public HistoricActivityStatisticsPostQueryImpl processDefinitionKey(String processDefinitionKey) {
    // Intentionally ignored: this query supports instance-level filters only.
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl processDefinitionKeyIn(String... processDefinitionKeys) {
    // Intentionally ignored: this query supports instance-level filters only.
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl processDefinitionKeyNotIn(List<String> processDefinitionKeys) {
    // Intentionally ignored: this query supports instance-level filters only.
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl processDefinitionName(String processDefinitionName) {
    // Intentionally ignored: this query supports instance-level filters only.
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl processDefinitionNameLike(String processDefinitionNameLike) {
    // Intentionally ignored: this query supports instance-level filters only.
    return this;
  }

  // Business Key filters

  @Override
  public HistoricActivityStatisticsPostQueryImpl businessKey(String businessKey) {
    this.businessKey = businessKey;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl businessKeyIn(String... businessKeys) {
    this.businessKeyIn = businessKeys;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl businessKeyLike(String businessKeyLike) {
    this.businessKeyLike = businessKeyLike;
    return this;
  }

  // State filters

  @Override
  public HistoricActivityStatisticsPostQueryImpl finished() {
    this.finished = true;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl unfinished() {
    this.unfinished = true;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl withJobsRetrying() {
    this.withJobsRetrying = true;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl withIncidents() {
    this.withIncidents = true;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl withRootIncidents() {
    this.withRootIncidents = true;
    return this;
  }

  // Incident filters

  @Override
  public HistoricActivityStatisticsPostQueryImpl incidentType(String incidentType) {
    this.incidentType = incidentType;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl incidentStatus(String incidentStatus) {
    this.incidentStatus = incidentStatus;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl incidentMessage(String incidentMessage) {
    this.incidentMessage = incidentMessage;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl incidentMessageLike(String incidentMessageLike) {
    this.incidentMessageLike = incidentMessageLike;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl incidentIdIn(String... incidentIds) {
    this.incidentIds = incidentIds;
    return this;
  }

  // Date filters

  @Override
  public HistoricActivityStatisticsPostQueryImpl startedBefore(Date date) {
    this.startedBefore = date;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl startedAfter(Date date) {
    this.startedAfter = date;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl finishedBefore(Date date) {
    this.finishedBefore = date;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl finishedAfter(Date date) {
    this.finishedAfter = date;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl executedActivityAfter(Date date) {
    this.executedActivityAfter = date;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl executedActivityBefore(Date date) {
    this.executedActivityBefore = date;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl executedJobAfter(Date date) {
    this.executedJobAfter = date;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl executedJobBefore(Date date) {
    this.executedJobBefore = date;
    return this;
  }

  // User and Case filters

  @Override
  public HistoricActivityStatisticsPostQueryImpl startedBy(String userId) {
    this.startedBy = userId;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl superProcessInstanceId(String superProcessInstanceId) {
    if (isRootProcessInstances) {
      throw new BadUserRequestException("Invalid query usage: cannot set both rootProcessInstances and superProcessInstanceId");
    }
    this.superProcessInstanceId = superProcessInstanceId;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl subProcessInstanceId(String subProcessInstanceId) {
    this.subProcessInstanceId = subProcessInstanceId;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl superCaseInstanceId(String superCaseInstanceId) {
    this.superCaseInstanceId = superCaseInstanceId;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl subCaseInstanceId(String subCaseInstanceId) {
    this.subCaseInstanceId = subCaseInstanceId;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl caseInstanceId(String caseInstanceId) {
    this.caseInstanceId = caseInstanceId;
    return this;
  }

  // Tenant filters

  @Override
  public HistoricActivityStatisticsPostQueryImpl tenantIdIn(String... tenantIds) {
    ensureNotNull("tenantIds", (Object[]) tenantIds);
    this.tenantIds = tenantIds;
    this.isTenantIdSet = true;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl withoutTenantId() {
    this.tenantIds = null;
    this.isTenantIdSet = false;
    return this;
  }

  // Activity filters

  @Override
  public HistoricActivityStatisticsPostQueryImpl executedActivityIdIn(String... executedActivityIds) {
    this.executedActivityIds = executedActivityIds;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl activeActivityIdIn(String... activeActivityIds) {
    this.activeActivityIds = activeActivityIds;
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl activityIdIn(String... activityIds) {
    this.activityIds = activityIds;
    return this;
  }

  // Variable support

  @Override
  public HistoricActivityStatisticsPostQueryImpl variableValueEquals(String variableName, Object variableValue) {
    addVariable(variableName, variableValue, QueryOperator.EQUALS, true);
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl variableValueGreaterThan(String variableName, Object variableValue) {
    addVariable(variableName, variableValue, QueryOperator.GREATER_THAN, true);
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl variableValueGreaterThanOrEqual(String variableName, Object variableValue) {
    addVariable(variableName, variableValue, QueryOperator.GREATER_THAN_OR_EQUAL, true);
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl variableValueLessThan(String variableName, Object variableValue) {
    addVariable(variableName, variableValue, QueryOperator.LESS_THAN, true);
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl variableValueLessThanOrEqual(String variableName, Object variableValue) {
    addVariable(variableName, variableValue, QueryOperator.LESS_THAN_OR_EQUAL, true);
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl variableValueNotEquals(String variableName, Object variableValue) {
    addVariable(variableName, variableValue, QueryOperator.NOT_EQUALS, true);
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl variableValueLike(String variableName, String variableValue) {
    addVariable(variableName, variableValue, QueryOperator.LIKE, true);
    return this;
  }
  protected void addState(String state) {
    this.state.add(state);
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl matchVariableNamesIgnoreCase() {
    variableNamesIgnoreCase = true;
    for (QueryVariableValue variable : getQueryVariableValues()) {
      variable.setVariableNameIgnoreCase(true);
    }
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQueryImpl matchVariableValuesIgnoreCase() {
    variableValuesIgnoreCase = true;
    for (QueryVariableValue variable : getQueryVariableValues()) {
      variable.setVariableValueIgnoreCase(true);
    }
    return this;
  }

  protected void addVariable(String name, Object value, QueryOperator operator, boolean processInstanceScope) {
    boolean matchNamesIgnoreCase = Boolean.TRUE.equals(variableNamesIgnoreCase);
    boolean matchValuesIgnoreCase = Boolean.TRUE.equals(variableValuesIgnoreCase)
        && value != null && String.class.isAssignableFrom(value.getClass());
    QueryVariableValue queryVariableValue = new QueryVariableValue(name, value, operator,
        processInstanceScope, matchNamesIgnoreCase, matchValuesIgnoreCase);
    Set<QueryVariableValue> existing = queryVariableNameToValuesMap.get(name);
    if (existing == null) {
      queryVariableNameToValuesMap.put(name, new HashSet<>(Collections.singletonList(queryVariableValue)));
    } else {
      existing.add(queryVariableValue);
    }
  }

  public List<QueryVariableValue> getQueryVariableValues() {
    return queryVariableNameToValuesMap.values()
        .stream()
        .flatMap(Set::stream)
        .collect(Collectors.toList());
  }

  public Map<String, Set<QueryVariableValue>> getQueryVariableNameToValuesMap() {
    return queryVariableNameToValuesMap;
  }

  // Nested Queries support

  public void setIsOrQueryActive() {
    isOrQueryActive = true;
  }

  public void addOrQuery(HistoricActivityStatisticsPostQueryImpl orQuery) {
    orQuery.isOrQueryActive = true;
    orQuery.processDefinitionId = null;
    queries.add(orQuery);
  }

  // Execution methods

  public long executeCount(CommandContext commandContext) {
    checkQueryOk();
    ensureVariablesInitialized();
    return commandContext
      .getHistoricStatisticsManager()
      .getHistoricStatisticsCountGroupedByActivity(this);
  }

  public List<HistoricActivityStatistics> executeList(CommandContext commandContext, Page page) {
    checkQueryOk();
    ensureVariablesInitialized();
    return commandContext
      .getHistoricStatisticsManager()
      .getHistoricStatisticsGroupedByActivity(this, page);
  }

  protected void ensureVariablesInitialized() {
    ProcessEngineConfigurationImpl config = Context.getProcessEngineConfiguration();
    VariableSerializers serializers = config.getVariableSerializers();
    String dbType = config.getDatabaseType();
    List<QueryVariableValue> vars = getQueryVariableValues();
    if (!vars.isEmpty()) {
      for (QueryVariableValue var : vars) {
        var.initialize(serializers, dbType);
      }
    }
    for (HistoricActivityStatisticsPostQueryImpl query : getQueries()) {
      if (query != this) {
        List<QueryVariableValue> orQueryVars = query.getQueryVariableValues();
        if (!orQueryVars.isEmpty()) {
          for (QueryVariableValue var : orQueryVars) {
            var.initialize(serializers, dbType);
          }
        }
      }
    }
  }

  protected void checkQueryOk() {
    super.checkQueryOk();
    ensureNotNull("processDefinitionId", processDefinitionId);
  }

  // Getters /////////////////////////////////////////////////

  public String getProcessDefinitionId() {
    return processDefinitionId;
  }

  public void setProcessDefinitionId(String processDefinitionId) {
    this.processDefinitionId = processDefinitionId;
  }

  public boolean isIncludeFinished() {
    return includeFinished;
  }

  public boolean isIncludeCanceled() {
    return includeCanceled;
  }

  public boolean isIncludeCompleteScope() {
    return includeCompleteScope;
  }

  public boolean isIncludeIncidents() {
    return includeIncidents;
  }

  public String getProcessInstanceId() {
    return processInstanceId;
  }

  public Set<String> getProcessInstanceIds() {
    return processInstanceIds;
  }

  public String[] getProcessInstanceIdNotIn() {
    return processInstanceIdNotIn;
  }

  public String getRootProcessInstanceId() {
    return rootProcessInstanceId;
  }

  public boolean isRootProcessInstances() {
    return isRootProcessInstances;
  }

  public String getProcessDefinitionKey() {
    return null;
  }

  public String[] getProcessDefinitionKeys() {
    return null;
  }

  public List<String> getProcessDefinitionKeyNotIn() {
    return null;
  }

  public String getProcessDefinitionName() {
    return null;
  }

  public String getProcessDefinitionNameLike() {
    return null;
  }

  public String getBusinessKey() {
    return businessKey;
  }

  public String[] getBusinessKeyIn() {
    return businessKeyIn;
  }

  public String getBusinessKeyLike() {
    return businessKeyLike;
  }

  public boolean isFinished() {
    return finished;
  }

  public boolean isUnfinished() {
    return unfinished;
  }

  public boolean isWithJobsRetrying() {
    return withJobsRetrying;
  }

  public boolean isWithIncidents() {
    return withIncidents;
  }

  public boolean isWithRootIncidents() {
    return withRootIncidents;
  }

  public String getIncidentType() {
    return incidentType;
  }

  public String getIncidentStatus() {
    return incidentStatus;
  }

  public String getIncidentMessage() {
    return incidentMessage;
  }

  public String getIncidentMessageLike() {
    return incidentMessageLike;
  }

  public String[] getIncidentIds() {
    return incidentIds;
  }

  public Date getStartedBefore() {
    return startedBefore;
  }

  public Date getStartedAfter() {
    return startedAfter;
  }

  public Date getFinishedBefore() {
    return finishedBefore;
  }

  public Date getFinishedAfter() {
    return finishedAfter;
  }

  public Date getExecutedActivityAfter() {
    return executedActivityAfter;
  }

  public Date getExecutedActivityBefore() {
    return executedActivityBefore;
  }

  public Date getExecutedJobAfter() {
    return executedJobAfter;
  }

  public Date getExecutedJobBefore() {
    return executedJobBefore;
  }

  public String getStartedBy() {
    return startedBy;
  }

  public String getSuperProcessInstanceId() {
    return superProcessInstanceId;
  }

  public String getSubProcessInstanceId() {
    return subProcessInstanceId;
  }

  public String getSuperCaseInstanceId() {
    return superCaseInstanceId;
  }

  public String getSubCaseInstanceId() {
    return subCaseInstanceId;
  }

  public String getCaseInstanceId() {
    return caseInstanceId;
  }
    public Set<String> getState() {
    return state;
  }

  public Date getFinishDateBy() {
    return finishDateBy;
  }

  public Date getStartDateBy() {
    return startDateBy;
  }

  public Date getStartDateOn() {
    return startDateOn;
  }

  public Date getStartDateOnBegin() {
    return startDateOnBegin;
  }

  public Date getStartDateOnEnd() {
    return startDateOnEnd;
  }

  public Date getFinishDateOn() {
    return finishDateOn;
  }

  public Date getFinishDateOnBegin() {
    return finishDateOnBegin;
  }

  public Date getFinishDateOnEnd() {
    return finishDateOnEnd;
  }

  public String[] getTenantIds() {
    return tenantIds;
  }

  public boolean isWithoutTenantId() {
    return !isTenantIdSet && tenantIds == null;
  }

  public boolean isTenantIdSet(){
    return isTenantIdSet;
  }

  public String[] getExecutedActivityIds() {
    return executedActivityIds;
  }

  public String[] getActiveActivityIds() {
    return activeActivityIds;
  }

  public String[] getActivityIds() {
    return activityIds;
  }

  public boolean isActive() {
    return active;
  }

  public boolean isSuspended() {
    return suspended;
  }

  public boolean isCompleted() {
    return completed;
  }

  public boolean isExternallyTerminated() {
    return externallyTerminated;
  }

  public boolean isInternallyTerminated() {
    return internallyTerminated;
  }

  public List<HistoricActivityStatisticsPostQueryImpl> getQueries() {
    return queries;
  }

  public boolean isOrQueryActive() {
    return isOrQueryActive;
  }

  public HistoricActivityStatisticsPostQuery orderByActivityId() {
    return orderBy(HistoricActivityStatisticsQueryProperty.ACTIVITY_ID_);
  }

  @Override
  public HistoricActivityStatisticsPostQuery active() {
    if(!isOrQueryActive) {
      ensureEmpty(BadUserRequestException.class, "Already querying for historic process instance with another state", state);
    }
    state.add(HistoricProcessInstance.STATE_ACTIVE);
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQuery suspended() {
    if(!isOrQueryActive) {
      ensureEmpty(BadUserRequestException.class, "Already querying for historic process instance with another state", state);
    }
    state.add(HistoricProcessInstance.STATE_SUSPENDED);
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQuery completed() {
    if(!isOrQueryActive) {
      ensureEmpty(BadUserRequestException.class, "Already querying for historic process instance with another state", state);
    }
    state.add(HistoricProcessInstance.STATE_COMPLETED);
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQuery externallyTerminated() {
    if(!isOrQueryActive) {
      ensureEmpty(BadUserRequestException.class, "Already querying for historic process instance with another state", state);
    }
    state.add(HistoricProcessInstance.STATE_EXTERNALLY_TERMINATED);
    return this;
  }

  @Override
  public HistoricActivityStatisticsPostQuery internallyTerminated() {
    if(!isOrQueryActive) {
      ensureEmpty(BadUserRequestException.class, "Already querying for historic process instance with another state", state);
    }
    state.add(HistoricProcessInstance.STATE_INTERNALLY_TERMINATED);
    return this;
  }

}
