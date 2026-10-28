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
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Map.Entry;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.EntityTypes;
import org.cibseven.bpm.engine.FormService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.exception.NotFoundException;
import org.cibseven.bpm.engine.exception.NullValueException;
import org.cibseven.bpm.engine.filter.Filter;
import org.cibseven.bpm.engine.form.CamundaFormRef;
import org.cibseven.bpm.engine.form.FormData;
import org.cibseven.bpm.engine.history.HistoricTaskInstance;
import org.cibseven.bpm.engine.history.HistoricTaskInstanceQuery;
import org.cibseven.bpm.engine.impl.util.IoUtil;
import org.cibseven.bpm.engine.query.Query;
import org.cibseven.bpm.engine.rest.dto.AbstractQueryDto;
import org.cibseven.bpm.engine.rest.dto.VariableValueDto;
import org.cibseven.bpm.engine.rest.dto.converter.DelegationStateConverter;
import org.cibseven.bpm.engine.rest.dto.converter.StringListConverter;
import org.cibseven.bpm.engine.rest.dto.history.HistoricTaskInstanceDto;
import org.cibseven.bpm.engine.rest.dto.history.HistoricTaskInstanceQueryDto;
import org.cibseven.bpm.engine.rest.dto.runtime.VariableInstanceDto;
import org.cibseven.bpm.engine.rest.dto.runtime.VariableInstanceQueryDto;
import org.cibseven.bpm.engine.rest.dto.task.CompleteTaskDto;
import org.cibseven.bpm.engine.rest.dto.task.FormDto;
import org.cibseven.bpm.engine.rest.dto.task.TaskBpmnErrorDto;
import org.cibseven.bpm.engine.rest.dto.task.TaskCountByCandidateGroupResultDto;
import org.cibseven.bpm.engine.rest.dto.task.TaskEscalationDto;
import org.cibseven.bpm.engine.rest.dto.task.TaskDto;
import org.cibseven.bpm.engine.rest.dto.task.TaskQueryDto;
import org.cibseven.bpm.engine.rest.dto.task.TaskWithAttachmentAndCommentDto;
import org.cibseven.bpm.engine.rest.exception.RestException;
import org.cibseven.bpm.engine.rest.util.ApplicationContextPathUtil;
import org.cibseven.bpm.engine.rest.util.QueryUtil;
import org.cibseven.bpm.engine.runtime.VariableInstanceQuery;
import org.cibseven.bpm.engine.task.Comment;
import org.cibseven.bpm.engine.task.DelegationState;
import org.cibseven.bpm.engine.task.TaskCountByCandidateGroupResult;
import org.cibseven.bpm.engine.task.TaskQuery;
import org.cibseven.bpm.engine.variable.VariableMap;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.NoObjectFoundException;
import org.cibseven.webapp.exception.SubmitDeniedException;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.CamundaForm;
import org.cibseven.webapp.rest.model.CandidateGroupTaskCount;
import org.cibseven.webapp.rest.model.IdentityLink;
import org.cibseven.webapp.rest.model.Task;
import org.cibseven.webapp.rest.model.TaskFiltering;
import org.cibseven.webapp.rest.model.TaskForm;
import org.cibseven.webapp.rest.model.TaskHistory;
import org.cibseven.webapp.rest.model.Variable;
import org.cibseven.webapp.rest.model.VariableInstance;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;

@Slf4j
public class DirectTaskProvider implements ITaskProvider {

	DirectProviderUtil directProviderUtil;
	public DirectTaskProvider(DirectProviderUtil directProviderUtil){
		this.directProviderUtil = directProviderUtil;
	}

	@Override
	public Collection<Task> findTasks(String filter, CIBUser user) {
		Map<String, Object> filters = new HashMap<>();
		String[] splitFilter = filter.split("&");
		for (String params : splitFilter) {
			String[] splitValue = params.split("=");
			if (splitValue.length > 1)
				filters.put(splitValue[0], URLDecoder.decode(splitValue[1], Charset.forName("UTF-8")));
		}
		return convertTasks(queryTasks(filters, user), user);
	}

	@Override
	public Integer findTasksCount(@RequestBody Map<String, Object> filters, CIBUser user) {
		return queryTasks(filters, user).size();
	}

