/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
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
package org.cibseven.bpm.run.test.config.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Date;
import java.util.TimeZone;

import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.run.test.AbstractRestTest;
import org.cibseven.bpm.spring.boot.starter.rest.CamundaJerseyResourceConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.util.ClassUtils;

import tools.jackson.databind.json.JsonMapper;

/**
 * The REST API of Run reads and writes JSON with the Jackson 3 provider of engine-rest, with the
 * date format and the error responses of the REST API; Jersey's Jackson 2 JacksonFeature is not
 * there.
 *
 * <p>Dates are compared with exact strings in a JVM default time zone with a visible offset
 * (Europe/Berlin, +0200 in summer).
 *
 * <p>Own application context: a cached one can have lost its process engine to another test class
 * that closed its context ("No process engine available"), and the provider builds its JSON writer
 * for a type at the first request, with the time zone of that moment.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
public class RestJsonProviderTest extends AbstractRestTest {

  private static final Date DUE = Date.from(Instant.parse("2026-07-14T08:30:15.123Z"));
  /** {@link #DUE} in the date format of the REST API (JacksonConfigurator) in Europe/Berlin. */
  private static final String DUE_IN_THE_REST_API = "2026-07-14T10:30:15.123+0200";

  private static TimeZone defaultTimeZone;

  @Autowired
  private TaskService taskService;

  @Autowired
  private CamundaJerseyResourceConfig resourceConfig;

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

  @AfterEach
  public void deleteTask() {
    if (taskId != null) {
      taskService.deleteTask(taskId, true);
    }
  }

  @Test
  public void shouldWriteDatesInTheRestApiDateFormat() {
    Task task = taskService.newTask();
    task.setDueDate(DUE);
    taskService.saveTask(task);
    taskId = task.getId();

    ResponseEntity<String> response = testRestTemplate.getForEntity(CONTEXT_PATH + "/task/{id}", String.class, taskId);

    assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    assertThat(JsonMapper.builder().build().readTree(response.getBody()).get("due").asString()).isEqualTo(DUE_IN_THE_REST_API);
  }

  @Test
  public void shouldAnswerMalformedJsonWithAnInvalidRequestException() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    ResponseEntity<String> response = testRestTemplate.postForEntity(CONTEXT_PATH + "/task/create",
        new HttpEntity<>("{\"name\": ", headers), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).contains("\"type\":\"InvalidRequestException\"");
  }

  @Test
  public void shouldAnswerJsonOfAnotherTypeWithAnInvalidRequestException() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    // valid JSON, but the priority of a task is a number
    ResponseEntity<String> response = testRestTemplate.postForEntity(CONTEXT_PATH + "/task/create",
        new HttpEntity<>("{\"name\": \"task\", \"priority\": \"high\"}", headers), String.class);

    assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).contains("\"type\":\"InvalidRequestException\"");
  }

  @Test
  public void shouldKeepTheJsonFeatureNextToTheRunProperties() {
    assertThat(ClassUtils.isPresent("org.glassfish.jersey.jackson.JacksonFeature", null)).isFalse();
    // Run replaces the properties of the ResourceConfig (WADL switch); the JSON feature is set afterwards
    assertThat(resourceConfig.getProperty("jersey.config.server.wadl.disableWadl")).isNotNull();
    assertThat(resourceConfig.getProperty(CamundaJerseyResourceConfig.JERSEY_SERVER_JSON_FEATURE))
        .isEqualTo(CamundaJerseyResourceConfig.JSON_FEATURE);
  }

}
