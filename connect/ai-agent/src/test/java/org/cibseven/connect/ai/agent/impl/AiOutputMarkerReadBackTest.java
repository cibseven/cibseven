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
package org.cibseven.connect.ai.agent.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.ObjectValue;
import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.cibseven.spin.DataFormats;
import org.cibseven.spin.impl.json.jackson.format.JacksonJsonDataFormat;
import org.cibseven.spin.impl.json.jackson.format.MapJacksonJsonTypeDetector;
import org.cibseven.spin.plugin.impl.SpinProcessEnginePlugin;
import org.cibseven.spin.spi.DataFormatConfigurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The AI-output marker ({@code ${outputAiMeta}}, which the element templates map to
 * {@code agentOutput_aiMeta}) can be read back from the process instance, whatever the engine's
 * serialization setup, after a plain service task and after an agentic ad hoc sub process.
 *
 * <p>The marker is a map whose first value is a Boolean ({@code aiGenerated}) and whose others are
 * Strings. A variable that cannot be read back fails more than its own reads: every read of all of
 * the instance's variables fails with it, as a task's form variables, and so does a gateway that
 * asks the marker.
 *
 * <p>Before CIB7-2819 the marker could not be read back under JSON with type validation, nor under
 * JSON with the map type detector, after either activity. Now it is stored in the engine's default
 * format under a type of its own ({@link AiOutputMarker}), and reads back in every setup.
 */
public class AiOutputMarkerReadBackTest {

  /** How the engine stores an object value. Spin is there in each. */
  enum Setup {
    /** The engine's default: Java serialization. */
    JAVA,
    /** Java, and the type validation of {@code production.yml} of CIB seven Run. */
    JAVA_VALIDATION,
    /** JSON as the default format. */
    JSON,
    /** JSON, with the type validation that the security guide recommends. */
    JSON_VALIDATION,
    /** JSON, with Spin's map type detector added, as a host can add it. */
    JSON_MAP_DETECTOR
  }

  /** Where the agent runs. */
  enum Activity {
    SERVICE_TASK, MULTI_INSTANCE_SERVICE_TASK, AD_HOC_SUB_PROCESS
  }

  private HttpServer stub;
  private ProcessEngine engine;

  static Stream<Arguments> setups() {
    List<Arguments> arguments = new ArrayList<Arguments>();
    for (Setup setup : Setup.values()) {
      for (Activity activity : Activity.values()) {
        arguments.add(Arguments.of(setup, activity));
      }
    }
    return arguments.stream();
  }

  @AfterEach
  public void stopEverything() {
    if (engine != null) {
      engine.close();
      engine = null;
    }
    if (stub != null) {
      stub.stop(0);
      stub = null;
    }
    // Spin's data formats are global: drop the detector for whatever runs next.
    DataFormats.loadDataFormats(SpinProcessEnginePlugin.class.getClassLoader());
  }

  @ParameterizedTest(name = "{1} on {0}")
  @MethodSource("setups")
  public void theMarkerReadsBack(Setup setup, Activity activity) throws IOException {
    startStub();
    startEngine(setup, activity);
    ProcessInstance pi = engine.getRuntimeService().startProcessInstanceByKey("marker");
    // The ad hoc sub process runs its turn as jobs; the service task has run already.
    TurnSteps.runToTheEnd(engine, pi.getId());
    Task review = engine.getTaskService().createTaskQuery().processInstanceId(pi.getId())
        .singleResult();
    assertThat(review).as("the instance waits after the agent").isNotNull();

    assertStoredFormat(setup, pi);
    Object marker = engine.getRuntimeService().getVariable(pi.getId(), "agentOutput_aiMeta");
    assertThat(marker).isInstanceOf(Map.class);
    assertThat(((Map<?, ?>) marker).get("aiGenerated")).isEqualTo(Boolean.TRUE);
    assertThat(((Map<?, ?>) marker).get("generatedAt")).isInstanceOf(String.class);

    assertThat(engine.getRuntimeService().getVariables(pi.getId()))
        .as("all of the instance's variables").containsKey("agentOutput_aiMeta");
    assertThat(engine.getFormService().getTaskFormVariables(review.getId()))
        .as("the task's form variables").containsKey("agentOutput_aiMeta");

    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("agentOutput_aiMeta").singleResult().getValue())
        .as("the marker in the history").isEqualTo(marker);

