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

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;

import org.cibseven.bpm.engine.FilterService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.exception.NotValidException;
import org.cibseven.bpm.engine.impl.TaskQueryImpl;
import org.cibseven.bpm.engine.rest.mapper.JacksonConfigurator;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.Filter;
import org.cibseven.webapp.rest.model.FilterCriterias;
import org.cibseven.webapp.rest.model.FilterProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * Runs the filter provider against a real in-memory engine rather than mocks, so the JSON
 * conversions between the webclient model and the engine's filter and task query DTOs are
 * exercised end to end: a filter has to survive the trip into the engine and back unchanged.
 */
class DirectFilterDtoProviderTest {

	private static final String ENGINE_NAME = "filters";
	private static final String DUE_AFTER = "2026-10-19T08:30:00.000+0000";

	private ProcessEngine engine;
	private FilterService filterService;
	private ObjectMapper objectMapper;
	private DirectFilterProvider provider;
	private CIBUser user;

	@BeforeEach
	void setUp() {
		engine = ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
			.setJdbcUrl("jdbc:h2:mem:" + ENGINE_NAME + ";DB_CLOSE_DELAY=1000")
			.setProcessEngineName(ENGINE_NAME)
			.setJobExecutorActivate(false)
			.buildProcessEngine();
		filterService = engine.getFilterService();
		objectMapper = JacksonConfigurator.configureObjectMapper(new ObjectMapper());

		DirectProviderUtil providerUtil = new DirectProviderUtil();
		providerUtil.processEngines.put(ENGINE_NAME, engine);
		providerUtil.objectMappers.put(ENGINE_NAME, objectMapper);
		provider = new DirectFilterProvider(providerUtil);

		user = new CIBUser("tester");
		user.setEngine(ENGINE_NAME);
	}

	@AfterEach
	void tearDown() {
		engine.close();
	}

	// ---------- create ----------

	@Test
	void createFilter_storesTheTaskQueryAndPropertiesAndReturnsTheSavedFilter() throws Exception {
		Filter created = provider.createFilter(taskFilter("My tasks", "demo"), user);

		assertThat(created.getId()).isNotNull();
		assertThat(created.getResourceType()).isEqualTo("Task");
		assertThat(created.getName()).isEqualTo("My tasks");
		assertThat(created.getOwner()).isEqualTo("tester");
		assertThat(created.getProperties().getColor()).isEqualTo("#555555");
		assertThat(created.getProperties().getPriority()).isEqualTo(5);

		FilterCriterias query = created.getQuery();
		assertThat(query.getAssignee()).isEqualTo("demo");
		assertThat(parse(query.getDueAfter())).isEqualTo(parse(DUE_AFTER));
		assertThat(query.getProcessVariables().get(0).get("name").asText()).isEqualTo("amount");
		assertThat(query.getProcessVariables().get(0).get("operator").asText()).isEqualTo("gt");
		assertThat(query.getSorting().get(0).get("sortBy").asText()).isEqualTo("created");
		assertThat(query.getSorting().get(0).get("sortOrder").asText()).isEqualTo("desc");

		assertThat(filterService.getFilter(created.getId()).getName()).isEqualTo("My tasks");
		// the stored query must be one the engine can run
		assertThat(filterService.count(created.getId())).isZero();
	}

	@Test
	void createFilter_keepsTheCandidateFlagsTheEngineDtoCanOnlyRead() throws Exception {
		Filter filter = taskFilter("Group tasks", null);
		filter.getQuery().setWithCandidateGroups("true");
		filter.getQuery().setWithoutCandidateUsers("true");

		Filter created = provider.createFilter(filter, user);

		TaskQueryImpl storedQuery = filterService.getFilter(created.getId()).getQuery();
		assertThat(storedQuery.isWithCandidateGroups()).isTrue();
		assertThat(storedQuery.isWithoutCandidateUsers()).isTrue();
	}

	@Test
	void createFilter_rejectsAResourceTypeOtherThanTask() throws Exception {
		Filter filter = taskFilter("My processes", "demo");
		filter.setResourceType("ProcessInstance");

		assertThatThrownBy(() -> provider.createFilter(filter, user))
			.isInstanceOf(SystemException.class)
			.hasMessageEndingWith("Unable to create filter with invalid resource type 'ProcessInstance'");
		assertThat(filterService.createFilterQuery().count()).isZero();
	}

