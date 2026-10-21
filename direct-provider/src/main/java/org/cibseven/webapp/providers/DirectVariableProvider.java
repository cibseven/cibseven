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
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.io.IOUtils;
import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.history.HistoricVariableInstance;
import org.cibseven.bpm.engine.history.HistoricVariableInstanceQuery;
import org.cibseven.bpm.engine.impl.RuntimeServiceImpl;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.rest.dto.PatchVariablesDto;
import org.cibseven.bpm.engine.rest.dto.VariableValueDto;
import org.cibseven.bpm.engine.rest.dto.history.HistoricVariableInstanceDto;
import org.cibseven.bpm.engine.rest.dto.history.HistoricVariableInstanceQueryDto;
import org.cibseven.bpm.engine.rest.dto.runtime.ProcessInstanceDto;
import org.cibseven.bpm.engine.rest.dto.runtime.VariableInstanceQueryDto;
import org.cibseven.bpm.engine.rest.exception.RestException;
import org.cibseven.bpm.engine.rest.util.QueryUtil;
import org.cibseven.bpm.engine.runtime.DeserializationTypeValidator;
import org.cibseven.bpm.engine.variable.VariableMap;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.impl.type.AbstractValueTypeImpl;
import org.cibseven.bpm.engine.variable.type.FileValueType;
import org.cibseven.bpm.engine.variable.type.ValueType;
import org.cibseven.bpm.engine.variable.value.BytesValue;
import org.cibseven.bpm.engine.variable.value.FileValue;
import org.cibseven.bpm.engine.variable.value.TypedValue;
import org.cibseven.common.api.NamedByteArrayDataSource;
import org.cibseven.common.api.auth.CIBUser;
import org.cibseven.common.api.exception.ExpressionEvaluationException;
import org.cibseven.common.api.exception.NoObjectFoundException;
import org.cibseven.common.api.exception.SystemException;
import org.cibseven.common.api.exception.UnexpectedTypeException;
import org.cibseven.common.api.exception.UnsupportedTypeException;
import org.cibseven.common.api.providers.IVariableProvider;
import org.cibseven.common.api.rest.model.ProcessStart;
import org.cibseven.common.api.rest.model.Variable;
import org.cibseven.common.api.rest.model.VariableHistory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;

