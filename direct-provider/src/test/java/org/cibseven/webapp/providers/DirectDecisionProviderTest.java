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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.history.HistoricDecisionInstanceQuery;
import org.cibseven.bpm.engine.repository.DecisionDefinition;
import org.cibseven.bpm.engine.repository.DecisionDefinitionQuery;
import org.cibseven.bpm.engine.rest.dto.repository.DecisionDefinitionDiagramDto;
import org.cibseven.bpm.engine.rest.mapper.JacksonConfigurator;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.Decision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

public class DirectDecisionProviderTest {

	private DirectProviderUtil directProviderUtil;
	private RepositoryService repositoryService;
	private HistoryService historyService;
	private DecisionDefinitionQuery definitionQuery;
	private DirectDecisionProvider decisionProvider;
	private CIBUser user;

	@BeforeEach
	void setUp() {
		user = new CIBUser("testUser");

		ProcessEngine processEngine = mock(ProcessEngine.class);
		repositoryService = mock(RepositoryService.class);
		historyService = mock(HistoryService.class);
		when(processEngine.getRepositoryService()).thenReturn(repositoryService);
		when(processEngine.getHistoryService()).thenReturn(historyService);

		definitionQuery = mock(DecisionDefinitionQuery.class, withSettings().defaultAnswer(RETURNS_SELF));
		when(repositoryService.createDecisionDefinitionQuery()).thenReturn(definitionQuery);

		ObjectMapper objectMapper = new ObjectMapper();
		JacksonConfigurator.configureObjectMapper(objectMapper);

		directProviderUtil = Mockito.spy(new DirectProviderUtil());
		doReturn(processEngine).when(directProviderUtil).getProcessEngine(any(CIBUser.class));
		doReturn(objectMapper).when(directProviderUtil).getObjectMapper(any(CIBUser.class));

		decisionProvider = new DirectDecisionProvider(directProviderUtil);
	}

	private DecisionDefinition mockDefinition(String id, String key, String name) {
		DecisionDefinition definition = mock(DecisionDefinition.class);
		when(definition.getId()).thenReturn(id);
		when(definition.getKey()).thenReturn(key);
		when(definition.getName()).thenReturn(name);
		return definition;
	}

	@Test
	void getDecisionDefinitionList_mapsEveryDefinition() {
		DecisionDefinition definition = mockDefinition("dec-1", "risk", "Risk rating");
		when(definitionQuery.list()).thenReturn(List.of(definition));

		Collection<Decision> decisions = decisionProvider.getDecisionDefinitionList(new HashMap<>(), user);

		assertThat(decisions).hasSize(1);
		assertThat(decisions.iterator().next().getKey()).isEqualTo("risk");
	}

	@Test
	void getDecisionDefinitionListCount_returnsTheQueryCount() {
		when(definitionQuery.count()).thenReturn(4L);

		assertThat(decisionProvider.getDecisionDefinitionListCount(new HashMap<>(), user)).isEqualTo(4L);
	}

	@Test
	void getDecisionDefinitionByKey_looksUpTheLatestUntenantedVersion() {
		DecisionDefinition definition = mockDefinition("dec-1", "risk", "Risk rating");
		when(definitionQuery.singleResult()).thenReturn(definition);

		Decision decision = decisionProvider.getDecisionDefinitionByKey("risk", user);

		assertThat(decision.getId()).isEqualTo("dec-1");
		verify(definitionQuery).decisionDefinitionKey("risk");
		verify(definitionQuery).latestVersion();
		// a null tenant means the untenanted definition, not "any tenant"
		verify(definitionQuery).withoutTenantId();
	}

	@Test
	void getDecisionDefinitionByKeyAndTenant_restrictsToThatTenant() {
		DecisionDefinition definition = mockDefinition("dec-1", "risk", "Risk rating");
		when(definitionQuery.singleResult()).thenReturn(definition);

		decisionProvider.getDecisionDefinitionByKeyAndTenant("risk", "tenant-a", user);

		verify(definitionQuery).tenantIdIn("tenant-a");
	}

