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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.exception.NotFoundException;
import org.cibseven.bpm.engine.rest.dto.runtime.JobDto;
import org.cibseven.bpm.engine.rest.dto.runtime.JobDuedateDto;
import org.cibseven.bpm.engine.rest.mapper.JacksonConfigurator;
import org.cibseven.bpm.engine.runtime.JobQuery;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.Job;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.databind.exc.InvalidDefinitionException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

public class DirectJobProviderTest {

	private static final Date DUE_DATE = new Date(1_700_000_000_000L);
	private static final Date CREATE_TIME = new Date(1_600_000_000_000L);

	private DirectProviderUtil directProviderUtil;
	private ManagementService managementService;
	private HistoryService historyService;
	private DirectJobProvider jobProvider;
	private CIBUser user;
	// engine-rest's Jackson 2 mapper and the provider's Jackson 3 mapper
	private ObjectMapper objectMapper;
	private JsonMapper jsonMapper;
	// plain mappers with the defaults of each version
	private final ObjectMapper jackson2Defaults = new ObjectMapper();
	private final JsonMapper jackson3Defaults = JsonMapper.builder().build();

	@BeforeEach
	void setUp() {
		user = new CIBUser("testUser");

		ProcessEngine processEngine = mock(ProcessEngine.class);
		managementService = mock(ManagementService.class);
		historyService = mock(HistoryService.class);
		when(processEngine.getManagementService()).thenReturn(managementService);
		when(processEngine.getHistoryService()).thenReturn(historyService);

		objectMapper = new ObjectMapper();
		JacksonConfigurator.configureObjectMapper(objectMapper);
		jsonMapper = DirectProviderUtil.createJsonMapper(objectMapper);

		directProviderUtil = Mockito.spy(new DirectProviderUtil());
		doReturn(processEngine).when(directProviderUtil).getProcessEngine(any(CIBUser.class));
		doReturn(objectMapper).when(directProviderUtil).getObjectMapper(any(CIBUser.class));
		doReturn(jsonMapper).when(directProviderUtil).getJsonMapper(any(CIBUser.class));

		jobProvider = new DirectJobProvider(directProviderUtil);
	}

	private org.cibseven.bpm.engine.runtime.Job engineJob() {
		org.cibseven.bpm.engine.runtime.Job job = mock(org.cibseven.bpm.engine.runtime.Job.class);
		when(job.getId()).thenReturn("job-1");
		when(job.getJobDefinitionId()).thenReturn("definition-1");
		when(job.getRetries()).thenReturn(3);
		when(job.getDuedate()).thenReturn(DUE_DATE);
		when(job.isSuspended()).thenReturn(true);
		when(job.getPriority()).thenReturn(5L);
		when(job.getCreateTime()).thenReturn(CREATE_TIME);
		return job;
	}

	private void jobQueryReturns(org.cibseven.bpm.engine.runtime.Job job) {
		JobQuery jobQuery = mock(JobQuery.class, withSettings().defaultAnswer(RETURNS_SELF));
		when(managementService.createJobQuery()).thenReturn(jobQuery);
		when(jobQuery.list()).thenReturn(List.of(job));
	}

	private JobDuedateDto dueDateRequest() {
		JobDuedateDto request = new JobDuedateDto();
		request.setDuedate(DUE_DATE);
		request.setCascade(true);
		return request;
	}

	/** A date as engine-rest writes it, in the time zone of the JVM. */
	private static String engineRestDate(Date date) {
		return new SimpleDateFormat(JacksonConfigurator.dateFormatString).format(date);
	}

	@Test
	void deleteJob_delegatesToTheManagementService() {
		jobProvider.deleteJob("job-1", user);

		verify(managementService).deleteJob("job-1");
	}

	@Test
	void deleteJob_letsAuthorizationFailuresThrough() {
		doThrow(new AuthorizationException("denied")).when(managementService).deleteJob("job-1");

		assertThatThrownBy(() -> jobProvider.deleteJob("job-1", user))
			.isInstanceOf(AuthorizationException.class);
	}

	@Test
	void deleteJob_wrapsOtherEngineFailures() {
		doThrow(new ProcessEngineException("boom")).when(managementService).deleteJob("job-1");

		assertThatThrownBy(() -> jobProvider.deleteJob("job-1", user)).isInstanceOf(SystemException.class);
	}

	@Test
	void getHistoryJobLogStacktrace_returnsTheStacktrace() {
		when(historyService.getHistoricJobLogExceptionStacktrace("job-1")).thenReturn("at Foo.bar()");

		assertThat(jobProvider.getHistoryJobLogStacktrace("job-1", user)).isEqualTo("at Foo.bar()");
	}

