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
package org.cibseven.connect.plugin.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The function {@code toolParameter} with the connectors only, as on an engine whose AI agent is
 * switched off (CIB7-2136): the AI agent is not on this module's class path.
 */
public class ToolParameterFunctionTest {

  private static final String MODEL = "<?xml version='1.0' encoding='UTF-8'?>"
      + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
      + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
      + "             targetNamespace='http://cibseven.org/tool-parameter'>"
      + "  <process id='toolParameterWithoutAgent' isExecutable='true' camunda:historyTimeToLive='P1D'>"
      + "    <startEvent id='start' />"
      + "    <sequenceFlow id='f1' sourceRef='start' targetRef='freigeben' />"
      + "    <userTask id='freigeben' name='Freigeben'><extensionElements><camunda:inputOutput>"
      + "      <camunda:inputParameter name='vorschlag'>"
      + "${toolParameter(toolCall.betrag, 'Betrag in Euro', 'integer')}</camunda:inputParameter>"
      + "      <camunda:inputParameter name='warum'>${toolParameter(toolCall.grund, 'Grund')}"
      + "</camunda:inputParameter>"
      + "      <camunda:inputParameter name='notiz'>${toolParameter(toolCall.notiz)}"
      + "</camunda:inputParameter>"
      + "    </camunda:inputOutput></extensionElements></userTask>"
      + "    <sequenceFlow id='f2' sourceRef='freigeben' targetRef='end' />"
      + "    <endEvent id='end' />"
      + "  </process>"
      + "</definitions>";

  private ProcessEngine engine;

  @AfterEach
  public void closeEngine() {
    if (engine != null) {
      engine.close();
    }
  }

  @Test
  public void aValueIsHandedOnUnchanged() {
    assertThat(ToolParameterFunction.toolParameter("Rabatt")).isEqualTo("Rabatt");
    assertThat(ToolParameterFunction.toolParameter("Rabatt", "Grund")).isEqualTo("Rabatt");
    assertThat(ToolParameterFunction.toolParameter(null, "Betrag", "integer")).isNull();
    assertThat(ToolParameterFunction.toolParameter(true, "Ok", "boolean")).isEqualTo(true);
  }

  /** A map read back from JSON may hold an Integer where the call set a Long. */
  @Test
  public void aNumberTakesTheJavaTypeOfItsDeclaredType() {
    assertThat(ToolParameterFunction.toolParameter(1200, "Betrag", "integer")).isEqualTo(1200L);
    assertThat(ToolParameterFunction.toolParameter(89, "Betrag", "number")).isEqualTo(89.0d);
    assertThat(ToolParameterFunction.toolParameter(1200, "Betrag", " INTEGER ")).isEqualTo(1200L);
    assertThat(ToolParameterFunction.toolParameter(1200.0, "Betrag", "integer")).isEqualTo(1200L);
  }

  /** An optional parameter's type, with its trailing '?', converts as the plain type does. */
  @Test
  public void anOptionalTypeConvertsAsThePlainType() {
    assertThat(ToolParameterFunction.toolParameter(1200, "Betrag", "integer?")).isEqualTo(1200L);
    assertThat(ToolParameterFunction.toolParameter(89, "Betrag", "number?")).isEqualTo(89.0d);
    assertThat(ToolParameterFunction.toolParameter(1200, "Betrag", " Integer ? ")).isEqualTo(1200L);
    assertThat(ToolParameterFunction.toolParameter(12.5, "Betrag", "integer?")).isEqualTo(12.5);
  }

  /** Nothing is cut: a fraction, or a number beyond a Long, goes on as it came. */
  @Test
  public void aNumberThatIsNoLongIsHandedOnUnchanged() {
    assertThat(ToolParameterFunction.toolParameter(12.5, "Betrag", "integer")).isEqualTo(12.5);
    BigInteger huge = BigInteger.ONE.shiftLeft(64);
    assertThat(ToolParameterFunction.toolParameter(huge, "Betrag", "integer")).isSameAs(huge);
    assertThat(ToolParameterFunction.toolParameter(Double.NaN, "Betrag", "integer"))
        .isEqualTo(Double.NaN);
  }

  /** The second argument is the description; only a third one is the type. */
  @Test
  public void onlyANamedTypeConverts() {
    assertThat(ToolParameterFunction.toolParameter(1200, "integer")).isEqualTo(1200);
    assertThat(ToolParameterFunction.toolParameter(1200, "Betrag", null)).isEqualTo(1200);
  }

  /**
   * Without the AI agent a model using the function, in each of its three forms, deploys, its
   * mappings hand the values on, and after the definition left the cache, as after a restart, it
   * is parsed again and the waiting instance goes on.
   */
  @Test
  public void aModelUsingItDeploysRunsAndIsParsedAgainWithoutTheAgent() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName("tool-parameter-without-agent");
    configuration.setJdbcUrl("jdbc:h2:mem:tool-parameter-without-agent;DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    configuration.setProcessEnginePlugins(
        Collections.singletonList(new ConnectProcessEnginePlugin()));
    engine = configuration.buildProcessEngine();

    engine.getRepositoryService().createDeployment()
        .addString("tool-parameter.bpmn20.xml", MODEL).deploy();
    Map<String, Object> toolCall = new TreeMap<String, Object>();
    toolCall.put("betrag", 1200);
    toolCall.put("grund", "Rabatt");
    toolCall.put("notiz", "Termin");
    ProcessInstance pi = engine.getRuntimeService().startProcessInstanceByKey(
        "toolParameterWithoutAgent", Collections.<String, Object>singletonMap("toolCall", toolCall));

    Task task = engine.getTaskService().createTaskQuery().singleResult();
    assertThat(engine.getTaskService().getVariable(task.getId(), "vorschlag")).isEqualTo(1200L);
    assertThat(engine.getTaskService().getVariable(task.getId(), "warum"))
        .as("the call with a description only").isEqualTo("Rabatt");
    assertThat(engine.getTaskService().getVariable(task.getId(), "notiz"))
        .as("the call with the value only").isEqualTo("Termin");

    ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration()).getDeploymentCache()
        .discardProcessDefinitionCache();
    engine.getTaskService().complete(task.getId());

    assertThat(engine.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(pi.getId()).count()).as("the instance went on to its end").isZero();
  }
}
