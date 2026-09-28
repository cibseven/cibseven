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
package org.cibseven.bpm.engine.history;

import java.util.Date;
import java.util.List;
import java.util.Set;
import org.cibseven.bpm.engine.query.Query;

/**
 * Extended query interface for historic activity statistics with support for
 * additional process instance filters.
 *
 * @author CIBSeven
 */
public interface HistoricActivityStatisticsPostQuery extends Query<HistoricActivityStatisticsPostQuery, HistoricActivityStatistics> {

  /**
   * Include an aggregation of finished instances in the result.
   */
  HistoricActivityStatisticsPostQuery includeFinished();

  /**
   * Include an aggregation of canceled instances in the result.
   */
  HistoricActivityStatisticsPostQuery includeCanceled();

  /**
   * Include an aggregation of instances, which complete a scope (ie. in bpmn manner: an activity
   * which consumed a token and did not produced a new one), in the result.
   */
  HistoricActivityStatisticsPostQuery includeCompleteScope();

  /** Include an aggregation of the incidents in the result. */
  HistoricActivityStatisticsPostQuery includeIncidents();

  // Process Instance Filters

  /** Select historic activities of process instances with the given ID */
  HistoricActivityStatisticsPostQuery processInstanceId(String processInstanceId);

  /** Select historic activities of process instances with any of the given IDs */
  HistoricActivityStatisticsPostQuery processInstanceIds(Set<String> processInstanceIds);

  /** Select historic activities of process instances without the given IDs */
  HistoricActivityStatisticsPostQuery processInstanceIdNotIn(String... processInstanceIds);

  /** Select historic activities of root process instances with the given ID */
  HistoricActivityStatisticsPostQuery rootProcessInstanceId(String rootProcessInstanceId);

  /** Only select root process instances */
  HistoricActivityStatisticsPostQuery rootProcessInstances();

  // Process Definition Filters

  /** Select historic activities of process definitions with the given key */
  HistoricActivityStatisticsPostQuery processDefinitionKey(String processDefinitionKey);

  /** Select historic activities of process definitions with any of the given keys */
  HistoricActivityStatisticsPostQuery processDefinitionKeyIn(String... processDefinitionKeys);

  /** Select historic activities of process definitions without the given keys */
  HistoricActivityStatisticsPostQuery processDefinitionKeyNotIn(List<String> processDefinitionKeys);

  /** Select historic activities of process definitions with the given name */
  HistoricActivityStatisticsPostQuery processDefinitionName(String processDefinitionName);

  /** Select historic activities of process definitions with a name matching the given pattern */
  HistoricActivityStatisticsPostQuery processDefinitionNameLike(String processDefinitionNameLike);

  // Business Key Filters

  /** Select historic activities of process instances with the given business key */
  HistoricActivityStatisticsPostQuery businessKey(String businessKey);

  /** Select historic activities of process instances with any of the given business keys */
  HistoricActivityStatisticsPostQuery businessKeyIn(String... businessKeys);

  /** Select historic activities of process instances with a business key matching the given pattern */
  HistoricActivityStatisticsPostQuery businessKeyLike(String businessKeyLike);

  // State Filters

  /** Select finished process instances */
  HistoricActivityStatisticsPostQuery finished();

  /** Select unfinished process instances */
  HistoricActivityStatisticsPostQuery unfinished();

  /** Select process instances with jobs in retry state */
  HistoricActivityStatisticsPostQuery withJobsRetrying();

  /** Select process instances with incidents */
  HistoricActivityStatisticsPostQuery withIncidents();

  /** Select process instances with root incidents */
  HistoricActivityStatisticsPostQuery withRootIncidents();

  // Incident Filters

  /** Select process instances with incidents of the given type */
  HistoricActivityStatisticsPostQuery incidentType(String incidentType);

  /** Select process instances with incidents of the given status */
  HistoricActivityStatisticsPostQuery incidentStatus(String incidentStatus);

  /** Select process instances with incidents with the given message */
  HistoricActivityStatisticsPostQuery incidentMessage(String incidentMessage);

  /** Select process instances with incidents with a message matching the given pattern */
  HistoricActivityStatisticsPostQuery incidentMessageLike(String incidentMessageLike);

  /** Select process instances with any of the given incident IDs */
  HistoricActivityStatisticsPostQuery incidentIdIn(String... incidentIds);

  // Date Filters

  /** Only select historic activities of process instances that were started before the given date. */
  HistoricActivityStatisticsPostQuery startedBefore(Date date);

  /** Only select historic activities of process instances that were started after the given date. */
  HistoricActivityStatisticsPostQuery startedAfter(Date date);