	@Override
	public Collection<Task> findTasksByProcessInstance(String processInstanceId, CIBUser user) {
		TaskQuery taskQuery = directProviderUtil.getProcessEngine(user).getTaskService().createTaskQuery().processInstanceId(processInstanceId);
		List<org.cibseven.bpm.engine.task.Task> resultList = taskQuery.initializeFormKeys().list();
		return convertTasks(resultList, user);
	}

	@Override
	public Collection<Task> findTasksByProcessInstanceAsignee(Optional<String> processInstanceId,
			Optional<String> createdAfter, CIBUser user) {
		TaskQueryDto dto = new TaskQueryDto();
		if (createdAfter.isPresent()) {
			dto.setCreatedAfter(directProviderUtil.getJsonMapper(user).convertValue(createdAfter.get(), Date.class));
		}
		dto.setAssignee(user.getId());
		if (processInstanceId.isPresent())
			dto.setProcessInstanceId(processInstanceId.get());
		TaskQuery taskQuery = dto.toQuery(directProviderUtil.getProcessEngine(user));
		List<org.cibseven.bpm.engine.task.Task> resultList = taskQuery.initializeFormKeys().list();
		return convertTasks(resultList, user);

	}

	@Override
	public Task findTaskById(String id, CIBUser user) {
		org.cibseven.bpm.engine.task.Task result = directProviderUtil.getProcessEngine(user).getTaskService().createTaskQuery().taskId(id).initializeFormKeys()
				.singleResult();
		if (result == null)
			throw new NoObjectFoundException("No matching task with id " + Optional.ofNullable(id).orElse("null"));
		return directProviderUtil.getJsonMapper(user).convertValue(TaskDto.fromEntity(result), Task.class);
	}

	@Override
	public void update(Task task, CIBUser user) {
		org.cibseven.bpm.engine.task.Task foundTask = directProviderUtil.getProcessEngine(user).getTaskService().createTaskQuery().taskId(task.getId()).initializeFormKeys()
				.singleResult();

		if (foundTask == null) {
			throw new NoObjectFoundException("No matching task with id " + Optional.ofNullable(task.getId()).orElse("null"));
		}

		foundTask.setName(task.getName());
		foundTask.setDescription(task.getDescription());
		foundTask.setPriority((int) task.getPriority());
		foundTask.setAssignee(task.getAssignee());
		foundTask.setOwner(task.getOwner());

		DelegationState state = null;
		if (task.getDelegationState() != null) {
			DelegationStateConverter converter = new DelegationStateConverter();
			state = converter.convertQueryParameterToType(task.getDelegationState());
		}
		foundTask.setDelegationState(state);

		foundTask.setDueDate(directProviderUtil.getJsonMapper(user).convertValue(task.getDue(), Date.class));
		foundTask.setFollowUpDate(directProviderUtil.getJsonMapper(user).convertValue(task.getFollowUp(), Date.class));
		foundTask.setParentTaskId(task.getParentTaskId());
		foundTask.setCaseInstanceId(task.getCaseInstanceId());
		foundTask.setTenantId(task.getTenantId());

		directProviderUtil.getProcessEngine(user).getTaskService().saveTask(foundTask);
	}

	@Override
	public void setAssignee(String taskId, String assignee, CIBUser user) {
		org.cibseven.bpm.engine.task.Task foundTask = getTaskById(taskId, user);
		// "null" is how callers ask for no assignee; the REST provider maps it to /unclaim
		foundTask.setAssignee("null".equals(assignee) ? null : assignee);
		directProviderUtil.getProcessEngine(user).getTaskService().saveTask(foundTask);
	}

	@Override
	public void claim(String taskId, String userId, CIBUser user) {
		directProviderUtil.getProcessEngine(user).getTaskService().claim(taskId, userId);
	}

	@Override
	public void delegate(String taskId, String userId, CIBUser user) {
		directProviderUtil.getProcessEngine(user).getTaskService().delegateTask(taskId, userId);
	}

	@Override
	public Map<String, Object> findLocalVariables(String taskId, CIBUser user) {
		// Same shape and default as engine-rest's /localVariables: typed values, objects deserialized
		VariableMap variables = directProviderUtil.getProcessEngine(user).getTaskService().getVariablesLocalTyped(taskId, true);
		return new HashMap<>(VariableValueDto.fromMap(variables));
	}

