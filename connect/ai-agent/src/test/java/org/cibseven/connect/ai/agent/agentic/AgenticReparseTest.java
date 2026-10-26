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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.impl.RepositoryServiceImpl;
import org.cibseven.bpm.engine.impl.bpmn.helper.BpmnProperties;
import org.cibseven.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.impl.pvm.process.ProcessDefinitionImpl;
import org.cibseven.bpm.engine.runtime.ActivityInstance;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.connect.ai.agent.AgentConnectorConstants;
import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.cibseven.connect.plugin.impl.ToolParameterFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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
 * <p>So do the agent's other checks (CIB7-2367): a scope no agent can run loads as the plain scope
 * it was deployed as, as does one whose every tool is broken, and a broken tool or setting of an
 * agentic scope is left out.
 *
 * <p>Two engines on one database: one without the agent, as with it switched off, which has the
 * function {@code toolParameter} as the connect plugin registers it, and one with the agent.
 */
public class AgenticReparseTest {

  /** One database per test, and per invocation of a parameterized one. */
  protected static final AtomicInteger DATABASES = new AtomicInteger();

  protected static final String MESSAGE =
      "<camunda:inputParameter name='message'>Do the work.</camunda:inputParameter>";

  /** The classes whose warnings a test reads. */
  protected static final List<Class<?>> LOGGING =
      Arrays.<Class<?>>asList(AgenticToolCatalogParser.class, AgenticTurnRetry.class);

  protected final List<ProcessEngine> engines = new ArrayList<ProcessEngine>();
  protected String database;
  protected ListAppender<ILoggingEvent> log;

