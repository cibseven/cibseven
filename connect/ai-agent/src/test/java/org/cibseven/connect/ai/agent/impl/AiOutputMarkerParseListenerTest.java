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

import java.util.Collections;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.impl.RepositoryServiceImpl;
import org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.core.variable.mapping.IoMapping;
import org.cibseven.bpm.engine.impl.core.variable.mapping.OutputParameter;
import org.cibseven.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.cibseven.connect.ai.agent.agentic.AgenticAdHocParseListener;
import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.cibseven.connect.plugin.impl.ServiceTaskConnectorActivityBehavior;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Which output parameters store the AI-output marker through {@link AiOutputMarker} (CIB7-2819):
 * those of the AI agent connector that map {@code ${outputAiMeta}} as it is, and no other.
 */
public class AiOutputMarkerParseListenerTest {

  private ProcessEngine engine;
  private ProcessDefinitionEntity definition;

  @BeforeEach
  public void deploy() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName("ai-output-marker-parse");
    configuration.setJdbcUrl("jdbc:h2:mem:ai-output-marker-parse;DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    configuration.setHistoryTimeToLive("P30D");
    configuration.setProcessEnginePlugins(
        Collections.<ProcessEnginePlugin>singletonList(new ConnectProcessEnginePlugin()));
    engine = configuration.buildProcessEngine();
    engine.getRepositoryService().createDeployment()
        .addString("parsed.bpmn20.xml", model()).deploy();
    String id = engine.getRepositoryService().createProcessDefinitionQuery()
        .processDefinitionKey("parsed").singleResult().getId();
    definition = (ProcessDefinitionEntity) ((RepositoryServiceImpl) engine.getRepositoryService())
        .getDeployedProcessDefinition(id);
  }

  @AfterEach
  public void close() {
    if (engine != null) {
      engine.close();
    }
  }

  @Test
  public void theMarkerAsItIsIsStoredThroughTheHelper() {
    IoMapping io = connectorIo("agent");

    assertThat(storesTheMarker(io, "asItIs")).isTrue();
    assertThat(storesTheMarker(io, "withBlanks")).isTrue();
  }

  @Test
  public void everyOtherOutputParameterStaysAsItIs() {
    IoMapping io = connectorIo("agent");

    assertThat(storesTheMarker(io, "flag")).as("${outputAiMeta.aiGenerated}").isFalse();
    assertThat(storesTheMarker(io, "answer")).as("${output}").isFalse();
    assertThat(storesTheMarker(io, "inAList")).as("the marker inside a list").isFalse();
    assertThat(storesTheMarker(io, "inAnExpression"))
        .as("the marker inside a longer expression").isFalse();
    assertThat(storesTheMarker(connectorIo("otherConnector"), "marker"))
        .as("another connector's outputAiMeta").isFalse();
  }

  @Test
  public void aSendTaskAndAMultiInstanceTaskStoreTheMarkerThroughTheHelper() {
    assertThat(storesTheMarker(connectorIo("send"), "marker")).isTrue();
    assertThat(storesTheMarker(connectorIo("multi"), "marker")).isTrue();
  }

  @Test
  public void theAgentOfAnAdHocSubProcessStoresTheMarkerThroughTheHelper() {
    IoMapping io = definition.findActivity("adHoc").getProperties()
        .get(AgenticAdHocParseListener.AGENT_CONNECTOR_IO);

    assertThat(storesTheMarker(io, "marker")).isTrue();
    assertThat(storesTheMarker(io, "answer")).isFalse();
  }

  @Test
  public void aTaskWithoutTheConnectorsParametersParses() {
    assertThat(definition.findActivity("expression")).isNotNull();
    assertThat(definition.findActivity("noParameters")).isNotNull();
  }

  private IoMapping connectorIo(String activityId) {
    return ((ServiceTaskConnectorActivityBehavior) definition.findActivity(activityId)
        .getActivityBehavior()).getIoMapping();
  }

