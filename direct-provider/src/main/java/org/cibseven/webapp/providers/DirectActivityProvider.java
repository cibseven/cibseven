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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.exception.NotFoundException;
import org.cibseven.bpm.engine.history.HistoricActivityInstance;
import org.cibseven.bpm.engine.history.HistoricActivityInstanceQuery;
import org.cibseven.bpm.engine.rest.dto.history.HistoricActivityInstanceDto;
import org.cibseven.bpm.engine.rest.dto.history.HistoricActivityInstanceQueryDto;
import org.cibseven.bpm.engine.rest.dto.runtime.ActivityInstanceDto;
import org.cibseven.bpm.engine.rest.util.QueryUtil;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.NoObjectFoundException;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.ActivityInstance;
import org.cibseven.webapp.rest.model.ActivityInstanceHistory;

public class DirectActivityProvider implements IActivityProvider {

	DirectProviderUtil directProviderUtil;
	public DirectActivityProvider(DirectProviderUtil directProviderUtil){
		this.directProviderUtil = directProviderUtil;
	}

	@Override
	public ActivityInstance findActivityInstance(String processInstanceId, CIBUser user) {
		org.cibseven.bpm.engine.runtime.ActivityInstance activityInstance = null;
		try {
			activityInstance = directProviderUtil.getProcessEngine(user).getRuntimeService().getActivityInstance(processInstanceId);
		} catch (AuthorizationException e) {
			throw e;
		} catch (ProcessEngineException e) {
			throw new SystemException(e.getMessage(), e);
		}

		if (activityInstance == null) {
			throw new NoObjectFoundException(new SystemException("Process instance with id " + processInstanceId + " does not exist"));
		}

		ActivityInstanceDto result = ActivityInstanceDto.fromActivityInstance(activityInstance);
		return directProviderUtil.convertValue(result, ActivityInstance.class, user);
	}

	@Override
	public List<ActivityInstanceHistory> findActivitiesInstancesHistory(Map<String, Object> queryParams, CIBUser user) {
		// Paging is no filter: the query DTO has no field for it, so it is taken out and applied to
		// the query, the way engine-rest reads it from the request
		Map<String, Object> filters = new HashMap<>(queryParams);
		Integer firstResult = toInteger(filters.remove("firstResult"));
		Integer maxResults = toInteger(filters.remove("maxResults"));
		HistoricActivityInstanceQueryDto queryHistoricActivityInstanceDto = directProviderUtil.getObjectMapper(user).convertValue(filters,
				HistoricActivityInstanceQueryDto.class);
		return queryHistoricActivityInstance(queryHistoricActivityInstanceDto, firstResult, maxResults, user);
	}

	private static Integer toInteger(Object value) {
		if (value == null || value.toString().isBlank()) return null;
		return value instanceof Number number ? number.intValue() : Integer.valueOf(value.toString().trim());
	}

	@Override
	public List<ActivityInstanceHistory> findActivitiesInstancesHistory(String processInstanceId, CIBUser user) {
		HistoricActivityInstanceQueryDto queryHistoricActivityInstanceDto = new HistoricActivityInstanceQueryDto();
		queryHistoricActivityInstanceDto.setProcessInstanceId(processInstanceId);
		return queryHistoricActivityInstance(queryHistoricActivityInstanceDto, user);
	}

	@Override
	public ActivityInstance findActivityInstances(String processInstanceId, CIBUser user) throws SystemException {

		org.cibseven.bpm.engine.runtime.ActivityInstance activityInstance = null;

		try {
			activityInstance = directProviderUtil.getProcessEngine(user).getRuntimeService().getActivityInstance(processInstanceId);
		} catch (AuthorizationException e) {
			throw e;
		} catch (ProcessEngineException e) {
			throw new SystemException(e.getMessage(), e);
		}

		if (activityInstance == null) {
			throw new NoObjectFoundException(new SystemException("Process instance with id " + processInstanceId + " does not exist"));
		}

		ActivityInstanceDto result = ActivityInstanceDto.fromActivityInstance(activityInstance);
		return directProviderUtil.convertValue(result, ActivityInstance.class, user);
	}

	@Override
	public List<ActivityInstanceHistory> findActivityInstanceHistory(String processInstanceId, CIBUser user)
			throws SystemException {

		HistoricActivityInstanceQueryDto queryHistoricActivityInstanceDto = new HistoricActivityInstanceQueryDto();
		queryHistoricActivityInstanceDto.setProcessInstanceId(processInstanceId);
		return queryHistoricActivityInstance(queryHistoricActivityInstanceDto, user);
	}

	@Override
	public void deleteVariableByExecutionId(String executionId, String variableName, CIBUser user) {
		try {
			directProviderUtil.getProcessEngine(user).getRuntimeService().removeVariableLocal(executionId, variableName);
		} catch (AuthorizationException e) {
			throw e;
		} catch (ProcessEngineException e) {
			String errorMessage = String.format("Cannot delete %s variable %s: %s", executionId, variableName, e.getMessage());
			throw new SystemException(errorMessage, e);
		}

	}

	@Override
	public void deleteVariableHistoryInstance(String id, CIBUser user) {
		try {
			directProviderUtil.getProcessEngine(user).getHistoryService().deleteHistoricVariableInstance(id);
		} catch (NotFoundException nfe) { // rewrite status code from bad request
																			// (400) to not found (404)
			throw new SystemException(nfe.getMessage(), nfe);
		}
	}

	@Override
	public Collection<ActivityInstanceHistory> findActivitiesProcessDefinitionHistory(String processDefinitionId,
			Map<String, Object> params, CIBUser user) {
		HistoricActivityInstanceQueryDto queryHistoricActivityInstanceDto = directProviderUtil.getObjectMapper(user).convertValue(params,
				HistoricActivityInstanceQueryDto.class);
		queryHistoricActivityInstanceDto.setProcessDefinitionId(processDefinitionId);
		return queryHistoricActivityInstance(queryHistoricActivityInstanceDto, user);

	}

	private List<ActivityInstanceHistory> queryHistoricActivityInstance(
			HistoricActivityInstanceQueryDto queryHistoricActivityInstanceDto, CIBUser user) {
		return queryHistoricActivityInstance(queryHistoricActivityInstanceDto, null, null, user);
	}

	private List<ActivityInstanceHistory> queryHistoricActivityInstance(
			HistoricActivityInstanceQueryDto queryHistoricActivityInstanceDto, Integer firstResult, Integer maxResults, CIBUser user) {
		queryHistoricActivityInstanceDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
		HistoricActivityInstanceQuery query = queryHistoricActivityInstanceDto.toQuery(directProviderUtil.getProcessEngine(user));
		List<HistoricActivityInstance> matchingHistoricActivityInstances = QueryUtil.list(query, firstResult, maxResults);

		List<ActivityInstanceHistory> historicActivityInstanceResults = new ArrayList<>();
		for (HistoricActivityInstance historicActivityInstance : matchingHistoricActivityInstances) {
			HistoricActivityInstanceDto resultHistoricActivityInstance = new HistoricActivityInstanceDto();
			HistoricActivityInstanceDto.fromHistoricActivityInstance(resultHistoricActivityInstance, historicActivityInstance);
			historicActivityInstanceResults.add(directProviderUtil.convertValue(resultHistoricActivityInstance, ActivityInstanceHistory.class, user));
		}
		return historicActivityInstanceResults;
	}

}
