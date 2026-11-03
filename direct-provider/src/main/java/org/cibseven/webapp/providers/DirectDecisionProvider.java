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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.io.IOUtils;
import org.cibseven.bpm.dmn.engine.DmnDecisionResult;
import org.cibseven.bpm.dmn.engine.DmnDecisionResultEntries;
import org.cibseven.bpm.dmn.engine.DmnEngineException;
import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.exception.NotFoundException;
import org.cibseven.bpm.engine.exception.NotValidException;
import org.cibseven.bpm.engine.history.HistoricDecisionInstanceQuery;
import org.cibseven.bpm.engine.history.SetRemovalTimeSelectModeForHistoricDecisionInstancesBuilder;
import org.cibseven.bpm.engine.impl.util.IoUtil;
import org.cibseven.bpm.engine.repository.DecisionDefinition;
import org.cibseven.bpm.engine.repository.DecisionDefinitionQuery;
import org.cibseven.bpm.engine.rest.dto.HistoryTimeToLiveDto;
import org.cibseven.bpm.engine.rest.dto.VariableValueDto;
import org.cibseven.bpm.engine.rest.dto.batch.BatchDto;
import org.cibseven.bpm.engine.rest.dto.dmn.EvaluateDecisionDto;
import org.cibseven.bpm.engine.rest.dto.history.HistoricDecisionInstanceDto;
import org.cibseven.bpm.engine.rest.dto.history.HistoricDecisionInstanceQueryDto;
import org.cibseven.bpm.engine.rest.dto.history.batch.DeleteHistoricDecisionInstancesDto;
import org.cibseven.bpm.engine.rest.dto.history.batch.removaltime.SetRemovalTimeToHistoricDecisionInstancesDto;
import org.cibseven.bpm.engine.rest.dto.repository.DecisionDefinitionDiagramDto;
import org.cibseven.bpm.engine.rest.dto.repository.DecisionDefinitionDto;
import org.cibseven.bpm.engine.rest.dto.repository.DecisionDefinitionQueryDto;
import org.cibseven.bpm.engine.rest.util.QueryUtil;
import org.cibseven.bpm.engine.variable.VariableMap;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.TypedValue;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.NoObjectFoundException;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.Decision;
import org.cibseven.webapp.rest.model.HistoricDecisionInstance;

public class DirectDecisionProvider implements IDecisionProvider {

	DirectProviderUtil directProviderUtil;

	DirectDecisionProvider(DirectProviderUtil directProviderUtil){
		this.directProviderUtil = directProviderUtil;
	}

