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

import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.MismatchingMessageCorrelationException;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.management.SetJobRetriesBuilder;
import org.cibseven.bpm.engine.rest.dto.VariableValueDto;
import org.cibseven.bpm.engine.rest.dto.message.CorrelationMessageDto;
import org.cibseven.bpm.engine.rest.dto.message.MessageCorrelationResultDto;
import org.cibseven.bpm.engine.rest.dto.message.MessageCorrelationResultWithVariableDto;
import org.cibseven.bpm.engine.rest.dto.runtime.EventSubscriptionDto;
import org.cibseven.bpm.engine.rest.dto.runtime.EventSubscriptionQueryDto;
import org.cibseven.bpm.engine.rest.dto.runtime.RetriesDto;
import org.cibseven.bpm.engine.rest.exception.RestException;
import org.cibseven.bpm.engine.rest.util.QueryUtil;
import org.cibseven.bpm.engine.runtime.EventSubscriptionQuery;
import org.cibseven.bpm.engine.runtime.MessageCorrelationBuilder;
import org.cibseven.bpm.engine.runtime.MessageCorrelationResult;
import org.cibseven.bpm.engine.runtime.MessageCorrelationResultWithVariables;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.EventSubscription;
import org.cibseven.webapp.rest.model.Message;

import com.fasterxml.jackson.databind.ObjectMapper;

public class DirectUtilsProvider implements IUtilsProvider {

	DirectProviderUtil directProviderUtil;

	DirectUtilsProvider(DirectProviderUtil directProviderUtil){
		this.directProviderUtil = directProviderUtil;
	}

	@Override
	public Collection<Message> correlateMessage(Map<String, Object> data, CIBUser user) throws SystemException {
		CorrelationMessageDto messageDto = directProviderUtil.getObjectMapper(user).convertValue(data, CorrelationMessageDto.class);
		if (messageDto.getMessageName() == null) {
			throw new SystemException("No message name supplied");
		}
		if (messageDto.getTenantId() != null && messageDto.isWithoutTenantId()) {
			throw new SystemException("Parameter 'tenantId' cannot be used together with parameter 'withoutTenantId'.");
		}
		boolean variablesInResultEnabled = messageDto.isVariablesInResultEnabled();
		if (!messageDto.isResultEnabled() && variablesInResultEnabled) {
			throw new SystemException(
					"Parameter 'variablesInResultEnabled' cannot be used without 'resultEnabled' set to true.");
		}

		List<MessageCorrelationResultDto> resultDtos = new ArrayList<>();
		try {
			MessageCorrelationBuilder correlation = createMessageCorrelationBuilder(messageDto, user);
			if (!variablesInResultEnabled) {
				resultDtos.addAll(correlate(messageDto, correlation));
			} else {
				resultDtos.addAll(correlateWithVariablesEnabled(messageDto, correlation));
			}
		} catch (RestException e) {
			String errorMessage = String.format("Cannot deliver message: %s", e.getMessage());
			throw new SystemException(errorMessage, e);
		} catch (MismatchingMessageCorrelationException e) {
			throw new SystemException(e);
		}
		List<Message> messageList = new ArrayList<>();
		if (messageDto.isResultEnabled()) {
			for (MessageCorrelationResultDto resultDto : resultDtos) {
				messageList.add(directProviderUtil.convertValue(resultDto, Message.class, user));
			}
		}
		return messageList;
	}

	public MessageCorrelationBuilder createMessageCorrelationBuilder(CorrelationMessageDto messageDto, CIBUser user) {
		ObjectMapper objectMapper = directProviderUtil.getObjectMapper(user);
		Map<String, Object> correlationKeys = VariableValueDto.toMap(messageDto.getCorrelationKeys(), directProviderUtil.getProcessEngine(user),
				objectMapper);
		Map<String, Object> localCorrelationKeys = VariableValueDto.toMap(messageDto.getLocalCorrelationKeys(), directProviderUtil.getProcessEngine(user),
				objectMapper);
		Map<String, Object> processVariables = VariableValueDto.toMap(messageDto.getProcessVariables(), directProviderUtil.getProcessEngine(user),
				objectMapper);
		Map<String, Object> processVariablesLocal = VariableValueDto.toMap(messageDto.getProcessVariablesLocal(),
				directProviderUtil.getProcessEngine(user), objectMapper);
		Map<String, Object> processVariablesToTriggeredScope = VariableValueDto
				.toMap(messageDto.getProcessVariablesToTriggeredScope(), directProviderUtil.getProcessEngine(user), objectMapper);

		MessageCorrelationBuilder builder = directProviderUtil.getProcessEngine(user).getRuntimeService().createMessageCorrelation(messageDto.getMessageName());

		if (processVariables != null) {
			builder.setVariables(processVariables);
		}
		if (processVariablesLocal != null) {
			builder.setVariablesLocal(processVariablesLocal);
		}
		if (processVariablesToTriggeredScope != null) {
			builder.setVariablesToTriggeredScope(processVariablesToTriggeredScope);
		}
		if (messageDto.getBusinessKey() != null) {
			builder.processInstanceBusinessKey(messageDto.getBusinessKey());
		}

		if (correlationKeys != null && !correlationKeys.isEmpty()) {
			for (java.util.Map.Entry<String, Object> correlationKey : correlationKeys.entrySet()) {
				String name = correlationKey.getKey();
				Object value = correlationKey.getValue();
				builder.processInstanceVariableEquals(name, value);
			}
		}

		if (localCorrelationKeys != null && !localCorrelationKeys.isEmpty()) {
			for (java.util.Map.Entry<String, Object> correlationKey : localCorrelationKeys.entrySet()) {
				String name = correlationKey.getKey();
				Object value = correlationKey.getValue();
				builder.localVariableEquals(name, value);
			}
		}

		if (messageDto.getTenantId() != null) {
			builder.tenantId(messageDto.getTenantId());

		} else if (messageDto.isWithoutTenantId()) {
			builder.withoutTenantId();
		}

		String processInstanceId = messageDto.getProcessInstanceId();
		if (processInstanceId != null) {
			builder.processInstanceId(processInstanceId);
		}

		return builder;
	}

