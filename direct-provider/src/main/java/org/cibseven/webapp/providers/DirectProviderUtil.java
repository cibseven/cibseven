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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.cibseven.bpm.BpmPlatform;
import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.Tenant;
import org.cibseven.bpm.engine.impl.identity.Authentication;
import org.cibseven.bpm.engine.rest.dto.VariableValueDto;
import org.cibseven.bpm.engine.rest.dto.runtime.VariableInstanceDto;
import org.cibseven.bpm.engine.rest.dto.runtime.VariableInstanceQueryDto;
import org.cibseven.bpm.engine.rest.exception.RestException;
import org.cibseven.bpm.engine.rest.mapper.JacksonConfigurator;
import org.cibseven.bpm.engine.rest.util.QueryUtil;
import org.cibseven.bpm.engine.runtime.VariableInstance;
import org.cibseven.bpm.engine.runtime.VariableInstanceQuery;
import org.cibseven.bpm.engine.variable.value.TypedValue;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.Variable;
import org.cibseven.webapp.rest.model.VariableHistory;
import org.cibseven.bpm.engine.rest.util.EngineUtil;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Setter;

public class DirectProviderUtil {

	private static final String FIRST_RESULT_PARAM = "firstResult";
	private static final String MAX_RESULTS_PARAM = "maxResults";

	protected Map<String, ProcessEngine> processEngines = new HashMap<>();
	protected Map<String, ObjectMapper> objectMappers = new HashMap<>();
	@Setter
	protected IEngineProvider engineProvider;

	protected ProcessEngine getProcessEngine(String engineId) {
		ProcessEngine processEngine = null;
		if (processEngines.containsKey(engineId))
			processEngine = processEngines.get(engineId);
		else {
			// either one of the two methods can be used to lookup the process engine - the other might fail for unknown reasons
			try {
				processEngine = EngineUtil.lookupProcessEngine(engineId);
			} catch (RestException ex) {
				processEngine = BpmPlatform.getProcessEngineService().getProcessEngine(engineId);
			} finally {
				if (processEngine == null)
					throw new SystemException("No process engine found with name " + engineId);
			}
			processEngines.put(engineId, processEngine);
			ObjectMapper objectMapper = new ObjectMapper();
			JacksonConfigurator.configureObjectMapper(objectMapper);
			objectMappers.put(engineId, objectMapper);
		}
		return processEngine;
	}

	protected ProcessEngine getProcessEngine(CIBUser user) {
		return getProcessEngine(getEngineId(user));
	}

	protected ObjectMapper getObjectMapper(CIBUser user) {
		String engineId = getEngineId(user);
		getProcessEngine(engineId);
		return objectMappers.get(engineId);
	}

	protected ObjectMapper getObjectMapper(String engineId) {
		getProcessEngine(engineId);
		return objectMappers.get(engineId);
	}

	protected String getEngineId(CIBUser user) {
		String engineId = user != null ? user.getEngine() : null;
		// If engine name is provided and not "default", add it to the path
		if (engineId == null || engineId.isEmpty())
			engineId = engineProvider.getEffectiveDefaultEngineId();
		return engineId;
	}

	/**
	 * conversion and helper functions
	 */
	protected <T> T convertValue(Object fromValueDto, Class<T> toValueType, CIBUser user) throws IllegalArgumentException {
		ObjectMapper objectMapper = getObjectMapper(user);
		Map<String, Object> filterDtoMap = objectMapper.convertValue(fromValueDto, new TypeReference<Map<String, Object>>() {
		});
		return objectMapper.convertValue(filterDtoMap, toValueType);
	}

	/**
	 * Converts a submitted form variable into the DTO the engine's {@code VariableValueDto.toMap}
	 * expects, applying the same adjustments {@code VariableProvider.submitVariables} makes before
	 * posting to engine-rest:
	 * <ul>
	 * <li>{@code json} and {@code Object} values that arrive as parsed JSON are serialized to a
	 * string, because the engine only accepts a string or {@code null} for serializable types</li>
	 * <li>{@code .rtf} files get the {@code application/rtf} mime type (BPM4CIB-434)</li>
	 * </ul>
	 */
	protected VariableValueDto toVariableValueDto(Variable variable, CIBUser user) {
		VariableValueDto variableValueDto = convertValue(variable, VariableValueDto.class, user);
		String type = variable.getType();
		Object value = variable.getValue();
		Map<String, Object> valueInfo = variable.getValueInfo() != null ? new HashMap<>(variable.getValueInfo()) : null;

		if (value != null && !(value instanceof String) && ("json".equalsIgnoreCase(type) || "Object".equalsIgnoreCase(type))) {
			try {
				value = getObjectMapper(user).writeValueAsString(value);
			} catch (JsonProcessingException e) {
				throw new SystemException("Cannot serialize value of variable " + variable.getName(), e);
			}
		}

		//https://helpdesk.cib.de/browse/BPM4CIB-434
		String filename = valueInfo != null ? (String) valueInfo.get("filename") : null;
		if ("file".equalsIgnoreCase(type) && filename != null && filename.endsWith(".rtf") && filename.length() > 4) {
			valueInfo.put("mimeType", "application/rtf");
		}

		variableValueDto.setType(type);
		variableValueDto.setValue(value);
		if (valueInfo != null)
			variableValueDto.setValueInfo(valueInfo);
		return variableValueDto;
	}

