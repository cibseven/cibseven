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
package org.cibseven.bpm.engine.rest.history;

import java.util.Date;
import java.util.List;
import java.util.Set;

import javax.ws.rs.core.Response.Status;

import org.cibseven.bpm.engine.history.HistoricActivityStatistics;
import org.cibseven.bpm.engine.history.HistoricActivityStatisticsPostQuery;
import org.cibseven.bpm.engine.history.HistoricActivityStatisticsQuery;
import org.cibseven.bpm.engine.impl.HistoricActivityStatisticsPostQueryImpl;
import org.cibseven.bpm.engine.impl.HistoricActivityStatisticsQueryImpl;
import org.cibseven.bpm.engine.rest.AbstractRestServiceTest;
import org.cibseven.bpm.engine.rest.exception.InvalidRequestException;
import org.cibseven.bpm.engine.rest.helper.MockProvider;
import static org.cibseven.bpm.engine.rest.util.DateTimeUtils.DATE_FORMAT_WITH_TIMEZONE;
import org.cibseven.bpm.engine.rest.util.container.TestContainerRule;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.InOrder;
import org.mockito.Mockito;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import static io.restassured.RestAssured.given;
import io.restassured.http.ContentType;
import static io.restassured.path.json.JsonPath.from;
import io.restassured.response.Response;

/**
 *
 * @author Roman Smirnov
 *
 */
public class HistoricActivityStatisticsRestServiceQueryTest extends AbstractRestServiceTest {

  @ClassRule
  public static TestContainerRule rule = new TestContainerRule();

  protected static final String HISTORY_URL = TEST_RESOURCE_ROOT_PATH + "/history";
  protected static final String HISTORIC_ACTIVITY_STATISTICS_URL = HISTORY_URL + "/process-definition/{id}/statistics";

  private HistoricActivityStatisticsQuery historicActivityStatisticsQuery;
  private HistoricActivityStatisticsPostQuery historicActivityStatisticsPostQuery;

  @Before
  public void setUpRuntimeData() {
    setupHistoricActivityStatisticsMock();
  }

  private void setupHistoricActivityStatisticsMock() {
    List<HistoricActivityStatistics> mocks = MockProvider.createMockHistoricActivityStatistics();

    historicActivityStatisticsQuery = mock(HistoricActivityStatisticsQueryImpl.class);
    when(processEngine.getHistoryService().createHistoricActivityStatisticsQuery(eq(MockProvider.EXAMPLE_PROCESS_DEFINITION_ID))).thenReturn(historicActivityStatisticsQuery);
    when(historicActivityStatisticsQuery.unlimitedList()).thenReturn(mocks);

    historicActivityStatisticsPostQuery= mock(HistoricActivityStatisticsPostQueryImpl.class);
    when(processEngine.getHistoryService().createHistoricActivityStatisticsPostQuery(eq(MockProvider.EXAMPLE_PROCESS_DEFINITION_ID))).thenReturn(historicActivityStatisticsPostQuery);
    doReturn(mocks).when(historicActivityStatisticsPostQuery).list();
  }

  @Test
  public void testHistoricActivityStatisticsRetrieval() {
    given().pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
      .body("$.size()", is(2))
      .body("id", hasItems(MockProvider.EXAMPLE_ACTIVITY_ID, MockProvider.ANOTHER_EXAMPLE_ACTIVITY_ID))
    .when().get(HISTORIC_ACTIVITY_STATISTICS_URL);
  }

  @Test
  public void testHistoricActivityStatisticsRetrievalAsPost() {
    given()
      .contentType(POST_JSON_CONTENT_TYPE)
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .body("{}")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
      .body("$.size()", is(2))
      .body("id", hasItems(MockProvider.EXAMPLE_ACTIVITY_ID, MockProvider.ANOTHER_EXAMPLE_ACTIVITY_ID))
    .when().post(HISTORY_URL + "/process-definition/{id}/statistics");

    verify(historicActivityStatisticsPostQuery).list();
    verifyNoMoreInteractions(historicActivityStatisticsPostQuery);
  }

  @Test
  public void testHistoricActivityStatisticsRetrievalAsPostWithTestProcessIdAndEmptyBody() {
    given()
      .contentType(POST_JSON_CONTENT_TYPE)
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .body("{}")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
      .body("$.size()", is(2))
      .body("id", hasItems(MockProvider.EXAMPLE_ACTIVITY_ID, MockProvider.ANOTHER_EXAMPLE_ACTIVITY_ID))
    .when().post(HISTORY_URL + "/process-definition/{id}/statistics");

    verify(processEngine.getHistoryService()).createHistoricActivityStatisticsPostQuery(eq(MockProvider.EXAMPLE_PROCESS_DEFINITION_ID));
    verify(historicActivityStatisticsPostQuery).list();
    verifyNoMoreInteractions(historicActivityStatisticsPostQuery);
  }

