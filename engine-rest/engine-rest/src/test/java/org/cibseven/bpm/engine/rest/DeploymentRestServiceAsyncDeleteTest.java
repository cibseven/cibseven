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

// TESTDOC: -------------------------------------------------------------------------------------
// TESTDOC: REST-Schicht-Test fuer den Endpoint  POST /deployment/delete  (Ticket CIB7-1597).
// TESTDOC: Im Gegensatz zum Engine-Test laeuft hier KEINE echte Engine: der RepositoryService wird
// TESTDOC: gemockt. Getestet wird ausschliesslich die REST-Schicht, also:
// TESTDOC:   - dass der JSON-Request-Body korrekt auf DeleteDeploymentsDto gemappt wird,
// TESTDOC:   - dass die Werte korrekt an RepositoryService.deleteDeploymentsAsync(...) durchgereicht werden,
// TESTDOC:   - dass HTTP-Statuscodes stimmen (200 / 400).
// TESTDOC: -------------------------------------------------------------------------------------
public class DeploymentRestServiceAsyncDeleteTest extends AbstractRestServiceTest {

  // TESTDOC: Startet den eingebetteten REST-Container (JAX-RS) einmalig fuer alle Tests dieser Klasse.
  @ClassRule
  public static TestContainerRule rule = new TestContainerRule();

  // TESTDOC: Basis-URL des Deployment-Resources und die konkrete URL des Batch-Delete-Endpoints.
  protected static final String RESOURCE_URL = TEST_RESOURCE_ROOT_PATH + "/deployment";
  protected static final String DELETE_ASYNC_URL = RESOURCE_URL + "/delete";

  protected RepositoryService mockRepositoryService;

  // TESTDOC: setUpMocks(): laeuft vor jedem Test. WICHTIG - der Name darf NICHT setUp() sein, sonst
  // TESTDOC: wuerde er die setUp()-Methode von AbstractRestServiceTest ueberschreiben, die das
  // TESTDOC: statische processEngine-Mock initialisiert (sonst NullPointerException).
  // TESTDOC: Hier wird das RepositoryService-Mock erzeugt, in die Engine eingehaengt und so gestubbt,
  // TESTDOC: dass createDeploymentQuery() und deleteDeploymentsAsync(...) brauchbare Rueckgaben liefern.
  // TESTDOC: Hinweis: createMockBatch() / mock() werden bewusst VORHER in lokale Variablen gezogen -
  // TESTDOC: hierin wird selbst Mockito-Stubbing ausgefuehrt; direkt in .thenReturn(...) aufgerufen
  // TESTDOC: fuehrte es zu einem verschachtelten Stubbing ("UnfinishedStubbing").
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

  // TESTDOC: TEST: Standardfall mit expliziter Id-Liste. Erwartung: HTTP 200, Antwort enthaelt die
  // TESTDOC: Batch-Id des Mocks, und der Service wird mit genau diesen Ids und allen Flags=false aufgerufen.
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

  // TESTDOC: TEST: Flag "cascade" im Body wird korrekt als 3. Parameter (cascade=true) durchgereicht.
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

  // TESTDOC: TEST: Flag "skipCustomListeners" wird korrekt als 4. Parameter (=true) durchgereicht.
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

  // TESTDOC: TEST: Flag "skipIoMappings" wird korrekt als 5. Parameter (=true) durchgereicht.
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

  // TESTDOC: TEST: Auswahl per Query statt per Ids. Der Body enthaelt ein (leeres) deploymentQuery-Objekt.
  // TESTDOC: Die REST-Schicht wandelt dieses DTO in eine DeploymentQuery um; erwartet wird daher ein
  // TESTDOC: Aufruf mit Ids=null und einer NICHT-null DeploymentQuery.
  @Test
  public void shouldDeleteAsyncWithQuery() {
    Map<String, Object> body = new HashMap<>();
    body.put("deploymentQuery", Collections.emptyMap());

    given()
        .contentType(ContentType.JSON).body(body)
        .then().expect()
        .statusCode(Status.OK.getStatusCode())
        .when().post(DELETE_ASYNC_URL);

    // query is resolved to a (non-null) DeploymentQuery, ids are absent
    verify(mockRepositoryService, times(1))
        .deleteDeploymentsAsync(eq(null), any(DeploymentQuery.class), eq(false), eq(false), eq(false));
  }

  // TESTDOC: TEST (Fehlerfall): Wirft der Service eine BadUserRequestException (z. B. leere Auswahl),
  // TESTDOC: muss die REST-Schicht dies in HTTP 400 (Bad Request) uebersetzen.
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