	@Override
	public Collection<Map<String, Object>> findComments(String taskId, CIBUser user) {
		List<Map<String, Object>> comments = new ArrayList<>();
		for (Comment comment : directProviderUtil.getProcessEngine(user).getTaskService().getTaskComments(taskId)) {
			Map<String, Object> entry = new HashMap<>();
			entry.put("id", comment.getId());
			entry.put("userId", comment.getUserId());
			entry.put("taskId", comment.getTaskId());
			entry.put("processInstanceId", comment.getProcessInstanceId());
			entry.put("time", comment.getTime());
			entry.put("message", comment.getFullMessage());
			comments.add(entry);
		}
		return comments;
	}

	@Override
	public void submit(String taskId, CIBUser user) {
		VariableMap variables = null;
		directProviderUtil.getProcessEngine(user).getFormService().submitTaskForm(taskId, variables);
	}

	@Override
	public void submit(Task task, List<Variable> formResult, CIBUser user) {
		Map<String, VariableValueDto> variables = new HashMap<>();
		for (Variable variable : formResult) {
			variables.put(variable.getName(), directProviderUtil.toVariableValueDto(variable, user));
		}
		CompleteTaskDto completeTaskDto = new CompleteTaskDto();
		completeTaskDto.setVariables(variables);

		try {
			VariableMap variablesMap = VariableValueDto.toMap(completeTaskDto.getVariables(), directProviderUtil.getProcessEngine(user), directProviderUtil.getObjectMapper(user));
			directProviderUtil.getProcessEngine(user).getFormService().submitTaskForm(task.getId(), variablesMap);
		} catch (AuthorizationException e) {
			throw e;
		} catch (ProcessEngineException e) {
			String errorMessage = String.format("Cannot submit task form %s: %s", task.getId(), e.getMessage());
			throw new SubmitDeniedException(new SystemException(errorMessage, e));
		}
	}

	@Override
	public Object formReference(String taskId, CIBUser user) {
		List<String> formVariables = null;
		String variableNames = "formReference";
		if (variableNames != null) {
			StringListConverter stringListConverter = new StringListConverter();
			formVariables = stringListConverter.convertQueryParameterToType(variableNames);
		}
		boolean deserializeValues = true;
		VariableMap startFormVariables = directProviderUtil.getProcessEngine(user).getFormService().getTaskFormVariables(taskId, formVariables, deserializeValues);
		Set<String> keys = startFormVariables.keySet();
		if (keys.isEmpty())
			return new String("empty-task");
		else {
			return VariableValueDto.fromMap(startFormVariables);
		}
	}

	@Override
	public Object form(String taskId, CIBUser user) {
		org.cibseven.bpm.engine.task.Task task = getTaskById(taskId, user);
		FormData formData;
		try {
			formData = directProviderUtil.getProcessEngine(user).getFormService().getTaskFormData(taskId);
		} catch (AuthorizationException e) {
			throw e;
		} catch (ProcessEngineException e) {
			throw new SystemException("Cannot get form for task " + taskId, e);
		}

		FormDto dto = FormDto.fromFormData(formData);
		if (dto.getKey() == null || dto.getKey().isEmpty()) {
			if (formData != null && formData.getFormFields() != null && !formData.getFormFields().isEmpty()) {
				dto.setKey("embedded:engine://engine/:engine/task/" + taskId + "/rendered-form");
			}
		}
		if (dto.getKey() == null || dto.getKey().isEmpty()) {
			return "empty-task";
		}

		directProviderUtil.runWithoutAuthorization(() -> {
			String processDefinitionId = task.getProcessDefinitionId();
			String caseDefinitionId = task.getCaseDefinitionId();
			if (processDefinitionId != null) {
				dto.setContextPath(
						ApplicationContextPathUtil.getApplicationPathByProcessDefinitionId(directProviderUtil.getProcessEngine(user), processDefinitionId));

			} else if (caseDefinitionId != null) {
				dto.setContextPath(
						ApplicationContextPathUtil.getApplicationPathByCaseDefinitionId(directProviderUtil.getProcessEngine(user), caseDefinitionId));
			}
			return null;
		}, user);

		TaskForm taskForm = new TaskForm();
		CamundaFormRef camundaFormRef = dto.getCamundaFormRef();
		if (camundaFormRef != null)
			taskForm.setCamundaFormRef(new CamundaForm(camundaFormRef.getKey(), camundaFormRef.getBinding(),
					Integer.toString(camundaFormRef.getVersion())));
		taskForm.setContextPath(dto.getContextPath());
		taskForm.setKey(dto.getKey());
		return taskForm;

	}

