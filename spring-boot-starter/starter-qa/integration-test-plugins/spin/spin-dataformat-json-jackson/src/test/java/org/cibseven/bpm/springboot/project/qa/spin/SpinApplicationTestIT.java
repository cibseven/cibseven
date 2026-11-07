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
package org.cibseven.bpm.springboot.project.qa.spin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.spring.boot.starter.spin.CamundaJacksonFormatConfiguratorJSR310;
import org.cibseven.bpm.spring.boot.starter.spin.CamundaJacksonFormatConfiguratorJdk8;
import org.cibseven.bpm.spring.boot.starter.spin.CamundaJacksonFormatConfiguratorParameterNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = { SpinApplication.class },
                webEnvironment = SpringBootTest.WebEnvironment.NONE)
@SuppressWarnings("removal")
public class SpinApplicationTestIT {

  @Autowired
  RuntimeService runtimeService;

  @Autowired
  HistoryService historyService;

  @Autowired
  ApplicationContext applicationContext;

  @Test
  public void shouldDeserializeSuccessfully() {
    // when
    runtimeService.startProcessInstanceByKey("spinServiceProcess");

    // then
    long variableCount = historyService.createHistoricVariableInstanceQuery().count();
    assertThat(variableCount).isOne();
  }

  @Test
  public void shouldDeserializeJavaTimeWithJackson3() {
    // Jackson 3 supports java.time out of the box; with Jackson 2, this failed with
    // SPIN/JACKSON-JSON-01006 unless jackson-datatype-jsr310 was registered
    assertThatCode(() -> runtimeService.startProcessInstanceByKey("spinJava8ServiceProcess"))
      .doesNotThrowAnyException();
  }

  @Test
  public void shouldNotRegisterJackson2Configurators() {
    // the Jackson 2 module configurators of the starter apply only to cibseven-spin-dataformat-json-jackson2
    assertThat(applicationContext.getBeanNamesForType(CamundaJacksonFormatConfiguratorJSR310.class)).isEmpty();
    assertThat(applicationContext.getBeanNamesForType(CamundaJacksonFormatConfiguratorJdk8.class)).isEmpty();
    assertThat(applicationContext.getBeanNamesForType(CamundaJacksonFormatConfiguratorParameterNames.class)).isEmpty();
  }
}
