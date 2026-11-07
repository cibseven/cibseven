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

import org.cibseven.bpm.engine.rest.mapper.CibsevenJacksonJsonProvider;
import org.cibseven.bpm.spring.boot.starter.property.CamundaBpmProperties;
import org.cibseven.bpm.spring.boot.starter.rest.test.TestRestApplication;
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
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.util.ClassUtils;

/**
 * The REST API of the starter writes and reads JSON with the Jackson 3 provider of
 * engine-rest ({@link CibsevenJacksonJsonProvider} with the mapper of the
 * JacksonConfigurator), not with the Jackson 2 JacksonFeature of Jersey.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = TestRestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
public class RestJsonProviderIT {

  /** Date format of the REST API (JacksonConfigurator), e.g. 2026-11-07T14:20:01.123+0100. */
  private static final String REST_API_DATE = "\"deploymentTime\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{4}\"";

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

  @Test
  public void shouldWriteDatesInTheRestApiDateFormat() {
    ResponseEntity<String> entity = testRestTemplate.getForEntity("/engine-rest/engine/{engineName}/deployment",
        String.class, camundaBpmProperties.getProcessEngineName());

    assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(entity.getBody()).containsPattern(REST_API_DATE);
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
