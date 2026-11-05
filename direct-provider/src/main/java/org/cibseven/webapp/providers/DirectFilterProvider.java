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

import org.cibseven.bpm.engine.EntityTypes;
import org.cibseven.bpm.engine.exception.NotValidException;
import org.cibseven.bpm.engine.exception.NullValueException;
import org.cibseven.bpm.engine.filter.FilterQuery;
import org.cibseven.bpm.engine.rest.dto.runtime.FilterDto;
import org.cibseven.bpm.engine.rest.dto.runtime.FilterQueryDto;
import org.cibseven.bpm.engine.rest.util.QueryUtil;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.Filter;

public class DirectFilterProvider implements IFilterProvider{

	DirectProviderUtil directProviderUtil;

	DirectFilterProvider(DirectProviderUtil directProviderUtil){
		this.directProviderUtil = directProviderUtil;
	}

	@Override
	public Collection<Filter> findFilters(CIBUser user) {
		FilterQueryDto filterQueryDto = new FilterQueryDto();
		filterQueryDto.setResourceType("Task");
		FilterQuery query = filterQueryDto.toQuery(directProviderUtil.getProcessEngine(user));

		List<org.cibseven.bpm.engine.filter.Filter> matchingFilters = QueryUtil.list(query, null, null);

		List<Filter> filters = new ArrayList<>();
		for (org.cibseven.bpm.engine.filter.Filter filter : matchingFilters) {
			FilterDto filterDto = FilterDto.fromFilter(filter);
			filters.add(directProviderUtil.convertValue(filterDto, Filter.class, user));
		}
		return filters;
	}

	@Override
	public Filter createFilter(Filter filter, CIBUser user) {
		FilterDto filterDto = directProviderUtil.convertValue(filter, FilterDto.class, user);
		String resourceType = filterDto.getResourceType();

		org.cibseven.bpm.engine.filter.Filter engineFilter;
		if (EntityTypes.TASK.equals(resourceType)) {
			engineFilter = directProviderUtil.getProcessEngine(user).getFilterService().newTaskFilter();
		} else {
			throw new SystemException("Unable to create filter with invalid resource type '" + resourceType + "'");
		}

		try {
			filterDto.updateFilter(engineFilter, directProviderUtil.getProcessEngine(user));
		} catch (NotValidException e) {
			throw new SystemException("Unable to create filter with invalid content", e);
		}

		directProviderUtil.getProcessEngine(user).getFilterService().saveFilter(engineFilter);

		Filter resultFilter = directProviderUtil.convertValue(FilterDto.fromFilter(engineFilter), Filter.class, user);
		return resultFilter;
	}

	@Override
	public void updateFilter(Filter filter, CIBUser user) {
		FilterDto filterDto = directProviderUtil.convertValue(filter, FilterDto.class, user);
		org.cibseven.bpm.engine.filter.Filter dbFilter = directProviderUtil.getProcessEngine(user).getFilterService().getFilter(filter.getId());

		if (dbFilter == null) {
			throw new SystemException("Requested filter not found: " + filter.getId());
		}

		try {
			filterDto.updateFilter(dbFilter, directProviderUtil.getProcessEngine(user));
		} catch (NotValidException e) {
			throw new SystemException("Unable to update filter with invalid content", e);
		}
		directProviderUtil.getProcessEngine(user).getFilterService().saveFilter(dbFilter);
	}

	@Override
	public void deleteFilter(String filterId, CIBUser user) {
		try {
			directProviderUtil.getProcessEngine(user).getFilterService().deleteFilter(filterId);
		} catch (NullValueException e) {
			throw new SystemException("Requested filter not found: " + filterId);
		}
	}
}