  /** Only select historic activities of process instances that were finished before the given date. */
  HistoricActivityStatisticsPostQuery finishedBefore(Date date);

  /** Only select historic activities of process instances that were finished after the given date. */
  HistoricActivityStatisticsPostQuery finishedAfter(Date date);

  /** Only select historic activities executed after the given date. */
  HistoricActivityStatisticsPostQuery executedActivityAfter(Date date);

  /** Only select historic activities executed before the given date. */
  HistoricActivityStatisticsPostQuery executedActivityBefore(Date date);

  /** Only select historic activities with jobs executed after the given date. */
  HistoricActivityStatisticsPostQuery executedJobAfter(Date date);

  /** Only select historic activities with jobs executed before the given date. */
  HistoricActivityStatisticsPostQuery executedJobBefore(Date date);

  // User and Case Filters

  /** Select historic activities started by the given user */
  HistoricActivityStatisticsPostQuery startedBy(String userId);

  /** Select historic activities of process instances with the given super process instance ID */
  HistoricActivityStatisticsPostQuery superProcessInstanceId(String superProcessInstanceId);

  /** Select historic activities of process instances with the given sub process instance ID */
  HistoricActivityStatisticsPostQuery subProcessInstanceId(String subProcessInstanceId);

  /** Select historic activities of case instances with the given super case instance ID */
  HistoricActivityStatisticsPostQuery superCaseInstanceId(String superCaseInstanceId);

  /** Select historic activities of case instances with the given sub case instance ID */
  HistoricActivityStatisticsPostQuery subCaseInstanceId(String subCaseInstanceId);

  /** Select historic activities of the given case instance ID */
  HistoricActivityStatisticsPostQuery caseInstanceId(String caseInstanceId);

  // Tenant Filter

  /** Select historic activities with any of the given tenant IDs */
  HistoricActivityStatisticsPostQuery tenantIdIn(String... tenantIds);

  /** Select historic activities without a tenant */
  HistoricActivityStatisticsPostQuery withoutTenantId();

  // Activity Filters

  /** Select historic activities with any of the given executed activity IDs */
  HistoricActivityStatisticsPostQuery executedActivityIdIn(String... executedActivityIds);

  /** Select historic activities with any of the given active activity IDs */
  HistoricActivityStatisticsPostQuery activeActivityIdIn(String... activeActivityIds);

  /** Select historic activities with any of the given activity IDs */
  HistoricActivityStatisticsPostQuery activityIdIn(String... activityIds);

  // Activity State Filters

  /** Select active activities */
  HistoricActivityStatisticsPostQuery active();

  /** Select suspended activities */
  HistoricActivityStatisticsPostQuery suspended();

  /** Select completed activities */
  HistoricActivityStatisticsPostQuery completed();

  /** Select externally terminated activities */
  HistoricActivityStatisticsPostQuery externallyTerminated();

  /** Select internally terminated activities */
  HistoricActivityStatisticsPostQuery internallyTerminated();

  // Variable support

  /**
   * Select process instances that have a variable with the given name
   */
  HistoricActivityStatisticsPostQuery variableValueEquals(String variableName, Object variableValue);

  /**
   * Select process instances that have a variable with the given name and value greater than the given value
   */
  HistoricActivityStatisticsPostQuery variableValueGreaterThan(String variableName, Object variableValue);

  /**
   * Select process instances that have a variable with the given name and value greater than or equal the given value
   */
  HistoricActivityStatisticsPostQuery variableValueGreaterThanOrEqual(String variableName, Object variableValue);

  /**
   * Select process instances that have a variable with the given name and value less than the given value
   */
  HistoricActivityStatisticsPostQuery variableValueLessThan(String variableName, Object variableValue);

  /**
   * Select process instances that have a variable with the given name and value less than or equal the given value
   */
  HistoricActivityStatisticsPostQuery variableValueLessThanOrEqual(String variableName, Object variableValue);

  /**
   * Select process instances that have a variable with the given name and value not equal the given value
   */
  HistoricActivityStatisticsPostQuery variableValueNotEquals(String variableName, Object variableValue);

  /**
   * Select process instances that have a variable with the given name and value like the given value
   */
  HistoricActivityStatisticsPostQuery variableValueLike(String variableName, String variableValue);

  /**
   * Match variable names and values in case sensitive manner
   */
  HistoricActivityStatisticsPostQuery matchVariableNamesIgnoreCase();

  /**
   * Match variable values in case insensitive manner
   */
  HistoricActivityStatisticsPostQuery matchVariableValuesIgnoreCase();

  /**
   * Order by Activity ID (needs to be followed by {@link #asc()} or {@link #desc()})
   */
  HistoricActivityStatisticsPostQuery orderByActivityId();

}
