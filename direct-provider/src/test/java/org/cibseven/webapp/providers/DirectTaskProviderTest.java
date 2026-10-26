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
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.cibseven.bpm.engine.FormService;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.impl.variable.ValueTypeResolverImpl;
import org.cibseven.bpm.engine.task.IdentityLinkType;
import org.cibseven.bpm.engine.task.TaskQuery;
import org.cibseven.bpm.engine.rest.dto.VariableValueDto;
import org.cibseven.bpm.engine.rest.mapper.JacksonConfigurator;
import org.cibseven.bpm.engine.variable.VariableMap;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.ObjectValue;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.NoObjectFoundException;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.IdentityLink;
import org.cibseven.webapp.rest.model.Task;
import org.cibseven.webapp.rest.model.Variable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.ObjectMapper;

public class DirectTaskProviderTest {

	private DirectProviderUtil directProviderUtil;
	private TaskService taskService;
	private FormService formService;
	private TaskQuery taskQuery;
	private DirectTaskProvider taskProvider;
	private CIBUser user;

	@BeforeEach
	void setUp() {
		user = new CIBUser("testUser");

		ProcessEngine processEngine = mock(ProcessEngine.class);
		taskService = mock(TaskService.class);
		formService = mock(FormService.class);
		when(processEngine.getTaskService()).thenReturn(taskService);
		when(processEngine.getFormService()).thenReturn(formService);
		when(processEngine.getHistoryService()).thenReturn(mock(HistoryService.class));

		taskQuery = mock(TaskQuery.class, withSettings().defaultAnswer(RETURNS_SELF));
		when(taskService.createTaskQuery()).thenReturn(taskQuery);

		ObjectMapper objectMapper = new ObjectMapper();
		JacksonConfigurator.configureObjectMapper(objectMapper);

		directProviderUtil = Mockito.spy(new DirectProviderUtil());
		doReturn(processEngine).when(directProviderUtil).getProcessEngine(any(CIBUser.class));
		doReturn(objectMapper).when(directProviderUtil).getObjectMapper(any(CIBUser.class));

		taskProvider = new DirectTaskProvider(directProviderUtil);
	}

	private org.cibseven.bpm.engine.task.Task mockEngineTask(String id, String name) {
		org.cibseven.bpm.engine.task.Task task = mock(org.cibseven.bpm.engine.task.Task.class);
		when(task.getId()).thenReturn(id);
		when(task.getName()).thenReturn(name);
		return task;
	}

	// ---------- lookup ----------

	@Test
	void findTaskById_mapsTheEngineTaskOntoTheWebclientModel() {
		org.cibseven.bpm.engine.task.Task engineTask = mockEngineTask("task-1", "Approve invoice");
		when(taskQuery.singleResult()).thenReturn(engineTask);

		Task task = taskProvider.findTaskById("task-1", user);

		assertThat(task.getId()).isEqualTo("task-1");
		assertThat(task.getName()).isEqualTo("Approve invoice");
		// form keys have to be initialised or the task list cannot render its forms
		verify(taskQuery).initializeFormKeys();
	}

	@Test
	void findTaskById_throwsWhenTheTaskIsGone() {
		when(taskQuery.singleResult()).thenReturn(null);

		assertThatThrownBy(() -> taskProvider.findTaskById("task-1", user))
			.isInstanceOf(NoObjectFoundException.class);
	}

	@Test
	void findTaskById_toleratesANullId() {
		when(taskQuery.singleResult()).thenReturn(null);

		// the message builder substitutes "null" rather than NPEing
		assertThatThrownBy(() -> taskProvider.findTaskById(null, user))
			.isInstanceOf(NoObjectFoundException.class);
	}

	@Test
	void findTasksByProcessInstance_scopesTheQueryToTheInstance() {
		org.cibseven.bpm.engine.task.Task engineTask = mockEngineTask("task-1", "Approve invoice");
		when(taskQuery.list()).thenReturn(List.of(engineTask));

		Collection<Task> tasks = taskProvider.findTasksByProcessInstance("instance-1", user);

		assertThat(tasks).hasSize(1);
		verify(taskQuery).processInstanceId("instance-1");
	}

