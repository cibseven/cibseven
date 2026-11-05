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
import java.util.Map.Entry;

import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.exception.NotFoundException;
import org.cibseven.bpm.engine.history.HistoricJobLog;
import org.cibseven.bpm.engine.history.HistoricJobLogQuery;
import org.cibseven.bpm.engine.runtime.JobQuery;
import org.cibseven.bpm.engine.rest.dto.history.HistoricJobLogDto;
import org.cibseven.bpm.engine.rest.dto.history.HistoricJobLogQueryDto;
import org.cibseven.bpm.engine.rest.dto.management.JobDefinitionSuspensionStateDto;
import org.cibseven.bpm.engine.rest.dto.runtime.JobDto;
import org.cibseven.bpm.engine.rest.dto.runtime.JobDuedateDto;
import org.cibseven.bpm.engine.rest.dto.runtime.JobQueryDto;
import org.cibseven.bpm.engine.rest.util.QueryUtil;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.Job;

public class DirectJobProvider implements IJobProvider {

	DirectProviderUtil directProviderUtil;

	DirectJobProvider(DirectProviderUtil directProviderUtil){
		this.directProviderUtil = directProviderUtil;
	}

	@Override
	public Collection<Job> getJobs(Map<String, Object> params, CIBUser user) {
		Integer firstResult = null;
		Integer maxResults = null;

		Map<String, Object> queryParams = new java.util.HashMap<>(params);
		queryParams.remove("firstResult");
		queryParams.remove("maxResults");

		for (Entry<String, Object> entry : params.entrySet()) {
			if (entry.getKey().equals("firstResult"))
				firstResult = Integer.parseInt((String) params.get("firstResult"));
			else if (entry.getKey().equals("maxResults"))
				maxResults = Integer.parseInt((String) params.get("maxResults"));
		}

		JobQueryDto queryDto = directProviderUtil.getObjectMapper(user).convertValue(queryParams, JobQueryDto.class);
		queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
		JobQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));
		List<org.cibseven.bpm.engine.runtime.Job> matchingJobs = QueryUtil.list(query, firstResult, maxResults);

		List<Job> jobResults = new ArrayList<>();
		for (org.cibseven.bpm.engine.runtime.Job job : matchingJobs) {
			JobDto result = JobDto.fromJob(job);
			jobResults.add(directProviderUtil.convertValue(result, Job.class, user));
		}
		return jobResults;
	}

	@Override
	public void setSuspended(String id, Map<String, Object> params, CIBUser user) {

		JobDefinitionSuspensionStateDto jobDefinitionSuspensionStateDto = directProviderUtil.getObjectMapper(user).convertValue(params,
				JobDefinitionSuspensionStateDto.class);
		jobDefinitionSuspensionStateDto.setProcessDefinitionId(id);
		if (jobDefinitionSuspensionStateDto.getJobDefinitionId() != null) {
			String message = "Either processDefinitionId or processDefinitionKey can be set to update the suspension state.";
			throw new SystemException(message);
		}

		try {
			jobDefinitionSuspensionStateDto.updateSuspensionState(directProviderUtil.getProcessEngine(user));

		} catch (IllegalArgumentException e) {
			String message = String.format("Could not update the suspension state of Job Definitions due to: %s",
					e.getMessage());
			throw new SystemException(message, e);
		}
	}

	@Override
	public void deleteJob(String id, CIBUser user) {
		try {
			directProviderUtil.getProcessEngine(user).getManagementService().deleteJob(id);
		} catch (AuthorizationException e) {
			throw e;
		} catch (ProcessEngineException e) {
			throw new SystemException(e.getMessage());
		}
	}

	@Override
	public Collection<Object> getHistoryJobLog(Map<String, Object> params, CIBUser user) {

		HistoricJobLogQueryDto queryDto = directProviderUtil.getObjectMapper(user).convertValue(params, HistoricJobLogQueryDto.class);
		queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
		HistoricJobLogQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));
		Integer firstResult = null;
		Integer maxResults = null;
		for (Entry<String, Object> entry : params.entrySet()) {
			if (entry.getKey().equals("firstResult"))
				firstResult = Integer.parseInt((String) params.get("firstResult"));
			else if (entry.getKey().equals("maxResults"))
				maxResults = Integer.parseInt((String) params.get("maxResults"));
		}
		List<HistoricJobLog> matchingHistoricJobLogs = QueryUtil.list(query, firstResult, maxResults);

		List<Object> results = new ArrayList<>();
		for (HistoricJobLog historicJobLog : matchingHistoricJobLogs) {
			HistoricJobLogDto result = HistoricJobLogDto.fromHistoricJobLog(historicJobLog);
			results.add(result);
		}
		return results;
	}

	@Override
	public String getHistoryJobLogStacktrace(String id, CIBUser user) {

		try {
			String stacktrace = directProviderUtil.getProcessEngine(user).getHistoryService().getHistoricJobLogExceptionStacktrace(id);
			return stacktrace;
		} catch (AuthorizationException e) {
			throw e;
		} catch (ProcessEngineException e) {
			throw new SystemException(e.getMessage());
		}
	}

	@Override
	public void changeDueDate(String jobId, Map<String, Object> data, CIBUser user) {
		try {
			JobDuedateDto dto = directProviderUtil.getObjectMapper(user).convertValue(data,JobDuedateDto.class);
		  directProviderUtil.getProcessEngine(user).getManagementService().setJobDuedate(jobId, dto.getDuedate(), dto.isCascade());
		} catch (AuthorizationException e) {
		  throw e;
		} catch (ProcessEngineException e) {
		  throw new SystemException(e.getMessage());
		}
	}

	@Override
	public void recalculateDueDate(String jobId, Map<String, Object> params, CIBUser user) {
		try {
			boolean creationDateBased = params.containsKey("creationDateBased") ? 
					Boolean.parseBoolean((String)params.get("creationDateBased")) : false;
			directProviderUtil.getProcessEngine(user).getManagementService().recalculateJobDuedate(jobId, creationDateBased);
		} catch (AuthorizationException e) {
		  throw e;
		} catch(NotFoundException e) {// rewrite status code from bad request (400) to not found (404)
		  throw new SystemException(e.getMessage(), e);
		} catch (ProcessEngineException e) {
		  throw new SystemException(e.getMessage());
		}
	}

}
