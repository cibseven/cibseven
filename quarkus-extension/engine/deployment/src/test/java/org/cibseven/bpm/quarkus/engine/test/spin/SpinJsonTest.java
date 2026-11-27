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
package org.cibseven.bpm.quarkus.engine.test.spin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.LocalDate;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import io.quarkus.test.QuarkusUnitTest;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.ObjectValue;
import org.cibseven.bpm.quarkus.engine.extension.QuarkusProcessEngineConfiguration;
import org.cibseven.bpm.quarkus.engine.test.helper.ProcessEngineAwareExtension;
import org.cibseven.spin.DataFormats;
import org.cibseven.spin.Spin;
import org.cibseven.spin.impl.json.jackson.format.JacksonJsonDataFormat;
import org.cibseven.spin.json.SpinJsonNode;
import org.cibseven.spin.plugin.impl.SpinProcessEnginePlugin;
import org.cibseven.spin.plugin.variable.SpinValues;
import org.cibseven.spin.plugin.variable.value.JsonValue;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Spin JSON on Quarkus. CIB seven 3.x moved Spin JSON to Jackson 3 (tools.jackson), which the
 * Quarkus platform BOM does not manage; Quarkus itself stays on Jackson 2
 * (com.fasterxml.jackson). The two live in different packages and may share a classpath, so the
 * extension works with the Jackson 3 artifact cibseven-spin-dataformat-json-jackson, not with the
 * deprecated Jackson 2 variant cibseven-spin-dataformat-json-jackson2.
 *
 * <p>The tests cover what a Quarkus application depends on: that the Spin data format is found
 * through the ServiceLoader inside the Quarkus class loader, that it reads and writes JSON with
 * Jackson 3, that JsonPath works (json-path 3.0.0 or later, for Jackson3JsonProvider), and that
 * the Spin process engine plugin registers its JUEL functions and variable serializers.
 */
public class SpinJsonTest {

