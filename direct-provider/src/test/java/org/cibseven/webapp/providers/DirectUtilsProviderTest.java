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
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.HashMap;
import java.util.Map;

import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.management.SetJobRetriesBuilder;
import org.cibseven.bpm.engine.rest.mapper.JacksonConfigurator;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.SystemException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

public class DirectUtilsProviderTest {

	private DirectProviderUtil directProviderUtil;
	private ManagementService managementService;
	private DirectUtilsProvider utilsProvider;
	private CIBUser user;

	@BeforeEach
	void setUp() {
		user = new CIBUser("testUser");

		ProcessEngine processEngine = mock(ProcessEngine.class);
		managementService = mock(ManagementService.class);
		when(processEngine.getManagementService()).thenReturn(managementService);

		ObjectMapper objectMapper = new ObjectMapper();
		JacksonConfigurator.configureObjectMapper(objectMapper);

		directProviderUtil = Mockito.spy(new DirectProviderUtil());
		doReturn(processEngine).when(directProviderUtil).getProcessEngine(any(CIBUser.class));
		doReturn(objectMapper).when(directProviderUtil).getObjectMapper(any(CIBUser.class));

		utilsProvider = new DirectUtilsProvider(directProviderUtil);
	}

	@Test
	void findStacktrace_returnsTheJobStacktrace() {
		when(managementService.getJobExceptionStacktrace("job-1")).thenReturn("at Foo.bar()");

		assertThat(utilsProvider.findStacktrace("job-1", user)).isEqualTo("at Foo.bar()");
	}

	@Test
	void findStacktrace_letsAuthorizationFailuresThrough() {
		when(managementService.getJobExceptionStacktrace("job-1")).thenThrow(new AuthorizationException("denied"));

		assertThatThrownBy(() -> utilsProvider.findStacktrace("job-1", user))
			.isInstanceOf(AuthorizationException.class);
	}

	@Test
	void findStacktrace_wrapsOtherEngineFailures() {
		when(managementService.getJobExceptionStacktrace("job-1")).thenThrow(new ProcessEngineException("boom"));

		assertThatThrownBy(() -> utilsProvider.findStacktrace("job-1", user))
			.isInstanceOf(SystemException.class);
	}

	@Test
	void retryJobById_setsTheRetryCountOnTheJob() {
		SetJobRetriesBuilder builder = mock(SetJobRetriesBuilder.class, withSettings().defaultAnswer(RETURNS_SELF));
		when(managementService.setJobRetries(3)).thenReturn(builder);
		Map<String, Object> data = new HashMap<>();
		data.put("retries", 3);

		utilsProvider.retryJobById("job-1", data, user);

		verify(builder).jobId("job-1");
		verify(builder).execute();
	}

	@Test
	void retryJobById_doesNotTouchTheDueDateWhenNoneWasSubmitted() {
		SetJobRetriesBuilder builder = mock(SetJobRetriesBuilder.class, withSettings().defaultAnswer(RETURNS_SELF));
		when(managementService.setJobRetries(3)).thenReturn(builder);
		Map<String, Object> data = new HashMap<>();
		data.put("retries", 3);

		utilsProvider.retryJobById("job-1", data, user);

		// passing a null due date would clear the job's existing schedule
		verify(builder, Mockito.never()).dueDate(any());
	}
}
