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
package org.cibseven.bpm.spring.boot.starter.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Date;
import java.util.TimeZone;

import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.rest.mapper.CibsevenJacksonJsonProvider;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.spring.boot.starter.property.CamundaBpmProperties;
import org.cibseven.bpm.spring.boot.starter.rest.test.TestRestApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.util.ClassUtils;

import tools.jackson.databind.json.JsonMapper;

/**
 * The REST API of the starter writes and reads JSON with the Jackson 3 provider of
 * engine-rest ({@link CibsevenJacksonJsonProvider} with the mapper of the
 * JacksonConfigurator), not with the Jackson 2 JacksonFeature of Jersey.
 *
 * <p>Dates are compared with exact strings in a JVM default time zone with a visible offset
 * (Europe/Berlin, +0200 in summer). Own application context: the provider builds its JSON writer
 * for a type at the first request, with the time zone of that moment.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = TestRestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
public class RestJsonProviderIT {

  private static final Date DUE = Date.from(Instant.parse("2026-07-14T08:30:15.123Z"));
  /** {@link #DUE} in the date format of the REST API (JacksonConfigurator) in Europe/Berlin. */
  private static final String DUE_IN_THE_REST_API = "2026-07-14T10:30:15.123+0200";

  private static TimeZone defaultTimeZone;

  /** Hands the Jackson 2 ObjectMapper of Spring Boot and Jersey's JacksonFeature to the ResourceConfig. */
  private static final String JERSEY_JACKSON2_CUSTOMIZER =
      "org.springframework.boot.jersey.autoconfigure.JerseyAutoConfiguration$Jackson2ResourceConfigCustomizerConfiguration";

  @Autowired
  private TestRestTemplate testRestTemplate;

  @Autowired
  private CamundaBpmProperties camundaBpmProperties;

  @Autowired
  private CamundaJerseyResourceConfig resourceConfig;

  @Autowired
  private ConfigurableApplicationContext applicationContext;

  @Autowired
  private TaskService taskService;

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

    ResponseEntity<String> entity = testRestTemplate.getForEntity("/engine-rest/engine/{engineName}/task/{id}",
        String.class, camundaBpmProperties.getProcessEngineName(), taskId);

    assertThat(entity.getStatusCode()).as(entity.getBody()).isEqualTo(HttpStatus.OK);
    assertThat(JsonMapper.builder().build().readTree(entity.getBody()).get("due").asString()).isEqualTo(DUE_IN_THE_REST_API);
  }

  @Test
  public void shouldAnswerMalformedJsonWithAnInvalidRequestException() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    ResponseEntity<String> entity = testRestTemplate.postForEntity(
        "/engine-rest/engine/{engineName}/process-definition/key/TestProcess/start",
        new HttpEntity<>("{\"variables\": ", headers), String.class, camundaBpmProperties.getProcessEngineName());

    assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(entity.getBody()).contains("\"type\":\"InvalidRequestException\"");
  }

  @Test
  public void shouldAnswerJsonOfAnotherTypeWithAnInvalidRequestException() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    // valid JSON, but the priority of a task is a number
    ResponseEntity<String> entity = testRestTemplate.postForEntity("/engine-rest/engine/{engineName}/task/create",
        new HttpEntity<>("{\"name\": \"task\", \"priority\": \"high\"}", headers), String.class,
        camundaBpmProperties.getProcessEngineName());

    assertThat(entity.getStatusCode()).as(entity.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(entity.getBody()).contains("\"type\":\"InvalidRequestException\"");
  }

  @Test
  public void shouldUseTheJackson3ProviderOfTheRestApiOnly() {
    assertThat(resourceConfig.isRegistered(CibsevenJacksonJsonProvider.class)).isTrue();
    // no Jackson 2 JSON support of Jersey (jersey-media-json-jackson) on the classpath ...
    assertThat(ClassUtils.isPresent("org.glassfish.jersey.jackson.JacksonFeature", null)).isFalse();
    assertThat(ConditionEvaluationReport.get(applicationContext.getBeanFactory()).getConditionAndOutcomesBySource())
        .hasEntrySatisfying(JERSEY_JACKSON2_CUSTOMIZER, outcomes -> assertThat(outcomes.isFullMatch()).isFalse());
    // ... and if it comes back through the application, it stays switched off
    assertThat(resourceConfig.getProperty(CamundaJerseyResourceConfig.JERSEY_SERVER_JSON_FEATURE))
        .isEqualTo(CamundaJerseyResourceConfig.JSON_FEATURE);
  }

}
