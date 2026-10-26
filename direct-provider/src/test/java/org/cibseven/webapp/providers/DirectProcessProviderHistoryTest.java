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
import static org.cibseven.webapp.providers.ProcessInstanceRuntimeHistoryTestData.INCIDENT_ID_1;
import static org.cibseven.webapp.providers.ProcessInstanceRuntimeHistoryTestData.INSTANCE_ID_1;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.exception.NotFoundException;
import org.cibseven.bpm.engine.history.HistoricProcessInstance;
import org.cibseven.bpm.engine.history.HistoricProcessInstanceQuery;
import org.cibseven.bpm.engine.management.ActivityStatistics;
import org.cibseven.bpm.engine.management.ActivityStatisticsQuery;
import org.cibseven.bpm.engine.rest.mapper.JacksonConfigurator;
import org.cibseven.bpm.engine.runtime.ProcessInstanceQuery;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.NoObjectFoundException;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.HistoryProcessInstance;
import org.cibseven.webapp.rest.model.Incident;
import org.cibseven.webapp.rest.model.ProcessStatistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The history, statistics and lifecycle side of {@link DirectProcessProvider}. Definitions live in
 * {@link DirectProcessProviderDefinitionsTest}, running instances in {@link DirectProcessProviderTest}.
 */
public class DirectProcessProviderHistoryTest {

	private DirectProviderUtil directProviderUtil;
	private RepositoryService repositoryService;
	private RuntimeService runtimeService;
	private HistoryService historyService;
	private ManagementService managementService;
	private org.cibseven.bpm.engine.impl.HistoricProcessInstanceQueryImpl historyQuery;
	private ProcessInstanceQuery instanceQuery;
	private SevenDirectProvider sevenDirectProvider;
	private DirectProcessProvider processProvider;
	private CIBUser user;

	@BeforeEach
	void setUp() {
		user = new CIBUser("testUser");

		ProcessEngine processEngine = mock(ProcessEngine.class);
		repositoryService = mock(RepositoryService.class);
		runtimeService = mock(RuntimeService.class);
		historyService = mock(HistoryService.class);
		managementService = mock(ManagementService.class);
		when(processEngine.getRepositoryService()).thenReturn(repositoryService);
		when(processEngine.getRuntimeService()).thenReturn(runtimeService);
		when(processEngine.getHistoryService()).thenReturn(historyService);
		when(processEngine.getManagementService()).thenReturn(managementService);

		// HistoricProcessInstanceQueryDto casts the query to the impl when applying or-queries,
		// so the mock has to be of the concrete type
		historyQuery = mock(org.cibseven.bpm.engine.impl.HistoricProcessInstanceQueryImpl.class,
			withSettings().defaultAnswer(RETURNS_SELF));
		when(historyService.createHistoricProcessInstanceQuery()).thenReturn(historyQuery);
		instanceQuery = mock(ProcessInstanceQuery.class, withSettings().defaultAnswer(RETURNS_SELF));
		when(runtimeService.createProcessInstanceQuery()).thenReturn(instanceQuery);

		ObjectMapper objectMapper = new ObjectMapper();
		JacksonConfigurator.configureObjectMapper(objectMapper);

		directProviderUtil = Mockito.spy(new DirectProviderUtil());
		doReturn(processEngine).when(directProviderUtil).getProcessEngine(any(CIBUser.class));
		doReturn(objectMapper).when(directProviderUtil).getObjectMapper(any(CIBUser.class));

		sevenDirectProvider = mock(SevenDirectProvider.class);
		processProvider = new DirectProcessProvider(directProviderUtil, sevenDirectProvider);
	}

	// ---------- history queries ----------

	@Test
	void findProcessesInstancesHistoryByKey_filtersByDefinitionKey() {
		when(historyQuery.list()).thenReturn(List.of());

		Collection<HistoryProcessInstance> result = processProvider
			.findProcessesInstancesHistory("invoice", Optional.empty(), null, null, user);

		assertThat(result).isEmpty();
		verify(historyQuery).processDefinitionKey("invoice");
	}

	@Test
	void findProcessesInstancesHistoryByKey_appliesTheActiveFilterWhenGiven() {
		when(historyQuery.list()).thenReturn(List.of());

		processProvider.findProcessesInstancesHistory("invoice", Optional.of(true), null, null, user);

		verify(historyQuery).active();
	}