	@Override
	public Collection<Task> findTasksByFilter(TaskFiltering filters, String filterId, CIBUser user, Integer firstResult,
			Integer maxResults) {
		List<?> entities = executeFilterList(filters, filterId, user, firstResult, maxResults);

		if (entities != null && !entities.isEmpty()) {
			List<Task> tasks = convertToDtoList(entities, user);
			List<String> variableNames = filters.getVariableNames();
			if (variableNames != null && !variableNames.isEmpty()) {
				enrichTasksWithVariables(tasks, variableNames, user);
			}
			return tasks;
		} else {
			return Collections.emptyList();
		}
	}

	private void enrichTasksWithVariables(List<Task> tasks, List<String> variableNames, CIBUser user) {
		String processInstanceIds = tasks.stream()
			.map(Task::getProcessInstanceId)
			.filter(id -> id != null && !id.isEmpty())
			.distinct()
			.collect(Collectors.joining(","));

		if (processInstanceIds.isEmpty()) return;
			VariableInstanceQueryDto queryDto = new VariableInstanceQueryDto();
			queryDto.setProcessInstanceIdIn(new String[] { processInstanceIds });
			List<VariableInstanceDto> variableInstances = queryVariableInstances(queryDto, false, user);
		try {
			Map<String, Map<String, Object>> varsByInstance = new HashMap<>();
			Map<String, Map<String, String>> typesByInstance = new HashMap<>();
			for (VariableInstanceDto vi : variableInstances) {
				if (variableNames.contains(vi.getName()) && vi.getProcessInstanceId() != null) {
					Object displayValue = vi.getValue();
					if ("Object".equals(vi.getType()) && vi.getValueInfo() != null) {
						Object typeName = vi.getValueInfo().get("objectTypeName");
						if (typeName != null) displayValue = typeName;
					}
					varsByInstance
						.computeIfAbsent(vi.getProcessInstanceId(), k -> new HashMap<>())
						.put(vi.getName(), displayValue);
					typesByInstance
						.computeIfAbsent(vi.getProcessInstanceId(), k -> new HashMap<>())
						.put(vi.getName(), vi.getType());
				}
			}

			for (Task task : tasks) {
				if (task.getProcessInstanceId() != null) {
					task.setVariables(varsByInstance.getOrDefault(task.getProcessInstanceId(), new HashMap<>()));
					task.setVariableTypes(typesByInstance.getOrDefault(task.getProcessInstanceId(), new HashMap<>()));
				}
			}
		} catch (Exception e) {
			log.warn("Could not enrich tasks with variables: {}", e.getMessage());
		}
	}


private List<VariableInstanceDto> queryVariableInstances(VariableInstanceQueryDto queryDto, boolean deserializeObjectValues, CIBUser user) {
	  ProcessEngine engine = directProviderUtil.getProcessEngine(user);
	  queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
	  VariableInstanceQuery query = queryDto.toQuery(engine);
	
	  // disable binary fetching by default.
	  query.disableBinaryFetching();
	
	  // disable custom object fetching by default. Cannot be done to not break existing API
	  if (!deserializeObjectValues) {
	    query.disableCustomObjectDeserialization();
	  }
	
	  List<org.cibseven.bpm.engine.runtime.VariableInstance> matchingInstances = QueryUtil.list(query, null, null);
	
	  List<VariableInstanceDto> instanceResults = new ArrayList<>();
	  for (org.cibseven.bpm.engine.runtime.VariableInstance instance : matchingInstances) {
	    VariableInstanceDto resultInstance = VariableInstanceDto.fromVariableInstance(instance);
	    instanceResults.add(resultInstance);
	  }
  return instanceResults;
	}
	
