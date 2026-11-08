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
package org.cibseven.bpm.springboot.project.qa.jackson2;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Date;
import java.util.TimeZone;

import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.spring.boot.starter.rest.CamundaJerseyResourceConfig;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import tools.jackson.databind.json.JsonMapper;

/**
 * An application that brings Jackson 2 for JAX-RS back (spring-boot-starter-jersey with
 * spring-boot-jackson2 and jersey-media-json-jackson): the REST API of the REST starter and the
 * REST APIs of the web applications still read and write JSON with the Jackson 3 provider of
 * engine-rest, i.e. with the date format and the error responses of the REST API, not with
 * Jersey's JacksonFeature and the Jackson 2 ObjectMapper of Spring Boot.
 *
 * <p>The JSON is compared with exact strings in a JVM default time zone with a visible offset
 * (Europe/Berlin, +0200 in summer). Without the property <code>jersey.config.server.jsonFeature</code>
 * of the starters, Jackson 2 takes over: the REST API writes the due date with the ObjectMapper of
 * Spring Boot (<code>2026-07-14T08:30:15.123+00:00</code>), the web applications with the default
 * mapper of Jersey (<code>1784017815123</code>), and Jersey's Jackson 2 exception mappers answer
 * invalid JSON with a 400 and a text message instead of an InvalidRequestException.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class Jackson2InTheApplicationIT {

  /** Hands the Jackson 2 ObjectMapper of Spring Boot and Jersey's JacksonFeature to the ResourceConfig. */
  private static final String JERSEY_JACKSON2_CUSTOMIZER =
      "org.springframework.boot.jersey.autoconfigure.JerseyAutoConfiguration$Jackson2ResourceConfigCustomizerConfiguration";

  private static final Date DUE = Date.from(Instant.parse("2026-07-14T08:30:15.123Z"));
  /** {@link #DUE} in the date format of the REST API in Europe/Berlin. */
  private static final String DUE_IN_THE_REST_API = "2026-07-14T10:30:15.123+0200";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static TimeZone defaultTimeZone;

  @LocalServerPort
  private int port;

  @Autowired
  private TaskService taskService;

  @Autowired
  private CamundaJerseyResourceConfig resourceConfig;

  @Autowired
  private ConfigurableApplicationContext applicationContext;

  private HttpClient client;

  private String taskId;

  @BeforeAll
  public static void setTimeZone() {
    defaultTimeZone = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
  }

  @AfterAll
  public static void resetTimeZone() {
    TimeZone.setDefault(defaultTimeZone);
  }

  @BeforeEach
  public void createTask() {
    client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();

    Task task = taskService.newTask();
    task.setDueDate(DUE);
    taskService.saveTask(task);
    taskId = task.getId();
  }

  @AfterEach
  public void deleteTask() {
    taskService.deleteTask(taskId, true);
  }

  @Test
  public void shouldHaveJackson2ForJaxRsInTheApplication() {
    assertThat(applicationContext.getBeanProvider(com.fasterxml.jackson.databind.ObjectMapper.class).getIfUnique()).isNotNull();
    assertThat(ConditionEvaluationReport.get(applicationContext.getBeanFactory()).getConditionAndOutcomesBySource())
        .hasEntrySatisfying(JERSEY_JACKSON2_CUSTOMIZER, outcomes -> assertThat(outcomes.isFullMatch()).isTrue());
    // registered by Spring Boot, but switched off by the REST starter
    assertThat(resourceConfig.isRegistered(JacksonFeature.class)).isTrue();
  }

  @Test
  public void shouldWriteDatesOfTheRestApiInItsDateFormat() throws Exception {
    HttpResponse<String> response = get("/engine-rest/task/" + taskId);

    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(JSON.readTree(response.body()).get("due").asString()).isEqualTo(DUE_IN_THE_REST_API);
  }

  @Test
  public void shouldAnswerMalformedJsonOfTheRestApiWithAnInvalidRequestException() throws Exception {
    HttpResponse<String> response = postJson("/engine-rest/task/create", "{\"name\": ", null);

    assertInvalidRequest(response);
  }

  @Test
  public void shouldAnswerJsonOfAnotherTypeOfTheRestApiWithAnInvalidRequestException() throws Exception {
    HttpResponse<String> response = postJson("/engine-rest/task/create", "{\"name\": \"task\", \"priority\": \"high\"}", null);

    assertInvalidRequest(response);
  }

  @Test
  public void shouldWriteDatesOfTheWebappsInTheDateFormatOfTheRestApi() throws Exception {
    login();

    HttpResponse<String> response = get("/camunda/api/engine/engine/default/task/" + taskId);

    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(JSON.readTree(response.body()).get("due").asString()).isEqualTo(DUE_IN_THE_REST_API);
  }

  @Test
  public void shouldAnswerMalformedJsonOfTheWebappsWithAnInvalidRequestException() throws Exception {
    HttpResponse<String> response = postJson("/camunda/api/engine/engine/default/identity/password-policy",
        "{\"password\": ", csrfToken());

    assertInvalidRequest(response);
  }

  @Test
  public void shouldAnswerJsonOfAnotherTypeOfTheWebappsWithAnInvalidRequestException() throws Exception {
    HttpResponse<String> response = postJson("/camunda/api/engine/engine/default/identity/password-policy",
        "{\"password\": \"secret\", \"profile\": \"demo\"}", csrfToken());

    assertInvalidRequest(response);
  }

  /**
   * Logs in to the web applications as the admin user (session cookie in {@link #client}).
   */
  private void login() throws IOException, InterruptedException {
    String csrfToken = csrfToken();
    HttpResponse<String> login = client.send(HttpRequest.newBuilder(uri("/camunda/api/admin/auth/user/default/login/cockpit"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("X-XSRF-TOKEN", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString("username=demo&password=demo"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
  }

  /**
   * @return the CSRF token of the web applications for the session in {@link #client}
   */
  private String csrfToken() throws IOException, InterruptedException {
    return get("/camunda/api/engine/engine/").headers().firstValue("X-XSRF-TOKEN").orElseThrow();
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    return client.send(HttpRequest.newBuilder(uri(path)).header("Accept", "application/json").GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> postJson(String path, String json, String csrfToken) throws IOException, InterruptedException {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json));
    if (csrfToken != null) {
      request.header("X-XSRF-TOKEN", csrfToken);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private static void assertInvalidRequest(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
    // the exception mapper of the REST API, not the Jackson 2 exception mappers of Jersey's JacksonFeature
    assertThat(response.body()).contains("\"type\":\"InvalidRequestException\"");
  }

}
