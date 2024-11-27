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
package org.cibseven.bpm.spring.boot.starter.security;

import jakarta.annotation.PostConstruct;
import my.own.custom.spring.boot.project.SampleApplication;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;

import static org.assertj.core.api.Assertions.assertThat;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = SampleApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class CamundaBpmSampleApplicationTest {

  private String baseUrl;

  @LocalServerPort
  private int port;

  @Autowired
  private TestRestTemplate testRestTemplate;

  @PostConstruct
  public void postConstruct() {
    baseUrl = "http://localhost:" + port;
  }

  @Test
  public void webappApiIsAvailableAndAuthorized() {
    ResponseEntity<String> entity = testRestTemplate.getForEntity(baseUrl + "/camunda/api/engine/engine/default/user", String.class);
    assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  public void restApiIsAvailable() {
    ResponseEntity<String> entity = testRestTemplate.getForEntity(baseUrl + "/engine-rest/engine/", String.class);
    assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(entity.getBody()).isEqualTo("[{\"name\":\"default\"}]");
  }
}