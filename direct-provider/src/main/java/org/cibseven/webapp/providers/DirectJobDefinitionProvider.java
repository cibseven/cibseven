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
import org.cibseven.bpm.engine.management.JobDefinitionQuery;
import org.cibseven.bpm.engine.management.SetJobRetriesBuilder;
import org.cibseven.bpm.engine.rest.dto.management.JobDefinitionDto;
import org.cibseven.bpm.engine.rest.dto.management.JobDefinitionQueryDto;
import org.cibseven.bpm.engine.rest.dto.management.JobDefinitionSuspensionStateDto;
import org.cibseven.bpm.engine.rest.dto.runtime.JobDefinitionPriorityDto;
import org.cibseven.bpm.engine.rest.dto.runtime.RetriesDto;
import org.cibseven.bpm.engine.rest.util.QueryUtil;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.NoObjectFoundException;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.JobDefinition;

import com.fasterxml.jackson.core.JsonProcessingException;

public class DirectJobDefinitionProvider implements IJobDefinitionProvider {

	DirectProviderUtil directProviderUtil;

	DirectJobDefinitionProvider(DirectProviderUtil directProviderUtil){
		this.directProviderUtil = directProviderUtil;
	}

	@Override
	public Collection<JobDefinition> findJobDefinitions(String params, CIBUser user) {
		JobDefinitionQueryDto queryDto;
		try {
			queryDto = directProviderUtil.getObjectMapper(user).readValue(params, JobDefinitionQueryDto.class);
		} catch (JsonProcessingException e) {
			throw new SystemException(e.getMessage());
		}
		queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
		JobDefinitionQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));

		List<org.cibseven.bpm.engine.management.JobDefinition> matchingJobDefinitions = QueryUtil.list(query, null, null);

		List<JobDefinition> jobDefinitionResults = new ArrayList<>();
		for (org.cibseven.bpm.engine.management.JobDefinition jobDefinition : matchingJobDefinitions) {
			JobDefinitionDto result = JobDefinitionDto.fromJobDefinition(jobDefinition);
			jobDefinitionResults.add(directProviderUtil.convertValue(result, JobDefinition.class, user));
		}

		return jobDefinitionResults;
	}

	@Override
	public void suspendJobDefinition(String jobDefinitionId, String param, CIBUser user) {
		try {
			@SuppressWarnings("unchecked")
			Map<String,Object> params = directProviderUtil.getObjectMapper(user).readValue(param, HashMap.class);
			JobDefinitionSuspensionStateDto dto = directProviderUtil.getObjectMapper(user).convertValue(params, JobDefinitionSuspensionStateDto.class);
			dto.setJobDefinitionId(jobDefinitionId);
			dto.updateSuspensionState(directProviderUtil.getProcessEngine(user));

		} catch (IllegalArgumentException e) {
			String message = String.format(
					"The suspension state of Job Definition with id %s could not be updated due to: %s", jobDefinitionId,
					e.getMessage());
			throw new SystemException(message, e);
		} catch (JsonProcessingException e) {
			throw new SystemException(e.getMessage());
		}
	}

	@Override
	public void overrideJobDefinitionPriority(String jobDefinitionId, String param, CIBUser user) {
		try {
			@SuppressWarnings("unchecked")
			Map<String,Object> params = directProviderUtil.getObjectMapper(user).readValue(param, HashMap.class);
			JobDefinitionPriorityDto dto = directProviderUtil.getObjectMapper(user).convertValue(params, JobDefinitionPriorityDto.class);

			if (dto.getPriority() != null) {
				directProviderUtil.getProcessEngine(user).getManagementService().setOverridingJobPriorityForJobDefinition(jobDefinitionId, dto.getPriority(),
						dto.isIncludeJobs());
			} else {
				if (dto.isIncludeJobs()) {
					throw new SystemException("Cannot reset priority for job definition " + jobDefinitionId + " with includeJobs=true");
				}
				directProviderUtil.getProcessEngine(user).getManagementService().clearOverridingJobPriorityForJobDefinition(jobDefinitionId);
			}

		} catch (AuthorizationException e) {
			throw e;
		} catch (JsonProcessingException|ProcessEngineException e) {
			throw new SystemException(e.getMessage());
		}
	}

	@Override
	public void retryJobDefinitionById(String id, Map<String, Object> params, CIBUser user) {
		RetriesDto dto = directProviderUtil.getObjectMapper(user).convertValue(params, RetriesDto.class);
		try {
			SetJobRetriesBuilder builder = directProviderUtil.getProcessEngine(user).getManagementService().setJobRetries(dto.getRetries()).jobDefinitionId(id);
			if (dto.isDueDateSet()) {
				builder.dueDate(dto.getDueDate());
			}
			builder.execute();
		} catch (AuthorizationException e) {
			throw e;
		} catch (ProcessEngineException e) {
			throw new SystemException(e.getMessage());
		}
	}

	@Override
	public JobDefinition findJobDefinition(String id, CIBUser user) {

		org.cibseven.bpm.engine.management.JobDefinition jobDefinition = directProviderUtil.getProcessEngine(user).getManagementService().createJobDefinitionQuery()
				.jobDefinitionId(id).singleResult();
		if (jobDefinition == null) {
			throw new NoObjectFoundException(new SystemException("Job Definition with id " + id + " does not exist"));
		}
		return directProviderUtil.convertValue(JobDefinitionDto.fromJobDefinition(jobDefinition), JobDefinition.class, user);
	}

}
