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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.FormService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.impl.RuntimeServiceImpl;
import org.cibseven.bpm.engine.impl.variable.ValueTypeResolverImpl;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.repository.ProcessDefinitionQuery;
import org.cibseven.bpm.engine.rest.mapper.JacksonConfigurator;
import org.cibseven.bpm.engine.variable.VariableMap;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.ObjectValue;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.Variable;
import org.cibseven.webapp.rest.model.VariableHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The form- and process-instance-variable side of {@link DirectVariableProvider}; the binary
 * download and modification paths live in {@link DirectVariableProviderTest}.
 */
public class DirectVariableProviderFormsTest {

	private DirectProviderUtil directProviderUtil;
	private FormService formService;
	private RepositoryService repositoryService;
	private ProcessDefinitionQuery definitionQuery;
	private DirectVariableProvider variableProvider;
	private CIBUser user;

	@BeforeEach
	void setUp() {
		user = new CIBUser("testUser");

		ProcessEngine processEngine = mock(ProcessEngine.class);
		formService = mock(FormService.class);
		repositoryService = mock(RepositoryService.class);
		when(processEngine.getFormService()).thenReturn(formService);
		when(processEngine.getRepositoryService()).thenReturn(repositoryService);
		when(processEngine.getRuntimeService()).thenReturn(mock(RuntimeServiceImpl.class));
		ProcessEngineConfiguration configuration = mock(ProcessEngineConfiguration.class);
		when(configuration.getValueTypeResolver()).thenReturn(new ValueTypeResolverImpl());
		when(processEngine.getProcessEngineConfiguration()).thenReturn(configuration);

		definitionQuery = mock(ProcessDefinitionQuery.class, withSettings().defaultAnswer(RETURNS_SELF));
		when(repositoryService.createProcessDefinitionQuery()).thenReturn(definitionQuery);

		ObjectMapper objectMapper = new ObjectMapper();
		JacksonConfigurator.configureObjectMapper(objectMapper);

		directProviderUtil = Mockito.spy(new DirectProviderUtil());
		doReturn(processEngine).when(directProviderUtil).getProcessEngine(any(CIBUser.class));
		doReturn(objectMapper).when(directProviderUtil).getObjectMapper(any(CIBUser.class));
		doReturn(DirectProviderUtil.createJsonMapper(objectMapper)).when(directProviderUtil).getJsonMapper(any(CIBUser.class));

		variableProvider = new DirectVariableProvider(directProviderUtil);
	}

	private VariableMap variables() {
		return Variables.createVariables().putValue("amount", 42).putValue("customer", "ACME");
	}

	// ---------- task form variables ----------

	@Test
	void fetchFormVariables_mapsTheEngineVariableMap() {
		when(formService.getTaskFormVariables("task-1", null, true)).thenReturn(variables());

		Map<String, Variable> result = variableProvider.fetchFormVariables("task-1", true, user);

		assertThat(result).containsOnlyKeys("amount", "customer");
		assertThat(result.get("amount").getValue()).isEqualTo(42);
	}

	@Test
	void fetchFormVariables_withoutNamesAsksForAllOfThem() {
		when(formService.getTaskFormVariables("task-1", null, true)).thenReturn(variables());

		variableProvider.fetchFormVariables("task-1", true, user);

		// the single-argument overload delegates with a null name list, meaning "everything"
		verify(formService).getTaskFormVariables("task-1", null, true);
	}

	@Test
	void fetchFormVariables_forwardsAnExplicitNameList() {
		List<String> names = List.of("amount");
		when(formService.getTaskFormVariables("task-1", names, false)).thenReturn(variables());

		variableProvider.fetchFormVariables(names, "task-1", false, user);

		verify(formService).getTaskFormVariables("task-1", names, false);
	}

	@Test
	void fetchFormVariables_returnsAnEmptyMapWhenTheFormHasNoVariables() {
		when(formService.getTaskFormVariables("task-1", null, true))
			.thenReturn(Variables.createVariables());

		assertThat(variableProvider.fetchFormVariables("task-1", true, user)).isEmpty();
	}

	// ---------- process start form variables ----------

	@Test
	void fetchProcessFormVariables_resolvesTheLatestUntenantedDefinitionFirst() {
		ProcessDefinition definition = mock(ProcessDefinition.class);
		when(definition.getId()).thenReturn("definition-1");
		when(definitionQuery.singleResult()).thenReturn(definition);
		when(formService.getStartFormVariables("definition-1", null, true)).thenReturn(variables());

		Map<String, Variable> result = variableProvider.fetchProcessFormVariables("invoice", true, user);

		assertThat(result).containsOnlyKeys("amount", "customer");
		verify(definitionQuery).processDefinitionKey("invoice");
		verify(definitionQuery).withoutTenantId();
		verify(definitionQuery).latestVersion();
	}