	protected <V extends Object> V runWithoutAuthorization(Supplier<V> action, CIBUser user) {
		IdentityService identityService = getProcessEngine(user).getIdentityService();
		Authentication currentAuthentication = identityService.getCurrentAuthentication();
		try {
			identityService.clearAuthentication();
			return action.get();
		} finally {
			identityService.setAuthentication(currentAuthentication);
		}
	}

	/**
	 * Executes {@code action} with the given user authenticated on the engine, resolving the user's
	 * groups and tenants from the identity service. Where authorization is enabled, this is what lets
	 * the engine enforce its authorizations for the operation.
	 *
	 * <p>Set regardless of whether authorization is enabled: the engine also needs the acting user to
	 * write the user operation log and to resolve {@code ${currentUser()}} in filter expressions.
	 */
	protected <V extends Object> V runAsUser(CIBUser user, Supplier<V> action) {
		if (user == null || user.getId() == null) {
			return action.get();
		}
		IdentityService identityService = getProcessEngine(user).getIdentityService();
		Authentication previousAuthentication = identityService.getCurrentAuthentication();
		try {
			identityService.setAuthentication(user.getId(), getGroupsOfUser(user), getTenantsOfUser(user));
			return action.get();
		} finally {
			//Authentication set from another call will be restored
			identityService.setAuthentication(previousAuthentication);
		}
	}

	/**
	 * Void variant of {@link #runAsUser(CIBUser, Supplier)} for operations that do not return a value.
	 */
	protected void runAsUser(CIBUser user, Runnable action) {
		runAsUser(user, () -> {
			action.run();
			return null;
		});
	}

	protected List<String> getGroupsOfUser(CIBUser user) {
		List<Group> groups = getProcessEngine(user).getIdentityService().createGroupQuery()
				.groupMember(user.getId())
				.list();
		List<String> groupIds = new ArrayList<>();
		for (Group group : groups) {
			groupIds.add(group.getId());
		}
		return groupIds;
	}

	protected List<String> getTenantsOfUser(CIBUser user) {
		List<Tenant> tenants = getProcessEngine(user).getIdentityService().createTenantQuery()
				.userMember(user.getId())
				.includingGroupsOfUser(true)
				.list();
		List<String> tenantIds = new ArrayList<>();
		for (Tenant tenant : tenants) {
			tenantIds.add(tenant.getId());
		}
		return tenantIds;
	}

	public List<Variable> queryVariableInstances(VariableInstanceQueryDto queryDto, Integer firstResult,
			Integer maxResults, boolean deserializeObjectValues, CIBUser user) {
		VariableInstanceQuery query = queryDto.toQuery(getProcessEngine(user));

		// disable binary fetching by default.
		query.disableBinaryFetching();

		// disable custom object fetching by default. Cannot be done to not break
		// existing API
		if (!deserializeObjectValues) {
			query.disableCustomObjectDeserialization();
		}

		List<VariableInstance> matchingInstances = QueryUtil.list(query, firstResult,
				maxResults);

		List<Variable> instanceResults = new ArrayList<>();
		for (VariableInstance instance : matchingInstances) {
			VariableInstanceDto resultInstanceDto = VariableInstanceDto.fromVariableInstance(instance);
			VariableHistory resultInstance = convertValue(resultInstanceDto, VariableHistory.class, user);
			instanceResults.add(resultInstance);
		}
		return instanceResults;
	}

	public TypedValue getTypedValueForTaskVariable(String taskId, String variableName, boolean deserializeValue, CIBUser user) {
		TypedValue value = null;
		try {
			value = getProcessEngine(user).getTaskService().getVariableTyped(taskId, variableName, deserializeValue);
		} catch (AuthorizationException e) {
			throw e;
		} catch (ProcessEngineException e) {
			String errorMessage = String.format("Cannot get %s variable %s: %s", "task", variableName, e.getMessage());
			throw new SystemException(errorMessage, e);
		}

		if (value == null) {
			String errorMessage = String.format("%s variable with name %s does not exist", "task", variableName);
			throw new SystemException(errorMessage);
		}
		return value;
	}

	protected Integer getFirstResult(Map<String, Object> params) {
		return getPagingParam(params, FIRST_RESULT_PARAM);
	}

	protected Integer getMaxResults(Map<String, Object> params) {
		return getPagingParam(params, MAX_RESULTS_PARAM);
	}

	/**
	 * Copy of the request parameters without the paging keys, so that they are not
	 * offered to the query DTO as filter criteria.
	 */
	protected Map<String, Object> withoutPagingParams(Map<String, Object> params) {
		Map<String, Object> queryParams = new HashMap<>(params);
		queryParams.remove(FIRST_RESULT_PARAM);
		queryParams.remove(MAX_RESULTS_PARAM);
		return queryParams;
	}

	/**
	 * Values arrive as strings via {@code @RequestParam}, but internal callers may
	 * pass numbers.
	 */
	private Integer getPagingParam(Map<String, Object> params, String name) {
		Object value = params.get(name);
		if (value == null)
			return null;
		if (value instanceof Number)
			return ((Number) value).intValue();
		String text = value.toString().trim();
		return text.isEmpty() ? null : Integer.valueOf(text);
	}
}
