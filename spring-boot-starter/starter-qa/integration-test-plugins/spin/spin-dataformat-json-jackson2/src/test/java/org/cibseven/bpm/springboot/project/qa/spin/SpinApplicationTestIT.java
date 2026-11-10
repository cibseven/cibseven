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
package org.cibseven.bpm.springboot.project.qa.spin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.spring.boot.starter.spin.CamundaJacksonFormatConfiguratorJSR310;
import org.cibseven.bpm.spring.boot.starter.spin.CamundaJacksonFormatConfiguratorJdk8;
import org.cibseven.bpm.spring.boot.starter.spin.CamundaJacksonFormatConfiguratorParameterNames;
import org.cibseven.spin.DataFormats;
import org.cibseven.spin.impl.json.jackson.format.JacksonJsonDataFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;

/**
 * The starter with the deprecated Jackson 2 variant of Spin: the starter registers the Jackson 2 modules for
 * Java 8 types (java.time, Optional, parameter names) with the Spin object mapper, as in CIB seven 2.x.
 */
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
  public void shouldDeserializeJavaTimeWithJackson2Module() {
    assertThatCode(() -> runtimeService.startProcessInstanceByKey("spinJava8ServiceProcess"))
      .doesNotThrowAnyException();
  }

  @Test
  public void shouldRegisterJackson2ModulesWithSpin() {
    assertThat(applicationContext.getBeanNamesForType(CamundaJacksonFormatConfiguratorJSR310.class)).hasSize(1);
    assertThat(applicationContext.getBeanNamesForType(CamundaJacksonFormatConfiguratorJdk8.class)).hasSize(1);
    assertThat(applicationContext.getBeanNamesForType(CamundaJacksonFormatConfiguratorParameterNames.class)).hasSize(1);

    ObjectMapper objectMapper = ((JacksonJsonDataFormat) DataFormats.json()).getObjectMapper();
    assertThat(objectMapper.getRegisteredModuleIds()).contains(
        new JavaTimeModule().getTypeId(), new Jdk8Module().getTypeId(), new ParameterNamesModule().getTypeId());
  }
}