  @AfterEach
  public void tearDown() throws SQLException {
    for (ProcessEngine engine : engines) {
      engine.close();
    }
    // DB_CLOSE_DELAY=-1 keeps the database once its engines are closed; one per test would
    // otherwise pile up until the JVM ends.
    if (database != null) {
      try (Connection connection =
          DriverManager.getConnection("jdbc:h2:mem:" + database, "sa", "")) {
        connection.createStatement().execute("SHUTDOWN");
      }
    }
    if (log != null) {
      for (Class<?> logging : LOGGING) {
        ((Logger) LoggerFactory.getLogger(logging)).detachAppender(log);
      }
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

  /**
   * Each check of the agent a deployment would refuse (CIB7-2367), on a model deployed with the
   * agent off. Parsed again with the agent on, it only logs; the model loads, its instances are
   * read, go on and are deleted, and a new deployment of it is still refused.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("refusals")
  public void aModelEveryCheckWouldRefuseLoadsOnceTheAgentIsOn(String refusal, String scope,
      String refused, String instead, Consumer<ActivityImpl> loaded, TestInfo test) {
    loadsOnceTheAgentIsOn(test, scope, refused, instead, refused, loaded);
  }

  /**
   * A scope whose every tool is left out on a parse again would leave its agent nothing to do
   * but end the scope (CIB7-2367): it loads without the agent, as it was deployed. Its settings
   * are not judged then, so the log tells of no setting left out of an agent it does not have.
   */
  @Test
  public void aScopeWithEveryToolLeftOutLoadsWithoutTheAgent(TestInfo test) {
    loadsOnceTheAgentIsOn(test,
        adHoc(properties(AgenticAdHocParseListener.ON_LIMIT, "fail") + connector(MESSAGE),
            "<userTask id='pruefen.alt' /><userTask id='completeScope' />"),
        "Ad hoc sub process 'adHoc': none of its startable children",
        "'adHoc' loads as it was deployed, without the agent",
        "the id 'pruefen.alt' cannot be a tool name", withoutTheAgent());
    assertThat(warnings()).anySatisfy(warning -> assertThat(warning)
        .contains("so the tool 'pruefen.alt' is left out of the agent's catalogue."));
    assertThat(warnings()).noneSatisfy(warning -> assertThat(warning)
        .contains(AgenticAdHocParseListener.ON_LIMIT + " is left out"));
  }

  /**
   * What follows a tool left out on a parse again is not judged by the reads of toolCall it
   * makes: the tool's declarations are gone with it, and the warning would only mislead.
   */
  @Test
  public void theFollowersOfALeftOutToolAreNotJudged(TestInfo test) {
    ProcessEngine off = engineWithoutTheAgent(test);
    deploy(off, process("agenticScope", adHoc(connector(MESSAGE), "<userTask id='arbeit' />"
        + "<userTask id='gesperrt'><extensionElements>"
        + properties(AgenticToolCatalogParser.BLOCKED_WHILE_OTHERS_RUN_PROPERTY, "ja")
        + "<camunda:inputOutput><camunda:inputParameter name='x'>"
        + "${toolParameter(toolCall.x)}</camunda:inputParameter></camunda:inputOutput>"
        + "</extensionElements></userTask>"
        + "<sequenceFlow id='weiter' sourceRef='gesperrt' targetRef='danach' />"
        + "<userTask id='danach'><extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='y'>${toolCall.x}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements></userTask>")));
    String definitionId = off.getRepositoryService().createProcessDefinitionQuery()
        .singleResult().getId();
    ProcessEngine on = engineWithTheAgent(test);
    recordWarnings();

    tools("arbeit").accept(scopeOf(on, definitionId, "adHoc"));

    assertThat(warnings()).anySatisfy(warning -> assertThat(warning)
        .contains("so the tool 'gesperrt' is left out of the agent's catalogue."));
    assertThat(warnings()).noneSatisfy(warning -> assertThat(warning).contains("'danach'"));
  }

  /**
   * An id too long for a conversation that continues: no instance of it ever starts, since the
   * engine's activity instance id holds at most 64 characters, but the model loads.
   */
  @Test
  public void aPerActivityScopeWithATooLongIdLoadsWithoutTheAgent(TestInfo test) {
    int longest = 255 - AgentConnectorConstants.AGENT_CONNECTOR_MEMORY_PREFIX.length()
        - "adhoc-".length() - 64 - 1;
    String id = "adHoc" + new String(new char[longest - 4]).replace('\0', 'x');
    ProcessEngine off = engineWithoutTheAgent(test);
    String model = process("agenticScope", adHoc(id,
        properties(AgenticAdHocParseListener.CONVERSATION, AdHocConversation.PER_ACTIVITY)
            + connector(MESSAGE), "<userTask id='arbeit' />"));
    deploy(off, model);
    String definitionId = off.getRepositoryService().createProcessDefinitionQuery()
        .singleResult().getId();
    ProcessEngine on = engineWithTheAgent(test);
    recordWarnings();

    withoutTheAgent().accept(scopeOf(on, definitionId, id));

    assertThat(warnings()).anySatisfy(warning -> assertThat(warning)
        .contains("perActivity conversation requires an activity id of at most " + longest)
        .contains("so '" + id + "' loads as it was deployed, without the agent."));
    assertThatThrownBy(() -> deploy(on, model)).as("a new deployment is still refused")
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("perActivity conversation requires an activity id of at most");
  }

  /**
   * A scope's own retry cycle that cannot be read gives way, on a parse again, to the next in the
   * order: the engine's, before the default.
   */
  @Test
  public void aRetryCycleThatCannotBeReadGivesWayToTheEngines(TestInfo test) {
    ProcessEngine off = engineWithoutTheAgent(test);
    deploy(off, process("agenticScope", adHoc(
        "<camunda:failedJobRetryTimeCycle>sometimes</camunda:failedJobRetryTimeCycle>"
            + connector(MESSAGE), "<userTask id='arbeit' />")));
    String definitionId = off.getRepositoryService().createProcessDefinitionQuery()
        .singleResult().getId();
    ProcessEngine on = engine(test, "on", new ConnectProcessEnginePlugin(),
        configuration -> configuration.setFailedJobRetryTimeCycle("R5/PT1M"));
    recordWarnings();

    ActivityImpl scope = scopeOf(on, definitionId, "adHoc");

    assertThat(scope.getProperties().get(AgenticTurnRetry.RETRY_CONFIGURATION).getRetries())
        .isEqualTo(5);
    assertThat(warnings()).anySatisfy(warning -> assertThat(warning)
        .contains("'sometimes' is not a retry cycle for its turns")
        .contains("so the turns of 'adHoc' are retried by the cycle 'R5/PT1M'."));
  }

  /**
   * The engine's cycle, as the fallback of a scope's cycle that cannot be read, cannot be read
   * either: the default retries the turns, and the expression's error blocks nothing.
   */
  @Test
  public void anEnginesCycleThatCannotBeReadGivesWayToTheDefault(TestInfo test) {
    ProcessEngine off = engineWithoutTheAgent(test);
    deploy(off, process("agenticScope", adHoc(
        "<camunda:failedJobRetryTimeCycle>sometimes</camunda:failedJobRetryTimeCycle>"
            + connector(MESSAGE), "<userTask id='arbeit' />")));
    String definitionId = off.getRepositoryService().createProcessDefinitionQuery()
        .singleResult().getId();
    ProcessEngine on = engine(test, "on", new ConnectProcessEnginePlugin(),
        configuration -> configuration.setFailedJobRetryTimeCycle("${unbekannt()}"));
    recordWarnings();

    ActivityImpl scope = scopeOf(on, definitionId, "adHoc");

    assertThat(scope.getProperties().get(AgenticTurnRetry.RETRY_CONFIGURATION).getRetries())
        .isEqualTo(3);
    assertThat(warnings()).anySatisfy(warning -> assertThat(warning)
        .contains("'sometimes' is not a retry cycle for its turns")
        .contains("so the turns of 'adHoc' are retried by the cycle '"
            + AgenticTurnRetry.DEFAULT_RETRY_TIME_CYCLE + "'."));
  }

  /**
   * The engine's cycle is its configuration, not the model's: one that cannot be read refuses no
   * deployment. It is logged, and the default retries the turns.
   */
  @Test
  public void anEnginesCycleThatCannotBeReadRefusesNoModel(TestInfo test) {
    ProcessEngine on = engine(test, "on", new ConnectProcessEnginePlugin(),
        configuration -> configuration.setFailedJobRetryTimeCycle("${unbekannt()}"));
    recordWarnings();

    deploy(on, process("agenticScope", adHoc(connector(MESSAGE), "<userTask id='arbeit' />")));
    String definitionId = on.getRepositoryService().createProcessDefinitionQuery()
        .singleResult().getId();

    assertThat(scopeOf(on, definitionId, "adHoc").getProperties()
        .get(AgenticTurnRetry.RETRY_CONFIGURATION).getRetries()).isEqualTo(3);
    assertThat(warnings()).anySatisfy(warning -> assertThat(warning)
        .contains("The engine's failedJobRetryTimeCycle '${unbekannt()}' is not a retry cycle, so"
            + " the turns of 'adHoc' are retried by the cycle '"
            + AgenticTurnRetry.DEFAULT_RETRY_TIME_CYCLE + "'."));
  }

  /**
   * A nested agent that loads without its agent, as a parse again leaves it, is no tool of the
   * agent around it (CIB7-2367): started, nothing would end it, and the outer agent could not end
   * its own scope while it waits. So with the nested scope a multi-instance.
   */
  @ParameterizedTest(name = "multi-instance: {0}")
  @ValueSource(booleans = { false, true })
  public void aNestedAgentWithoutItsAgentIsNoTool(boolean multiInstance, TestInfo test) {
    loadsOnceTheAgentIsOn(test,
        adHoc(connector(MESSAGE), "<userTask id='arbeit' />"
            + adHoc("innen", connector("<camunda:inputParameter name='instruction'>Be brief."
                + "</camunda:inputParameter>"),
                (multiInstance ? "<multiInstanceLoopCharacteristics isSequential='true'>"
                    + "<loopCardinality>1</loopCardinality></multiInstanceLoopCharacteristics>"
                    : "")
                + "<userTask id='innenArbeit' />")),
        "its child 'innen' asks for an AI agent but has none",
        "the tool 'innen' is left out of the agent's catalogue",
        "an agentic scope needs a task for its agent", tools("arbeit"));
    assertThat(warnings()).anySatisfy(warning -> assertThat(warning)
        .contains("an agentic scope needs a task for its agent")
        .contains("so 'innen' loads as it was deployed, without the agent."));
  }

  /**
   * Deploys the scope with the agent off and starts two instances; with the agent on, the model
   * loads and logs what a deployment would refuse, one instance is read and goes on, the other is
   * deleted, and a new deployment is still refused.
   *
   * @param refusedOnDeployment what that refusal says, when it differs from the warning
   */
  protected void loadsOnceTheAgentIsOn(TestInfo test, String scope, String refused,
      String instead, String refusedOnDeployment, Consumer<ActivityImpl> loaded) {
    String scopeId = scopeId(scope);
    ProcessEngine off = engineWithoutTheAgent(test);
    String model = process("agenticScope", scope);
    deploy(off, model);
    String definitionId = off.getRepositoryService().createProcessDefinitionQuery()
        .singleResult().getId();
    ProcessInstance goesOn = off.getRuntimeService().startProcessInstanceByKey("agenticScope");
    ProcessInstance deleted = off.getRuntimeService().startProcessInstanceByKey("agenticScope");
    ProcessEngine on = engineWithTheAgent(test);
    recordWarnings();

    ActivityInstance tree = on.getRuntimeService().getActivityInstance(goesOn.getId());

    assertThat(tree.getActivityInstances(scopeId)).as("the tree of an instance is read")
        .hasSize(1);
    loaded.accept(scopeOf(on, definitionId, scopeId));
    Task task = on.getTaskService().createTaskQuery().processInstanceId(goesOn.getId())
        .singleResult();
    if (task != null) {
      on.getTaskService().complete(task.getId());
    } else {
      on.getRuntimeService().completeAdHocSubProcess(
          tree.getActivityInstances(scopeId)[0].getExecutionIds()[0]);
    }
    assertThat(on.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(goesOn.getId()).count()).as("the instance went on, and ended").isZero();
    on.getRuntimeService().deleteProcessInstance(deleted.getId(), "CIB7-2367");
    assertThat(on.getRuntimeService().createProcessInstanceQuery()
        .processInstanceId(deleted.getId()).count()).as("the instance is deleted").isZero();
    assertThat(warnings()).anySatisfy(warning -> assertThat(warning)
        .contains("Process 'agenticScope' is parsed again, not deployed, and a deployment of it"
            + " would now be refused")
        .contains(refused)
        .contains("so " + instead + ". Deploy a corrected version."));
    assertThatThrownBy(() -> deploy(on, model)).as("a new deployment is still refused")
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining(refusedOnDeployment);
  }

  static Stream<Arguments> refusals() {
    String plain = "'adHoc' loads as it was deployed, without the agent";
    Consumer<ActivityImpl> withoutTheAgent = withoutTheAgent();
    String leftOutOfOnLimit = AgenticAdHocParseListener.ON_LIMIT + " is left out of 'adHoc', and"
        + " a limit ends it as any turn would";
    return Stream.of(
        Arguments.of("the agent on a sub process",
            "<subProcess id='adHoc'><extensionElements>" + connector(MESSAGE)
                + "</extensionElements><startEvent id='innen' />"
                + "<sequenceFlow id='i1' sourceRef='innen' targetRef='arbeit' />"
                + "<userTask id='arbeit' />"
                + "<sequenceFlow id='i2' sourceRef='arbeit' targetRef='aus' />"
                + "<endEvent id='aus' /></subProcess>",
            "the AI agent is only valid on an adHocSubProcess", plain, withoutTheAgent),
        Arguments.of("the limits without the connector",
            adHoc(properties(AgenticAdHocParseListener.MAX_TURNS, "5"),
                "<userTask id='arbeit' />"),
            "but carries no connector 'cibseven-ai-agent'", plain, withoutTheAgent),
        Arguments.of("the template without the connector",
            "<adHocSubProcess id='adHoc' camunda:modelerTemplate='"
                + AgenticAdHocParseListener.AD_HOC_TEMPLATE_ID + "'>"
                + "<userTask id='arbeit' /></adHocSubProcess>",
            "is marked as an AI agent (the template", plain, withoutTheAgent),
        Arguments.of("a total turn limit without perActivity",
            adHoc(properties(AgenticAdHocParseListener.MAX_TURNS_TOTAL, "10")
                + connector(MESSAGE), "<userTask id='arbeit' />"),
            AgenticAdHocParseListener.MAX_TURNS_TOTAL + " bounds the turns of every entry"
                + " together", plain, withoutTheAgent),
        Arguments.of("a total turn limit that is not positive",
            adHoc(properties(AgenticAdHocParseListener.CONVERSATION,
                AdHocConversation.PER_ACTIVITY, AgenticAdHocParseListener.MAX_TURNS_TOTAL, "0")
                + connector(MESSAGE), "<userTask id='arbeit' />"),
            AgenticAdHocParseListener.MAX_TURNS_TOTAL + " must be a positive integer, but is '0'",
            plain, withoutTheAgent),
        Arguments.of("perActivity on a parallel multi-instance",
            adHoc(properties(AgenticAdHocParseListener.CONVERSATION,
                AdHocConversation.PER_ACTIVITY) + connector(MESSAGE),
                "<multiInstanceLoopCharacteristics isSequential='false'>"
                    + "<loopCardinality>1</loopCardinality></multiInstanceLoopCharacteristics>"
                    + "<userTask id='arbeit' />"),
            "is not supported on a parallel multi-instance scope", plain, withoutTheAgent),
        Arguments.of("a memory window that is no number",
            adHoc(properties(AgenticAdHocParseListener.CONVERSATION,
                AdHocConversation.PER_ACTIVITY)
                + connector(MESSAGE + "<camunda:inputParameter name='chatMemoryMaxMessages'>viele"
                    + "</camunda:inputParameter>"), "<userTask id='arbeit' />"),
            "chatMemoryMaxMessages must be a positive integer", plain, withoutTheAgent),
        Arguments.of("an empty message",
            adHoc(connector("<camunda:inputParameter name='message' />"),
                "<userTask id='arbeit' />"),
            "the input parameter 'message' of the connector 'cibseven-ai-agent' is empty", plain,
            withoutTheAgent),
        Arguments.of("useChatMemory",
            adHoc(connector(MESSAGE + "<camunda:inputParameter name='useChatMemory'>true"
                + "</camunda:inputParameter>"), "<userTask id='arbeit' />"),
            "the input parameter 'useChatMemory' of the connector 'cibseven-ai-agent' is set by"
                + " the agentic loop itself", plain, withoutTheAgent),
        Arguments.of("memoryId",
            adHoc(connector(MESSAGE + "<camunda:inputParameter name='memoryId'>m"
                + "</camunda:inputParameter>"), "<userTask id='arbeit' />"),
            "the input parameter 'memoryId' of the connector 'cibseven-ai-agent' is set by the"
                + " agentic loop itself", plain, withoutTheAgent),
        Arguments.of("the task on the scope",
            adHoc("<camunda:inputOutput>" + MESSAGE + "</camunda:inputOutput>"
                + connector(MESSAGE), "<userTask id='arbeit' />"),
            "'message' is an input parameter of the scope", plain, withoutTheAgent),
        Arguments.of("an unknown conversation",
            adHoc(properties(AgenticAdHocParseListener.CONVERSATION, "everEntry")
                + connector(MESSAGE), "<userTask id='arbeit' />"),
            "'everEntry' is not a value of " + AgenticAdHocParseListener.CONVERSATION, plain,
            withoutTheAgent),
        Arguments.of("an undersized memory window",
            adHoc(properties(AgenticAdHocParseListener.CONVERSATION,
                AdHocConversation.PER_ACTIVITY)
                + connector(MESSAGE + "<camunda:inputParameter name='chatMemoryMaxMessages'>1"
                    + "</camunda:inputParameter>"), "<userTask id='arbeit' />"),
            "chatMemoryMaxMessages must be at least", plain, withoutTheAgent),
        Arguments.of("an own completion condition",
            adHoc(connector(MESSAGE), "<userTask id='arbeit' />"
                + "<completionCondition>${false}</completionCondition>"),
            "must not carry a completionCondition", plain, withoutTheAgent),
        Arguments.of("no startable child",
            adHoc(connector(MESSAGE), "<userTask id='a' /><userTask id='b' />"
                + "<sequenceFlow id='ab' sourceRef='a' targetRef='b' />"
                + "<sequenceFlow id='ba' sourceRef='b' targetRef='a' />"),
            "needs at least one directly startable child", plain, withoutTheAgent),
        Arguments.of("no message",
            adHoc(connector("<camunda:inputParameter name='instruction'>Be brief."
                + "</camunda:inputParameter>"), "<userTask id='arbeit' />"),
            "an agentic scope needs a task for its agent", plain, withoutTheAgent),
        Arguments.of("toolClasses",
            adHoc(connector(MESSAGE + "<camunda:inputParameter name='toolClasses'>x.Y"
                + "</camunda:inputParameter>"), "<userTask id='arbeit' />"),
            "the input parameter 'toolClasses'", plain, withoutTheAgent),
        Arguments.of("a child id that cannot be a tool name",
            adHoc(connector(MESSAGE), "<userTask id='arbeit' /><userTask id='pruefen.alt' />"),
            "the id 'pruefen.alt' cannot be a tool name",
            "the tool 'pruefen.alt' is left out of the agent's catalogue", tools("arbeit")),
        Arguments.of("a child named as the built-in tool",
            adHoc(connector(MESSAGE), "<userTask id='arbeit' /><userTask id='completeScope' />"),
            "the child 'completeScope' collides with the built-in tool",
            "the tool 'completeScope' is left out of the agent's catalogue", tools("arbeit")),
        Arguments.of("a bad adHocBlockedWhileOthersRun",
            adHoc(connector(MESSAGE), "<userTask id='arbeit' /><userTask id='gesperrt'>"
                + "<extensionElements>" + properties(
                    AgenticToolCatalogParser.BLOCKED_WHILE_OTHERS_RUN_PROPERTY, "ja")
                + "</extensionElements></userTask>"),
            "adHocBlockedWhileOthersRun is 'ja', but must be 'true' or 'false'",
            "the tool 'gesperrt' is left out of the agent's catalogue", tools("arbeit")),
        Arguments.of("an unknown onLimit",
            adHoc(properties(AgenticAdHocParseListener.ON_LIMIT, "fail") + connector(MESSAGE),
                "<userTask id='arbeit' />"),
            "'fail' is not a value of " + AgenticAdHocParseListener.ON_LIMIT, leftOutOfOnLimit,
            withoutOnLimit()),
        Arguments.of("an onLimit error nothing catches",
            adHoc(properties(AgenticAdHocParseListener.ON_LIMIT, "error") + connector(MESSAGE),
                "<userTask id='arbeit' />"),
            "no boundary error event on it catches", leftOutOfOnLimit, withoutOnLimit()),
        Arguments.of("an onLimit error an event sub process inside catches first",
            adHoc(properties(AgenticAdHocParseListener.ON_LIMIT, "error") + connector(MESSAGE),
                "<userTask id='arbeit' />"
                    + "<subProcess id='fehler' triggeredByEvent='true'>"
                    + "<startEvent id='fehlerStart'><errorEventDefinition /></startEvent>"
                    + "<sequenceFlow id='fe' sourceRef='fehlerStart' targetRef='fehlerEnde' />"
                    + "<endEvent id='fehlerEnde' /></subProcess>")
                + "<boundaryEvent id='grenze' attachedToRef='adHoc'><errorEventDefinition />"
                + "</boundaryEvent><sequenceFlow id='g1' sourceRef='grenze' targetRef='end' />",
            "the event sub process 'fehler' inside it catches", leftOutOfOnLimit,
            withoutOnLimit()),
        Arguments.of("a message that does not parse",
            adHoc(connector("<camunda:inputParameter name='message'>Handle ${order."
                + "</camunda:inputParameter>"), "<userTask id='arbeit' />"),
            "an expression of the connector 'cibseven-ai-agent' cannot be read", plain,
            withoutTheAgent),
        Arguments.of("an unknown function in the connector",
            adHoc(connector("<camunda:inputParameter name='message'>${unbekannt(1)}"
                + "</camunda:inputParameter>"), "<userTask id='arbeit' />"),
            "an expression of the connector 'cibseven-ai-agent' cannot be read", plain,
            withoutTheAgent),
        Arguments.of("a retry cycle expression that does not parse",
            adHoc("<camunda:failedJobRetryTimeCycle>${unbekannt()}"
                + "</camunda:failedJobRetryTimeCycle>" + connector(MESSAGE),
                "<userTask id='arbeit' />"),
            "'${unbekannt()}' is not a retry cycle for its turns",
            "the turns of 'adHoc' are retried by the cycle '"
                + AgenticTurnRetry.DEFAULT_RETRY_TIME_CYCLE + "'",
            (Consumer<ActivityImpl>) scope -> assertThat(scope.getProperties()
                .get(AgenticTurnRetry.RETRY_CONFIGURATION).getRetries()).as("the default's")
                .isEqualTo(3)),
        Arguments.of("a retry cycle that cannot be read",
            adHoc("<camunda:failedJobRetryTimeCycle>sometimes</camunda:failedJobRetryTimeCycle>"
                + connector(MESSAGE), "<userTask id='arbeit' />"),
            "'sometimes' is not a retry cycle for its turns",
            "the turns of 'adHoc' are retried by the cycle '"
                + AgenticTurnRetry.DEFAULT_RETRY_TIME_CYCLE + "'",
            (Consumer<ActivityImpl>) scope -> assertThat(scope.getProperties()
                .get(AgenticTurnRetry.RETRY_CONFIGURATION).getRetries()).as("the default's")
                .isEqualTo(3)));
  }

  /** The scope loads without the agent, as it was deployed. */
  protected static Consumer<ActivityImpl> withoutTheAgent() {
    return scope -> {
      assertThat(scope.getProperties().get(AgenticAdHocParseListener.AGENT_CONNECTOR_IO))
          .as("no agent on the scope").isNull();
      assertThat(scope.getProperties().get(AdHocToolDescriptor.CATALOG)).isNull();
    };
  }

  /** The scope stays agentic, with only these tools in its catalogue. */
  protected static Consumer<ActivityImpl> tools(String... ids) {
    return scope -> assertThat(scope.getProperties().get(AdHocToolDescriptor.CATALOG))
        .extracting(AdHocToolDescriptor::getActivityId).containsExactly(ids);
  }

  /** The scope stays agentic, and its settings no longer carry onLimit. */
  protected static Consumer<ActivityImpl> withoutOnLimit() {
    return scope -> {
      assertThat(scope.getProperties().get(AdHocToolDescriptor.CATALOG)).isNotNull();
      Map<String, String> properties =
          scope.getProperties().get(BpmnProperties.EXTENSION_PROPERTIES);
      assertThat(properties).doesNotContainKey(AgenticAdHocParseListener.ON_LIMIT);
    };
  }

  protected static String adHoc(String extensions, String children) {
    return adHoc("adHoc", extensions, children);
  }

  protected static String adHoc(String id, String extensions, String children) {
    return "<adHocSubProcess id='" + id + "'><extensionElements>" + extensions
        + "</extensionElements>" + children + "</adHocSubProcess>";
  }

  protected static String connector(String inputs) {
    return "<camunda:connector><camunda:connectorId>cibseven-ai-agent</camunda:connectorId>"
        + "<camunda:inputOutput>" + inputs + "</camunda:inputOutput></camunda:connector>";
  }

  /** {@code camunda:properties} of the names and values, in pairs. */
  protected static String properties(String... namesAndValues) {
    StringBuilder properties = new StringBuilder("<camunda:properties>");
    for (int i = 0; i < namesAndValues.length; i += 2) {
      properties.append("<camunda:property name='").append(namesAndValues[i])
          .append("' value='").append(namesAndValues[i + 1]).append("' />");
    }
    return properties.append("</camunda:properties>").toString();
  }

  /** The id of the scope: the first one in its XML. */
  protected static String scopeId(String scope) {
    int start = scope.indexOf("id='") + "id='".length();
    return scope.substring(start, scope.indexOf('\'', start));
  }

  /** The scope as the engine parses the definition, from its cache or again. */
  protected static ActivityImpl scopeOf(ProcessEngine engine, String definitionId,
      String scopeId) {
    ProcessDefinitionImpl definition = (ProcessDefinitionImpl) ((RepositoryServiceImpl) engine
        .getRepositoryService()).getDeployedProcessDefinition(definitionId);
    return (ActivityImpl) definition.findActivity(scopeId);
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
    return engine(test, name, plugin, configuration -> { });
  }

  /** @param configured sets what this engine needs beyond the others */
  protected ProcessEngine engine(TestInfo test, String name, ProcessEnginePlugin plugin,
      Consumer<StandaloneInMemProcessEngineConfiguration> configured) {
    if (database == null) {
      database = "agentic-reparse-" + test.getTestMethod().get().getName() + "-"
          + DATABASES.incrementAndGet();
    }
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName(database + "-" + name);
    configuration.setJdbcUrl("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
    configuration.setDatabaseSchemaUpdate(ProcessEngineConfigurationImpl.DB_SCHEMA_UPDATE_TRUE);
    configuration.setJobExecutorActivate(false);
    configuration.setEnforceHistoryTimeToLive(false);
    configuration.setProcessEnginePlugins(Collections.singletonList(plugin));
    configured.accept(configuration);
    ProcessEngine engine = configuration.buildProcessEngine();
    engines.add(engine);
    return engine;
  }

  protected void recordWarnings() {
    log = new ListAppender<ILoggingEvent>();
    log.start();
    for (Class<?> logging : LOGGING) {
      ((Logger) LoggerFactory.getLogger(logging)).addAppender(log);
    }
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
    String first = scopeId(scope);
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
