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

import static io.restassured.RestAssured.given;
import static org.cibseven.bpm.engine.rest.mapper.WireFixtures.assertMatches;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.TimeZone;

import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.management.IncidentStatistics;
import org.cibseven.bpm.engine.management.ProcessDefinitionStatistics;
import org.cibseven.bpm.engine.management.ProcessDefinitionStatisticsQuery;
import org.cibseven.bpm.engine.management.SetJobRetriesBuilder;
import org.cibseven.bpm.engine.rest.AbstractRestServiceTest;
import org.cibseven.bpm.engine.rest.helper.MockTaskBuilder;
import org.cibseven.bpm.engine.rest.util.container.TestContainerRule;
import org.cibseven.bpm.engine.runtime.ProcessInstanceModificationBuilder;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.task.TaskQuery;
import org.cibseven.bpm.engine.variable.Variables;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.restassured.http.ContentType;
import io.restassured.response.Response;

/**
 * Pins the JSON wire format of the REST API end to end: through the JAX-RS runtime of the
 * test profile (Jersey or RESTEasy), the registered JSON and HAL providers, the
 * {@link JacksonConfigurator} lookup and the exception mappers.
 *
 * <p>Each fixture holds the status line and the response body.
 *
 * @see WireFixtures
 * @see RestJsonWireContractTest
 */
public class RestJsonWireContractContainerTest extends AbstractRestServiceTest {

  @RegisterExtension
  public static TestContainerRule rule = new TestContainerRule();

  protected static final String TASK_URL = TEST_RESOURCE_ROOT_PATH + "/task/{id}";
  protected static final String VARIABLES_URL = TEST_RESOURCE_ROOT_PATH + "/process-instance/{id}/variables";
  protected static final String VARIABLE_URL = VARIABLES_URL + "/{name}";
  protected static final String MODIFICATION_URL = TEST_RESOURCE_ROOT_PATH + "/process-instance/{id}/modification";
  protected static final String JOB_RETRIES_URL = TEST_RESOURCE_ROOT_PATH + "/job/{id}/retries";
  protected static final String STATISTICS_URL = TEST_RESOURCE_ROOT_PATH + "/process-definition/statistics";

  static TimeZone previousTimeZone;

  @BeforeAll
  public static void fixTimeZone() {
    // the providers locate the mapper lazily on the first request, i.e. after this
    previousTimeZone = TimeZone.getDefault();
    TimeZone.setDefault(WireFixtures.ZONE);
  }

  @AfterAll
  public static void restoreTimeZone() {
    TimeZone.setDefault(previousTimeZone);
  }

  @Test
  public void taskAsJsonAndHal() throws Exception {
    Task task = new MockTaskBuilder()
        .id("taskId")
        .name("Approve / check")
        .createTime(RestJsonWireContractTest.WINTER)
        .dueDate(RestJsonWireContractTest.SUMMER)
        .priority(50)
        .build();
    TaskQuery query = mock(TaskQuery.class, RETURNS_SELF);
    when(query.singleResult()).thenReturn(task);
    TaskService taskService = processEngine.getTaskService();
    when(taskService.createTaskQuery()).thenReturn(query);

    assertResponse("container/task.json", given()
        .header(ACCEPT_JSON_HEADER)
        .pathParam("id", "taskId")
        .get(TASK_URL));
    assertResponse("container/task-hal.json", given()
        .header(ACCEPT_HAL_HEADER)
        .pathParam("id", "taskId")
        .get(TASK_URL));
  }

  @Test
  public void variables() throws Exception {
    RuntimeService runtimeService = processEngine.getRuntimeService();
    when(runtimeService.getVariableTyped(eq("processInstanceId"), eq("date"), anyBoolean()))
        .thenReturn(Variables.dateValue(RestJsonWireContractTest.WINTER));
    when(runtimeService.getVariablesTyped(eq("processInstanceId"), anyBoolean()))
        .thenReturn(Variables.createVariables()
            .putValueTyped("date", Variables.dateValue(RestJsonWireContractTest.SUMMER))
            .putValueTyped("text", Variables.stringValue(RestJsonWireContractTest.SPECIAL_TEXT))
            .putValueTyped("object", Variables.objectValue(new RestJsonWireContractTest.JavaTimeHolder()).create()));

    assertResponse("container/variable-date.json", given()
        .header(ACCEPT_JSON_HEADER)
        .pathParam("id", "processInstanceId")
        .pathParam("name", "date")
        .get(VARIABLE_URL));
    assertResponse("container/variables.json", given()
        .header(ACCEPT_JSON_HEADER)
        .pathParam("id", "processInstanceId")
        .queryParam("deserializeValues", true)
        .get(VARIABLES_URL));
  }

