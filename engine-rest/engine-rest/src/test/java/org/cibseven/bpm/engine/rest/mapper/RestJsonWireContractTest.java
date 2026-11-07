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
package org.cibseven.bpm.engine.rest.mapper;

import static org.cibseven.bpm.engine.rest.mapper.WireFixtures.assertMatches;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;

import jakarta.ws.rs.core.Response.Status;

import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.authorization.MissingAuthorization;
import org.cibseven.bpm.engine.batch.Batch;
import org.cibseven.bpm.engine.batch.BatchStatistics;
import org.cibseven.bpm.engine.history.HistoricIncident;
import org.cibseven.bpm.engine.history.HistoricProcessInstance;
import org.cibseven.bpm.engine.history.HistoricVariableInstance;
import org.cibseven.bpm.engine.identity.User;
import org.cibseven.bpm.engine.management.ActivityStatistics;
import org.cibseven.bpm.engine.management.IncidentStatistics;
import org.cibseven.bpm.engine.management.ProcessDefinitionStatistics;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.rest.dto.AtomLink;
import org.cibseven.bpm.engine.rest.dto.CountResultDto;
import org.cibseven.bpm.engine.rest.dto.ExceptionDto;
import org.cibseven.bpm.engine.rest.dto.ModificationDto;
import org.cibseven.bpm.engine.rest.dto.SortingDto;
import org.cibseven.bpm.engine.rest.dto.StatisticsResultDto;
import org.cibseven.bpm.engine.rest.dto.TelemetryConfigurationDto;
import org.cibseven.bpm.engine.rest.dto.VariableValueDto;
import org.cibseven.bpm.engine.rest.dto.authorization.AuthorizationCheckResultDto;
import org.cibseven.bpm.engine.rest.dto.batch.BatchDto;
import org.cibseven.bpm.engine.rest.dto.batch.BatchStatisticsDto;
import org.cibseven.bpm.engine.rest.dto.externaltask.LockedExternalTaskDto;
import org.cibseven.bpm.engine.rest.dto.history.HistoricIncidentDto;
import org.cibseven.bpm.engine.rest.dto.history.HistoricProcessInstanceDto;
import org.cibseven.bpm.engine.rest.dto.history.HistoricVariableInstanceDto;
import org.cibseven.bpm.engine.rest.dto.message.MessageCorrelationResultDto;
import org.cibseven.bpm.engine.rest.dto.message.MessageCorrelationResultWithVariableDto;
import org.cibseven.bpm.engine.rest.dto.migration.MigrationInstructionDto;
import org.cibseven.bpm.engine.rest.dto.repository.ActivityStatisticsResultDto;
import org.cibseven.bpm.engine.rest.dto.repository.ProcessDefinitionDto;
import org.cibseven.bpm.engine.rest.dto.repository.ProcessDefinitionStatisticsResultDto;
import org.cibseven.bpm.engine.rest.dto.runtime.FilterDto;
import org.cibseven.bpm.engine.rest.dto.runtime.IncidentDto;
import org.cibseven.bpm.engine.rest.dto.runtime.JobDto;
import org.cibseven.bpm.engine.rest.dto.runtime.PriorityDto;
import org.cibseven.bpm.engine.rest.dto.runtime.ProcessInstanceDto;
import org.cibseven.bpm.engine.rest.dto.runtime.RetriesDto;
import org.cibseven.bpm.engine.rest.dto.runtime.StartProcessInstanceDto;
import org.cibseven.bpm.engine.rest.dto.runtime.batch.DeleteProcessInstancesDto;
import org.cibseven.bpm.engine.rest.dto.runtime.modification.ProcessInstanceModificationDto;
import org.cibseven.bpm.engine.rest.dto.task.TaskDto;
import org.cibseven.bpm.engine.rest.dto.task.TaskQueryDto;
import org.cibseven.bpm.engine.rest.dto.telemetry.LicenseKeyDataDto;
import org.cibseven.bpm.engine.rest.exception.ExceptionHandlerHelper;
import org.cibseven.bpm.engine.rest.exception.InvalidRequestException;
import org.cibseven.bpm.engine.rest.hal.HalResource;
import org.cibseven.bpm.engine.rest.hal.task.HalTask;
import org.cibseven.bpm.engine.rest.hal.task.HalTaskList;
import org.cibseven.bpm.engine.rest.hal.user.HalUser;
import org.cibseven.bpm.engine.rest.helper.MockTaskBuilder;
import org.cibseven.bpm.engine.runtime.ActivityInstance;
import org.cibseven.bpm.engine.runtime.Execution;
import org.cibseven.bpm.engine.runtime.Incident;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.MessageCorrelationResultType;
import org.cibseven.bpm.engine.runtime.MessageCorrelationResultWithVariables;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.externaltask.LockedExternalTask;
import org.cibseven.bpm.engine.task.DelegationState;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.variable.VariableMap;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.TypedValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the JSON wire format of the REST API on the level of the object mapper that
 * {@link JacksonConfigurator} provides to the JAX-RS JSON provider and to the resources.
 *
 * <p>Dates are formatted in the JVM default time zone, so the test fixes it to
 * {@link WireFixtures#ZONE} and builds the mapper afterwards. Entities come from
 * Mockito mocks with fixed values (not from {@code MockProvider}, whose dates depend on the
 * time zone at class initialisation).
 *
 * @see WireFixtures
 * @see RestJsonWireContractContainerTest
 */
public class RestJsonWireContractTest {

  /** 2013-01-23T12:42:42Z, winter time in Berlin. */
  static final Date WINTER = new Date(1358944962000L);
  /** 2013-07-23T12:22:42.123Z, summer time in Berlin. */
  static final Date SUMMER = new Date(1374582162123L);
  /** 2014-04-24T14:10:44.007Z. */
  static final Date OTHER = new Date(1398348644007L);

  static final String SPECIAL_TEXT = "a/b \"q\" \\ \t ü € 😀 \u0007 <x>&'";
  /** Non-BMP characters and Unicode line terminators, which JSON allows unescaped. */
  static final String UNICODE_TEXT = "emoji 😀🎉 [\u2028] [\u2029] [\u0085] [\uFEFF] [\u007F] ß 中文 \uD834\uDD1E";

  static TimeZone previousTimeZone;
  static ObjectMapper mapper;

  @BeforeAll
  public static void fixTimeZone() {
    previousTimeZone = TimeZone.getDefault();
    TimeZone.setDefault(WireFixtures.ZONE);
    mapper = new JacksonConfigurator().getContext(Object.class);
  }

  @AfterAll
  public static void restoreTimeZone() {
    TimeZone.setDefault(previousTimeZone);
  }

  // serialization ////////////////////////////////////////////////////////////

  @Test
  public void task() throws Exception {
    assertWritten("serialize/task.json", TaskDto.fromEntity(mockTask()));
  }

  @Test
  public void taskWithNullsAndDelegation() throws Exception {
    Task task = new MockTaskBuilder()
        .id("taskId2")
        .createTime(WINTER)
        .delegationState(DelegationState.RESOLVED)
        .priority(0)
        .build();
    assertWritten("serialize/task-nulls.json", TaskDto.fromEntity(task));
  }

  @Test
  public void taskListWithCustomDateFormat() throws Exception {
    String previousFormat = JacksonConfigurator.dateFormatString;
    try {
      JacksonConfigurator.setDateFormatString("yyyy-MM-dd'T'HH:mm:ss");
      ObjectMapper customMapper = new JacksonConfigurator().getContext(Object.class);
      List<TaskDto> tasks = Arrays.asList(TaskDto.fromEntity(mockTask()));
      assertMatches("serialize/task-list-custom-date-format.json", customMapper.writeValueAsBytes(tasks));
    } finally {
      JacksonConfigurator.setDateFormatString(previousFormat);
    }
  }

  @Test
  public void halTask() throws Exception {
    HalTask halTask = HalTask.fromTask(mockTask());
    assertWritten("serialize/hal-task.json", halTask);
  }

  @Test
  public void halTaskListWithEmbeddedResources() throws Exception {
    HalTaskList list = HalTaskList.fromTaskList(Arrays.asList(mockTask()), 1);
    List<HalResource<?>> users = new ArrayList<>();
    users.add(HalUser.fromUser(mockUser()));
    list.addEmbedded("assignee", users);
    assertWritten("serialize/hal-task-list.json", list);
  }

  @Test
  public void filterWithTaskQuery() throws Exception {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("color", "#ff0000");
    properties.put("priority", 5);
    properties.put("refresh", true);
    properties.put("variables", Arrays.asList(Collections.singletonMap("name", "amount")));

    FilterDto dto = new FilterDto();
    dto.setId("filterId");
    dto.setResourceType("Task");
    dto.setName("My tasks");
    dto.setOwner("demo");
    dto.setQuery(taskQueryDto());
    dto.setProperties(properties);
    dto.setItemCount(42L);
    assertWritten("serialize/filter.json", dto);
  }

  @Test
  public void filterWithoutItemCount() throws Exception {
    FilterDto dto = new FilterDto();
    dto.setId("filterId");
    dto.setResourceType("Task");
    dto.setQuery(new TaskQueryDto());
    assertWritten("serialize/filter-without-item-count.json", dto);
  }

  @Test
  public void statisticsResults() throws Exception {
    IncidentStatistics incidentStatistics = mock(IncidentStatistics.class);
    when(incidentStatistics.getIncidentType()).thenReturn("failedJob");
    when(incidentStatistics.getIncidentCount()).thenReturn(3);

    ProcessDefinitionStatistics definitionStatistics = mock(ProcessDefinitionStatistics.class);
    when(definitionStatistics.getId()).thenReturn("processDefinitionId");
    when(definitionStatistics.getInstances()).thenReturn(7);
    when(definitionStatistics.getFailedJobs()).thenReturn(2);
    when(definitionStatistics.getIncidentStatistics()).thenReturn(Arrays.asList(incidentStatistics));
    when(definitionStatistics.getKey()).thenReturn("invoice");
    when(definitionStatistics.getName()).thenReturn("Invoice");
    when(definitionStatistics.getVersion()).thenReturn(3);
    when(definitionStatistics.getDeploymentId()).thenReturn("deploymentId");

    ActivityStatistics activityStatistics = mock(ActivityStatistics.class);
    when(activityStatistics.getId()).thenReturn("userTask");
    when(activityStatistics.getInstances()).thenReturn(1);
    when(activityStatistics.getFailedJobs()).thenReturn(0);
    when(activityStatistics.getIncidentStatistics()).thenReturn(Collections.<IncidentStatistics>emptyList());

    List<StatisticsResultDto> results = new ArrayList<>();
    results.add(ProcessDefinitionStatisticsResultDto.fromProcessDefinitionStatistics(definitionStatistics));
    results.add(ActivityStatisticsResultDto.fromActivityStatistics(activityStatistics));
    // untyped (element type Object): no type id
    assertWritten("serialize/statistics.json", results);
    // typed as by the JAX-RS provider for List<StatisticsResultDto>: with "@class"
    assertMatches("serialize/statistics-typed.json",
        mapper.writerFor(new TypeReference<List<StatisticsResultDto>>() {}).writeValueAsBytes(results));
  }

  @Test
  public void primitiveVariableValues() throws Exception {
    Map<String, VariableValueDto> variables = new LinkedHashMap<>();
    variables.put("string", VariableValueDto.fromTypedValue(Variables.stringValue(SPECIAL_TEXT)));
    variables.put("integer", VariableValueDto.fromTypedValue(Variables.integerValue(42)));
    variables.put("long", VariableValueDto.fromTypedValue(Variables.longValue(Long.MAX_VALUE)));
    variables.put("short", VariableValueDto.fromTypedValue(Variables.shortValue((short) -7)));
    variables.put("double", VariableValueDto.fromTypedValue(Variables.doubleValue(1.5e-10)));
    variables.put("doubleWhole", VariableValueDto.fromTypedValue(Variables.doubleValue(1.0)));
    variables.put("doubleNaN", VariableValueDto.fromTypedValue(Variables.doubleValue(Double.NaN)));
    variables.put("boolean", VariableValueDto.fromTypedValue(Variables.booleanValue(true)));
    variables.put("dateWinter", VariableValueDto.fromTypedValue(Variables.dateValue(WINTER)));
    variables.put("dateSummer", VariableValueDto.fromTypedValue(Variables.dateValue(SUMMER)));
    variables.put("bytes", VariableValueDto.fromTypedValue(Variables.byteArrayValue("someBytes".getBytes(StandardCharsets.UTF_8))));
    variables.put("null", VariableValueDto.fromTypedValue(Variables.untypedNullValue()));
    variables.put("typedNull", VariableValueDto.fromTypedValue(Variables.stringValue(null)));
    variables.put("file", VariableValueDto.fromTypedValue(Variables.fileValue("invoice.pdf")
        .file("content".getBytes(StandardCharsets.UTF_8)).mimeType("application/pdf").encoding("UTF-8").create()));
    assertWritten("serialize/variables-primitive.json", variables);
  }

  @Test
  public void unicodeText() throws Exception {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("task", TaskDto.fromEntity(new MockTaskBuilder().id("taskId").name(UNICODE_TEXT).build()));
    result.put("variable", VariableValueDto.fromTypedValue(Variables.stringValue(UNICODE_TEXT)));
    assertWritten("serialize/unicode-text.json", result);
    // and back
    assertRead("deserialize/unicode-text.json", VariableValueDto.class,
        "{\"type\":\"String\",\"value\":\"" + UNICODE_TEXT + " \\u2028 \\uD83D\\uDE00\"}");
  }

  @Test
  public void serializedObjectVariableValue() throws Exception {
    TypedValue value = Variables.serializedObjectValue("{\"amount\":1.5,\"customer\":\"a/b\"}")
        .serializationDataFormat("application/json")
        .objectTypeName("org.example.Invoice")
        .create();
    assertWritten("serialize/variable-object-serialized.json", VariableValueDto.fromTypedValue(value));
  }

  @Test
  public void deserializedObjectVariableValueWithJavaTime() throws Exception {
    TypedValue value = Variables.objectValue(new JavaTimeHolder()).create();
    assertWritten("serialize/variable-object-java-time.json", VariableValueDto.fromTypedValue(value));
  }

  @Test
  public void deserializedObjectVariableValueWithOptional() throws Exception {
    TypedValue value = Variables.objectValue(new OptionalHolder()).create();
    assertMatches("serialize/variable-object-optional.json", write(VariableValueDto.fromTypedValue(value)));
  }

  @Test
  public void exceptions() throws Exception {
    ExceptionHandlerHelper helper = ExceptionHandlerHelper.getInstance();
    List<ExceptionDto> exceptions = new ArrayList<>();
    exceptions.add(helper.fromException(new InvalidRequestException(Status.BAD_REQUEST, "Cannot parse '/a/b'")));
    exceptions.add(helper.fromException(new InvalidRequestException(Status.BAD_REQUEST, new IllegalStateException("cause"), "")));
    exceptions.add(helper.fromException(new ProcessEngineException("engine failure", 22222)));
    exceptions.add(helper.fromException(new AuthorizationException("demo", Arrays.asList(
        new MissingAuthorization("READ", "Task", "taskId"),
        new MissingAuthorization("UPDATE", "ProcessInstance", null)))));
    assertWritten("serialize/exceptions.json", exceptions);
  }

  @Test
  public void batch() throws Exception {
    Batch batch = mock(Batch.class);
    when(batch.getId()).thenReturn("batchId");
    when(batch.getType()).thenReturn("instance-migration");
    when(batch.getTotalJobs()).thenReturn(10);
    when(batch.getJobsCreated()).thenReturn(5);
    when(batch.getBatchJobsPerSeed()).thenReturn(100);
    when(batch.getInvocationsPerBatchJob()).thenReturn(1);
    when(batch.getSeedJobDefinitionId()).thenReturn("seedJobDefinitionId");
    when(batch.getMonitorJobDefinitionId()).thenReturn("monitorJobDefinitionId");
    when(batch.getBatchJobDefinitionId()).thenReturn("batchJobDefinitionId");
    when(batch.isSuspended()).thenReturn(true);
    when(batch.getTenantId()).thenReturn(null);
    when(batch.getCreateUserId()).thenReturn("demo");
    when(batch.getStartTime()).thenReturn(WINTER);
    when(batch.getExecutionStartTime()).thenReturn(SUMMER);

    BatchStatistics statistics = mock(BatchStatistics.class);
    when(statistics.getId()).thenReturn("batchId");
    when(statistics.getType()).thenReturn("instance-deletion");
    when(statistics.getRemainingJobs()).thenReturn(3);
    when(statistics.getCompletedJobs()).thenReturn(6);
    when(statistics.getFailedJobs()).thenReturn(1);
    when(statistics.getStartTime()).thenReturn(OTHER);

    List<Object> batches = new ArrayList<>();
    batches.add(BatchDto.fromBatch(batch));
    batches.add(BatchStatisticsDto.fromBatchStatistics(statistics));
    assertWritten("serialize/batch.json", batches);
  }

  @Test
  public void jobAndIncident() throws Exception {
    Job job = mock(Job.class);
    when(job.getId()).thenReturn("jobId");
    when(job.getDuedate()).thenReturn(SUMMER);
    when(job.getCreateTime()).thenReturn(WINTER);
    when(job.getProcessInstanceId()).thenReturn("processInstanceId");
    when(job.getRetries()).thenReturn(0);
    when(job.getExceptionMessage()).thenReturn(SPECIAL_TEXT);
    when(job.isSuspended()).thenReturn(false);
    when(job.getPriority()).thenReturn(Long.MIN_VALUE);

    Incident incident = mock(Incident.class);
    when(incident.getId()).thenReturn("incidentId");
    when(incident.getIncidentTimestamp()).thenReturn(OTHER);
    when(incident.getIncidentType()).thenReturn("failedJob");
    when(incident.getIncidentMessage()).thenReturn("exception");
    when(incident.getConfiguration()).thenReturn("jobId");
    when(incident.getAnnotation()).thenReturn("an annotation");

    List<Object> result = new ArrayList<>();
    result.add(JobDto.fromJob(job));
    result.add(IncidentDto.fromIncident(incident));
    assertWritten("serialize/job-incident.json", result);
  }

  @Test
  public void processInstanceWithLinks() throws Exception {
    ProcessInstance instance = mock(ProcessInstance.class);
    when(instance.getId()).thenReturn("processInstanceId");
    when(instance.getProcessDefinitionId()).thenReturn("invoice:1:abc");
    when(instance.getBusinessKey()).thenReturn("businessKey");
    when(instance.isEnded()).thenReturn(false);
    when(instance.isSuspended()).thenReturn(true);

    ProcessInstanceDto dto = ProcessInstanceDto.fromProcessInstance(instance);
    dto.addReflexiveLink(URI.create("http://localhost:8080/engine-rest/process-instance/processInstanceId"), "GET", "self");
    assertWritten("serialize/process-instance.json", dto);
  }

  @Test
  public void messageCorrelationResults() throws Exception {
    Execution execution = mock(Execution.class);
    when(execution.getId()).thenReturn("executionId");
    when(execution.getProcessInstanceId()).thenReturn("processInstanceId");
    when(execution.isEnded()).thenReturn(false);

    ProcessInstance processInstance = mock(ProcessInstance.class);
    when(processInstance.getId()).thenReturn("processInstanceId");
    when(processInstance.getProcessDefinitionId()).thenReturn("invoice:1:abc");

    MessageCorrelationResultWithVariables executionResult = mock(MessageCorrelationResultWithVariables.class);
    when(executionResult.getResultType()).thenReturn(MessageCorrelationResultType.Execution);
    when(executionResult.getExecution()).thenReturn(execution);
    VariableMap variables = Variables.createVariables()
        .putValue("amount", 10)
        .putValueTyped("created", Variables.dateValue(WINTER));
    when(executionResult.getVariables()).thenReturn(variables);

    MessageCorrelationResultWithVariables definitionResult = mock(MessageCorrelationResultWithVariables.class);
    when(definitionResult.getResultType()).thenReturn(MessageCorrelationResultType.ProcessDefinition);
    when(definitionResult.getProcessInstance()).thenReturn(processInstance);

    List<Object> results = new ArrayList<>();
    results.add(MessageCorrelationResultDto.fromMessageCorrelationResult(executionResult));
    results.add(MessageCorrelationResultWithVariableDto.fromMessageCorrelationResultWithVariables(executionResult));
    results.add(MessageCorrelationResultDto.fromMessageCorrelationResult(definitionResult));
    assertWritten("serialize/message-correlation.json", results);
  }

  @Test
  public void historicProcessInstanceAndIncident() throws Exception {
    HistoricProcessInstance instance = mock(HistoricProcessInstance.class);
    when(instance.getId()).thenReturn("processInstanceId");
    when(instance.getBusinessKey()).thenReturn("businessKey");
    when(instance.getProcessDefinitionId()).thenReturn("invoice:1:abc");
    when(instance.getStartTime()).thenReturn(WINTER);
    when(instance.getEndTime()).thenReturn(SUMMER);
    when(instance.getRemovalTime()).thenReturn(OTHER);
    when(instance.getDurationInMillis()).thenReturn(15637200123L);
    when(instance.getState()).thenReturn("COMPLETED");

    HistoricIncident incident = mock(HistoricIncident.class);
    when(incident.getId()).thenReturn("incidentId");
    when(incident.getCreateTime()).thenReturn(WINTER);
    when(incident.getEndTime()).thenReturn(null);
    when(incident.isOpen()).thenReturn(true);
    when(incident.isDeleted()).thenReturn(false);
    when(incident.isResolved()).thenReturn(false);

    List<Object> result = new ArrayList<>();
    result.add(HistoricProcessInstanceDto.fromHistoricProcessInstance(instance));
    result.add(HistoricIncidentDto.fromHistoricIncident(incident));
    assertWritten("serialize/historic-process-instance-incident.json", result);
  }

  @Test
  public void historicVariableInstance() throws Exception {
    HistoricVariableInstance variable = mock(HistoricVariableInstance.class);
    when(variable.getId()).thenReturn("variableId");
    when(variable.getName()).thenReturn("amount");
    when(variable.getTypedValue()).thenReturn(Variables.doubleValue(12.5));
    when(variable.getCreateTime()).thenReturn(WINTER);
    when(variable.getRemovalTime()).thenReturn(SUMMER);
    when(variable.getState()).thenReturn("CREATED");
    assertWritten("serialize/historic-variable-instance.json", HistoricVariableInstanceDto.fromHistoricVariableInstance(variable));
  }

  @Test
  public void processDefinition() throws Exception {
    ProcessDefinition definition = mock(ProcessDefinition.class);
    when(definition.getId()).thenReturn("invoice:1:abc");
    when(definition.getKey()).thenReturn("invoice");
    when(definition.getName()).thenReturn("Invoice Receipt");
    when(definition.getVersion()).thenReturn(1);
    when(definition.getResourceName()).thenReturn("invoice/invoice.bpmn");
    when(definition.getDiagramResourceName()).thenReturn("invoice/invoice.png");
    when(definition.isSuspended()).thenReturn(false);
    when(definition.hasStartFormKey()).thenReturn(true);
    when(definition.isStartableInTasklist()).thenReturn(true);
    when(definition.getHistoryTimeToLive()).thenReturn(null);
    assertWritten("serialize/process-definition.json", ProcessDefinitionDto.fromProcessDefinition(definition));
  }

  @Test
  public void lockedExternalTask() throws Exception {
    LockedExternalTask task = mock(LockedExternalTask.class);
    when(task.getId()).thenReturn("externalTaskId");
    when(task.getTopicName()).thenReturn("payment");
    when(task.getWorkerId()).thenReturn("worker");
    when(task.getLockExpirationTime()).thenReturn(SUMMER);
    when(task.getCreateTime()).thenReturn(WINTER);
    when(task.getRetries()).thenReturn(null);
    when(task.getPriority()).thenReturn(100L);
    when(task.getVariables()).thenReturn(Variables.createVariables().putValue("amount", 10L));
    Map<String, String> extensionProperties = new LinkedHashMap<>();
    extensionProperties.put("key", "value");
    when(task.getExtensionProperties()).thenReturn(extensionProperties);
    assertWritten("serialize/locked-external-task.json", LockedExternalTaskDto.fromLockedExternalTask(task));
  }

  @Test
  public void activityInstanceTree() throws Exception {
    ActivityInstance child = mock(ActivityInstance.class);
    when(child.getId()).thenReturn("childId");
    when(child.getActivityId()).thenReturn("userTask");
    when(child.getActivityType()).thenReturn("userTask");
    when(child.getChildActivityInstances()).thenReturn(new ActivityInstance[0]);
    when(child.getChildTransitionInstances()).thenReturn(new org.cibseven.bpm.engine.runtime.TransitionInstance[0]);
    when(child.getExecutionIds()).thenReturn(new String[] {"executionId"});
    when(child.getIncidentIds()).thenReturn(new String[0]);
    when(child.getIncidents()).thenReturn(new Incident[0]);

    ActivityInstance root = mock(ActivityInstance.class);
    when(root.getId()).thenReturn("rootId");
    when(root.getActivityId()).thenReturn("invoice:1:abc");
    when(root.getActivityType()).thenReturn("processDefinition");
    when(root.getChildActivityInstances()).thenReturn(new ActivityInstance[] {child});
    when(root.getChildTransitionInstances()).thenReturn(new org.cibseven.bpm.engine.runtime.TransitionInstance[0]);
    when(root.getExecutionIds()).thenReturn(new String[] {"processInstanceId"});
    when(root.getIncidentIds()).thenReturn(new String[0]);
    when(root.getIncidents()).thenReturn(new Incident[0]);
    assertWritten("serialize/activity-instance.json",
        org.cibseven.bpm.engine.rest.dto.runtime.ActivityInstanceDto.fromActivityInstance(root));
  }

  @Test
  public void booleanWrapperGettersAndRenamedProperties() throws Exception {
    AuthorizationCheckResultDto authorizationCheck = new AuthorizationCheckResultDto();
    authorizationCheck.setPermissionName("READ");
    authorizationCheck.setResourceName("Task");
    authorizationCheck.setResourceId("*");
    authorizationCheck.setAuthorized(Boolean.TRUE);

    TelemetryConfigurationDto telemetry = new TelemetryConfigurationDto();
    telemetry.setEnableTelemetry(Boolean.FALSE);

    MigrationInstructionDto instruction = new MigrationInstructionDto();
    instruction.setSourceActivityIds(Arrays.asList("a"));
    instruction.setTargetActivityIds(Arrays.asList("b"));
    instruction.setUpdateEventTrigger(Boolean.TRUE);

    LicenseKeyDataDto licenseKey = new LicenseKeyDataDto("customer", "type", "2030-01-01", Boolean.FALSE,
        Collections.singletonMap("cawemo", "true"), "raw");

    List<Object> result = new ArrayList<>();
    result.add(authorizationCheck);
    result.add(telemetry);
    result.add(instruction);
    result.add(licenseKey);
    assertWritten("serialize/boolean-getters.json", result);
  }

  @Test
  public void sortingAndCount() throws Exception {
    SortingDto withoutParameters = new SortingDto();
    withoutParameters.setSortBy("dueDate");
    withoutParameters.setSortOrder("asc");
    SortingDto withParameters = new SortingDto();
    withParameters.setSortBy("processVariable");
    withParameters.setSortOrder("desc");
    withParameters.setParameters(Collections.<String, Object>singletonMap("variable", "amount"));

    List<Object> result = new ArrayList<>();
    result.add(withoutParameters);
    result.add(withParameters);
    result.add(new CountResultDto(3L));
    assertWritten("serialize/sorting-count.json", result);
  }

  // deserialization //////////////////////////////////////////////////////////

  @Test
  public void readNullForPrimitives() throws Exception {
    assertRead("deserialize/modification-null-primitives.json", ModificationDto.class,
        "{\"skipCustomListeners\":null,\"skipIoMappings\":null,\"processDefinitionId\":\"pd\","
        + "\"instructions\":[{\"type\":\"startBeforeActivity\",\"activityId\":\"a\"},"
        + "{\"type\":\"cancel\",\"activityId\":\"b\",\"cancelCurrentActiveActivityInstances\":null}],"
        + "\"processInstanceIds\":[\"p1\"],\"annotation\":\"x\"}");
    assertRead("deserialize/delete-process-instances-null-primitives.json", DeleteProcessInstancesDto.class,
        "{\"processInstanceIds\":[\"p1\",\"p2\"],\"deleteReason\":\"r\",\"skipCustomListeners\":null,"
        + "\"skipIoMappings\":null}");
    // setSkipSubprocesses(Boolean) unboxes into a boolean field: null fails in the setter (400)
    assertRead("deserialize/delete-process-instances-null-boxed-setter.json", DeleteProcessInstancesDto.class,
        "{\"processInstanceIds\":[\"p1\"],\"skipSubprocesses\":null}");
  }

  @Test
  public void readUnknownPropertiesAndTrailingTokens() throws Exception {
    assertRead("deserialize/unknown-properties.json", DeleteProcessInstancesDto.class,
        "{\"deleteReason\":\"r\",\"unknown\":{\"a\":[1,2,{\"b\":null}]},\"other\":true}");
    assertRead("deserialize/trailing-tokens.json", DeleteProcessInstancesDto.class,
        "{\"deleteReason\":\"r\"} {\"deleteReason\":\"s\"}");
    assertRead("deserialize/duplicate-keys.json", DeleteProcessInstancesDto.class,
        "{\"deleteReason\":\"first\",\"deleteReason\":\"second\"}");
    assertRead("deserialize/single-value-as-array.json", DeleteProcessInstancesDto.class,
        "{\"processInstanceIds\":\"p1\"}");
    assertRead("deserialize/null-literal.json", DeleteProcessInstancesDto.class, "null");
  }

  @Test
  public void readDates() throws Exception {
    Map<String, String> inputs = new LinkedHashMap<>();
    inputs.put("rfc822-offset", "\"2013-01-23T13:42:42.000+0100\"");
    inputs.put("other-offset", "\"2013-07-23T14:22:42.123-0300\"");
    inputs.put("iso-offset", "\"2013-01-23T13:42:42.000+01:00\"");
    inputs.put("utc-designator", "\"2013-01-23T12:42:42.000Z\"");
    inputs.put("without-millis", "\"2013-01-23T13:42:42+0100\"");
    inputs.put("without-zone", "\"2013-01-23T13:42:42\"");
    inputs.put("date-only", "\"2013-01-23\"");
    inputs.put("timestamp", "1358944962000");
    inputs.put("empty", "\"\"");
    inputs.put("null", "null");
    Map<String, String> results = new LinkedHashMap<>();
    for (Map.Entry<String, String> input : inputs.entrySet()) {
      results.put(input.getKey(), read(TaskDto.class, "{\"due\":" + input.getValue() + "}", dto -> dto.getDue()));
    }
    assertWritten("deserialize/dates.json", results);
  }

  @Test
  public void readNumberCoercions() throws Exception {
    Map<String, String> results = new LinkedHashMap<>();
    results.put("int-from-string", read(RetriesDto.class, "{\"retries\":\"4\"}", dto -> dto.getRetries()));
    results.put("int-from-float", read(RetriesDto.class, "{\"retries\":4.7}", dto -> dto.getRetries()));
    results.put("int-from-null", read(RetriesDto.class, "{\"retries\":null}", dto -> dto.getRetries()));
    results.put("int-from-empty-string", read(RetriesDto.class, "{\"retries\":\"\"}", dto -> dto.getRetries()));
    results.put("int-overflow", read(RetriesDto.class, "{\"retries\":99999999999}", dto -> dto.getRetries()));
    results.put("int-from-boolean", read(RetriesDto.class, "{\"retries\":true}", dto -> dto.getRetries()));
    results.put("long-from-string", read(PriorityDto.class, "{\"priority\":\"12\"}", dto -> dto.getPriority()));
    results.put("long-overflow", read(PriorityDto.class, "{\"priority\":99999999999999999999}", dto -> dto.getPriority()));
    assertWritten("deserialize/number-coercions.json", results);
  }

  @Test
  public void readPolymorphicInstructions() throws Exception {
    assertRead("deserialize/modification-instructions.json", ProcessInstanceModificationDto.class,
        "{\"annotation\":\"a\",\"skipCustomListeners\":true,\"instructions\":["
        + "{\"type\":\"startTransition\",\"transitionId\":\"t\",\"variables\":{\"v\":{\"value\":\"x\",\"type\":\"String\",\"local\":true}}},"
        + "{\"type\":\"startAfterActivity\",\"activityId\":\"a\",\"ancestorActivityInstanceId\":\"ai\"},"
        + "{\"type\":\"cancel\",\"activityInstanceId\":\"ai2\"}]}");
    assertRead("deserialize/modification-unknown-instruction.json", ProcessInstanceModificationDto.class,
        "{\"instructions\":[{\"type\":\"unknownType\",\"activityId\":\"a\"}]}");
  }

  @Test
  public void readFilterWithTaskQuery() throws Exception {
    assertRead("deserialize/filter.json", FilterDto.class,
        "{\"resourceType\":\"Task\",\"name\":\"f\",\"owner\":\"o\","
        + "\"query\":{\"assignee\":\"demo\",\"dueBefore\":\"2013-01-23T13:42:42.000+0100\","
        + "\"sorting\":[{\"sortBy\":\"dueDate\",\"sortOrder\":\"asc\"}],\"orQueries\":[{\"candidateGroup\":\"g\"}]},"
        + "\"properties\":{\"color\":\"#ff0000\",\"priority\":5,\"refresh\":true,\"variables\":[{\"name\":\"v\",\"label\":\"V\"}]}}");
    assertRead("deserialize/filter-without-resource-type.json", FilterDto.class,
        "{\"name\":\"f\",\"query\":{\"assignee\":\"demo\"}}");
  }

  @Test
  public void readUntypedVariableValues() throws Exception {
    StartProcessInstanceDto dto = mapper.readValue(
        "{\"businessKey\":\"bk\",\"withVariablesInReturn\":null,\"variables\":{"
        + "\"int\":{\"value\":1},\"double\":{\"value\":1.5},\"long\":{\"value\":12345678901},"
        + "\"big\":{\"value\":123456789012345678901234567890},\"bool\":{\"value\":true},\"null\":{\"value\":null},"
        + "\"list\":{\"value\":[1,\"x\"]},\"map\":{\"value\":{\"k\":\"v\"}},"
        + "\"string\":{\"value\":\"s\",\"type\":\"String\",\"valueInfo\":{\"transient\":true}}}}",
        StartProcessInstanceDto.class);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("dto", dto);
    Map<String, String> valueTypes = new LinkedHashMap<>();
    for (Map.Entry<String, VariableValueDto> variable : dto.getVariables().entrySet()) {
      Object value = variable.getValue().getValue();
      valueTypes.put(variable.getKey(), value == null ? null : value.getClass().getName());
    }
    result.put("valueTypes", valueTypes);
    assertWritten("deserialize/untyped-variable-values.json", result);
  }

  @Test
  public void readAndWriteDeepNesting() throws Exception {
    // the nesting depth limit of Jackson 2 (1000, reading and writing) is kept, Jackson 3 has 500
    assertRead("deserialize/nesting-depth-700.json", StartProcessInstanceDto.class,
        "{\"variables\":{\"deep\":{\"value\":" + nestedArrays(700) + "}}}");
    assertRead("deserialize/nesting-depth-1100.json", StartProcessInstanceDto.class,
        "{\"variables\":{\"deep\":{\"value\":" + nestedArrays(1100) + "}}}");
  }

  @Test
  public void readEnum() throws Exception {
    assertRead("deserialize/enum.json", MessageCorrelationResultDto.class, "{\"resultType\":\"ProcessDefinition\"}");
    assertRead("deserialize/enum-unknown.json", MessageCorrelationResultDto.class, "{\"resultType\":\"processDefinition\"}");
  }

  // helpers //////////////////////////////////////////////////////////////////

  protected void assertWritten(String fixture, Object value) throws Exception {
    assertMatches(fixture, mapper.writeValueAsBytes(value));
  }

  /**
   * Writes the value, or returns <code>"rejected"</code> if the mapper refuses it.
   */
  protected String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      return "rejected";
    }
  }

  /**
   * Reads the JSON and pins the result: the read value written again by the same mapper,
   * or <code>"rejected"</code> if the mapper refuses the input (the REST API answers 400 then).
   */
  protected void assertRead(String fixture, Class<?> type, String json) throws Exception {
    assertMatches(fixture, read(type, json, value -> value));
  }

  protected <T> String read(Class<T> type, String json, ValueExtractor<T> extractor) throws Exception {
    Object value;
    try {
      value = extractor.extract(mapper.readValue(json, type));
    } catch (Exception e) {
      return "rejected";
    }
    if (value instanceof Date) {
      // a date read with the wrong zone shows up as a different instant
      return "epochMillis=" + ((Date) value).getTime();
    }
    return mapper.writeValueAsString(value);
  }

  static String nestedArrays(int depth) {
    StringBuilder json = new StringBuilder();
    for (int i = 0; i < depth; i++) {
      json.append('[');
    }
    json.append("\"x\"");
    for (int i = 0; i < depth; i++) {
      json.append(']');
    }
    return json.toString();
  }

  protected interface ValueExtractor<T> {
    Object extract(T value);
  }

  protected Task mockTask() {
    return new MockTaskBuilder()
        .id("taskId")
        .name("Approve invoice / check \"amount\"")
        .assignee("demo")
        .owner("john")
        .createTime(WINTER)
        .lastUpdated(OTHER)
        .dueDate(SUMMER)
        .followUpDate(WINTER)
        .delegationState(DelegationState.PENDING)
        .description(SPECIAL_TEXT)
        .executionId("executionId")
        .parentTaskId(null)
        .priority(50)
        .processDefinitionId("invoice:1:abc")
        .processInstanceId("processInstanceId")
        .taskDefinitionKey("approveInvoice")
        .formKey("embedded:app:forms/approve.html")
        .tenantId("tenant1")
        .taskState("Created")
        .hasAttachment(true)
        .hasComment(false)
        .build();
  }

  protected User mockUser() {
    User user = mock(User.class);
    when(user.getId()).thenReturn("demo");
    when(user.getFirstName()).thenReturn("Demo");
    when(user.getLastName()).thenReturn("Müller");
    when(user.getEmail()).thenReturn("demo.mueller(at)example.org");
    return user;
  }

  protected TaskQueryDto taskQueryDto() {
    TaskQueryDto query = new TaskQueryDto();
    query.setAssignee("demo");
    query.setDueBefore(SUMMER);
    query.setCandidateGroups(Arrays.asList("accounting", "sales"));
    query.setPriority(50);
    SortingDto sorting = new SortingDto();
    sorting.setSortBy("dueDate");
    sorting.setSortOrder("asc");
    query.setSorting(Arrays.asList(sorting));
    TaskQueryDto orQuery = new TaskQueryDto();
    orQuery.setCandidateUser("john");
    query.setOrQueries(Arrays.asList(orQuery));
    return query;
  }

  /** Object variable value with java.time types, serialized by the REST mapper when deserializeValues=true. */
  public static class JavaTimeHolder {
    public LocalDate localDate = LocalDate.of(2013, 1, 23);
    public LocalDateTime localDateTime = LocalDateTime.of(2013, 1, 23, 13, 42, 42, 123_000_000);
    public LocalTime localTime = LocalTime.of(13, 42, 42);
    public Instant instant = Instant.ofEpochMilli(1358944962123L);
    public OffsetDateTime offsetDateTime = OffsetDateTime.of(2013, 7, 23, 14, 22, 42, 0, ZoneOffset.ofHours(-3));
    public ZonedDateTime zonedDateTime = ZonedDateTime.of(2013, 7, 23, 14, 22, 42, 0, ZoneId.of("America/New_York"));
    public Duration duration = Duration.ofSeconds(3723, 5);
    public Period period = Period.of(1, 2, 3);
    public YearMonth yearMonth = YearMonth.of(2013, 1);
    public ZoneId zoneId = ZoneId.of("Europe/Berlin");
    public Date date = WINTER;
    public Calendar calendar = utcCalendar();
    public BigDecimal amount = new BigDecimal("1.50");

    static Calendar utcCalendar() {
      Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
      calendar.setTime(SUMMER);
      return calendar;
    }
  }

  /**
   * Object variable value with {@link Optional}. The only intended change of the move to
   * Jackson 3: Jackson 2 (without the JDK 8 module) refused to write it, so the REST API
   * answered 400; Jackson 3 supports {@link Optional} out of the box and writes the value.
   */
  public static class OptionalHolder {
    public Optional<String> present = Optional.of("value");
    public Optional<String> absent = Optional.empty();
  }

}