	@Override
	public String findStacktrace(String jobId, CIBUser user) {
		try {
			String stacktrace = directProviderUtil.getProcessEngine(user).getManagementService().getJobExceptionStacktrace(jobId);
			return stacktrace;
		} catch (AuthorizationException e) {
			throw e;
		} catch (ProcessEngineException e) {
			throw new SystemException(e.getMessage());
		}
	}

	@Override
	public void retryJobById(String jobId, Map<String, Object> data, CIBUser user) {
		RetriesDto dto = directProviderUtil.getObjectMapper(user).convertValue(data, RetriesDto.class);
		try {
			SetJobRetriesBuilder builder = directProviderUtil.getProcessEngine(user).getManagementService().setJobRetries(dto.getRetries()).jobId(jobId);
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
	public Collection<EventSubscription> getEventSubscriptions(Optional<String> processInstanceId,
			Optional<String> eventType, Optional<String> eventName, CIBUser user) {
		EventSubscriptionQueryDto queryDto = new EventSubscriptionQueryDto();
		queryDto.setObjectMapper(directProviderUtil.getObjectMapper(user));
		if (processInstanceId.isPresent())
			queryDto.setProcessInstanceId(processInstanceId.get());
		if (eventType.isPresent())
			queryDto.setEventType(eventType.get());
		if (eventName.isPresent())
			queryDto.setEventName(eventName.get());
		EventSubscriptionQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));

		List<org.cibseven.bpm.engine.runtime.EventSubscription> matchingEventSubscriptions = QueryUtil.list(query, null,
				null);

		List<EventSubscription> eventSubscriptionResults = new ArrayList<>();
		for (org.cibseven.bpm.engine.runtime.EventSubscription eventSubscription : matchingEventSubscriptions) {
			EventSubscriptionDto resultEventSubscription = EventSubscriptionDto.fromEventSubscription(eventSubscription);
			eventSubscriptionResults.add(directProviderUtil.convertValue(resultEventSubscription, EventSubscription.class, user));
		}
		return eventSubscriptionResults;
	}

	private List<MessageCorrelationResultDto> correlate(CorrelationMessageDto messageDto,
			MessageCorrelationBuilder correlation) {
		List<MessageCorrelationResultDto> resultDtos = new ArrayList<>();
		if (!messageDto.isAll()) {
			MessageCorrelationResult result = correlation.correlateWithResult();
			resultDtos.add(MessageCorrelationResultDto.fromMessageCorrelationResult(result));
		} else {
			List<MessageCorrelationResult> results = correlation.correlateAllWithResult();
			for (MessageCorrelationResult result : results) {
				resultDtos.add(MessageCorrelationResultDto.fromMessageCorrelationResult(result));
			}
		}
		return resultDtos;
	}

	private List<MessageCorrelationResultWithVariableDto> correlateWithVariablesEnabled(CorrelationMessageDto messageDto,
			MessageCorrelationBuilder correlation) {
		List<MessageCorrelationResultWithVariableDto> resultDtos = new ArrayList<>();
		if (!messageDto.isAll()) {
			MessageCorrelationResultWithVariables result = correlation.correlateWithResultAndVariables(false);
			resultDtos.add(MessageCorrelationResultWithVariableDto.fromMessageCorrelationResultWithVariables(result));
		} else {
			List<MessageCorrelationResultWithVariables> results = correlation.correlateAllWithResultAndVariables(false);
			for (MessageCorrelationResultWithVariables result : results) {
				resultDtos.add(MessageCorrelationResultWithVariableDto.fromMessageCorrelationResultWithVariables(result));
			}
		}
		return resultDtos;
	}
}
