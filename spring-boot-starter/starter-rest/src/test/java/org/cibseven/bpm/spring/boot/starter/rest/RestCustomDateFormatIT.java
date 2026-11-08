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
package org.cibseven.bpm.spring.boot.starter.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Date;
import java.util.TimeZone;

import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.rest.CustomJacksonDateFormatListener;
import org.cibseven.bpm.engine.rest.mapper.JacksonConfigurator;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.spring.boot.starter.property.CamundaBpmProperties;
import org.cibseven.bpm.spring.boot.starter.rest.test.TestRestApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import tools.jackson.databind.json.JsonMapper;

/**
 * A custom date format of the REST API in a Spring Boot application: the starter does not register
 * {@link CustomJacksonDateFormatListener} itself; the application registers it as a bean and sets
 * its context parameter {@value CustomJacksonDateFormatListener#CONTEXT_PARAM_NAME} with
 * <code>server.servlet.context-parameters</code>. The Jackson 3 provider of the REST API writes and
 * reads dates in that format (in the JVM default time zone, here Europe/Berlin).
 *
 * <p>The application context is closed after the tests (the listener resets the date format then);
 * its process engine has its own name, so that closing it does not unregister the engine of the
 * cached contexts of the other tests.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = TestRestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "server.servlet.context-parameters[" + CustomJacksonDateFormatListener.CONTEXT_PARAM_NAME + "]="
            + RestCustomDateFormatIT.DATE_FORMAT,
        "camunda.bpm.process-engine-name=customDateFormatEngine" })
@Import(RestCustomDateFormatIT.DateFormatListenerConfiguration.class)
@AutoConfigureTestRestTemplate
@DirtiesContext
public class RestCustomDateFormatIT {

  static final String DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ssXXX";

  private static final Instant DUE = Instant.parse("2026-07-14T08:30:15Z");
  /** {@link #DUE} in {@link #DATE_FORMAT} in Europe/Berlin. */
  private static final String DUE_IN_THE_DATE_FORMAT = "2026-07-14T10:30:15+02:00";

  private static TimeZone defaultTimeZone;

  @Configuration(proxyBeanMethods = false)
  static class DateFormatListenerConfiguration {

    @Bean
    CustomJacksonDateFormatListener customJacksonDateFormatListener() {
      return new CustomJacksonDateFormatListener();
    }

  }

  @Autowired
  private TestRestTemplate testRestTemplate;

  @Autowired
  private CamundaBpmProperties camundaBpmProperties;

  @Autowired
  private TaskService taskService;

  private String taskId;

  @BeforeAll
  public static void setTimeZone() {
    defaultTimeZone = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
  }

  @AfterAll
  public static void reset() {
    TimeZone.setDefault(defaultTimeZone);
    // also done by the listener when the application context is closed
    JacksonConfigurator.setDateFormatString(JacksonConfigurator.DEFAULT_DATE_FORMAT);
  }

  @AfterEach
  public void deleteTask() {
    if (taskId != null) {
      taskService.deleteTask(taskId, true);
    }
  }

  @Test
  public void shouldWriteDatesInTheCustomDateFormat() {
    Task task = taskService.newTask();
    task.setDueDate(Date.from(DUE));
    taskService.saveTask(task);
    taskId = task.getId();

    ResponseEntity<String> entity = testRestTemplate.getForEntity("/engine-rest/engine/{engineName}/task/{id}",
        String.class, camundaBpmProperties.getProcessEngineName(), taskId);

    assertThat(entity.getStatusCode()).as(entity.getBody()).isEqualTo(HttpStatus.OK);
    assertThat(JsonMapper.builder().build().readTree(entity.getBody()).get("due").asString()).isEqualTo(DUE_IN_THE_DATE_FORMAT);
  }

  @Test
  public void shouldReadDatesInTheCustomDateFormat() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    ResponseEntity<String> entity = testRestTemplate.postForEntity("/engine-rest/engine/{engineName}/task/create",
        new HttpEntity<>("{\"name\": \"customDateFormat\", \"due\": \"" + DUE_IN_THE_DATE_FORMAT + "\"}", headers),
        String.class, camundaBpmProperties.getProcessEngineName());

    assertThat(entity.getStatusCode()).as(entity.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
    Task task = taskService.createTaskQuery().taskName("customDateFormat").singleResult();
    taskId = task.getId();
    assertThat(task.getDueDate().toInstant()).isEqualTo(DUE);
  }

}