	@Test
	void getDecisionDefinitionByKey_throwsWhenNothingMatches() {
		when(definitionQuery.singleResult()).thenReturn(null);

		assertThatThrownBy(() -> decisionProvider.getDecisionDefinitionByKey("missing", user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("No matching decision definition with key: missing");
	}

	@Test
	void getDecisionDefinitionById_skipsTheInstanceCountWithoutExtraInfo() {
		DecisionDefinition definition = mockDefinition("dec-1", "risk", "Risk rating");
		when(repositoryService.getDecisionDefinition("dec-1")).thenReturn(definition);

		Decision decision = decisionProvider.getDecisionDefinitionById("dec-1", Optional.empty(), user);

		assertThat(decision.getId()).isEqualTo("dec-1");
		verify(historyService, Mockito.never()).createHistoricDecisionInstanceQuery();
	}

	@Test
	void getDecisionDefinitionById_withExtraInfoCountsHistoricInstances() {
		DecisionDefinition definition = mockDefinition("dec-1", "risk", "Risk rating");
		when(repositoryService.getDecisionDefinition("dec-1")).thenReturn(definition);
		HistoricDecisionInstanceQuery historyQuery =
			mock(HistoricDecisionInstanceQuery.class, withSettings().defaultAnswer(RETURNS_SELF));
		when(historyService.createHistoricDecisionInstanceQuery()).thenReturn(historyQuery);
		when(historyQuery.count()).thenReturn(12L);

		Decision decision = decisionProvider.getDecisionDefinitionById("dec-1", Optional.of(true), user);

		assertThat(decision.getAllInstances()).isEqualTo(12L);
	}

	@Test
	void getXmlByKey_readsTheDmnFromTheEngine() {
		DecisionDefinition definition = mockDefinition("dec-1", "risk", "Risk rating");
		when(definitionQuery.singleResult()).thenReturn(definition);
		String dmn = "<definitions id=\"risk\"/>";
		when(repositoryService.getDecisionModel("dec-1"))
			.thenReturn(new ByteArrayInputStream(dmn.getBytes(StandardCharsets.UTF_8)));

		DecisionDefinitionDiagramDto result =
			(DecisionDefinitionDiagramDto) decisionProvider.getXmlByKey("risk", user);

		assertThat(result.getId()).isEqualTo("dec-1");
		assertThat(result.getDmnXml()).isEqualTo(dmn);
	}

	@Test
	void getXmlById_readsTheModelDirectlyWithoutResolvingTheDefinition() {
		String dmn = "<definitions id=\"risk\"/>";
		when(repositoryService.getDecisionModel("dec-1"))
			.thenReturn(new ByteArrayInputStream(dmn.getBytes(StandardCharsets.UTF_8)));

		DecisionDefinitionDiagramDto result =
			(DecisionDefinitionDiagramDto) decisionProvider.getXmlById("dec-1", user);

		assertThat(result.getDmnXml()).isEqualTo(dmn);
		// the id path needs no definition lookup, unlike the key path
		verify(repositoryService, Mockito.never()).getDecisionDefinition(Mockito.anyString());
	}

	@Test
	void getXmlById_wrapsAnEngineFailureInSystemException() {
		when(repositoryService.getDecisionModel("dec-1"))
			.thenThrow(new org.cibseven.bpm.engine.ProcessEngineException("no such model"));

		assertThatThrownBy(() -> decisionProvider.getXmlById("dec-1", user))
			.isInstanceOf(SystemException.class);
	}

	@Test
	void getDiagramById_throwsWhenTheDecisionHasNoDiagram() {
		DecisionDefinition definition = mockDefinition("dec-1", "risk", "Risk rating");
		when(repositoryService.getDecisionDefinition("dec-1")).thenReturn(definition);
		when(repositoryService.getDecisionDiagram("dec-1")).thenReturn(null);

		assertThatThrownBy(() -> decisionProvider.getDiagramById("dec-1", user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("Diagram of decision dec-1 not found");
	}

	@Test
	void updateHistoryTTLByKey_appliesTheTtlToTheResolvedDefinition() {
		DecisionDefinition definition = mockDefinition("dec-1", "risk", "Risk rating");
		when(definitionQuery.singleResult()).thenReturn(definition);
		Map<String, Object> data = new HashMap<>();
		data.put("historyTimeToLive", 30);

		decisionProvider.updateHistoryTTLByKey(data, "risk", user);

		verify(repositoryService).updateDecisionDefinitionHistoryTimeToLive("dec-1", 30);
	}

	@Test
	void getHistoricDecisionInstanceCount_returnsTheQueryCount() {
		HistoricDecisionInstanceQuery historyQuery =
			mock(HistoricDecisionInstanceQuery.class, withSettings().defaultAnswer(RETURNS_SELF));
		when(historyService.createHistoricDecisionInstanceQuery()).thenReturn(historyQuery);
		when(historyQuery.count()).thenReturn(7L);

		assertThat(decisionProvider.getHistoricDecisionInstanceCount(new HashMap<>(), user)).isEqualTo(7L);
	}
}
