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

import org.cibseven.bpm.engine.externaltask.ExternalTaskQuery;
import org.cibseven.bpm.engine.rest.dto.externaltask.ExternalTaskDto;
import org.cibseven.bpm.engine.rest.dto.externaltask.ExternalTaskQueryDto;
import org.cibseven.bpm.engine.rest.util.QueryUtil;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.ExternalTask;

public class DirectExternalTaskProvider implements IExternalTaskProvider {

	DirectProviderUtil directProviderUtil;

	DirectExternalTaskProvider(DirectProviderUtil directProviderUtil){
		this.directProviderUtil = directProviderUtil;
	}

	@Override
	public Collection<ExternalTask> getExternalTasks(Map<String, Object> params, CIBUser user) throws SystemException {
		Integer firstResult = directProviderUtil.getFirstResult(params);
		Integer maxResults = directProviderUtil.getMaxResults(params);
		Map<String, Object> queryParams = directProviderUtil.withoutPagingParams(params);

		ExternalTaskQueryDto queryDto = directProviderUtil.getObjectMapper(user).convertValue(queryParams, ExternalTaskQueryDto.class);
		queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
		ExternalTaskQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));
		List<org.cibseven.bpm.engine.externaltask.ExternalTask> matchingTasks = QueryUtil.list(query, firstResult, maxResults);

		List<ExternalTask> taskResults = new ArrayList<>();
		for (org.cibseven.bpm.engine.externaltask.ExternalTask task : matchingTasks) {
			ExternalTaskDto resultInstance = ExternalTaskDto.fromExternalTask(task);
			taskResults.add(directProviderUtil.convertValue(resultInstance, ExternalTask.class, user));
		}
		return taskResults;
	}

}