  @Test
  public void testHistoricActivityStatisticsRetrievalAsPostWithTestProcessIdReturnsExpectedValues() {
    given()
      .contentType(POST_JSON_CONTENT_TYPE)
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .body("{}")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
    .when().post(HISTORY_URL + "/process-definition/{id}/statistics");
  }

  @Test
  public void testHistoricActivityStatisticsRetrievalAsPostPassesProcessInstanceIds() {
    given()
      .contentType(POST_JSON_CONTENT_TYPE)
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .body("{}")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
    .when().post(HISTORY_URL + "/process-definition/{id}/statistics");

    InOrder inOrder = Mockito.inOrder(historicActivityStatisticsPostQuery);
    inOrder.verify(historicActivityStatisticsPostQuery).list();
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void testHistoricActivityStatisticsRetrievalAsPostPassesDateAndIncludeFlags() {
    final Date testDate = new Date(0);

    given()
      .contentType(POST_JSON_CONTENT_TYPE)
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .body("{"
          + "\"startedAfter\":\"" + DATE_FORMAT_WITH_TIMEZONE.format(testDate) + "\","
          + "\"includeCanceled\":true,"
          + "\"includeFinished\":true,"
          + "\"includeCompleteScope\":true,"
          + "\"includeIncidents\":true"
          + "}")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
    .when().post(HISTORY_URL + "/process-definition/{id}/statistics");

    InOrder inOrder = Mockito.inOrder(historicActivityStatisticsPostQuery);
    inOrder.verify(historicActivityStatisticsPostQuery).includeCanceled();
    inOrder.verify(historicActivityStatisticsPostQuery).includeFinished();
    inOrder.verify(historicActivityStatisticsPostQuery).includeCompleteScope();
    inOrder.verify(historicActivityStatisticsPostQuery).includeIncidents();
    inOrder.verify(historicActivityStatisticsPostQuery).startedAfter(testDate);
    inOrder.verify(historicActivityStatisticsPostQuery).list();
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void testPostProcessDefinitionAndBusinessKeyFilters() {
    given()
      .contentType(POST_JSON_CONTENT_TYPE)
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .body("{"
          + "\"processDefinitionKey\":\"procKey\","
          + "\"processDefinitionKeyIn\":[\"k1\",\"k2\"],"
          + "\"processDefinitionName\":\"procName\","
          + "\"processDefinitionNameLike\":\"proc%\","
          + "\"processDefinitionKeyNotIn\":[\"x1\",\"x2\"],"
          + "\"processInstanceBusinessKey\":\"bk\","
          + "\"processInstanceBusinessKeyIn\":[\"bk1\",\"bk2\"],"
          + "\"processInstanceBusinessKeyLike\":\"order%\""
          + "}")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
    .when().post(HISTORY_URL + "/process-definition/{id}/statistics");

    
    verify(historicActivityStatisticsPostQuery).processDefinitionKey("procKey");
    verify(historicActivityStatisticsPostQuery).processDefinitionKeyIn(new String[] {"k1", "k2"});
    verify(historicActivityStatisticsPostQuery).processDefinitionName("procName");
    verify(historicActivityStatisticsPostQuery).processDefinitionNameLike("proc%");
    verify(historicActivityStatisticsPostQuery).processDefinitionKeyNotIn(java.util.Arrays.asList("x1", "x2"));
    verify(historicActivityStatisticsPostQuery).businessKey("bk");
    verify(historicActivityStatisticsPostQuery).businessKeyIn(new String[] {"bk1", "bk2"});
    verify(historicActivityStatisticsPostQuery).businessKeyLike("order%");
    verify(historicActivityStatisticsPostQuery).list();
    verifyNoMoreInteractions(historicActivityStatisticsPostQuery);
  }

  @Test
  public void testPostProcessInstanceAndHierarchyFilters() {
    given()
      .contentType(POST_JSON_CONTENT_TYPE)
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .body("{"
          + "\"processInstanceId\":\"p1\","
          + "\"processInstanceIds\":[\"p2\",\"p3\"],"
          + "\"processInstanceIdNotIn\":[\"p4\",\"p5\"],"
          + "\"rootProcessInstanceId\":\"rp1\","
          + "\"rootProcessInstances\":true,"
          + "\"startedBy\":\"demo\","
          + "\"superProcessInstanceId\":\"sp1\","
          + "\"subProcessInstanceId\":\"subp1\","
          + "\"superCaseInstanceId\":\"sc1\","
          + "\"subCaseInstanceId\":\"subc1\","
          + "\"caseInstanceId\":\"c1\""
          + "}")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
    .when().post(HISTORY_URL + "/process-definition/{id}/statistics");

    verify(historicActivityStatisticsPostQuery).processInstanceId("p1");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Set<String>> processInstanceIdsCaptor = ArgumentCaptor.forClass(Set.class);
    verify(historicActivityStatisticsPostQuery).processInstanceIds(processInstanceIdsCaptor.capture());
    Assert.assertTrue(processInstanceIdsCaptor.getValue().contains("p2"));
    Assert.assertTrue(processInstanceIdsCaptor.getValue().contains("p3"));
    Assert.assertEquals(2, processInstanceIdsCaptor.getValue().size());

    verify(historicActivityStatisticsPostQuery).processInstanceIdNotIn(new String[] {"p4", "p5"});
    verify(historicActivityStatisticsPostQuery).rootProcessInstanceId("rp1");
    verify(historicActivityStatisticsPostQuery).rootProcessInstances();
    verify(historicActivityStatisticsPostQuery).startedBy("demo");
    verify(historicActivityStatisticsPostQuery).superProcessInstanceId("sp1");
    verify(historicActivityStatisticsPostQuery).subProcessInstanceId("subp1");
    verify(historicActivityStatisticsPostQuery).superCaseInstanceId("sc1");
    verify(historicActivityStatisticsPostQuery).subCaseInstanceId("subc1");
    verify(historicActivityStatisticsPostQuery).caseInstanceId("c1");
    verify(historicActivityStatisticsPostQuery).list();
    verifyNoMoreInteractions(historicActivityStatisticsPostQuery);
  }

  @Test
  public void testPostIncidentAndStateFilters() {
    given()
      .contentType(POST_JSON_CONTENT_TYPE)
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .body("{"
          + "\"finished\":true,"
          + "\"unfinished\":true,"
          + "\"withJobsRetrying\":true,"
          + "\"withIncidents\":true,"
          + "\"withRootIncidents\":true,"
          + "\"incidentIdIn\":[\"inc1\",\"inc2\"],"
          + "\"incidentStatus\":\"open\","
          + "\"incidentType\":\"failedJob\","
          + "\"incidentMessage\":\"message\","
          + "\"incidentMessageLike\":\"msg%\""
          + "}")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
    .when().post(HISTORY_URL + "/process-definition/{id}/statistics");

    
    verify(historicActivityStatisticsPostQuery).finished();
    verify(historicActivityStatisticsPostQuery).unfinished();
    verify(historicActivityStatisticsPostQuery).withJobsRetrying();
    verify(historicActivityStatisticsPostQuery).withIncidents();
    verify(historicActivityStatisticsPostQuery).withRootIncidents();
    verify(historicActivityStatisticsPostQuery).incidentIdIn(new String[] {"inc1", "inc2"});
    verify(historicActivityStatisticsPostQuery).incidentStatus("open");
    verify(historicActivityStatisticsPostQuery).incidentType("failedJob");
    verify(historicActivityStatisticsPostQuery).incidentMessage("message");
    verify(historicActivityStatisticsPostQuery).incidentMessageLike("msg%");
    verify(historicActivityStatisticsPostQuery).list();
    verifyNoMoreInteractions(historicActivityStatisticsPostQuery);
  }

  @Test
  public void testPostTenantExecutedActivityAndStateFilters() {
    final Date executedAfter = new Date(1000L);
    final Date executedBefore = new Date(2000L);
    final Date executedJobAfter = new Date(3000L);
    final Date executedJobBefore = new Date(4000L);

    given()
      .contentType(POST_JSON_CONTENT_TYPE)
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .body("{"
          + "\"tenantIdIn\":[\"t1\",\"t2\"],"
          + "\"withoutTenantId\":true,"
          + "\"executedActivityAfter\":\"" + DATE_FORMAT_WITH_TIMEZONE.format(executedAfter) + "\","
          + "\"executedActivityBefore\":\"" + DATE_FORMAT_WITH_TIMEZONE.format(executedBefore) + "\","
          + "\"executedActivityIdIn\":[\"ea1\",\"ea2\"],"
          + "\"activeActivityIdIn\":[\"aa1\",\"aa2\"],"
          + "\"activityIdIn\":[\"a1\",\"a2\"],"
          + "\"executedJobAfter\":\"" + DATE_FORMAT_WITH_TIMEZONE.format(executedJobAfter) + "\","
          + "\"executedJobBefore\":\"" + DATE_FORMAT_WITH_TIMEZONE.format(executedJobBefore) + "\","
          + "\"active\":true,"
          + "\"suspended\":true,"
          + "\"completed\":true,"
          + "\"externallyTerminated\":true,"
          + "\"internallyTerminated\":true"
          + "}")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
    .when().post(HISTORY_URL + "/process-definition/{id}/statistics");

    verify(historicActivityStatisticsPostQuery).tenantIdIn(new String[] {"t1", "t2"});
    verify(historicActivityStatisticsPostQuery).withoutTenantId();
    verify(historicActivityStatisticsPostQuery).executedActivityAfter(executedAfter);
    verify(historicActivityStatisticsPostQuery).executedActivityBefore(executedBefore);
    verify(historicActivityStatisticsPostQuery).executedActivityIdIn(new String[] {"ea1", "ea2"});
    verify(historicActivityStatisticsPostQuery).activeActivityIdIn(new String[] {"aa1", "aa2"});
    verify(historicActivityStatisticsPostQuery).activityIdIn(new String[] {"a1", "a2"});
    verify(historicActivityStatisticsPostQuery).executedJobAfter(executedJobAfter);
    verify(historicActivityStatisticsPostQuery).executedJobBefore(executedJobBefore);
    verify(historicActivityStatisticsPostQuery).active();
    verify(historicActivityStatisticsPostQuery).suspended();
    verify(historicActivityStatisticsPostQuery).completed();
    verify(historicActivityStatisticsPostQuery).externallyTerminated();
    verify(historicActivityStatisticsPostQuery).internallyTerminated();
    verify(historicActivityStatisticsPostQuery).list();
    verifyNoMoreInteractions(historicActivityStatisticsPostQuery);
  }

  @Test
  public void testPostVariableComparatorsGreaterThanOrEqualLessThanAndLessThanOrEqual() {
    given()
      .contentType(POST_JSON_CONTENT_TYPE)
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .body("{\"variables\":["
          + "{\"name\":\"n1\",\"operator\":\"gteq\",\"value\":10},"
          + "{\"name\":\"n2\",\"operator\":\"lt\",\"value\":20},"
          + "{\"name\":\"n3\",\"operator\":\"lteq\",\"value\":30}"
          + "]}")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
    .when().post(HISTORY_URL + "/process-definition/{id}/statistics");

    verify(historicActivityStatisticsPostQuery).variableValueGreaterThanOrEqual(eq("n1"), any());
    verify(historicActivityStatisticsPostQuery).variableValueLessThan(eq("n2"), any());
    verify(historicActivityStatisticsPostQuery).variableValueLessThanOrEqual(eq("n3"), any());
    verify(historicActivityStatisticsPostQuery).list();
    verifyNoMoreInteractions(historicActivityStatisticsPostQuery);
  }

  @Test
  public void testAdditionalCanceledOption() {
    given()
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .queryParam("canceled", "true")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
    .when().get(HISTORIC_ACTIVITY_STATISTICS_URL);

    InOrder inOrder = Mockito.inOrder(historicActivityStatisticsQuery);
    inOrder.verify(historicActivityStatisticsQuery).includeCanceled();
    inOrder.verify(historicActivityStatisticsQuery).unlimitedList();
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void testAdditionalFinishedOption() {
    given()
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .queryParam("finished", "true")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
    .when().get(HISTORIC_ACTIVITY_STATISTICS_URL);

    InOrder inOrder = Mockito.inOrder(historicActivityStatisticsQuery);
    inOrder.verify(historicActivityStatisticsQuery).includeFinished();
    inOrder.verify(historicActivityStatisticsQuery).unlimitedList();
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void testAdditionalCompleteScopeOption() {
    given()
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
    . queryParam("completeScope", "true")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
    .when().get(HISTORIC_ACTIVITY_STATISTICS_URL);

    InOrder inOrder = Mockito.inOrder(historicActivityStatisticsQuery);
    inOrder.verify(historicActivityStatisticsQuery).includeCompleteScope();
    inOrder.verify(historicActivityStatisticsQuery).unlimitedList();
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void testAdditionalStartedAfterOption() {
    final Date testDate = new Date(0);
    given()
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .queryParam("startedAfter", DATE_FORMAT_WITH_TIMEZONE.format(testDate))
      .then().expect()
      .statusCode(Status.OK.getStatusCode())
      .when().get(HISTORIC_ACTIVITY_STATISTICS_URL);

    InOrder inOrder = Mockito.inOrder(historicActivityStatisticsQuery);
    inOrder.verify(historicActivityStatisticsQuery).startedAfter(testDate);
    inOrder.verify(historicActivityStatisticsQuery).unlimitedList();
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void testAdditionalStartedBeforeOption() {
    final Date testDate = new Date(0);
    given()
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .queryParam("startedBefore", DATE_FORMAT_WITH_TIMEZONE.format(testDate))
      .then().expect()
      .statusCode(Status.OK.getStatusCode())
      .when().get(HISTORIC_ACTIVITY_STATISTICS_URL);

    InOrder inOrder = Mockito.inOrder(historicActivityStatisticsQuery);
    inOrder.verify(historicActivityStatisticsQuery).startedBefore(testDate);
    inOrder.verify(historicActivityStatisticsQuery).unlimitedList();
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void testAdditionalFinishedAfterOption() {
    final Date testDate = new Date(0);
    given()
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .queryParam("finishedAfter", DATE_FORMAT_WITH_TIMEZONE.format(testDate))
      .then().expect()
      .statusCode(Status.OK.getStatusCode())
      .when().get(HISTORIC_ACTIVITY_STATISTICS_URL);

    InOrder inOrder = Mockito.inOrder(historicActivityStatisticsQuery);
    inOrder.verify(historicActivityStatisticsQuery).finishedAfter(testDate);
    inOrder.verify(historicActivityStatisticsQuery).unlimitedList();
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void testAdditionalFinishedBeforeOption() {
    final Date testDate = new Date(0);
    given()
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .queryParam("finishedBefore", DATE_FORMAT_WITH_TIMEZONE.format(testDate))
      .then().expect()
      .statusCode(Status.OK.getStatusCode())
      .when().get(HISTORIC_ACTIVITY_STATISTICS_URL);

    InOrder inOrder = Mockito.inOrder(historicActivityStatisticsQuery);
    inOrder.verify(historicActivityStatisticsQuery).finishedBefore(testDate);
    inOrder.verify(historicActivityStatisticsQuery).unlimitedList();
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void testAdditionalCompleteScopeAndCanceledOption() {
    given()
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .queryParam("completeScope", "true")
      .queryParam("canceled", "true")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
    .when().get(HISTORIC_ACTIVITY_STATISTICS_URL);

    verify(historicActivityStatisticsQuery).includeCompleteScope();
    verify(historicActivityStatisticsQuery).includeCanceled();
    verify(historicActivityStatisticsQuery).unlimitedList();
    verifyNoMoreInteractions(historicActivityStatisticsQuery);
  }

  @Test
  public void testAdditionalCompleteScopeAndFinishedOption() {
    given()
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .queryParam("completeScope", "true")
      .queryParam("finished", "true")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
    .when().get(HISTORIC_ACTIVITY_STATISTICS_URL);

    verify(historicActivityStatisticsQuery).includeCompleteScope();
    verify(historicActivityStatisticsQuery).includeFinished();
    verify(historicActivityStatisticsQuery).unlimitedList();
    verifyNoMoreInteractions(historicActivityStatisticsQuery);
  }

  @Test
  public void testAdditionalCanceledAndFinishedOption() {
    given()
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .queryParam("canceled", "true")
      .queryParam("finished", "true")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
    .when().get(HISTORIC_ACTIVITY_STATISTICS_URL);

    verify(historicActivityStatisticsQuery).includeCanceled();
    verify(historicActivityStatisticsQuery).includeFinished();
    verify(historicActivityStatisticsQuery).unlimitedList();
    verifyNoMoreInteractions(historicActivityStatisticsQuery);
  }

  @Test
  public void testAdditionalCompleteScopeAndFinishedAndCanceledOption() {
    given()
    .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .queryParam("completeScope", "true")
      .queryParam("finished", "true")
      .queryParam("canceled", "true")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
    .when().get(HISTORIC_ACTIVITY_STATISTICS_URL);

    verify(historicActivityStatisticsQuery).includeCompleteScope();
    verify(historicActivityStatisticsQuery).includeFinished();
    verify(historicActivityStatisticsQuery).includeCanceled();
    verify(historicActivityStatisticsQuery).unlimitedList();
    verifyNoMoreInteractions(historicActivityStatisticsQuery);
  }

  @Test
  public void testAdditionalCompleteScopeAndFinishedAndCanceledOptionFalse() {
    given()
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .queryParam("completeScope", "false")
      .queryParam("finished", "false")
      .queryParam("canceled", "false")
      .then().expect()
      .statusCode(Status.OK.getStatusCode())
      .when().get(HISTORIC_ACTIVITY_STATISTICS_URL);

    verify(historicActivityStatisticsQuery).unlimitedList();
    verifyNoMoreInteractions(historicActivityStatisticsQuery);
  }


  @Test
  public void testProcessInstanceIdInFilter() {
    given()
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .queryParam("processInstanceIdIn", "foo,bar")
      .then().expect()
      .statusCode(Status.OK.getStatusCode())
      .when().get(HISTORIC_ACTIVITY_STATISTICS_URL);

    InOrder inOrder = Mockito.inOrder(historicActivityStatisticsQuery);
    inOrder.verify(historicActivityStatisticsQuery).processInstanceIdIn(new String[] {"foo", "bar"});
    inOrder.verify(historicActivityStatisticsQuery).unlimitedList();
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void testIncidentsFilter() {
    given()
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .queryParam("incidents", "true")
      .then().expect()
      .statusCode(Status.OK.getStatusCode())
      .when().get(HISTORIC_ACTIVITY_STATISTICS_URL);

    InOrder inOrder = Mockito.inOrder(historicActivityStatisticsQuery);
    inOrder.verify(historicActivityStatisticsQuery).includeIncidents();
    inOrder.verify(historicActivityStatisticsQuery).unlimitedList();
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void testSimpleTaskQuery() {
    Response response = given()
          .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
         .then().expect()
           .statusCode(Status.OK.getStatusCode())
      .when().get(HISTORIC_ACTIVITY_STATISTICS_URL);

    String content = response.asString();
    List<String> result = from(content).getList("");
    Assert.assertEquals(2, result.size());

    Assert.assertNotNull(result.get(0));
    Assert.assertNotNull(result.get(1));

    String id = from(content).getString("[0].id");
    long instances = from(content).getLong("[0].instances");
    long canceled = from(content).getLong("[0].canceled");
    long finished = from(content).getLong("[0].finished");
    long completeScope = from(content).getLong("[0].completeScope");
    long openIncidents = from(content).getLong("[0].openIncidents");
    long resolvedIncidents = from(content).getLong("[0].resolvedIncidents");
    long deletedIncidents = from(content).getLong("[0].deletedIncidents");

    Assert.assertEquals(MockProvider.EXAMPLE_ACTIVITY_ID, id);
    Assert.assertEquals(MockProvider.EXAMPLE_INSTANCES_LONG, instances);
    Assert.assertEquals(MockProvider.EXAMPLE_CANCELED_LONG, canceled);
    Assert.assertEquals(MockProvider.EXAMPLE_FINISHED_LONG, finished);
    Assert.assertEquals(MockProvider.EXAMPLE_COMPLETE_SCOPE_LONG, completeScope);
    Assert.assertEquals(MockProvider.EXAMPLE_OPEN_INCIDENTS_LONG, openIncidents);
    Assert.assertEquals(MockProvider.EXAMPLE_RESOLVED_INCIDENTS_LONG, resolvedIncidents);
    Assert.assertEquals(MockProvider.EXAMPLE_DELETED_INCIDENTS_LONG, deletedIncidents);

    id = from(content).getString("[1].id");
    instances = from(content).getLong("[1].instances");
    canceled = from(content).getLong("[1].canceled");
    finished = from(content).getLong("[1].finished");
    completeScope = from(content).getLong("[1].completeScope");
    openIncidents = from(content).getLong("[1].openIncidents");
    resolvedIncidents = from(content).getLong("[1].resolvedIncidents");
    deletedIncidents = from(content).getLong("[1].deletedIncidents");

    Assert.assertEquals(MockProvider.ANOTHER_EXAMPLE_ACTIVITY_ID, id);
    Assert.assertEquals(MockProvider.ANOTHER_EXAMPLE_INSTANCES_LONG, instances);
    Assert.assertEquals(MockProvider.ANOTHER_EXAMPLE_CANCELED_LONG, canceled);
    Assert.assertEquals(MockProvider.ANOTHER_EXAMPLE_FINISHED_LONG, finished);
    Assert.assertEquals(MockProvider.ANOTHER_EXAMPLE_COMPLETE_SCOPE_LONG, completeScope);
    Assert.assertEquals(MockProvider.ANOTHER_EXAMPLE_OPEN_INCIDENTS_LONG, openIncidents);
    Assert.assertEquals(MockProvider.ANOTHER_EXAMPLE_RESOLVED_INCIDENTS_LONG, resolvedIncidents);
    Assert.assertEquals(MockProvider.ANOTHER_EXAMPLE_DELETED_INCIDENTS_LONG, deletedIncidents);

  }

  @Test
  public void testSortByParameterOnly() {
    given()
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .queryParam("sortBy", "activityId")
    .then().expect().statusCode(Status.BAD_REQUEST.getStatusCode()).contentType(ContentType.JSON)
      .body("type", equalTo(InvalidRequestException.class.getSimpleName()))
      .body("message", equalTo("Only a single sorting parameter specified. sortBy and sortOrder required"))
    .when().get(HISTORIC_ACTIVITY_STATISTICS_URL);
  }

  @Test
  public void testSortOrderParameterOnly() {
    given()
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .queryParam("sortOrder", "asc")
    .then().expect().statusCode(Status.BAD_REQUEST.getStatusCode()).contentType(ContentType.JSON)
      .body("type", equalTo(InvalidRequestException.class.getSimpleName()))
      .body("message", equalTo("Only a single sorting parameter specified. sortBy and sortOrder required"))
    .when().get(HISTORIC_ACTIVITY_STATISTICS_URL);
  }

  @Test
  public void testInvalidSortOrder() {
    given()
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .queryParam("sortOrder", "invalid")
      .queryParam("sortBy", "activityId")
    .then().expect().statusCode(Status.BAD_REQUEST.getStatusCode()).contentType(ContentType.JSON)
      .body("type", equalTo(InvalidRequestException.class.getSimpleName()))
      .body("message", equalTo("Cannot set query parameter 'sortOrder' to value 'invalid'"))
      .when().get(HISTORIC_ACTIVITY_STATISTICS_URL);
  }

  @Test
  public void testInvalidSortByParameterOnly() {
    given()
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .queryParam("sortOrder", "asc")
      .queryParam("sortBy", "invalid")
    .then().expect().statusCode(Status.BAD_REQUEST.getStatusCode()).contentType(ContentType.JSON)
      .body("type", equalTo(InvalidRequestException.class.getSimpleName()))
      .body("message", equalTo("Cannot set query parameter 'sortBy' to value 'invalid'"))
      .when().get(HISTORIC_ACTIVITY_STATISTICS_URL);
  }

  @Test
  public void testValidSortingParameters() {
    given()
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .queryParam("sortOrder", "asc")
      .queryParam("sortBy", "activityId")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
    .when()
      .get(HISTORIC_ACTIVITY_STATISTICS_URL);

    InOrder inOrder = Mockito.inOrder(historicActivityStatisticsQuery);
    inOrder.verify(historicActivityStatisticsQuery).orderByActivityId();
    inOrder.verify(historicActivityStatisticsQuery).asc();
    inOrder.verify(historicActivityStatisticsQuery).unlimitedList();
    inOrder.verifyNoMoreInteractions();

    given()
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .queryParam("sortOrder", "desc")
      .queryParam("sortBy", "activityId")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
    .when()
      .get(HISTORIC_ACTIVITY_STATISTICS_URL);

    inOrder = Mockito.inOrder(historicActivityStatisticsQuery);
    inOrder.verify(historicActivityStatisticsQuery).orderByActivityId();
    inOrder.verify(historicActivityStatisticsQuery).desc();
    inOrder.verify(historicActivityStatisticsQuery).unlimitedList();
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void testPostVariableValueEquals() {
    given()
      .contentType(POST_JSON_CONTENT_TYPE)
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .body("{\"variables\":[{\"name\":\"myVar\",\"operator\":\"eq\",\"value\":\"hello\"}]}")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
    .when().post(HISTORY_URL + "/process-definition/{id}/statistics");

    verify(historicActivityStatisticsPostQuery).variableValueEquals("myVar", "hello");
    verify(historicActivityStatisticsPostQuery).list();
    verifyNoMoreInteractions(historicActivityStatisticsPostQuery);
  }

  @Test
  public void testPostVariableValueNotEquals() {
    final Date testDate = new Date(1356998400000L); // 2013-01-01T00:00:00.000+0200
    given()
      .contentType(POST_JSON_CONTENT_TYPE)
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .body("{\"variables\":[{\"name\":\"status\",\"operator\":\"neq\",\"value\":\"active\"}],\"finishedAfter\":\"" + DATE_FORMAT_WITH_TIMEZONE.format(testDate) + "\"}")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
    .when().post(HISTORY_URL + "/process-definition/{id}/statistics");

    verify(historicActivityStatisticsPostQuery).finishedAfter(any(Date.class));
    verify(historicActivityStatisticsPostQuery).variableValueNotEquals("status", "active");
    verify(historicActivityStatisticsPostQuery).list();
    verifyNoMoreInteractions(historicActivityStatisticsPostQuery);
  }

  @Test
  public void testPostOrQueryVariables() {
    given()
      .contentType(POST_JSON_CONTENT_TYPE)
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .body("{\"orQueries\":[{\"variables\":[{\"name\":\"myVar\",\"operator\":\"eq\",\"value\":\"hello\"}]}]}")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
    .when().post(HISTORY_URL + "/process-definition/{id}/statistics");

    verify((HistoricActivityStatisticsPostQueryImpl) historicActivityStatisticsPostQuery).addOrQuery(any(HistoricActivityStatisticsPostQueryImpl.class));
    verify(historicActivityStatisticsPostQuery).list();
    verifyNoMoreInteractions(historicActivityStatisticsPostQuery);
  }

  @Test
  public void testPostVariableValueGreaterThan() {
    given()
      .contentType(POST_JSON_CONTENT_TYPE)
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .body("{\"variables\":[{\"name\":\"count\",\"operator\":\"gt\",\"value\":5}]}")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
    .when().post(HISTORY_URL + "/process-definition/{id}/statistics");

    verify(historicActivityStatisticsPostQuery).variableValueGreaterThan(eq("count"), org.mockito.ArgumentMatchers.any());
    verify(historicActivityStatisticsPostQuery).list();
    verifyNoMoreInteractions(historicActivityStatisticsPostQuery);
  }

  @Test
  public void testPostVariableValueLike() {
    given()
      .contentType(POST_JSON_CONTENT_TYPE)
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .body("{\"variables\":[{\"name\":\"name\",\"operator\":\"like\",\"value\":\"John%\"}]}")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
    .when().post(HISTORY_URL + "/process-definition/{id}/statistics");

    verify(historicActivityStatisticsPostQuery).variableValueLike("name", "John%");
    verify(historicActivityStatisticsPostQuery).list();
    verifyNoMoreInteractions(historicActivityStatisticsPostQuery);
  }

  @Test
  public void testPostMultipleVariables() {
    given()
      .contentType(POST_JSON_CONTENT_TYPE)
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .body("{\"variables\":["
          + "{\"name\":\"varA\",\"operator\":\"eq\",\"value\":\"foo\"},"
          + "{\"name\":\"varB\",\"operator\":\"neq\",\"value\":\"bar\"}"
          + "]}")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
    .when().post(HISTORY_URL + "/process-definition/{id}/statistics");

    verify(historicActivityStatisticsPostQuery).variableValueEquals("varA", "foo");
    verify(historicActivityStatisticsPostQuery).variableValueNotEquals("varB", "bar");
    verify(historicActivityStatisticsPostQuery).list();
    verifyNoMoreInteractions(historicActivityStatisticsPostQuery);
  }

  @Test
  public void testPostVariableNamesIgnoreCase() {
    given()
      .contentType(POST_JSON_CONTENT_TYPE)
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .body("{\"variableNamesIgnoreCase\":true,"
          + "\"variables\":[{\"name\":\"myVar\",\"operator\":\"eq\",\"value\":\"val\"}]}")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
    .when().post(HISTORY_URL + "/process-definition/{id}/statistics");

    verify(historicActivityStatisticsPostQuery).matchVariableNamesIgnoreCase();
    verify(historicActivityStatisticsPostQuery).variableValueEquals("myVar", "val");
    verify(historicActivityStatisticsPostQuery).list();
    verifyNoMoreInteractions(historicActivityStatisticsPostQuery);
  }

  @Test
  public void testPostVariableValuesIgnoreCase() {
    given()
      .contentType(POST_JSON_CONTENT_TYPE)
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .body("{\"variableValuesIgnoreCase\":true,"
          + "\"variables\":[{\"name\":\"myVar\",\"operator\":\"eq\",\"value\":\"val\"}]}")
    .then().expect()
      .statusCode(Status.OK.getStatusCode())
    .when().post(HISTORY_URL + "/process-definition/{id}/statistics");

    verify(historicActivityStatisticsPostQuery).matchVariableValuesIgnoreCase();
    verify(historicActivityStatisticsPostQuery).variableValueEquals("myVar", "val");
    verify(historicActivityStatisticsPostQuery).list();
    verifyNoMoreInteractions(historicActivityStatisticsPostQuery);
  }

  @Test
  public void testPostInvalidVariableOperator() {
    given()
      .contentType(POST_JSON_CONTENT_TYPE)
      .pathParam("id", MockProvider.EXAMPLE_PROCESS_DEFINITION_ID)
      .body("{\"variables\":[{\"name\":\"myVar\",\"operator\":\"invalid\",\"value\":\"val\"}]}")
    .then().expect()
      .statusCode(Status.BAD_REQUEST.getStatusCode())
    .when().post(HISTORY_URL + "/process-definition/{id}/statistics");
  }

}