	@Test
	void getHistoryJobLogStacktrace_wrapsEngineFailures() {
		when(historyService.getHistoricJobLogExceptionStacktrace("job-1"))
			.thenThrow(new ProcessEngineException("boom"));

		assertThatThrownBy(() -> jobProvider.getHistoryJobLogStacktrace("job-1", user))
			.isInstanceOf(SystemException.class);
	}

	@Test
	void recalculateDueDate_defaultsToNotCreationDateBased() {
		jobProvider.recalculateDueDate("job-1", new HashMap<>(), user);

		verify(managementService).recalculateJobDuedate("job-1", false);
	}

	@Test
	void recalculateDueDate_honoursTheCreationDateBasedFlag() {
		Map<String, Object> params = new HashMap<>();
		params.put("creationDateBased", "true");

		jobProvider.recalculateDueDate("job-1", params, user);

		verify(managementService).recalculateJobDuedate("job-1", true);
	}

	@Test
	void recalculateDueDate_translatesAnUnknownJobIntoSystemException() {
		doThrow(new NotFoundException("gone")).when(managementService).recalculateJobDuedate("missing", false);

		// deliberately rewritten from the engine's 400 to something the REST layer maps to 404
		assertThatThrownBy(() -> jobProvider.recalculateDueDate("missing", new HashMap<>(), user))
			.isInstanceOf(SystemException.class);
	}