  @Test
  public void unicodeText() throws Exception {
    Task task = new MockTaskBuilder().id("taskId").name(RestJsonWireContractTest.UNICODE_TEXT).build();
    TaskQuery query = mock(TaskQuery.class, RETURNS_SELF);
    when(query.singleResult()).thenReturn(task);
    TaskService taskService = processEngine.getTaskService();
    when(taskService.createTaskQuery()).thenReturn(query);
    RuntimeService runtimeService = processEngine.getRuntimeService();
    when(runtimeService.getVariableTyped(eq("processInstanceId"), eq("text"), anyBoolean()))
        .thenReturn(Variables.stringValue(RestJsonWireContractTest.UNICODE_TEXT));

    assertResponse("container/unicode-task.json", given()
        .header(ACCEPT_JSON_HEADER)
        .pathParam("id", "taskId")
        .get(TASK_URL));
    assertResponse("container/unicode-task-hal.json", given()
        .header(ACCEPT_HAL_HEADER)
        .pathParam("id", "taskId")
        .get(TASK_URL));
    assertResponse("container/unicode-variable.json", given()
        .header(ACCEPT_JSON_HEADER)
        .pathParam("id", "processInstanceId")
        .pathParam("name", "text")
        .get(VARIABLE_URL));
  }

  @Test
  public void statisticsWithTypeInfo() throws Exception {
    IncidentStatistics incidentStatistics = mock(IncidentStatistics.class);
    when(incidentStatistics.getIncidentType()).thenReturn("failedJob");
    when(incidentStatistics.getIncidentCount()).thenReturn(3);
    ProcessDefinitionStatistics statistics = mock(ProcessDefinitionStatistics.class);
    when(statistics.getId()).thenReturn("invoice:1:abc");
    when(statistics.getKey()).thenReturn("invoice");
    when(statistics.getInstances()).thenReturn(7);
    when(statistics.getFailedJobs()).thenReturn(2);
    when(statistics.getIncidentStatistics()).thenReturn(Arrays.asList(incidentStatistics));

    ProcessDefinitionStatisticsQuery query = mock(ProcessDefinitionStatisticsQuery.class, RETURNS_SELF);
    when(query.unlimitedList()).thenReturn(Collections.singletonList(statistics));
    ManagementService managementService = processEngine.getManagementService();
    when(managementService.createProcessDefinitionStatisticsQuery()).thenReturn(query);

    assertResponse("container/statistics.json", given()
        .header(ACCEPT_JSON_HEADER)
        .queryParam("failedJobs", true)
        .queryParam("incidents", true)
        .get(STATISTICS_URL));
  }

  @Test
  public void requestBodies() throws Exception {
    RuntimeService runtimeService = processEngine.getRuntimeService();
    ProcessInstanceModificationBuilder modificationBuilder = mock(ProcessInstanceModificationBuilder.class, RETURNS_SELF);
    when(runtimeService.createProcessInstanceModification("processInstanceId")).thenReturn(modificationBuilder);
    ManagementService managementService = processEngine.getManagementService();
    SetJobRetriesBuilder retriesBuilder = mock(SetJobRetriesBuilder.class, RETURNS_SELF);
    when(managementService.setJobRetries(anyInt())).thenReturn(retriesBuilder);

    assertResponse("container/request-null-primitives.json", post(MODIFICATION_URL, "processInstanceId",
        "{\"skipCustomListeners\":null,\"skipIoMappings\":null,\"instructions\":[{\"type\":\"cancel\",\"activityId\":\"a\"}]}"));
    assertResponse("container/request-unknown-property.json", post(MODIFICATION_URL, "processInstanceId",
        "{\"unknown\":{\"a\":1},\"instructions\":[{\"type\":\"cancel\",\"activityId\":\"a\"}]}"));
    assertResponse("container/request-malformed.json", post(MODIFICATION_URL, "processInstanceId",
        "{\"instructions\": ["));
    assertResponse("container/request-unknown-subtype.json", post(MODIFICATION_URL, "processInstanceId",
        "{\"instructions\":[{\"type\":\"unknownType\",\"activityId\":\"a\"}]}"));
    assertResponse("container/request-wrong-type.json", post(MODIFICATION_URL, "processInstanceId",
        "{\"instructions\":{\"type\":\"cancel\"}}"));
    // the nesting depth limit of Jackson 2 is 1000
    assertResponse("container/request-nesting-depth-700.json", given()
        .contentType(ContentType.JSON)
        .body(("{\"value\":" + RestJsonWireContractTest.nestedArrays(700) + "}").getBytes(StandardCharsets.UTF_8))
        .pathParam("id", "processInstanceId")
        .pathParam("name", "deep")
        .put(VARIABLE_URL));
    assertResponse("container/request-number-overflow.json", given()
        .contentType(ContentType.JSON)
        .body("{\"retries\":99999999999}".getBytes(StandardCharsets.UTF_8))
        .pathParam("id", "jobId")
        .put(JOB_RETRIES_URL));
  }

  protected Response post(String url, String id, String json) {
    return given()
        .contentType(ContentType.JSON)
        .header(ACCEPT_JSON_HEADER)
        .body(json.getBytes(StandardCharsets.UTF_8))
        .pathParam("id", id)
        .post(url);
  }

  protected void assertResponse(String fixture, Response response) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(("HTTP " + response.statusCode() + "\n").getBytes(StandardCharsets.US_ASCII));
    out.write(response.asByteArray());
    assertMatches(fixture, out.toByteArray());
  }

}