	@Test
	void findProcessesInstancesHistoryById_filtersByDefinitionId() {
		when(historyQuery.list()).thenReturn(List.of());

		processProvider.findProcessesInstancesHistoryById(
			"id-1", Optional.empty(), Optional.empty(), null, null, "", user);

		verify(historyQuery).processDefinitionId("id-1");
	}

	@Test
	void findProcessesInstancesHistoryById_narrowsToAnActivityWhenGiven() {
		when(historyQuery.list()).thenReturn(List.of());

		processProvider.findProcessesInstancesHistoryById(
			"id-1", Optional.of("serviceTask"), Optional.empty(), null, null, "", user);

		verify(historyQuery).activityIdIn("serviceTask");
	}

	@Test
	void findProcessesInstancesHistoryById_searchesBusinessKeyAndInstanceIdForFreeText() {
		when(historyQuery.list()).thenReturn(List.of());

		processProvider.findProcessesInstancesHistoryById(
			"id-1", Optional.empty(), Optional.empty(), null, null, "INV-42", user);

		// the search box matches either a business key fragment or an exact instance id, which the
		// DTO applies as a nested or-query rather than as two top-level filters
		verify(historyQuery).addOrQuery(any());
	}

	@Test
	void findProcessesInstancesHistoryById_mapsTheEngineResults() {
		HistoricProcessInstance instance = mock(HistoricProcessInstance.class);
		when(instance.getId()).thenReturn("pi-1");
		when(instance.getProcessDefinitionKey()).thenReturn("invoice");
		when(historyQuery.list()).thenReturn(List.of(instance));

		Collection<HistoryProcessInstance> result = processProvider.findProcessesInstancesHistoryById(
			"id-1", Optional.empty(), Optional.empty(), null, null, "", user);

		assertThat(result).singleElement()
			.extracting(HistoryProcessInstance::getId).isEqualTo("pi-1");
	}

	@Test
	void countProcessesInstancesHistory_returnsTheQueryCount() {
		when(historyQuery.count()).thenReturn(17L);

		assertThat(processProvider.countProcessesInstancesHistory(new HashMap<>(), user)).isEqualTo(17L);
	}

	@Test
	void findProcessesInstancesHistoryWithFilters_dropsTheWebclientOnlyFetchIncidentsFlag() {
		when(historyQuery.list()).thenReturn(List.of());
		Map<String, Object> filters = new HashMap<>();
		filters.put("fetchIncidents", Boolean.TRUE);

		processProvider.findProcessesInstancesHistory(filters, Optional.empty(), Optional.empty(), user);

		// the engine would reject an unknown query property, so it has to be removed first
		assertThat(filters).doesNotContainKey("fetchIncidents");
	}

	@Test
	void findProcessesInstancesHistory_fetchesIncidentsPerInstanceWhenNoProcessDefinitionIdIsGiven() {
		// this is exactly the filter shape findProcessesInstancesRuntime builds: a set of instance
		// ids plus fetchIncidents, with no processDefinitionId
		HistoricProcessInstance instance = mock(HistoricProcessInstance.class);
		when(instance.getId()).thenReturn(INSTANCE_ID_1);
		when(historyQuery.list()).thenReturn(List.of(instance));

		Incident incident = new Incident();
		incident.setId(INCIDENT_ID_1);
		incident.setProcessInstanceId(INSTANCE_ID_1);
		when(sevenDirectProvider.findIncidentByInstanceId(INSTANCE_ID_1, user)).thenReturn(List.of(incident));

		Map<String, Object> filters = new HashMap<>();
		filters.put("processInstanceIds", Set.of(INSTANCE_ID_1));
		filters.put("fetchIncidents", Boolean.TRUE);

		Collection<HistoryProcessInstance> result = processProvider.findProcessesInstancesHistory(
			filters, Optional.empty(), Optional.empty(), user);

		assertThat(result).singleElement()
			.extracting(HistoryProcessInstance::getIncidents, org.assertj.core.api.InstanceOfAssertFactories.list(Incident.class))
			.extracting(Incident::getId)
			.containsExactly(INCIDENT_ID_1);
	}

	// ---------- statistics ----------

