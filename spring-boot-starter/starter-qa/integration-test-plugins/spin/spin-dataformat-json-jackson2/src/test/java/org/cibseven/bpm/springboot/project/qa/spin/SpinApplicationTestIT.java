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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.ObjectValue;
import org.cibseven.bpm.spring.boot.starter.spin.CamundaJacksonFormatConfiguratorJSR310;
import org.cibseven.bpm.spring.boot.starter.spin.CamundaJacksonFormatConfiguratorJdk8;
import org.cibseven.bpm.spring.boot.starter.spin.CamundaJacksonFormatConfiguratorParameterNames;
import org.cibseven.spin.DataFormats;
import org.cibseven.spin.Spin;
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
  TaskService taskService;

  @Autowired
  ApplicationContext applicationContext;

  /** An object variable with Java 8 types, as in CIB7-2681. */
  public static class Java8Values {
    public LocalDate day;
    public Instant at;
    public Duration took;
    public Optional<String> note;
  }

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

  @Test
  public void shouldWriteAndReadJava8TypesInObjectVariable() {
    // given
    Java8Values written = new Java8Values();
    written.day = LocalDate.of(2026, 11, 10);
    // milliseconds on purpose: the engine reads the stored JSON as a tree, where the decimal timestamp
    // Jackson 2 writes for an Instant is a double and loses the last digits of the nanoseconds
    written.at = Instant.parse("2026-11-10T12:51:15.123Z");
    written.took = Duration.ofMillis(1500);
    written.note = Optional.of("java.time");

    Task task = taskService.newTask();
    taskService.saveTask(task);
    try {
      // when the variable is written by one command and read from the database by another one
      taskService.setVariable(task.getId(), "values", Variables.objectValue(written)
          .serializationDataFormat(DataFormats.JSON_DATAFORMAT_NAME)
          .create());
      ObjectValue typedValue = taskService.getVariableTyped(task.getId(), "values");

      // then
      assertThat(typedValue.getSerializationDataFormat()).isEqualTo(DataFormats.JSON_DATAFORMAT_NAME);
      Java8Values read = typedValue.getValue(Java8Values.class);
      assertThat(read).isNotSameAs(written);
      assertThat(read.day).isEqualTo(written.day);
      assertThat(read.at).isEqualTo(written.at);
      assertThat(read.took).isEqualTo(written.took);
      assertThat(read.note).isEqualTo(written.note);
    } finally {
      // with its history, so that the other tests of this class see their own variables only
      taskService.deleteTask(task.getId(), true);
    }
  }

  @Test
  public void shouldUseConstructorParameterNames() {
    // the Jackson 2 ParameterNames module binds the JSON properties to the constructor parameters
    SpinConstructorDto dto = Spin.JSON("{\"name\":\"order\",\"amount\":3}").mapTo(SpinConstructorDto.class);

    assertThat(dto.getName()).isEqualTo("order");
    assertThat(dto.getAmount()).isEqualTo(3);
  }
}
