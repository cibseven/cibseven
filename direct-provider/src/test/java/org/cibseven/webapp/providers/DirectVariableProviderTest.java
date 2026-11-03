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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.history.HistoricVariableInstance;
import org.cibseven.bpm.engine.history.HistoricVariableInstanceQuery;
import org.cibseven.bpm.engine.impl.RuntimeServiceImpl;
import org.cibseven.bpm.engine.impl.variable.ValueTypeResolverImpl;
import org.cibseven.bpm.engine.rest.mapper.JacksonConfigurator;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.TypedValue;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.NoObjectFoundException;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.Variable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;

public class DirectVariableProviderTest {

	private DirectProviderUtil directProviderUtil;
	private RuntimeServiceImpl runtimeService;
	private TaskService taskService;
	private HistoryService historyService;
	private DirectVariableProvider variableProvider;
	private CIBUser user;

	@BeforeEach
	void setUp() {
		user = new CIBUser("testUser");

		ProcessEngine processEngine = mock(ProcessEngine.class);
		// the provider casts the runtime service to RuntimeServiceImpl, so the mock has to be one
		runtimeService = mock(RuntimeServiceImpl.class);
		taskService = mock(TaskService.class);
		historyService = mock(HistoryService.class);
		when(processEngine.getRuntimeService()).thenReturn(runtimeService);
		when(processEngine.getTaskService()).thenReturn(taskService);
		when(processEngine.getHistoryService()).thenReturn(historyService);
		// building a typed value from an upload goes through the engine's value type resolver
		ProcessEngineConfiguration configuration = mock(ProcessEngineConfiguration.class);
		when(configuration.getValueTypeResolver()).thenReturn(new ValueTypeResolverImpl());
		when(processEngine.getProcessEngineConfiguration()).thenReturn(configuration);

		ObjectMapper objectMapper = new ObjectMapper();
		JacksonConfigurator.configureObjectMapper(objectMapper);

		directProviderUtil = Mockito.spy(new DirectProviderUtil());
		doReturn(processEngine).when(directProviderUtil).getProcessEngine(any(CIBUser.class));
		doReturn(objectMapper).when(directProviderUtil).getObjectMapper(any(CIBUser.class));

		variableProvider = new DirectVariableProvider(directProviderUtil);
	}

	// ---------- binary variable download ----------

	@Test
	void fetchVariableDataByExecutionId_returnsRawBytesForAByteArrayVariable() {
		byte[] payload = "pdf-bytes".getBytes(StandardCharsets.UTF_8);
		when(runtimeService.getVariableLocalTyped("exec-1", "attachment", false))
			.thenReturn(Variables.byteArrayValue(payload));

		ResponseEntity<byte[]> response = variableProvider.fetchVariableDataByExecutionId("exec-1", "attachment", user);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo(payload);
	}

	@Test
	void fetchVariableDataByExecutionId_servesAnEmptyBodyForAByteArrayVariableWithNoValue() {
		when(runtimeService.getVariableLocalTyped("exec-1", "attachment", false))
			.thenReturn(Variables.byteArrayValue(null));

		ResponseEntity<byte[]> response = variableProvider.fetchVariableDataByExecutionId("exec-1", "attachment", user);

		// null is normalised to an empty array rather than a null body
		assertThat(response.getBody()).isEmpty();
	}

	@Test
	void fetchVariableDataByExecutionId_usesTheFilesOwnMimeType() {
		byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
		when(runtimeService.getVariableLocalTyped("exec-1", "invoice", false))
			.thenReturn(Variables.fileValue("invoice.txt").file(payload).mimeType("text/plain").create());

		ResponseEntity<byte[]> response = variableProvider.fetchVariableDataByExecutionId("exec-1", "invoice", user);

		assertThat(response.getBody()).isEqualTo(payload);
		assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
	}

	@Test
	void fetchVariableDataByExecutionId_fallsBackToOctetStreamWhenTheFileHasNoMimeType() {
		when(runtimeService.getVariableLocalTyped("exec-1", "invoice", false))
			.thenReturn(Variables.fileValue("invoice.bin").file(new byte[] { 1, 2 }).create());

		ResponseEntity<byte[]> response = variableProvider.fetchVariableDataByExecutionId("exec-1", "invoice", user);

		assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
	}