    engine.getTaskService().complete(review.getId());
    assertThat(engine.getHistoryService().createHistoricActivityInstanceQuery()
        .processInstanceId(pi.getId()).activityId("aiGenerated").count())
        .as("the gateway asks ${agentOutput_aiMeta.aiGenerated}").isEqualTo(1);
  }

  /**
   * A definition deployed earlier is parsed again when the engine restarts, or when its cache
   * entry is dropped. It stores the marker the same way.
   */
  @ParameterizedTest(name = "{0}")
  @org.junit.jupiter.params.provider.EnumSource(Activity.class)
  public void aDefinitionParsedAgainStoresTheMarkerTheSameWay(Activity activity)
      throws IOException {
    startStub();
    startEngine(Setup.JSON_MAP_DETECTOR, activity);
    ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration()).getDeploymentCache()
        .discardProcessDefinitionCache();

    ProcessInstance pi = engine.getRuntimeService().startProcessInstanceByKey("marker");
    TurnSteps.runToTheEnd(engine, pi.getId());

    assertStoredFormat(Setup.JSON_MAP_DETECTOR, pi);
    assertThat(engine.getRuntimeService().getVariables(pi.getId()))
        .containsKey("agentOutput_aiMeta");
  }

  /** In the engine's default format; with JSON, under the marker's own type. */
  private void assertStoredFormat(Setup setup, ProcessInstance pi) {
    ObjectValue stored = (ObjectValue) engine.getRuntimeService()
        .getVariableTyped(pi.getId(), "agentOutput_aiMeta", false);
    if (setup == Setup.JAVA || setup == Setup.JAVA_VALIDATION) {
      assertThat(stored.getSerializationDataFormat())
          .isEqualTo(Variables.SerializationDataFormats.JAVA.getName());
    } else {
      assertThat(stored.getSerializationDataFormat())
          .isEqualTo(Variables.SerializationDataFormats.JSON.getName());
      assertThat(stored.getObjectTypeName()).isEqualTo(AiOutputMarker.JSON_TYPE_NAME);
      assertThat(stored.getValueSerialized()).startsWith("{\"aiGenerated\":true,\"runId\":\"");
    }
  }

  private void startStub() throws IOException {
    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext("/v1/chat/completions", this::answer);
    stub.start();
  }

  private void startEngine(Setup setup, Activity activity) {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName("ai-output-marker");
    configuration.setJdbcUrl("jdbc:h2:mem:ai-output-marker;DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    configuration.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
    configuration.setHistoryTimeToLive("P30D");
    if (setup == Setup.JSON || setup == Setup.JSON_VALIDATION
        || setup == Setup.JSON_MAP_DETECTOR) {
      configuration.setDefaultSerializationFormat(Variables.SerializationDataFormats.JSON.getName());
    }
    configuration.setDeserializationTypeValidationEnabled(
        setup == Setup.JAVA_VALIDATION || setup == Setup.JSON_VALIDATION);
    List<ProcessEnginePlugin> plugins = new ArrayList<ProcessEnginePlugin>();
    plugins.add(new ConnectProcessEnginePlugin());
    plugins.add(setup == Setup.JSON_MAP_DETECTOR
        ? new SpinWithMapTypeDetector()
        : new SpinProcessEnginePlugin());
    configuration.setProcessEnginePlugins(plugins);
    engine = configuration.buildProcessEngine();
    engine.getRepositoryService().createDeployment()
        .addString("marker.bpmn20.xml", model(activity)).deploy();
  }

  /**
   * The agent, with the outputs the element templates map; then a task to wait in, and a gateway
   * that asks the marker.
   */
  private String model(Activity activity) {
    String connector = "<extensionElements><camunda:connector>"
        + "<camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "<camunda:inputOutput>"
        + "<camunda:inputParameter name='agentName'>Pruefer</camunda:inputParameter>"
        + "<camunda:inputParameter name='message'>Pruefe den Text.</camunda:inputParameter>"
        + "<camunda:inputParameter name='model'>stub</camunda:inputParameter>"
        + "<camunda:inputParameter name='apiKey'>stub-key</camunda:inputParameter>"
        + "<camunda:inputParameter name='baseUrl'>http://127.0.0.1:"
        + stub.getAddress().getPort() + "/v1</camunda:inputParameter>"
        + "<camunda:outputParameter name='agentOutput'>${output}</camunda:outputParameter>"
        + "<camunda:outputParameter name='agentOutput_aiMeta'>${outputAiMeta}"
        + "</camunda:outputParameter>"
        + "</camunda:inputOutput>"
        + "</camunda:connector></extensionElements>";
    String agent;
    if (activity == Activity.SERVICE_TASK) {
      agent = "<serviceTask id='agent' name='Agent'>" + connector + "</serviceTask>";
    } else if (activity == Activity.MULTI_INSTANCE_SERVICE_TASK) {
      agent = "<serviceTask id='agent' name='Agent'>" + connector
          + "<multiInstanceLoopCharacteristics isSequential='true'>"
          + "<loopCardinality>2</loopCardinality></multiInstanceLoopCharacteristics>"
          + "</serviceTask>";
    } else {
      agent = "<adHocSubProcess id='agent' name='Agent'>" + connector
          + "<userTask id='worker' name='Worker' /></adHocSubProcess>";
    }
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + " xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'"
        + " xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + " targetNamespace='http://cibseven.org/ai-output-marker'>"
        + "<process id='marker' isExecutable='true'>"
        + "<startEvent id='start' />"
        + "<sequenceFlow id='f1' sourceRef='start' targetRef='agent' />"
        + agent
        + "<sequenceFlow id='f2' sourceRef='agent' targetRef='review' />"
        + "<userTask id='review' name='Review' />"
        + "<sequenceFlow id='f3' sourceRef='review' targetRef='marked' />"
        + "<exclusiveGateway id='marked' default='f5' />"
        + "<sequenceFlow id='f4' sourceRef='marked' targetRef='aiGenerated'>"
        + "<conditionExpression xsi:type='tFormalExpression'>${agentOutput_aiMeta.aiGenerated}"
        + "</conditionExpression></sequenceFlow>"
        + "<sequenceFlow id='f5' sourceRef='marked' targetRef='notMarked' />"
        + "<endEvent id='aiGenerated' />"
        + "<endEvent id='notMarked' />"
        + "</process>"
        + "</definitions>";
  }

  private void answer(HttpExchange exchange) throws IOException {
    exchange.getRequestBody().readAllBytes();
    byte[] payload = ("{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,"
        + "\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
        + "\"content\":\"Gepruefter Text.\"},\"finish_reason\":\"stop\"}],"
        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}")
        .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, payload.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(payload);
    }
  }

  /**
   * Spin with its map type detector added through a DataFormatConfigurator: its JSON format then
   * names the type of a map after the map's first value.
   */
  protected static class SpinWithMapTypeDetector extends SpinProcessEnginePlugin {

    @Override
    public void preInit(ProcessEngineConfigurationImpl processEngineConfiguration) {
      DataFormats.loadDataFormats(SpinProcessEnginePlugin.class.getClassLoader(),
          Collections.<DataFormatConfigurator>singletonList(
              new DataFormatConfigurator<JacksonJsonDataFormat>() {

                @Override
                public Class<JacksonJsonDataFormat> getDataFormatClass() {
                  return JacksonJsonDataFormat.class;
                }

                @Override
                public void configure(JacksonJsonDataFormat dataFormat) {
                  dataFormat.addTypeDetector(MapJacksonJsonTypeDetector.INSTANCE);
                }
              }));
    }
  }
}
