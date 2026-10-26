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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.exception.NotFoundException;
import org.cibseven.bpm.engine.history.HistoricProcessInstance;
import org.cibseven.bpm.engine.history.HistoricProcessInstanceQuery;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.repository.ProcessDefinitionQuery;
import org.cibseven.bpm.engine.rest.mapper.JacksonConfigurator;
import org.cibseven.webapp.Data;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.Process;
import org.cibseven.webapp.rest.model.ProcessDiagram;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Covers the process-definition side of {@link DirectProcessProvider}.
 * The process-instance side lives in {@link DirectProcessProviderTest}.
 */
public class DirectProcessProviderDefinitionsTest {

	private DirectProviderUtil directProviderUtil;
	private RepositoryService repositoryService;
	private RuntimeService runtimeService;
	private HistoryService historyService;
	private ProcessDefinitionQuery definitionQuery;
	private DirectProcessProvider processProvider;
	private CIBUser user;

	@BeforeEach
	void setUp() {
		user = new CIBUser("testUser");

		ProcessEngine processEngine = mock(ProcessEngine.class);
		repositoryService = mock(RepositoryService.class);
		runtimeService = mock(RuntimeService.class);
		historyService = mock(HistoryService.class);
		when(processEngine.getRepositoryService()).thenReturn(repositoryService);
		when(processEngine.getRuntimeService()).thenReturn(runtimeService);
		when(processEngine.getHistoryService()).thenReturn(historyService);

		definitionQuery = mock(ProcessDefinitionQuery.class, withSettings().defaultAnswer(RETURNS_SELF));
		when(repositoryService.createProcessDefinitionQuery()).thenReturn(definitionQuery);

		ObjectMapper objectMapper = new ObjectMapper();
		JacksonConfigurator.configureObjectMapper(objectMapper);

		directProviderUtil = Mockito.spy(new DirectProviderUtil());
		doReturn(processEngine).when(directProviderUtil).getProcessEngine(any(CIBUser.class));
		doReturn(objectMapper).when(directProviderUtil).getObjectMapper(any(CIBUser.class));

		processProvider = new DirectProcessProvider(directProviderUtil, mock(SevenDirectProvider.class));
	}

	private ProcessDefinition mockDefinition(String id, String key, String name, int version) {
		ProcessDefinition definition = mock(ProcessDefinition.class);
		when(definition.getId()).thenReturn(id);
		when(definition.getKey()).thenReturn(key);
		when(definition.getName()).thenReturn(name);
		when(definition.getVersion()).thenReturn(version);
		return definition;
	}

	@Test
	void findProcesses_mapsEngineDefinitionsOntoTheWebclientModel() {
		ProcessDefinition definition = mockDefinition("id-1", "invoice", "Invoice Receipt", 3);
		when(definitionQuery.list()).thenReturn(List.of(definition));

		Collection<Process> processes = processProvider.findProcesses(user);

		assertThat(processes).hasSize(1);
		Process process = processes.iterator().next();
		assertThat(process.getId()).isEqualTo("id-1");
		assertThat(process.getKey()).isEqualTo("invoice");
		assertThat(process.getName()).isEqualTo("Invoice Receipt");
		// the engine models the version as an int, the webclient as a String
		assertThat(process.getVersion()).isEqualTo("3");
	}

	@Test
	void findProcesses_asksOnlyForTheLatestVersionOfEachDefinition() {
		when(definitionQuery.list()).thenReturn(List.of());

		processProvider.findProcesses(user);

		// the process list must not show one row per historical version
		verify(definitionQuery).latestVersion();
	}

	@Test
	void findProcesses_returnsEmptyWhenTheEngineHasNoDefinitions() {
		when(definitionQuery.list()).thenReturn(List.of());

		assertThat(processProvider.findProcesses(user)).isEmpty();
	}

	@Test
	void findProcessByDefinitionKey_restrictsToTheGivenTenant() {
		ProcessDefinition definition = mockDefinition("id-1", "invoice", "Invoice Receipt", 1);
		when(definitionQuery.singleResult()).thenReturn(definition);

		Process process = processProvider.findProcessByDefinitionKey("invoice", "tenant-a", user);

		assertThat(process.getKey()).isEqualTo("invoice");
		verify(definitionQuery).processDefinitionKey("invoice");
		verify(definitionQuery).latestVersion();
		verify(definitionQuery).tenantIdIn("tenant-a");
	}

	@Test
	void findProcessByDefinitionKey_withoutTenantExcludesTenantedDefinitions() {
		ProcessDefinition definition = mockDefinition("id-1", "invoice", "Invoice Receipt", 1);
		when(definitionQuery.singleResult()).thenReturn(definition);

		processProvider.findProcessByDefinitionKey("invoice", null, user);

		// a null tenant means "the untenanted definition", not "any tenant"
		verify(definitionQuery).withoutTenantId();
	}