	@Test
	void createFilter_rejectsContentTheEngineDoesNotAccept() throws Exception {
		// the engine requires a filter name
		Filter filter = taskFilter("", "demo");

		assertThatThrownBy(() -> provider.createFilter(filter, user))
			.isInstanceOf(SystemException.class)
			.hasMessageEndingWith("Unable to create filter with invalid content")
			.hasRootCauseInstanceOf(NotValidException.class);
		assertThat(filterService.createFilterQuery().count()).isZero();
	}

	// ---------- find ----------

	@Test
	void findFilters_returnsEveryTaskFilterWithItsQuery() throws Exception {
		org.cibseven.bpm.engine.filter.Filter engineFilter = filterService.newTaskFilter("Engine filter");
		engineFilter.setQuery(engine.getTaskService().createTaskQuery().taskAssignee("john"));
		filterService.saveFilter(engineFilter);
		provider.createFilter(taskFilter("My tasks", "demo"), user);

		Collection<Filter> filters = provider.findFilters(user);

		assertThat(filters).extracting(Filter::getName).containsExactlyInAnyOrder("Engine filter", "My tasks");
		assertThat(filters).extracting(filter -> filter.getQuery().getAssignee()).containsExactlyInAnyOrder("john", "demo");
	}

	@Test
	void findFilters_returnsNothingWithoutFilters() {
		assertThat(provider.findFilters(user)).isEmpty();
	}

	// ---------- update ----------

	@Test
	void updateFilter_acceptsTheFilterAsReturnedAndStoresTheChanges() throws Exception {
		Filter created = provider.createFilter(taskFilter("My tasks", "demo"), user);
		created.setName("Renamed");
		created.getQuery().setAssignee("john");

		provider.updateFilter(created, user);

		assertThat(filterService.getFilter(created.getId()).getName()).isEqualTo("Renamed");
		Filter stored = provider.findFilters(user).iterator().next();
		assertThat(stored.getQuery().getAssignee()).isEqualTo("john");
		assertThat(parse(stored.getQuery().getDueAfter())).isEqualTo(parse(DUE_AFTER));
		assertThat(stored.getQuery().getSorting().get(0).get("sortBy").asText()).isEqualTo("created");
	}

	@Test
	void updateFilter_failsForAnUnknownFilter() throws Exception {
		Filter filter = taskFilter("My tasks", "demo");
		filter.setId("unknown");

		assertThatThrownBy(() -> provider.updateFilter(filter, user))
			.isInstanceOf(SystemException.class)
			.hasMessageEndingWith("Requested filter not found: unknown");
	}

	@Test
	void updateFilter_rejectsContentTheEngineDoesNotAccept() throws Exception {
		Filter created = provider.createFilter(taskFilter("My tasks", "demo"), user);
		created.setName("");

		assertThatThrownBy(() -> provider.updateFilter(created, user))
			.isInstanceOf(SystemException.class)
			.hasMessageEndingWith("Unable to update filter with invalid content")
			.hasRootCauseInstanceOf(NotValidException.class);
		assertThat(filterService.getFilter(created.getId()).getName()).isEqualTo("My tasks");
	}

	// ---------- delete ----------

	@Test
	void deleteFilter_removesTheFilter() throws Exception {
		Filter created = provider.createFilter(taskFilter("My tasks", "demo"), user);

		provider.deleteFilter(created.getId(), user);

		assertThat(filterService.getFilter(created.getId())).isNull();
	}

	@Test
	void deleteFilter_failsForAnUnknownFilter() {
		assertThatThrownBy(() -> provider.deleteFilter("unknown", user))
			.isInstanceOf(SystemException.class)
			.hasMessageEndingWith("Requested filter not found: unknown");
	}

	// ---------- helpers ----------

	private Filter taskFilter(String name, String assignee) throws JsonProcessingException {
		FilterCriterias query = new FilterCriterias();
		query.setAssignee(assignee);
		query.setDueAfter(DUE_AFTER);
		query.setProcessVariables(array("[{\"name\":\"amount\",\"operator\":\"gt\",\"value\":100}]"));
		query.setSorting(array("[{\"sortBy\":\"created\",\"sortOrder\":\"desc\"}]"));

		FilterProperties properties = new FilterProperties();
		properties.setColor("#555555");
		properties.setPriority(5);

		Filter filter = new Filter();
		filter.setResourceType("Task");
		filter.setName(name);
		filter.setOwner("tester");
		filter.setQuery(query);
		filter.setProperties(properties);
		return filter;
	}

	private ArrayNode array(String json) throws JsonProcessingException {
		return (ArrayNode) objectMapper.readTree(json);
	}

	private static Date parse(String date) throws ParseException {
		return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").parse(date);
	}
}
