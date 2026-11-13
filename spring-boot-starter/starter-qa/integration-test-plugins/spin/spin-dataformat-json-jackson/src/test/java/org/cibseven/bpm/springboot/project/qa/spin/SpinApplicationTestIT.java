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
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.ObjectValue;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.spring.boot.starter.spin.CamundaJackson3FormatConfiguratorParameterNames;
import org.cibseven.bpm.spring.boot.starter.spin.CamundaJacksonFormatConfiguratorJSR310;
import org.cibseven.bpm.spring.boot.starter.spin.CamundaJacksonFormatConfiguratorJdk8;
import org.cibseven.bpm.spring.boot.starter.spin.CamundaJacksonFormatConfiguratorParameterNames;
import org.cibseven.spin.DataFormats;
import org.cibseven.spin.Spin;
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
  RepositoryService repositoryService;

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

  @Test
  public void shouldUseConstructorParameterNames() {
    // the starter turns parameter names on again for Jackson 3 (Spin's Jackson 2 defaults turn them off);
    // with Jackson 2, the ParameterNames module did this. A class without a constructor without parameters
    // (also a Kotlin data class or Lombok's @Value) is read through its constructor.
    assertThat(applicationContext.getBeanNamesForType(CamundaJackson3FormatConfiguratorParameterNames.class)).hasSize(1);

    SpinConstructorDto dto = Spin.JSON("{\"name\":\"order\",\"amount\":3}").mapTo(SpinConstructorDto.class);

    assertThat(dto.getName()).isEqualTo("order");
    assertThat(dto.getAmount()).isEqualTo(3);
  }

  @Test
  public void shouldUseDefaultConstructorAndSettersWhenPresent() {
    // as with Jackson 2: a class with a constructor without parameters (Lombok's @Data @NoArgsConstructor
    // @AllArgsConstructor) is read through it and its setters, not through the constructor that takes all
    // properties (Jackson 3 does that once it has parameter names, databind#5318), so properties missing in the
    // JSON keep their default values
    SpinDefaultConstructorDto dto = Spin.JSON("{\"name\":\"order\",\"amount\":3}").mapTo(SpinDefaultConstructorDto.class);

    assertThat(dto.getName()).isEqualTo("order");
    assertThat(dto.getAmount()).isEqualTo(3);
    assertThat(dto.getTags()).isEmpty();
    assertThat(dto.getStatus()).isEqualTo("NEW");
  }

  @Test
  public void shouldKeepDefaultValuesOfVariableWrittenByOlderClassVersion() {
    // given a JSON variable written before the class got the properties tags and status
    String deploymentId = repositoryService.createDeployment()
        .addModelInstance("spin-wait.bpmn", Bpmn.createExecutableProcess("spinWaitProcess")
            .startEvent().userTask().endEvent().done())
        .deploy().getId();
    try {
      String processInstanceId = runtimeService.startProcessInstanceByKey("spinWaitProcess").getId();
      runtimeService.setVariable(processInstanceId, "contract",
          Variables.serializedObjectValue("{\"name\":\"order\",\"amount\":3}")
            .serializationDataFormat(DataFormats.JSON_DATAFORMAT_NAME)
            .objectTypeName(SpinDefaultConstructorDto.class.getName())
            .create());

      // when
      SpinDefaultConstructorDto dto = (SpinDefaultConstructorDto) runtimeService.getVariable(processInstanceId, "contract");

      // then the default values are read and stored again, not null
      assertThat(dto.getTags()).isEmpty();
      assertThat(dto.getStatus()).isEqualTo("NEW");
      ObjectValue stored = runtimeService.getVariableTyped(processInstanceId, "contract", false);
      assertThat(stored.getValueSerialized())
        .isEqualTo("{\"name\":\"order\",\"amount\":3,\"tags\":[],\"status\":\"NEW\"}");
    } finally {
      repositoryService.deleteDeployment(deploymentId, true);
    }
  }

  @Test
  public void shouldSetFinalFieldsAfterDefaultConstructor() {
    // a Kotlin data class whose properties all have default values: read through the constructor without
    // parameters as with Jackson 2; the constructor that takes all properties would get null for tags and fail
    SpinDefaultConstructorFinalFieldsDto dto = Spin.JSON("{\"name\":\"order\",\"amount\":3}")
        .mapTo(SpinDefaultConstructorFinalFieldsDto.class);

    assertThat(dto.getName()).isEqualTo("order");
    assertThat(dto.getAmount()).isEqualTo(3);
    assertThat(dto.getTags()).isEmpty();
  }

  @Test
  public void shouldWritePropertiesInFieldOrderWhenDefaultConstructorPresent() {
    // as with Jackson 2; the parameters of a constructor Jackson reads through would come first
    assertThat(Spin.JSON(new SpinParameterOrderDto(3, "order")).toString())
      .isEqualTo("{\"name\":\"order\",\"amount\":3}");
  }

  @Test
  public void shouldUseJsonCreatorNextToDefaultConstructor() {
    SpinJsonCreatorDto dto = Spin.JSON("{\"name\":\"order\"}").mapTo(SpinJsonCreatorDto.class);

    assertThat(dto.getName()).isEqualTo("order");
    // the annotated constructor got null for tags (the setters would have kept the empty list)
    assertThat(dto.getTags()).isNull();
  }

  @Test
  public void shouldUseCanonicalConstructorOfRecord() {
    SpinRecordDto dto = Spin.JSON("{\"name\":\"order\",\"amount\":3}").mapTo(SpinRecordDto.class);

    assertThat(dto).isEqualTo(new SpinRecordDto("order", 3));
  }
}
