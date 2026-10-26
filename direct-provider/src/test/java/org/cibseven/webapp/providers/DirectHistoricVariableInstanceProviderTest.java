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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.history.HistoricVariableInstanceQuery;
import org.cibseven.bpm.engine.rest.mapper.JacksonConfigurator;
import org.cibseven.webapp.auth.CIBUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

public class DirectHistoricVariableInstanceProviderTest {

	private HistoricVariableInstanceQuery query;
	private DirectHistoricVariableInstanceProvider provider;
	private CIBUser user;

	@BeforeEach
	void setUp() {
		user = new CIBUser("testUser");

		ProcessEngine processEngine = mock(ProcessEngine.class);
		HistoryService historyService = mock(HistoryService.class);
		when(processEngine.getHistoryService()).thenReturn(historyService);

		query = mock(HistoricVariableInstanceQuery.class, withSettings().defaultAnswer(RETURNS_SELF));
		when(historyService.createHistoricVariableInstanceQuery()).thenReturn(query);
		when(query.list()).thenReturn(List.of());

		ObjectMapper objectMapper = new ObjectMapper();
		JacksonConfigurator.configureObjectMapper(objectMapper);

		DirectProviderUtil directProviderUtil = Mockito.spy(new DirectProviderUtil());
		doReturn(processEngine).when(directProviderUtil).getProcessEngine(any(CIBUser.class));
		doReturn(objectMapper).when(directProviderUtil).getObjectMapper(any(CIBUser.class));

		provider = new DirectHistoricVariableInstanceProvider(directProviderUtil);
	}

	@Test
	void findHistoricVariableInstances_forwardsTheBodyFiltersToTheEngineQuery() {
		// what an embedded form sends: history.variableInstance({ taskIdIn: [...] })
		provider.findHistoricVariableInstances(Map.of("taskIdIn", List.of("task-1", "task-2")),
				Optional.empty(), Optional.empty(), null, user);

		verify(query).taskIdIn("task-1", "task-2");
	}

	@Test
	void findHistoricVariableInstances_neverLoadsFileOrBytesContents() {
		// engine-rest's list endpoint does the same: a list answers with metadata, the content
		// of a file or bytes variable is fetched one variable at a time
		provider.findHistoricVariableInstances(Map.of(), Optional.empty(), Optional.empty(), null, user);

		verify(query).disableBinaryFetching();
	}

	@Test
	void findHistoricVariableInstances_keepsObjectsSerializedOnlyWhenAskedTo() {
		provider.findHistoricVariableInstances(Map.of(), Optional.empty(), Optional.empty(), false, user);
		verify(query).disableCustomObjectDeserialization();
	}

	@Test
	void findHistoricVariableInstances_leavesDeserializationToTheEngineByDefault() {
		provider.findHistoricVariableInstances(Map.of(), Optional.empty(), Optional.empty(), null, user);
		verify(query, never()).disableCustomObjectDeserialization();
	}
}