	@Test
	void setSuspended_rejectsAJobDefinitionIdBecauseTheIdIsTakenFromThePath() {
		Map<String, Object> params = new HashMap<>();
		params.put("jobDefinitionId", "definition-1");

		assertThatThrownBy(() -> jobProvider.setSuspended("process-1", params, user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("Either processDefinitionId or processDefinitionKey can be set");
	}

	// ---------- Jackson 2 <-> Jackson 3 ----------
	// The provider converts with Jackson 3, engine-rest and the webclient with Jackson 2.

	@Test
	void getJobs_returnsTheJobsEngineRestWritesWithJackson2() throws Exception {
		org.cibseven.bpm.engine.runtime.Job engineJob = engineJob();
		jobQueryReturns(engineJob);
		// what engine-rest answers, and the REST provider reads with Jackson 2
		String engineRestJson = objectMapper.writeValueAsString(JobDto.fromJob(engineJob));

		Collection<Job> jobs = jobProvider.getJobs(new HashMap<>(), user);

		assertThat(jobs).containsExactly(jsonMapper.readValue(engineRestJson, Job.class));
		assertThat(jobs).containsExactly(objectMapper.readValue(engineRestJson, Job.class));
		assertThat(jobs).extracting(Job::getDueDate).containsExactly(engineRestDate(DUE_DATE));
	}

	@Test
	void getJobs_resultWrittenWithJackson3ReadsBackWithJackson2() throws Exception {
		jobQueryReturns(engineJob());
		Job job = jobProvider.getJobs(new HashMap<>(), user).iterator().next();

		String json = jsonMapper.writeValueAsString(job);

		// the webclient answers with Jackson 2, which writes the same JSON
		assertThat(json).isEqualTo(objectMapper.writeValueAsString(job));
		assertThat(objectMapper.readValue(json, Job.class)).isEqualTo(job);
	}

	@Test
	void changeDueDate_acceptsARequestWrittenWithJackson2() throws Exception {
		String requestBody = objectMapper.writeValueAsString(dueDateRequest());
		// the webclient reads the request body with Jackson 2, the provider converts it with Jackson 3
		Map<String, Object> data = objectMapper.readValue(requestBody, new TypeReference<Map<String, Object>>() {});

		jobProvider.changeDueDate("job-1", data, user);

		verify(managementService).setJobDuedate("job-1", DUE_DATE, true);
	}

	@Test
	void jobDuedateDtoWrittenWithJackson3_readsBackWithJackson2() throws Exception {
		String json = jsonMapper.writeValueAsString(dueDateRequest());

		assertThat(json).isEqualTo(objectMapper.writeValueAsString(dueDateRequest()));
		JobDuedateDto request = objectMapper.readValue(json, JobDuedateDto.class);
		assertThat(request.getDuedate()).isEqualTo(DUE_DATE);
		assertThat(request.isCascade()).isTrue();
	}

	/** Written as text by Jackson 2 only: {@link JsonSerialize} is a Jackson 2 databind annotation. */
	static class TextPriority {
		@JsonSerialize(using = ToStringSerializer.class)
		public long priority = 5;
	}

	@Test
	void jacksonAnnotations_sharedOnesWorkInBothVersionsDatabindOnesOnlyInJackson2() throws Exception {
		// com.fasterxml.jackson.annotation is shared, so Jackson 3 honours @JsonIgnoreProperties on Job
		JsonMapper failingOnUnknownProperties = JsonMapper.builderWithJackson2Defaults().build();
		assertThat(failingOnUnknownProperties.readValue("{\"unknown\":1}", Job.class)).isEqualTo(new Job());
		assertThatThrownBy(() -> failingOnUnknownProperties.readValue("{\"unknown\":1}", JobDuedateDto.class))
			.isInstanceOf(UnrecognizedPropertyException.class);

		// com.fasterxml.jackson.databind.annotation is not, so Jackson 3 ignores @JsonSerialize
		assertThat(jackson2Defaults.convertValue(new TextPriority(), Map.class).get("priority")).isEqualTo("5");
		assertThat(jackson3Defaults.convertValue(new TextPriority(), Map.class).get("priority")).isEqualTo(5L);
	}

	// ---------- Jackson 2 vs Jackson 3 defaults ----------
	// jackson2Defaults and jackson3Defaults show what each version does out of the box. The provider's
	// jsonMapper behaves like engine-rest's Jackson 2 mapper in each case.

	@Test
	void jacksonDefaults_writeDatesAsTimestampsInJackson2AndAsIsoTextInJackson3() throws Exception {
		String jackson2Json = jackson2Defaults.writeValueAsString(dueDateRequest());
		String jackson3Json = jackson3Defaults.writeValueAsString(dueDateRequest());

		assertThat(jackson2Json).contains("\"duedate\":1700000000000");
		assertThat(jackson3Json).contains("\"duedate\":\"2023-11-14T22:13:20.000Z\"");
		// each reads the date the other wrote
		assertThat(jackson3Defaults.readValue(jackson2Json, JobDuedateDto.class).getDuedate()).isEqualTo(DUE_DATE);
		assertThat(jackson2Defaults.readValue(jackson3Json, JobDuedateDto.class).getDuedate()).isEqualTo(DUE_DATE);
		// but the webclient's Job keeps dates as text, so it would get a different text from each
		JobDto jobDto = JobDto.fromJob(engineJob());
		assertThat(jackson2Defaults.convertValue(jobDto, Job.class).getDueDate()).isEqualTo("1700000000000");
		assertThat(jackson3Defaults.convertValue(jobDto, Job.class).getDueDate()).isEqualTo("2023-11-14T22:13:20.000Z");
		assertThat(jsonMapper.convertValue(jobDto, Job.class).getDueDate()).isEqualTo(engineRestDate(DUE_DATE));
	}

	@Test
	void jacksonDefaults_sortPropertiesAlphabeticallyOnlyInJackson3() throws Exception {
		JobDto jobDto = JobDto.fromJob(engineJob());

		assertThat(jackson2Defaults.writeValueAsString(jobDto)).startsWith("{\"id\":\"job-1\",\"jobDefinitionId\":");
		assertThat(jackson3Defaults.writeValueAsString(jobDto)).startsWith("{\"batchId\":null,\"createTime\":");
		assertThat(jsonMapper.writeValueAsString(jobDto)).isEqualTo(objectMapper.writeValueAsString(jobDto));
	}

	@Test
	void jacksonDefaults_failOnNullForAPrimitiveOnlyInJackson3() throws Exception {
		String json = "{\"cascade\":null}";

		assertThat(jackson2Defaults.readValue(json, JobDuedateDto.class).isCascade()).isFalse();
		assertThatThrownBy(() -> jackson3Defaults.readValue(json, JobDuedateDto.class))
			.isInstanceOf(MismatchedInputException.class)
			.hasMessageContaining("FAIL_ON_NULL_FOR_PRIMITIVES");

		Map<String, Object> data = new HashMap<>();
		data.put("cascade", null);
		jobProvider.changeDueDate("job-1", data, user);
		verify(managementService).setJobDuedate("job-1", null, false);
	}

	@Test
	void jacksonDefaults_failOnUnknownPropertiesOnlyInJackson2() throws Exception {
		String json = "{\"cascade\":true,\"unknown\":1}";

		assertThatThrownBy(() -> jackson2Defaults.readValue(json, JobDuedateDto.class))
			.isInstanceOf(JsonProcessingException.class)
			.hasMessageContaining("Unrecognized field \"unknown\"");
		assertThat(jackson3Defaults.readValue(json, JobDuedateDto.class).isCascade()).isTrue();

		// engine-rest ignores unknown properties as well
		jobProvider.changeDueDate("job-1", Map.of("cascade", true, "unknown", 1), user);
		verify(managementService).setJobDuedate("job-1", null, true);
	}

	@Test
	void jacksonDefaults_failOnTrailingTokensOnlyInJackson3() throws Exception {
		String json = "{\"id\":\"job-1\"} {\"id\":\"job-2\"}";

		assertThat(jackson2Defaults.readValue(json, Job.class).getId()).isEqualTo("job-1");
		assertThatThrownBy(() -> jackson3Defaults.readValue(json, Job.class))
			.isInstanceOf(MismatchedInputException.class)
			.hasMessageContaining("FAIL_ON_TRAILING_TOKENS");
		assertThat(jsonMapper.readValue(json, Job.class).getId()).isEqualTo("job-1");
	}

	/** Written as its name by Jackson 2 and as its toString() by Jackson 3. */
	enum JobState {
		SUSPENDED;

		@Override
		public String toString() {
			return "suspended";
		}
	}

	@Test
	void jacksonDefaults_useToStringForEnumsOnlyInJackson3() throws Exception {
		assertThat(jackson2Defaults.writeValueAsString(JobState.SUSPENDED)).isEqualTo("\"SUSPENDED\"");
		assertThat(jackson3Defaults.writeValueAsString(JobState.SUSPENDED)).isEqualTo("\"suspended\"");
		// so neither reads what the other wrote
		assertThatThrownBy(() -> jackson3Defaults.readValue("\"SUSPENDED\"", JobState.class))
			.isInstanceOf(InvalidFormatException.class);
		assertThatThrownBy(() -> jackson2Defaults.readValue("\"suspended\"", JobState.class))
			.isInstanceOf(JsonProcessingException.class)
			.hasMessageContaining("not one of the values accepted");

		assertThat(jsonMapper.writeValueAsString(JobState.SUSPENDED)).isEqualTo("\"SUSPENDED\"");
		assertThat(jsonMapper.readValue("\"SUSPENDED\"", JobState.class)).isEqualTo(JobState.SUSPENDED);
	}

	@Test
	void jacksonDefaults_supportJavaTimeWithoutAModuleOnlyInJackson3() throws Exception {
		Map<String, Object> value = Map.of("createTime", CREATE_TIME.toInstant());

		assertThatThrownBy(() -> jackson2Defaults.writeValueAsString(value))
			.isInstanceOf(JsonProcessingException.class)
			.hasMessageContaining("jackson-datatype-jsr310");
		assertThat(jackson3Defaults.writeValueAsString(value)).isEqualTo("{\"createTime\":\"2020-09-13T12:26:40Z\"}");
		// engine-rest registers the module
		assertThat(jsonMapper.writeValueAsString(value)).isEqualTo(objectMapper.writeValueAsString(value));
	}

	/** Has no property to write. */
	static class NoProperties {
	}

	@Test
	void jacksonDefaults_failOnEmptyBeansOnlyInJackson2() throws Exception {
		assertThatThrownBy(() -> jackson2Defaults.writeValueAsString(new NoProperties()))
			.isInstanceOf(JsonProcessingException.class)
			.hasMessageContaining("FAIL_ON_EMPTY_BEANS");
		assertThat(jackson3Defaults.writeValueAsString(new NoProperties())).isEqualTo("{}");
		assertThatThrownBy(() -> jsonMapper.writeValueAsString(new NoProperties()))
			.isInstanceOf(InvalidDefinitionException.class)
			.hasMessageContaining("FAIL_ON_EMPTY_BEANS");
	}

	@Test
	void jacksonDefaults_limitTheNestingDepthTo1000InJackson2And500InJackson3() throws Exception {
		String json = "[".repeat(600) + "]".repeat(600);

		assertThat(jackson2Defaults.readTree(json).isArray()).isTrue();
		assertThatThrownBy(() -> jackson3Defaults.readTree(json))
			.isInstanceOf(StreamConstraintsException.class)
			.hasMessageContaining("maximum allowed (500");
		assertThat(jsonMapper.readTree(json).isArray()).isTrue();
	}

	@Test
	void jacksonDefaults_throwCheckedExceptionsOnlyInJackson2() {
		String truncated = "{\"duedate\":";

		assertThatThrownBy(() -> jackson2Defaults.readValue(truncated, JobDuedateDto.class))
			.isInstanceOf(JsonProcessingException.class)
			.isInstanceOf(IOException.class);
		assertThatThrownBy(() -> jackson3Defaults.readValue(truncated, JobDuedateDto.class))
			.isInstanceOf(JacksonException.class)
			.isInstanceOf(RuntimeException.class);
	}

	@Test
	void changeDueDate_failsOnAnInvalidDateWithJacksonExceptionInsteadOfIllegalArgumentException() {
		Map<String, Object> data = Map.of("duedate", "not a date");

		// Jackson 2 wraps conversion errors into IllegalArgumentException, Jackson 3 throws them as they are
		assertThatThrownBy(() -> objectMapper.convertValue(data, JobDuedateDto.class))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> jobProvider.changeDueDate("job-1", data, user))
			.isInstanceOf(InvalidFormatException.class)
			.isNotInstanceOf(IllegalArgumentException.class);
	}
}