	@Override
	public Integer findTasksCountByFilter(String filterId, CIBUser user, TaskFiltering filters) {
		String extendingQueryString;
		try {
			extendingQueryString = filters.json();
		} catch (JsonProcessingException e) {
			throw new SystemException("Failed json conversion", e);
		}

		Query<?, ?> extendingQuery = convertQuery(extendingQueryString, filterId, user);
		try {
			long count = directProviderUtil.getProcessEngine(user).getFilterService().count(filterId, extendingQuery);
			return (int) count;
		} catch (NullValueException e) {
			throw new SystemException("Filter not found", e);
		}
	}

	@Override
	public Collection<TaskHistory> findTasksByProcessInstanceHistory(String processInstanceId, CIBUser user) {
		List<TaskHistory> taskHistoryList = new ArrayList<>();
		List<HistoricTaskInstance> results = directProviderUtil.getProcessEngine(user).getHistoryService().createHistoricTaskInstanceQuery()
				.processInstanceId(processInstanceId).unlimitedList();
		for (HistoricTaskInstance result : results) {
			taskHistoryList.add(directProviderUtil.convertValue(HistoricTaskInstanceDto.fromHistoricTaskInstance(result), TaskHistory.class, user));
		}
		return taskHistoryList;
	}

	@Override
	public Collection<TaskHistory> findTasksByDefinitionKeyHistory(String taskDefinitionKey, String processInstanceId,
			CIBUser user) {
		List<TaskHistory> taskHistoryList = new ArrayList<>();
		List<HistoricTaskInstance> results = directProviderUtil.getProcessEngine(user).getHistoryService().createHistoricTaskInstanceQuery()
				.taskDefinitionKey(taskDefinitionKey).processInstanceId(processInstanceId).unlimitedList();
		for (HistoricTaskInstance result : results) {
			taskHistoryList.add(directProviderUtil.convertValue(HistoricTaskInstanceDto.fromHistoricTaskInstance(result), TaskHistory.class, user));
		}
		return taskHistoryList;
	}

