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
package org.cibseven.connect.ai.agent.agentic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.impl.RepositoryServiceImpl;
import org.cibseven.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.impl.pvm.process.ProcessDefinitionImpl;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.cibseven.connect.plugin.impl.ToolParameterFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * A model deployed while the AI agent was off, parsed again once it is on (CIB7-2136): after a
 * restart, a cache eviction, or on another node of the same database. What the checks of tool
 * parameters would refuse at deployment is only logged then, and the model loads, so its
 * instances go on. A new deployment of the same model is still refused.
 *
 * <p>Two engines on one database: one without the agent, as with it switched off, which has the
 * function {@code toolParameter} as the connect plugin registers it, and one with the agent.
 */
public class AgenticReparseTest {

  protected final List<ProcessEngine> engines = new ArrayList<ProcessEngine>();
  protected String database;
  protected ListAppender<ILoggingEvent> log;

  @AfterEach
  public void tearDown() {
    for (ProcessEngine engine : engines) {
      engine.close();
    }
    if (log != null) {
      ((Logger) LoggerFactory.getLogger(AgenticToolCatalogParser.class)).detachAppender(log);
    }
  }

  /** A plain process that uses the function outside every agent, deployed with the agent off. */
  @Test
  public void aPlainModelDeployedWithTheAgentOffGoesOnOnceTheAgentIsOn(TestInfo test) {
    ProcessEngine off = engineWithoutTheAgent(test);
    String model = process("plainProcess", "<userTask id='plain'><extensionElements>"
        + "<camunda:inputOutput><camunda:inputParameter name='vorschlag'>"
        + "${toolParameter(toolCall.betrag)}</camunda:inputParameter></camunda:inputOutput>"
        + "</extensionElements></userTask>");
    deploy(off, model);
    ProcessInstance pi = off.getRuntimeService().startProcessInstanceByKey("plainProcess",
        Collections.<String, Object>singletonMap("toolCall",
            Collections.<String, Object>singletonMap("betrag", 5L)));
    ProcessEngine on = engineWithTheAgent(test);
    recordWarnings();

    Task task = on.getTaskService().createTaskQuery().singleResult();
    on.getTaskService().complete(task.getId());

    assertThat(on.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(pi.getId()).count()).as("the instance went on, and ended").isZero();
    assertThat(warnings()).anySatisfy(warning -> assertThat(warning)
        .contains("Process 'plainProcess' is parsed again, not deployed, and a deployment of it"
            + " would now be refused: Process 'plainProcess', element 'plain':"
            + " toolParameter(...) in inputParameter 'vorschlag' declares nothing.")
        .contains("so it loads as it is. Deploy a corrected version."));
    assertThatThrownBy(() -> deploy(on, model)).as("a new deployment is still refused")
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("toolParameter(...) in inputParameter 'vorschlag' declares nothing");
  }

  /**
   * An agentic scope deployed with the agent off was a plain ad hoc scope. Parsed again with the
   * agent, a declaration that would refuse a deployment is left out of the catalogue, and a
   * misspelt read is only logged; the other declarations are the tools' parameters.
   */
  @Test
  public void aDeclarationThatWouldBeRefusedIsLeftOutOfTheCatalogue(TestInfo test) {
    ProcessEngine off = engineWithoutTheAgent(test);
    deploy(off, process("agenticScope", "<adHocSubProcess id='adHoc'><extensionElements>"
        + "<camunda:connector><camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "<camunda:inputOutput><camunda:inputParameter name='message'>Do the work."
        + "</camunda:inputParameter></camunda:inputOutput></camunda:connector>"
        + "</extensionElements>"
        + "<userTask id='zaehlen'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='a'>${toolParameter(toolCall.menge, '', 'money')}"
        + "</camunda:inputParameter>"
        + "<camunda:inputParameter name='b'>${toolParameter(toolCall.preis, '', 'number')}"
        + "</camunda:inputParameter>"
        + "<camunda:inputParameter name='c'>${toolCall.prei}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask></adHocSubProcess>"));
    String definitionId = off.getRepositoryService().createProcessDefinitionQuery()
        .singleResult().getId();
    ProcessEngine on = engineWithTheAgent(test);
    recordWarnings();

    List<AdHocToolDescriptor> catalog = catalogOf(on, definitionId, "adHoc");

    assertThat(catalog).hasSize(1);
    assertThat(catalog.get(0).getParameters()).extracting(AdHocToolDescriptor.Parameter::getName)
        .as("'menge' with its unknown type is left out").containsExactly("preis");
    assertThat(warnings()).anySatisfy(warning -> assertThat(warning)
        .contains("Process 'agenticScope' is parsed again")
        .contains("the type 'money' is not supported"));
    assertThat(warnings()).anySatisfy(warning -> assertThat(warning)
        .contains("toolCall.prei is read, but no toolParameter(toolCall.prei, ...) of this tool"
            + " declares it."));
  }