	@Test
	void findTasks_parsesAndUrlDecodesTheQueryStringStyleFilter() {
		when(taskQuery.list()).thenReturn(List.of());

		// the webclient hands this provider a raw query string
		taskProvider.findTasks("assignee=demo%20user&processDefinitionKey=invoice", user);

		verify(taskQuery).taskAssignee("demo user");
	}

	@Test
	void findTasks_ignoresFilterSegmentsWithoutAValue() {
		when(taskQuery.list()).thenReturn(List.of());

		// a trailing "&" or a bare key must not blow up or become a null filter
		taskProvider.findTasks("assignee=demo&brokenSegment&", user);

		verify(taskQuery).taskAssignee("demo");
	}

	@Test
	void findTasksCount_countsWhatTheQueryReturns() {
		org.cibseven.bpm.engine.task.Task first = mockEngineTask("task-1", "a");
		org.cibseven.bpm.engine.task.Task second = mockEngineTask("task-2", "b");
		when(taskQuery.list()).thenReturn(List.of(first, second));

		assertThat(taskProvider.findTasksCount(new HashMap<>(), user)).isEqualTo(2);
	}

	// ---------- mutation ----------

	@Test
	void setAssignee_savesTheTaskWithTheNewAssignee() {
		org.cibseven.bpm.engine.task.Task engineTask = mockEngineTask("task-1", "Approve invoice");
		when(taskQuery.singleResult()).thenReturn(engineTask);

		taskProvider.setAssignee("task-1", "demo", user);

		verify(engineTask).setAssignee("demo");
		verify(taskService).saveTask(engineTask);
	}

	@Test
	void setAssignee_clearsTheAssigneeForTheNullConvention() {
		org.cibseven.bpm.engine.task.Task engineTask = mockEngineTask("task-1", "Approve invoice");
		when(taskQuery.singleResult()).thenReturn(engineTask);

		// what unassigning in the task list and the SDK's unclaim both send; it must not
		// become a user called "null", who would hide the task from every candidate list
		taskProvider.setAssignee("task-1", "null", user);

		verify(engineTask).setAssignee(null);
		verify(taskService).saveTask(engineTask);
	}

	// ---------- local variables ----------

	@Test
	void findLocalVariables_answersInTheShapeEngineRestUses() {
		when(taskService.getVariablesLocalTyped("task-1", true)).thenReturn(Variables.createVariables()
			.putValueTyped("amount", Variables.integerValue(42))
			.putValueTyped("note", Variables.stringValue("urgent")));

		Map<String, Object> variables = taskProvider.findLocalVariables("task-1", user);

		// a form reads vars.amount.value, as it does against the REST provider
		assertThat(variables).containsOnlyKeys("amount", "note");
		VariableValueDto amount = (VariableValueDto) variables.get("amount");
		assertThat(amount.getType()).isEqualTo("Integer");
		assertThat(amount.getValue()).isEqualTo(42);
		assertThat(((VariableValueDto) variables.get("note")).getValue()).isEqualTo("urgent");
	}

	@Test
	void update_copiesTheEditableFieldsOntoTheEngineTask() {
		org.cibseven.bpm.engine.task.Task engineTask = mockEngineTask("task-1", "old name");
		when(taskQuery.singleResult()).thenReturn(engineTask);

		Task edited = new Task();
		edited.setId("task-1");
		edited.setName("new name");
		edited.setDescription("please approve");
		edited.setPriority(75);
		edited.setAssignee("demo");

		taskProvider.update(edited, user);

		verify(engineTask).setName("new name");
		verify(engineTask).setDescription("please approve");
		verify(engineTask).setPriority(75);
		verify(engineTask).setAssignee("demo");
		verify(taskService).saveTask(engineTask);
	}

	@Test
	void update_throwsWhenTheTaskNoLongerExists() {
		when(taskQuery.singleResult()).thenReturn(null);
		Task edited = new Task();
		edited.setId("task-1");

		assertThatThrownBy(() -> taskProvider.update(edited, user))
			.isInstanceOf(NoObjectFoundException.class);
		verify(taskService, never()).saveTask(any());
	}

