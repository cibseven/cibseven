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
import java.util.Optional;

import org.cibseven.bpm.engine.history.HistoricVariableInstance;
import org.cibseven.bpm.engine.history.HistoricVariableInstanceQuery;
import org.cibseven.bpm.engine.rest.dto.history.HistoricVariableInstanceDto;
import org.cibseven.bpm.engine.rest.dto.history.HistoricVariableInstanceQueryDto;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.NoObjectFoundException;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.VariableHistory;

public class DirectHistoricVariableInstanceProvider implements IHistoricVariableInstanceProvider {

	DirectProviderUtil directProviderUtil;
	public DirectHistoricVariableInstanceProvider(DirectProviderUtil directProviderUtil){
		this.directProviderUtil = directProviderUtil;
	}

	@Override
	public VariableHistory getHistoricVariableInstance(String id, boolean deserializeValue, CIBUser user)
			throws SystemException, NoObjectFoundException {
		VariableHistory variableSerialized = getHistoricVariableInstanceImpl(id, false, user);
		VariableHistory variableDeserialized = getHistoricVariableInstanceImpl(id, true, user);

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

	private VariableHistory getHistoricVariableInstanceImpl(String id, boolean deserializeValue, CIBUser user) {
		HistoricVariableInstanceQuery query = directProviderUtil.getProcessEngine(user).getHistoryService().createHistoricVariableInstanceQuery().variableId(id);
		if (!deserializeValue) {
			query.disableCustomObjectDeserialization();
		}
		HistoricVariableInstance variableInstance = query.singleResult();
		if (variableInstance != null) {
			VariableHistory result = directProviderUtil.convertValue(HistoricVariableInstanceDto.fromHistoricVariableInstance(variableInstance),
					VariableHistory.class, user);
			return result;
		} else {
			throw new NoObjectFoundException(new SystemException(" historic variable with Id '" + id + "' does not exist."));
		}
	}

	@Override
	public Collection<VariableHistory> findHistoricVariableInstances(Map<String, Object> filters,
			Optional<Integer> firstResult, Optional<Integer> maxResults, Boolean deserializeValues,
			CIBUser user) throws SystemException {
		HistoricVariableInstanceQueryDto queryDto = directProviderUtil.getObjectMapper(user)
				.convertValue(filters, HistoricVariableInstanceQueryDto.class);
		queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
		HistoricVariableInstanceQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));
		// As engine-rest's list endpoint: file and bytes contents are not sent, only their metadata
		query.disableBinaryFetching();
		if (Boolean.FALSE.equals(deserializeValues)) {
			query.disableCustomObjectDeserialization();
		}

		List<HistoricVariableInstance> results = (firstResult.isPresent() || maxResults.isPresent())
				? query.listPage(firstResult.orElse(0), maxResults.orElse(Integer.MAX_VALUE))
				: query.list();

		List<VariableHistory> variables = new ArrayList<>();
		for (HistoricVariableInstance result : results) {
			variables.add(directProviderUtil.convertValue(
					HistoricVariableInstanceDto.fromHistoricVariableInstance(result), VariableHistory.class, user));
		}
		return variables;
	}

	@Override
	public Integer findHistoricVariableInstancesCount(Map<String, Object> filters, CIBUser user) throws SystemException {
		HistoricVariableInstanceQueryDto queryDto = directProviderUtil.getObjectMapper(user)
				.convertValue(filters, HistoricVariableInstanceQueryDto.class);
		queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
		return (int) queryDto.toQuery(directProviderUtil.getProcessEngine(user)).count();
	}

}