  /**
   * A declaration left out on a parse again leaves nothing behind: the next declaration of the
   * name is judged against the ones before it, and here gives the type.
   */
  @Test
  public void aDeclarationLeftOutLeavesTheNextOneWhole(TestInfo test) {
    ProcessEngine off = engineWithoutTheAgent(test);
    deploy(off, process("agenticScope", "<adHocSubProcess id='adHoc'><extensionElements>"
        + "<camunda:connector><camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "<camunda:inputOutput><camunda:inputParameter name='message'>Do the work."
        + "</camunda:inputParameter></camunda:inputOutput></camunda:connector>"
        + "</extensionElements>"
        + "<userTask id='zaehlen'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='a'>${toolParameter(toolCall.menge, 'Stück')}"
        + "</camunda:inputParameter>"
        + "<camunda:inputParameter name='b'>${toolParameter(toolCall.menge, 'Kisten', 'integer')}"
        + "</camunda:inputParameter>"
        + "<camunda:inputParameter name='c'>${toolParameter(toolCall.menge, 'Stück', 'integer')}"
        + "</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask></adHocSubProcess>"));
    String definitionId = off.getRepositoryService().createProcessDefinitionQuery()
        .singleResult().getId();
    ProcessEngine on = engineWithTheAgent(test);
    recordWarnings();

    AdHocToolDescriptor.Parameter menge = catalogOf(on, definitionId, "adHoc").get(0)
        .getParameters().get(0);

    assertThat(menge.getType()).as("given by the third declaration").isEqualTo("integer");
    assertThat(menge.getDescription()).isEqualTo("Stück");
    assertThat(warnings()).anySatisfy(warning -> assertThat(warning)
        .contains("parameter 'menge' is declared with two descriptions, 'Stück' and 'Kisten'"));
    assertThat(warnings()).noneSatisfy(warning -> assertThat(warning).contains("two types"));
  }

  /**
   * Every check of tool parameters only logs on a parse again: the reserved name, two types for
   * one parameter, an unknown type, a call where the catalogue does not read it, and a misspelt
   * read further along a tool's flow. A declaration refused there is left out, and with it what
   * follows it in the same input; the rest is the tools' parameters.
   */
  @Test
  public void everyCheckOfToolParametersOnlyLogsOnAParseAgain(TestInfo test) {
    ProcessEngine off = engineWithoutTheAgent(test);
    deploy(off, process("agenticScope", "<adHocSubProcess id='adHoc'><extensionElements>"
        + "<camunda:connector><camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "<camunda:inputOutput><camunda:inputParameter name='message'>Do the work."
        + "</camunda:inputParameter></camunda:inputOutput></camunda:connector>"
        + "</extensionElements>"
        + "<userTask id='a'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='r'>${toolParameter(toolCall.toolCall)}"
        + "</camunda:inputParameter>"
        + "<camunda:inputParameter name='s'>${toolParameter(toolCall.p, 'P')}"
        + "</camunda:inputParameter>"
        + "</camunda:inputOutput>"
        + "<camunda:executionListener event='start' expression=\"${toolParameter(toolCall.q)}\" />"
        + "</extensionElements></userTask>"
        + "<userTask id='b'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='i'>${toolParameter(toolCall.x, '', 'integer')}"
        + "</camunda:inputParameter>"
        + "<camunda:inputParameter name='j'>${toolParameter(toolCall.x, '', 'string')}"
        + "</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>"
        + "<userTask id='c'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='k'>${toolParameter(toolCall.y, '', 'money')}"
        + " ${toolParameter(toolCall.z)}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>"
        + "<sequenceFlow id='weiter' sourceRef='b' targetRef='danach' />"
        + "<userTask id='danach'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='l'>${toolCall.xx}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask></adHocSubProcess>"));
    String definitionId = off.getRepositoryService().createProcessDefinitionQuery()
        .singleResult().getId();
    ProcessEngine on = engineWithTheAgent(test);
    recordWarnings();

    List<AdHocToolDescriptor> catalog = catalogOf(on, definitionId, "adHoc");

    assertThat(catalog).extracting(AdHocToolDescriptor::getActivityId)
        .containsExactly("a", "b", "c");
    assertThat(catalog.get(0).getParameters()).extracting(AdHocToolDescriptor.Parameter::getName)
        .as("the reserved name is left out").containsExactly("p");
    assertThat(catalog.get(1).getParameters()).extracting(AdHocToolDescriptor.Parameter::getType)
        .as("the first type stays").containsExactly("integer");
    assertThat(catalog.get(2).getParameters())
        .as("the unknown type is left out, and the call after it in the same input").isEmpty();
    assertThat(String.join("\n", warnings()))
        .contains("'toolCall' cannot be a parameter name")
        .contains("'x' is declared with two types, 'integer' and 'string'")
        .contains("the type 'money' is not supported")
        .contains("toolParameter(...) in the attribute expression of executionListener declares"
            + " nothing")
        .contains("child 'danach': toolCall.xx is read");
  }

