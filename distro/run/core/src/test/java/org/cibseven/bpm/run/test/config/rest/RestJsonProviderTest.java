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

import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.run.test.AbstractRestTest;
import org.cibseven.bpm.spring.boot.starter.rest.CamundaJerseyResourceConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.util.ClassUtils;

/**
 * The REST API of Run reads and writes JSON with the Jackson 3 provider of engine-rest, with the
 * date format and the error responses of the REST API; Jersey's Jackson 2 JacksonFeature is not
 * there.
 *
 * <p>Own application context: a cached one can have lost its process engine to another test class
 * that closed its context ("No process engine available").
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
public class RestJsonProviderTest extends AbstractRestTest {

  /** Date format of the REST API (JacksonConfigurator), e.g. 2026-11-07T14:20:01.123+0100. */
  private static final String REST_API_DATE = "\"created\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{4}\"";

  @Autowired
  private TaskService taskService;

  @Autowired
  private CamundaJerseyResourceConfig resourceConfig;

  private String taskId;

  @AfterEach
  public void deleteTask() {
    if (taskId != null) {
      taskService.deleteTask(taskId, true);
    }
  }

  @Test
  public void shouldWriteDatesInTheRestApiDateFormat() {
    Task task = taskService.newTask();
    taskService.saveTask(task);
    taskId = task.getId();

    ResponseEntity<String> response = testRestTemplate.getForEntity(CONTEXT_PATH + "/task/{id}", String.class, taskId);

    assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsPattern(REST_API_DATE);
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
  public void shouldKeepTheJsonFeatureNextToTheRunProperties() {
    assertThat(ClassUtils.isPresent("org.glassfish.jersey.jackson.JacksonFeature", null)).isFalse();
    // Run replaces the properties of the ResourceConfig (WADL switch); the JSON feature is set afterwards
    assertThat(resourceConfig.getProperty("jersey.config.server.wadl.disableWadl")).isNotNull();
    assertThat(resourceConfig.getProperty(CamundaJerseyResourceConfig.JERSEY_SERVER_JSON_FEATURE))
        .isEqualTo(CamundaJerseyResourceConfig.JSON_FEATURE);
  }

}