	@Test
	void fetchVariableDataByExecutionId_rejectsANonBinaryVariable() {
		when(runtimeService.getVariableLocalTyped("exec-1", "amount", false))
			.thenReturn(Variables.stringValue("42"));

		assertThatThrownBy(() -> variableProvider.fetchVariableDataByExecutionId("exec-1", "amount", user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("is not a binary value");
	}

	// ---------- historic binary variable download ----------

	@Test
	void fetchHistoryVariableDataById_servesTheHistoricValue() {
		byte[] payload = "old-bytes".getBytes(StandardCharsets.UTF_8);
		HistoricVariableInstanceQuery query =
			mock(HistoricVariableInstanceQuery.class, withSettings().defaultAnswer(RETURNS_SELF));
		when(historyService.createHistoricVariableInstanceQuery()).thenReturn(query);
		HistoricVariableInstance historicVariable = mock(HistoricVariableInstance.class);
		when(query.singleResult()).thenReturn(historicVariable);
		when(historicVariable.getTypedValue()).thenReturn(Variables.byteArrayValue(payload));

		ResponseEntity<byte[]> response = variableProvider.fetchHistoryVariableDataById("var-1", user);

		assertThat(response.getBody()).isEqualTo(payload);
		// custom object deserialization must stay off for a raw download
		verify(query).disableCustomObjectDeserialization();
	}

	@Test
	void fetchHistoryVariableDataById_throwsWhenTheVariableIsUnknown() {
		HistoricVariableInstanceQuery query =
			mock(HistoricVariableInstanceQuery.class, withSettings().defaultAnswer(RETURNS_SELF));
		when(historyService.createHistoricVariableInstanceQuery()).thenReturn(query);
		when(query.singleResult()).thenReturn(null);

		assertThatThrownBy(() -> variableProvider.fetchHistoryVariableDataById("missing", user))
			.isInstanceOf(NoObjectFoundException.class);
	}

	/**
	 * TODO KNOWN BUG (not fixed): {@code ApplicationException} declares only
	 * {@code ApplicationException(Object... data)} and never calls {@code super(message)}, so every
	 * constructor of its subclasses - here {@code NoObjectFoundException(Throwable)} - stores the
	 * message and cause in {@code data} and leaves {@code RuntimeException}'s own message and cause
	 * unset. The provider builds "HistoryVariable with Id 'missing' does not exist." and it never
	 * reaches {@code getMessage()}; the wrapped {@code SystemException} never reaches
	 * {@code getCause()}. Anything logging or serialising this exception sees nothing. Affects all
	 * four subclasses of {@code ApplicationException}, of which {@code NoObjectFoundException} is
	 * thrown throughout the providers. This test states what the caller should be able to read; it
	 * fails until {@code ApplicationException} passes message and cause up to
	 * {@code RuntimeException}. See {@code ExceptionContractTest} in {@code cibseven-interfaces}.
	 */
	@Test
	@Disabled("KNOWN BUG: ApplicationException never calls super(message), so the reason is only in getData()")
	void fetchHistoryVariableDataById_reportsWhyItThrew() {
		HistoricVariableInstanceQuery query =
			mock(HistoricVariableInstanceQuery.class, withSettings().defaultAnswer(RETURNS_SELF));
		when(historyService.createHistoricVariableInstanceQuery()).thenReturn(query);
		when(query.singleResult()).thenReturn(null);

		Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
			() -> variableProvider.fetchHistoryVariableDataById("missing", user));

		assertThat(thrown).isInstanceOf(NoObjectFoundException.class);
		assertThat(thrown.getCause()).isInstanceOf(SystemException.class)
			.hasMessageContaining("HistoryVariable with Id 'missing' does not exist.");
		// today the information survives only here
		assertThat(((NoObjectFoundException) thrown).getData()).hasSize(2);
	}

	// ---------- deletion ----------

	@Test
	void deleteVariable_removesTheTaskVariable() {
		variableProvider.deleteVariable("task-1", "amount", user);

		verify(taskService).removeVariable("task-1", "amount");
	}

	@Test
	void deleteVariable_letsAuthorizationFailuresThrough() {
		doThrow(new AuthorizationException("denied")).when(taskService).removeVariable("task-1", "amount");

		// must stay an AuthorizationException so the REST layer answers 403, not 500
		assertThatThrownBy(() -> variableProvider.deleteVariable("task-1", "amount", user))
			.isInstanceOf(AuthorizationException.class);
	}

	@Test
	void deleteVariable_wrapsOtherEngineFailuresInSystemException() {
		doThrow(new ProcessEngineException("boom")).when(taskService).removeVariable("task-1", "amount");

		assertThatThrownBy(() -> variableProvider.deleteVariable("task-1", "amount", user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("Cannot delete task variable amount");
	}

	// ---------- runtime variable modification ----------

	@Test
	void modifyVariableByExecutionId_appliesDeletionsToTheExecution() {
		Map<String, Object> data = new HashMap<>();
		data.put("deletions", List.of("amount", "customer"));

		variableProvider.modifyVariableByExecutionId("exec-1", data, user);

		verify(runtimeService).updateVariables(eq("exec-1"), any(), eq(List.of("amount", "customer")));
	}

	@Test
	void modifyVariableByExecutionId_wrapsEngineFailureInSystemException() {
		Map<String, Object> data = new HashMap<>();
		data.put("deletions", List.of("amount"));
		doThrow(new ProcessEngineException("gone")).when(runtimeService).updateVariables(any(), any(), any());

		assertThatThrownBy(() -> variableProvider.modifyVariableByExecutionId("exec-1", data, user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("Cannot modify variables for modifyVariableByExecutionId exec-1");
	}

	@Test
	void modifyVariableDataByExecutionId_storesAnUploadedFileAsAFileValue() {
		MockMultipartFile upload =
			new MockMultipartFile("data", "invoice.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));

		variableProvider.modifyVariableDataByExecutionId("exec-1", "invoice", upload, "File", user);

		verify(runtimeService).setVariable(eq("exec-1"), eq("invoice"), any(TypedValue.class));
	}

	@Test
	void modifyVariableDataByExecutionId_rejectsASerializedValueThatIsNotJson() {
		MockMultipartFile upload =
			new MockMultipartFile("data", "obj.bin", "application/octet-stream", new byte[] { 1, 2 });

		assertThatThrownBy(() -> variableProvider.modifyVariableDataByExecutionId(
				"exec-1", "obj", upload, "Object", user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("Unrecognized content type for serialized java type");
	}

	// ---------- process instance variables ----------

	@Test
	void fetchProcessInstanceVariables_returnsEmptyWhenTheInstanceHasNoVariables() {
		doReturn(List.of()).when(directProviderUtil)
			.queryVariableInstances(any(), any(), any(), anyBoolean(), any(CIBUser.class));

		assertThat(variableProvider.fetchProcessInstanceVariables("instance-1", new HashMap<>(), user)).isEmpty();
	}

	@Test
	void fetchProcessInstanceVariables_scopesTheQueryToTheGivenInstance() {
		Variable variable = new Variable();
		variable.setName("amount");
		variable.setValue(42);
		doReturn(List.of(variable)).when(directProviderUtil)
			.queryVariableInstances(any(), any(), any(), anyBoolean(), any(CIBUser.class));

		Map<String, Object> data = new HashMap<>();
		variableProvider.fetchProcessInstanceVariables("instance-1", data, user);

		// the instance id is pushed into the query filter rather than filtered afterwards
		assertThat((String[]) data.get("processInstanceIdIn")).containsExactly("instance-1");
	}

	@Test
	void fetchProcessInstanceVariables_consumesTheDeserializeValuesFlagBeforeQuerying() {
		Variable variable = new Variable();
		variable.setName("amount");
		doReturn(List.of(variable)).when(directProviderUtil)
			.queryVariableInstances(any(), any(), any(), anyBoolean(), any(CIBUser.class));

		Map<String, Object> data = new HashMap<>();
		data.put("deserializeValues", Boolean.TRUE);

		variableProvider.fetchProcessInstanceVariables("instance-1", data, user);

		// it is a webclient-side flag; leaving it in would make the engine reject the query
		assertThat(data).doesNotContainKey("deserializeValues");
	}
}