import jakarta.activation.DataSource;
import jakarta.activation.MimeType;
import jakarta.activation.MimeTypeParseException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DirectVariableProvider implements IVariableProvider {

	public static final String DEFAULT_BINARY_VALUE_TYPE = "Bytes";

	DirectProviderUtil directProviderUtil;
	public DirectVariableProvider(DirectProviderUtil directProviderUtil){
		this.directProviderUtil = directProviderUtil;
	}
	@Override
	public void modifyVariableByExecutionId(String executionId, Map<String, Object> data, CIBUser user)
			throws SystemException {
		PatchVariablesDto patch = directProviderUtil.getObjectMapper(user).convertValue(data, PatchVariablesDto.class);
		VariableMap variableModifications = null;
		try {
			variableModifications = VariableValueDto.toMap(patch.getModifications(), directProviderUtil.getProcessEngine(user), directProviderUtil.getObjectMapper(user));
		} catch (RestException e) {
			String errorMessage = String.format("Cannot modify variables for %s: %s", "modifyVariableByExecutionId",
					e.getMessage());
			throw new SystemException(errorMessage, e);
		}

		List<String> variableDeletions = patch.getDeletions();
		try {
			((RuntimeServiceImpl) directProviderUtil.getProcessEngine(user).getRuntimeService()).updateVariables(executionId, variableModifications, variableDeletions);
		} catch (AuthorizationException e) {
			throw e;
		} catch (ProcessEngineException e) {
			String errorMessage = String.format("Cannot modify variables for %s %s: %s", "modifyVariableByExecutionId",
					executionId, e.getMessage());
			throw new SystemException(errorMessage, e);
		}
	}

	@Override
	public void modifyVariableDataByExecutionId(String executionId, String variableName, MultipartFile data,
			String valueType, CIBUser user) throws SystemException {

		try {
			if (valueType.equalsIgnoreCase("File") || valueType.equalsIgnoreCase("Bytes")) {
				// Handle binary/file data
				VariableValueDto valueDto = createVariableValueDto(valueType, data);
				try {
					TypedValue typedValue = valueDto.toTypedValue(directProviderUtil.getProcessEngine(user), directProviderUtil.getObjectMapper(user));// creates FileValueImpl

					directProviderUtil.getProcessEngine(user).getRuntimeService().setVariable(executionId, variableName, typedValue);
				} catch (AuthorizationException e) {
					throw e;
				} catch (ProcessEngineException e) {
					String errorMessage = String.format("Cannot put %s variable %s: %s", executionId, variableName, e.getMessage());
					throw new SystemException(errorMessage, e);
				}
			} else {
				// Handle JSON/serialized data
				Object object = null;

				if (data.getContentType() != null
						&& data.getContentType().toLowerCase().contains(MediaType.APPLICATION_JSON.toString())) {
					object = deserializeJsonObject(valueType, data.getBytes(), user);

				} else {
					throw new SystemException("Unrecognized content type for serialized java type: " + data.getContentType());
				}

				if (object != null) {
					directProviderUtil.getProcessEngine(user).getRuntimeService().setVariable(executionId, variableName, Variables.objectValue(object).create());
				}
			}
		} catch (IOException e) { // from data.getBytes()
			throw new UnsupportedTypeException(e);
		}
	}

	@Override
	public Collection<Variable> fetchProcessInstanceVariables(String processInstanceId, Map<String, Object> data,
			CIBUser user) throws SystemException {
		data.put("processInstanceIdIn", new String[] { processInstanceId });
		final boolean deserializeValues = data != null && data.containsKey("deserializeValues")
				&& (Boolean) data.get("deserializeValues");
		if (data != null && data.containsKey("deserializeValues"))
			data.remove("deserializeValues");

		VariableInstanceQueryDto queryDto = directProviderUtil.getObjectMapper(user).convertValue(data, VariableInstanceQueryDto.class);

		queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));

		List<Variable> variablesDeserialized = directProviderUtil.queryVariableInstances(queryDto, null, null, true, user);
		if (variablesDeserialized.isEmpty())
			return Collections.emptyList();
		List<Variable> variablesSerialized = directProviderUtil.queryVariableInstances(queryDto, null, null, false, user);
		if (variablesSerialized.isEmpty())
			return Collections.emptyList();

		mergeVariablesValuesRuntime(variablesDeserialized, variablesSerialized, deserializeValues);
		Collection<Variable> variables = (deserializeValues) ? variablesDeserialized : variablesSerialized;
		return variables;
	}

	@Override
	public ResponseEntity<byte[]> fetchVariableDataByExecutionId(String executionId, String variableName, CIBUser user)
			throws NoObjectFoundException, SystemException {
		TypedValue typedVariableValue = directProviderUtil.getProcessEngine(user).getRuntimeService().getVariableLocalTyped(executionId, variableName, false);
		return getResponseForTypedVariable(typedVariableValue, executionId);
	}

	@Override
	public Collection<VariableHistory> fetchProcessInstanceVariablesHistory(String processInstanceId,
			Map<String, Object> data, CIBUser user) throws SystemException {
		data.put("processInstanceIdIn", new String[] { processInstanceId });
		final boolean deserializeValues = data != null && data.containsKey("deserializeValues")
				&& (Boolean) data.get("deserializeValues");
		if (data != null && data.containsKey("deserializeValues"))
			data.remove("deserializeValues");
		ObjectMapper objectMapper = directProviderUtil.getObjectMapper(user);
		HistoricVariableInstanceQueryDto queryDto = objectMapper.convertValue(data,
				HistoricVariableInstanceQueryDto.class);

		queryDto.setObjectMapper(objectMapper);

		List<VariableHistory> variablesDeserialized = queryHistoricVariableInstances(queryDto, objectMapper, null, null,
				true, user);
		if (variablesDeserialized.isEmpty())
			return Collections.emptyList();
		List<VariableHistory> variablesSerialized = queryHistoricVariableInstances(queryDto, objectMapper, null, null,
				false, user);
		if (variablesSerialized.isEmpty())
			return Collections.emptyList();

		// Get list of variables and merge them
		final ArrayList<VariableHistory> variablesDeserializedTyped = new ArrayList<>();
		if (variablesDeserialized.size() > 0) {
			variablesDeserializedTyped.addAll(variablesDeserialized);
		}

		final ArrayList<VariableHistory> variablesSerializedTyped = new ArrayList<>();
		if (variablesSerialized.size() > 0) {
			variablesSerializedTyped.addAll(variablesSerialized);
		}

		mergeVariablesValuesHistory(variablesDeserializedTyped, variablesSerializedTyped, deserializeValues);

		Collection<VariableHistory> variables = (deserializeValues) ? variablesDeserialized : variablesSerialized;
		return variables;

	}

	@Override
	public Collection<VariableHistory> fetchActivityVariablesHistory(String activityInstanceId, CIBUser user) {
		HistoricVariableInstanceQueryDto queryDto = new HistoricVariableInstanceQueryDto();
		queryDto.setActivityInstanceIdIn(new String[] { activityInstanceId});
		queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
		return queryHistoricVariableInstances(queryDto, null, null, true, user);
	}

	@Override
	public Collection<VariableHistory> fetchActivityVariables(String activityInstanceId, CIBUser user) {
		VariableInstanceQueryDto queryDto = new VariableInstanceQueryDto();
		queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
		queryDto.setActivityInstanceIdIn(new String[] { activityInstanceId });
		List<Variable> variableInstances = directProviderUtil.queryVariableInstances(queryDto, null, null, true, user);
		List<VariableHistory> historyVariables = new ArrayList<>();
		for (Variable variableInstance : variableInstances) {
			historyVariables.add(directProviderUtil.convertValue(variableInstance, VariableHistory.class, user));
		}
		return historyVariables;
	}

	@Override
	public ResponseEntity<byte[]> fetchHistoryVariableDataById(String id, CIBUser user)
			throws NoObjectFoundException, SystemException {
		HistoricVariableInstanceQuery query = directProviderUtil.getProcessEngine(user).getHistoryService().createHistoricVariableInstanceQuery().variableId(id);
		query.disableCustomObjectDeserialization();
		HistoricVariableInstance queryResult = query.singleResult();
		if (queryResult != null) {
			TypedValue typedValue = queryResult.getTypedValue();
			return getResponseForTypedVariable(typedValue, id);
		} else {
			throw new NoObjectFoundException(new SystemException("HistoryVariable with Id '" + id + "' does not exist."));
		}
	}

	@Override
	public Variable fetchVariable(String taskId, String variableName, boolean deserializeValue, CIBUser user)
			throws NoObjectFoundException, SystemException {
		Variable variableSerialized = fetchTaskVariableImpl(taskId, variableName, false, user);
		Variable variableDeserialized = fetchTaskVariableImpl(taskId, variableName, true, user);

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
	public void deleteVariable(String taskId, String variableName, CIBUser user)
			throws NoObjectFoundException, SystemException {
		try {
			directProviderUtil.getProcessEngine(user).getTaskService().removeVariable(taskId, variableName);
		} catch (AuthorizationException e) {
			throw e;
		} catch (ProcessEngineException e) {
			String errorMessage = String.format("Cannot delete %s variable %s: %s", "task", variableName, e.getMessage());
			throw new SystemException(errorMessage, e);
		}
	}

	@Override
	public Map<String, Variable> fetchFormVariables(String taskId, boolean deserializeValues, CIBUser user)
			throws NoObjectFoundException, SystemException {
		return fetchFormVariables(null, taskId, deserializeValues, user);
	}

	@Override
	public Map<String, Variable> fetchFormVariables(List<String> variableListName, String taskId, boolean deserializeValues, CIBUser user)
			throws NoObjectFoundException, SystemException {
		VariableMap startFormVariables = directProviderUtil.getProcessEngine(user).getFormService().getTaskFormVariables(taskId, variableListName, deserializeValues);
		Map<String, VariableValueDto> variableDtos = VariableValueDto.fromMap(startFormVariables);
		Map<String, Variable> variablesMap = new HashMap<>();
		for (Entry<String, VariableValueDto> e : variableDtos.entrySet()) {
			variablesMap.put(e.getKey(), directProviderUtil.convertValue(e.getValue(), Variable.class, user));
		}
		return variablesMap;
	}

	@Override
	public Map<String, Variable> fetchProcessFormVariables(String key, boolean deserializeValues, CIBUser user)
			throws NoObjectFoundException, SystemException {
		List<String> formVariables = null;

		ProcessDefinition processDefinition = directProviderUtil.getProcessEngine(user).getRepositoryService().createProcessDefinitionQuery()
				.processDefinitionKey(key).withoutTenantId().latestVersion().singleResult();

		if (processDefinition == null) {
			String errorMessage = String.format("No matching process definition with key: %s and no tenant-id", key);
			throw new SystemException(errorMessage);

		}

		VariableMap startFormVariables = directProviderUtil.getProcessEngine(user).getFormService().getStartFormVariables(processDefinition.getId(), formVariables, deserializeValues);
		Map<String, VariableValueDto> variableDtos = VariableValueDto.fromMap(startFormVariables);
		Map<String, Variable> variablesMap = new HashMap<>();
		for (Entry<String, VariableValueDto> e : variableDtos.entrySet()) {
			variablesMap.put(e.getKey(), directProviderUtil.convertValue(e.getValue(), Variable.class, user));
		}
		return variablesMap;
	}

	@Override
	public NamedByteArrayDataSource fetchVariableFileData(String taskId, String variableName, CIBUser user)
			throws NoObjectFoundException, UnexpectedTypeException, SystemException {
		try {
			byte[] data = null;
			String filename = null;
			String mimeType = null;

			Variable variable = fetchVariable(taskId, variableName, true, user);
			String objectType = variable.getValueInfo().get("objectTypeName");
			if (objectType != null) {
				try {
					Class<?> clazz = Class.forName(objectType);

					if (DataSource.class.isAssignableFrom(clazz)) {
						@SuppressWarnings("unchecked")
						DataSource ds = directProviderUtil.getObjectMapper(user).convertValue(variable.getValue(), (Class<? extends DataSource>) clazz);

						return new NamedByteArrayDataSource(ds.getName(), ds.getContentType(),
								IOUtils.toByteArray(ds.getInputStream()));
					}
				} catch (ClassNotFoundException e) {
					log.info("Class " + objectType + " could not be loaded!");
				}
			}

			filename = variable.getFilename();
			mimeType = variable.getMimeType();

			TypedValue typedVariableValue = directProviderUtil.getTypedValueForTaskVariable(taskId, variableName, true, user);
			// VariableValueDto dto = VariableValueDto.fromTypedValue(value);
			if (typedVariableValue instanceof BytesValue || ValueType.BYTES.equals(typedVariableValue.getType())) {
				data = (byte[]) typedVariableValue.getValue();
				if (data == null) {
					data = new byte[0];
				}
			} else if (ValueType.FILE.equals(typedVariableValue.getType())) {
				FileValue typedFileValue = (FileValue) typedVariableValue;
				try {
					data = typedFileValue.getValue() == null ? null : IOUtils.toByteArray(typedFileValue.getValue());
					// status code if bytes==null?
				} catch (IOException e) {
					throw new SystemException(e.getMessage(), e);
				}
			} else {
				throw new SystemException(String.format("Value of variable with id %s is not a binary value.", variableName));
			}

			return new NamedByteArrayDataSource(filename, mimeType, data);
		} catch (IOException e) {
			throw new SystemException(e);
		}
	}

	@Override
	public void uploadVariableFileData(String taskId, String variableName, MultipartFile data, String valueType,
			CIBUser user) throws NoObjectFoundException, SystemException {
		try {
			setBinaryVariable(data, valueType, null, taskId, null, variableName, user);
		} catch (IOException e) {
			throw new SystemException(e.getMessage());
		}
	}

	@Override
	public ResponseEntity<byte[]> fetchProcessInstanceVariableData(String processInstanceId, String variableName,
			CIBUser user) throws NoObjectFoundException, SystemException {
		Variable variable = fetchVariableByProcessInstanceId(processInstanceId, variableName, user);
		String objectType = variable.getValueInfo().get("objectTypeName");
		if (objectType != null) {
			try {
				Class<?> clazz = Class.forName(objectType);

				if (DataSource.class.isAssignableFrom(clazz)) {
					final ObjectMapper mapper = new ObjectMapper();
					@SuppressWarnings("unchecked")
					DataSource ds = mapper.convertValue(variable.getValue(), (Class<? extends DataSource>) clazz);

					return ResponseEntity.ok(IOUtils.toByteArray(ds.getInputStream()));
				}
			} catch (ClassNotFoundException e) {
				log.info("Class " + objectType + " could not be loaded!");
			} catch (IOException e) {
				throw new SystemException(e.getMessage(), e);
			}
		}
		TypedValue value = null;
		try {
			value = directProviderUtil.getProcessEngine(user).getRuntimeService().getVariableTyped(processInstanceId, variableName, false);
		} catch (AuthorizationException e) {
			throw e;
		} catch (ProcessEngineException e) {
			String errorMessage = String.format("Cannot get %s variable %s: %s", "processInstance", variableName,
					e.getMessage());
			throw new SystemException(errorMessage, e);
		}

		if (value == null) {
			String errorMessage = String.format("%s variable with name %s does not exist", "processInstance", variableName);
			throw new SystemException(errorMessage);
		}
		if (value instanceof BytesValue || ValueType.BYTES.equals(value.getType())) {
			byte[] valueBytes = (byte[]) value.getValue();
			if (valueBytes == null) {
				valueBytes = new byte[0];
			}
			
			ResponseEntity<byte[]> responseEntity = ResponseEntity.ok(valueBytes);
			return responseEntity;
		} else if (ValueType.FILE.equals(value.getType())) {
			FileValue typedFileValue = (FileValue) value;
			try {
				byte[] bytes = typedFileValue.getValue() == null ? null : IOUtils.toByteArray(typedFileValue.getValue());
				HttpHeaders headers = new HttpHeaders();
				headers.setContentType(typedFileValue.getMimeType() != null ? MediaType.valueOf(typedFileValue.getMimeType())
						: MediaType.APPLICATION_OCTET_STREAM);
				ResponseEntity<byte[]> responseEntity = ResponseEntity.ok().headers(headers).body(bytes);
				return responseEntity;
			} catch (IOException e) {
				throw new SystemException(e.getMessage(), e);
			}
		} else {
			throw new SystemException(String.format("Value of variable with id %s is not a binary value.", variableName));
		}
	}

	@Override
	public void uploadProcessInstanceVariableFileData(String processInstanceId, String variableName, MultipartFile data,
			String valueType, CIBUser user) throws NoObjectFoundException, SystemException {
		try {
			setBinaryVariable(data, valueType, null, null, processInstanceId, variableName, user);
		} catch (IOException e) {
			throw new SystemException(e.getMessage());
		}
	}

	@Override
	public ProcessStart submitStartFormVariables(String processDefinitionId, List<Variable> formResult, CIBUser user)
			throws SystemException {
		Map<String, Object> variablesMap = new HashMap<>();
		for (Variable variable : formResult) {
			if (variable.getType().equalsIgnoreCase("file")) {
				// https://helpdesk.cib.de/browse/BPM4CIB-434
				int lastIndex = variable.getFilename().lastIndexOf(".rtf");
				if ((lastIndex > 0) && ((lastIndex + 4) == variable.getFilename().length())) {
					variable.getValueInfo().put("mimeType", "application/rtf");
				}
			}
			VariableValueDto variableValueDto = directProviderUtil.convertValue(variable, VariableValueDto.class, user);
			TypedValue typedValue = variableValueDto.toTypedValue(directProviderUtil.getProcessEngine(user),
					directProviderUtil.getObjectMapper(user));
			variablesMap.put(variable.getName(), typedValue);
		}
		try {
			org.cibseven.bpm.engine.runtime.ProcessInstance instance = directProviderUtil.getProcessEngine(user).getFormService().submitStartForm(processDefinitionId, variablesMap);
			ProcessInstanceDto processInstanceDto = ProcessInstanceDto.fromProcessInstance(instance);

			ProcessStart result = directProviderUtil.convertValue(processInstanceDto, ProcessStart.class, user);
			return result;
		} catch (AuthorizationException e) {
			throw e;

		} catch (ProcessEngineException|RestException e) {
			String errorMessage = String.format("Cannot instantiate process definition %s: %s", processDefinitionId,
					e.getMessage());
			throw new ExpressionEvaluationException(new UnsupportedTypeException(new SystemException(errorMessage, e)));
		}
	}

	@Override
	public Variable fetchVariableByProcessInstanceId(String processInstanceId, String variableName, CIBUser user)
			throws SystemException {
		Variable variableSerialized = fetchVariableByProcessInstanceIdImpl(processInstanceId, variableName, false, user);
		Variable variableDeserialized = fetchVariableByProcessInstanceIdImpl(processInstanceId, variableName, true, user);

		variableDeserialized.setValueSerialized(variableSerialized.getValue());
		variableDeserialized.setValueDeserialized(variableDeserialized.getValue());
		return variableDeserialized;
	}

	@Override
	public void saveVariableInProcessInstanceId(String processInstanceId, List<Variable> variables, CIBUser user)
			throws SystemException {
		List<String> deletions = new ArrayList<>();
		Map<String, VariableValueDto> modifications = new HashMap<>();
		for (Variable variable : variables) {
			VariableValueDto variableValueDto = directProviderUtil.convertValue(variable, VariableValueDto.class, user);
			variableValueDto.setType(variable.getType());
			variableValueDto.setValue(variable.getValue());
			if (variable.getValueInfo() != null)
				variableValueDto.setValueInfo(new HashMap<>(variable.getValueInfo()));
			modifications.put(variable.getName(), variableValueDto);
		}
		updateVariableEntities(processInstanceId, modifications, deletions, user);
	}

	@Override
	public void submitVariables(String processInstanceId, List<Variable> formResult, CIBUser user,
			String processDefinitionId) throws SystemException {

		List<String> deletions = new ArrayList<>();
		Map<String, VariableValueDto> modifications = new HashMap<>();
		for (Variable variable : formResult) {
			modifications.put(variable.getName(), directProviderUtil.toVariableValueDto(variable, user));
		}

		updateVariableEntities(processInstanceId, modifications, deletions, user);

	}

	@Override
	public Map<String, Variable> fetchProcessFormVariablesById(String id, CIBUser user) throws SystemException {
		VariableMap startFormVariables = directProviderUtil.getProcessEngine(user).getFormService().getStartFormVariables(id, null, true);
		Map<String, Variable> resultMap = new HashMap<>();
		Map<String, VariableValueDto> resultDtoMap = VariableValueDto.fromMap(startFormVariables);
		for (Entry<String, VariableValueDto> resultDtoEntry : resultDtoMap.entrySet()) {
			resultMap.put(resultDtoEntry.getKey(), directProviderUtil.convertValue(resultDtoEntry.getValue(), Variable.class, user));
		}
		return resultMap;
	}

	@Override
	public void putLocalExecutionVariable(String executionId, String varName, Map<String, Object> data, CIBUser user) {
		try {
			VariableValueDto variable = directProviderUtil.getObjectMapper(user).convertValue(data, VariableValueDto.class);
			TypedValue typedValue = variable.toTypedValue(directProviderUtil.getProcessEngine(user), directProviderUtil.getObjectMapper(user));
			directProviderUtil.getProcessEngine(user).getRuntimeService().setVariable(executionId, varName, typedValue);

		} catch (AuthorizationException e) {
			throw new SystemException(e.getMessage(), e);
		} catch (ProcessEngineException|RestException e) {
			throw new SystemException(String.format("Cannot put %s variable %s: %s", "execution", varName, e.getMessage()), e);
		}
	}

	/*
	 * puts variable to different targets depending on taskId, processInstanceId,
	 * ...
	 */
	private void setBinaryVariable(MultipartFile data, String valueType, String objectType, String taskId,
			String processInstanceId, String variableName, CIBUser user) throws IOException {
		if (objectType != null) {
			Object object = null;

			if (data.getContentType() != null
					&& data.getContentType().toLowerCase().contains(MediaType.APPLICATION_JSON.toString())) {

				byte[] bytes = IOUtils.toByteArray(data.getResource().getInputStream());
				object = deserializeJsonObject(objectType, bytes, user);

			} else {
				throw new SystemException("Unrecognized content type for serialized java type: " + data.getContentType());
			}

			if (object != null) {
				if (taskId != null)
					directProviderUtil.getProcessEngine(user).getTaskService().setVariable(taskId, variableName, Variables.objectValue(object).create());
				else if (processInstanceId != null)
					directProviderUtil.getProcessEngine(user).getRuntimeService().setVariable(processInstanceId, variableName, Variables.objectValue(object).create());
			}
		} else {

			String valueTypeName = DEFAULT_BINARY_VALUE_TYPE;
			if (valueType != null) {
				if (valueType.isBlank()) {
					throw new SystemException("Form part with name 'valueType' must have a text/plain value");
				}

				valueTypeName = valueType;
			}
			VariableValueDto valueDto = createVariableValueDto(valueTypeName, data);
			try {
				TypedValue typedValue = valueDto.toTypedValue(directProviderUtil.getProcessEngine(user), directProviderUtil.getObjectMapper(user));
				if (taskId != null)
					directProviderUtil.getProcessEngine(user).getTaskService().setVariable(taskId, variableName, typedValue);
				else if (processInstanceId != null) 
					directProviderUtil.getProcessEngine(user).getRuntimeService().setVariable(processInstanceId, variableName, typedValue);
			} catch (AuthorizationException e) {
				throw e;
			} catch (ProcessEngineException e) {
				String errorMessage = String.format("Cannot put %s variable %s: %s", "task", variableName, e.getMessage());
				throw new SystemException(errorMessage, e);
			}
		}
	}

	private Object deserializeJsonObject(String className, byte[] data, CIBUser user) {
		try {
			JavaType type = TypeFactory.defaultInstance().constructFromCanonical(className);
			validateType(type, user);
			return directProviderUtil.getObjectMapper(user).readValue(new String(data, Charset.forName("UTF-8")), type);
		} catch (Exception e) {
			throw new SystemException("Could not deserialize JSON object: " + e.getMessage());
		}
	}

	// updates execution variables
	private void updateVariableEntities(String processInstanceId, Map<String, VariableValueDto> modifications,
			List<String> deletions, CIBUser user) {
		VariableMap variableModifications = null;
		ProcessEngine processEngine = directProviderUtil.getProcessEngine(user);
		try {
			variableModifications = VariableValueDto.toMap(modifications, processEngine, directProviderUtil.getObjectMapper(user));
		} catch (RestException e) {
			String errorMessage = String.format("Cannot modify variables for %s: %s", "processInstance", e.getMessage());
			throw new SystemException(errorMessage, e);
		}
		try {
			RuntimeServiceImpl runtimeServiceImpl = (RuntimeServiceImpl) processEngine.getRuntimeService();
			runtimeServiceImpl.updateVariables(processInstanceId, variableModifications, deletions);
		} catch (AuthorizationException e) {
			throw e;
		} catch (ProcessEngineException e) {
			String errorMessage = String.format("Cannot modify variables for %s %s: %s", "processInstance", processInstanceId,
					e.getMessage());
			throw new SystemException(errorMessage, e);
		}
	}

	public Variable fetchVariableByProcessInstanceIdImpl(String processInstanceId, String variableName,
			boolean deserializeValue, CIBUser user) throws SystemException {
		TypedValue value = getTypedValueForProcessInstanceVariable(processInstanceId, variableName, deserializeValue, user);
		return directProviderUtil.convertValue(VariableValueDto.fromTypedValue(value), Variable.class, user);
	}

	private TypedValue getTypedValueForProcessInstanceVariable(String processInstanceId, String variableName,
			boolean deserializeValue, CIBUser user) {
		try {
			return directProviderUtil.getProcessEngine(user).getRuntimeService().getVariableTyped(processInstanceId, variableName, deserializeValue);
		} catch (AuthorizationException e) {
			throw e;
		} catch (ProcessEngineException e) {
			String errorMessage = String.format("Cannot get %s variable %s: %s", "task", variableName, e.getMessage());
			throw new SystemException(errorMessage, e);
		}
	}

	/**
	 * Validate the type with the help of the validator in the engine.<br>
	 * Note: when adjusting this method, please also consider adjusting the
	 * {@code JacksonJsonDataFormatMapper#validateType} in the Engine Spin Plugin
	 */
	private void validateType(JavaType type, CIBUser user) {
		if (directProviderUtil.getProcessEngine(user).getProcessEngineConfiguration().isDeserializationTypeValidationEnabled()) {
			DeserializationTypeValidator validator = directProviderUtil.getProcessEngine(user).getProcessEngineConfiguration().getDeserializationTypeValidator();
			if (validator != null) {
				List<String> invalidTypes = new ArrayList<>();
				validateType(type, validator, invalidTypes);
				if (!invalidTypes.isEmpty()) {
					throw new SystemException("The following classes are not whitelisted for deserialization: " + invalidTypes);
				}
			}
		}
	}

	private void validateType(JavaType type, DeserializationTypeValidator validator, List<String> invalidTypes) {
		if (!type.isPrimitive()) {
			if (!type.isArrayType()) {
				validateTypeInternal(type, validator, invalidTypes);
			}
			if (type.isMapLikeType()) {
				validateType(type.getKeyType(), validator, invalidTypes);
			}
			if (type.isContainerType() || type.hasContentType()) {
				validateType(type.getContentType(), validator, invalidTypes);
			}
		}
	}

	private void validateTypeInternal(JavaType type, DeserializationTypeValidator validator, List<String> invalidTypes) {
		String className = type.getRawClass().getName();
		if (!validator.validate(className) && !invalidTypes.contains(className)) {
			invalidTypes.add(className);
		}
	}

	private ResponseEntity<byte[]> getResponseForTypedVariable(TypedValue typedVariableValue, String id) {
		if (typedVariableValue instanceof BytesValue || ValueType.BYTES.equals(typedVariableValue.getType())) {
			byte[] valueBytes = (byte[]) typedVariableValue.getValue();
			if (valueBytes == null) {
				valueBytes = new byte[0];
			}
			ResponseEntity<byte[]> responseEntity = ResponseEntity.ok(valueBytes);
			return responseEntity;
		} else if (ValueType.FILE.equals(typedVariableValue.getType())) {
			FileValue typedFileValue = (FileValue) typedVariableValue;
			try {
				byte[] bytes = typedFileValue.getValue() == null ? null : IOUtils.toByteArray(typedFileValue.getValue());
				// status code if bytes==null?
				HttpHeaders headers = new HttpHeaders();
				headers.setContentType(typedFileValue.getMimeType() != null ? MediaType.valueOf(typedFileValue.getMimeType())
						: MediaType.APPLICATION_OCTET_STREAM);
				ResponseEntity<byte[]> responseEntity = ResponseEntity.ok().headers(headers).body(bytes);
				return responseEntity;
			} catch (IOException e) {
				throw new SystemException(e.getMessage(), e);
			}
		} else {
			throw new SystemException(String.format("Value of variable with id %s is not a binary value.", id));
		}
	}

	private List<VariableHistory> queryHistoricVariableInstances(HistoricVariableInstanceQueryDto queryDto,
			Integer firstResult, Integer maxResults, boolean deserializeObjectValues, CIBUser user) {
		queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
		HistoricVariableInstanceQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));
		query.disableBinaryFetching();

		if (!deserializeObjectValues) {
			query.disableCustomObjectDeserialization();
		}

		List<HistoricVariableInstance> matchingHistoricVariableInstances = QueryUtil.list(query, firstResult, maxResults);
		List<VariableHistory> historicVariableInstanceDtoResults = new ArrayList<>();
		for (HistoricVariableInstance historicVariableInstance : matchingHistoricVariableInstances) {
			HistoricVariableInstanceDto resultHistoricVariableInstance = HistoricVariableInstanceDto
					.fromHistoricVariableInstance(historicVariableInstance);
			historicVariableInstanceDtoResults.add(directProviderUtil.convertValue(resultHistoricVariableInstance, VariableHistory.class, user));
		}
		return historicVariableInstanceDtoResults;
	}

	private Variable fetchTaskVariableImpl(String taskId, String variableName, boolean deserializeValue, CIBUser user)
			throws NoObjectFoundException, SystemException {
		TypedValue value = directProviderUtil.getTypedValueForTaskVariable(taskId, variableName, deserializeValue, user);
		return directProviderUtil.convertValue(VariableValueDto.fromTypedValue(value), Variable.class, user);
	}

	private VariableValueDto createVariableValueDto(String valueTypeName, MultipartFile data) throws IOException {
		VariableValueDto valueDto = new VariableValueDto();
		valueDto.setType(valueTypeName);
		valueDto.setValue(data.getBytes());

		String contentType = data.getContentType();
		if (contentType == null) {
			contentType = MediaType.APPLICATION_OCTET_STREAM.toString();
		}

		Map<String, Object> valueInfoMap = new HashMap<>();
		valueInfoMap.put(FileValueType.VALUE_INFO_FILE_NAME, data.getResource().getFilename());
		MimeType mimeType = null;
		try {
			mimeType = new MimeType(contentType);
		} catch (MimeTypeParseException e) {
			throw new SystemException("Invalid mime type given");
		}

		valueInfoMap.put(FileValueType.VALUE_INFO_FILE_MIME_TYPE, mimeType.getBaseType());

		String encoding = mimeType.getParameter("encoding");
		if (encoding != null) {
			valueInfoMap.put(FileValueType.VALUE_INFO_FILE_ENCODING, encoding);
		}

		String transientString = mimeType.getParameter("transient");
		boolean isTransient = Boolean.parseBoolean(transientString);
		if (isTransient) {
			valueInfoMap.put(AbstractValueTypeImpl.VALUE_INFO_TRANSIENT, isTransient);
		}
		valueDto.setValueInfo(valueInfoMap);
		return valueDto;
	}

	private List<VariableHistory> queryHistoricVariableInstances(HistoricVariableInstanceQueryDto queryDto,
			ObjectMapper objectMapper, Integer firstResult, Integer maxResults, boolean deserializeObjectValues, CIBUser user) {
		// change to history query!!
		HistoricVariableInstanceQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));

		// disable binary fetching by default.
		query.disableBinaryFetching();

		// disable custom object fetching by default. Cannot be done to not break
		// existing API
		if (!deserializeObjectValues) {
			query.disableCustomObjectDeserialization();
		}

		List<HistoricVariableInstance> matchingInstances = QueryUtil.list(query, firstResult, maxResults);

		List<VariableHistory> instanceResults = new ArrayList<>();
		for (HistoricVariableInstance instance : matchingInstances) {
			HistoricVariableInstanceDto resultInstanceDto = HistoricVariableInstanceDto.fromHistoricVariableInstance(instance);
			VariableHistory resultInstance = directProviderUtil.convertValue(resultInstanceDto, VariableHistory.class, user);
			instanceResults.add(resultInstance);
		}
		return instanceResults;
	}

	@Override
	public Map<String, Variable> fetchProcessFormVariables(List<String> variableListName, String processDefinitionId,
			boolean deserializeValues, CIBUser user) throws NoObjectFoundException, SystemException {
		VariableMap startFormVariables = directProviderUtil.getProcessEngine(user).getFormService()
				.getStartFormVariables(processDefinitionId, variableListName, deserializeValues);
		Map<String, VariableValueDto> variableDtos = VariableValueDto.fromMap(startFormVariables);
		Map<String, Variable> variablesMap = new HashMap<>();
		for (Entry<String, VariableValueDto> e : variableDtos.entrySet()) {
			variablesMap.put(e.getKey(), directProviderUtil.convertValue(e.getValue(), Variable.class, user));
		}
		return variablesMap;
	}
}