  /**
   * What a tool's outputs do not hand out is named when the model is deployed, and not again each
   * time it is parsed again, on every start and on every node.
   */
  @Test
  public void theWarningForResultsTheOutputsDoNotHandOutComesOnce(TestInfo test) {
    ProcessEngine on = engineWithTheAgent(test);
    recordWarnings();
    deploy(on, process("agenticScope", "<adHocSubProcess id='adHoc'><extensionElements>"
        + "<camunda:connector><camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "<camunda:inputOutput><camunda:inputParameter name='message'>Do the work."
        + "</camunda:inputParameter></camunda:inputOutput></camunda:connector>"
        + "</extensionElements>"
        + "<userTask id='freigeben'><extensionElements><camunda:inputOutput>"
        + "<camunda:outputParameter name='freigegeben'>${entscheidung == 'ja'}"
        + "</camunda:outputParameter></camunda:inputOutput><camunda:formData>"
        + "<camunda:formField id='entscheidung' type='string' />"
        + "</camunda:formData></extensionElements></userTask></adHocSubProcess>"));
    String definitionId = on.getRepositoryService().createProcessDefinitionQuery()
        .singleResult().getId();
    assertThat(warnings()).filteredOn(warning -> warning.contains("its output parameters"))
        .hasSize(1);

    ((ProcessEngineConfigurationImpl) on.getProcessEngineConfiguration()).getDeploymentCache()
        .discardProcessDefinitionCache();
    catalogOf(on, definitionId, "adHoc");

    assertThat(warnings()).filteredOn(warning -> warning.contains("its output parameters"))
        .as("not again on a parse again").hasSize(1);
  }

  protected ProcessEngine engineWithoutTheAgent(TestInfo test) {
    return engine(test, "off", new AbstractProcessEnginePlugin() {
      @Override
      public void postInit(ProcessEngineConfigurationImpl configuration) {
        configuration.getExpressionManager().addFunction(ToolParameterFunction.NAME,
            ToolParameterFunction.method());
      }
    });
  }

  protected ProcessEngine engineWithTheAgent(TestInfo test) {
    return engine(test, "on", new ConnectProcessEnginePlugin());
  }

  /** An engine on this test's database, which the first one creates and the others share. */
  protected ProcessEngine engine(TestInfo test, String name, ProcessEnginePlugin plugin) {
    if (database == null) {
      database = "agentic-reparse-" + test.getTestMethod().get().getName();
    }
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName(database + "-" + name);
    configuration.setJdbcUrl("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
    configuration.setDatabaseSchemaUpdate(ProcessEngineConfigurationImpl.DB_SCHEMA_UPDATE_TRUE);
    configuration.setJobExecutorActivate(false);
    configuration.setEnforceHistoryTimeToLive(false);
    configuration.setProcessEnginePlugins(Collections.singletonList(plugin));
    ProcessEngine engine = configuration.buildProcessEngine();
    engines.add(engine);
    return engine;
  }

  protected void recordWarnings() {
    log = new ListAppender<ILoggingEvent>();
    log.start();
    ((Logger) LoggerFactory.getLogger(AgenticToolCatalogParser.class)).addAppender(log);
  }

  protected List<String> warnings() {
    List<String> messages = new ArrayList<String>();
    for (ILoggingEvent event : log.list) {
      messages.add(event.getFormattedMessage());
    }
    return messages;
  }

  /** The catalogue of the scope as the engine parses the definition, from its cache or again. */
  protected static List<AdHocToolDescriptor> catalogOf(ProcessEngine engine, String definitionId,
      String scopeId) {
    ProcessDefinitionImpl definition = (ProcessDefinitionImpl) ((RepositoryServiceImpl) engine
        .getRepositoryService()).getDeployedProcessDefinition(definitionId);
    return ((ActivityImpl) definition.findActivity(scopeId)).getProperties()
        .get(AdHocToolDescriptor.CATALOG);
  }

  protected static void deploy(ProcessEngine engine, String model) {
    engine.getRepositoryService().createDeployment().addString("reparse.bpmn20.xml", model)
        .deploy();
  }

  protected static String process(String processId, String scope) {
    String first = scope.contains("id='plain'") ? "plain" : "adHoc";
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + "             targetNamespace='http://cibseven.org/agentic'>"
        + "  <process id='" + processId + "' isExecutable='true'>"
        + "    <startEvent id='start' />"
        + "    <sequenceFlow id='f1' sourceRef='start' targetRef='" + first + "' />"
        + scope
        + "    <sequenceFlow id='f2' sourceRef='" + first + "' targetRef='end' />"
        + "    <endEvent id='end' />"
        + "  </process>"
        + "</definitions>";
  }
}
