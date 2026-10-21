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

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.FormService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.exception.NotFoundException;
import org.cibseven.bpm.engine.exception.NullValueException;
import org.cibseven.bpm.engine.form.StartFormData;
import org.cibseven.bpm.engine.history.HistoricActivityStatistics;
import org.cibseven.bpm.engine.history.HistoricActivityStatisticsPostQuery;
import org.cibseven.bpm.engine.history.HistoricActivityStatisticsQuery;
import org.cibseven.bpm.engine.history.HistoricProcessInstance;
import org.cibseven.bpm.engine.history.HistoricProcessInstanceQuery;
import org.cibseven.bpm.engine.impl.form.validator.FormFieldValidationException;
import org.cibseven.bpm.engine.impl.util.IoUtil;
import org.cibseven.bpm.engine.management.ActivityStatistics;
import org.cibseven.bpm.engine.management.ActivityStatisticsQuery;
import org.cibseven.bpm.engine.management.ProcessDefinitionStatistics;
import org.cibseven.bpm.engine.management.ProcessDefinitionStatisticsQuery;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.repository.ProcessDefinitionQuery;
import org.cibseven.bpm.engine.rest.dto.HistoryTimeToLiveDto;
import org.cibseven.bpm.engine.rest.dto.StatisticsResultDto;
import org.cibseven.bpm.engine.rest.dto.VariableValueDto;
import org.cibseven.bpm.engine.rest.dto.history.HistoricActivityStatisticsDto;
import org.cibseven.bpm.engine.rest.dto.history.HistoricActivityStatisticsPostQueryDto;
import org.cibseven.bpm.engine.rest.dto.history.HistoricProcessInstanceDto;
import org.cibseven.bpm.engine.rest.dto.history.HistoricProcessInstanceQueryDto;
import org.cibseven.bpm.engine.rest.dto.repository.ActivityStatisticsResultDto;
import org.cibseven.bpm.engine.rest.dto.repository.CalledProcessDefinitionDto;
import org.cibseven.bpm.engine.rest.dto.repository.ProcessDefinitionDiagramDto;
import org.cibseven.bpm.engine.rest.dto.repository.ProcessDefinitionDto;
import org.cibseven.bpm.engine.rest.dto.repository.ProcessDefinitionQueryDto;
import org.cibseven.bpm.engine.rest.dto.repository.ProcessDefinitionStatisticsResultDto;
import org.cibseven.bpm.engine.rest.dto.repository.ProcessDefinitionSuspensionStateDto;
import org.cibseven.bpm.engine.rest.dto.runtime.ProcessInstanceDto;
import org.cibseven.bpm.engine.rest.dto.runtime.ProcessInstanceQueryDto;
import org.cibseven.bpm.engine.rest.dto.runtime.ProcessInstanceSuspensionStateDto;
import org.cibseven.bpm.engine.rest.dto.runtime.ProcessInstanceWithVariablesDto;
import org.cibseven.bpm.engine.rest.dto.runtime.StartProcessInstanceDto;
import org.cibseven.bpm.engine.rest.dto.runtime.VariableInstanceQueryDto;
import org.cibseven.bpm.engine.rest.dto.runtime.modification.ProcessInstanceModificationInstructionDto;
import org.cibseven.bpm.engine.rest.dto.task.FormDto;
import org.cibseven.bpm.engine.rest.exception.RestException;
import org.cibseven.bpm.engine.rest.impl.history.HistoricActivityStatisticsQueryDto;
import org.cibseven.bpm.engine.rest.util.ApplicationContextPathUtil;
import org.cibseven.bpm.engine.rest.util.QueryUtil;
import org.cibseven.bpm.engine.runtime.ProcessInstanceQuery;
import org.cibseven.bpm.engine.runtime.ProcessInstanceWithVariables;
import org.cibseven.bpm.engine.runtime.ProcessInstantiationBuilder;
import org.cibseven.common.api.Data;
import org.cibseven.common.api.auth.CIBUser;
import org.cibseven.common.api.exception.ExpressionEvaluationException;
import org.cibseven.common.api.exception.NoObjectFoundException;
import org.cibseven.common.api.exception.SystemException;
import org.cibseven.common.api.exception.UnsupportedTypeException;
import org.cibseven.common.api.providers.IProcessProvider;
import org.cibseven.common.api.rest.model.HistoryProcessInstance;
import org.cibseven.common.api.rest.model.HistoryStatistics;
import org.cibseven.common.api.rest.model.Incident;
import org.cibseven.common.api.rest.model.Process;
import org.cibseven.common.api.rest.model.ProcessDiagram;
import org.cibseven.common.api.rest.model.ProcessInstance;
import org.cibseven.common.api.rest.model.ProcessStart;
import org.cibseven.common.api.rest.model.ProcessStatistics;
import org.cibseven.common.api.rest.model.StartForm;
import org.cibseven.common.api.rest.model.Variable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

public class DirectProcessProvider implements IProcessProvider {

	private static final String FETCH_INCIDENTS = "fetchIncidents";

	SevenDirectProvider sevenDirectProvider;
	DirectProviderUtil directProviderUtil;

	DirectProcessProvider(DirectProviderUtil directProviderUtil, SevenDirectProvider sevenDirectProvider){
		this.directProviderUtil = directProviderUtil;
		this.sevenDirectProvider = sevenDirectProvider;
	}

