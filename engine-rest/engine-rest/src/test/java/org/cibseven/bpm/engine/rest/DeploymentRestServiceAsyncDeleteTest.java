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

import static io.restassured.RestAssured.given;
import static org.cibseven.bpm.engine.rest.helper.MockProvider.EXAMPLE_BATCH_ID;
import static org.cibseven.bpm.engine.rest.helper.MockProvider.createMockBatch;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.core.Response.Status;

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.batch.Batch;
import org.cibseven.bpm.engine.repository.DeploymentQuery;
import org.cibseven.bpm.engine.rest.util.container.TestContainerRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import io.restassured.http.ContentType;

/**
 * Tests the REST layer of {@code POST /deployment/delete} (CIB7-1597) against a mocked
 * {@link RepositoryService}: body mapping, parameter pass-through, and status codes.
 */
public class DeploymentRestServiceAsyncDeleteTest extends AbstractRestServiceTest {

  @ClassRule
  public static TestContainerRule rule = new TestContainerRule();

  protected static final String RESOURCE_URL = TEST_RESOURCE_ROOT_PATH + "/deployment";
  protected static final String DELETE_ASYNC_URL = RESOURCE_URL + "/delete";

  protected RepositoryService mockRepositoryService;

  // must not be named setUp() to keep AbstractRestServiceTest#setUp() (engine mock init) running
  @Before
  public void setUpMocks() {
    mockRepositoryService = mock(RepositoryService.class);
    when(processEngine.getRepositoryService()).thenReturn(mockRepositoryService);

    DeploymentQuery deploymentQuery = mock(DeploymentQuery.class);
    when(mockRepositoryService.createDeploymentQuery()).thenReturn(deploymentQuery);

    Batch mockBatch = createMockBatch();
    when(mockRepositoryService.deleteDeploymentsAsync(any(), any(), anyBoolean(), anyBoolean(), anyBoolean()))
        .thenReturn(mockBatch);
  }

  @Test
  public void shouldDeleteAsyncWithIds() {
    List<String> ids = Arrays.asList("deploymentId1", "deploymentId2");

    Map<String, Object> body = new HashMap<>();
    body.put("deploymentIds", ids);

    given()
        .contentType(ContentType.JSON).body(body)
        .then().expect()
        .statusCode(Status.OK.getStatusCode())
        .body("id", is(EXAMPLE_BATCH_ID))
        .when().post(DELETE_ASYNC_URL);

    verify(mockRepositoryService, times(1)).deleteDeploymentsAsync(ids, null, false, false, false);
  }

  @Test
  public void shouldDeleteAsyncWithCascade() {
    List<String> ids = Collections.singletonList("deploymentId1");

    Map<String, Object> body = new HashMap<>();
    body.put("deploymentIds", ids);
    body.put("cascade", true);

    given()
        .contentType(ContentType.JSON).body(body)
        .then().expect()
        .statusCode(Status.OK.getStatusCode())
        .when().post(DELETE_ASYNC_URL);

    verify(mockRepositoryService, times(1)).deleteDeploymentsAsync(ids, null, true, false, false);
  }

  @Test
  public void shouldDeleteAsyncWithSkipCustomListeners() {
    List<String> ids = Collections.singletonList("deploymentId1");

    Map<String, Object> body = new HashMap<>();
    body.put("deploymentIds", ids);
    body.put("skipCustomListeners", true);

    given()
        .contentType(ContentType.JSON).body(body)
        .then().expect()
        .statusCode(Status.OK.getStatusCode())
        .when().post(DELETE_ASYNC_URL);

    verify(mockRepositoryService, times(1)).deleteDeploymentsAsync(ids, null, false, true, false);
  }

  @Test
  public void shouldDeleteAsyncWithSkipIoMappings() {
    List<String> ids = Collections.singletonList("deploymentId1");

    Map<String, Object> body = new HashMap<>();
    body.put("deploymentIds", ids);
    body.put("skipIoMappings", true);

    given()
        .contentType(ContentType.JSON).body(body)
        .then().expect()
        .statusCode(Status.OK.getStatusCode())
        .when().post(DELETE_ASYNC_URL);

    verify(mockRepositoryService, times(1)).deleteDeploymentsAsync(ids, null, false, false, true);
  }

  @Test
  public void shouldDeleteAsyncWithQuery() {
    Map<String, Object> body = new HashMap<>();
    body.put("deploymentQuery", Collections.emptyMap());

    given()
        .contentType(ContentType.JSON).body(body)
        .then().expect()
        .statusCode(Status.OK.getStatusCode())
        .when().post(DELETE_ASYNC_URL);

    // the query dto is resolved to a (non-null) DeploymentQuery, ids are absent
    verify(mockRepositoryService, times(1))
        .deleteDeploymentsAsync(eq(null), any(DeploymentQuery.class), eq(false), eq(false), eq(false));
  }

  @Test
  public void shouldReturnBadRequestOnEmptySelection() {
    doThrow(new BadUserRequestException("deploymentIds is empty"))
        .when(mockRepositoryService).deleteDeploymentsAsync(any(), any(), anyBoolean(), anyBoolean(), anyBoolean());

    Map<String, Object> body = new HashMap<>();
    body.put("deploymentIds", Collections.emptyList());

    given()
        .contentType(ContentType.JSON).body(body)
        .then().expect()
        .statusCode(Status.BAD_REQUEST.getStatusCode())
        .when().post(DELETE_ASYNC_URL);
  }
}
