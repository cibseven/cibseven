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
package org.cibseven.webapp.providers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.exception.NotFoundException;
import org.cibseven.bpm.engine.history.HistoricIncident;
import org.cibseven.bpm.engine.history.HistoricIncidentQuery;
import org.cibseven.bpm.engine.rest.dto.AnnotationDto;
import org.cibseven.bpm.engine.rest.dto.history.HistoricIncidentDto;
import org.cibseven.bpm.engine.rest.dto.history.HistoricIncidentQueryDto;
import org.cibseven.bpm.engine.rest.dto.runtime.IncidentDto;
import org.cibseven.bpm.engine.rest.dto.runtime.IncidentQueryDto;
import org.cibseven.bpm.engine.rest.dto.runtime.RetriesDto;
import org.cibseven.bpm.engine.rest.util.QueryUtil;
import org.cibseven.bpm.engine.runtime.IncidentQuery;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.NoObjectFoundException;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.Incident;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DirectIncidentProvider implements IIncidentProvider {

	DirectProviderUtil directProviderUtil;

	DirectIncidentProvider(DirectProviderUtil directProviderUtil){
		this.directProviderUtil = directProviderUtil;
	}

	@Override
	public Long countIncident(Map<String, Object> params, CIBUser user) {
		IncidentQueryDto queryDto = directProviderUtil.getObjectMapper(user).convertValue(params, IncidentQueryDto.class);
		IncidentQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));
		return query.count();
	}

	@Override
	public Long countHistoricIncident(Map<String, Object> params, CIBUser user) {
		HistoricIncidentQueryDto queryDto = directProviderUtil.getObjectMapper(user).convertValue(params, HistoricIncidentQueryDto.class);
		HistoricIncidentQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));
		long count = query.count();
		return count;
	}

	@Override
	public Collection<Incident> findIncident(Map<String, Object> params, CIBUser user) {
		Integer firstResult = directProviderUtil.getFirstResult(params);
		Integer maxResults = directProviderUtil.getMaxResults(params);
		Map<String, Object> queryParams = directProviderUtil.withoutPagingParams(params);

		IncidentQueryDto queryDto = directProviderUtil.getObjectMapper(user).convertValue(queryParams, IncidentQueryDto.class);
		IncidentQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));

		List<org.cibseven.bpm.engine.runtime.Incident> queryResult = QueryUtil.list(query, firstResult, maxResults);

		List<Incident> incidents = new ArrayList<>();
		for (org.cibseven.bpm.engine.runtime.Incident incident : queryResult) {
			IncidentDto dto = IncidentDto.fromIncident(incident);
			incidents.add(directProviderUtil.convertValue(dto, Incident.class, user));
		}
		RuntimeService runtimeService = directProviderUtil.getProcessEngine(user).getRuntimeService();
		for (Incident incident : incidents) {
			if (incident.getId() != null && incident.getRootCauseIncidentId() != null
					&& !incident.getId().equals(incident.getRootCauseIncidentId())) {
				try {
					// Fetch the root cause incident
					org.cibseven.bpm.engine.runtime.Incident engineIncident = runtimeService.createIncidentQuery().incidentId(incident.getRootCauseIncidentId())
							.singleResult();
					if (engineIncident == null) {
						throw new SystemException("No matching incident with id " + incident.getRootCauseIncidentId());
					}
					Incident rootCauseIncident = directProviderUtil.convertValue(IncidentDto.fromIncident(engineIncident), Incident.class, user);

					if (rootCauseIncident != null) {
						// Map root cause incident data to the specific fields
						incident.setCauseIncidentProcessInstanceId(rootCauseIncident.getProcessInstanceId());
						incident.setCauseIncidentProcessDefinitionId(rootCauseIncident.getProcessDefinitionId());
						incident.setCauseIncidentActivityId(rootCauseIncident.getActivityId());
						incident.setCauseIncidentFailedActivityId(rootCauseIncident.getFailedActivityId());
						incident.setRootCauseIncidentProcessInstanceId(rootCauseIncident.getProcessInstanceId());
						incident.setRootCauseIncidentProcessDefinitionId(rootCauseIncident.getProcessDefinitionId());
						incident.setRootCauseIncidentActivityId(rootCauseIncident.getActivityId());
						incident.setRootCauseIncidentFailedActivityId(rootCauseIncident.getFailedActivityId());
						incident.setRootCauseIncidentConfiguration(rootCauseIncident.getConfiguration());
						incident.setRootCauseIncidentMessage(rootCauseIncident.getIncidentMessage());
					}
				} catch (Exception e) {
					log.warn("Failed to enrich incident with ID: {} and root cause ID: {}", incident.getId(),
							incident.getRootCauseIncidentId(), e);
				}
			}
		}
		return incidents;
	}

	@Override
	public List<Incident> findIncidentByInstanceId(String processInstanceId, CIBUser user) {
		return fetchIncidents(null, null, processInstanceId, user);
	}

	@Override
	public Collection<Incident> fetchIncidents(String processDefinitionKey, CIBUser user) {
		return fetchIncidents(processDefinitionKey, null, null, user);
	}

	@Override
	public Collection<Incident> fetchIncidentsByInstanceAndActivityId(String processDefinitionKey, String activityId,
			CIBUser user) {
		//called only internally
		return fetchIncidents(processDefinitionKey, activityId, null, user);
	}

	@Override
	public void setIncidentAnnotation(String incidentId, Map<String, Object> data, CIBUser user) {
		AnnotationDto annotationDto = directProviderUtil.getObjectMapper(user).convertValue(data, AnnotationDto.class);
		directProviderUtil.getProcessEngine(user).getRuntimeService().setAnnotationForIncidentById(incidentId, annotationDto.getAnnotation());
	}

	@Override
	public void retryExternalTask(String externalTaskId, Map<String, Object> data, CIBUser user) {
		RetriesDto dto = directProviderUtil.getObjectMapper(user).convertValue(data, RetriesDto.class);
		Integer retries = dto.getRetries();

		if (retries == null) {
			throw new SystemException("The number of retries cannot be null.");
		}

		try {
			directProviderUtil.getProcessEngine(user).getExternalTaskService().setRetries(externalTaskId, retries);
		} catch (NotFoundException e) {
			throw new NoObjectFoundException(new SystemException("External task with id " + externalTaskId + " does not exist", e));
		}
	}

	@Override
	public String findHistoricExternalTaskErrorDetails(String externalTaskId, CIBUser user) {
		try {
			return directProviderUtil.getProcessEngine(user).getHistoryService().getHistoricExternalTaskLogErrorDetails(externalTaskId);
		} catch (AuthorizationException e) {
			throw e;
		} catch (ProcessEngineException e) {
			throw new SystemException(e.getMessage());
		}
	}

	@Override
	public Collection<Incident> findHistoricIncidents(Map<String, Object> params, CIBUser user) {
		Integer firstResult = directProviderUtil.getFirstResult(params);
		Integer maxResults = directProviderUtil.getMaxResults(params);
		Map<String, Object> queryParams = directProviderUtil.withoutPagingParams(params);

		HistoricIncidentQueryDto queryDto = directProviderUtil.getObjectMapper(user).convertValue(queryParams, HistoricIncidentQueryDto.class);
		HistoricIncidentQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));

		List<HistoricIncident> queryResult = QueryUtil.list(query, firstResult, maxResults);

		List<HistoricIncidentDto> historicIncidentDtos = new ArrayList<HistoricIncidentDto>();
		for (HistoricIncident historicIncident : queryResult) {
			HistoricIncidentDto dto = HistoricIncidentDto.fromHistoricIncident(historicIncident);
			historicIncidentDtos.add(dto);
		}

		List<Incident> incidents = new ArrayList<>();
		// Enrich historic incidents with root cause incident data (same enrichment
		// algorithm as current incidents)
		for (HistoricIncidentDto incidentDto : historicIncidentDtos) {
			Incident incident = directProviderUtil.convertValue(incidentDto, Incident.class, user);
			if (incidentDto.getId() != null && incidentDto.getRootCauseIncidentId() != null
					&& !incidentDto.getId().equals(incidentDto.getRootCauseIncidentId())) {
				try {
					// For historic incidents, try to fetch the root cause from historic
					// incidents first, then from current incidents
					HistoricIncidentDto rootCauseIncident = fetchHistoricIncidentById(incidentDto.getRootCauseIncidentId(), user,
							directProviderUtil.getObjectMapper(user));
					if (rootCauseIncident != null) {
						// Map root cause incident data to the specific fields
						incident.setCauseIncidentProcessInstanceId(rootCauseIncident.getProcessInstanceId());
						incident.setCauseIncidentProcessDefinitionId(rootCauseIncident.getProcessDefinitionId());
						incident.setCauseIncidentActivityId(rootCauseIncident.getActivityId());
						incident.setCauseIncidentFailedActivityId(rootCauseIncident.getFailedActivityId());
						incident.setRootCauseIncidentProcessInstanceId(rootCauseIncident.getProcessInstanceId());
						incident.setRootCauseIncidentProcessDefinitionId(rootCauseIncident.getProcessDefinitionId());
						incident.setRootCauseIncidentActivityId(rootCauseIncident.getActivityId());
						incident.setRootCauseIncidentFailedActivityId(rootCauseIncident.getFailedActivityId());
						incident.setRootCauseIncidentConfiguration(rootCauseIncident.getConfiguration());
						incident.setRootCauseIncidentMessage(rootCauseIncident.getIncidentMessage());
					}
				} catch (RuntimeException e) {
					log.warn("Failed to enrich historic incident with ID: {} and root cause ID: {}", incident.getId(),
							incident.getRootCauseIncidentId(), e);
				}
			}
			incidents.add(incident);
		}
		return incidents;
	}

	@Override
	public String findExternalTaskErrorDetails(String externalTaskId, CIBUser user) {
		try {
			return directProviderUtil.getProcessEngine(user).getExternalTaskService().getExternalTaskErrorDetails(externalTaskId);
		} catch (NotFoundException e) {
			throw new NoObjectFoundException(new SystemException("External task with id " + externalTaskId + " does not exist", e));
		}
	}

	@Override
	public String findHistoricStacktraceByJobId(String jobId, CIBUser user) {
		try {
			String stacktrace = directProviderUtil.getProcessEngine(user).getHistoryService().getHistoricJobLogExceptionStacktrace(jobId);
			return stacktrace;
		} catch (AuthorizationException e) {
			throw e;
		} catch (ProcessEngineException e) {
			throw new SystemException(e.getMessage());
		}
	}

	private List<Incident> fetchIncidents(String processDefinitionKey, String activityId,
			String processInstanceId, CIBUser user) {
		IncidentQueryDto queryDto = new IncidentQueryDto();
		queryDto.setActivityId(activityId);
		if (processDefinitionKey != null)
			queryDto.setProcessDefinitionKeyIn(new String[] { processDefinitionKey });
		queryDto.setProcessInstanceId(processInstanceId);

		IncidentQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));
		List<org.cibseven.bpm.engine.runtime.Incident> queryResult = QueryUtil.list(query, null, null);

		List<Incident> result = new ArrayList<>();
		for (org.cibseven.bpm.engine.runtime.Incident incident : queryResult) {
			IncidentDto dto = IncidentDto.fromIncident(incident);
			result.add(directProviderUtil.convertValue(dto, Incident.class, user));
		}
		return result;
	}

	private HistoricIncidentDto fetchHistoricIncidentById(String incidentId, CIBUser user, ObjectMapper objectMapper) {
		Map<String, Object> params = Map.of("incidentId", incidentId);
		HistoricIncidentQueryDto queryDto = objectMapper.convertValue(params, HistoricIncidentQueryDto.class);
		HistoricIncidentQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));

		List<HistoricIncident> queryResult = QueryUtil.list(query, null, 1);

		for (HistoricIncident historicIncident : queryResult) {
			HistoricIncidentDto dto = HistoricIncidentDto.fromHistoricIncident(historicIncident);
			return dto;
		}
		// Historic incident not found, return null
		return null;
	}
}
