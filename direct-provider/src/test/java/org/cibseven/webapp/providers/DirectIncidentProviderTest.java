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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.ExternalTaskService;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.exception.NotFoundException;
import org.cibseven.bpm.engine.rest.mapper.JacksonConfigurator;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.NoObjectFoundException;
import org.cibseven.webapp.exception.SystemException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

public class DirectIncidentProviderTest {

	private DirectProviderUtil directProviderUtil;
	private RuntimeService runtimeService;
	private ExternalTaskService externalTaskService;
	private HistoryService historyService;
	private DirectIncidentProvider incidentProvider;
	private CIBUser user;

	@BeforeEach
	void setUp() {
		user = new CIBUser("testUser");

		ProcessEngine processEngine = mock(ProcessEngine.class);
		runtimeService = mock(RuntimeService.class);
		externalTaskService = mock(ExternalTaskService.class);
		historyService = mock(HistoryService.class);
		when(processEngine.getRuntimeService()).thenReturn(runtimeService);
		when(processEngine.getExternalTaskService()).thenReturn(externalTaskService);
		when(processEngine.getHistoryService()).thenReturn(historyService);

		ObjectMapper objectMapper = new ObjectMapper();
		JacksonConfigurator.configureObjectMapper(objectMapper);

		directProviderUtil = Mockito.spy(new DirectProviderUtil());
		doReturn(processEngine).when(directProviderUtil).getProcessEngine(any(CIBUser.class));
		doReturn(objectMapper).when(directProviderUtil).getObjectMapper(any(CIBUser.class));

		incidentProvider = new DirectIncidentProvider(directProviderUtil);
	}

	@Test
	void setIncidentAnnotation_storesTheAnnotationOnTheIncident() {
		Map<String, Object> data = new HashMap<>();
		data.put("annotation", "looked at, retrying tomorrow");

		incidentProvider.setIncidentAnnotation("incident-1", data, user);

		verify(runtimeService).setAnnotationForIncidentById("incident-1", "looked at, retrying tomorrow");
	}

	@Test
	void setIncidentAnnotation_clearsTheAnnotationWhenNoneIsGiven() {
		incidentProvider.setIncidentAnnotation("incident-1", new HashMap<>(), user);

		verify(runtimeService).setAnnotationForIncidentById("incident-1", null);
	}

	@Test
	void retryExternalTask_setsTheRequestedRetryCount() {
		Map<String, Object> data = new HashMap<>();
		data.put("retries", 3);

		incidentProvider.retryExternalTask("task-1", data, user);

		verify(externalTaskService).setRetries("task-1", 3);
	}

	@Test
	void retryExternalTask_rejectsAMissingRetryCount() {
		// without this guard the engine would be asked to set null retries
		assertThatThrownBy(() -> incidentProvider.retryExternalTask("task-1", new HashMap<>(), user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("The number of retries cannot be null");
	}

	@Test
	void retryExternalTask_translatesAnUnknownTaskIntoNoObjectFound() {
		Map<String, Object> data = new HashMap<>();
		data.put("retries", 1);
		doThrow(new NotFoundException("gone")).when(externalTaskService).setRetries("missing", 1);

		assertThatThrownBy(() -> incidentProvider.retryExternalTask("missing", data, user))
			.isInstanceOf(NoObjectFoundException.class);
	}

	@Test
	void findExternalTaskErrorDetails_returnsTheEngineDetails() {
		when(externalTaskService.getExternalTaskErrorDetails("task-1")).thenReturn("java.lang.RuntimeException");

		assertThat(incidentProvider.findExternalTaskErrorDetails("task-1", user))
			.isEqualTo("java.lang.RuntimeException");
	}

	@Test
	void findExternalTaskErrorDetails_translatesAnUnknownTaskIntoNoObjectFound() {
		when(externalTaskService.getExternalTaskErrorDetails("missing")).thenThrow(new NotFoundException("gone"));

		assertThatThrownBy(() -> incidentProvider.findExternalTaskErrorDetails("missing", user))
			.isInstanceOf(NoObjectFoundException.class);
	}

	@Test
	void findHistoricExternalTaskErrorDetails_returnsTheHistoricDetails() {
		when(historyService.getHistoricExternalTaskLogErrorDetails("task-1")).thenReturn("boom");

		assertThat(incidentProvider.findHistoricExternalTaskErrorDetails("task-1", user)).isEqualTo("boom");
	}

	@Test
	void findHistoricExternalTaskErrorDetails_letsAuthorizationFailuresThrough() {
		when(historyService.getHistoricExternalTaskLogErrorDetails("task-1"))
			.thenThrow(new AuthorizationException("denied"));

		assertThatThrownBy(() -> incidentProvider.findHistoricExternalTaskErrorDetails("task-1", user))
			.isInstanceOf(AuthorizationException.class);
	}

	@Test
	void findHistoricExternalTaskErrorDetails_wrapsOtherEngineFailures() {
		when(historyService.getHistoricExternalTaskLogErrorDetails("task-1"))
			.thenThrow(new ProcessEngineException("boom"));

		assertThatThrownBy(() -> incidentProvider.findHistoricExternalTaskErrorDetails("task-1", user))
			.isInstanceOf(SystemException.class);
	}

	@Test
	void findHistoricStacktraceByJobId_returnsTheStacktrace() {
		when(historyService.getHistoricJobLogExceptionStacktrace("job-1")).thenReturn("at Foo.bar()");

		assertThat(incidentProvider.findHistoricStacktraceByJobId("job-1", user)).isEqualTo("at Foo.bar()");
	}

	@Test
	void findHistoricStacktraceByJobId_letsAuthorizationFailuresThrough() {
		when(historyService.getHistoricJobLogExceptionStacktrace("job-1"))
			.thenThrow(new AuthorizationException("denied"));

		assertThatThrownBy(() -> incidentProvider.findHistoricStacktraceByJobId("job-1", user))
			.isInstanceOf(AuthorizationException.class);
	}
}