	@Override
	public Collection<Task> findTasksPost(Map<String, Object> data, CIBUser user) throws SystemException {
		TaskQueryDto queryDto = directProviderUtil.getJsonMapper(user).convertValue(data, TaskQueryDto.class);
		queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
		TaskQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));

		query.initializeFormKeys();
		List<org.cibseven.bpm.engine.task.Task> matchingTasks = QueryUtil.list(query, null, null);

		List<TaskDto> tasks = new ArrayList<>();
		if (Boolean.TRUE.equals(queryDto.getWithCommentAttachmentInfo())) {
			tasks = matchingTasks.stream().map(TaskWithAttachmentAndCommentDto::fromEntity).collect(Collectors.toList());
		} else {
			tasks = matchingTasks.stream().map(TaskDto::fromEntity).collect(Collectors.toList());
		}
		List<Task> resultTasks = new ArrayList<>();
		for (TaskDto matchingTask : tasks) {
			resultTasks.add(directProviderUtil.convertValue(matchingTask, Task.class, user));
		}
		return resultTasks;
	}

	@Override
	public Collection<IdentityLink> findIdentityLink(String taskId, Optional<String> type, CIBUser user) {
		List<org.cibseven.bpm.engine.task.IdentityLink> identityLinks = directProviderUtil.getProcessEngine(user).getTaskService().getIdentityLinksForTask(taskId);

		Collection<IdentityLink> result = new ArrayList<>();
		for (org.cibseven.bpm.engine.task.IdentityLink link : identityLinks) {
			if (type.isEmpty() || type.get().equals(link.getType())) {
				result.add(new IdentityLink(link.getUserId(), link.getGroupId(), link.getType()));
			}
		}
		return result;
	}

	@Override
	public void createIdentityLink(String taskId, Map<String, Object> data, CIBUser user) {
		String userId = (String) data.get("userId");
		String groupId = (String) data.get("groupId");
		if (userId != null && groupId != null) {
			throw new SystemException("Identity Link requires userId or groupId, but not both.");
		}

		if (userId == null && groupId == null) {
			throw new SystemException("Identity Link requires userId or groupId.");
		}

		String type = (String) data.get("type");
		if (userId != null) {
			directProviderUtil.getProcessEngine(user).getTaskService().addUserIdentityLink(taskId, userId, type);
		} else if (groupId != null) {
			directProviderUtil.getProcessEngine(user).getTaskService().addGroupIdentityLink(taskId, groupId, type);
		}
	}

	@Override
	public void deleteIdentityLink(String taskId, Map<String, Object> data, CIBUser user) {
		String userId = (String) data.get("userId");
		String groupId = (String) data.get("groupId");
		if (userId != null && groupId != null) {
			throw new SystemException("Identity Link requires userId or groupId, but not both.");
		}

		if (userId == null && groupId == null) {
			throw new SystemException("Identity Link requires userId or groupId.");
		}

		String type = (String) data.get("type");
		if (userId != null) {
			directProviderUtil.getProcessEngine(user).getTaskService().deleteUserIdentityLink(taskId, userId, type);
		} else if (groupId != null) {
			directProviderUtil.getProcessEngine(user).getTaskService().deleteGroupIdentityLink(taskId, groupId, type);
		}

	}

	@Override
	public void handleBpmnError(String taskId, Map<String, Object> data, CIBUser user) throws SystemException {
		TaskBpmnErrorDto dto = directProviderUtil.getObjectMapper(user).convertValue(data, TaskBpmnErrorDto.class);
		try {
			directProviderUtil.getProcessEngine(user).getTaskService().handleBpmnError(taskId, dto.getErrorCode(), dto.getErrorMessage(),
					VariableValueDto.toMap(dto.getVariables(), directProviderUtil.getProcessEngine(user), directProviderUtil.getObjectMapper(user)));
		} catch (NotFoundException e) {
			throw new SystemException(e.getMessage(), e);
		}
	}

	@Override
	public void handleBpmnEscalation(String taskId, Map<String, Object> data, CIBUser user) throws SystemException {
		TaskEscalationDto dto = directProviderUtil.getObjectMapper(user).convertValue(data, TaskEscalationDto.class);
		try {
			directProviderUtil.getProcessEngine(user).getTaskService().handleEscalation(taskId, dto.getEscalationCode(),
					VariableValueDto.toMap(dto.getVariables(), directProviderUtil.getProcessEngine(user), directProviderUtil.getObjectMapper(user)));
		} catch (NotFoundException e) {
			throw new SystemException(e.getMessage(), e);
		}
	}

	@Override
	public Collection<TaskHistory> findTasksByTaskIdHistory(String taskId, CIBUser user) {
		List<TaskHistory> taskHistoryList = new ArrayList<>();
		List<HistoricTaskInstance> results = directProviderUtil.getProcessEngine(user).getHistoryService().createHistoricTaskInstanceQuery().taskId(taskId).unlimitedList();
		for (HistoricTaskInstance result : results) {
			taskHistoryList.add(directProviderUtil.convertValue(HistoricTaskInstanceDto.fromHistoricTaskInstance(result), TaskHistory.class, user));
		}
		return taskHistoryList;
	}

	@Override
	public ResponseEntity<byte[]> getDeployedForm(String taskId, CIBUser user) {
		InputStream form = directProviderUtil.getProcessEngine(user).getFormService().getDeployedTaskForm(taskId);
		if (form != null) {
			try {
				byte[] bytes = IOUtils.toByteArray(form);
				ResponseEntity<byte[]> responseEntity = ResponseEntity.ok()
					.contentType(MediaType.APPLICATION_XHTML_XML)
					.body(bytes);
				return responseEntity;
			} catch (IOException e) {
				throw new SystemException(e.getMessage());
			} finally {
				IoUtil.closeSilently(form);
			}
		}
		return ResponseEntity.status(422).build();
	}

	@Override
	public Integer findHistoryTasksCount(Map<String, Object> filters, CIBUser user) {
		HistoricTaskInstanceQueryDto queryDto = directProviderUtil.getJsonMapper(user).convertValue(filters, HistoricTaskInstanceQueryDto.class);
		queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
		HistoricTaskInstanceQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));

		long count = query.count();
		return (int) count;
	}

	@Override
	public Collection<TaskHistory> findHistoryTasks(Map<String, Object> filters,
			Optional<Integer> firstResult, Optional<Integer> maxResults, CIBUser user) {
		HistoricTaskInstanceQueryDto queryDto = directProviderUtil.getJsonMapper(user).convertValue(filters, HistoricTaskInstanceQueryDto.class);
		queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
		HistoricTaskInstanceQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));

		List<HistoricTaskInstance> results = (firstResult.isPresent() || maxResults.isPresent())
				? query.listPage(firstResult.orElse(0), maxResults.orElse(Integer.MAX_VALUE))
				: query.unlimitedList();

		List<TaskHistory> taskHistoryList = new ArrayList<>();
		for (HistoricTaskInstance result : results) {
			taskHistoryList.add(directProviderUtil.convertValue(HistoricTaskInstanceDto.fromHistoricTaskInstance(result), TaskHistory.class, user));
		}
		return taskHistoryList;
	}

	@Override
	public Collection<CandidateGroupTaskCount> getTaskCountByCandidateGroup(CIBUser user) {
		TaskCountByCandidateGroupResultDto reportDto = new TaskCountByCandidateGroupResultDto();
		List<TaskCountByCandidateGroupResult> results = reportDto.executeTaskCountByCandidateGroupReport(directProviderUtil.getProcessEngine(user));
		Collection<CandidateGroupTaskCount> resultTaskCount = new ArrayList<>();
		for (TaskCountByCandidateGroupResult result : results) {
			resultTaskCount.add(directProviderUtil.convertValue(TaskCountByCandidateGroupResultDto.fromTaskCountByCandidateGroupResultDto(result),
					CandidateGroupTaskCount.class, user));
		}
		return resultTaskCount;
	}

	private Collection<Task> convertTasks(Collection<org.cibseven.bpm.engine.task.Task> engineTasks, CIBUser user) {
		List<Task> resultList = new ArrayList<>();
		for (org.cibseven.bpm.engine.task.Task engineTask : engineTasks)
			resultList.add(directProviderUtil.getJsonMapper(user).convertValue(TaskDto.fromEntity(engineTask), Task.class));
		return resultList;
	}

	private List<org.cibseven.bpm.engine.task.Task> queryTasks(Map<String, Object> filters, CIBUser user) {
		MultivaluedMap<String, String> multiValueMap = new MultivaluedHashMap<>();
		for (Entry<String, Object> entry : filters.entrySet()) {
			multiValueMap.put(entry.getKey(), Arrays.asList(String.valueOf(entry.getValue())));
		}
		TaskQueryDto dto = new TaskQueryDto(directProviderUtil.getObjectMapper(user), multiValueMap);
		ProcessEngine engine = directProviderUtil.getProcessEngine(user);
		TaskQuery taskQuery = dto.toQuery(engine);
		List<org.cibseven.bpm.engine.task.Task> taskList = taskQuery.list();

		return taskList;
	}

	protected org.cibseven.bpm.engine.task.Task getTaskById(String taskId, CIBUser user) {
		org.cibseven.bpm.engine.task.Task foundTask = directProviderUtil.getProcessEngine(user).getTaskService().createTaskQuery().taskId(taskId).initializeFormKeys()
				.singleResult();
		if (foundTask == null) {
			throw new NoObjectFoundException(new SystemException("No matching task with id " + taskId));
		}
		return foundTask;
	}

	private List<?> executeFilterList(TaskFiltering filters, String filterId, CIBUser user, Integer firstResult,
			Integer maxResults) {
		String extendingQuery;
		try {
			extendingQuery = filters.json();
		} catch (JsonProcessingException e) {
			throw new SystemException("Failed json conversion", e);
		}
		return executeFilterList(extendingQuery, filterId, firstResult, maxResults, user);
	}

	private List<?> executeFilterList(String extendingQueryString, String filterId, Integer firstResult, Integer maxResults, CIBUser user) {
		Query<?, ?> extendingQuery = convertQuery(extendingQueryString, filterId, user);
		try {
			if (firstResult != null || maxResults != null) {
				if (firstResult == null) {
					firstResult = 0;
				}
				if (maxResults == null) {
					maxResults = Integer.MAX_VALUE;
				}
				return directProviderUtil.getProcessEngine(user).getFilterService().listPage(filterId, extendingQuery, firstResult, maxResults);
			} else {
				return directProviderUtil.getProcessEngine(user).getFilterService().list(filterId, extendingQuery);
			}
		} catch (NullValueException e) {
			throw new NoObjectFoundException("Filter not found", e);
		}
	}

	private Query<?, ?> convertQuery(String queryString, String filterId, CIBUser user) {
		if (isEmptyJson(queryString)) {
			return null;
		} else {
			ProcessEngine processEngine = directProviderUtil.getProcessEngine(user);
			Filter filter = processEngine.getFilterService().getFilter(filterId);
			if (filter == null) {
				throw new NoObjectFoundException("Filter with id '" + filterId + "' not found.");
			}
			String resourceType = filter.getResourceType();

			AbstractQueryDto<?> queryDto = getQueryDtoForQuery(queryString, resourceType, user);
			queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
			return queryDto.toQuery(processEngine);
		}
	}

	private AbstractQueryDto<?> getQueryDtoForQuery(String queryString, String resourceType, CIBUser user) {
		try {
			if (EntityTypes.TASK.equals(resourceType)) {
				return directProviderUtil.getJsonMapper(user).readValue(queryString, TaskQueryDto.class);
			} else {
				throw new SystemException(
						"Queries for resource type '" + resourceType + "' are currently not supported by filters.");
			}
		} catch (JacksonException e) {
			throw new SystemException("Invalid query for resource type '" + resourceType + "'", e);
		}
	}

	private List<Task> convertToDtoList(List<?> entities, CIBUser user) {
		List<Task> dtoList = new ArrayList<>();
		for (Object entity : entities) {
			dtoList.add(convertToDto(entity, user));
		}
		return dtoList;
	}

	private Task convertToDto(Object entity, CIBUser user) {
		if (entity instanceof org.cibseven.bpm.engine.task.Task) {
			return directProviderUtil.convertValue(TaskDto.fromEntity((org.cibseven.bpm.engine.task.Task) entity), Task.class, user);
		} else {
			throw new SystemException(
					"Entities of class '" + entity.getClass().getCanonicalName() + "' are currently not supported by filters.");
		}
	}

	private boolean isEmptyJson(String jsonString) {
		final Pattern EMPTY_JSON_BODY = Pattern.compile("\\s*\\{\\s*\\}\\s*");
		return jsonString == null || jsonString.trim().isEmpty() || EMPTY_JSON_BODY.matcher(jsonString).matches();
	}

	@Override
	public void submit(String taskId, String formResult, CIBUser user) {
    ProcessEngine engine = directProviderUtil.getProcessEngine(user);
		FormService formService = engine.getFormService();
		ObjectMapper objectMapper = directProviderUtil.getObjectMapper(user);
		CompleteTaskDto dto;
		try {
			dto = objectMapper.readValue(formResult, CompleteTaskDto.class);
		} catch (JsonProcessingException e) {
			throw new SystemException(e.getMessage(), e);
		} 

		try {
			VariableMap variables = VariableValueDto.toMap(dto.getVariables(), engine, objectMapper);
			if (dto.isWithVariablesInReturn()) {
				formService.submitTaskFormWithVariablesInReturn(taskId, variables, false);
				} else {
					formService.submitTaskForm(taskId, variables);
				}

		} catch (RestException|ProcessEngineException e) {
			String errorMessage = String.format("Cannot submit task form %s: %s", taskId, e.getMessage());
			throw new SystemException(errorMessage, e);
		}
		
	}

	@Override
	public ResponseEntity<String> getRenderedForm(String taskId, Map<String, Object> params, CIBUser user) {
    FormService formService = directProviderUtil.getProcessEngine(user).getFormService();

		Object renderedTaskForm = formService.getRenderedTaskForm(taskId);
		if(renderedTaskForm != null) {
			// the engine renders the form in memory, so no byte round trip is needed; re-encoding it
			// through the JVM default charset garbled non-ASCII text (CIB7-2204)
			return ResponseEntity.ok(renderedTaskForm.toString());
		}

		throw new SystemException("No matching rendered form for task with the id " + taskId + " found.");
	}
}