	@Override
	public Collection<Decision> getDecisionDefinitionList(Map<String, Object> queryParams, CIBUser user) {
		DecisionDefinitionQueryDto queryDto = directProviderUtil.getObjectMapper(user).convertValue(queryParams, DecisionDefinitionQueryDto.class);
		List<Decision> definitions = new ArrayList<>();
		DecisionDefinitionQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));
		List<DecisionDefinition> matchingDefinitions = QueryUtil.list(query, null, null);
		for (DecisionDefinition definition : matchingDefinitions) {
			DecisionDefinitionDto def = DecisionDefinitionDto.fromDecisionDefinition(definition);
			definitions.add(directProviderUtil.convertValue(def, Decision.class, user));
		}
		return definitions;
	}

	@Override
	public Long getDecisionDefinitionListCount(Map<String, Object> queryParams, CIBUser user) {
		DecisionDefinitionQueryDto queryDto = directProviderUtil.getObjectMapper(user).convertValue(queryParams, DecisionDefinitionQueryDto.class);
		DecisionDefinitionQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));
		return query.count();
	}

	@Override
	public Decision getDecisionDefinitionByKey(String key, CIBUser user) {
		DecisionDefinition decisionDefinition = getDecisionDefinitionByKeyAndTenantImpl(key, null, user);
		return directProviderUtil.convertValue(DecisionDefinitionDto.fromDecisionDefinition(decisionDefinition), Decision.class, user);
	}

	@Override
	public Object getDiagramByKey(String key, CIBUser user) {
		return getDiagramByKeyAndTenant(key, null, user);
	}

	@Override
	public Object evaluateDecisionDefinitionByKey(Map<String, Object> data, String key, CIBUser user) {
		DecisionDefinition decisionDefinition = getDecisionDefinitionByKeyAndTenantImpl(key, null, user);

		return evaluateDecisionDefinitionById(decisionDefinition.getId(), data, user);
	}

	@Override
	public void updateHistoryTTLByKey(Map<String, Object> data, String key, CIBUser user) {

		HistoryTimeToLiveDto historyTimeToLiveDto = directProviderUtil.getObjectMapper(user).convertValue(data, HistoryTimeToLiveDto.class);
		DecisionDefinition decisionDefinition = getDecisionDefinitionByKeyAndTenantImpl(key, null, user);
		directProviderUtil.getProcessEngine(user).getRepositoryService().updateDecisionDefinitionHistoryTimeToLive(decisionDefinition.getId(),
				historyTimeToLiveDto.getHistoryTimeToLive());
	}

	@Override
	public Decision getDecisionDefinitionByKeyAndTenant(String key, String tenant, CIBUser user) {
		DecisionDefinition decisionDefinition = getDecisionDefinitionByKeyAndTenantImpl(key, tenant, user);
		DecisionDefinitionDto dto = DecisionDefinitionDto.fromDecisionDefinition(decisionDefinition);
		return directProviderUtil.convertValue(dto, Decision.class, user);
	}

	@Override
	public Object getDiagramByKeyAndTenant(String key, String tenant, CIBUser user) {
		DecisionDefinition decisionDefinition = getDecisionDefinitionByKeyAndTenantImpl(key, tenant, user);
		return getDiagramByDecisionDefinition(decisionDefinition, user);
	}

	@Override
	public Object evaluateDecisionDefinitionByKeyAndTenant(Map<String, Object> data, String key, String tenant, CIBUser user) {
		DecisionDefinition decisionDefinition = getDecisionDefinitionByKeyAndTenantImpl(key, tenant, user);

		return evaluateDecisionDefinitionById(decisionDefinition.getId(), data, user);
	}

	@Override
	public void updateHistoryTTLByKeyAndTenant(Map<String, Object> data, String key, String tenant, CIBUser user) {
		DecisionDefinition decisionDefinition = getDecisionDefinitionByKeyAndTenantImpl(key, tenant, user);
		updateHistoryTTLById(decisionDefinition.getId(), data, user);
	}

	@Override
	public Object getXmlByKey(String key, CIBUser user) {
		return getXmlByKeyAndTenant(key, null, user);
	}

	@Override
	public Object getXmlByKeyAndTenant(String key, String tenant, CIBUser user) {
		DecisionDefinition decisionDefinition = getDecisionDefinitionByKeyAndTenantImpl(key, tenant, user);
		return getXmlByDefinitionId(decisionDefinition.getId(), user);
	}

	@Override
	public Decision getDecisionDefinitionById(String id, Optional<Boolean> extraInfo, CIBUser user) {
		DecisionDefinition definition = getDecisionDefinitionById(id, user);
		Decision decision = directProviderUtil.convertValue(DecisionDefinitionDto.fromDecisionDefinition(definition), Decision.class, user);
		if (extraInfo.isPresent() && extraInfo.get()) {
			Map<String, Object> queryParams = new HashMap<>();
			queryParams.put("decisionDefinitionId", definition.getId());
			Long count = getHistoricDecisionInstanceCount(queryParams, user);
			decision.setAllInstances(count);
		}
		return decision;
	}

	@Override
	public Object getDiagramById(String id, CIBUser user) {
		DecisionDefinition definition = getDecisionDefinitionById(id, user);
		return getDiagramByDecisionDefinition(definition, user);
	}

	@Override
	public Object evaluateDecisionDefinitionById(String id, Map<String, Object> data, CIBUser user) {
		try {
			EvaluateDecisionDto parameters = directProviderUtil.getObjectMapper(user).convertValue(data, EvaluateDecisionDto.class);
			Map<String, Object> variables = VariableValueDto.toMap(parameters.getVariables(), directProviderUtil.getProcessEngine(user), directProviderUtil.getObjectMapper(user));
			DmnDecisionResult decisionResult = directProviderUtil.getProcessEngine(user).getDecisionService().evaluateDecisionById(id)
					.variables(variables).evaluate();

			List<Map<String, VariableValueDto>> dto = new ArrayList<>();

			for (DmnDecisionResultEntries entries : decisionResult) {
				Map<String, VariableValueDto> resultEntriesDto = createResultEntriesDto(entries);
				dto.add(resultEntriesDto);
			}
			return dto;

		} catch (AuthorizationException e) {
			throw e;
		} catch (NotFoundException e) {
			String errorMessage = String.format("Cannot evaluate decision %s: %s", id, e.getMessage());
			throw new SystemException(errorMessage, e);
		} catch (NotValidException e) {
			String errorMessage = String.format("Cannot evaluate decision %s: %s", id, e.getMessage());
			throw new SystemException(errorMessage, e);
		} catch (ProcessEngineException e) {
			String errorMessage = String.format("Cannot evaluate decision %s: %s", id, e.getMessage());
			throw new SystemException(errorMessage, e);
		} catch (DmnEngineException e) {
			String errorMessage = String.format("Cannot evaluate decision %s: %s", id, e.getMessage());
			throw new SystemException(errorMessage, e);
		}
	}

	@Override
	public void updateHistoryTTLById(String id, Map<String, Object> data, CIBUser user) {
		HistoryTimeToLiveDto historyTimeToLiveDto = directProviderUtil.getObjectMapper(user).convertValue(data, HistoryTimeToLiveDto.class);
		directProviderUtil.getProcessEngine(user).getRepositoryService().updateDecisionDefinitionHistoryTimeToLive(id, historyTimeToLiveDto.getHistoryTimeToLive());
	}

	@Override
	public Object getXmlById(String id, CIBUser user) {
		return getXmlByDefinitionId(id, user);
	}

	@Override
	public Collection<Decision> getDecisionVersionsByKey(String key, Optional<Boolean> lazyLoad, CIBUser user) {
		List<DecisionDefinition> decisionDefinitions = directProviderUtil.getProcessEngine(user).getRepositoryService().createDecisionDefinitionQuery()
				.decisionDefinitionKey(key).withoutTenantId().unlimitedList();

		if (decisionDefinitions == null || decisionDefinitions.isEmpty()) {
			String errorMessage = String.format("No matching decision definition with key: %s and no tenant-id", key);
			throw new SystemException(errorMessage);
		}
		List<Decision> decisions = new ArrayList<>();
		for (DecisionDefinition decisionDefinition : decisionDefinitions) {
			DecisionDefinitionDto decisionDefinitionDto = DecisionDefinitionDto.fromDecisionDefinition(decisionDefinition);
			decisions.add(directProviderUtil.convertValue(decisionDefinitionDto, Decision.class, user));
		}
		return decisions;
	}

	@Override
	public Collection<HistoricDecisionInstance> getHistoricDecisionInstances(Map<String, Object> queryParams,
			CIBUser user) {
		HistoricDecisionInstanceQueryDto queryHistoricDecisionInstanceDto = directProviderUtil.getObjectMapper(user).convertValue(queryParams,
				HistoricDecisionInstanceQueryDto.class);
		HistoricDecisionInstanceQuery query = queryHistoricDecisionInstanceDto.toQuery(directProviderUtil.getProcessEngine(user));

		List<org.cibseven.bpm.engine.history.HistoricDecisionInstance> matchingHistoricDecisionInstances = QueryUtil
				.list(query, null, null);

		List<HistoricDecisionInstance> historicDecisionInstanceDtoResults = new ArrayList<>();
		for (org.cibseven.bpm.engine.history.HistoricDecisionInstance historicDecisionInstance : matchingHistoricDecisionInstances) {
			HistoricDecisionInstanceDto resultHistoricDecisionInstanceDto = HistoricDecisionInstanceDto
					.fromHistoricDecisionInstance(historicDecisionInstance);
			historicDecisionInstanceDtoResults
					.add(directProviderUtil.convertValue(resultHistoricDecisionInstanceDto, HistoricDecisionInstance.class, user));
		}
		return historicDecisionInstanceDtoResults;
	}

	@Override
	public Long getHistoricDecisionInstanceCount(Map<String, Object> queryParams, CIBUser user) {
		HistoricDecisionInstanceQueryDto queryHistoricDecisionInstanceDto = directProviderUtil.getObjectMapper(user).convertValue(queryParams,
				HistoricDecisionInstanceQueryDto.class);
		HistoricDecisionInstanceQuery query = queryHistoricDecisionInstanceDto.toQuery(directProviderUtil.getProcessEngine(user));
		return query.count();
	}

	@Override
	public HistoricDecisionInstance getHistoricDecisionInstanceById(String id, Map<String, Object> queryParams,
			CIBUser user) {

		HistoricDecisionInstanceQueryDto historicDecisionInstanceQueryDto = directProviderUtil.getObjectMapper(user).convertValue(queryParams,
				HistoricDecisionInstanceQueryDto.class);
		historicDecisionInstanceQueryDto.setDecisionInstanceId(id);
		HistoricDecisionInstanceQuery query = historicDecisionInstanceQueryDto.toQuery(directProviderUtil.getProcessEngine(user));
		org.cibseven.bpm.engine.history.HistoricDecisionInstance instance = query.singleResult();

		if (instance == null) {
			throw new NoObjectFoundException(new SystemException("Historic decision instance with id '" + id + "' does not exist"));
		}

		return directProviderUtil.convertValue(HistoricDecisionInstanceDto.fromHistoricDecisionInstance(instance),
				HistoricDecisionInstance.class, user);
	}

	@Override
	public Object deleteHistoricDecisionInstances(Map<String, Object> data, CIBUser user) {

		DeleteHistoricDecisionInstancesDto dto = directProviderUtil.getObjectMapper(user).convertValue(data, DeleteHistoricDecisionInstancesDto.class);
		HistoricDecisionInstanceQuery decisionInstanceQuery = null;
		if (dto.getHistoricDecisionInstanceQuery() != null) {
			decisionInstanceQuery = dto.getHistoricDecisionInstanceQuery().toQuery(directProviderUtil.getProcessEngine(user));
		}

		try {
			List<String> historicDecisionInstanceIds = dto.getHistoricDecisionInstanceIds();
			String deleteReason = dto.getDeleteReason();
			org.cibseven.bpm.engine.batch.Batch batch = directProviderUtil.getProcessEngine(user).getHistoryService()
					.deleteHistoricDecisionInstancesAsync(historicDecisionInstanceIds, decisionInstanceQuery, deleteReason);
			return BatchDto.fromBatch(batch);
		} catch (BadUserRequestException e) {
			throw new SystemException(e.getMessage());
		}
	}

	@Override
	public Object setHistoricDecisionInstanceRemovalTime(Map<String, Object> data, CIBUser user) {
		SetRemovalTimeToHistoricDecisionInstancesDto dto = directProviderUtil.getObjectMapper(user).convertValue(data,
				SetRemovalTimeToHistoricDecisionInstancesDto.class);

		HistoricDecisionInstanceQuery historicDecisionInstanceQuery = null;

		if (dto.getHistoricDecisionInstanceQuery() != null) {
			historicDecisionInstanceQuery = dto.getHistoricDecisionInstanceQuery().toQuery(directProviderUtil.getProcessEngine(user));

		}

		SetRemovalTimeSelectModeForHistoricDecisionInstancesBuilder builder = directProviderUtil.getProcessEngine(user).getHistoryService()
				.setRemovalTimeToHistoricDecisionInstances();

		if (dto.isCalculatedRemovalTime()) {
			builder.calculatedRemovalTime();

		}

		Date removalTime = dto.getAbsoluteRemovalTime();
		if (dto.getAbsoluteRemovalTime() != null) {
			builder.absoluteRemovalTime(removalTime);

		}

		if (dto.isClearedRemovalTime()) {
			builder.clearedRemovalTime();

		}

		builder.byIds(dto.getHistoricDecisionInstanceIds());
		builder.byQuery(historicDecisionInstanceQuery);

		if (dto.isHierarchical()) {
			builder.hierarchical();

		}

		org.cibseven.bpm.engine.batch.Batch batch = builder.executeAsync();
		return BatchDto.fromBatch(batch);
	}

	private DecisionDefinition getDecisionDefinitionByKeyAndTenantImpl(String key, String tenantId, CIBUser user) {
		DecisionDefinitionQuery query = directProviderUtil.getProcessEngine(user).getRepositoryService().createDecisionDefinitionQuery().decisionDefinitionKey(key);
		if (tenantId == null)
			query.withoutTenantId();
		else
			query.tenantIdIn(new String[] { tenantId });

		DecisionDefinition decisionDefinition = query.latestVersion().singleResult();

		if (decisionDefinition == null) {
			String errorMessage = String.format("No matching decision definition with key: %s and no tenant-id", key);
			throw new SystemException(errorMessage);
		}
		return decisionDefinition;
	}

	private Object getXmlByDefinitionId(String definitionId, CIBUser user) {
		InputStream decisionModelInputStream = null;
		try {
			decisionModelInputStream = directProviderUtil.getProcessEngine(user).getRepositoryService().getDecisionModel(definitionId);

			byte[] decisionModel = IoUtil.readInputStream(decisionModelInputStream, "decisionModelDmnXml");
			return DecisionDefinitionDiagramDto.create(definitionId, new String(decisionModel, "UTF-8"));

		} catch (ProcessEngineException | UnsupportedEncodingException e) {
			throw new SystemException(e.getMessage(), e);

		} finally {
			IoUtil.closeSilently(decisionModelInputStream);
		}
	}

	private Object getDiagramByDecisionDefinition(DecisionDefinition decisionDefinition, CIBUser user) {
		InputStream decisionDiagram = directProviderUtil.getProcessEngine(user).getRepositoryService().getDecisionDiagram(decisionDefinition.getId());
		if (decisionDiagram == null) {
			throw new SystemException("Diagram of decision " + decisionDefinition.getId() + " not found.");
		} else {
			try {
				byte[] byteContent = IOUtils.toByteArray(decisionDiagram);
				return DecisionDefinitionDiagramDto.create(decisionDefinition.getDiagramResourceName(), new String(byteContent, "UTF-8"));
			} catch (IOException e) {
				throw new SystemException(e.getMessage(), e);
			} finally {
				IoUtil.closeSilently(decisionDiagram);
			}
		}
	}

	private DecisionDefinition getDecisionDefinitionById(String id, CIBUser user) {

		DecisionDefinition definition = null;
		try {
			definition = directProviderUtil.getProcessEngine(user).getRepositoryService().getDecisionDefinition(id);
		} catch (ProcessEngineException e) {
			throw new SystemException(e.getMessage(), e);
		}
		return definition;
	}

	private Map<String, VariableValueDto> createResultEntriesDto(DmnDecisionResultEntries entries) {
		VariableMap variableMap = Variables.createVariables();

		for (String key : entries.keySet()) {
			TypedValue typedValue = entries.getEntryTyped(key);
			variableMap.putValueTyped(key, typedValue);
		}

		return VariableValueDto.fromMap(variableMap);
	}

}