	// ---------- identity links ----------

	@Test
	void findIdentityLink_returnsEveryLinkWhenNoTypeIsGiven() {
		org.cibseven.bpm.engine.task.IdentityLink assignee =
			mockEngineIdentityLink("demo", null, IdentityLinkType.ASSIGNEE);
		org.cibseven.bpm.engine.task.IdentityLink candidate =
			mockEngineIdentityLink(null, "sales", IdentityLinkType.CANDIDATE);
		when(taskService.getIdentityLinksForTask("task-1")).thenReturn(List.of(assignee, candidate));

		Collection<IdentityLink> links = taskProvider.findIdentityLink("task-1", Optional.empty(), user);

		assertThat(links).hasSize(2);
	}

	@Test
	void findIdentityLink_filtersByTypeWhenOneIsGiven() {
		org.cibseven.bpm.engine.task.IdentityLink assignee =
			mockEngineIdentityLink("demo", null, IdentityLinkType.ASSIGNEE);
		org.cibseven.bpm.engine.task.IdentityLink candidate =
			mockEngineIdentityLink(null, "sales", IdentityLinkType.CANDIDATE);
		when(taskService.getIdentityLinksForTask("task-1")).thenReturn(List.of(assignee, candidate));

		Collection<IdentityLink> links =
			taskProvider.findIdentityLink("task-1", Optional.of(IdentityLinkType.CANDIDATE), user);

		assertThat(links).hasSize(1);
		assertThat(links.iterator().next().getGroupId()).isEqualTo("sales");
	}

	private org.cibseven.bpm.engine.task.IdentityLink mockEngineIdentityLink(
			String userId, String groupId, String type) {
		org.cibseven.bpm.engine.task.IdentityLink link = mock(org.cibseven.bpm.engine.task.IdentityLink.class);
		when(link.getUserId()).thenReturn(userId);
		when(link.getGroupId()).thenReturn(groupId);
		when(link.getType()).thenReturn(type);
		return link;
	}

	@Test
	void createIdentityLink_addsAUserLink() {
		Map<String, Object> data = new HashMap<>();
		data.put("userId", "demo");
		data.put("type", IdentityLinkType.CANDIDATE);

		taskProvider.createIdentityLink("task-1", data, user);

		verify(taskService).addUserIdentityLink("task-1", "demo", IdentityLinkType.CANDIDATE);
	}

	@Test
	void createIdentityLink_addsAGroupLink() {
		Map<String, Object> data = new HashMap<>();
		data.put("groupId", "sales");
		data.put("type", IdentityLinkType.CANDIDATE);

		taskProvider.createIdentityLink("task-1", data, user);

		verify(taskService).addGroupIdentityLink("task-1", "sales", IdentityLinkType.CANDIDATE);
	}