  @RegisterExtension
  static final QuarkusUnitTest unitTest = new ProcessEngineAwareExtension()
      .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
          .addClasses(MyConfig.class, SpinJsonDto.class, SpinJavaTimeDto.class)
          .addAsResource("org/cibseven/bpm/quarkus/engine/test/spin/spinJsonProcess.bpmn"));

  @ApplicationScoped
  static class MyConfig {

    @Produces
    public QuarkusProcessEngineConfiguration engineConfiguration() {
      QuarkusProcessEngineConfiguration engineConfig = new QuarkusProcessEngineConfiguration();
      engineConfig.setJobExecutorActivate(false);
      // the Quarkus extension does not discover process engine plugins, an application registers them
      engineConfig.getProcessEnginePlugins().add(new SpinProcessEnginePlugin());
      return engineConfig;
    }

  }

  @Inject
  ProcessEngine processEngine;

  @Inject
  RuntimeService runtimeService;

  @Inject
  RepositoryService repositoryService;

  @Test
  public void shouldDiscoverSpinJsonDataFormat() {
    // given the Spin process engine plugin, which loads the data formats with the class loader of
    // the plugin: the Quarkus runtime class loader in a Quarkus application

    // then the ServiceLoader found cibseven-spin-dataformat-json-jackson
    assertThat(DataFormats.json()).isInstanceOf(JacksonJsonDataFormat.class);
  }

  @Test
  public void shouldUseJackson3() {
    // given
    JacksonJsonDataFormat dataFormat = (JacksonJsonDataFormat) DataFormats.json();

    // then the object mapper is a Jackson 3 one (tools.jackson), not the Jackson 2 one
    // (com.fasterxml.jackson) of the deprecated cibseven-spin-dataformat-json-jackson2
    String objectMapperClass = dataFormat.getObjectMapper().getClass().getName();
    assertThat(objectMapperClass).startsWith("tools.jackson.");
    assertThat(objectMapperClass).doesNotStartWith("com.fasterxml.jackson.");
  }

  @Test
  public void shouldReadAndWriteJson() {
    // when
    SpinJsonNode node = Spin.JSON("{\"name\":\"order\",\"amount\":3}");

    // then
    assertThat(node.prop("name").stringValue()).isEqualTo("order");
    assertThat(node.prop("amount").numberValue().intValue()).isEqualTo(3);

    SpinJsonNode written = Spin.JSON(Spin.JSON(new SpinJsonDto("order", 3)).toString());
    assertThat(written.prop("name").stringValue()).isEqualTo("order");
    assertThat(written.prop("amount").numberValue().intValue()).isEqualTo(3);
    assertThat(written.prop("tags").isArray()).isTrue();
  }

  @Test
  public void shouldMapJsonToObject() {
    // when
    SpinJsonDto dto = Spin.JSON("{\"name\":\"order\",\"amount\":3}").mapTo(SpinJsonDto.class);

    // then the bean is read through its constructor without parameters and its setters, so a
    // property missing in the JSON keeps its default value
    assertThat(dto.getName()).isEqualTo("order");
    assertThat(dto.getAmount()).isEqualTo(3);
    assertThat(dto.getTags()).isEmpty();
  }

  @Test
  public void shouldQueryWithJsonPath() {
    // given json-path 3.0.0 or later, the first release with Jackson3JsonProvider; an older one
    // fails here with a NoClassDefFoundError

    // when
    String name = Spin.JSON("{\"order\":{\"name\":\"order\",\"amount\":3}}")
        .jsonPath("$.order.name").stringValue();

    // then
    assertThat(name).isEqualTo("order");
  }

  @Test
  public void shouldMapJavaTime() {
    // Jackson 3 reads and writes java.time out of the box; with Jackson 2 this failed with
    // SPIN/JACKSON-JSON-01006 unless jackson-datatype-jsr310 was registered
    assertThatCode(() -> {
      SpinJavaTimeDto dto = Spin.JSON("{\"dueDate\":\"2026-11-16\"}").mapTo(SpinJavaTimeDto.class);
      assertThat(dto.getDueDate()).isEqualTo(LocalDate.of(2026, 11, 16));
    }).doesNotThrowAnyException();
  }

  @Test
  public void shouldEvaluateSpinJsonFunctionInExpression() {
    // given the JUEL function JSON() the Spin process engine plugin registers
    String deploymentId = deploy();
    try {
      // when
      ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("spinJsonProcess",
          Map.of("payload", "{\"name\":\"order\",\"amount\":3}"));

      // then the service task stored what the function returned
      JsonValue document = runtimeService.getVariableTyped(processInstance.getId(), "document");
      assertThat(document.getValue().prop("name").stringValue()).isEqualTo("order");
    } finally {
      repositoryService.deleteDeployment(deploymentId, true);
    }
  }

  @Test
  public void shouldStoreObjectVariableAsJson() {
    // given
    String deploymentId = deploy();
    try {
      ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("spinJsonProcess",
          Map.of("payload", "{\"name\":\"order\",\"amount\":3}"));

      // when
      runtimeService.setVariable(processInstance.getId(), "contract",
          Variables.objectValue(new SpinJsonDto("order", 3))
              .serializationDataFormat(DataFormats.JSON_DATAFORMAT_NAME)
              .create());

      // then it is serialized by Spin JSON and read back as the bean
      ObjectValue stored = runtimeService.getVariableTyped(processInstance.getId(), "contract", false);
      assertThat(stored.getSerializationDataFormat()).isEqualTo(DataFormats.JSON_DATAFORMAT_NAME);
      SpinJsonNode serialized = Spin.JSON(stored.getValueSerialized());
      assertThat(serialized.prop("name").stringValue()).isEqualTo("order");
      assertThat(serialized.prop("amount").numberValue().intValue()).isEqualTo(3);

      SpinJsonDto read = (SpinJsonDto) runtimeService.getVariable(processInstance.getId(), "contract");
      assertThat(read.getName()).isEqualTo("order");
      assertThat(read.getAmount()).isEqualTo(3);
    } finally {
      repositoryService.deleteDeployment(deploymentId, true);
    }
  }

  @Test
  public void shouldStoreSpinJsonVariable() {
    // given the json variable type the Spin process engine plugin registers
    String deploymentId = deploy();
    try {
      ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("spinJsonProcess",
          Map.of("payload", "{\"name\":\"order\",\"amount\":3}"));

      // when
      runtimeService.setVariable(processInstance.getId(), "manualDocument",
          SpinValues.jsonValue("{\"name\":\"order\"}").create());

      // then
      JsonValue value = runtimeService.getVariableTyped(processInstance.getId(), "manualDocument");
      assertThat(value.getValue().prop("name").stringValue()).isEqualTo("order");
    } finally {
      repositoryService.deleteDeployment(deploymentId, true);
    }
  }

  protected String deploy() {
    return processEngine.getRepositoryService().createDeployment()
        .addClasspathResource("org/cibseven/bpm/quarkus/engine/test/spin/spinJsonProcess.bpmn")
        .deploy().getId();
  }

}