	@Override
	public Collection<Process> findProcesses(CIBUser user) {
		MultivaluedMap<String, String> queryParameters = new MultivaluedHashMap<>();
		// ProcessProvider adds: "?latestVersion=true&sortBy=name&sortOrder=desc";"
		queryParameters.add("latestVersion", "true");
		queryParameters.add("sortBy", "name");
		queryParameters.add("sortOrder", "desc");
		ProcessDefinitionQueryDto queryDto = new ProcessDefinitionQueryDto(directProviderUtil.getObjectMapper(user), queryParameters);

		ProcessDefinitionQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));
		List<ProcessDefinition> matchingDefinitions = QueryUtil.list(query, null, null);

		List<Process> processes = new ArrayList<>();
		for (ProcessDefinition definition : matchingDefinitions) {
			ProcessDefinitionDto def = ProcessDefinitionDto.fromProcessDefinition(definition);
			processes.add(directProviderUtil.convertValue(def, Process.class, user));
		}
		return processes;
	}

	@Override
	public Collection<Process> findProcessesWithInfo(CIBUser user) {
		Map<String, Object> queryParams = new HashMap<>();
		queryParams.put("failedJobs", true);
		queryParams.put("incidents", true);
		Collection<ProcessStatistics> statisticsCollection = getProcessStatistics(queryParams, user);
		// Group by key and tenant ID to consolidate different versions
		List<ProcessStatistics> groupedStatistics = sevenDirectProvider.getProcessProvider()
				.groupProcessStatisticsByKeyAndTenant(statisticsCollection);
		// Build Process objects directly from grouped ProcessStatistics
		return groupedStatistics.stream().map(stats -> {
			Process process = directProviderUtil.convertValue(stats.getDefinition(), Process.class, user);

			// Set aggregated statistics data
			process.setRunningInstances(stats.getInstances());
			// Calculate total incidents from all incident types
			long totalIncidents = stats.getIncidents() != null
					? stats.getIncidents().stream().mapToLong(incident -> incident.getIncidentCount()).sum()
					: 0L;
			process.setIncidents(totalIncidents);

			// Set default values for fields not available in statistics
			process.setAllInstances(stats.getInstances()); // Same as running instances for now
			process.setCompletedInstances(0L); // Would need separate call to get completed instances

			return process;
		}).collect(Collectors.toList());

	}

	@Override
	public List<ProcessStatistics> groupProcessStatisticsByKeyAndTenant(Collection<ProcessStatistics> processStatistics) {
		return groupProcessStatisticsByKeyAndTenantImpl(processStatistics);
	}

	public Collection<ProcessStatistics> getProcessStatistics(Map<String, Object> queryParams, CIBUser user) {
		ObjectMapper objectMapper = directProviderUtil.getObjectMapper(user);
		Boolean includeIncidents = getBooleanValueFromObject(queryParams.get("incidents"), objectMapper);

		String includeIncidentsForType = (String) queryParams.get("incidentsForType");
		Boolean includeRootIncidents = getBooleanValueFromObject(queryParams.get("rootIncidents"), objectMapper);

		Boolean includeFailedJobs = getBooleanValueFromObject(queryParams.get("failedJobs"), objectMapper);
		if (includeIncidents != null && includeIncidents.booleanValue() && includeIncidentsForType != null
				&& !includeIncidentsForType.isBlank()) {
			throw new SystemException(
					"Only one of the query parameter includeIncidents or includeIncidentsForType can be set.");
		}

		if (includeIncidents != null && includeIncidents.booleanValue() && includeRootIncidents != null
				&& includeRootIncidents.booleanValue()) {
			throw new SystemException("Only one of the query parameter includeIncidents or includeRootIncidents can be set.");
		}

		if (includeRootIncidents != null && includeRootIncidents.booleanValue() && includeIncidentsForType != null
				&& !includeIncidentsForType.isBlank()) {
			throw new SystemException(
					"Only one of the query parameter includeRootIncidents or includeIncidentsForType can be set.");
		}

		ProcessDefinitionStatisticsQuery query = directProviderUtil.getProcessEngine(user).getManagementService().createProcessDefinitionStatisticsQuery();

		if (includeFailedJobs != null && includeFailedJobs) {
			query.includeFailedJobs();
		}

		if (includeIncidents != null && includeIncidents) {
			query.includeIncidents();
		} else if (includeIncidentsForType != null) {
			query.includeIncidentsForType(includeIncidentsForType);
		} else if (includeRootIncidents != null && includeRootIncidents) {
			query.includeRootIncidents();
		}

		List<ProcessDefinitionStatistics> queryResults = query.unlimitedList();

		Collection<ProcessStatistics> processStatistics = new ArrayList<>();
		for (ProcessDefinitionStatistics queryResult : queryResults) {
			processStatistics.add(directProviderUtil.getObjectMapper(user).convertValue(
					ProcessDefinitionStatisticsResultDto.fromProcessDefinitionStatistics(queryResult), ProcessStatistics.class));
		}
		return processStatistics;
	}

	private Boolean getBooleanValueFromObject(Object value, ObjectMapper objectMapper) {
		return objectMapper.convertValue(value, Boolean.class);
	}

	@Override
	public Collection<Process> findProcessesWithFilters(String filters, CIBUser user) {
		Map<String, String> filterMap = new HashMap<>();
		String[] splitFilter = filters.split("&");
		for (String params : splitFilter) {
			String[] splitValue = params.split("=");
			if (splitValue.length > 1)
				filterMap.put(splitValue[0], URLDecoder.decode(splitValue[1], Charset.forName("UTF-8")));
		}
		ObjectMapper objectMapper = directProviderUtil.getObjectMapper(user);
		ProcessDefinitionQueryDto queryDto = objectMapper.convertValue(filterMap, ProcessDefinitionQueryDto.class);
		List<Process> processes = new ArrayList<>();
		ProcessEngine processEngine = directProviderUtil.getProcessEngine(user);
		ProcessDefinitionQuery query = queryDto.toQuery(processEngine);
		List<ProcessDefinition> matchingDefinitions = QueryUtil.list(query, null, null);

		for (ProcessDefinition definition : matchingDefinitions) {
			ProcessDefinitionDto def = ProcessDefinitionDto.fromProcessDefinition(definition);
			processes.add(directProviderUtil.convertValue(def, Process.class, user));
		}
		for (Process process : processes) {
			ProcessInstanceQueryDto processInstanceQueryDto = new ProcessInstanceQueryDto();
			queryDto.setObjectMapper(objectMapper);
			processInstanceQueryDto.setProcessDefinitionId(process.getId());
			process.setRunningInstances(processInstanceQueryDto.toQuery(processEngine).count());
		}
		return processes;
	}

	@Override
	public Process findProcessByDefinitionKey(String key, String tenantId, CIBUser user) {
		ProcessDefinitionQuery query = directProviderUtil.getProcessEngine(user).getRepositoryService().createProcessDefinitionQuery().processDefinitionKey(key)
				.latestVersion();
		if (tenantId != null)
			query.tenantIdIn(new String[] { tenantId });
		else
			query.withoutTenantId();
		ProcessDefinition instance = query.singleResult();
		if (instance == null) {
			if (tenantId != null)
				throw new SystemException("Process instance " + key + " not found with tenantId " + tenantId);
			else
				throw new SystemException("Process instance not found: " + key);
		}
		Process process = directProviderUtil.convertValue(ProcessDefinitionDto.fromProcessDefinition(instance), Process.class, user);
		return process;
	}

	@Override
	public Collection<Process> findProcessVersionsByDefinitionKey(String key, String tenantId, Optional<Boolean> lazyLoad,
			CIBUser user) {
		// returns same array but in different order
		ProcessDefinitionQueryDto queryDto = new ProcessDefinitionQueryDto();
		queryDto.setKey(key);
		queryDto.setSortBy("version");
		queryDto.setSortOrder("desc");
		if (tenantId != null)
			queryDto.setTenantIdIn(Arrays.asList(tenantId));
		else
			queryDto.setWithoutTenantId(true);
		ProcessDefinitionQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));
		List<ProcessDefinition> definitions = QueryUtil.list(query, null, null);
		List<Process> processes = new ArrayList<>();
		for (ProcessDefinition definition : definitions) {
			ProcessDefinitionDto def = ProcessDefinitionDto.fromProcessDefinition(definition);
			processes.add(directProviderUtil.convertValue(def, Process.class, user));
		}

		if (!lazyLoad.isPresent() || (lazyLoad.isPresent() && !lazyLoad.get())) {
			for (Process process : processes) {
				HistoricProcessInstanceQueryDto historicProcessInstanceQueryDto = new HistoricProcessInstanceQueryDto();
				historicProcessInstanceQueryDto.setProcessDefinitionId(process.getId());
				historicProcessInstanceQueryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
				HistoricProcessInstanceQuery historicProcessInstanceQuery = historicProcessInstanceQueryDto
						.toQuery(directProviderUtil.getProcessEngine(user));
				List<HistoricProcessInstance> matchingHistoricProcessInstances = historicProcessInstanceQuery.unlimitedList();

				process.setAllInstances(matchingHistoricProcessInstances.size());

				historicProcessInstanceQueryDto.setUnfinished(true);
				historicProcessInstanceQuery = historicProcessInstanceQueryDto.toQuery(directProviderUtil.getProcessEngine(user));
				matchingHistoricProcessInstances = historicProcessInstanceQuery.unlimitedList();

				process.setRunningInstances(matchingHistoricProcessInstances.size());

				historicProcessInstanceQueryDto.setUnfinished(false);
				historicProcessInstanceQueryDto.setCompleted(true);
				historicProcessInstanceQuery = historicProcessInstanceQueryDto.toQuery(directProviderUtil.getProcessEngine(user));
				matchingHistoricProcessInstances = historicProcessInstanceQuery.unlimitedList();

				process.setCompletedInstances(matchingHistoricProcessInstances.size());
			}
		}
		return processes;
	}

	@Override
	public Process findProcessById(String id, Optional<Boolean> extraInfo, CIBUser user) throws SystemException {
		ProcessDefinition definition;
		try {
			definition = directProviderUtil.getProcessEngine(user).getRepositoryService().getProcessDefinition(id);
		} catch (ProcessEngineException e) {
			throw new SystemException("No matching definition with id " + id, e);
		}

		ProcessDefinitionDto definitionDto = ProcessDefinitionDto.fromProcessDefinition(definition);
		Process process = directProviderUtil.convertValue(definitionDto, Process.class, user);
		if (extraInfo.isPresent() && extraInfo.get()) {
			Map<String, Object> filters = new HashMap<>();
			filters.put("processDefinitionId", id);
			Long count = countProcessesInstancesHistory(filters, user);
			process.setAllInstances(count);
			filters.clear();
			filters.put("processDefinitionId", process.getId());
			filters.put("unfinished", true);
			count = countProcessesInstancesHistory(filters, user);
			process.setRunningInstances(count);
			filters.clear();
			filters.put("processDefinitionId", process.getId());
			filters.put("completed", true);
			count = countProcessesInstancesHistory(filters, user);
			process.setCompletedInstances(count);
		}
		return process;
	}

	@Override
	public Collection<ProcessInstance> findProcessesInstances(String key, CIBUser user) {
		List<ProcessInstance> result = new ArrayList<>();
		List<org.cibseven.bpm.engine.runtime.ProcessInstance> instances = directProviderUtil.getProcessEngine(user).getRuntimeService().createProcessInstanceQuery()
				.processDefinitionKey(key).list();

		for (org.cibseven.bpm.engine.runtime.ProcessInstance instance : instances) {
			ProcessInstanceDto backendDto = ProcessInstanceDto.fromProcessInstance(instance);
			ProcessInstance webClientDto = directProviderUtil.convertValue(backendDto, ProcessInstance.class, user);
			result.add(webClientDto);
		}

		addWithIncidentsInfo(result, false, user);

		return result;
	}

	@Override
	public ProcessDiagram fetchDiagram(String id, CIBUser user) {
		InputStream processModelIn = null;
		try {
			processModelIn = directProviderUtil.getProcessEngine(user).getRepositoryService().getProcessModel(id);
			byte[] processModel = IoUtil.readInputStream(processModelIn, "processModelBpmn20Xml");
			return directProviderUtil.convertValue(ProcessDefinitionDiagramDto.create(id, new String(processModel, "UTF-8")),
					ProcessDiagram.class, user);
		} catch (AuthorizationException e) {
			throw e;
		} catch (NotFoundException e) {
			throw new SystemException("No matching definition with id " + id, e);
		} catch (UnsupportedEncodingException e) {
			throw new SystemException(e.getMessage(), e);
		} finally {
			IoUtil.closeSilently(processModelIn);
		}
	}

	@Override
	public StartForm fetchStartForm(String processDefinitionId, CIBUser user) {
		final StartFormData formData;
		try {
			formData = directProviderUtil.getProcessEngine(user).getFormService().getStartFormData(processDefinitionId);
		} catch (AuthorizationException e) {
			throw e;
		} catch (ProcessEngineException e) {
			throw new SystemException("Cannot get start form data for process definition " + processDefinitionId, e);
		}
		FormDto dto = FormDto.fromFormData(formData);
		if ((dto.getKey() == null || dto.getKey().isEmpty()) && dto.getCamundaFormRef() == null) {
			if (formData != null && formData.getFormFields() != null && !formData.getFormFields().isEmpty()) {
				dto.setKey("embedded:engine://engine/:engine/process-definition/" + processDefinitionId + "/rendered-form");
			}
		}
		dto.setContextPath(
				ApplicationContextPathUtil.getApplicationPathByProcessDefinitionId(directProviderUtil.getProcessEngine(user), processDefinitionId));
		return directProviderUtil.convertValue(dto, StartForm.class, user);
	}

	@Override
	public Data downloadBpmn(String id, String fileName, CIBUser user) {

		ProcessDiagram diagram = fetchDiagram(id, user);
		ByteArrayResource resource = new ByteArrayResource(diagram.getBpmn20Xml().getBytes());
		return new Data(fileName, "application/bpmn+xml", resource, resource.contentLength());
	}

	@Override
	public void suspendProcessInstance(String processInstanceId, Boolean suspend, CIBUser user) {
		ProcessInstanceSuspensionStateDto processInstanceSuspensionStateDto = new ProcessInstanceSuspensionStateDto();
		processInstanceSuspensionStateDto.setProcessInstanceIds(Arrays.asList(processInstanceId));
		processInstanceSuspensionStateDto.setSuspended(suspend);
		processInstanceSuspensionStateDto.updateSuspensionState(directProviderUtil.getProcessEngine(user));
	}

	@Override
	public void deleteProcessInstance(String processInstanceId, CIBUser user) {
		directProviderUtil.getProcessEngine(user).getRuntimeService().deleteProcessInstance(processInstanceId, null, false, true);
	}

	@Override
	public void suspendProcessDefinition(String processDefinitionId, Boolean suspend, Boolean includeProcessInstances,
			String executionDate, CIBUser user) {

		ProcessDefinitionSuspensionStateDto dto = new ProcessDefinitionSuspensionStateDto();
		dto.setProcessDefinitionId(processDefinitionId);
		dto.setSuspended(suspend);
		dto.setIncludeProcessInstances(includeProcessInstances);
		if (executionDate != null)
			dto.setExecutionDate(executionDate);
		try {
			dto.updateSuspensionState(directProviderUtil.getProcessEngine(user));

		} catch (IllegalArgumentException e) {
			String message = String.format("Could not update the suspension state of Process Definitions due to: %s",
					e.getMessage());
			throw new SystemException(message, e);
		}
	}

	@Override
	public ProcessStart startProcess(String processDefinitionKey, String tenantId, Map<String, Object> data, CIBUser user)
			throws SystemException, UnsupportedTypeException, ExpressionEvaluationException {
		ProcessDefinitionQuery processDefinitionQuery = directProviderUtil.getProcessEngine(user).getRepositoryService().createProcessDefinitionQuery()
				.processDefinitionKey(processDefinitionKey);
		if (tenantId != null)
			processDefinitionQuery.tenantIdIn(tenantId);
		else
			processDefinitionQuery.withoutTenantId();
		ProcessDefinition processDefinition = processDefinitionQuery.latestVersion().singleResult();

		if (processDefinition == null) {
			String errorMessage = tenantId != null
					? String.format("No matching process definition with key: %s and tenant-id: %s", processDefinitionKey, tenantId)
					: String.format("No matching process definition with key: %s and no tenant-id", processDefinitionKey);

			throw new SystemException(errorMessage);

		} else {
			// start the process
			ProcessInstanceWithVariables processInstanceWithVariables = null;
			// the simple case contains the _locale variable, only
			StartProcessInstanceDto startProcessInstanceDto = directProviderUtil.getObjectMapper(user).convertValue(data, StartProcessInstanceDto.class);
			try {
				processInstanceWithVariables = startProcessInstanceAtActivities(startProcessInstanceDto,
						processDefinition.getId(), user);
			} catch (AuthorizationException e) {
				throw e;

			} catch (ProcessEngineException e) {
				String errorMessage = String.format("Cannot instantiate process definition %s: %s", processDefinition.getId(),
						e.getMessage());
				throw new ExpressionEvaluationException(new UnsupportedTypeException(new RuntimeException(errorMessage, e)));
			}

			ProcessInstanceDto result;
			if (startProcessInstanceDto.isWithVariablesInReturn()) {
				result = ProcessInstanceWithVariablesDto.fromProcessInstance(processInstanceWithVariables);
			} else {
				result = ProcessInstanceDto.fromProcessInstance(processInstanceWithVariables);
			}

			ProcessStart processStart = directProviderUtil.convertValue(result, ProcessStart.class, user);
			return processStart;
		}

	}

	@Override
	public ProcessStart submitForm(String processDefinitionKey, String tenantId, Map<String, Object> data, CIBUser user)
			throws SystemException, UnsupportedTypeException, ExpressionEvaluationException {
		ProcessDefinitionQuery query = directProviderUtil.getProcessEngine(user).getRepositoryService().createProcessDefinitionQuery()
				.processDefinitionKey(processDefinitionKey);
		if (tenantId != null)
			query.tenantIdIn(tenantId);
		else
			query.withoutTenantId();
		ProcessDefinition processDefinition = query.latestVersion().singleResult();

		if (processDefinition == null) {
			String errorMessage = String.format("No matching process definition with key: %s and tenant-id: %s",
					processDefinitionKey, tenantId);
			throw new SystemException(errorMessage);
		} else {
			StartProcessInstanceDto parameters = directProviderUtil.getObjectMapper(user).convertValue(data, StartProcessInstanceDto.class);
			org.cibseven.bpm.engine.runtime.ProcessInstance instance = null;
			try {
				Map<String, Object> variables = VariableValueDto.toMap(parameters.getVariables(), directProviderUtil.getProcessEngine(user), directProviderUtil.getObjectMapper(user));
				String businessKey = parameters.getBusinessKey();
				if (businessKey != null) {
					instance = directProviderUtil.getProcessEngine(user).getFormService().submitStartForm(processDefinition.getId(), businessKey, variables);
				} else {
					instance = directProviderUtil.getProcessEngine(user).getFormService().submitStartForm(processDefinition.getId(), variables);
				}

			} catch (AuthorizationException e) {
				throw e;

			} catch (ProcessEngineException e) {
				String errorMessage = String.format("Cannot instantiate process definition %s: %s", processDefinition.getId(),
						e.getMessage());
				throw new ExpressionEvaluationException(new UnsupportedTypeException(new SystemException(errorMessage, e)));
			}

			ProcessInstanceDto result = ProcessInstanceDto.fromProcessInstance(instance);

			return directProviderUtil.getObjectMapper(user).convertValue(result, ProcessStart.class);

		}
	}

	@Override
	public Collection<ProcessStatistics> findProcessStatistics(String processId, CIBUser user)
			throws SystemException, UnsupportedTypeException, ExpressionEvaluationException {
		ActivityStatisticsQuery query = directProviderUtil.getProcessEngine(user).getManagementService().createActivityStatisticsQuery(processId);
		List<ActivityStatistics> queryResults = query.unlimitedList();

		Collection<ProcessStatistics> processStatistics = new ArrayList<>();
		for (ActivityStatistics queryResult : queryResults) {
			StatisticsResultDto dto = ActivityStatisticsResultDto.fromActivityStatistics(queryResult);
			processStatistics.add(directProviderUtil.getObjectMapper(user).convertValue(dto, ProcessStatistics.class));
		}
		return processStatistics;
	}

	@Override
	public Collection<HistoryProcessInstance> findProcessesInstancesHistory(Map<String, Object> filters,
			Optional<Integer> firstResult, Optional<Integer> maxResults, CIBUser user) {
		Boolean fetchIncidents = (Boolean) filters.get(FETCH_INCIDENTS);
		if (fetchIncidents != null) {
			filters.remove(FETCH_INCIDENTS);
		}
		HistoricProcessInstanceQueryDto historicProcessInstanceQueryDto = directProviderUtil.getObjectMapper(user).convertValue(filters,
				HistoricProcessInstanceQueryDto.class);

		historicProcessInstanceQueryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
		HistoricProcessInstanceQuery query = historicProcessInstanceQueryDto.toQuery(directProviderUtil.getProcessEngine(user));

		List<HistoricProcessInstance> matchingHistoricProcessInstances = QueryUtil.list(query,
				firstResult.isPresent() ? firstResult.get() : null, maxResults.isPresent() ? maxResults.get() : null);

		List<HistoryProcessInstance> historicProcessInstanceResults = new ArrayList<HistoryProcessInstance>();
		for (HistoricProcessInstance historicProcessInstance : matchingHistoricProcessInstances) {
			HistoricProcessInstanceDto resultHistoricProcessInstanceDto = HistoricProcessInstanceDto
					.fromHistoricProcessInstance(historicProcessInstance);
			historicProcessInstanceResults.add(directProviderUtil.convertValue(resultHistoricProcessInstanceDto, HistoryProcessInstance.class, user));
		}
		// Check if caller wants incident handling
		if (fetchIncidents != null && fetchIncidents) {
			String processDefinitionId = (String) filters.get("processDefinitionId");
			if (processDefinitionId == null) {
				// No processDefinitionId - fetch incidents per instance (e.g. called from findProcessesInstancesRuntime)
				if (historicProcessInstanceResults != null) {
					historicProcessInstanceResults.forEach(p -> {
						p.setIncidents(sevenDirectProvider.findIncidentByInstanceId(p.getId(), user));
					});
				}
			} else {
				@SuppressWarnings("unchecked")
				List<String> activityIdIn = (List<String>) filters.get("activeActivityIdIn");

				// Handle case where no processes found with activity filter - fallback
				// to incident-based search
				if ((historicProcessInstanceResults == null || historicProcessInstanceResults.isEmpty()) && activityIdIn != null
						&& !activityIdIn.isEmpty()) {
					String activityId = activityIdIn.get(0);
					Collection<Incident> incidents = sevenDirectProvider.fetchIncidentsByInstanceAndActivityId(processDefinitionId, activityId, user);

					if (incidents != null && !incidents.isEmpty()) {
						Map<String, List<Incident>> incidentsByProcessInstance = incidents.stream()
								.collect(Collectors.groupingBy(Incident::getProcessInstanceId));

						Set<String> processInstanceIds = incidentsByProcessInstance.keySet();

						// Create new query for process instances with incidents
						Map<String, Object> dataIdIn = new HashMap<>(filters);
						dataIdIn.put("processInstanceIdIn", processInstanceIds);
						dataIdIn.remove("activeActivityIdIn"); // Remove activity filter for
																										// fallback search

						historicProcessInstanceResults = (List<HistoryProcessInstance>) findProcessesInstancesHistory(dataIdIn,
								Optional.ofNullable(null), Optional.ofNullable(null), user);

						// Associate incidents with process instances
						historicProcessInstanceResults.forEach(
								p -> p.setIncidents(incidentsByProcessInstance.getOrDefault(p.getId(), Collections.emptyList())));
					}
				} else if (historicProcessInstanceResults != null) {
					// For regular queries, fetch incidents for all returned processes
					historicProcessInstanceResults.forEach(p -> {
						p.setIncidents(sevenDirectProvider.findIncidentByInstanceId(p.getId(), user));
					});
				}
			}
		}
		return historicProcessInstanceResults;

	}

	@Override
	public Collection<HistoryProcessInstance> findProcessesInstancesHistory(String key, Optional<Boolean> active,
			Integer firstResult, Integer maxResults, CIBUser user) {
		HistoricProcessInstanceQueryDto historicProcessInstanceQueryDto = new HistoricProcessInstanceQueryDto();
		// historicProcessInstanceQueryDto.setProcessDefinitionId(id);
		historicProcessInstanceQueryDto.setProcessDefinitionKey(key);
		if (active.isPresent())
			historicProcessInstanceQueryDto.setActive(active.get());
		return queryHistoryProcessInstances(historicProcessInstanceQueryDto, firstResult, maxResults, user);
	}

	@Override
	public Collection<HistoryProcessInstance> findProcessesInstancesHistoryById(String id, Optional<String> activityId,
			Optional<Boolean> active, Integer firstResult, Integer maxResults, String text, CIBUser user) {
		HistoricProcessInstanceQueryDto historicProcessInstanceQueryDto = new HistoricProcessInstanceQueryDto();
		historicProcessInstanceQueryDto.setProcessDefinitionId(id);
		if (activityId.isPresent())
			historicProcessInstanceQueryDto.setActivityIdIn(Arrays.asList(activityId.get()));
		if (active.isPresent())
			historicProcessInstanceQueryDto.setActive(active.get());
		if (text != "") {
			List<HistoricProcessInstanceQueryDto> orQueries = new ArrayList<>();
			HistoricProcessInstanceQueryDto orQuery = new HistoricProcessInstanceQueryDto();
			orQuery.setProcessInstanceBusinessKeyLike("*" + text + "*");
			orQuery.setProcessInstanceId(text);
			orQueries.add(orQuery);
			historicProcessInstanceQueryDto.setOrQueries(orQueries);
		}

		return queryHistoryProcessInstances(historicProcessInstanceQueryDto, firstResult, maxResults, user);
	}

	@Override
	public Long countProcessesInstancesHistory(Map<String, Object> filters, CIBUser user) {
		HistoricProcessInstanceQueryDto historicProcessInstanceQueryDto = directProviderUtil.getObjectMapper(user).convertValue(filters,
				HistoricProcessInstanceQueryDto.class);
		historicProcessInstanceQueryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
		HistoricProcessInstanceQuery query = historicProcessInstanceQueryDto.toQuery(directProviderUtil.getProcessEngine(user));

		long count = query.count();
		return count;
	}

	@Override
	public ProcessInstance findProcessInstance(String processInstanceId, CIBUser user) {
		org.cibseven.bpm.engine.runtime.ProcessInstance instance = directProviderUtil.getProcessEngine(user).getRuntimeService().createProcessInstanceQuery()
				.processInstanceId(processInstanceId).singleResult();
		if (instance == null) {
			throw new NoObjectFoundException(new SystemException("Process instance with id " + processInstanceId + " does not exist"));
		}

		ProcessInstanceDto result = ProcessInstanceDto.fromProcessInstance(instance);
		ProcessInstance webClientDto = directProviderUtil.convertValue(result, ProcessInstance.class, user);

		addWithIncidentsInfo(List.of(webClientDto), false, user);

		return webClientDto;
	}

	@Override
	public Variable fetchProcessInstanceVariable(String processInstanceId, String variableName, boolean deserializeValue,
			CIBUser user) throws SystemException {
		VariableInstanceQueryDto queryDto = new VariableInstanceQueryDto();
		queryDto.setProcessInstanceIdIn(new String[] { processInstanceId });
		queryDto.setVariableName(variableName);
		queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));

		List<Variable> variablesDeserialized = directProviderUtil.queryVariableInstances(queryDto, null, null, true, user);
		List<Variable> variablesSerialized = directProviderUtil.queryVariableInstances(queryDto, null, null, false, user);
		Variable variableDeserialized = variablesDeserialized.size() == 1 ? variablesDeserialized.get(0) : null;
		Variable variableSerialized = variablesSerialized.size() == 1 ? variablesSerialized.get(0) : null;
		if (variableDeserialized == null || variableSerialized == null)
			throw new SystemException("Variable " + variableName + " not found in process instance " + processInstanceId);
		if (deserializeValue) {
			variableDeserialized.setValueSerialized(variableSerialized.getValue());
			variableDeserialized.setValueDeserialized(variableDeserialized.getValue());
			return variableDeserialized;
		} else {
			variableSerialized.setValueSerialized(variableSerialized.getValue());
			variableSerialized.setValueDeserialized(variableDeserialized.getValue());
			return variableSerialized;
		}

	}

	@Override
	public HistoryProcessInstance findHistoryProcessInstanceHistory(String processInstanceId, CIBUser user) {
		HistoricProcessInstance instance = directProviderUtil.getProcessEngine(user).getHistoryService().createHistoricProcessInstanceQuery()
				.processInstanceId(processInstanceId).singleResult();
		if (instance == null) {
			throw new NoObjectFoundException(new SystemException("Historic process instance with id " + processInstanceId + " does not exist"));
		}

		HistoryProcessInstance historyProcessInstance = directProviderUtil.convertValue(
				HistoricProcessInstanceDto.fromHistoricProcessInstance(instance), HistoryProcessInstance.class, user);
		;
		return historyProcessInstance;
	}

	@Override
	public Collection<Process> findCalledProcessDefinitions(String processDefinitionId, CIBUser user) {
		try {
			List<Process> calledProcessDefinitionDtos = directProviderUtil.getProcessEngine(user).getRepositoryService()
					.getStaticCalledProcessDefinitions(processDefinitionId).stream().map(CalledProcessDefinitionDto::from)
					.map(DirectProcessProvider::convertToProcess).collect(Collectors.toList());
			return calledProcessDefinitionDtos;
		} catch (NotFoundException e) {
			throw new SystemException(e.getMessage());
		}

	}

	public static Process convertToProcess(CalledProcessDefinitionDto dto) {
		ObjectMapper objectMapper = new ObjectMapper();
		Map<String, Object> filterDtoMap = objectMapper.convertValue(dto, new TypeReference<Map<String, Object>>() {
		});
		return objectMapper.convertValue(filterDtoMap, Process.class);
	}

	@Override
	public ResponseEntity<byte[]> getDeployedStartForm(String processDefinitionId, CIBUser user) {
		try {
			InputStream deployedStartForm = directProviderUtil.getProcessEngine(user).getFormService().getDeployedStartForm(processDefinitionId);
			byte[] bytes = IOUtils.toByteArray(deployedStartForm);
			return ResponseEntity.ok(bytes);
		} catch (NotFoundException e) {
			throw new SystemException(e.getMessage());
		} catch (NullValueException e) {
			throw new SystemException(e.getMessage());
		} catch (AuthorizationException e) {
			throw new SystemException(e.getMessage());
		} catch (IOException e) {
			throw new SystemException(e.getMessage());
		}
	}

	@Override
	public void updateHistoryTimeToLive(String id, Map<String, Object> data, CIBUser user) {
		HistoryTimeToLiveDto historyTimeToLiveDto = directProviderUtil.getObjectMapper(user).convertValue(data, HistoryTimeToLiveDto.class);
		directProviderUtil.getProcessEngine(user).getRepositoryService().updateProcessDefinitionHistoryTimeToLive(id, historyTimeToLiveDto.getHistoryTimeToLive());
	}

	@Override
	public void deleteProcessInstanceFromHistory(String id, CIBUser user) {
		try {
			directProviderUtil.getProcessEngine(user).getHistoryService().deleteHistoricProcessInstance(id);
		} catch (BadUserRequestException e) {
			throw new SystemException(e.getMessage());
		}
	}

	@Override
	public void deleteProcessDefinition(String id, Optional<Boolean> cascade, CIBUser user) {
		boolean cascadeVal = cascade.orElse(true);
		try {
			directProviderUtil.getProcessEngine(user).getRepositoryService().deleteProcessDefinition(id, cascadeVal);
		} catch (NotFoundException nfe) {
			throw new SystemException(nfe.getMessage(), nfe);
		}
	}

	@Override
	public Collection<ProcessInstance> findCurrentProcessesInstances(Map<String, Object> data, Optional<Integer> firstResult, Optional<Integer> maxResults, CIBUser user)
			throws SystemException {
		ProcessInstanceQueryDto queryDto = directProviderUtil.getObjectMapper(user).convertValue(data, ProcessInstanceQueryDto.class);
		queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
		ProcessInstanceQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));

		List<org.cibseven.bpm.engine.runtime.ProcessInstance> matchingInstances = QueryUtil.list(query, firstResult.orElse(null), maxResults.orElse(null));

		List<ProcessInstance> instanceResults = new ArrayList<>();
		for (org.cibseven.bpm.engine.runtime.ProcessInstance instance : matchingInstances) {
			ProcessInstanceDto resultInstance = ProcessInstanceDto.fromProcessInstance(instance);
			instanceResults.add(directProviderUtil.convertValue(resultInstance, ProcessInstance.class, user));
		}

		boolean alreadyAllWithIncident = Boolean.TRUE.equals(data.get("withIncident"));
		addWithIncidentsInfo(instanceResults, alreadyAllWithIncident, user);

		return instanceResults;
	}

	private void addWithIncidentsInfo(Collection<ProcessInstance> result, boolean alreadyAllWithIncident, CIBUser user) {
		if (result.isEmpty()) {
			return;
		}

		if (alreadyAllWithIncident) {
			result.forEach(i -> i.setWithIncident(Boolean.TRUE));
			return;
		}

		result.forEach(i -> i.setWithIncident(Boolean.FALSE));

		Set<String> processInstanceIds = result.stream().map(ProcessInstance::getId).collect(Collectors.toSet());
		List<org.cibseven.bpm.engine.runtime.ProcessInstance> instancesWithIncident = directProviderUtil.getProcessEngine(user).getRuntimeService()
				.createProcessInstanceQuery()
				.processInstanceIds(processInstanceIds)
				.withIncident()
				.list();

		Set<String> idsWithIncident = instancesWithIncident.stream()
				.map(org.cibseven.bpm.engine.runtime.ProcessInstance::getId).collect(Collectors.toSet());
		result.forEach(i -> {
			if (idsWithIncident.contains(i.getId())) {
				i.setWithIncident(Boolean.TRUE);
			}
		});
	}

	@Override
	public Collection<HistoryStatistics> fetchHistoricActivityStatistics(String id, Map<String, Object> params, CIBUser user) {
		MultivaluedMap<String, String> queryParams = new MultivaluedHashMap<>();
		for (String key : params.keySet()) {
			queryParams.put(key, Arrays.asList((String) params.get(key)));
		}

		HistoricActivityStatisticsQueryDto historicActivityStatisticsQueryDto = new HistoricActivityStatisticsQueryDto(
				directProviderUtil.getObjectMapper(user), id, queryParams);
		HistoricActivityStatisticsQuery query = historicActivityStatisticsQueryDto.toQuery(directProviderUtil.getProcessEngine(user));
		List<HistoryStatistics> result = new ArrayList<>();
		List<HistoricActivityStatistics> statistics = query.unlimitedList();
		for (HistoricActivityStatistics currentStatistics : statistics) {
			result.add(directProviderUtil.convertValue(HistoricActivityStatisticsDto.fromHistoricActivityStatistics(currentStatistics), HistoryStatistics.class, user));
		}
		return result;
	}

	@Override
	public Collection<HistoryStatistics> findHistoricActivityStatistics(String id, Map<String, Object> filters, CIBUser user)
			throws SystemException {
		if (id == null || id.isEmpty()) {
        	throw new SystemException("processDefinitionId is required");
		}
		HistoricActivityStatisticsPostQueryDto queryDto = directProviderUtil.getObjectMapper(user)
				.convertValue(filters, HistoricActivityStatisticsPostQueryDto.class);
		queryDto.setProcessDefinitionId(id);
		queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));

		HistoricActivityStatisticsPostQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));
		List<HistoricActivityStatistics> matchingStatistics = query.list();
		List<HistoryStatistics> result = new ArrayList<>();
		for (HistoricActivityStatistics statistic : matchingStatistics) {
			HistoricActivityStatisticsDto activityResult = HistoricActivityStatisticsDto.fromHistoricActivityStatistics(statistic);
			result.add(directProviderUtil.convertValue(activityResult, HistoryStatistics.class, user));
		}
		return result;
	}

	private ProcessInstanceWithVariables startProcessInstanceAtActivities(StartProcessInstanceDto dto,
			String processDefinitionKey, CIBUser user) {
		ObjectMapper objectMapper = directProviderUtil.getObjectMapper(user);
		Map<String, Object> processInstanceVariables = VariableValueDto.toMap(dto.getVariables(), directProviderUtil.getProcessEngine(user),
				objectMapper);
		String businessKey = dto.getBusinessKey();
		String caseInstanceId = dto.getCaseInstanceId();

		ProcessInstantiationBuilder instantiationBuilder = directProviderUtil.getProcessEngine(user).getRuntimeService().createProcessInstanceById(processDefinitionKey)
				.businessKey(businessKey).caseInstanceId(caseInstanceId).setVariables(processInstanceVariables);

		if (dto.getStartInstructions() != null && !dto.getStartInstructions().isEmpty()) {
			for (ProcessInstanceModificationInstructionDto instruction : dto.getStartInstructions()) {
				instruction.applyTo(instantiationBuilder, directProviderUtil.getProcessEngine(user), objectMapper);
			}
		}
		return instantiationBuilder.executeWithVariablesInReturn(dto.isSkipCustomListeners(), dto.isSkipIoMappings());
	}

	private Collection<HistoryProcessInstance> queryHistoryProcessInstances(
			HistoricProcessInstanceQueryDto historicProcessInstanceQueryDto, Integer firstResult, Integer maxResults, CIBUser user) {
		historicProcessInstanceQueryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));

		HistoricProcessInstanceQuery query = historicProcessInstanceQueryDto.toQuery(directProviderUtil.getProcessEngine(user));
		List<HistoricProcessInstance> matchingHistoricProcessInstances = QueryUtil.list(query, firstResult, maxResults);

		List<HistoryProcessInstance> HistoryProcessInstanceResults = new ArrayList<HistoryProcessInstance>();
		for (HistoricProcessInstance historicProcessInstance : matchingHistoricProcessInstances) {
			HistoricProcessInstanceDto resultHistoricProcessInstanceDto = HistoricProcessInstanceDto
					.fromHistoricProcessInstance(historicProcessInstance);
			HistoryProcessInstanceResults.add(directProviderUtil.convertValue(resultHistoricProcessInstanceDto, HistoryProcessInstance.class, user));
		}
		return HistoryProcessInstanceResults;
	}

	@Override
	public ProcessStart submitForm(String processDefinitionId, String formResult, CIBUser user)
			throws SystemException, UnsupportedTypeException, ExpressionEvaluationException {
		ObjectMapper objectMapper = directProviderUtil.getObjectMapper(user);
		StartProcessInstanceDto parameters;
		try {
			parameters = objectMapper.readValue(formResult, StartProcessInstanceDto.class);
		} catch (JsonProcessingException e) {
			throw new SystemException(e.getMessage(), e);
		}
		FormService formService = directProviderUtil.getProcessEngine(user).getFormService();

		org.cibseven.bpm.engine.runtime.ProcessInstance instance = null;
		try {
			Map<String, Object> variables = VariableValueDto.toMap(parameters.getVariables(), directProviderUtil.getProcessEngine(user), objectMapper);
			String businessKey = parameters.getBusinessKey();
			if (businessKey != null) {
				instance = formService.submitStartForm(processDefinitionId, businessKey, variables);
			} else {
				instance = formService.submitStartForm(processDefinitionId, variables);
			}
		} catch (AuthorizationException e) {
			throw e;
		} catch (ProcessEngineException|RestException e) {
			String errorMessage = String.format("Cannot instantiate process definition %s: %s", processDefinitionId, e.getMessage());
			throw new SystemException(errorMessage, e);
		}

    ProcessInstanceDto result = ProcessInstanceDto.fromProcessInstance(instance);
    return  directProviderUtil.convertValue(result, ProcessStart.class, user);
	}

	@Override
	public ResponseEntity<String> getRenderedForm(String processDefinitionId, Map<String, Object> params, CIBUser user) {
    FormService formService = directProviderUtil.getProcessEngine(user).getFormService();

		Object startForm = formService.getRenderedStartForm(processDefinitionId);
		if (startForm != null) {
			// the engine renders the form in memory, so no byte round trip is needed; re-encoding it
			// through the JVM default charset garbled non-ASCII text (CIB7-2204)
			return ResponseEntity.ok(startForm.toString());
	}

		throw new SystemException("No matching rendered start form for process definition with the id " + processDefinitionId + " found.");
	}

	@Override
	public Collection<HistoryProcessInstance> findProcessesInstancesRuntime(Map<String, Object> data, 
			Optional<Integer> firstResult, Optional<Integer> maxResults, CIBUser user) {
		// the two-call approach (runtime → history) is fundamentally the right design
		// since "/process-instance" has runtime-specific filters not available in the history API.

		ProcessInstanceQueryDto queryDto = directProviderUtil.getObjectMapper(user).convertValue(data, ProcessInstanceQueryDto.class);
		queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
		ProcessInstanceQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));

		List<org.cibseven.bpm.engine.runtime.ProcessInstance> matchingInstances = QueryUtil.list(query, firstResult.orElse(null), maxResults.orElse(null));

		List<ProcessInstance> instanceResults = new ArrayList<>();
		for (org.cibseven.bpm.engine.runtime.ProcessInstance instance : matchingInstances) {
			ProcessInstanceDto resultInstance = ProcessInstanceDto.fromProcessInstance(instance);
			instanceResults.add(directProviderUtil.convertValue(resultInstance, ProcessInstance.class, user));
		}
		if (instanceResults.isEmpty()) {
			return Collections.emptyList();
		}

		// Note: Between the two calls, a runtime instance could complete.
		// The history call will still find it (history is permanent),
		// but state in the result may be COMPLETED instead of ACTIVE.
		// This is usually acceptable but worth being aware of.

		// fetch history for those ids to get full info
		Map<String, Object> dataHistory = new HashMap<>();
		List<String> processInstanceIds = instanceResults.stream().map(ProcessInstance::getId).collect(Collectors.toList());
		dataHistory.put("processInstanceIds", processInstanceIds);
		dataHistory.put(FETCH_INCIDENTS, Boolean.TRUE);

		Integer firstResult0 = 0;
		Collection<HistoryProcessInstance> historicInstances = findProcessesInstancesHistory(dataHistory, Optional.of(firstResult0), maxResults, user);
		// sort [historicInstances] like they are inside [processInstanceIds]
		historicInstances = historicInstances.stream()
				.sorted(Comparator.comparingInt(h -> processInstanceIds.indexOf(h.getId())))
				.collect(Collectors.toList());

		return historicInstances;
	}

	@Override
	public Long countProcessesInstancesRuntime(Map<String, Object> filters, CIBUser user) {
		ProcessInstanceQueryDto queryDto = directProviderUtil.getObjectMapper(user).convertValue(filters, ProcessInstanceQueryDto.class);
		queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
		ProcessInstanceQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));
    return query.count();
	}

}
