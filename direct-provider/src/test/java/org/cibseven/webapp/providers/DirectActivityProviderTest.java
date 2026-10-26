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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.exception.NotFoundException;
import org.cibseven.bpm.engine.history.HistoricActivityInstanceQuery;
import org.cibseven.bpm.engine.rest.mapper.JacksonConfigurator;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.SystemException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

public class DirectActivityProviderTest {

	private DirectProviderUtil directProviderUtil;
	private RuntimeService runtimeService;
	private HistoryService historyService;
	private DirectActivityProvider activityProvider;
	private CIBUser user;

	@BeforeEach
	void setUp() {
		user = new CIBUser("testUser");

		ProcessEngine processEngine = mock(ProcessEngine.class);
		runtimeService = mock(RuntimeService.class);
		historyService = mock(HistoryService.class);
		when(processEngine.getRuntimeService()).thenReturn(runtimeService);
		when(processEngine.getHistoryService()).thenReturn(historyService);

		ObjectMapper objectMapper = new ObjectMapper();
		JacksonConfigurator.configureObjectMapper(objectMapper);

		directProviderUtil = Mockito.spy(new DirectProviderUtil());
		doReturn(processEngine).when(directProviderUtil).getProcessEngine(any(CIBUser.class));
		doReturn(objectMapper).when(directProviderUtil).getObjectMapper(any(CIBUser.class));

		activityProvider = new DirectActivityProvider(directProviderUtil);
	}

	private HistoricActivityInstanceQuery mockHistoricActivityQuery() {
		HistoricActivityInstanceQuery query = mock(HistoricActivityInstanceQuery.class, withSettings().defaultAnswer(RETURNS_SELF));
		when(historyService.createHistoricActivityInstanceQuery()).thenReturn(query);
		when(query.list()).thenReturn(List.of());
		when(query.listPage(anyInt(), anyInt())).thenReturn(List.of());
		return query;
	}

	@Test
	void findActivitiesInstancesHistory_pagesWithTheParametersItIsGiven() {
		HistoricActivityInstanceQuery query = mockHistoricActivityQuery();

		// request parameters arrive as strings, and paging is no filter of the query DTO
		activityProvider.findActivitiesInstancesHistory(
				Map.of("processInstanceId", "pi-1", "firstResult", "5", "maxResults", "1"), user);

		verify(query).processInstanceId("pi-1");
		verify(query).listPage(5, 1);
		verify(query, never()).list();
	}

	@Test
	void findActivitiesInstancesHistory_listsEverythingWithoutPaging() {
		HistoricActivityInstanceQuery query = mockHistoricActivityQuery();

		activityProvider.findActivitiesInstancesHistory(Map.of("processInstanceId", "pi-1"), user);

		verify(query).list();
		verify(query, never()).listPage(anyInt(), anyInt());
	}

	@Test
	void deleteVariableByExecutionId_removesTheLocalVariable() {
		activityProvider.deleteVariableByExecutionId("exec-1", "amount", user);

		// local, not the whole scope - deleting the wrong one would clear a parent's variable
		verify(runtimeService).removeVariableLocal("exec-1", "amount");
	}

	@Test
	void deleteVariableByExecutionId_letsAuthorizationFailuresThrough() {
		doThrow(new AuthorizationException("denied")).when(runtimeService).removeVariableLocal("exec-1", "amount");

		assertThatThrownBy(() -> activityProvider.deleteVariableByExecutionId("exec-1", "amount", user))
			.isInstanceOf(AuthorizationException.class);
	}

	@Test
	void deleteVariableByExecutionId_wrapsOtherEngineFailures() {
		doThrow(new ProcessEngineException("boom")).when(runtimeService).removeVariableLocal("exec-1", "amount");

		assertThatThrownBy(() -> activityProvider.deleteVariableByExecutionId("exec-1", "amount", user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("Cannot delete exec-1 variable amount");
	}

	@Test
	void deleteVariableHistoryInstance_removesTheHistoricVariable() {
		activityProvider.deleteVariableHistoryInstance("var-1", user);

		verify(historyService).deleteHistoricVariableInstance("var-1");
	}

	@Test
	void deleteVariableHistoryInstance_translatesAnUnknownVariableIntoSystemException() {
		doThrow(new NotFoundException("gone")).when(historyService).deleteHistoricVariableInstance("missing");

		// deliberately rewritten from the engine's 400 to something mapped to 404
		assertThatThrownBy(() -> activityProvider.deleteVariableHistoryInstance("missing", user))
			.isInstanceOf(SystemException.class);
	}
}