	@Test
	void findProcessStatistics_mapsTheActivityStatistics() {
		ActivityStatisticsQuery query = mock(ActivityStatisticsQuery.class);
		when(managementService.createActivityStatisticsQuery("id-1")).thenReturn(query);
		ActivityStatistics statistics = mock(ActivityStatistics.class);
		when(statistics.getId()).thenReturn("serviceTask");
		when(statistics.getInstances()).thenReturn(3);
		when(query.unlimitedList()).thenReturn(List.of(statistics));

		Collection<ProcessStatistics> result = processProvider.findProcessStatistics("id-1", user);

		assertThat(result).singleElement()
			.extracting(ProcessStatistics::getId, ProcessStatistics::getInstances)
			.containsExactly("serviceTask", 3L);
	}

	@Test
	void findProcessStatistics_returnsEmptyWhenTheProcessHasNoActivityStatistics() {
		ActivityStatisticsQuery query = mock(ActivityStatisticsQuery.class);
		when(managementService.createActivityStatisticsQuery("id-1")).thenReturn(query);
		when(query.unlimitedList()).thenReturn(List.of());

		assertThat(processProvider.findProcessStatistics("id-1", user)).isEmpty();
	}

	// ---------- instance lookup ----------

	@Test
	void findProcessInstance_throwsWhenTheInstanceIsGone() {
		when(instanceQuery.singleResult()).thenReturn(null);

		assertThatThrownBy(() -> processProvider.findProcessInstance("missing", user))
			.isInstanceOf(NoObjectFoundException.class);
	}

	// ---------- lifecycle ----------

	@Test
	void updateHistoryTimeToLive_appliesTheTtlToTheDefinition() {
		Map<String, Object> data = new HashMap<>();
		data.put("historyTimeToLive", 30);

		processProvider.updateHistoryTimeToLive("id-1", data, user);

		verify(repositoryService).updateProcessDefinitionHistoryTimeToLive("id-1", 30);
	}

	@Test
	void updateHistoryTimeToLive_acceptsAnUnlimitedTtl() {
		Map<String, Object> data = new HashMap<>();
		data.put("historyTimeToLive", null);

		processProvider.updateHistoryTimeToLive("id-1", data, user);

		// null means "keep forever", which is a legitimate setting
		verify(repositoryService).updateProcessDefinitionHistoryTimeToLive("id-1", null);
	}

	@Test
	void deleteProcessInstanceFromHistory_removesTheHistoricInstance() {
		processProvider.deleteProcessInstanceFromHistory("pi-1", user);

		verify(historyService).deleteHistoricProcessInstance("pi-1");
	}

	@Test
	void deleteProcessInstanceFromHistory_wrapsABadRequest() {
		doThrow(new BadUserRequestException("still running"))
			.when(historyService).deleteHistoricProcessInstance("pi-1");

		assertThatThrownBy(() -> processProvider.deleteProcessInstanceFromHistory("pi-1", user))
			.isInstanceOf(SystemException.class);
	}

	@Test
	void deleteProcessDefinition_cascadesByDefault() {
		processProvider.deleteProcessDefinition("id-1", Optional.empty(), user);

		// omitting the flag deletes the running instances too
		verify(repositoryService).deleteProcessDefinition("id-1", true);
	}

	@Test
	void deleteProcessDefinition_honoursAnExplicitCascadeFlag() {
		processProvider.deleteProcessDefinition("id-1", Optional.of(false), user);

		verify(repositoryService).deleteProcessDefinition("id-1", false);
	}

	@Test
	void deleteProcessDefinition_translatesAnUnknownDefinition() {
		doThrow(new NotFoundException("gone")).when(repositoryService).deleteProcessDefinition("missing", true);

		assertThatThrownBy(() -> processProvider.deleteProcessDefinition("missing", Optional.empty(), user))
			.isInstanceOf(SystemException.class);
	}

	// ---------- current instances ----------

	@Test
	void findCurrentProcessesInstances_returnsEmptyWithoutAFollowUpIncidentQuery() {
		when(instanceQuery.list()).thenReturn(List.of());

		assertThat(processProvider.findCurrentProcessesInstances(
			new HashMap<>(), Optional.empty(), Optional.empty(), user)).isEmpty();
	}
}