  private static boolean storesTheMarker(IoMapping io, String name) {
    for (OutputParameter parameter : io.getOutputParameters()) {
      if (parameter.getName().equals(name)) {
        return parameter.getValueProvider() instanceof AiOutputMarker.MarkerValueProvider;
      }
    }
    throw new AssertionError("no output parameter " + name);
  }

  private static String connector(String connectorId, String outputs) {
    return "<extensionElements><camunda:connector>"
        + "<camunda:connectorId>" + connectorId + "</camunda:connectorId>"
        + "<camunda:inputOutput>"
        + "<camunda:inputParameter name='agentName'>Pruefer</camunda:inputParameter>"
        + "<camunda:inputParameter name='message'>Pruefe.</camunda:inputParameter>"
        + "<camunda:inputParameter name='model'>stub</camunda:inputParameter>"
        + "<camunda:inputParameter name='apiKey'>stub-key</camunda:inputParameter>"
        + "<camunda:inputParameter name='baseUrl'>http://127.0.0.1:1/v1</camunda:inputParameter>"
        + outputs
        + "</camunda:inputOutput></camunda:connector></extensionElements>";
  }

  private static String model() {
    String marker = "<camunda:outputParameter name='marker'>${outputAiMeta}"
        + "</camunda:outputParameter>";
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + " xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + " targetNamespace='http://cibseven.org/ai-output-marker'>"
        + "<process id='parsed' isExecutable='true'>"
        + "<startEvent id='start' />"
        + "<sequenceFlow id='f1' sourceRef='start' targetRef='agent' />"
        + "<serviceTask id='agent'>" + connector("cibseven-ai-agent",
            "<camunda:outputParameter name='asItIs'>${outputAiMeta}</camunda:outputParameter>"
            + "<camunda:outputParameter name='withBlanks'>${ outputAiMeta }"
            + "</camunda:outputParameter>"
            + "<camunda:outputParameter name='flag'>${outputAiMeta.aiGenerated}"
            + "</camunda:outputParameter>"
            + "<camunda:outputParameter name='answer'>${output}</camunda:outputParameter>"
            + "<camunda:outputParameter name='inAList'><camunda:list>"
            + "<camunda:value>${outputAiMeta}</camunda:value></camunda:list>"
            + "</camunda:outputParameter>"
            + "<camunda:outputParameter name='inAnExpression'>"
            + "${outputAiMeta == null ? null : outputAiMeta}</camunda:outputParameter>")
        + "</serviceTask>"
        + "<sequenceFlow id='f2' sourceRef='agent' targetRef='otherConnector' />"
        + "<serviceTask id='otherConnector'>" + connector("http-connector", marker)
        + "</serviceTask>"
        + "<sequenceFlow id='f3' sourceRef='otherConnector' targetRef='send' />"
        + "<sendTask id='send'>" + connector("cibseven-ai-agent", marker) + "</sendTask>"
        + "<sequenceFlow id='f4' sourceRef='send' targetRef='multi' />"
        + "<serviceTask id='multi'>" + connector("cibseven-ai-agent", marker)
        + "<multiInstanceLoopCharacteristics isSequential='true'>"
        + "<loopCardinality>2</loopCardinality></multiInstanceLoopCharacteristics>"
        + "</serviceTask>"
        + "<sequenceFlow id='f5' sourceRef='multi' targetRef='expression' />"
        + "<serviceTask id='expression' camunda:expression='${true}' />"
        + "<sequenceFlow id='f6' sourceRef='expression' targetRef='noParameters' />"
        + "<serviceTask id='noParameters'><extensionElements><camunda:connector>"
        + "<camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "</camunda:connector></extensionElements></serviceTask>"
        + "<sequenceFlow id='f7' sourceRef='noParameters' targetRef='adHoc' />"
        + "<adHocSubProcess id='adHoc'>" + connector("cibseven-ai-agent", marker
            + "<camunda:outputParameter name='answer'>${output}</camunda:outputParameter>")
        + "<userTask id='worker' name='Worker' /></adHocSubProcess>"
        + "<sequenceFlow id='f8' sourceRef='adHoc' targetRef='end' />"
        + "<endEvent id='end' />"
        + "</process>"
        + "</definitions>";
  }
}