	@Test
	void fetchProcessFormVariables_throwsWhenTheDefinitionIsUnknown() {
		when(definitionQuery.singleResult()).thenReturn(null);

		assertThatThrownBy(() -> variableProvider.fetchProcessFormVariables("missing", true, user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("No matching process definition with key: missing");
	}

	@Test
	void fetchProcessFormVariablesById_skipsTheDefinitionLookup() {
		when(formService.getStartFormVariables("definition-1", null, true)).thenReturn(variables());

		Map<String, Variable> result = variableProvider.fetchProcessFormVariablesById("definition-1", user);

		assertThat(result).containsOnlyKeys("amount", "customer");
		// addressing by id needs no query at all
		verify(repositoryService, Mockito.never()).createProcessDefinitionQuery();
	}

	// ---------- activity variables ----------

	@Test
	void fetchActivityVariables_scopesTheQueryToTheActivityInstance() {
		Variable variable = new Variable();
		variable.setName("amount");
		variable.setValue(42);
		doReturn(List.of(variable)).when(directProviderUtil)
			.queryVariableInstances(any(), any(), any(), anyBoolean(), any(CIBUser.class));

		java.util.Collection<VariableHistory> result =
			variableProvider.fetchActivityVariables("activity-1", user);

		assertThat(result).singleElement()
			.extracting(VariableHistory::getName).isEqualTo("amount");
	}

	@Test
	void fetchActivityVariables_returnsEmptyWhenTheActivityHasNoVariables() {
		doReturn(List.of()).when(directProviderUtil)
			.queryVariableInstances(any(), any(), any(), anyBoolean(), any(CIBUser.class));

		assertThat(variableProvider.fetchActivityVariables("activity-1", user)).isEmpty();
	}

	// ---------- writing variables back ----------

	@Test
	void saveVariableInProcessInstanceId_sendsEveryVariableAsAModification() {
		Variable variable = new Variable();
		variable.setName("amount");
		variable.setType("Integer");
		variable.setValue(42);
		RuntimeServiceImpl runtimeService =
			(RuntimeServiceImpl) directProviderUtil.getProcessEngine(user).getRuntimeService();

		variableProvider.saveVariableInProcessInstanceId("pi-1", List.of(variable), user);

		verify(runtimeService).updateVariables(eq("pi-1"), any(), eq(List.of()));
	}

	@Test
	void submitVariables_sendsTheFormResultAsModifications() {
		Variable variable = new Variable();
		variable.setName("amount");
		variable.setType("Integer");
		variable.setValue(42);
		RuntimeServiceImpl runtimeService =
			(RuntimeServiceImpl) directProviderUtil.getProcessEngine(user).getRuntimeService();

		variableProvider.submitVariables("pi-1", List.of(variable), user, "definition-1");

		verify(runtimeService).updateVariables(eq("pi-1"), any(), eq(List.of()));
	}

	@Test
	void submitVariables_serializesAnObjectValueThatArrivesAsParsedJson() {
		// regression for CIB7-880: the engine rejected a non-string value for a serializable type
		Variable variable = new Variable();
		variable.setName("payload");
		variable.setType("Object");
		variable.setValue(Map.of("a", 1));
		variable.setValueInfo(Map.of("objectTypeName", "java.util.HashMap", "serializationDataFormat", "application/json"));
		RuntimeServiceImpl runtimeService =
			(RuntimeServiceImpl) directProviderUtil.getProcessEngine(user).getRuntimeService();

		variableProvider.submitVariables("pi-1", List.of(variable), user, "definition-1");

		ArgumentCaptor<VariableMap> modifications = ArgumentCaptor.forClass(VariableMap.class);
		verify(runtimeService).updateVariables(eq("pi-1"), modifications.capture(), eq(List.of()));
		ObjectValue payload = modifications.getValue().getValueTyped("payload");
		assertThat(payload.getValueSerialized()).isEqualTo("{\"a\":1}");
		assertThat(payload.getObjectTypeName()).isEqualTo("java.util.HashMap");
	}

	@Test
	void saveVariableInProcessInstanceId_carriesTheValueInfoAlong() {
		Variable variable = new Variable();
		variable.setName("payload");
		variable.setType("Object");
		variable.setValue("{}");
		variable.setValueInfo(Map.of("objectTypeName", "java.util.HashMap"));
		RuntimeServiceImpl runtimeService =
			(RuntimeServiceImpl) directProviderUtil.getProcessEngine(user).getRuntimeService();

		variableProvider.saveVariableInProcessInstanceId("pi-1", List.of(variable), user);

		// the serialized object's type has to survive, or the engine cannot deserialize it again
		verify(runtimeService).updateVariables(eq("pi-1"), any(), any());
	}

	@Test
	void saveVariableInProcessInstanceId_withNoVariablesStillCallsTheEngine() {
		RuntimeServiceImpl runtimeService =
			(RuntimeServiceImpl) directProviderUtil.getProcessEngine(user).getRuntimeService();

		variableProvider.saveVariableInProcessInstanceId("pi-1", List.of(), user);

		verify(runtimeService).updateVariables(eq("pi-1"), any(), eq(List.of()));
	}
}
