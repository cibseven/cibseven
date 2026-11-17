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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.history.HistoricDetail;
import org.cibseven.bpm.engine.history.HistoricVariableUpdate;
import org.cibseven.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.variable.serializer.JavaObjectSerializer;
import org.cibseven.bpm.engine.impl.variable.serializer.VariableSerializers;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The AI-output marker ({@code ${outputAiMeta}}, which the element templates map to
 * {@code agentOutput_aiMeta}) can be read back from the process instance, whatever the engine's
 * serialization setup (CIB7-2819).
 *
 * <p>The marker is a map whose first value is a Boolean ({@code aiGenerated}) and whose others are
 * Strings. A variable that cannot be read back fails more than its own reads: every read of all of
 * the instance's variables fails with it, and so do a task's form variables.
 *
 * <p>The connector hands the marker out as a LinkedHashMap, which type validation allows, and
 * stores it Java-serialized, as the agent's own variables: in the agentic ad hoc sub process it
 * applies the output parameters itself; after a service task, once the engine has applied them
 * (CIB7-2819). In the engine's default format, under JSON with Spin's map type detector, which
 * names a map's type after its first value, it would not read back.
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
    SERVICE_TASK, MULTI_INSTANCE_SERVICE_TASK, PARALLEL_MULTI_INSTANCE_SERVICE_TASK,
    AD_HOC_SUB_PROCESS
  }

  private HttpServer stub;
  private ProcessEngine engine;
  /** The expression that maps the marker. */
  private String markerExpression = "${outputAiMeta}";
  /** Output parameters of the agent's connector after the marker's. */
  private String moreOutputs = "";
  /** Elements of the process besides the agent, the gateway and the tasks. */
  private String moreProcess = "";
  /** Attributes of the agent's service task. */
  private String agentAttributes = "";
  /** Beans the model's expressions may call. */
  private final Map<Object, Object> beans = new HashMap<Object, Object>();
  /** Plugins besides connect and Spin. */
  private final List<ProcessEnginePlugin> morePlugins = new ArrayList<ProcessEnginePlugin>();

  static Stream<Arguments> setups() {
    List<Arguments> arguments = new ArrayList<Arguments>();
    for (Setup setup : Setup.values()) {
      for (Activity activity : Activity.values()) {
        arguments.add(Arguments.of(setup, activity));
      }
    }
    return arguments.stream();
  }

  /** Every setup, with the agent as a service task, alone or multi-instance. */
  static Stream<Arguments> agentTaskSetups() {
    List<Arguments> arguments = new ArrayList<Arguments>();
    for (Setup setup : Setup.values()) {
      for (Activity activity : Activity.values()) {
        if (activity != Activity.AD_HOC_SUB_PROCESS) {
          arguments.add(Arguments.of(setup, activity));
        }
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
    ProcessInstance pi = runTheAgent(setup, activity);

    assertTheMarkerReadsBack(pi);
    assertStoredJavaSerialized(pi);
  }

  /** The agent hands the marker itself over to an output parameter however it is mapped. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Setup.class)
  public void theAdHocSubProcessStoresTheMarkerMappedAsADeferredExpression(Setup setup)
      throws IOException {
    markerExpression = "#{outputAiMeta}";
    ProcessInstance pi = runTheAgent(setup, Activity.AD_HOC_SUB_PROCESS);

    assertTheMarkerReadsBack(pi);
    assertStoredJavaSerialized(pi);
  }

  /** So does a service task's response, once the engine has applied the output parameters. */
  @ParameterizedTest(name = "{1} on {0}")
  @MethodSource("agentTaskSetups")
  public void theServiceTaskStoresTheMarkerMappedAsADeferredExpression(Setup setup,
      Activity activity) throws IOException {
    markerExpression = "#{outputAiMeta}";
    ProcessInstance pi = runTheAgent(setup, activity);

    assertTheMarkerReadsBack(pi);
    assertStoredJavaSerialized(pi);
  }

  /**
   * The history of a service task's marker: one row where the default format is Java; under JSON
   * the engine's own write first, then the Java one, which reads back. Under JSON with the map
   * type detector the first row cannot be read back; the variable's history can.
   */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Setup.class)
  public void theHistoryHasTheEnginesWriteThenTheJavaOne(Setup setup) throws IOException {
    ProcessInstance pi = runTheAgent(setup, Activity.SERVICE_TASK);

    List<HistoricVariableUpdate> rows = new ArrayList<HistoricVariableUpdate>();
    for (HistoricDetail detail : engine.getHistoryService().createHistoricDetailQuery()
        .processInstanceId(pi.getId()).variableUpdates().orderPartiallyByOccurrence().asc()
        .list()) {
      HistoricVariableUpdate update = (HistoricVariableUpdate) detail;
      if ("agentOutput_aiMeta".equals(update.getVariableName())) {
        rows.add(update);
      }
    }
    boolean java = setup == Setup.JAVA || setup == Setup.JAVA_VALIDATION;
    assertThat(rows).hasSize(java ? 1 : 2);
    HistoricVariableUpdate last = rows.get(rows.size() - 1);
    assertThat(last.getErrorMessage()).isNull();
    assertThat(((Map<?, ?>) last.getValue()).get("aiGenerated")).isEqualTo(Boolean.TRUE);
  }

  /**
   * An output parameter after the marker's starts a non-interrupting event sub process of the
   * same instance, so the engine replaces the agent's execution while it applies the outputs. The
   * marker is still stored Java-serialized.
   */
  @ParameterizedTest(name = "{0}")
  @EnumSource(value = Setup.class, names = {"JSON", "JSON_MAP_DETECTOR"})
  public void theMarkerReadsBackWhenAnOutputReplacesTheAgentsExecution(Setup setup)
      throws IOException {
    beans.put("sideEvent", new SideEvent());
    // In a job, so that the side event's subscription is in the database when the message comes.
    agentAttributes = " camunda:asyncBefore='true'";
    moreOutputs = "<camunda:outputParameter name='sideStarted'>${sideEvent.start()}"
        + "</camunda:outputParameter>";
    moreProcess = "<subProcess id='side' triggeredByEvent='true'>"
        + "<startEvent id='sideStart' isInterrupting='false'>"
        + "<messageEventDefinition messageRef='sideMessage' /></startEvent>"
        + "<sequenceFlow id='s1' sourceRef='sideStart' targetRef='sideTask' />"
        + "<userTask id='sideTask' name='Side' /></subProcess>";
    ProcessInstance pi = runTheAgent(setup, Activity.SERVICE_TASK);

    assertThat(engine.getTaskService().createTaskQuery().processInstanceId(pi.getId())
        .taskDefinitionKey("sideTask").count()).as("the side event ran").isEqualTo(1);
    assertTheMarkerReadsBack(pi);
    assertStoredJavaSerialized(pi);
  }

  /**
   * Where the engine has no Java serializer, the marker is left as the engine stored it, not
   * cleared by a write that fails.
   */
  @Test
  public void withoutAJavaSerializerTheMarkerStaysAsTheEngineStoredIt() throws IOException {
    morePlugins.add(new WithoutJavaSerializer());
    ProcessInstance pi = runTheAgent(Setup.JSON, Activity.SERVICE_TASK);

    assertTheMarkerReadsBack(pi);
    ObjectValue stored = (ObjectValue) engine.getRuntimeService()
        .getVariableTyped(pi.getId(), "agentOutput_aiMeta", false);
    assertThat(stored.getSerializationDataFormat())
        .isEqualTo(Variables.SerializationDataFormats.JSON.getName());
  }

  /**
   * Runs the agent. The gateway right after it asks the marker in the same command that stored it;
   * the instance then waits in Review only if it read {@code aiGenerated} as true.
   */
  private ProcessInstance runTheAgent(Setup setup, Activity activity) throws IOException {
    startStub();
    startEngine(setup, activity);
    ProcessInstance pi = engine.getRuntimeService().startProcessInstanceByKey("marker");
    // The ad hoc sub process runs its turn as jobs; the service task has run already.
    TurnSteps.runToTheEnd(engine, pi.getId());
    assertThat(engine.getTaskService().createTaskQuery().processInstanceId(pi.getId())
        .taskDefinitionKey("notMarked").count()).as("the gateway read the marker").isZero();
    Task review = engine.getTaskService().createTaskQuery().processInstanceId(pi.getId())
        .taskDefinitionKey("review").singleResult();
    assertThat(review).as("the gateway read the marker").isNotNull();
    return pi;
  }

  /** In a later command: the marker, all of the instance's variables, the task's, the history. */
  private void assertTheMarkerReadsBack(ProcessInstance pi) {
    Object marker = engine.getRuntimeService().getVariable(pi.getId(), "agentOutput_aiMeta");
    assertThat(marker).isInstanceOf(Map.class);
    assertThat(((Map<?, ?>) marker).get("aiGenerated")).isEqualTo(Boolean.TRUE);
    assertThat(((Map<?, ?>) marker).get("generatedAt")).isInstanceOf(String.class);

    assertThat(engine.getRuntimeService().getVariables(pi.getId()))
        .as("all of the instance's variables").containsKey("agentOutput_aiMeta");
    Task review = engine.getTaskService().createTaskQuery().processInstanceId(pi.getId())
        .taskDefinitionKey("review").singleResult();
    assertThat(engine.getFormService().getTaskFormVariables(review.getId()))
        .as("the task's form variables").containsKey("agentOutput_aiMeta");
    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
        .processInstanceId(pi.getId()).variableName("agentOutput_aiMeta").singleResult().getValue())
        .as("the marker in the history").isEqualTo(marker);
  }

  /** Java-serialized after either activity, whatever the engine's default format. */
  private void assertStoredJavaSerialized(ProcessInstance pi) {
    ObjectValue stored = (ObjectValue) engine.getRuntimeService()
        .getVariableTyped(pi.getId(), "agentOutput_aiMeta", false);
    assertThat(stored.getSerializationDataFormat())
        .isEqualTo(Variables.SerializationDataFormats.JAVA.getName());
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
    plugins.addAll(morePlugins);
    configuration.setProcessEnginePlugins(plugins);
    if (!beans.isEmpty()) {
      configuration.setBeans(beans);
    }
    engine = configuration.buildProcessEngine();
    engine.getRepositoryService().createDeployment()
        .addString("marker.bpmn20.xml", model(activity)).deploy();
  }

  /**
   * The agent, with the outputs the element templates map; then a gateway that asks the marker,
   * and a task to wait in.
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
        + "<camunda:outputParameter name='agentOutput_aiMeta'>" + markerExpression
        + "</camunda:outputParameter>"
        + moreOutputs
        + "</camunda:inputOutput>"
        + "</camunda:connector></extensionElements>";
    String agent;
    if (activity == Activity.SERVICE_TASK) {
      agent = "<serviceTask id='agent' name='Agent'" + agentAttributes + ">" + connector
          + "</serviceTask>";
    } else if (activity == Activity.MULTI_INSTANCE_SERVICE_TASK
        || activity == Activity.PARALLEL_MULTI_INSTANCE_SERVICE_TASK) {
      agent = "<serviceTask id='agent' name='Agent'>" + connector
          + "<multiInstanceLoopCharacteristics isSequential='"
          + (activity == Activity.MULTI_INSTANCE_SERVICE_TASK) + "'>"
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
        + "<message id='sideMessage' name='sideMessage' />"
        + "<process id='marker' isExecutable='true'>"
        + "<startEvent id='start' />"
        + "<sequenceFlow id='f1' sourceRef='start' targetRef='agent' />"
        + agent
        + "<sequenceFlow id='f2' sourceRef='agent' targetRef='marked' />"
        + "<exclusiveGateway id='marked' default='f4' />"
        + "<sequenceFlow id='f3' sourceRef='marked' targetRef='review'>"
        + "<conditionExpression xsi:type='tFormalExpression'>${agentOutput_aiMeta.aiGenerated}"
        + "</conditionExpression></sequenceFlow>"
        + "<sequenceFlow id='f4' sourceRef='marked' targetRef='notMarked' />"
        + "<userTask id='review' name='Review' />"
        + "<userTask id='notMarked' name='Not marked' />"
        + moreProcess
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

  /** Starts the side event of the same instance, from an expression of the agent's outputs. */
  public static class SideEvent {
    public String start() {
      Context.getProcessEngineConfiguration().getRuntimeService().correlateMessage("sideMessage");
      return "started";
    }
  }

  /** A host that took the Java serializer out of the engine. */
  protected static class WithoutJavaSerializer extends AbstractProcessEnginePlugin {
    @Override
    public void postInit(ProcessEngineConfigurationImpl processEngineConfiguration) {
      VariableSerializers serializers = processEngineConfiguration.getVariableSerializers();
      serializers.removeSerializer(serializers.getSerializerByName(JavaObjectSerializer.NAME));
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