	@Test
	void findProcessByDefinitionKey_throwsWhenNothingMatches() {
		when(definitionQuery.singleResult()).thenReturn(null);

		assertThatThrownBy(() -> processProvider.findProcessByDefinitionKey("invoice", null, user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("Process instance not found: invoice");
	}

	@Test
	void findProcessByDefinitionKey_throwsNamingTheTenantWhenOneWasGiven() {
		when(definitionQuery.singleResult()).thenReturn(null);

		assertThatThrownBy(() -> processProvider.findProcessByDefinitionKey("invoice", "tenant-a", user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("tenantId tenant-a");
	}

	@Test
	void findProcessVersionsByDefinitionKey_lazyLoadSkipsTheInstanceCounts() {
		ProcessDefinition v2 = mockDefinition("id-2", "invoice", "Invoice Receipt", 2);
		ProcessDefinition v1 = mockDefinition("id-1", "invoice", "Invoice Receipt", 1);
		when(definitionQuery.list()).thenReturn(List.of(v2, v1));

		Collection<Process> versions =
			processProvider.findProcessVersionsByDefinitionKey("invoice", null, Optional.of(true), user);

		assertThat(versions).extracting(Process::getVersion).containsExactly("2", "1");
		verify(historyService, Mockito.never()).createHistoricProcessInstanceQuery();
	}

	@Test
	void findProcessVersionsByDefinitionKey_withoutLazyLoadCountsInstancesPerVersion() {
		ProcessDefinition v2 = mockDefinition("id-2", "invoice", "Invoice Receipt", 2);
		when(definitionQuery.list()).thenReturn(List.of(v2));
		HistoricProcessInstanceQuery historyQuery =
			mock(HistoricProcessInstanceQuery.class, withSettings().defaultAnswer(RETURNS_SELF));
		when(historyService.createHistoricProcessInstanceQuery()).thenReturn(historyQuery);
		HistoricProcessInstance instance = mock(HistoricProcessInstance.class);
		when(historyQuery.unlimitedList()).thenReturn(
			Collections.nCopies(9, instance),   // all
			Collections.nCopies(2, instance),   // unfinished
			Collections.nCopies(7, instance));  // completed

		Collection<Process> versions =
			processProvider.findProcessVersionsByDefinitionKey("invoice", null, Optional.empty(), user);

		Process version = versions.iterator().next();
		assertThat(version.getAllInstances()).isEqualTo(9L);
		assertThat(version.getRunningInstances()).isEqualTo(2L);
		assertThat(version.getCompletedInstances()).isEqualTo(7L);
	}

	/**
	 * TODO KNOWN BUG (not fixed, performance): the counts above cost three history queries per
	 * version - all, unfinished, completed - so the version list of a process with many versions
	 * costs 3xN engine round-trips. One query per version can carry all three counts. This test
	 * asserts that budget; it fails until the three queries are folded into one.
	 */
	@Test
	@Disabled("KNOWN BUG: findProcessVersionsByDefinitionKey runs three history queries per version instead of one")
	void findProcessVersionsByDefinitionKey_countsInstancesWithOneHistoryQueryPerVersion() {
		ProcessDefinition v2 = mockDefinition("id-2", "invoice", "Invoice Receipt", 2);
		when(definitionQuery.list()).thenReturn(List.of(v2));
		HistoricProcessInstanceQuery historyQuery =
			mock(HistoricProcessInstanceQuery.class, withSettings().defaultAnswer(RETURNS_SELF));
		when(historyService.createHistoricProcessInstanceQuery()).thenReturn(historyQuery);
		HistoricProcessInstance instance = mock(HistoricProcessInstance.class);
		when(historyQuery.unlimitedList()).thenReturn(
			Collections.nCopies(9, instance),
			Collections.nCopies(2, instance),
			Collections.nCopies(7, instance));

		processProvider.findProcessVersionsByDefinitionKey("invoice", null, Optional.empty(), user);

		verify(historyService, Mockito.times(1)).createHistoricProcessInstanceQuery();
	}

	@Test
	void findProcessById_mapsTheDefinitionAndSkipsCountsWhenExtraInfoIsAbsent() {
		ProcessDefinition definition = mockDefinition("id-1", "invoice", "Invoice Receipt", 1);
		when(repositoryService.getProcessDefinition("id-1")).thenReturn(definition);

		Process process = processProvider.findProcessById("id-1", Optional.empty(), user);

		assertThat(process.getId()).isEqualTo("id-1");
		assertThat(process.getAllInstances()).isZero();
		// no history query should have been needed
		verify(historyService, Mockito.never()).createHistoricProcessInstanceQuery();
	}

	@Test
	void findProcessById_withExtraInfoCountsAllUnfinishedAndCompletedInstances() {
		ProcessDefinition definition = mockDefinition("id-1", "invoice", "Invoice Receipt", 1);
		when(repositoryService.getProcessDefinition("id-1")).thenReturn(definition);
		HistoricProcessInstanceQuery historyQuery =
			mock(HistoricProcessInstanceQuery.class, withSettings().defaultAnswer(RETURNS_SELF));
		when(historyService.createHistoricProcessInstanceQuery()).thenReturn(historyQuery);
		when(historyQuery.count()).thenReturn(10L, 4L, 6L);

		Process process = processProvider.findProcessById("id-1", Optional.of(true), user);

		// three separate counts, in this order: all, unfinished, completed
		assertThat(process.getAllInstances()).isEqualTo(10L);
		assertThat(process.getRunningInstances()).isEqualTo(4L);
		assertThat(process.getCompletedInstances()).isEqualTo(6L);
	}

	@Test
	void findProcessById_wrapsEngineFailureInSystemException() {
		when(repositoryService.getProcessDefinition("missing"))
			.thenThrow(new ProcessEngineException("no such definition"));

		assertThatThrownBy(() -> processProvider.findProcessById("missing", Optional.empty(), user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("No matching definition with id missing");
	}

	@Test
	void countProcessesInstancesHistory_passesTheFilterToTheEngineQuery() {
		HistoricProcessInstanceQuery historyQuery =
			mock(HistoricProcessInstanceQuery.class, withSettings().defaultAnswer(RETURNS_SELF));
		when(historyService.createHistoricProcessInstanceQuery()).thenReturn(historyQuery);
		when(historyQuery.count()).thenReturn(42L);

		Map<String, Object> filters = new HashMap<>();
		filters.put("processDefinitionId", "id-1");

		assertThat(processProvider.countProcessesInstancesHistory(filters, user)).isEqualTo(42L);
		verify(historyQuery).processDefinitionId("id-1");
	}

	@Test
	void fetchDiagram_readsTheBpmnXmlFromTheEngine() {
		String xml = "<definitions id=\"d\"/>";
		when(repositoryService.getProcessModel("id-1"))
			.thenReturn(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

		ProcessDiagram diagram = processProvider.fetchDiagram("id-1", user);

		assertThat(diagram.getId()).isEqualTo("id-1");
		assertThat(diagram.getBpmn20Xml()).isEqualTo(xml);
	}

	@Test
	void fetchDiagram_wrapsNotFoundInSystemException() {
		when(repositoryService.getProcessModel("missing")).thenThrow(new NotFoundException("gone"));

		assertThatThrownBy(() -> processProvider.fetchDiagram("missing", user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("No matching definition with id missing");
	}

	@Test
	void fetchDiagram_letsAuthorizationFailuresThrough() {
		when(repositoryService.getProcessModel("id-1")).thenThrow(new AuthorizationException("denied"));

		// must stay an AuthorizationException so the REST layer can answer 403 rather than 500
		assertThatThrownBy(() -> processProvider.fetchDiagram("id-1", user))
			.isInstanceOf(AuthorizationException.class);
	}

	@Test
	void downloadBpmn_wrapsTheDiagramXmlAsANamedBpmnAttachment() {
		String xml = "<definitions id=\"d\"/>";
		when(repositoryService.getProcessModel("id-1"))
			.thenReturn(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

		Data data = processProvider.downloadBpmn("id-1", "invoice.bpmn", user);

		assertThat(data.getName()).isEqualTo("invoice.bpmn");
		assertThat(data.getContentType()).isEqualTo("application/bpmn+xml");
		assertThat(data.getSize()).isEqualTo(xml.getBytes(StandardCharsets.UTF_8).length);
	}

	@Test
	void deleteProcessInstance_stopsTheInstanceAsExternallyTerminated() {
		processProvider.deleteProcessInstance("instance-1", user);

		// same as engine-rest, so the historic state is EXTERNALLY_TERMINATED and not INTERNALLY_TERMINATED
		verify(runtimeService).deleteProcessInstance("instance-1", null, false, true);
		verify(runtimeService, never()).deleteProcessInstance("instance-1", null);
	}

	@Test
	void suspendProcessDefinition_wrapsIllegalArgumentInSystemException() {
		// an unparseable execution date is the realistic trigger
		assertThatThrownBy(() -> processProvider.suspendProcessDefinition(
				"id-1", Boolean.TRUE, Boolean.FALSE, "not-a-date", user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("Could not update the suspension state of Process Definitions");
	}
}
