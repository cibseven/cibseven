/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cibseven.bpm.engine.rest;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.rest.dto.converter.TaskReportResultToCsvConverter;
import org.cibseven.bpm.engine.rest.util.container.TestContainerRule;
import org.cibseven.bpm.engine.task.TaskCountByCandidateGroupResult;
import org.cibseven.bpm.engine.task.TaskReport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;

import jakarta.ws.rs.core.Response.Status;
import java.util.List;

import static io.restassured.RestAssured.given;
import static io.restassured.path.json.JsonPath.from;
import static org.cibseven.bpm.engine.rest.helper.MockProvider.EXAMPLE_GROUP_ID;
import static org.cibseven.bpm.engine.rest.helper.MockProvider.EXAMPLE_TASK_COUNT_BY_CANDIDATE_GROUP;
import static org.cibseven.bpm.engine.rest.helper.MockProvider.createMockTaskCountByCandidateGroupReport;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * @author Roman Smirnov
 *
 */
public class TaskReportRestServiceTest extends AbstractRestServiceTest {

  @RegisterExtension
  public static TestContainerRule rule = new TestContainerRule();

  protected static final String TASK_REPORT_URL = TEST_RESOURCE_ROOT_PATH + "/task/report";
  protected static final String CANDIDATE_GROUP_REPORT_URL = TASK_REPORT_URL + "/candidate-group-count";

  protected TaskReport mockedReportQuery;

  @BeforeEach
  public void setUpRuntimeData() {
    mockedReportQuery = setUpMockHistoricProcessInstanceReportQuery();
  }

  private TaskReport setUpMockHistoricProcessInstanceReportQuery() {
    TaskReport mockedReportQuery = mock(TaskReport.class);

    List<TaskCountByCandidateGroupResult> taskCountByCandidateGroupResults = createMockTaskCountByCandidateGroupReport();
    when(mockedReportQuery.taskCountByCandidateGroup()).thenReturn(taskCountByCandidateGroupResults);

    when(processEngine.getTaskService().createTaskReport()).thenReturn(mockedReportQuery);

    return mockedReportQuery;
  }

  @Test
  public void testEmptyReport() {
    given()
    .then()
      .expect()
        .statusCode(Status.OK.getStatusCode())
        .contentType(ContentType.JSON)
      .when()
        .get(CANDIDATE_GROUP_REPORT_URL);

    verify(mockedReportQuery).taskCountByCandidateGroup();
    verifyNoMoreInteractions(mockedReportQuery);
  }

  @Test
  public void testMissingAuthorization() {
    String message = "not authorized";
    when(mockedReportQuery.taskCountByCandidateGroup()).thenThrow(new AuthorizationException(message));

    given()
    .then()
      .expect()
        .statusCode(Status.FORBIDDEN.getStatusCode())
        .contentType(ContentType.JSON)
        .body("type", equalTo(AuthorizationException.class.getSimpleName()))
        .body("message", equalTo(message))
      .when()
        .get(CANDIDATE_GROUP_REPORT_URL);
  }

  @Test
  public void testTaskCountByCandidateGroupReport() {
    Response response = given()
      .then()
      .expect()
      .statusCode(Status.OK.getStatusCode())
      .contentType(ContentType.JSON)
      .when()
      .get(CANDIDATE_GROUP_REPORT_URL);

    String content = response.asString();
    List<String> reports = from(content).getList("");
    Assertions.assertEquals( 1, reports.size(), "There should be one report returned.");
    Assertions.assertNotNull(reports.get(0), "The returned report should not be null.");

    String returnedGroup = from(content).getString("[0].groupName");
    int returnedCount = from(content).getInt("[0].taskCount");

    Assertions.assertEquals(EXAMPLE_GROUP_ID, returnedGroup);
    Assertions.assertEquals(EXAMPLE_TASK_COUNT_BY_CANDIDATE_GROUP, returnedCount);
  }


  @Test
  public void testEmptyCsvReport() {
    given()
      .accept("text/csv")
    .then()
      .expect()
        .statusCode(Status.OK.getStatusCode())
        .contentType("text/csv")
      .when()
        .get(CANDIDATE_GROUP_REPORT_URL);

    verify(mockedReportQuery).taskCountByCandidateGroup();
    verifyNoMoreInteractions(mockedReportQuery);
  }

  @Test
  public void testCsvTaskCountByCandidateGroupReport() {
    Response response = given()
        .accept("text/csv")
      .then()
        .expect()
          .statusCode(Status.OK.getStatusCode())
          .contentType("text/csv")
          .header("Content-Disposition", "attachment; " +
                  "filename=\"task-count-by-candidate-group.csv\"; " +
                  "filename*=UTF-8''task-count-by-candidate-group.csv")
      .when()
        .get(CANDIDATE_GROUP_REPORT_URL);

    String responseContent = response.asString();
    assertTrue(responseContent.contains(TaskReportResultToCsvConverter.CANDIDATE_GROUP_HEADER));
    assertTrue(responseContent.contains(EXAMPLE_GROUP_ID));
    assertTrue(responseContent.contains(String.valueOf(EXAMPLE_TASK_COUNT_BY_CANDIDATE_GROUP)));
  }

  @Test
  public void testApplicationCsvTaskCountByCandidateGroupReport() {
    Response response = given()
        .accept("application/csv")
      .then()
        .expect()
          .statusCode(Status.OK.getStatusCode())
          .contentType("application/csv")
          .header("Content-Disposition", "attachment; " +
                  "filename=\"task-count-by-candidate-group.csv\"; " +
                  "filename*=UTF-8''task-count-by-candidate-group.csv")
        .when()
          .get(CANDIDATE_GROUP_REPORT_URL);

    String responseContent = response.asString();
    assertTrue(responseContent.contains(TaskReportResultToCsvConverter.CANDIDATE_GROUP_HEADER));
    assertTrue(responseContent.contains(EXAMPLE_GROUP_ID));
    assertTrue(responseContent.contains(String.valueOf(EXAMPLE_TASK_COUNT_BY_CANDIDATE_GROUP)));
  }
}