	@Test
	void createIdentityLink_rejectsBothUserAndGroup() {
		Map<String, Object> data = new HashMap<>();
		data.put("userId", "demo");
		data.put("groupId", "sales");

		assertThatThrownBy(() -> taskProvider.createIdentityLink("task-1", data, user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("but not both");
	}

	@Test
	void createIdentityLink_rejectsNeitherUserNorGroup() {
		assertThatThrownBy(() -> taskProvider.createIdentityLink("task-1", new HashMap<>(), user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("requires userId or groupId");
	}

	@Test
	void deleteIdentityLink_removesAUserLink() {
		Map<String, Object> data = new HashMap<>();
		data.put("userId", "demo");
		data.put("type", IdentityLinkType.CANDIDATE);

		taskProvider.deleteIdentityLink("task-1", data, user);

		verify(taskService).deleteUserIdentityLink("task-1", "demo", IdentityLinkType.CANDIDATE);
	}

	@Test
	void deleteIdentityLink_removesAGroupLink() {
		Map<String, Object> data = new HashMap<>();
		data.put("groupId", "sales");
		data.put("type", IdentityLinkType.CANDIDATE);

		taskProvider.deleteIdentityLink("task-1", data, user);

		verify(taskService).deleteGroupIdentityLink("task-1", "sales", IdentityLinkType.CANDIDATE);
	}

	@Test
	void deleteIdentityLink_rejectsBothUserAndGroup() {
		Map<String, Object> data = new HashMap<>();
		data.put("userId", "demo");
		data.put("groupId", "sales");

		assertThatThrownBy(() -> taskProvider.deleteIdentityLink("task-1", data, user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("but not both");
	}

	// ---------- bpmn error / escalation ----------

	@Test
	void handleBpmnError_forwardsTheErrorCodeAndMessage() {
		Map<String, Object> data = new HashMap<>();
		data.put("errorCode", "invoice-rejected");
		data.put("errorMessage", "amount too high");

		taskProvider.handleBpmnError("task-1", data, user);

		verify(taskService).handleBpmnError(
			Mockito.eq("task-1"), Mockito.eq("invoice-rejected"), Mockito.eq("amount too high"), any());
	}

	@Test
	void handleBpmnEscalation_forwardsTheEscalationCode() {
		Map<String, Object> data = new HashMap<>();
		data.put("escalationCode", "needs-manager");

		taskProvider.handleBpmnEscalation("task-1", data, user);

		verify(taskService).handleEscalation(Mockito.eq("task-1"), Mockito.eq("needs-manager"), any());
	}

	// ---------- deployed form ----------

	@Test
	void getDeployedForm_servesTheFormAsXhtml() {
		byte[] form = "<form/>".getBytes(StandardCharsets.UTF_8);
		when(formService.getDeployedTaskForm("task-1")).thenReturn(new ByteArrayInputStream(form));

		ResponseEntity<byte[]> response = taskProvider.getDeployedForm("task-1", user);

		assertThat(response.getBody()).isEqualTo(form);
		assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_XHTML_XML);
	}

	@Test
	void getDeployedForm_answers422WhenTheTaskHasNoDeployedForm() {
		when(formService.getDeployedTaskForm("task-1")).thenReturn(null);

		ResponseEntity<byte[]> response = taskProvider.getDeployedForm("task-1", user);

		assertThat(response.getStatusCode().value()).isEqualTo(422);
		assertThat(response.getBody()).isNull();
	}

	// ---------- rendered form ----------

	@Test
	void getRenderedForm_returnsTheFormUnchanged() {
		// the engine hands over a String; it used to be re-decoded with the JVM default charset (CIB7-2204)
		String form = "<form><label>Begründung 姓名</label></form>";
		when(formService.getRenderedTaskForm("task-1")).thenReturn(form);

		ResponseEntity<String> response = taskProvider.getRenderedForm("task-1", Map.of(), user);

		assertThat(response.getBody()).isSameAs(form);
	}

	@Test
	void getRenderedForm_failsWhenTheTaskHasNoRenderedForm() {
		when(formService.getRenderedTaskForm("task-1")).thenReturn(null);

		assertThatThrownBy(() -> taskProvider.getRenderedForm("task-1", Map.of(), user))
			.isInstanceOf(SystemException.class);
	}

	// ---------- submitting a task form ----------

	@Test
	void submit_serializesAnObjectValueThatArrivesAsParsedJson() {
		// regression for CIB7-880: the engine rejected a non-string value for a serializable type
		ProcessEngineConfiguration configuration = mock(ProcessEngineConfiguration.class);
		when(configuration.getValueTypeResolver()).thenReturn(new ValueTypeResolverImpl());
		when(directProviderUtil.getProcessEngine(user).getProcessEngineConfiguration()).thenReturn(configuration);
		Variable variable = new Variable();
		variable.setName("payload");
		variable.setType("Object");
		variable.setValue(Map.of("a", 1));
		variable.setValueInfo(Map.of("objectTypeName", "java.util.HashMap", "serializationDataFormat", "application/json"));
		Task task = new Task();
		task.setId("task-1");

		taskProvider.submit(task, List.of(variable), user);

		ArgumentCaptor<VariableMap> submitted = ArgumentCaptor.forClass(VariableMap.class);
		verify(formService).submitTaskForm(eq("task-1"), submitted.capture());
		ObjectValue payload = submitted.getValue().getValueTyped("payload");
		assertThat(payload.getValueSerialized()).isEqualTo("{\"a\":1}");
	}
}
